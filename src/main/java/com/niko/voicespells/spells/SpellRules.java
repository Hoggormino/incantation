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
 * <p>Iron's Spells exposes exactly the three hooks this needs — {@code SpellCooldownAddedEvent.Pre}
 * (cancellable, with {@code setEffectiveCooldown}), {@code ModifySpellLevelEvent} ({@code
 * addLevels}), and {@code SpellPreCastEvent} (cancellable) — and all three exist with identical
 * signatures on 1.20.1 and 1.21.1, so only the bus registration is version-split.
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

    /** How long a voice-cast stamp stays valid. Generous for a lagging server, far short of the
     *  gap between two deliberate casts. */
    private static final long STAMP_TTL_NANOS = 3_000_000_000L;

    /** Players currently inside a voice-initiated cast, by the nanotime it started. */
    private static final Map<UUID, Long> castingByVoice = new ConcurrentHashMap<>();

    /** Which spells each player has ever voice-cast, for FIRST_CAST. */
    private static final Map<UUID, Set<String>> learned = new ConcurrentHashMap<>();

    /** Mark the start of a voice-initiated cast. Paired with {@link #endVoiceCast}. */
    public static void beginVoiceCast(UUID player, String spellId) {
        if (player == null) return;
        castingByVoice.put(player, System.nanoTime());
        if (spellId != null) {
            learned.computeIfAbsent(player, k -> ConcurrentHashMap.newKeySet()).add(spellId);
        }
    }

    /** Clear the mark. Safe to call when none was set. */
    public static void endVoiceCast(UUID player) {
        if (player != null) castingByVoice.remove(player);
    }

    /** True while this player is inside a cast the mod itself started. */
    public static boolean isVoiceCasting(UUID player) {
        if (player == null) return false;
        Long at = castingByVoice.get(player);
        if (at == null) return false;
        if (System.nanoTime() - at > STAMP_TTL_NANOS) {   // stale: treat as not ours
            castingByVoice.remove(player);
            return false;
        }
        return true;
    }

    /** Whether this player has ever voice-cast this spell, for the FIRST_CAST rule. */
    public static boolean hasLearned(UUID player, String spellId) {
        Set<String> s = learned.get(player);
        return s != null && s.contains(spellId);
    }

    /** Forget a player's state when they disconnect, so the maps do not grow forever. */
    public static void forget(UUID player) {
        castingByVoice.remove(player);
        learned.remove(player);
    }

    /** Release everything at server stop. These are statics, so on a client they outlive a
     *  world: without this, spells learned in one save would count as learned in the next. */
    public static void forgetAll() {
        castingByVoice.clear();
        learned.clear();
    }

    // ---- the three rules, called from the loader-specific event wiring ------------------------

    /**
     * Scaled cooldown for a voice cast. Returns {@code -1} to leave the cooldown alone.
     *
     * @param base the cooldown Iron's Spells was about to apply, in ticks
     */
    public static int voiceCooldown(Player player, int base) {
        if (player == null || !isVoiceCasting(player.getUUID())) return -1;
        int pct;
        try {
            pct = VoiceSpellsServerConfig.SERVER.voiceCooldownPercent.get();
        } catch (Throwable t) {
            return -1;
        }
        if (pct == 100) return -1;
        return Math.max(0, (int) Math.round(base * (pct / 100.0)));
    }

    /** Extra spell levels for a voice cast, or 0 for none. */
    public static int voiceLevelBonus(Player player) {
        if (player == null || !isVoiceCasting(player.getUUID())) return 0;
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
        if (isVoiceCasting(player.getUUID())) return false;      // our own cast, always allowed
        VoiceSpellsServerConfig.IncantationRule rule;
        try {
            rule = VoiceSpellsServerConfig.SERVER.incantationOnly.get();
        } catch (Throwable t) {
            return false;
        }
        return switch (rule) {
            case OFF        -> false;
            case ALWAYS     -> true;
            case FIRST_CAST -> !hasLearned(player.getUUID(), spellId);
        };
    }

    /** Told to the player when a cast is refused, so the rule is never silent. */
    public static void explainBlocked(Player player, String spellId) {
        try {
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
