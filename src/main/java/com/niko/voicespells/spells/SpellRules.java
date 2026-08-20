package com.niko.voicespells.spells;

import com.niko.voicespells.VoiceSpells;
import com.niko.voicespells.VoiceSpellsServerConfig;
import net.minecraft.world.entity.player.Player;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Makes voice casting mechanically different from clicking, and can require it.
 *
 * <p>Both behaviours were asked for by players. A server host running a wizard-battle event wanted
 * voice-cast spells to have their own cooldown and power so that speaking is a real choice rather
 * than a second way to press a button; another asked whether spells could be locked behind their
 * incantation, either the first time or permanently.
 *
 * <p>Iron's Spells exposes two usable hooks — {@code SpellCooldownAddedEvent.Pre} (cancellable,
 * with {@code setEffectiveCooldown}) and {@code SpellPreCastEvent} (cancellable) — both with
 * identical signatures on 1.20.1 and 1.21.1, so only the bus registration is version-split. The
 * level bonus does not go through an event at all: {@code ModifySpellLevelEvent} never fires on
 * this path, so {@link SpellCaster} applies it where it already owns the cast level.
 *
 * <p><b>How a cast is known to be ours.</b> The events fire deep inside Iron's Spells and carry no
 * indication of who started the cast, so {@link SpellCaster} stamps the player immediately before
 * it invokes the cast and clears the stamp afterwards. The stamp also expires on its own after a
 * short window, because a cast that throws inside Iron's Spells must not leave a player
 * permanently marked as "currently voice casting" — that would hand them every advantage for free
 * and, with ALWAYS, let them click-cast forever.
 *
 * <p>Everything here is server-side. Cooldown and level are gameplay, so a client cannot be
 * trusted with them, and the incantation rule is only meaningful if the server enforces it.
 */
public final class SpellRules {

    private SpellRules() {}

    /**
     * How long a pending voice cast stays claimable.
     *
     * <p>Long, and deliberately so. The first version cleared the mark the instant
     * {@code attemptInitiateCast} returned, which is far too early: initiation only STARTS a cast,
     * and Iron's Spells applies the cooldown when the cast RESOLVES - immediately for an instant
     * spell, but seconds later for anything with a cast time. The cooldown event therefore fired
     * with no mark set and the feature did nothing at all. Ten seconds comfortably covers the
     * longest cast while still expiring long before a player could line up an unrelated cast to
     * steal the discount, and the entry is consumed the moment it is used.
     */
    private static final long PENDING_TTL_NANOS = 10_000_000_000L;

    /** A voice cast waiting for its cooldown to be applied. */
    private record Pending(String spellId, long atNanos) {}

    /** Most recent voice cast per player, until its cooldown is applied or it expires. */
    private static final Map<UUID, Pending> pending = new ConcurrentHashMap<>();

    /**
     * Which spells each player has ever voice-cast, for FIRST_CAST.
     *
     * <p>Persisted to {@code <world>/voicespells/learned.txt}, because losing it is not a
     * cosmetic reset: FIRST_CAST means a spell you have already unlocked by speaking it goes back
     * to being unclickable, so a server restart silently takes progress away from every player who
     * had earned it. Read at server start, written at server stop and at every logout that follows
     * a change - a dirty flag makes the common logout free.
     */
    private static final Map<UUID, Set<String>> learned = new ConcurrentHashMap<>();

    /** Where the learned set lives. Null until a server tells us its config directory. */
    private static volatile java.nio.file.Path storeFile;
    private static volatile boolean dirty = false;

    /**
     * Record a voice-initiated cast. Not paired with a clear: the entry is consumed when the
     * cooldown hook uses it, and expires on its own otherwise.
     */
    public static void beginVoiceCast(UUID player, String spellId) {
        if (player == null || spellId == null) return;
        pending.put(player, new Pending(spellId, System.nanoTime()));
        if (learned.computeIfAbsent(player, k -> ConcurrentHashMap.newKeySet()).add(spellId)) {
            dirty = true;
            // Written HERE, not left to logout or shutdown. An unlock is unrecoverable - under
            // FIRST_CAST the player has to speak the spell again to get it back - and the two
            // existing write points only run on a CLEAN exit. A host panel's nightly force-restart,
            // an OOM, or a power cut takes back everything every online player unlocked since the
            // last logout. add() returns true at most once per player per spell for the life of
            // the world, so this write is rare enough to be free.
            saveStore();
        }
    }

    /** Drop any pending entry - used on the failure path and on disconnect. */
    public static void endVoiceCast(UUID player) {
        if (player != null) {
            pending.remove(player);
            lastExplained.remove(player);
        }
    }

    /**
     * True if this player has a live, unconsumed voice cast OF THIS SPELL.
     *
     * <p>The spell id was always recorded and never read, so the permission the stamp grants was
     * blind to which spell it was granted for: speak one cheap spell and every clicked cast was
     * permitted until the stamp aged out. Ten seconds is a long time in a fight, and it is worse
     * for a recast spell, where Iron's Spells skips the cooldown entirely so nothing consumes the
     * stamp early and it always lives its full term.
     */
    private static boolean hasPendingFor(UUID player, String spellId) {
        if (player == null || spellId == null) return false;
        Pending p = pending.get(player);
        if (p == null) return false;
        if (System.nanoTime() - p.atNanos() > PENDING_TTL_NANOS) {
            pending.remove(player);
            return false;
        }
        return spellId.equals(p.spellId());
    }

    /** True if this player has a live, unconsumed voice cast. */
    private static boolean hasPending(UUID player) {
        Pending p = pending.get(player);
        if (p == null) return false;
        if (System.nanoTime() - p.atNanos() > PENDING_TTL_NANOS) {
            pending.remove(player);
            return false;
        }
        return true;
    }

    /** True while this player has a voice cast in flight. */
    public static boolean isVoiceCasting(UUID player) {
        return player != null && hasPending(player);
    }

    /** Whether this player has ever voice-cast this spell, for the FIRST_CAST rule. */
    public static boolean hasLearned(UUID player, String spellId) {
        Set<String> s = learned.get(player);
        return s != null && s.contains(spellId);
    }

    /**
     * Point the learned-spell store at a server's config directory and load what is there.
     * Called on server start; a client that never opens a world simply never has a store.
     */
    public static void openStore(java.nio.file.Path configDir) {
        try {
            storeFile = configDir.resolve("learned.txt");
            learned.clear();
            if (!java.nio.file.Files.exists(storeFile)) return;
            for (String line : java.nio.file.Files.readAllLines(storeFile)) {
                int sp = line.indexOf(' ');
                if (sp <= 0) continue;
                UUID id;
                try { id = UUID.fromString(line.substring(0, sp)); } catch (Throwable bad) { continue; }
                Set<String> set = learned.computeIfAbsent(id, k -> ConcurrentHashMap.newKeySet());
                for (String s : line.substring(sp + 1).split(",")) {
                    if (!s.isBlank()) set.add(s.trim());
                }
            }
            VoiceSpells.LOGGER.info("Loaded learned incantations for {} player(s)", learned.size());
        } catch (Throwable t) {
            VoiceSpells.LOGGER.warn("Could not read learned.txt: {}", t.toString());
        }
    }

    /** Write the learned set if anything changed. Cheap enough to call on a timer or at stop. */
    public static void saveStore() {
        java.nio.file.Path f = storeFile;
        if (f == null || !dirty) return;
        try {
            StringBuilder sb = new StringBuilder();
            learned.forEach((id, set) -> {
                if (set.isEmpty()) return;
                sb.append(id).append(' ').append(String.join(",", set)).append(System.lineSeparator());
            });
            java.nio.file.Files.createDirectories(f.getParent());
            java.nio.file.Path tmp = f.resolveSibling("learned.txt.tmp");
            java.nio.file.Files.writeString(tmp, sb.toString());
            java.nio.file.Files.move(tmp, f,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            dirty = false;
        } catch (Throwable t) {
            VoiceSpells.LOGGER.warn("Could not write learned.txt: {}", t.toString());
        }
    }

    /** Release everything at server stop. These are statics, so on a client they outlive a
     *  world: without this, spells learned in one save would count as learned in the next. */
    public static void forgetAll() {
        saveStore();          // do not drop learned spells on the floor at shutdown
        pending.clear();
        lastExplained.clear();
        learned.clear();
        storeFile = null;
    }

    // ---- the three rules, called from the loader-specific event wiring ------------------------

    /**
     * Scaled cooldown for a voice cast. Returns {@code -1} to leave the cooldown alone.
     *
     * @param base the cooldown Iron's Spells was about to apply, in ticks
     */
    public static int voiceCooldown(Player player, int base) {
        if (player == null || !hasPending(player.getUUID())) return -1;
        // Consumed here: one cast, one cooldown. Leaving it would let the next clicked cast of
        // any spell inside the TTL take the discount too.
        pending.remove(player.getUUID());
        int pct;
        try {
            pct = VoiceSpellsServerConfig.SERVER.voiceCooldownPercent.get();
        } catch (Throwable t) {
            return -1;
        }
        if (pct == 100) return -1;
        return Math.max(0, (int) Math.round(base * (pct / 100.0)));
    }

    /**
     * Extra spell levels configured for a voice cast, or 0.
     *
     * <p>Read by {@code SpellCaster} BEFORE it casts, not by an event hook. The first attempt used
     * {@code ModifySpellLevelEvent}, which never fires on this path - the level is already fixed
     * by the time {@code attemptInitiateCast} is called, so the bonus silently did nothing.
     * SpellCaster owns the number it is about to cast with, so that is where the bonus belongs.
     */
    public static int configuredLevelBonus() {
        try {
            return VoiceSpellsServerConfig.SERVER.voiceLevelBonus.get();
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * Whether a cast that did NOT come from voice should be blocked.
     *
     * @param spellId the spell being cast, as {@code namespace:path}
     */
    public static boolean blockClickedCast(Player player, String spellId) {
        if (player == null) return false;
        // Our own cast, always allowed - but only the spell we actually stamped. See hasPendingFor.
        if (hasPendingFor(player.getUUID(), spellId)) return false;
        VoiceSpellsServerConfig.IncantationRule rule;
        try {
            rule = VoiceSpellsServerConfig.SERVER.incantationOnly.get();
        } catch (Throwable t) {
            return false;
        }
        // Never lock out someone who physically cannot comply.
        if (!canSpeak(player)) return false;
        return switch (rule) {
            case OFF        -> false;
            case ALWAYS     -> true;
            case FIRST_CAST -> !hasLearned(player.getUUID(), spellId);
        };
    }

    /**
     * Whether this player can voice-cast at all, i.e. whether the incantation rule is fair to them.
     *
     * <p>A player without the mod client-side can never speak a spell, so under ALWAYS they would
     * be permanently unable to cast anything, with no way to find out why beyond a message that
     * their client may not even have the translation for. Anyone without the mod is therefore
     * exempt rather than locked out.
     *
     * <p>The question is answered at LOGIN, by asking whether the connection negotiated the mod's
     * network channel. It used to be inferred from "the server has received a cast packet from
     * this player at some point since it booted", which was wrong in both directions and made the
     * whole rule opt-in by the player it constrains: someone who never spoke was never recorded,
     * so ALWAYS never touched them, and speaking once was what armed the lock on yourself. The
     * set was also never persisted, so every restart un-constrained everybody, and it was sticky
     * within a run, so a player who removed the mod or lost their microphone kept the flag and
     * could not cast at all.
     */
    public static boolean canSpeak(Player player) {
        if (player == null) return false;
        // Not on the voice allowlist means they are forbidden to speak, so the rule cannot
        // require it of them - that combination locked a player out of magic entirely.
        if (!com.niko.voicespells.spells.SpellCaster.voiceAllowedFor(player)) return false;
        return com.niko.voicespells.spells.SpellCaster.hasVoiceClient(player.getUUID());
    }

    /**
     * How often a blocked player may be told why. Iron's Spells posts a pre-cast event for every
     * attempt, and a held right-click attempts several times a second, so an unthrottled message
     * is a strobing action bar and a screen-reader backlog. Two seconds sits inside vanilla's own
     * three-second overlay lifetime, so the text stays continuously visible without being redrawn.
     */
    private static final long EXPLAIN_COOLDOWN_NANOS = 2_000_000_000L;
    private static final Map<UUID, Long> lastExplained = new ConcurrentHashMap<>();

    /** Told to the player when a cast is refused, so the rule is never silent. */
    public static void explainBlocked(Player player, String spellId) {
        try {
            UUID id = player.getUUID();
            long now = System.nanoTime();
            Long prev = lastExplained.get(id);
            if (prev != null && now - prev < EXPLAIN_COOLDOWN_NANOS) return;
            lastExplained.put(id, now);
            VoiceSpellsServerConfig.IncantationRule rule =
                VoiceSpellsServerConfig.SERVER.incantationOnly.get();
            String key = rule == VoiceSpellsServerConfig.IncantationRule.ALWAYS
                ? "voicespells.incantation.always"
                : "voicespells.incantation.first";
            player.displayClientMessage(
                net.minecraft.network.chat.Component.translatable(key,
                    spellId.substring(spellId.indexOf(':') + 1).replace('_', ' ')), true);
        } catch (Throwable t) {
            VoiceSpells.LOGGER.debug("Could not explain blocked cast: {}", t.toString());
        }
    }
}
