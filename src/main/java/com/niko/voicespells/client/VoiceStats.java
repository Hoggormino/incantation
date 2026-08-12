package com.niko.voicespells.client;

import com.niko.voicespells.VoiceSpells;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
// (Set/HashSet retained for distinctSchoolsCast computation below.)

/**
 * Persistent voice-cast stats: total casts, per-spell counts, schools used, best streak,
 * first-cast / last-cast timestamps. Drives the Voice Codex screen and the achievement
 * milestone toasts.
 *
 * Storage is a plain key=value file at {@code run/config/voicespells/stats.dat} (one
 * spell-count entry per line under {@code spell.}). Hand-editable and not coupled to any
 * particular library. Writes are debounced — at most one disk hit per N casts and one on
 * client shutdown.
 */
public final class VoiceStats {
    private VoiceStats() {}

    // ---- in-memory state ---------------------------------------------------
    private static final Map<String, Integer> COUNTS = new HashMap<>();
    /** Per-spell last-cast timestamp (epoch ms). Surfaced in the spell tooltip so the player
     *  can see "you last cast this 3m ago" inline. */
    private static final Map<String, Long>    LAST_CAST_MS = new HashMap<>();

    /** Spell-of-the-day challenge state — computed deterministically from the epoch day so all
     *  players using the mod see the same suggestion on the same date. {@link #sotdCasts}
     *  resets when the date rolls over. */
    private static long   sotdDayEpoch = 0L;
    private static String sotdSpellId  = "";
    private static int    sotdCasts    = 0;
    /** Casts of today's challenge spell needed to "complete" it (cosmetic — just a tracker). */
    public static final int SOTD_TARGET = 3;
    private static int  totalCasts    = 0;
    private static int  longestStreak = 0;
    private static long firstCastMs   = 0L;
    private static long lastCastMs    = 0L;

    private static final AtomicBoolean LOADED = new AtomicBoolean(false);
    private static int writesSinceFlush = 0;
    private static final int FLUSH_EVERY = 5; // disk write every 5 casts

    // ---- accessors ---------------------------------------------------------
    public static synchronized int totalCasts() { ensureLoaded(); return totalCasts; }
    public static synchronized int longestStreak() { ensureLoaded(); return longestStreak; }
    public static synchronized long firstCastMs() { ensureLoaded(); return firstCastMs; }
    public static synchronized long lastCastMs() { ensureLoaded(); return lastCastMs; }
    public static synchronized int castCount(String spellId) {
        ensureLoaded();
        return COUNTS.getOrDefault(spellId, 0);
    }
    public static synchronized long lastCastMsFor(String spellId) {
        ensureLoaded();
        return LAST_CAST_MS.getOrDefault(spellId, 0L);
    }

    /** Returns the spell id designated as "today's spell". Stable for the calendar day, then
     *  rolls to a new pick at midnight. Empty when the index isn't ready yet. */
    public static synchronized String spellOfTheDayId() {
        ensureLoaded();
        long today = System.currentTimeMillis() / 86_400_000L;
        if (today != sotdDayEpoch || sotdSpellId.isEmpty()) {
            java.util.List<com.niko.voicespells.spells.SpellIndex.SpellRow> all =
                com.niko.voicespells.spells.SpellIndex.allSpells();
            if (all.isEmpty()) return "";
            java.util.Random rnd = new java.util.Random(today);
            sotdSpellId  = all.get(rnd.nextInt(all.size())).id();
            sotdCasts    = 0;
            sotdDayEpoch = today;
            trySave();
        }
        return sotdSpellId;
    }

    /** Casts of today's challenge spell since the last day-roll. */
    public static synchronized int spellOfTheDayCasts() { ensureLoaded(); return sotdCasts; }

    /** SOTD challenge streak — number of consecutive days the player has completed the
     *  daily challenge. Updated on disk when the day rolls and the previous day's count
     *  reached the target; resets when a day is skipped. */
    private static int  sotdStreak             = 0;
    private static long sotdLastCompletedDay   = 0L;
    public static synchronized int sotdStreak() { ensureLoaded(); return sotdStreak; }

    /** Welcome-wizard "seen" flag, mirrored from the config TOML. We persist it here because
     *  the config TOML write is asynchronous and can be lost if the user exits quickly after
     *  finishing the wizard — stats.dat is written immediately on each call. The wizard treats
     *  the flag as latched: once true on either side, the wizard does not re-pop. */
    private static boolean wizardSeen = false;
    public static synchronized boolean wizardSeen() { ensureLoaded(); return wizardSeen; }
    public static synchronized void markWizardSeen() {
        ensureLoaded();
        if (wizardSeen) return;
        wizardSeen = true;
        trySave();
    }

    /** Rank label derived from total casts. Re-added to drive the chat-tag feature; vanilla
     *  advancements drive the milestone toasts, but this short label is convenient to display
     *  inline (chat prefix, codex header, etc). */
    public static synchronized String currentRank() {
        ensureLoaded();
        if (totalCasts >= 1000) return "Archmage";
        if (totalCasts >= 200)  return "Magus";
        if (totalCasts >= 50)   return "Adept";
        if (totalCasts >= 10)   return "Apprentice";
        if (totalCasts >= 1)    return "Novice";
        return "Unranked";
    }
    public static synchronized List<Map.Entry<String, Integer>> topSpells(int limit) {
        ensureLoaded();
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(COUNTS.entrySet());
        entries.sort(Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue).reversed());
        return entries.subList(0, Math.min(limit, entries.size()));
    }
    public static synchronized int distinctSchoolsCast() {
        ensureLoaded();
        Set<String> set = new HashSet<>();
        for (String id : COUNTS.keySet()) {
            String s = com.niko.voicespells.spells.SpellInfo.of(id).school;
            if (s != null && !s.isEmpty()) set.add(s);
        }
        return set.size();
    }

    // ---- recording ---------------------------------------------------------
    /**
     * Record one successful cast. Called from {@link VoiceController}'s dispatch path. Updates
     * lifetime counters and triggers a debounced disk write. Milestone/rank notifications are
     * no longer fired here — those now go through Minecraft's advancement system, triggered
     * server-side via {@link com.niko.voicespells.advancements.VoiceCastTrigger}.
     */
    public static void recordCast(String spellId, int currentStreak) {
        ensureLoaded();
        synchronized (VoiceStats.class) {
            long now = System.currentTimeMillis();
            if (firstCastMs == 0L) firstCastMs = now;
            lastCastMs = now;
            totalCasts++;
            COUNTS.merge(spellId, 1, Integer::sum);
            LAST_CAST_MS.put(spellId, now);
            if (currentStreak > longestStreak) longestStreak = currentStreak;
            // SOTD challenge tracker — increment if today's pick matches the cast spell.
            String today = sotdSpellId;
            long todayEpoch = System.currentTimeMillis() / 86_400_000L;
            if (todayEpoch == sotdDayEpoch && spellId.equals(today)) {
                sotdCasts++;
                // Streak math: only crediting when the count CROSSES the target so we don't
                // tick the streak repeatedly across the same day.
                if (sotdCasts == SOTD_TARGET) {
                    long lastDay = sotdLastCompletedDay;
                    if (lastDay == 0L || todayEpoch - lastDay > 1) sotdStreak = 1;
                    else if (todayEpoch - lastDay == 1)            sotdStreak++;
                    // todayEpoch == lastDay -> already counted today, no change.
                    sotdLastCompletedDay = todayEpoch;
                }
            }
            writesSinceFlush++;
            if (writesSinceFlush >= FLUSH_EVERY) {
                writesSinceFlush = 0;
                trySave();
            }
        }
    }

    // ---- persistence -------------------------------------------------------
    private static Path statsFile() {
        return Minecraft.getInstance().gameDirectory.toPath()
            .resolve("config").resolve("voicespells").resolve("stats.dat");
    }

    private static void ensureLoaded() {
        if (LOADED.compareAndSet(false, true)) tryLoad();
    }

    private static void tryLoad() {
        Path p = statsFile();
        if (!Files.exists(p)) return;
        try {
            List<String> lines = Files.readAllLines(p);
            synchronized (VoiceStats.class) {
                for (String ln : lines) {
                    int eq = ln.indexOf('=');
                    if (eq <= 0) continue;
                    String key = ln.substring(0, eq).trim();
                    String val = ln.substring(eq + 1).trim();
                    try {
                        switch (key) {
                            case "totalCasts"    -> totalCasts = Integer.parseInt(val);
                            case "longestStreak" -> longestStreak = Integer.parseInt(val);
                            case "firstCastMs"   -> firstCastMs = Long.parseLong(val);
                            case "lastCastMs"    -> lastCastMs = Long.parseLong(val);
                            case "earned"        -> { /* legacy field — advancements handle this now, ignore */ }
                            case "sotdDayEpoch"  -> sotdDayEpoch = Long.parseLong(val);
                            case "sotdSpellId"   -> sotdSpellId  = val;
                            case "sotdCasts"     -> sotdCasts    = Integer.parseInt(val);
                            case "sotdStreak"    -> sotdStreak   = Integer.parseInt(val);
                            case "sotdLastDay"   -> sotdLastCompletedDay = Long.parseLong(val);
                            case "wizardSeen"    -> wizardSeen = Boolean.parseBoolean(val);
                            default -> {
                                if (key.startsWith("spell.")) {
                                    String spellId = key.substring("spell.".length());
                                    COUNTS.put(spellId, Integer.parseInt(val));
                                } else if (key.startsWith("lastms.")) {
                                    String spellId = key.substring("lastms.".length());
                                    LAST_CAST_MS.put(spellId, Long.parseLong(val));
                                }
                            }
                        }
                    } catch (NumberFormatException ignored) {}
                }
            }
            VoiceSpells.LOGGER.info("VoiceStats loaded: {} total casts across {} distinct spells",
                totalCasts, COUNTS.size());
        } catch (IOException e) {
            VoiceSpells.LOGGER.warn("Failed to load stats.dat: {}", e.toString());
        }
    }

    /** Save now. Called from the debounced cast path and from the shutdown hook. */
    public static synchronized void trySave() {
        ensureLoaded();
        Path p = statsFile();
        try {
            Files.createDirectories(p.getParent());
            StringBuilder sb = new StringBuilder();
            sb.append("totalCasts=").append(totalCasts).append('\n');
            sb.append("longestStreak=").append(longestStreak).append('\n');
            sb.append("firstCastMs=").append(firstCastMs).append('\n');
            sb.append("lastCastMs=").append(lastCastMs).append('\n');
            sb.append("sotdDayEpoch=").append(sotdDayEpoch).append('\n');
            sb.append("sotdSpellId=").append(sotdSpellId).append('\n');
            sb.append("sotdCasts=").append(sotdCasts).append('\n');
            sb.append("sotdStreak=").append(sotdStreak).append('\n');
            sb.append("sotdLastDay=").append(sotdLastCompletedDay).append('\n');
            sb.append("wizardSeen=").append(wizardSeen).append('\n');
            for (Map.Entry<String, Integer> e : COUNTS.entrySet()) {
                sb.append("spell.").append(e.getKey()).append('=').append(e.getValue()).append('\n');
            }
            for (Map.Entry<String, Long> e : LAST_CAST_MS.entrySet()) {
                sb.append("lastms.").append(e.getKey()).append('=').append(e.getValue()).append('\n');
            }
            Files.writeString(p, sb.toString(), StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        } catch (IOException e) {
            VoiceSpells.LOGGER.warn("Failed to save stats.dat: {}", e.toString());
        }
    }

    public static String fmtDate(long ms) {
        if (ms <= 0) return "—";
        return java.time.Instant.ofEpochMilli(ms)
            .atZone(java.time.ZoneId.systemDefault())
            .toLocalDate().toString();
    }

    public static String fmtElapsed(long sinceMs) {
        if (sinceMs <= 0) return "—";
        long secs = (System.currentTimeMillis() - sinceMs) / 1000;
        if (secs < 60)    return secs + "s ago";
        if (secs < 3600)  return (secs / 60) + "m ago";
        if (secs < 86400) return (secs / 3600) + "h ago";
        return (secs / 86400) + "d ago";
    }

    /** Read-only snapshot for the codex screen. */
    public static synchronized List<Map.Entry<String, Integer>> snapshotAll() {
        ensureLoaded();
        return new ArrayList<>(COUNTS.entrySet());
    }

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(VoiceStats::trySave, "VoiceSpells-Stats-Save"));
    }
}
