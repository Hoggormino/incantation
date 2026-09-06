package com.niko.voicespells;

import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

import java.util.List;

/**
 * Client-side config for the HUD chip. Saved to {@code config/voicespells-client.toml} on first
 * launch; values are re-read on file change without a restart.
 *
 * Position is "corner + offset" — choose a screen corner, then offsetX/Y is the distance from
 * that corner to the chip's nearest corner. Colours are ARGB hex strings (8 chars with alpha,
 * or 6 chars treated as fully opaque). A global opacity multiplier scales every colour's alpha
 * channel uniformly, so you can fade the whole HUD toward transparent without editing every
 * colour.
 */
public final class VoiceSpellsConfig {
    public enum Corner { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }


    public static final ModConfigSpec CLIENT_SPEC;
    public static final Client CLIENT;
    static {
        Pair<Client, ModConfigSpec> pair =
            new ModConfigSpec.Builder().configure(Client::new);
        CLIENT = pair.getLeft();
        CLIENT_SPEC = pair.getRight();
    }

    private VoiceSpellsConfig() {}

    /**
     * Write the client config to disk.
     *
     * <p>MUST be called after any {@code CLIENT.<value>.set(...)}. On NeoForge 21.x,
     * {@code ModConfigSpec.ConfigValue.set()} does NOT persist: its bytecode writes the
     * in-memory CommentedConfig, updates the cached value, and returns — there is no call to
     * ILoadedConfig.save() anywhere in it. NeoForge also stopped building the config as an
     * autosaving CommentedFileConfig; ConfigTracker now holds a plain CommentedConfig and
     * persists only through an explicit LoadedConfig.save() (TomlWriter, REPLACE_ATOMIC).
     *
     * <p>The effect of the missing call was that every setting written through the config
     * screen, every alias added, the noise-gate calibration result and the firstRun latch all
     * survived the session and were silently lost on restart. It looked like it worked, which
     * is why it went unnoticed. The Forge 1.20.1 build was never affected — there the file
     * IS built with .autosave() (verified in ConfigFileTypeHandler bytecode) — but the same
     * helper exists on both sides so callers do not have to care.
     *
     * <p>Guarded by isLoaded(): save() throws if no config object is assigned yet, which is
     * the case if something writes a value before the config has loaded.
     */
    public static void saveToDisk() {
        try {
            if (CLIENT_SPEC.isLoaded()) CLIENT_SPEC.save();
        } catch (Throwable t) {
            com.niko.voicespells.VoiceSpells.LOGGER.warn(
                "Could not persist client config: {}", t.toString());
        }
    }


    public static void onConfigLoad(ModConfigEvent.Loading e) {
        if (e.getConfig().getType() == ModConfig.Type.CLIENT) {
            cacheColors();
            VoiceSpells.LOGGER.debug("Config loaded; color cache refreshed");
        }
    }
    /** Set by client init to {@code VoiceController::onConfigChanged}. Invoked when the toml is
     *  edited externally so the grammar rebuilds live. Kept as a Runnable so this common-side
     *  config class never link-references the client-only controller. */
    public static volatile Runnable reloadCallback;

    /** Set by client init to {@code Theme::applyPalette}. There is one palette and no accent
     *  now, so this only exists to initialise the colour table; it stays {@code null} on a
     *  dedicated server so {@code cacheColors()} can run without triggering Theme load. */
    public static volatile Runnable themeApplier;

    public static void onConfigReload(ModConfigEvent.Reloading e) {
        if (e.getConfig().getType() == ModConfig.Type.CLIENT) {
            cacheColors();
            VoiceSpells.LOGGER.debug("Config reloaded; cache refreshed");
            Runnable cb = reloadCallback;
            if (cb != null) {
                try { cb.run(); } catch (Throwable t) {
                    VoiceSpells.LOGGER.warn("Config reload callback failed: {}", t.toString());
                }
            }
        }
    }

    /**
     * Force-refresh the cached colour ints from the current spec values. The in-game config
     * screen calls this immediately after writing changes so the HUD updates synchronously,
     * without waiting for NeoForge's file-watcher to fire a reload event (which is debounced).
     */
    public static void refreshCache() {
        cacheColors();
    }

    /**
     * When the microphone is allowed to feed the recognizer.
     *
     * <p>Distinct from the existing combatOnly / pauseWhenAfk options, which filter at dispatch —
     * after audio has already been recognised. These gate at capture, so in HOLD_KEY and HOLD_ITEM
     * the recognizer never sees the audio at all.
     */
    public enum GatingMode {
        /** Microphone live whenever you are in a world. */
        ALWAYS_ON,
        /** Only while the push-to-talk keybind is held. */
        HOLD_KEY,
        /** Only while a spellbook, staff or imbued weapon is in your hands or Curios slot. */
        HOLD_ITEM,
        /** Only while holding the push-to-talk key AND a spellbook, staff or imbued weapon. */
        HOLD_KEY_AND_ITEM
    }

    public static final class Client {
        // --- position ---
        public final ModConfigSpec.EnumValue<Corner> hudCorner;
        public final ModConfigSpec.IntValue          hudOffsetX;
        public final ModConfigSpec.IntValue          hudOffsetY;
        public final ModConfigSpec.DoubleValue       globalOpacity;

        // --- colours (ARGB hex strings) ---
        public final ModConfigSpec.ConfigValue<String> bgColor;
        public final ModConfigSpec.ConfigValue<String> borderColor;
        public final ModConfigSpec.ConfigValue<String> textMuted;
        public final ModConfigSpec.ConfigValue<String> textToast;

        // --- recognition tuning ---
        public final ModConfigSpec.BooleanValue debugMonitor;
        public final ModConfigSpec.IntValue     fuzzyMaxDistance;
        public final ModConfigSpec.BooleanValue substringMatch;
        public final ModConfigSpec.IntValue     dedupMillis;
        public final ModConfigSpec.IntValue     echoLockoutMillis;
        public final ModConfigSpec.IntValue     castQueueSize;
        public final ModConfigSpec.BooleanValue clientPreflight;
        public final ModConfigSpec.DoubleValue  minConfidence;
        public final ModConfigSpec.IntValue     minMsPerLetter;
        public final ModConfigSpec.BooleanValue autoDownloadModel;
        public final ModConfigSpec.ConfigValue<String> modelPath;
        /** Catalogued model to install and use, when modelPath is not set explicitly. */
        public final ModConfigSpec.ConfigValue<String> modelId;
        public final ModConfigSpec.BooleanValue requireSneak;
        public final ModConfigSpec.ConfigValue<String> triggerWord;
        public final ModConfigSpec.ConfigValue<List<? extends String>> triggerWords;
        public final ModConfigSpec.ConfigValue<List<? extends String>> perSpellMinConfidence;
        public final ModConfigSpec.BooleanValue showMisses;
        public final ModConfigSpec.BooleanValue enableEchoSfx;
        public final ModConfigSpec.BooleanValue streamerMode;
        public final ModConfigSpec.BooleanValue sassMode;
        public final ModConfigSpec.BooleanValue castCooldownChip;
        public final ModConfigSpec.IntValue          castCooldownChipY;
        public final ModConfigSpec.BooleanValue castVignette;
        public final ModConfigSpec.BooleanValue combatOnly;
        public final ModConfigSpec.BooleanValue pauseWhenAfk;
        public final ModConfigSpec.IntValue     afkSeconds;
        public final ModConfigSpec.BooleanValue alwaysShowHeard;
        public final ModConfigSpec.BooleanValue handsFreeConfirm;
        public final ModConfigSpec.DoubleValue  noiseGateRms;
        /** How loudly this player normally speaks, learned by "Calibrate mic"; 0 = never run. */
        public final ModConfigSpec.DoubleValue  speechPeakRms;
        /** When the mic is allowed to feed the recognizer. */
        public final ModConfigSpec.EnumValue<GatingMode> gatingMode;
        /** Release the mic while the window is unfocused or the game is paused. */
        public final ModConfigSpec.BooleanValue suspendWhenUnfocused;
        /** Minimum spell phrases kept in the Vosk grammar; short sets are padded with decoys. */
        public final ModConfigSpec.IntValue     grammarFloor;
        /** Exact capture device name, or blank for the system default. /voicespells devices lists them. */
        public final ModConfigSpec.ConfigValue<String> captureDevice;
        public final ModConfigSpec.BooleanValue chatRankTag;
        public final ModConfigSpec.BooleanValue restrictToOwned;
        public final ModConfigSpec.ConfigValue<List<? extends String>> customPhrases;
        public final ModConfigSpec.ConfigValue<List<? extends String>> incantations;
        public final ModConfigSpec.ConfigValue<List<? extends String>> blockedSpells;
        public final ModConfigSpec.ConfigValue<List<? extends String>> loadouts;

        // --- internal flags (tracked by the mod, not normally hand-edited) ---
        public final ModConfigSpec.BooleanValue firstRun;

        Client(ModConfigSpec.Builder b) {
            b.push("hud");
            b.comment("Position of the HUD chip on screen. offsetX/Y are pixels from the",
                      "chosen corner to the nearest corner of the chip.");
            hudCorner   = b.defineEnum("corner",  Corner.BOTTOM_LEFT);
            hudOffsetX  = b.defineInRange("offsetX", 5,  0, 1000);
            hudOffsetY  = b.defineInRange("offsetY", 28, 0, 1000);
            b.comment("Multiplier 0..1 applied to every colour's alpha channel.",
                      "Set to 0.2 for a barely-there chip, 0 to hide the background entirely.");
            globalOpacity = b.defineInRange("opacity", 1.0, 0.0, 1.0);
            b.pop();

            b.push("colors");
            b.comment("ARGB hex strings. 8 chars include alpha (\"CC0A0A0A\"); 6 chars are",
                      "treated as fully opaque. The leading '#' is optional.",
                      "",
                      "background and border default to fully transparent, so the HUD is just",
                      "shadowed text over the world - the way vanilla draws the action bar and",
                      "the way Simple Voice Chat draws its mic indicator. Set background to",
                      "something like CC0A0A0A if you would rather have a solid chip behind it.",
                      "Existing configs keep whatever you already had.",
                      "",
                      "textMuted colours the secondary chips - history strip, miss toast, heard",
                      "chip. toast colours the queued-cast chip only; the cast toast itself takes",
                      "its colour from the spell's school.");
            bgColor      = b.define("background",   "00000000");
            borderColor  = b.define("border",       "00000000");
            textMuted    = b.define("textMuted",    "FF8A8A8A");
            textToast    = b.define("toast",        "FFFFD75A");
            b.pop();

            b.push("recognition");
            b.comment("Log every recognition at INFO instead of DEBUG, for support logs. The Live Monitor is a separate screen (More... > Live Monitor) and is not gated by this.");
            debugMonitor = b.define("debugMonitor", false);
            b.comment("Fuzzy fallback edit distance. 0 exact only, 1 lenient, 2 most lenient.");
            fuzzyMaxDistance = b.defineInRange("fuzzyMaxDistance", 1, 0, 2);
            b.comment("Match a spell phrase found as a whole word inside the heard text.");
            substringMatch = b.define("substringMatch", true);
            b.comment("Ignore the same spell again within this many milliseconds.");
            dedupMillis = b.defineInRange("dedupMillis", 800, 0, 5000);
            b.comment("Echo lockout window (ms) — hard absolute window where a same-spell repeat",
                      "is dropped regardless of inter-event gap. Catches slow Vosk emissions",
                      "where partial -> final spans longer than dedupMillis AND the tail-audio",
                      "case where trailing audio after the word gets grammar-forced into the",
                      "just-cast spell. Set to 0 to disable. Lower this to ~600 if rapid-fire",
                      "incantation chants feel laggy; raise if same-spell double-casts still",
                      "slip through.");
            echoLockoutMillis = b.defineInRange("echoLockoutMillis", 1500, 0, 10000);
            b.comment("Max number of voice-recognized spells that can stack in the cast queue",
                      "while you're already casting. FIFO — oldest queued fires first.");
            castQueueSize = b.defineInRange("castQueueSize", 3, 1, 5);
            b.comment("Client-side preflight: check mana + cooldown via ClientMagicData before",
                      "sending the cast packet, so impossible casts don't round-trip the server.");
            clientPreflight = b.define("clientPreflight", true);
            b.comment("Minimum average word confidence (0..1) for a final result to cast.",
                      "Raise to reject mumbled/garbled audio, lower if good speech is ignored.",
                      "Partial results are not confidence-gated; see minMsPerLetter and",
                      "perSpellMinConfidence.");
            minConfidence = b.defineInRange("minConfidence", 0.55, 0.0, 1.0);
            b.comment("Acoustic completeness: a matched spell must have been SPOKEN for at",
                      "least this many milliseconds per letter of its phrase before it casts.",
                      "At the default 25, \"heal\" (4 letters) needs 100 ms, \"shield\" (6) needs",
                      "150 ms and \"invisibility\" (12) needs 300 ms. Real spell names measure",
                      "roughly 50-120 ms per letter; short one-syllable names like shield are",
                      "the tightest, around 40. What this stops is the recogniser force-fitting",
                      "a cough or a stray word onto a long spell name - which is how",
                      "Invisibility got cast from speech that was nothing like it. A running",
                      "guess that is too short is not cast; the final answer decides, and a",
                      "final that is too short is rejected and shows as (too short) in the",
                      "Live Monitor. Measured from the recogniser's own word timings. Set to 0",
                      "to disable.");
            minMsPerLetter = b.defineInRange("minMsPerLetter", 25, 0, 200);
            b.comment("Download the model named by modelId if no model is installed yet.",
                      "Default is vosk-model-small-en-us-0.15 (~40 MB), not the larger one.",
                      "Off = install a model into config/voicespells/model/ yourself.");
            autoDownloadModel = b.define("autoDownloadModel", true);
            b.comment("Override the Vosk model directory. Leave empty to use the default",
                      "(config/voicespells/model). Set to an absolute path to point at a",
                      "shared model on disk — useful for trying different precision/speed models.");
            modelPath = b.define("modelPath", "");
            b.comment("Which speech model to use, by id. Downloaded on demand into",
                      "config/voicespells/models/<id>/ when autoDownloadModel is on.",
                      "Known ids: vosk-model-small-en-us-0.15 (default, ~40 MB),",
                      "vosk-model-en-us-0.22-lgraph (~128 MB, more accurate English),",
                      "vosk-model-small-es-0.42, vosk-model-small-ru-0.22,",
                      "vosk-model-small-fr-0.22, vosk-model-small-de-0.15.",
                      "A model only recognises words in its own language: to play in another",
                      "language you need both the matching model here AND translated phrases in",
                      "config/voicespells/phrasebook.json. modelPath overrides this entirely.");
            modelId = b.define("modelId", com.niko.voicespells.speech.ModelCatalog.DEFAULT_ID);
            b.comment("Only voice-cast while sneaking. Off = always (hands-free).");
            requireSneak = b.define("requireSneak", false);
            b.comment("If set, the spell must be preceded by this word to cast.",
                      "Example: triggerWord cast means say cast fireball.",
                      "Kills false casts during voice chat. Empty = no trigger word.");
            triggerWord = b.define("triggerWord", "");
            b.comment("Multiple trigger words — any one of these in the heard phrase counts.",
                      "Combines with the single triggerWord above (both are accepted).",
                      "Example: triggerWords = [\"cast\", \"invoke\", \"summon\"]");
            triggerWords = b.defineListAllowEmpty("triggerWords",
                List.of(),
                o -> o instanceof String s && !s.isBlank());
            b.comment("Per-spell confidence thresholds, and the knob for a spell the",
                      "recogniser keeps force-fitting onto unrelated speech. A spell listed",
                      "here never casts from a running guess: it waits for the complete",
                      "utterance and is then judged at ITS OWN threshold instead of",
                      "minConfidence - higher or lower. That costs the listed spell a wait of",
                      "about half a second after you stop speaking - and it waits for you to",
                      "stop, so say it on its own rather than in the middle of a sentence.",
                      "Raise it for a magnet spell (irons_spellbooks:invisibility=0.7); lower",
                      "it for a stubborn one the model hears at low confidence",
                      "(irons_spellbooks:fire_bolt=0.35). Spells not listed keep instant",
                      "partial casting and are still judged at minConfidence on their final.",
                      "Format: namespace:spell_id=0.35");
            perSpellMinConfidence = b.defineListAllowEmpty("perSpellMinConfidence",
                List.of(),
                o -> o instanceof String s && s.indexOf('=') > 0);
            b.comment("Briefly show on-screen what was heard when it matched no spell, so you",
                      "can learn which phrasings the model produces. Off by default.");
            showMisses = b.define("showMisses", false);
            b.comment("Play a brief mystical chime when a phrase is recognized. Off if noisy.");
            enableEchoSfx = b.define("enableEchoSfx", true);
            b.comment("Streamer mode - obscure spell names in the HUD so screen viewers can't",
                      "read what you cast. The Live Monitor in the config screen still shows",
                      "actual text since only you see that.");
            streamerMode = b.define("streamerMode", false);
            b.comment("Sass mode - occasional snarky 'was that even a spell?' toast on miss.");
            sassMode = b.define("sassMode", false);
            b.comment("Show the cooldown chip under the crosshair after a voice cast.",
                      "It names the spell and fills as the cooldown runs down. Turn it off if",
                      "you would rather keep the centre of the screen clear.");
            castCooldownChip = b.define("castCooldownChip", true);
            b.comment("Nudge that chip up or down, in pixels. It sits just above the hotbar",
                      "by default, clear of the crosshair and of vanilla's item-name popup.",
                      "Negative moves it up the screen, positive moves it down.");
            castCooldownChipY = b.defineInRange("castCooldownChipY", 0, -400, 200);
            b.comment("Cinematic accent bars top and bottom of the screen, plus corner",
                      "glows, while you are casting a long spell. Drawn in white.",
                      "Off by default: the code existed since early builds but never ran,",
                      "because the reflection behind it named a class that does not exist.",
                      "Fixing that name switched it on for the first time, so leaving it",
                      "enabled would push a visual change on people who never had it.");
            castVignette = b.define("castVignette", false);
            b.comment("Combat-only mode - recognition only fires when the player took damage",
                      "from a mob in the last ~10 seconds. Damage you deal does not count.");
            combatOnly = b.define("combatOnly", false);
            b.comment("Pause recognition when AFK (no movement for afkSeconds).");
            pauseWhenAfk = b.define("pauseWhenAfk", false);
            b.comment("Seconds of no movement before AFK kicks in.");
            afkSeconds = b.defineInRange("afkSeconds", 60, 5, 3600);
            b.comment("Persistently show the last heard phrase on the HUD (separate from the",
                      "miss toast). Useful while tuning recognition. Off by default.");
            alwaysShowHeard = b.define("alwaysShowHeard", false);
            b.comment("Hands-free queue confirmation: when on, say 'no' to clear the cast",
                      "queue, or 'yes' to acknowledge. Injects those two words into the Vosk",
                      "grammar so they're listened for.");
            handsFreeConfirm = b.define("handsFreeConfirm", false);
            b.comment("Noise gate: skip mic frames quieter than this RMS (0..32767) before",
                      "feeding them to Vosk. Stops the grammar-restricted recogniser from",
                      "hallucinating spells out of silence or breath.",
                      "The gate is sticky: once a loud frame arrives it stays open for ~450ms,",
                      "so phoneme dips inside a word don't cut the utterance.",
                      "Raise this if you see phantom casts on background noise; lower if",
                      "soft speech is being missed. Set to 0 to disable the gate entirely.");
            noiseGateRms = b.defineInRange("noiseGateRms", 350.0, 0.0, 6000.0);
            b.comment("How loud you normally speak: the level heard during the last 'Calibrate mic'",
                      "run, as a microphone frame RMS. Voice casts are judged against it when the",
                      "server has voiceVolumeScaling on - speaking at this level earns about half",
                      "the voice level bonus, a quarter of it (a whisper) none of it, and 1.5x",
                      "(a raised voice) all of it.",
                      "Measured relative to your own voice on purpose: the same raised voice is a",
                      "different RMS on every microphone and at every gain setting.",
                      "The bonus itself is whole levels, so with voiceLevelBonus = 1 there is no",
                      "gradient to hear - your normal speaking voice already earns it, and only",
                      "dropping clearly below it (a whisper) loses it.",
                      "0 = never calibrated, in which case a typical microphone (3000) is assumed.",
                      "Set by Config -> More... -> Calibrate mic; there is no reason to type a",
                      "number here by hand.");
            speechPeakRms = b.defineInRange("speechPeakRms", 0.0, 0.0, 32767.0);
            b.comment("When the microphone is allowed to listen.",
                      "ALWAYS_ON         - live whenever you are in a world.",
                      "HOLD_KEY          - only while the push-to-talk keybind is held.",
                      "HOLD_ITEM         - only while a spellbook, staff or imbued weapon is in your",
                      "                    hands or your Curios slot.",
                      "HOLD_KEY_AND_ITEM - both of the above at once.",
                      "Unlike combatOnly and pauseWhenAfk, which filter after recognition, this",
                      "gates capture itself: in every mode but ALWAYS_ON the recognizer never",
                      "receives the audio at all.",
                      "HOLD_ITEM is the default: it needs no keybind, and stowing your spellbook is",
                      "enough to stop the mic listening. Note it counts the Curios slot too --",
                      "the default castMode is CURIO_SPELLBOOK, so hands-only would never open",
                      "the mic for the loadout this mod expects. If you keep a book in Curios",
                      "permanently, that makes this close to ALWAYS_ON in practice.",
                      "If you talk to friends over a voice-chat mod WHILE armed, use",
                      "HOLD_KEY_AND_ITEM -- holding a staff is not on its own evidence you meant to",
                      "cast, and that mode requires the key as well so speech never reaches the",
                      "recognizer unless you asked for it. ALWAYS_ON is fully hands-free.");
            gatingMode = b.defineEnum("gatingMode", GatingMode.HOLD_ITEM);
            b.comment("Close the microphone device entirely while the game window is unfocused or",
                      "paused. Leave this on unless you specifically want to cast while tabbed out;",
                      "it means the mic is not held open while you are doing something else.");
            suspendWhenUnfocused = b.define("suspendWhenUnfocused", true);
            b.comment("Minimum number of spell phrases in the speech grammar.",
                      "The grammar is narrowed to spells you can actually cast, which is what makes",
                      "recognition accurate. But a very small grammar is dangerous: the recognizer",
                      "picks the closest match rather than rejecting, so with only one or two",
                      "phrases almost any sound becomes a spell. Below this number the grammar is",
                      "padded with names of spells you do NOT have, which act as decoys - if one",
                      "wins, nothing casts. Raise it if you get false casts, lower it if a spell",
                      "you own is being misheard as one you do not.");
            grammarFloor = b.defineInRange("grammarFloor", 16, 1, 512);
            b.comment("Capture device name, or blank for the system default.",
                      "Run /voicespells devices in game to list the exact names.");
            captureDevice = b.define("captureDevice", "");
            b.comment("Prefix chat messages with your voice-cast rank, e.g. '[Adept] hello'.",
                      "Cosmetic only — opt-in.");
            chatRankTag = b.define("chatRankTag", false);
            b.comment("Restrict recognition to spells the player currently has. Scans the",
                      "Curios spellbook slot, both hands, and the hotbar for spellbooks and",
                      "imbued weapons; the Vosk grammar is rebuilt to only include those.",
                      "Stops Vosk from forcing background noise into the closest unowned",
                      "spell name. Off = listen for every registered spell.");
            restrictToOwned = b.define("restrictToOwned", true);
            b.comment("Custom phrase to spell mappings for spells the recognizer cannot say.",
                      "One entry per line, format: phrase=namespace:spell_id",
                      "Example: abyss blast=traveloptics:abyssal_blast");
            customPhrases = b.defineListAllowEmpty("customPhrases",
                List.of(),
                o -> o instanceof String s && s.indexOf('=') > 0);
            b.comment("Flavor incantations - longer mystical phrases that cast a spell.",
                      "Same mechanism as customPhrases, kept separate so technical aliases",
                      "and theatrical phrases don't get tangled in the toml.",
                      "Format: incantation=namespace:spell_id",
                      "Example: by the power of fire=irons_spellbooks:fireball");
            incantations = b.defineListAllowEmpty("incantations",
                List.of(),
                o -> o instanceof String s && s.indexOf('=') > 0);
            b.comment("Spell ids the voice mod must never cast (e.g. addon spells that crash",
                      "with your other mods). Format: namespace:spell_id, one per entry.",
                      "Example: traveloptics:sword_of_the_ancients");
            blockedSpells = b.defineListAllowEmpty("blockedSpells",
                List.of(),
                o -> o instanceof String s && s.indexOf(':') > 0);
            b.comment("Spell categories / loadouts. Say the name to cast the first castable",
                      "spell from the list (skips ones on cooldown or out of mana).",
                      "Format: name=namespace:spell_id,namespace:spell_id,...",
                      "Example: offense=irons_spellbooks:fireball,irons_spellbooks:lightning_lance");
            loadouts = b.defineListAllowEmpty("loadouts",
                List.of(),
                o -> o instanceof String s && s.indexOf('=') > 0);
            b.pop();

            b.push("internal");
            b.comment("Whether the welcome wizard has been shown yet.",
                      "Setting this back to true is NOT enough to see the wizard again: finishing",
                      "it also latches a flag in stats.dat, and the wizard only pops when both",
                      "agree it has not been seen. To re-open it, use Config -> More... ->",
                      "\"Open Welcome Wizard\".");
            firstRun = b.define("firstRun", true);
            b.pop();
        }
    }

    // --- cached parsed values + alpha-scaled variants ---
    // The HUD render runs every frame so we precompute parsed ints once per config reload
    // rather than parsing hex on each draw.

    public static volatile int cBg, cBorder, cTextMuted, cTextToast;
    public static volatile float cOpacity = 1f;

    // Recognition tuning — read on the mic thread per recognized phrase, so cache it rather
    // than hitting the config map each time.
    public static volatile boolean cDebugMonitor    = false;
    public static volatile int     cFuzzyMaxDistance = 1;
    public static volatile boolean cSubstringMatch  = true;
    public static volatile long    cDedupNanos        = 800L * 1_000_000L;
    public static volatile long    cEchoLockoutNanos  = 1500L * 1_000_000L;
    public static volatile int     cCastQueueSize     = 3;
    public static volatile boolean cClientPreflight   = true;
    public static volatile double  cMinConfidence   = 0.55;
    public static volatile int     cMinMsPerLetter  = 25;
    public static volatile boolean cRequireSneak    = false;
    public static volatile String  cTriggerWord     = "";
    public static volatile java.util.Set<String> cTriggerWords = java.util.Set.of();
    public static volatile java.util.Map<String, Double> cPerSpellConfidence = java.util.Map.of();
    public static volatile boolean cShowMisses      = false;
    public static volatile boolean cEchoSfx         = true;
    public static volatile boolean cStreamerMode    = false;
    public static volatile boolean cSassMode        = false;
    public static volatile boolean cCastCooldownChip  = true;
    public static volatile int     cCastCooldownChipY = 0;
    public static volatile boolean cCastVignette    = false;
    public static volatile boolean cCombatOnly      = false;
    public static volatile boolean cPauseWhenAfk    = false;
    public static volatile long    cAfkNanos        = 60L * 1_000_000_000L;
    public static volatile boolean cAlwaysShowHeard = false;
    public static volatile boolean cHandsFreeConfirm = false;
    public static volatile double  cNoiseGateRms    = 350.0;
    public static volatile double  cSpeechPeakRms   = 0.0;
    public static volatile String  cModelId         =
        com.niko.voicespells.speech.ModelCatalog.DEFAULT_ID;
    public static volatile GatingMode cGatingMode    = GatingMode.HOLD_ITEM;
    public static volatile boolean cSuspendUnfocused = true;
    public static volatile int     cGrammarFloor    = 16;
    public static volatile String  cCaptureDevice    = "";
    public static volatile boolean cChatRankTag     = false;
    public static volatile boolean cRestrictToOwned   = true;

    // HUD layout — read every render frame, cache to avoid the underlying CommentedConfig
    // map lookup (and the autoboxing for the ints).
    public static volatile Corner cHudCorner   = Corner.BOTTOM_LEFT;
    public static volatile int    cHudOffsetX  = 5;
    public static volatile int    cHudOffsetY  = 28;

    private static void cacheColors() {
        Client c = CLIENT;
        cDebugMonitor     = c.debugMonitor.get();
        cFuzzyMaxDistance = c.fuzzyMaxDistance.get();
        cSubstringMatch   = c.substringMatch.get();
        cDedupNanos       = c.dedupMillis.get() * 1_000_000L;
        cEchoLockoutNanos = c.echoLockoutMillis.get() * 1_000_000L;
        cCastQueueSize    = c.castQueueSize.get();
        cClientPreflight  = c.clientPreflight.get();
        cMinConfidence    = c.minConfidence.get();
        cMinMsPerLetter   = c.minMsPerLetter.get();
        cRequireSneak     = c.requireSneak.get();
        cTriggerWord      = c.triggerWord.get().trim().toLowerCase(java.util.Locale.ROOT);
        // Build the merged trigger-word set (legacy single + new list).
        java.util.Set<String> tw = new java.util.HashSet<>();
        if (!cTriggerWord.isEmpty()) tw.add(cTriggerWord);
        for (String w : c.triggerWords.get()) {
            if (w != null && !w.isBlank()) tw.add(w.trim().toLowerCase(java.util.Locale.ROOT));
        }
        cTriggerWords = tw.isEmpty() ? java.util.Set.of() : java.util.Set.copyOf(tw);
        // Parse per-spell confidence overrides.
        java.util.Map<String, Double> psc = new java.util.HashMap<>();
        for (String entry : c.perSpellMinConfidence.get()) {
            if (entry == null) continue;
            int eq = entry.indexOf('=');
            if (eq <= 0 || eq >= entry.length() - 1) continue;
            try {
                double v = Double.parseDouble(entry.substring(eq + 1).trim());
                psc.put(entry.substring(0, eq).trim(), Math.max(0.0, Math.min(1.0, v)));
            } catch (NumberFormatException ignored) {}
        }
        cPerSpellConfidence = psc.isEmpty() ? java.util.Map.of() : java.util.Map.copyOf(psc);
        cShowMisses       = c.showMisses.get();
        cEchoSfx          = c.enableEchoSfx.get();
        cStreamerMode     = c.streamerMode.get();
        cSassMode         = c.sassMode.get();
        cCastCooldownChip  = c.castCooldownChip.get();
        cCastCooldownChipY = c.castCooldownChipY.get();
        cCastVignette     = c.castVignette.get();
        cCombatOnly       = c.combatOnly.get();
        cPauseWhenAfk     = c.pauseWhenAfk.get();
        cAfkNanos         = (long) c.afkSeconds.get() * 1_000_000_000L;
        cAlwaysShowHeard  = c.alwaysShowHeard.get();
        cHandsFreeConfirm = c.handsFreeConfirm.get();
        cNoiseGateRms     = c.noiseGateRms.get();
        cSpeechPeakRms    = c.speechPeakRms.get();
        cModelId          = c.modelId.get();
        cGatingMode       = c.gatingMode.get();
        cSuspendUnfocused = c.suspendWhenUnfocused.get();
        cGrammarFloor     = c.grammarFloor.get();
        cCaptureDevice    = c.captureDevice.get();
        cChatRankTag      = c.chatRankTag.get();
        cRestrictToOwned   = c.restrictToOwned.get();
        cHudCorner        = c.hudCorner.get();
        cHudOffsetX       = c.hudOffsetX.get();
        cHudOffsetY       = c.hudOffsetY.get();
        // Indirect call so the dedicated server can load this class without resolving Theme
        // (which transitively pulls in client-only Minecraft GUI classes). Client init wires
        // {@link #themeApplier} at startup; on a server it stays null and we skip.
        Runnable applier = themeApplier;
        if (applier != null) applier.run();
        cOpacity     = (float) Math.max(0.0, Math.min(1.0, c.globalOpacity.get()));
        cBg          = applyOpacity(parseHex(c.bgColor.get(),     0xCC0A0A0A), cOpacity);
        cBorder      = applyOpacity(parseHex(c.borderColor.get(), 0xFF1F1F1F), cOpacity);
        cTextMuted   = applyOpacity(parseHex(c.textMuted.get(),   0xFF8A8A8A), cOpacity);
        cTextToast   = applyOpacity(parseHex(c.textToast.get(),   0xFFFFD75A), cOpacity);
    }

    public static int parseHex(String hex, int fallback) {
        if (hex == null) return fallback;
        String s = hex.trim();
        if (s.startsWith("#")) s = s.substring(1);
        if (s.length() == 6) s = "FF" + s;   // implied opaque
        if (s.length() != 8)  return fallback;
        try {
            return (int) Long.parseLong(s, 16);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int applyOpacity(int argb, float opacity) {
        int origA = (argb >>> 24) & 0xFF;
        int newA  = Math.max(0, Math.min(255, Math.round(origA * opacity)));
        return (newA << 24) | (argb & 0x00FFFFFF);
    }
}
