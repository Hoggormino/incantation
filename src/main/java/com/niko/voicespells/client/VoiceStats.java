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
    /** Set when the most recent {@link #tryLoad()} could not read or could not make sense of
     *  stats.dat. While true, {@link #trySave()} is a no-op.
     *
     *  <p>Without this, an unreadable stats.dat was silently upgraded into a destroyed one:
     *  the read threw, the warning scrolled past, LOADED had already been CAS'd true so nothing
     *  ever retried, and in-memory state stayed at zero. The next save — five casts later, or
     *  the moment the More screen computed the spell of the day, or the shutdown hook — then
     *  wrote those zeros over the real file with TRUNCATE_EXISTING. A transient Windows file
     *  lock from antivirus or OneDrive was enough to wipe a player's lifetime totals, unlock
     *  state and codex history. {@code Phrasebook} already guarded against exactly this; the
     *  guard was simply never applied here. */
    private static volatile boolean loadFailed = false;
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
            // Number of keys the parser actually RECOGNISED. This, not the values, is what
            // tells us whether the file was readable — see the check after the loop.
            int keysRead = 0;
            synchronized (VoiceStats.class) {
                for (String ln : lines) {
                    int eq = ln.indexOf('=');
                    if (eq <= 0) continue;
                    String key = ln.substring(0, eq).trim();
                    String val = ln.substring(eq + 1).trim();
                    try {
                        switch (key) {
                            case "totalCasts"    -> { totalCasts = Integer.parseInt(val); keysRead++; }
                            case "longestStreak" -> { longestStreak = Integer.parseInt(val); keysRead++; }
                            case "firstCastMs"   -> { firstCastMs = Long.parseLong(val); keysRead++; }
                            case "lastCastMs"    -> { lastCastMs = Long.parseLong(val); keysRead++; }
                            case "earned"        -> { /* legacy field — advancements handle this now, ignore */ }
                            case "sotdDayEpoch"  -> { sotdDayEpoch = Long.parseLong(val); keysRead++; }
                            case "sotdSpellId"   -> { sotdSpellId  = val; keysRead++; }
                            case "sotdCasts"     -> { sotdCasts    = Integer.parseInt(val); keysRead++; }
                            case "sotdStreak"    -> { sotdStreak   = Integer.parseInt(val); keysRead++; }
                            case "sotdLastDay"   -> { sotdLastCompletedDay = Long.parseLong(val); keysRead++; }
                            case "wizardSeen"    -> { wizardSeen = Boolean.parseBoolean(val); keysRead++; }
                            default -> {
                                if (key.startsWith("spell.")) {
                                    String spellId = key.substring("spell.".length());
                                    COUNTS.put(spellId, Integer.parseInt(val));
                                    keysRead++;
                                } else if (key.startsWith("lastms.")) {
                                    String spellId = key.substring("lastms.".length());
                                    LAST_CAST_MS.put(spellId, Long.parseLong(val));
                                    keysRead++;
                                }
                            }
                        }
                    } catch (NumberFormatException ignored) {}
                }
            }
            // A file that exists and has content but yields nothing the parser RECOGNISED is
            // truncated or corrupt. It does not throw, so without this check it would parse
            // cleanly into defaults and the next save would cement the loss.
            //
            // Judged on keysRead, never on the values. The first version of this guard asked
            // whether the stats were all zero — which is exactly what trySave() writes for a
            // player who has not cast yet and has not finished the wizard, a file the mod
            // produces itself on every fresh install. It then declared that file corrupt,
            // latched loadFailed for the JVM, and suppressed every save; the file on disk never
            // changed, so the same verdict was reached on every subsequent launch. The guard
            // turned a working install into permanent, silent, self-reinforcing data loss.
            // A zero-stat file is perfectly valid; an unparseable one is not.
            if (!lines.isEmpty() && keysRead == 0) {
                loadFailed = true;
                VoiceSpells.LOGGER.warn(
                    "stats.dat has {} line(s) but no readable stats — treating as corrupt and "
                    + "preserving the file rather than overwriting it", lines.size());
                backupUnreadable(p);
                return;
            }
            loadFailed = false;
            VoiceSpells.LOGGER.info("VoiceStats loaded: {} total casts across {} distinct spells",
                totalCasts, COUNTS.size());
        } catch (IOException | RuntimeException e) {
            // RuntimeException too: MalformedInputException surfaces as an unchecked
            // CharacterCodingException wrapper on some JDKs, and a single bad byte must not be
            // the difference between "stats preserved" and "stats destroyed".
            loadFailed = true;
            VoiceSpells.LOGGER.warn("Failed to load stats.dat ({}) — stats will NOT be saved this "
                + "session so the existing file is preserved", e.toString());
            backupUnreadable(p);
        }
    }

    /**
     * Guarantee a copy of {@code p} exists somewhere before we are allowed to overwrite it.
     *
     * <p>Unlike {@link #backupUnreadable}, this will not give up when stats.dat.bak is already
     * taken — it walks to stats.dat.bak2, .bak3 and so on, because the whole point is to be
     * able to answer "yes, the bytes are safe" truthfully. Returns false if no copy could be
     * made, in which case the caller must not write.
     */
    private static boolean ensureBackedUp(Path p) {
        try {
            if (!Files.exists(p)) return true; // nothing to lose
            for (int i = 0; i < 20; i++) {
                Path bak = p.resolveSibling("stats.dat.bak" + (i == 0 ? "" : String.valueOf(i + 1)));
                if (Files.exists(bak)) continue;
                Files.copy(p, bak);
                VoiceSpells.LOGGER.warn("Unreadable stats copied to {} before overwrite", bak);
                return true;
            }
            return false; // 20 backups already sitting there — stop making more, stop writing
        } catch (Throwable t) {
            VoiceSpells.LOGGER.warn("Could not back up stats before overwrite: {}", t.toString());
            return false;
        }
    }

    /** Copy an unreadable stats.dat aside once, so the player has something to hand back if
     *  they ask for help. Best-effort and never overwrites an existing backup. */
    private static void backupUnreadable(Path p) {
        try {
            Path bak = p.resolveSibling("stats.dat.bak");
            if (!Files.exists(bak) && Files.exists(p)) {
                Files.copy(p, bak);
                VoiceSpells.LOGGER.warn("Copied unreadable stats to {}", bak);
            }
        } catch (Throwable ignored) { /* diagnostics only */ }
    }

    /** Save now. Called from the debounced cast path and from the shutdown hook. */
    public static synchronized void trySave() {
        ensureLoaded();
        // Escape hatch. If this session has accumulated real progress, write it even though the
        // load failed: the original file was already copied to stats.dat.bak by
        // backupUnreadable(), so nothing unrecoverable is being overwritten, and refusing to
        // save would discard the whole session instead. Without this, anyone already carrying a
        // file poisoned by the previous version of the guard above would stay frozen forever.
        if (loadFailed && totalCasts > 0) {
            // Only overwrite an unreadable file once we have PROVEN a copy of it exists.
            //
            // This previously claimed in the log that "the unreadable original is preserved as
            // stats.dat.bak" and then wrote regardless. backupUnreadable() copies only when no
            // .bak is already there, so on a second bad launch the current file was never
            // backed up — and the hatch overwrote a player's lifetime stats while telling them
            // it had been saved. A reassuring log line next to unrecoverable data loss is worse
            // than no log line.
            if (!ensureBackedUp(statsFile())) {
                VoiceSpells.LOGGER.warn("Stats load failed and the file could not be backed up; "
                    + "refusing to overwrite it. This session's {} cast(s) will not be saved.",
                    totalCasts);
                return;
            }
            VoiceSpells.LOGGER.info("Stats load failed earlier, but this session has {} cast(s) — "
                + "saving anyway; the unreadable original was copied aside first.", totalCasts);
        } else if (loadFailed) {
            VoiceSpells.LOGGER.debug("Skipping stats save — last load failed, preserving file as-is");
            return;
        }
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
            // Write to a sibling temp file and move it into place, rather than truncating the
            // real file and writing into it. A truncate-then-write is a window in which the
            // stats file is empty on disk, and the two callers most likely to be interrupted
            // are the shutdown hook and the flush that fires every fifth cast — i.e. exactly
            // when the process may be going away. A crash inside that window left a 0-byte
            // stats.dat, which then parsed happily into defaults on next launch.
            Path tmp = p.resolveSibling("stats.dat.tmp");
            Files.writeString(tmp, sb.toString(), StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            try {
                Files.move(tmp, p, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException amnse) {
                // Some filesystems (and some network shares) cannot do it atomically.
                // A non-atomic replace is still strictly better than truncate-in-place.
                Files.move(tmp, p, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            // The load-failure state ends HERE, on the first successful write. Leaving it set
            // sent every later save back through the escape hatch, and ensureBackedUp() copies
            // the file aside each time it runs — so save #2 onward "backed up" the file THIS
            // METHOD had just written, not the player's damaged original. The result was up to
            // twenty near-identical stats.dat.bakN files in the config directory and then, once
            // the twentieth existed, ensureBackedUp() returning false and stats silently ceasing
            // to save for the rest of the session. One backup of the original is the entire
            // point of the hatch; after that the file is ours and there is nothing left to
            // protect.
            loadFailed = false;
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
