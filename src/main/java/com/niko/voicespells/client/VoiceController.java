package com.niko.voicespells.client;

import com.niko.voicespells.VoiceSpells;
import com.niko.voicespells.VoiceSpellsConfig;
import com.niko.voicespells.network.CastSpellPayload;
import com.niko.voicespells.spells.SpellCaster;
import com.niko.voicespells.spells.SpellIndex;
import com.niko.voicespells.spells.SpellInfo;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
//? if forge {
/*import com.niko.voicespells.network.Network;
*///?} else {
import net.neoforged.neoforge.network.PacketDistributor;
//?}

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Client-side glue between the microphone, the Vosk recognizer and the server.
 *
 * Capture is gated, not continuous. {@link com.niko.voicespells.client.MicCapture} only holds the
 * device while {@code captureAllowedNow()} says so, which follows {@code gatingMode}: HOLD_ITEM
 * (the default — a spell focus in hand or Curios), HOLD_KEY, HOLD_KEY_AND_ITEM, or ALWAYS_ON. So
 * there IS a keybind, and three of the four modes depend on it; this javadoc used to say
 * "No keybind", which predates the gating work. Frames that pass the gate are fed to Vosk when
 * the noise gate is open. Because a capture device never signals end-of-speech, the recognizer
 * is flushed on the noise gate's open-to-closed edge instead.
 *
 * Threading: {@link #onMicFrame16k} runs on the capture thread; phrase callbacks arrive on the same
 * thread, and we hop to the Minecraft client thread before touching world state or sending
 * packets.
 */
public final class VoiceController {

    /** Frames within this window of the last received frame mean the mic is delivering audio. */
    private static final long HEARING_WINDOW_NANOS = TimeUnit.MILLISECONDS.toNanos(300);
    /** How long the heard-spell toast stays visible (including fade). */
    public  static final long TOAST_DURATION_NANOS = TimeUnit.MILLISECONDS.toNanos(2400);
    public  static final long TOAST_FADE_IN_NANOS  = TimeUnit.MILLISECONDS.toNanos(120);
    public  static final long TOAST_FADE_OUT_NANOS = TimeUnit.MILLISECONDS.toNanos(600);

    private static volatile VoskSession session;
    private static final AtomicBoolean loading = new AtomicBoolean(false);
    private static volatile long lastFrameNanos = 0L;

    // User-facing toggles, both default-on. listeningEnabled drops incoming mic frames at the
    // first check; hudVisible only affects the HUD chip and lets people still cast invisibly.
    private static volatile boolean listeningEnabled = true;
    private static volatile boolean hudVisible       = true;

    /** "READY" / "LOADING" / "ERROR — message" — drives the HUD chip. */
    private static volatile String statusLine = "";
    private static volatile String lastHeard  = "";

    /** Smoothed RMS energy of recent mic frames (0..1) for the HUD's audio meter. */
    private static volatile float audioLevel = 0f;

    /** Nanotime of the last mic frame whose RMS crossed the noise-gate threshold. Used to
     *  keep the gate "open" for a short window after speech ends so mid-word dips don't kill
     *  the utterance. */
    private static volatile long lastLoudFrameNanos = 0L;
    /** How long the noise gate stays open after the last loud frame. Generous enough to span
     *  phoneme transitions in slow speech without re-opening for stray noise. */
    private static final long NOISE_GATE_STICKY_NANOS = 450_000_000L; // 450ms

    // ---- Auto-calibration -------------------------------------------------------------
    /** Running calibration state. Engaged via {@link #startNoiseGateCalibration()}; the
     *  mic-frame path then samples each frame's RMS for a few seconds, picks a threshold
     *  somewhere between observed silence and observed speech, and writes it back into the
     *  config. Avoids the user blindly guessing toml values. */
    private static volatile boolean calibrating = false;
    private static volatile long    calibStartNanos = 0L;
    private static volatile double  calibSum   = 0;
    private static volatile int     calibCount = 0;
    private static volatile double  calibPeak  = 0;
    private static volatile double  lastCalibThreshold = -1;
    public static final long CALIB_DURATION_NANOS = 5_000_000_000L;

    public static boolean isCalibrating() { return calibrating; }
    public static long calibRemainingNanos() {
        return calibrating
            ? Math.max(0, CALIB_DURATION_NANOS - (System.nanoTime() - calibStartNanos))
            : 0L;
    }
    public static double lastCalibThreshold() { return lastCalibThreshold; }

    /** Begin a 5-second calibration window. The mic-frame path samples RMS into the running
     *  totals; when the window ends, a threshold is derived and saved to the config. */
    public static void startNoiseGateCalibration() {
        calibSum = 0; calibCount = 0; calibPeak = 0;
        calibStartNanos = System.nanoTime();
        calibrating = true;
    }

    private static void sampleCalibration(double rms) {
        if (!calibrating) return;
        calibSum  += rms;
        calibCount++;
        if (rms > calibPeak) calibPeak = rms;
        if (System.nanoTime() - calibStartNanos >= CALIB_DURATION_NANOS) finishCalibration();
    }

    private static void finishCalibration() {
        double mean = calibCount > 0 ? calibSum / calibCount : 0;
        // Half the mean works well as a gate floor — it sits between observed silence and
        // observed speech for typical mics. Clamped to a sensible range so a calibration in
        // total silence doesn't disable the gate, and a noisy mic doesn't blow past it.
        double threshold = Math.max(100, Math.min(3000, mean * 0.5));
        try {
            VoiceSpellsConfig.CLIENT.noiseGateRms.set(threshold);
            VoiceSpellsConfig.refreshCache();
        } catch (Throwable ignored) {}
        lastCalibThreshold = threshold;
        calibrating = false;
        VoiceSpells.LOGGER.info("Noise gate calibrated: mean={}, peak={}, set threshold={}",
            String.format("%.0f", mean), String.format("%.0f", calibPeak),
            String.format("%.0f", threshold));
    }

    /** Last position the player moved through, and the nanotime of that move. Used by the
     *  AFK gate so we can pause recognition when the player has been stationary for a while. */
    private static volatile double lastPosX, lastPosY, lastPosZ;
    private static volatile long   lastMovementNanos = 0L;

    /** Sample the player's position once per client tick. Tracks the last meaningful movement
     *  so the AFK check has something to compare against. */
    public static void tickAfkPosition(net.minecraft.world.entity.player.Player player) {
        if (player == null) return;
        double dx = player.getX() - lastPosX;
        double dy = player.getY() - lastPosY;
        double dz = player.getZ() - lastPosZ;
        if (lastMovementNanos == 0L || (dx*dx + dy*dy + dz*dz) > 0.0001) {
            lastPosX = player.getX();
            lastPosY = player.getY();
            lastPosZ = player.getZ();
            lastMovementNanos = System.nanoTime();
        }
    }

    /** Has the player been stationary for longer than the configured AFK threshold? */
    private static boolean isAfk() {
        if (lastMovementNanos == 0L) return false;
        return (System.nanoTime() - lastMovementNanos) > VoiceSpellsConfig.cAfkNanos;
    }

    /** Was the player recently in combat? Uses vanilla's {@code getLastHurtByMobTimestamp},
     *  set whenever a mob last hurt the player (~10s window). */
    private static boolean isInCombat() {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.player == null || mc.player.level() == null) return false;
        long nowTicks = mc.player.level().getGameTime();
        long lastHurtTick = mc.player.getLastHurtByMobTimestamp();
        return lastHurtTick > 0 && (nowTicks - lastHurtTick) < 200; // hurt within ~10s
    }

    /** Rolling history of recent audio levels for the debug-monitor waveform. Sampled at ~20Hz
     *  from the client tick handler. Newest sample lives at {@code waveformIdx - 1} (mod len)
     *  and the array forms a circular buffer. */
    private static final int  WAVEFORM_LEN = 64;
    private static final float[] WAVEFORM = new float[WAVEFORM_LEN];
    private static int  waveformIdx = 0;
    private static long lastWaveformSampleNanos = 0L;

    /** Returns a chronologically-ordered snapshot of the waveform (oldest → newest). */
    public static float[] waveformSnapshot() {
        float[] out = new float[WAVEFORM_LEN];
        int start = waveformIdx;
        for (int i = 0; i < WAVEFORM_LEN; i++) {
            out[i] = WAVEFORM[(start + i) % WAVEFORM_LEN];
        }
        return out;
    }

    /** Called from the client tick handler. Samples the current audio level into the waveform
     *  buffer at ~20Hz so the visualisation reads as a steady scrolling trace. */
    public static void sampleWaveformIfDue() {
        long now = System.nanoTime();
        if (now - lastWaveformSampleNanos < 50_000_000L) return;
        lastWaveformSampleNanos = now;
        WAVEFORM[waveformIdx] = audioLevel;
        waveformIdx = (waveformIdx + 1) % WAVEFORM_LEN;
    }

    /** State for the "spell just cast" toast on the HUD. */
    private static volatile String lastCastDisplay = "";
    private static volatile long   lastCastNanos   = 0L;
    /** School of the last cast spell — drives the toast color and the echo chime instrument. */
    private static volatile String lastCastSchool  = "";

    /** Spell-id dedup. Vosk in grammar mode tends to emit the same recognized phrase several
     *  times — partial → final, plus the occasional residual partial after an utterance
     *  boundary. We drop duplicates of the same spell within a window (configurable via
     *  recognition.dedupMillis) so each "say it once" yields exactly one cast.
     *
     *  Two anchors are kept, and <b>both are only ever written on a real dispatch</b> — never on
     *  a suppressed repeat:
     *  - {@code lastDispatchedNanos} is the time of the last actual dispatch. With
     *    {@code cDedupNanos} it forms a fixed window that always lapses that long after the
     *    cast happened.
     *  - {@code lastDispatchedFirstNanos} anchors the echo lockout — a hard absolute window in
     *    which same-spell repeats are dropped no matter how spread out the events are.
     *  Slow Vosk emissions where partial → final spans longer than either window are handled by
     *  {@link #lastDispatchedUtterance} instead, which compares utterance identity rather than
     *  elapsed time and so cannot be defeated by timing at all.
     *
     *  <p>{@code lastDispatchedNanos} deliberately does <b>not</b> slide on suppressed repeats.
     *  It used to, and that made the window self-perpetuating under a continuously open mic:
     *  the same spell could never be cast twice in a row, only "unlocked" by saying a different
     *  spell first.
     */
    private static volatile String lastDispatchedSpellId    = "";
    private static volatile long   lastDispatchedNanos      = 0L;
    private static volatile long   lastDispatchedFirstNanos = 0L;

    /** Utterance-boundary tracking: Vosk emits partials during an utterance and a final at the
     *  silence boundary, then the next partial begins a NEW utterance. {@link #utteranceId}
     *  increments every time we transition from "last event was final" → "next event is a
     *  partial". Combined with {@link #lastDispatchedUtterance}, this lets us dedup *all*
     *  repeats within the same utterance regardless of how slowly Vosk emits them — covering
     *  the case where partial→final spans longer than the configured echo lockout. */
    private static volatile int     utteranceId             = 0;
    private static volatile boolean lastEventWasFinal       = false;
    private static volatile int     lastDispatchedUtterance = -1;

    /** Cast queue entries (FIFO). Each holds the spell id + the nanotime it was queued so the
     *  drainer can drop stale entries. */
    public record QueueEntry(ResourceLocation id, long atNanos) {}
    /** Multi-slot cast queue. While the player is casting, additional recognized spells get
     *  pushed onto the back; the tick drainer pops one off the front each time the player
     *  isn't casting. Bounded by {@link VoiceSpellsConfig#cCastQueueSize} — when full, the
     *  oldest entry is dropped to make room (most-recent-wins overflow). */
    private static final Deque<QueueEntry> CAST_QUEUE = new ArrayDeque<>();
    private static final long MAX_QUEUE_AGE_NANOS = 1500L * 1_000_000L;

    /** One entry per recognition attempt, for the debug monitor. {@code matched} is the spell
     *  id (optionally suffixed) or null when nothing matched. {@code confidence} is the Vosk
     *  average word confidence for final results (1.0 for partials). {@code tier} is the
     *  single-letter match-tier ({@code E/F/S/P/L}, space for n/a). */
    public record RecognitionEvent(long nanoTime, String heard, String matched,
                                    double confidence, char tier) {}

    /** One entry per successfully dispatched cast, for the rolling HUD history strip. */
    public record HistoryEntry(String display, long nanoTime) {}
    private static final int HISTORY_MAX = 3;
    private static final Deque<HistoryEntry> HISTORY = new ArrayDeque<>();

    /** Consecutive-cast counter. Increments on each successful dispatch, resets if no cast
     *  fires within {@link #STREAK_TIMEOUT_NANOS}. Cosmetic; surfaced on the HUD when ≥ 2. */
    private static volatile int  castStreak = 0;
    private static volatile long lastStreakNanos = 0L;
    private static final long STREAK_TIMEOUT_NANOS = 30L * 1_000_000_000L; // 30s of silence resets

    public static int  castStreak()      { return currentStreak(); }

    /** Recompute the current effective streak — folds in the "timeout reset" so accessors
     *  don't have to do it themselves. */
    private static int currentStreak() {
        if (castStreak <= 0) return 0;
        if (System.nanoTime() - lastStreakNanos > STREAK_TIMEOUT_NANOS) return 0;
        return castStreak;
    }

    /** Increment the streak counter on a successful dispatch, folding in any inactivity
     *  reset so a long gap between casts starts the streak fresh at 1. */
    private static void bumpStreak(long now) {
        if (lastStreakNanos == 0L || (now - lastStreakNanos) > STREAK_TIMEOUT_NANOS) {
            castStreak = 1;
        } else {
            castStreak++;
        }
        lastStreakNanos = now;
    }

    /** Newest-first snapshot of recent casts (max {@link #HISTORY_MAX}) for the HUD strip. */
    public static List<HistoryEntry> spellHistory() {
        synchronized (HISTORY) { return new ArrayList<>(HISTORY); }
    }

    /** Miss-toast state — set when a final result matched no spell and showMisses is on. */
    private static volatile String lastMissText  = "";
    private static volatile long   lastMissNanos = 0L;

    /** "Did you mean ...?" prompt state. Populated when a final result misses but lands close
     *  to an indexed spell (via fuzzy/phonetic). HUD renders a small chip with the candidate
     *  and a hint to add it as an alias. Cleared after the user clicks the chip or it ages out. */
    public record AliasSuggestion(String heardPhrase, ResourceLocation candidateSpellId,
                                   String candidateDisplay, long shownAtNanos) {}
    private static volatile AliasSuggestion lastSuggestion = null;
    public static AliasSuggestion lastSuggestion() { return lastSuggestion; }
    public static void clearSuggestion() { lastSuggestion = null; }
    /** How long the alias suggestion chip stays visible after appearing. */
    public static final long SUGGESTION_LIFETIME_NANOS = 8_000_000_000L;

    /** Sass mode lines — picked at random when {@link VoiceSpellsConfig#cSassMode} is on and
     *  a final result matched no spell. Replaces the literal heard text in the miss toast
     *  for some misses (not all, so a real "I want to see what was heard" debug session still
     *  surfaces the raw phrase). */
    private static final String[] SASS_LINES = {
        "Was that a spell?",
        "Try again, mortal.",
        "The arcane shrugs.",
        "Words have power. Find them.",
        "Mumble harder.",
        "Hmm. Not in the grimoire.",
        "Did the mic catch a bird?",
        "Speak with conviction.",
    };
    private static final java.util.Random SASS_RNG = new java.util.Random();
    public static String lastMissText()  { return lastMissText;  }
    public static long   lastMissNanos() { return lastMissNanos; }
    private static final int MAX_EVENTS = 30;
    private static final Deque<RecognitionEvent> RECENT = new ArrayDeque<>();

    private VoiceController() {}

    public static String statusLine() { return statusLine; }
    public static String lastHeard()  { return lastHeard;  }

    /** Vosk partials accumulate across a long utterance — if the player streams words without
     *  a pause we can end up with a multi-line string. Keep a tail-clamped copy so any screen
     *  that displays this without its own clamping still renders sanely. */
    private static String clampHeard(String raw) {
        if (raw == null) return "";
        if (raw.length() <= 80) return raw;
        return raw.substring(raw.length() - 80);
    }
    public static boolean isHudVisible() { return hudVisible; }
    public static float   audioLevel() { return audioLevel; }
    public static String  lastCastDisplay() { return lastCastDisplay; }
    public static long    lastCastNanos()   { return lastCastNanos;   }
    public static String  lastCastSchool()  { return lastCastSchool;  }
    public static String  lastDispatchedSpellId() { return lastDispatchedSpellId; }

    /** Recognition latency samples: time from "first heard the phrase" → "dispatched it to
     *  the server". Last {@link #LATENCY_WINDOW} entries kept; the codex shows the average. */
    private static final int  LATENCY_WINDOW = 32;
    private static final long[] LATENCY_BUF  = new long[LATENCY_WINDOW];
    private static int  latencyIdx = 0;
    private static int  latencySize = 0;
    /** Tracks the nanos at which the current utterance's first matching event arrived. Cleared
     *  on utterance boundary. */
    private static volatile long utteranceFirstHeardNanos = 0L;

    /** Median speech-to-cast time across the last {@link #LATENCY_WINDOW} casts, in ms. Median
     *  rather than mean because the underlying clock starts at the FIRST partial of the
     *  utterance — if you hesitate or say filler before the spell, that time counts. A few
     *  long utterances would skew an arithmetic mean badly; the median gives the typical
     *  cast time you actually feel. Returns -1 when no casts have happened yet. */
    public static synchronized double averageLatencyMs() {
        if (latencySize == 0) return -1;
        long[] sorted = new long[latencySize];
        System.arraycopy(LATENCY_BUF, 0, sorted, 0, latencySize);
        java.util.Arrays.sort(sorted);
        long median = sorted.length % 2 == 1
            ? sorted[sorted.length / 2]
            : (sorted[sorted.length / 2 - 1] + sorted[sorted.length / 2]) / 2;
        return median / 1_000_000.0;
    }

    private static synchronized void recordLatencyNanos(long deltaNanos) {
        LATENCY_BUF[latencyIdx] = deltaNanos;
        latencyIdx = (latencyIdx + 1) % LATENCY_WINDOW;
        if (latencySize < LATENCY_WINDOW) latencySize++;
    }

    /** Re-dispatch the last spell we cast via voice. Bound to the quick-recast keybind so the
     *  player can repeat a cast without speaking again. Goes through the same network path,
     *  so server-side rate limit + cooldown + preflight all still apply. */
    public static void quickRecastLast() {
        ResourceLocation id;
        try {
            if (lastDispatchedSpellId == null || lastDispatchedSpellId.isEmpty()) return;
//? if forge {
/*            id = ResourceLocation.tryParse(lastDispatchedSpellId);
*///?} else {
            id = ResourceLocation.parse(lastDispatchedSpellId);
//?}
        } catch (Throwable t) { return; }
//? if forge {
/*        // tryParse yields null instead of throwing on a malformed id.
        if (id == null) return;
*///?}
        if (!clientCanCast(id)) return;
        ResourceLocation dispatched = id;
        int totalForTrigger = VoiceStats.totalCasts();
        int streakForTrigger = currentStreak();
        Minecraft.getInstance().execute(() -> {
            SpellSelector.select(dispatched);
            dispatchCast(dispatched, 1.0f, totalForTrigger, streakForTrigger);
            if (VoiceSpellsConfig.cEchoSfx) playEchoChime(dispatched);
        });
    }

    /** Display name of the spell at the front of the cast queue (next to fire). Empty if
     *  the queue is empty. */
    public static String queuedSpellDisplay() {
        QueueEntry head;
        synchronized (CAST_QUEUE) { head = CAST_QUEUE.peekFirst(); }
        return head == null ? "" : displayNameFor(head.id());
    }
    /** Number of spells currently parked in the cast queue. */
    public static int queuedCount() {
        synchronized (CAST_QUEUE) { return CAST_QUEUE.size(); }
    }

    /** Snapshot of the recent recognition log, newest first, for the debug monitor screen. */
    public static List<RecognitionEvent> recentEvents() {
        synchronized (RECENT) { return new ArrayList<>(RECENT); }
    }

    private static void recordEvent(String heard, String matched, double confidence) {
        recordEvent(heard, matched, confidence, ' ');
    }

    private static void recordEvent(String heard, String matched, double confidence, char tier) {
        synchronized (RECENT) {
            RECENT.addFirst(new RecognitionEvent(System.nanoTime(), heard, matched, confidence, tier));
            while (RECENT.size() > MAX_EVENTS) RECENT.removeLast();
        }
    }

    /** True if we received a frame in the recent past — i.e. capture is live. */
    public static boolean isHearingNow() {
        long t = lastFrameNanos;
        return t != 0L && (System.nanoTime() - t) < HEARING_WINDOW_NANOS;
    }

    /** Flip the master listening toggle. While off, mic frames are dropped at the door and the
     *  recognizer is flushed so the next on-state starts clean. */
    public static boolean toggleListening() {
        listeningEnabled = !listeningEnabled;
        if (!listeningEnabled) {
            VoskSession s = session;
            if (s != null) {
                Thread t = new Thread(() -> { s.flush(); s.reset(); }, "VoiceSpells-Disable-Flush");
                t.setDaemon(true);
                t.start();
            }
            lastFrameNanos = 0L;
        }
        return listeningEnabled;
    }

    public static boolean toggleHud() {
        hudVisible = !hudVisible;
        return hudVisible;
    }

    /** Kick off model loading off the main thread. Safe to call multiple times. */
    public static void preloadAsync() {
        if (session != null) return;
        if (!loading.compareAndSet(false, true)) return;
        statusLine = "LOADING";
        new Thread(VoiceController::loadModel, "VoiceSpells-Engine-Loader").start();
    }

    /** Called on every captured mic frame, including empty
     *  arrays which signal end-of-transmission. */
    /**
     * OpenAL capture path — frames are already at the recognizer's 16 kHz, and the stream is
     * continuous.
     *
     * <p>That continuity is the real difference from SVC. SVC hands us an empty frame to mark
     * end-of-transmission, which is what triggers the flush that turns a pending utterance into a
     * final result. A raw capture device never stops sending, so there is no such marker: instead
     * the flush fires when the noise gate closes after having been open, which is the same
     * "speech just ended" signal derived locally rather than handed to us.
     */
    /**
     * Whether captured audio may reach the recognizer right now.
     *
     * <p>This is the capture-time gate, and it is deliberately stricter than the dispatch-time
     * filters ({@code combatOnly}, {@code pauseWhenAfk}) which only discard results after the audio
     * has already been recognised. In HOLD_KEY and HOLD_ITEM the recognizer never receives the
     * audio at all — the difference matters for both CPU and for what "the mic is off" means.
     *
     * <p>Being outside a world counts as closed regardless of mode: there is nothing to cast at,
     * and it means the mic is not live while sitting on the title screen.
     */
    private static boolean captureArmed() {
        if (!listeningEnabled) return false;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return false;
        if (VoiceSpellsConfig.cSuspendUnfocused && (!mc.isWindowActive() || mc.isPaused())) {
            return false;
        }
        switch (VoiceSpellsConfig.cGatingMode) {
            case HOLD_KEY:  return ClientEvents.isPushToTalkDown();
            case HOLD_ITEM: return com.niko.voicespells.client.OwnedSpells.holdingSpellFocus();
            // Both, and this is the default. See the config comment: holding a staff is not
            // evidence you meant to cast, because you are most likely to be talking to people
            // exactly when you are armed.
            case HOLD_KEY_AND_ITEM:
                return ClientEvents.isPushToTalkDown()
                    && com.niko.voicespells.client.OwnedSpells.holdingSpellFocus();
            default:        return true;
        }
    }

    /** Exposed for the HUD's mic-state icon and for diagnostics. */
    public static boolean isArmed() { return captureArmed(); }

    // ----- Calibration / transcription mode ------------------------------------------------

    private static volatile boolean transcription = false;
    private static volatile String lastTranscriptShown = "";

    public static boolean isTranscribing() { return transcription; }

    /**
     * Toggle free-dictation mode: the recognizer drops its grammar and every result is printed to
     * chat instead of being matched or cast.
     *
     * <p>Exists because grammar mode structurally cannot answer "what did it hear?". With a
     * grammar the recognizer can only return phrases from that grammar, so a misheard spell tells
     * you which of your spells you were closest to, not the words you actually produced. Choosing
     * a good alias needs the latter.
     *
     * @return the new state
     */
    public static boolean toggleTranscription() {
        transcription = !transcription;
        lastTranscriptShown = "";
        VoskSession s = session;
        if (s != null) {
            boolean on = transcription;
            Thread t = new Thread(() -> {
                if (on) s.rebuildUnconstrained();
                else    s.rebuildGrammar(currentGrammar());
            }, "VoiceSpells-Grammar-Calibrate");
            t.setDaemon(true);
            t.start();
        }
        return transcription;
    }

    private static void reportTranscription(String phrase, boolean isFinal, double confidence) {
        if (phrase == null || phrase.isBlank()) return;
        // Partials repeat every frame as the utterance grows; only surface changes, or chat
        // becomes unreadable.
        if (!isFinal && phrase.equals(lastTranscriptShown)) return;
        lastTranscriptShown = phrase;
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.player == null) return;
            Component line = isFinal
                ? Component.literal("heard: ")
                    .withStyle(net.minecraft.ChatFormatting.DARK_GRAY)
                    .append(Component.literal(phrase)
                        .withStyle(net.minecraft.ChatFormatting.AQUA))
                    .append(Component.literal(String.format(java.util.Locale.ROOT,
                            "  (%.0f%%)", confidence * 100))
                        .withStyle(net.minecraft.ChatFormatting.DARK_GRAY))
                : Component.literal("  … " + phrase)
                    .withStyle(net.minecraft.ChatFormatting.DARK_GRAY);
            mc.player.displayClientMessage(line, false);
        });
    }

    public static void onMicFrame16k(short[] pcm) {
        if (pcm == null || pcm.length == 0) return;
        if (!captureArmed()) {
            // Gate closed. If speech was in flight, settle it rather than leaving a half-recognised
            // utterance to bleed into whatever is said after the gate reopens.
            if (gateWasOpen) {
                gateWasOpen = false;
                VoskSession s = session;
                if (s != null) {
                    Thread t = new Thread(() -> { s.flush(); s.reset(); }, "VoiceSpells-Flush");
                    t.setDaemon(true);
                    t.start();
                }
            }
            audioLevel *= 0.5f;
            return;
        }
        long now = System.nanoTime();
        lastFrameNanos = now;
        double rms = updateAudioLevel(pcm);
        sampleCalibration(rms);
        VoskSession s = session;
        if (s == null) {
            if (SpellIndex.isReady()) preloadAsync();
            return;
        }
        boolean loud = rms >= VoiceSpellsConfig.cNoiseGateRms;
        if (loud) lastLoudFrameNanos = now;
        boolean gateOpen = lastLoudFrameNanos != 0L
            && (now - lastLoudFrameNanos) <= NOISE_GATE_STICKY_NANOS;

        if (gateOpen) {
            gateWasOpen = true;
            s.feed16k(pcm);
            return;
        }
        // Gate just closed after speech: flush so the pending utterance settles into a final
        // result, exactly as SVC's empty frame used to do. Flushing off-thread keeps the capture
        // thread free to keep reading the device.
        if (gateWasOpen) {
            gateWasOpen = false;
            Thread t = new Thread(() -> { s.flush(); s.reset(); }, "VoiceSpells-Flush");
            t.setDaemon(true);
            t.start();
            audioLevel *= 0.2f;
        }
    }

    /** Tracks the open→closed edge of the noise gate for the capture path's flush trigger. */
    private static volatile boolean gateWasOpen = false;

    /**
     * Compute the frame's RMS energy and fold it into a smoothed level used by the HUD's audio
     * meter. 16-bit signed PCM tops out at 32767; normal speech RMS lives around 1500–6000, so
     * dividing by 6000 gives roughly 0..1 with loud speech saturating at 1. Returns the raw RMS
     * so the caller can also use it as a noise-gate input.
     */
    private static double updateAudioLevel(short[] pcm) {
        long sumSq = 0;
        for (short v : pcm) sumSq += (long) v * v;
        double rms = Math.sqrt((double) sumSq / pcm.length);
        float normalized = (float) Math.min(1.0, rms / 6000.0);
        audioLevel = 0.55f * audioLevel + 0.45f * normalized;
        return rms;
    }

    /**
     * Apply a config change without a game restart: re-read the spell index (picks up
     * customPhrases edits) and, if a recognizer is live, rebuild its grammar against the new
     * phrase set on a worker thread. Called from the config screen's Save and from the external
     * file-reload hook.
     */
    public static void onConfigChanged() {
        SpellIndex.reindex();
        SpellInfo.clearCache(); // drop cached display names so spell renames / aliases re-resolve
        VoskSession s = session;
        if (s != null) {
            Thread t = new Thread(() -> s.rebuildGrammar(currentGrammar()),
                "VoiceSpells-Grammar-Reload");
            t.setDaemon(true);
            t.start();
        }
    }

    // Resolved once at class init — looked up every tick via tryDrainCastQueue, and once per
    // recognised phrase. The per-tick reflection chain (Class.forName + getMethod) measurably
    // shows up in long-running profiles; caching keeps the hot path to a single virtual call.
    private static final java.lang.reflect.Method IS_CASTING_M;
    static {
        java.lang.reflect.Method m = null;
        try {
            Class<?> cmd = Class.forName("io.redspace.ironsspellbooks.api.magic.ClientMagicData");
            m = cmd.getMethod("isCasting");
        } catch (Throwable ignored) {}
        IS_CASTING_M = m;
    }

    /** Reflective check against {@code ClientMagicData.isCasting()} (the client mirror of the
     *  server-side MagicData). Returns false if Iron's Spells isn't loaded or its API shape
     *  changes — better to let the cast through than to silently swallow it. */
    private static boolean isClientCasting() {
        if (IS_CASTING_M == null) return false;
        try {
            return (boolean) IS_CASTING_M.invoke(null);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Client-side mana + cooldown check for a single spell. Mirrors the server-side
     *  preflight in SpellCaster but runs on the client to avoid the network round-trip for
     *  impossible casts. Permissive on reflection failure — the server's preflight is the
     *  authoritative fallback. */
    private static boolean clientCanCast(ResourceLocation spellId) {
        if (!VoiceSpellsConfig.cClientPreflight) return true;
        try {
            Class<?> cmdCls = Class.forName("io.redspace.ironsspellbooks.api.magic.ClientMagicData");
            Class<?> registryCls = Class.forName("io.redspace.ironsspellbooks.api.registry.SpellRegistry");
            Class<?> spellCls = Class.forName("io.redspace.ironsspellbooks.api.spells.AbstractSpell");

            // Cooldown
            Object cooldowns = null;
            for (String m : new String[]{ "getPlayerCooldowns", "getCooldowns" }) {
                try {
                    cooldowns = cmdCls.getMethod(m).invoke(null);
                    break;
                } catch (NoSuchMethodException ignored) {}
            }
            if (cooldowns != null) {
                try {
                    java.lang.reflect.Method onCd = cooldowns.getClass().getMethod("isOnCooldown", String.class);
                    if ((boolean) onCd.invoke(cooldowns, spellId.toString())) return false;
                } catch (Throwable ignored) {}
            }

            // Mana
            float mana = Float.MAX_VALUE;
            for (String m : new String[]{ "getPlayerMana", "getMana" }) {
                try {
                    mana = ((Number) cmdCls.getMethod(m).invoke(null)).floatValue();
                    break;
                } catch (NoSuchMethodException ignored) {}
            }
            Object spell = registryCls.getMethod("getSpell", String.class).invoke(null, spellId.toString());
            if (spell != null) {
                try {
                    int cost = ((Number) spellCls.getMethod("getManaCost", int.class).invoke(spell, 1)).intValue();
                    if (cost > mana) return false;
                } catch (Throwable ignored) {}
            }
            return true;
        } catch (Throwable ignored) {
            return true; // permissive — let the server's preflight be the authoritative gate
        }
    }

    /**
     * From a loadout's ordered spell list, pick the first one that's actually castable —
     * not on cooldown and within the player's current mana. Reflects against the client-side
     * ClientMagicData / SpellRegistry classes; if any of that's missing we fall through to
     * the first spell (best-effort) so the cast still happens.
     */
    private static ResourceLocation pickCastableFromLoadout(List<ResourceLocation> ids) {
        if (ids == null || ids.isEmpty()) return null;
        try {
            Class<?> cmdCls      = Class.forName("io.redspace.ironsspellbooks.api.magic.ClientMagicData");
            Class<?> registryCls = Class.forName("io.redspace.ironsspellbooks.api.registry.SpellRegistry");
            Class<?> spellCls    = Class.forName("io.redspace.ironsspellbooks.api.spells.AbstractSpell");

            // Mana — try a few likely accessor names (API has churned across versions).
            float mana = Float.MAX_VALUE; // default to "enough" if we can't read
            for (String m : new String[]{ "getPlayerMana", "getMana" }) {
                try {
                    java.lang.reflect.Method g = cmdCls.getMethod(m);
                    mana = ((Number) g.invoke(null)).floatValue();
                    break;
                } catch (NoSuchMethodException ignored) { /* try next */ }
            }

            // Cooldown source — may be exposed as getCooldowns() or playerCooldowns field.
            Object cooldowns = null;
            for (String m : new String[]{ "getPlayerCooldowns", "getCooldowns" }) {
                try {
                    java.lang.reflect.Method g = cmdCls.getMethod(m);
                    cooldowns = g.invoke(null);
                    break;
                } catch (NoSuchMethodException ignored) { /* try next */ }
            }

            java.lang.reflect.Method getSpell = registryCls.getMethod("getSpell", String.class);

            for (ResourceLocation rid : ids) {
                Object spell = getSpell.invoke(null, rid.toString());
                if (spell == null) continue;
                String actualId = (String) spellCls.getMethod("getSpellId").invoke(spell);
                if (!rid.toString().equals(actualId)) continue; // unknown spell sentinel

                // On cooldown? Skip.
                if (cooldowns != null) {
                    try {
                        java.lang.reflect.Method onCd = cooldowns.getClass().getMethod("isOnCooldown", String.class);
                        if ((boolean) onCd.invoke(cooldowns, rid.toString())) continue;
                    } catch (Throwable ignored) { /* no isOnCooldown — proceed */ }
                }

                // Mana cost > current? Skip. Use level 1 since we don't have the spellbook
                // level on the client; this slightly under-counts cost for upgraded spellbooks
                // but the server's preflight will catch a real shortfall.
                try {
                    java.lang.reflect.Method getCost = spellCls.getMethod("getManaCost", int.class);
                    int cost = ((Number) getCost.invoke(spell, 1)).intValue();
                    if (cost > mana) continue;
                } catch (Throwable ignored) { /* no manaCost — proceed */ }

                return rid;
            }
            return null;
        } catch (Throwable t) {
            // Reflection unavailable — fall back to the first id so the loadout still casts.
            VoiceSpells.LOGGER.debug("Loadout castability check failed: {}", t.toString());
            return ids.get(0);
        }
    }

    /** Latest snapshot of "spells the player currently owns" — populated by
     *  {@link #refreshOwnedSpellsIfDue()} every ~1s when restrictToOwned is on. Reads are
     *  lock-free volatile; the set instance is replaced atomically when the scan finishes. */
    private static volatile java.util.Set<String> ownedSpellIds = java.util.Set.of();
    /** Whether the most recent owned-spell scan produced trustworthy data. Starts false and
     *  flips true only after a successful scan. When false the equipped-only gate fails OPEN —
     *  a permanent Iron's reflection break (or the pre-first-scan startup window) must not
     *  silently block every voice cast. The server-side cast check remains authoritative. */
    private static volatile boolean ownedScanReliable = false;
    private static long lastOwnedScanNanos    = 0L;
    private static int  lastOwnedSignature    = 0;
    private static final long OWNED_SCAN_INTERVAL_NANOS = 1_000_000_000L; // 1s

    /**
     * Periodic owned-spell scan. Called from the client tick. The owned set is consumed by the
     * dispatch-time equipped-only gate; it no longer drives a Vosk grammar rebuild (the grammar
     * stays broad to keep noise-mapping diluted across the full registry).
     */
    public static void refreshOwnedSpellsIfDue() {
        if (!VoiceSpellsConfig.cRestrictToOwned) {
            // Feature off — clear the cache and drop reliability so a later re-enable starts
            // clean (fails open until its first fresh scan rather than reusing stale data).
            if (!ownedSpellIds.isEmpty() || ownedScanReliable) {
                ownedSpellIds = java.util.Set.of();
                lastOwnedSignature = 0;
                ownedScanReliable = false;
            }
            return;
        }
        long now = System.nanoTime();
        if (now - lastOwnedScanNanos < OWNED_SCAN_INTERVAL_NANOS) return;
        lastOwnedScanNanos = now;
        java.util.Optional<java.util.Set<String>> result =
            com.niko.voicespells.client.OwnedSpells.scan();
        if (result.isEmpty()) {
            // Couldn't scan reliably this tick (player not loaded yet, Iron's reflection
            // unavailable, or a reflection mismatch). Mark the owned data UNRELIABLE so the
            // dispatch gate fails OPEN — better a rare ghost HUD entry than every cast blocked.
            // Leave the last-known set untouched so a one-tick blip doesn't thrash the cache.
            ownedScanReliable = false;
            return;
        }
        ownedScanReliable = true;
        java.util.Set<String> fresh = result.get();
        int sig = fresh.hashCode(); // HashSet.hashCode is stable across iterations
        if (sig != lastOwnedSignature) {
            ownedSpellIds = fresh;
            lastOwnedSignature = sig;
            // The castable set actually changed, so the grammar is now wrong. This is the only
            // thing that triggers a rebuild — never per tick. Rebuilding is not free (it
            // constructs a new Recognizer, though it keeps the loaded Model), so it is gated on a
            // real equipment change rather than on time.
            rebuildGrammarForOwned(fresh);
        }
    }


    /**
     * Send the cast, preferring Iron's Spells' own client-to-server path.
     *
     * <p>{@link ClientCast} makes the server build the whole cast from its own view of the
     * player's inventory, so nothing about the spell is taken on the client's word. It is also the
     * path that lets this mod work without a server-side component at all.
     *
     * <p>The mod's own packet stays as the fallback, deliberately. The Iron's class it reflects
     * against is internal rather than API, so an Iron's update can rename it out from under us; and
     * the client path depends on both sides ordering their spell slots identically, which is an
     * assumption rather than a guarantee. Falling back costs nothing and keeps casting working in
     * both cases.
     */
    private static void dispatchCast(ResourceLocation spellId, float volume,
                                     int totalForTrigger, int streakForTrigger) {
        // Order matters, and it used to be wrong. ClientCast.tryCast() was tried FIRST and
        // returned early on success, so on NeoForge — the published target — a voice cast went out
        // as Iron's Spells' own packet and this mod's server side never saw it. Everything
        // SpellCaster.cast() does was therefore silently inert: serverBlockedSpells, the
        // allowlist, maxCastsPerSecond, castMode, the voice_cast advancements, the admin log and
        // /voicespells follow. An operator could ban a spell, see it listed as banned in the toml,
        // and still be voice-cast. Forge never had the problem only because tryCast() is a
        // no-op there.
        //
        // So: if the server runs Incantation, always send our own payload and let it validate.
        // The Iron's Spells path stays for the case it was actually added for — a client casting
        // on a server that does not have this mod, where there is no server-side config to
        // enforce in the first place.
        if (serverHandlesCasts()) {
            sendCastPayload(spellId, volume, totalForTrigger, streakForTrigger);
            return;
        }
        if (ClientCast.tryCast(spellId)) return;
        // Neither route is open: no server-side Incantation, and Iron's own path unavailable
        // (spell not in an equipped container, or its internals moved). Sending the payload now
        // would throw, since the channel is not negotiated.
        VoiceSpells.LOGGER.debug(
            "Cast {} not dispatched: server has no Incantation channel and the Iron's Spells "
            + "client path was unavailable", spellId);
    }

    /**
     * True when the connected server can actually handle {@link CastSpellPayload} — i.e. it runs
     * Incantation and will validate the cast.
     *
     * <p>Since 0.10.2 the channel is optional, so a client can be connected to a server without
     * this mod. Sending into a channel the server never negotiated throws, so this is also what
     * keeps the fallback from becoming a crash.
     */
    private static boolean serverHandlesCasts() {
        try {
            net.minecraft.client.multiplayer.ClientPacketListener conn =
                Minecraft.getInstance().getConnection();
            if (conn == null) return false;
//? if forge {
/*            return Network.CHANNEL.isRemotePresent(conn.getConnection());
*///?} else {
            return net.neoforged.neoforge.network.registration.NetworkRegistry
                .hasChannel(conn, CastSpellPayload.TYPE.id());
//?}
        } catch (Throwable t) {
            // Never let a probe decide a cast fails outright; fall through to the Iron's path.
            VoiceSpells.LOGGER.debug("Channel probe failed: {}", t.toString());
            return false;
        }
    }

    /** Single place the cast packet leaves the client, so the loader split lives here rather than
     *  at each of the call sites it used to be duplicated across. */
    private static void sendCastPayload(ResourceLocation spellId, float volume,
                                        int totalForTrigger, int streakForTrigger) {
//? if forge {
/*        Network.sendToServer(
            new CastSpellPayload(spellId, volume, totalForTrigger, streakForTrigger));
*///?} else {
        PacketDistributor.sendToServer(
            new CastSpellPayload(spellId, volume, totalForTrigger, streakForTrigger));
//?}
    }

    /**
     * The grammar the recognizer should be using right now.
     *
     * <p>Falls back to the full phrase list whenever ownership is unknown or unreliable — a failed
     * scan must never be allowed to shrink the grammar, because a grammar narrowed to the wrong
     * set is far worse than one that is merely too broad: the spell the player is actually holding
     * would not be in it at all.
     */
    /** Read-only view of the equipped-spell set, for /voicespells grammar. */
    public static java.util.Set<String> ownedSpellIdsView() {
        java.util.Set<String> owned = ownedSpellIds;
        return owned == null ? java.util.Set.of() : java.util.Set.copyOf(owned);
    }

    /** The phrase list the recognizer is currently listening for, for /voicespells grammar. */
    public static java.util.List<String> activeGrammarView() {
        return currentGrammar();
    }

    /** True when this phrase resolves to a spell the player can actually cast right now — i.e.
     *  it is a real target rather than one of the decoys padding the grammar to the floor. */
    public static boolean phraseIsCastable(String phrase) {
        // Exact match only: this is a display helper for a grammar phrase we generated ourselves,
        // so the fuzzy/phonetic tiers would only add false positives to the listing.
        var hit = SpellIndex.lookupExactWithTier(phrase);
        if (hit.isEmpty()) return false;
        java.util.Set<String> owned = ownedSpellIds;
        return owned != null && owned.contains(hit.get().id().toString());
    }

    private static java.util.List<String> currentGrammar() {
        java.util.Set<String> owned = ownedSpellIds;
        if (!ownedScanReliable || owned == null || owned.isEmpty()) return SpellIndex.getPhrases();
        return SpellIndex.phrasesFor(owned, VoiceSpellsConfig.cGrammarFloor);
    }

    /**
     * Swap the recognizer onto a grammar narrowed to what the player can currently cast, padded to
     * {@code grammarFloor} with decoys. Runs off-thread because building a Recognizer blocks, and
     * this is called from the client tick.
     */
    private static void rebuildGrammarForOwned(java.util.Set<String> owned) {
        VoskSession s = session;
        if (s == null) return;
        java.util.Set<String> snapshot = java.util.Set.copyOf(owned);
        Thread t = new Thread(() -> {
            java.util.List<String> phrases =
                SpellIndex.phrasesFor(snapshot, VoiceSpellsConfig.cGrammarFloor);
            s.rebuildGrammar(phrases);
        }, "VoiceSpells-Grammar");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Called every client tick. While the queue has fresh entries and the player isn't
     * casting, pop the next one and dispatch it. Stale entries are quietly discarded.
     */
    public static void tryDrainCastQueue() {
        QueueEntry entry;
        synchronized (CAST_QUEUE) {
            entry = CAST_QUEUE.peekFirst();
        }
        if (entry == null) return;
        long age = System.nanoTime() - entry.atNanos();
        if (age > MAX_QUEUE_AGE_NANOS) {
            synchronized (CAST_QUEUE) { CAST_QUEUE.pollFirst(); }
            return;
        }
        if (isClientCasting()) return; // still casting — wait it out
        // Equipped-only check at drain time too — the player may have unequipped the spell
        // while it was sitting in the queue. Only enforced when the last scan was reliable
        // (ownedScanReliable); an unreadable scan fails open here just like the dispatch gate.
        if (VoiceSpellsConfig.cRestrictToOwned && ownedScanReliable) {
            java.util.Set<String> owned = ownedSpellIds;
            if (!owned.contains(entry.id().toString())) {
                synchronized (CAST_QUEUE) { CAST_QUEUE.pollFirst(); }
                logRecog("Queued {} dropped — no longer equipped", entry.id());
                return;
            }
        }
        synchronized (CAST_QUEUE) {
            CAST_QUEUE.pollFirst();
        }
        ResourceLocation queued = entry.id();
        long now = System.nanoTime();
        // Refresh the dedup anchors to the drain time so a delayed Vosk re-emission of the
        // same spell doesn't slip past the echo lockout and double-cast.
        lastDispatchedSpellId    = queued.toString();
        lastDispatchedNanos      = now;
        lastDispatchedFirstNanos = now;
        lastDispatchedUtterance  = utteranceId;
        // The drained cast is just as much "the cast that just happened" as a direct dispatch,
        // so it should drive the cast toast and the HUD history strip too.
        lastCastDisplay = displayNameFor(queued);
        lastCastNanos   = now;
        lastCastSchool  = SpellInfo.of(queued.toString()).school;
        bumpStreak(now);
        VoiceStats.recordCast(queued.toString(), currentStreak());
        synchronized (HISTORY) {
            HISTORY.addFirst(new HistoryEntry(lastCastDisplay, now));
            while (HISTORY.size() > HISTORY_MAX) HISTORY.removeLast();
        }
        float vol = Math.max(0f, Math.min(1f, audioLevel));
        int totalForTrigger = VoiceStats.totalCasts();
        int streakForTrigger = currentStreak();
        Minecraft.getInstance().execute(() -> {
            SpellSelector.select(queued);
            dispatchCast(queued, vol, totalForTrigger, streakForTrigger);
            if (VoiceSpellsConfig.cEchoSfx) playEchoChime(queued);
        });
    }

    /** Whole-word, case-insensitive containment ("cast" in "cast fireball" but not in
     *  "castle"). The phrase is already lowercased upstream; word is lowercased in config. */
    private static boolean containsWord(String phrase, String word) {
        for (String tok : phrase.split("\\s+")) {
            if (tok.equals(word)) return true;
        }
        return false;
    }

    /** Recognition chatter is useful while tuning but noisy for a normal session. Log at INFO
     *  only when the debug monitor is enabled; DEBUG otherwise. */
    private static void logRecog(String fmt, Object... args) {
        if (VoiceSpellsConfig.cDebugMonitor) VoiceSpells.LOGGER.info(fmt, args);
        else                                 VoiceSpells.LOGGER.debug(fmt, args);
    }

    /** Closes the underlying session — call on client shutdown. */
    public static void shutdown() {
        lastFrameNanos = 0L;
        stopCapture();
        VoskSession s = session;
        session = null;
        if (s != null) s.close();
    }

    // ----- OpenAL capture lifecycle -------------------------------------------------------

    /** Live capture engine; null before client setup or after shutdown. */
    private static volatile com.niko.voicespells.client.MicCapture capture;

    /**
     * Bring capture in line with the current config. Safe to call repeatedly — on config reload,
     * on world join, and after a device change — and cheap when nothing has changed.
     */
    public static synchronized void syncCapture() {
        // Refuse to open the device when listening makes no sense. Every caller used to be
        // trusted to check this, and one of them didn't: a freshly-written config file fires a
        // reload event, whose callback calls straight through to here, so the microphone opened
        // during startup while the player was still on the title screen. Guarding the open itself
        // means no future caller can reintroduce that.
        if (!captureAllowedNow()) {
            stopCapture();
            return;
        }
        com.niko.voicespells.client.MicCapture c = capture;
        // Restart when the selected device changed; the device name is baked in at construction.
        if (c != null) {
            if (java.util.Objects.equals(activeDevice, VoiceSpellsConfig.cCaptureDevice)) return;
            stopCapture();
        }
        activeDevice = VoiceSpellsConfig.cCaptureDevice;
        com.niko.voicespells.client.MicCapture fresh =
            new com.niko.voicespells.client.MicCapture(activeDevice, VoiceController::onMicFrame16k);
        capture = fresh;
        fresh.start();
        // The recognizer is loaded lazily elsewhere off the first frame, but starting it here
        // means the model is usually ready before the player finishes their first sentence.
        if (session == null && SpellIndex.isReady()) preloadAsync();
    }

    private static volatile String activeDevice = null;

    /**
     * The long-lived conditions under which the microphone may be open at all: in a world, and
     * (unless configured otherwise) with the window focused and the game unpaused.
     *
     * <p>Shared by {@link #syncCapture()} and {@link #tickCaptureSuspension()} so that opening and
     * releasing the device are decided by exactly one predicate. Short-lived gating — push-to-talk,
     * holding a spell focus — is deliberately not here; that is {@code captureArmed()}, checked per
     * frame, because opening a capture device is slow enough that thrashing it on a keypress would
     * clip the start of every utterance.
     */
    /** Set while a screen is actively asking the player to talk into the mic (the first-run
     *  wizard's mic check). Those screens live at the title screen, where there is no level and
     *  no player, so the in-world guard below would otherwise keep the device shut and the meter
     *  dead while the UI says "talk into your mic now" — which is exactly what it did after the
     *  title-screen fix landed. Not a config: it follows the screen's lifetime, nothing else. */
    private static volatile boolean diagnosticCaptureOverride = false;

    /** Open the microphone regardless of gating, for as long as a mic-test screen is showing.
     *  Always pair the {@code true} call with a {@code false} in the screen's close path. */
    public static void setDiagnosticCapture(boolean on) {
        if (diagnosticCaptureOverride == on) return;
        diagnosticCaptureOverride = on;
        syncCapture();
    }

    private static boolean captureAllowedNow() {
        Minecraft mc = Minecraft.getInstance();
        // The override deliberately precedes the in-world check: a mic test is the one case where
        // capturing outside a world is the whole point.
        if (diagnosticCaptureOverride) return true;
        if (mc.level == null || mc.player == null) return false;
        return !(VoiceSpellsConfig.cSuspendUnfocused && (!mc.isWindowActive() || mc.isPaused()));
    }

    /**
     * Open or release the capture device according to whether the game is in a state where
     * listening makes sense at all. Called once per client tick; idempotent and cheap.
     *
     * <p>Deliberately coarse. Only long-lived conditions release the device — no world, window
     * unfocused, game paused — because opening a capture device is not instant, and thrashing it
     * on something as fast as a push-to-talk keypress would clip the start of every utterance.
     * Short-lived gating is handled per frame in {@code captureArmed()} instead.
     */
    public static void tickCaptureSuspension() {
        if (!captureAllowedNow()) {
            if (capture != null) {
                stopCapture();
                // Drop any half-heard utterance so it cannot resurface after resuming.
                VoskSession s = session;
                if (s != null) s.reset();
                gateWasOpen = false;
                audioLevel = 0f;
            }
            return;
        }
        if (capture == null) syncCapture();
    }

    public static synchronized void stopCapture() {
        com.niko.voicespells.client.MicCapture c = capture;
        capture = null;
        activeDevice = null;
        if (c != null) c.close();
    }

    /** Capture status for the HUD / diagnostics, or null when OpenAL capture is not in use. */
    public static com.niko.voicespells.client.MicCapture captureEngine() { return capture; }

    // ----- internal -----

    private static void loadModel() {
        try {
            // SpellIndex may not have run yet on very early calls. Wait briefly if so — the COMMON
            // setup that populates it has already been enqueued by the time this thread starts.
            for (int i = 0; i < 50 && !SpellIndex.isReady(); i++) {
                try { Thread.sleep(50); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
            }
            if (!SpellIndex.isReady()) {
                statusLine = "ERROR no spells indexed";
                return;
            }
            loadVosk();
        } finally {
            loading.set(false);
        }
    }

    private static void loadVosk() {
        // Explicit modelPath wins; otherwise the legacy config/voicespells/model directory if it
        // still holds a model (so an existing install is never re-downloaded), else the
        // per-id models/<modelId> directory that downloads land in.
        Path modelPath = com.niko.voicespells.speech.ModelCatalog.resolveModelDir(
            VoiceSpellsConfig.CLIENT.modelPath.get(), VoiceSpellsConfig.cModelId);
        try {
            if (!com.niko.voicespells.client.ModelDownloader.looksLikeModel(modelPath)) {
                statusLine = "DOWNLOADING model…";
                boolean ok = com.niko.voicespells.client.ModelDownloader.ensureModel(
                    modelPath, pct -> statusLine = "DOWNLOADING model " + pct + "%");
                if (!ok) {
                    statusLine = "ERROR no Vosk model — see chat";
                    notifyModelMissing(modelPath);
                    return;
                }
            }
            // Before the first grammar is built, teach the index which words this particular
            // model can actually say. Iron's Spells names are largely compounds the lexicon does
            // not carry (firebolt, counterspell, heartstop) and Vosk drops them with only a native
            // stderr warning, leaving those spells quietly uncastable. This adds a spaced spelling
            // alongside each one. Must happen before currentGrammar() or the additions miss this
            // session's grammar.
            com.niko.voicespells.speech.Lexicon.load(modelPath);
            SpellIndex.registerRespellings();

            VoskSession s = VoskSession.open(
                modelPath,
                currentGrammar(),
                VoiceController::onPhraseRecognized
            );
            session = s;
            VoiceSpells.LOGGER.info("Vosk model loaded from {}", modelPath);
            statusLine = "READY";
        } catch (Throwable t) {
            VoiceSpells.LOGGER.error("Vosk load failed: {}", t.toString());
            statusLine = "ERROR no Vosk model — see chat";
            notifyModelMissing(modelPath);
        }
    }

    private static void notifyModelMissing(Path modelPath) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.player != null) {
                mc.player.displayClientMessage(
                    Component.translatable("text.voicespells.model_missing",
                        modelPath.toAbsolutePath().toString()),
                    false);
            }
        });
    }

    /**
     * Vosk callback — fires on the capture thread.
     *
     * Partial (mid-utterance) results are matched <b>strictly</b> (exact phrase only): they're
     * the fast path and too noisy to run the lenient fallbacks against. Final results may use
     * the full fuzzy/substring chain, but only if their average word confidence clears the
     * configured floor — that's what stops garbled audio like "beam black hole missile" from
     * casting via a greedy substring grab.
     */
    private static void onPhraseRecognized(String phrase, boolean isFinal, double confidence) {
        // Calibration mode short-circuits everything: report what the model heard and cast nothing.
        if (transcription) {
            reportTranscription(phrase, isFinal, confidence);
            return;
        }
        // Utterance-boundary detection: a partial that follows a final is the start of a new
        // utterance. Bump the counter so the dedup check below can tell "still the same thing
        // I was just hearing" from "the player said it again".
        boolean newUtterance = lastEventWasFinal && !isFinal;
        if (newUtterance) {
            utteranceId++;
            utteranceFirstHeardNanos = 0L;
        }
        if (utteranceFirstHeardNanos == 0L) utteranceFirstHeardNanos = System.nanoTime();
        lastEventWasFinal = isFinal;

        // Voice hotbar select: "spell one" through "spell nine" switch the active spellbook
        // slot without casting. Only acts on finals so partials don't trigger constantly.
        if (VoiceSpellsConfig.cVoiceHotbarSelect && isFinal) {
            int slot = SpellIndex.matchHotbarSlot(phrase);
            if (slot > 0) {
                final int s = slot;
                Minecraft.getInstance().execute(() -> {
                    boolean ok = SpellSelector.selectByIndex(s);
                    recordEvent(phrase, ok ? "selected slot " + s : "slot " + s + " not in book",
                        confidence, ' ');
                });
                return;
            }
        }

        // Hands-free queue control: "no" clears the queue, "yes" is a no-op acknowledgement
        // (the queue auto-drains anyway). Only finals — partials would fire instantly on the
        // first phoneme. Doesn't go through the rest of the cast pipeline.
        if (VoiceSpellsConfig.cHandsFreeConfirm && isFinal) {
            String trimmed = phrase == null ? "" : phrase.trim().toLowerCase(java.util.Locale.ROOT);
            if ("no".equals(trimmed)) {
                int cleared;
                synchronized (CAST_QUEUE) {
                    cleared = CAST_QUEUE.size();
                    CAST_QUEUE.clear();
                }
                recordEvent(phrase, "queue cleared (" + cleared + ")", confidence, ' ');
                return;
            }
            if ("yes".equals(trimmed)) {
                int n;
                synchronized (CAST_QUEUE) { n = CAST_QUEUE.size(); }
                recordEvent(phrase, "queue ack (" + n + ")", confidence, ' ');
                return;
            }
        }

        java.util.Set<String> triggers = VoiceSpellsConfig.cTriggerWords;
        boolean usingTrigger = !triggers.isEmpty();

        // Trigger word: any of the configured words counts. Only finals carry the prefix
        // (partials are matched exact and won't include it), so partials are skipped when set.
        if (usingTrigger) {
            if (!isFinal) return;
            boolean any = false;
            for (String tw : triggers) {
                if (containsWord(phrase, tw)) { any = true; break; }
            }
            if (!any) return; // normal chat — ignore silently
        }

        // Initial coarse confidence gate: drop anything well below the global floor early so we
        // don't waste lookup work. Per-spell overrides are applied later, after we know which
        // spell the phrase resolved to — they can only RELAX the gate, never tighten it below
        // the global setting.
        if (isFinal && confidence < VoiceSpellsConfig.cMinConfidence
                && VoiceSpellsConfig.cPerSpellConfidence.isEmpty()) {
            recordEvent(phrase, String.format(java.util.Locale.ROOT,
                "low conf %.2f", confidence), confidence);
            logRecog("Heard '{}' rejected (conf {} < {})",
                phrase, String.format(java.util.Locale.ROOT, "%.2f", confidence),
                VoiceSpellsConfig.cMinConfidence);
            return;
        }

        Optional<ResourceLocation> id;
        char matchTier = ' ';
        // Loadout lookup runs first: if the phrase matches a configured loadout name we pick
        // the first castable spell from the list (cooldown + mana aware via ClientMagicData),
        // rather than falling through to the generic phrase → single-spell lookup.
        List<ResourceLocation> loadoutSpells = SpellIndex.lookupLoadout(phrase);
        if (loadoutSpells != null && !loadoutSpells.isEmpty()) {
            ResourceLocation chosen = pickCastableFromLoadout(loadoutSpells);
            if (chosen == null) {
                recordEvent(phrase, "loadout: none castable", confidence, 'L');
                logRecog("Loadout '{}' matched but no spell is castable", phrase);
                return;
            }
            id = Optional.of(chosen);
            matchTier = 'L';
        } else {
            // Resolution strategy. Finals run the full lookup chain (exact → fuzzy →
            // substring → phonetic). Partials are restricted to EXACT + TRAILING-SUFFIX:
            //
            //   - EXACT catches the common case where the partial is just the spell name.
            //   - TRAILING matches when Vosk's grammar mode emits "[unk] fireball" because
            //     the user prefixed with a non-grammar word; we only accept the spell as
            //     the LAST word(s) of the partial, never mid-string, to avoid stale
            //     earlier-utterance fragments triggering casts after the user moved on.
            //
            // Fuzzy and phonetic stay off for partials — they're too lenient when audio is
            // still arriving, and would misfire a half-spoken word as a similar-sounding spell.
            Optional<SpellIndex.LookupResult> result;
            if (isFinal) {
                result = SpellIndex.lookupWithTier(phrase);
            } else {
                result = SpellIndex.lookupExactWithTier(phrase);
                if (result.isEmpty() && VoiceSpellsConfig.cSubstringMatch) {
                    result = SpellIndex.lookupTrailingWithTier(phrase);
                }
            }
            id = result.map(SpellIndex.LookupResult::id);
            if (result.isPresent()) matchTier = result.get().tier();
        }

        if (id.isEmpty()) {
            // Don't spam the log/monitor with every evolving partial miss; only finals.
            if (isFinal) {
                recordEvent(phrase, null, confidence);
                logRecog("Heard '{}' but no matching spell", phrase);
                if (VoiceSpellsConfig.cShowMisses) {
                    lastMissText  = VoiceSpellsConfig.cSassMode && SASS_RNG.nextInt(3) == 0
                        ? SASS_LINES[SASS_RNG.nextInt(SASS_LINES.length)]
                        : phrase;
                    lastMissNanos = System.nanoTime();
                }
                // Near-miss detection — run an aggressive lookup ignoring the user's matching
                // gates, and if it lands on a real spell, surface a "Did you mean X?" prompt.
                // Floor on confidence so the suggestion doesn't fire on noise / accidental
                // throat-clears.
                if (confidence >= 0.30) {
                    Optional<ResourceLocation> guess = SpellIndex.aggressiveLookup(phrase);
                    if (guess.isPresent()) {
                        ResourceLocation gid = guess.get();
                        lastSuggestion = new AliasSuggestion(
                            phrase, gid, displayNameFor(gid), System.nanoTime());
                        logRecog("Near-miss: heard '{}', suggesting alias for {}", phrase, gid);
                    }
                }
            }
            return;
        }
        ResourceLocation spellId = id.get();
        String spellKey = spellId.toString();
        long now = System.nanoTime();

        // Equipped-only hard gate. When cRestrictToOwned is on AND the last scan produced
        // trustworthy data (ownedScanReliable), the spell MUST be in the actively-equipped set
        // (main hand / off hand / curios) or we reject it — a reliable-but-empty set means the
        // player is holding nothing castable, so every dispatch is rejected, keeping unowned
        // phrases off the HUD streak/history. When the scan can't run reliably (Iron's
        // reflection unavailable, or before the first scan completes) we fail OPEN instead: a
        // permanent reflection break must not silently block every cast, and the server-side
        // cast check is the real authority anyway.
        if (VoiceSpellsConfig.cRestrictToOwned && ownedScanReliable) {
            java.util.Set<String> owned = ownedSpellIds;
            if (!owned.contains(spellKey)) {
                recordEvent(phrase, spellKey + " (not equipped)", confidence, matchTier);
                logRecog("Heard '{}' but {} is not equipped", phrase, spellId);
                return;
            }
        }

        // Per-spell confidence override — relaxes (or tightens) the global gate for individual
        // spells. Only relevant on finals (partials already passed lookupExact, no fuzzy noise).
        if (isFinal && !VoiceSpellsConfig.cPerSpellConfidence.isEmpty()) {
            double threshold = VoiceSpellsConfig.cPerSpellConfidence.getOrDefault(
                spellKey, (double) VoiceSpellsConfig.cMinConfidence);
            if (confidence < threshold) {
                recordEvent(phrase, String.format(java.util.Locale.ROOT,
                    "low conf %.2f / need %.2f", confidence, threshold), confidence, matchTier);
                logRecog("Heard '{}' rejected for {} (conf {} < {})",
                    phrase, spellId,
                    String.format(java.util.Locale.ROOT, "%.2f", confidence), threshold);
                return;
            }
        }

        // Don't cast while a screen is open (config/any menu). Still record it so the debug
        // monitor stays useful while you're tuning in the config screen.
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.screen != null) {
            recordEvent(phrase, spellKey + " (menu)", confidence);
            return;
        }
        // Optional sneak gate so casual talking doesn't fire spells.
        if (VoiceSpellsConfig.cRequireSneak
                && (mc.player == null || !mc.player.isShiftKeyDown())) {
            recordEvent(phrase, spellKey + " (no sneak)", confidence);
            return;
        }
        // Combat-only gate — only cast when recently hurt or recently dealt damage.
        if (VoiceSpellsConfig.cCombatOnly && !isInCombat()) {
            recordEvent(phrase, spellKey + " (out of combat)", confidence);
            return;
        }
        // AFK gate — pause recognition for stationary players (also saves a tick of CPU
        // every time we'd otherwise traverse the cast pipeline).
        if (VoiceSpellsConfig.cPauseWhenAfk && isAfk()) {
            recordEvent(phrase, spellKey + " (afk)", confidence);
            return;
        }
        // Sliding-window dedup. Vosk emits one utterance as partial(s) → getResult → the
        // flush getFinalResult; with the accurate model and a sticky noise gate the
        // gap between the first partial cast and the flush can exceed a fixed window, which is
        // exactly the "casts twice" bug. So on every *repeat* of the same spell we push the
        // timestamp forward — the window only lapses after the spell genuinely stops being
        // heard for cDedupNanos (real silence / new utterance), at which point a deliberate
        // re-cast is allowed again. Different spells are unaffected (chaining still works).
        if (spellKey.equals(lastDispatchedSpellId)) {
            boolean sameUtterance   = (utteranceId == lastDispatchedUtterance);
            boolean inSlidingWindow = (now - lastDispatchedNanos) < VoiceSpellsConfig.cDedupNanos;
            boolean inEchoLockout   = VoiceSpellsConfig.cEchoLockoutNanos > 0
                && (now - lastDispatchedFirstNanos) < VoiceSpellsConfig.cEchoLockoutNanos;
            // Same utterance = always dedup (catches slow-Vosk partial→final spans that
            // exceed the configured echo lockout). Time-based gates still cover the case
            // where Vosk re-emits a "tail" event right after a final.
            if (sameUtterance || inSlidingWindow || inEchoLockout) {
                // Deliberately do NOT push lastDispatchedNanos forward here. It used to slide on
                // every suppressed repeat, which starved the window: grammar-mode Vosk force-fits
                // ambient noise onto the nearest in-grammar phrase, so with an open or
                // voice-activated mic the same spell kept re-arming the window and could never be
                // cast a second time. The only escape was to say a *different* spell, which
                // rewrites lastDispatchedSpellId — exactly the "I have to cast something else in
                // between" bug players reported. The window is now measured from the real
                // dispatch, so it always lapses cDedupNanos after the cast actually happened.
                // Nothing is lost: sameUtterance already catches a slow partial→final span
                // exactly (by identity, not by timing), and inEchoLockout still bounds repeats
                // absolutely from lastDispatchedFirstNanos.
                return;
            }
        }
        // Cast queue: if a long-cast is on the bar, park this for the tick drainer instead of
        // dispatching now (which would either be canceled server-side by the isCasting check or
        // — worse — interrupt the current cast). Most recent wins on overflow.
        if (isClientCasting()) {
            // Multi-cast / long-channel spells take a few hundred ms to complete; during that
            // time trailing audio + the user's own continued vocalisation keep getting grammar-
            // forced into the same spell shape, and without this guard each repeat would stack
            // in the queue and fire again the moment the cast finishes. Block:
            //   - same spell as the one we just dispatched (lastDispatchedSpellId), and
            //   - same spell that's already sitting in the queue.
            // Different spells chain freely, which is the actual "queue the next intent" feature.
            boolean sameAsLastDispatch = spellKey.equals(lastDispatchedSpellId);
            boolean alreadyQueued;
            synchronized (CAST_QUEUE) {
                alreadyQueued = false;
                for (QueueEntry e : CAST_QUEUE) {
                    if (e.id().equals(spellId)) { alreadyQueued = true; break; }
                }
            }
            if (sameAsLastDispatch || alreadyQueued) {
                // No window slide here either — isClientCasting() plus these two checks already
                // block the tail for as long as the cast is actually in flight. Sliding on top of
                // that leaked the block past the end of the cast: every suppressed tail event
                // re-armed the dedup window, so a spell with a long channel time could stay
                // un-recastable well after it finished.
                recordEvent(phrase, spellKey + " (already in flight)", confidence, matchTier);
                logRecog("Heard '{}' but {} is already casting/queued — dropped",
                    phrase, spellId);
                return;
            }
            synchronized (CAST_QUEUE) {
                int max = Math.max(1, VoiceSpellsConfig.cCastQueueSize);
                while (CAST_QUEUE.size() >= max) {
                    // Overflow policy: drop the OLDEST queued spell so the most recently
                    // requested ones still fire. Keeps semantics close to "what I said last
                    // matters most" while still preserving FIFO ordering of the survivors.
                    CAST_QUEUE.pollFirst();
                }
                CAST_QUEUE.offerLast(new QueueEntry(spellId, now));
            }
            lastDispatchedSpellId    = spellKey;
            lastDispatchedNanos      = now;
            lastDispatchedFirstNanos = now;   // anchor echo lockout to this queue insert
            lastDispatchedUtterance  = utteranceId;
            lastHeard       = clampHeard(phrase);
            recordEvent(phrase, spellKey + " (queued)", confidence, matchTier);
            logRecog("Heard '{}' while casting -> queued {}", phrase, spellId);
            return;
        }
        // Client-side preflight: skip impossible casts (no mana / on cooldown) so we don't
        // round-trip the server. Server-side preflight is still the authoritative gate.
        if (!clientCanCast(spellId)) {
            recordEvent(phrase, spellKey + " (cant cast)", confidence, matchTier);
            logRecog("Heard '{}' but client preflight failed for {}", phrase, spellId);
            return;
        }
        lastDispatchedSpellId    = spellKey;
        lastDispatchedNanos      = now;
        lastDispatchedFirstNanos = now;       // anchor echo lockout to this dispatch
        lastDispatchedUtterance  = utteranceId;
        lastHeard       = phrase;
        lastCastDisplay = displayNameFor(spellId);
        lastCastNanos   = now;
        lastCastSchool  = SpellInfo.of(spellKey).school;
        bumpStreak(now);
        VoiceStats.recordCast(spellKey, currentStreak());
        // Record mic-heard → dispatched latency for the codex metric.
        if (utteranceFirstHeardNanos > 0L) {
            recordLatencyNanos(now - utteranceFirstHeardNanos);
        }
        synchronized (HISTORY) {
            HISTORY.addFirst(new HistoryEntry(lastCastDisplay, now));
            while (HISTORY.size() > HISTORY_MAX) HISTORY.removeLast();
        }
        recordEvent(phrase, spellKey, confidence, matchTier);
        logRecog("Heard '{}' ({}, conf {}) -> dispatching {}",
            phrase, isFinal ? "final" : "partial",
            String.format(java.util.Locale.ROOT, "%.2f", confidence), spellId);
        ResourceLocation dispatched = spellId;
        float vol = Math.max(0f, Math.min(1f, audioLevel));
        int totalForTrigger = VoiceStats.totalCasts(); // includes the cast we just recorded
        int streakForTrigger = currentStreak();
        Minecraft.getInstance().execute(() -> {
            // Move the spellbook's selected spell to the one we're casting so the HUD bar
            // reflects it and a follow-up manual cast uses the same spell.
            SpellSelector.select(dispatched);
            dispatchCast(dispatched, vol, totalForTrigger, streakForTrigger);
            if (VoiceSpellsConfig.cEchoSfx) playEchoChime(dispatched);
        });
    }

    /** Title-cased display string for a spell: prefer the localised {@code getDisplayName()}
     *  via {@link SpellInfo} (preserves mod authors' intended casing like "Magic Missile"),
     *  fall back to a snake_case-to-Title-Case derivation if reflection comes up empty. */
    private static String displayNameFor(ResourceLocation spellId) {
        try {
            SpellInfo info = SpellInfo.of(spellId.toString());
            if (info != null && info.name != null && !info.name.isBlank()) return info.name;
        } catch (Throwable ignored) { /* fall through */ }
        return SpellCaster.prettyName(spellId);
    }

    /** Brief mystical chime played on the client when a phrase is recognised. The instrument
     *  + pitch reflect the spell's school (fire → bell hi, ice → bell low, lightning →
     *  xylophone, nature → harp, etc.) so the audio cue carries information beyond
     *  "something matched". {@link net.minecraft.client.resources.sounds.SimpleSoundInstance#forUI}
     *  so the volume is independent of master-music and not positional. */
    private static void playEchoChime(ResourceLocation spellId) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.getSoundManager() == null) return;
            String school = SpellInfo.of(spellId.toString()).school;
            com.niko.voicespells.spells.SpellSchools.Cue cue =
                com.niko.voicespells.spells.SpellSchools.cueFor(school);
            float pitch = cue.pitch() + (float) (Math.random() * 0.1 - 0.05); // tiny jitter
            mc.getSoundManager().play(
                net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                    cue.sound().value(), pitch, 0.45f)
            );
        } catch (Throwable ignored) {
            // Sound failures shouldn't break a cast — swallow.
        }
    }
}
