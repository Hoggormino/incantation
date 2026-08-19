package com.niko.voicespells;

import net.minecraftforge.common.ForgeConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

/**
 * Server-authoritative config: how strict voice casting is about needing a spellbook. Lives in
 * {@code config/voicespells-server.toml} (per-world on a dedicated server). The cast itself runs
 * server-side in {@link com.niko.voicespells.spells.SpellCaster}, which reads this directly.
 */
public final class VoiceSpellsServerConfig {

    /** Where the spoken spell must be available before voice will cast it. */
    /** Whether spells may be cast without speaking them. See the config comments. */
    public enum IncantationRule { OFF, FIRST_CAST, ALWAYS }

    public enum CastMode {
        /** Default. Spellbook (an {@code ISpellbook}) in a Curios slot, containing the spell.
         *  Casts via SPELLBOOK source — mana and cooldown apply. */
        CURIO_SPELLBOOK,
        /** As above, but a spellbook in mainhand/offhand counts too. */
        ANY_SPELLBOOK,
        /** No spellbook needed: cast at level 1 via COMMAND source — no mana, no cooldown.
         *  Handy for testing or a "just let me yell spells" playstyle. */
        FREE
    }

    public static final ForgeConfigSpec SERVER_SPEC;
    public static final Server SERVER;
    static {
        Pair<Server, ForgeConfigSpec> pair =
            new ForgeConfigSpec.Builder().configure(Server::new);
        SERVER = pair.getLeft();
        SERVER_SPEC = pair.getRight();
    }

    private VoiceSpellsServerConfig() {}

    public static final class Server {
        public final ForgeConfigSpec.EnumValue<CastMode> castMode;
        public final ForgeConfigSpec.IntValue            maxCastsPerSecond;
        public final ForgeConfigSpec.ConfigValue<java.util.List<? extends String>> serverBlockedSpells;
        public final ForgeConfigSpec.ConfigValue<java.util.List<? extends String>> voiceAllowedPlayers;
        public final ForgeConfigSpec.BooleanValue        logVoiceCasts;
        public final ForgeConfigSpec.BooleanValue        voiceVolumeScaling;
        public final ForgeConfigSpec.BooleanValue        broadcastVoiceCasts;
        public final ForgeConfigSpec.IntValue            broadcastRadius;
        // Requested by a server host running a wizard-battle event: make voice-cast spells
        // mechanically different from clicked ones, so casting by voice is a real choice.
        public final ForgeConfigSpec.IntValue            voiceCooldownPercent;
        public final ForgeConfigSpec.IntValue            voiceLevelBonus;
        public final ForgeConfigSpec.EnumValue<IncantationRule> incantationOnly;

        Server(ForgeConfigSpec.Builder b) {
            b.push("casting");
            b.comment("How voice casting validates a spell before casting it.",
                      "CURIO_SPELLBOOK - spellbook in the Curios spellbook slot (default).",
                      "ANY_SPELLBOOK   - Curios slot OR a spellbook held in main/off hand.",
                      "FREE            - no spellbook needed; level 1, no mana or cooldown.");
            castMode = b.defineEnum("castMode", CastMode.CURIO_SPELLBOOK);
            b.comment("Max successful voice casts per second per player (0 = unlimited).",
                      "Server-side anti-spam — excess casts are silently dropped.");
            maxCastsPerSecond = b.defineInRange("maxCastsPerSecond", 5, 0, 50);
            b.comment("Spells that may never be voice-cast on this server, regardless of",
                      "client-side blocklists. Format: namespace:spell_id, one per line.");
            serverBlockedSpells = b.defineListAllowEmpty("serverBlockedSpells",
                java.util.List.of(),
                o -> o instanceof String s && s.indexOf(':') > 0);
            b.comment("If non-empty, only the listed player names or UUIDs are allowed to",
                      "voice-cast on this server. Empty = everyone is allowed.");
            voiceAllowedPlayers = b.defineListAllowEmpty("voiceAllowedPlayers",
                java.util.List.of(),
                o -> o instanceof String s && !s.isBlank());
            b.comment("Append every voice-cast to logs/voicespells-casts.log (player + spell + time).",
                      "Useful for admin auditing on shared servers.");
            logVoiceCasts = b.define("logVoiceCasts", false);
            b.comment("Scale the effective spellbook level by the client's voice volume.",
                      "Speaking quietly -> level 1, shout -> max level on the equipped spellbook.",
                      "Trusts the client to report its own RMS — not a security boundary, but a",
                      "fun expressivity toggle. Off by default.");
            voiceVolumeScaling = b.define("voiceVolumeScaling", false);
            b.comment("When on, every successful voice cast broadcasts a small chat line to",
                      "players within broadcastRadius blocks: 'Niko cast Fireball'.",
                      "Off by default — opt-in for RP/streaming servers.");
            broadcastVoiceCasts = b.define("broadcastVoiceCasts", false);
            b.comment("Radius in blocks for the voice-cast chat broadcast. 0 = only the caster.");
            broadcastRadius = b.defineInRange("broadcastRadius", 16, 0, 256);
            b.pop();

            b.push("voiceAdvantage");
            b.comment("Cooldown applied to a spell cast BY VOICE, as a percentage of normal.",
                      "100 = no change. 50 = half cooldown. 0 = no cooldown at all.",
                      "Values above 100 make voice casting cost more, not less.",
                      "Applies only to casts the mod itself initiated, never to clicked ones.");
            voiceCooldownPercent = b.defineInRange("voiceCooldownPercent", 100, 0, 300);
            b.comment("Extra spell levels granted to a spell cast BY VOICE. 0 = no change.",
                      "Spell level is what Iron's Spells scales damage, duration and count from,",
                      "so this is the 'voice casts hit harder' knob.");
            voiceLevelBonus = b.defineInRange("voiceLevelBonus", 0, 0, 5);
            b.comment("Whether spells may be cast WITHOUT speaking them.",
                      "OFF        - normal Iron's Spells behaviour (default).",
                      "FIRST_CAST - a spell must be voice-cast once before it can be clicked;",
                      "             learning the incantation unlocks the spell for that player.",
                      "ALWAYS     - spells can only ever be cast by voice.",
                      "Both non-OFF modes apply to every player on the server, and neither can",
                      "work unless the client has the mod installed.");
            incantationOnly = b.defineEnum("incantationOnly", IncantationRule.OFF);
            b.pop();
        }
    }
}
