package com.niko.voicespells.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.niko.voicespells.VoiceSpells;
import org.vosk.LibVosk;
import org.vosk.LogLevel;
import org.vosk.Model;
import org.vosk.Recognizer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Wraps a Vosk {@link Recognizer} restricted to a fixed phrase grammar — the
 * spell names. The grammar limits what the recognizer can return, which makes
 * matching fast and accurate even on the small English model.
 *
 * Two emission paths:
 *   - <b>partial</b> (mid-utterance, every frame): no confidence available from
 *     Vosk, so the consumer treats these strictly (exact phrase only).
 *   - <b>final</b> (utterance boundary / flush): word confidences are available
 *     because we enable {@code setWords(true)}; the consumer can run the lenient
 *     fuzzy/substring fallbacks but gate them behind an average-confidence floor.
 *
 * Audio arrives from MicCapture already at 16 kHz mono 16-bit — the rate Vosk wants — so there
 * is no resampling anywhere in the path.
 *
 * Recognizer is not thread-safe; all entry points synchronize on this instance.
 */
public final class VoskSession implements AutoCloseable {
    private static final float SAMPLE_RATE = 16_000f;

    /**
     * True when the Vosk native boundary is carrying UTF-8, so non-ASCII phrases survive it.
     *
     * <p>Read by {@link Diagnostics} rather than used to filter phrases. A phrase that cannot be
     * encoded is REPORTED, never silently dropped - a grammar entry that vanishes with no message
     * is this mod's worst failure mode, because it presents as a dead microphone.
     */
    public static volatile boolean utf8Grammar = false;

    static {
        // ORDER IS THE WHOLE FIX, and it is the opposite of what looks right.
        //
        // org.vosk.LibVosk is JNA DIRECT-MAPPED: its own <clinit> calls
        // Native.register(LibVosk.class, "libvosk") - the plain two-arg form, four times, verified
        // with javap - which bakes the JVM's default native encoding into every method handle.
        // On a default Windows JVM that is windows-1252, so every accented character in a grammar
        // phrase reaches the recogniser mangled and Vosk answers
        //     WARNING (VoskAPI:UpdateGrammarFst()) Ignoring word missing in vocabulary: 'bart?k'
        // and drops the phrase.
        //
        // Registering UTF-8 BEFORE touching LibVosk does nothing: the clinit has not run yet, and
        // when it does it overwrites the registration. The trap is that
        // Native.getStringEncoding(LibVosk.class) still reads "UTF-8" afterwards, so the broken
        // order self-verifies as working. setLogLevel first forces the clinit; then we re-register.
        LibVosk.setLogLevel(LogLevel.WARNINGS);
        try {
            String lib = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
                ? "libvosk" : "vosk";
            // Scoped to this library, not global. Native.getDefaultStringEncoding() is left alone,
            // so Minecraft's own oshi - the reason both buildscripts pin JNA 5.12.1 - is untouched.
            com.sun.jna.Native.register(LibVosk.class,
                com.sun.jna.NativeLibrary.getInstance(lib,
                    java.util.Map.of(com.sun.jna.Library.OPTION_STRING_ENCODING, "UTF-8")));
            utf8Grammar = "UTF-8".equalsIgnoreCase(com.sun.jna.Native.getStringEncoding(LibVosk.class));
        } catch (Throwable t) {
            utf8Grammar = false;
            VoiceSpells.LOGGER.warn("Could not force UTF-8 on the Vosk native boundary ({}); "
                + "phrases containing non-ASCII characters will not reach the recogniser intact",
                t.toString());
        }
    }

    /** Consumer callback. {@code isFinal} distinguishes a settled utterance result (with a
     *  meaningful {@code confidence} 0..1) from a mid-utterance partial (confidence is 1.0 and
     *  should be ignored — partials are handled strictly instead). */
    @FunctionalInterface
    public interface PhraseSink {
        void accept(String text, boolean isFinal, double confidence);
    }

    private final Model model;
    // Not final: rebuildGrammar() swaps in a fresh Recognizer (keeping the loaded Model) so
    // grammar/customPhrase edits apply live without reloading the model from disk.
    private Recognizer recognizer;
    private final PhraseSink onPhrase;
    private boolean closed = false;

    private String lastTriedPartial = "";

    private VoskSession(Model model, Recognizer recognizer, PhraseSink onPhrase) {
        this.model = model;
        this.recognizer = recognizer;
        this.onPhrase = onPhrase;
    }

    public synchronized void reset() {
        if (!closed) recognizer.reset();
        lastTriedPartial = "";
    }

    /**
     * Swap in a recognizer built against {@code phrases} while keeping the (slow-to-load) Model
     * resident. Lets the mod apply customPhrase / spell-index changes live. On failure the old
     * recognizer is kept so recognition never goes dead.
     */
    public synchronized void rebuildGrammar(List<String> phrases) {
        if (closed) return;
        Recognizer fresh;
        try {
            fresh = new Recognizer(model, SAMPLE_RATE, buildGrammarJson(phrases));
            fresh.setWords(true);
        } catch (Throwable t) {
            VoiceSpells.LOGGER.error("Grammar rebuild failed, keeping current grammar: {}",
                t.toString());
            return;
        }
        Recognizer old = recognizer;
        recognizer = fresh;
        lastTriedPartial = "";
        try { old.close(); } catch (Throwable ignored) {}
        VoiceSpells.LOGGER.info("Recognition grammar rebuilt ({} phrases)", phrases.size());
    }

    /**
     * Swap in a recognizer with <b>no grammar at all</b> — free dictation over the model's whole
     * lexicon.
     *
     * <p>This is the opposite of how the mod normally runs, and that is the point. With a grammar,
     * the recognizer can only ever report phrases the grammar contains, so it cannot tell you what
     * it actually heard — it tells you which of your spells your speech was closest to. For
     * choosing aliases you need the unfiltered transcription: say the spell name, see the words the
     * model genuinely produced, and bind those.
     *
     * <p>Far less accurate than grammar mode and not meant for casting. The caller is expected to
     * suppress casting while this is active.
     */
    public synchronized void rebuildUnconstrained() {
        if (closed) return;
        Recognizer fresh;
        try {
            fresh = new Recognizer(model, SAMPLE_RATE);
            fresh.setWords(true);
        } catch (Throwable t) {
            VoiceSpells.LOGGER.error("Could not open a free-dictation recognizer: {}", t.toString());
            return;
        }
        Recognizer old = recognizer;
        recognizer = fresh;
        lastTriedPartial = "";
        try { old.close(); } catch (Throwable ignored) {}
        VoiceSpells.LOGGER.info("Recognition switched to free dictation (no grammar)");
    }

    /**
     * Feed a frame that is already at the recognizer's rate.
     *
     * <p>The capture device is opened at 16 kHz, so frames need no conversion. The name keeps the
     * rate explicit at every call site: feeding audio at any other rate here would stretch or
     * compress every utterance and destroy recognition, and that failure is silent.
     */
    public synchronized void feed16k(short[] frame) {
        if (closed || frame == null || frame.length == 0) return;
        ByteBuffer bb = ByteBuffer.allocate(frame.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (short s : frame) bb.putShort(s);
        accept(bb.array());
    }

    private void accept(byte[] bytes) {
        try {
            if (recognizer.acceptWaveForm(bytes, bytes.length)) {
                emitFinal(recognizer.getResult());
                lastTriedPartial = "";
            } else {
                emitPartial(recognizer.getPartialResult());
            }
        } catch (Throwable t) {
            VoiceSpells.LOGGER.warn("Vosk feed failed: {}", t.toString());
        }
    }

    public synchronized void flush() {
        if (closed) return;
        try {
            emitFinal(recognizer.getFinalResult());
        } catch (Throwable t) {
            VoiceSpells.LOGGER.warn("Vosk flush failed: {}", t.toString());
        }
        lastTriedPartial = "";
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        try { recognizer.close(); } catch (Throwable ignored) {}
        try { model.close(); }      catch (Throwable ignored) {}
    }

    /** Mid-utterance partial: {@code {"partial":"fire ball"}}. No confidence; flagged !final. */
    private void emitPartial(String json) {
        if (json == null || json.isEmpty()) return;
        try {
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            if (!obj.has("partial")) return;
            String text = obj.get("partial").getAsString().trim().toLowerCase(Locale.ROOT);
            if (text.isEmpty() || text.equals("[unk]")) return;
            if (text.equals(lastTriedPartial)) return; // unchanged since last frame
            lastTriedPartial = text;
            onPhrase.accept(text, false, 1.0);
        } catch (Throwable t) {
            VoiceSpells.LOGGER.debug("Vosk partial parse: {}", t.toString());
        }
    }

    /** Settled result: {@code {"text":"fire ball","result":[{"conf":0.97,"word":"fire"},…]}}.
     *  We average the per-word confidences and hand that to the consumer's gate. */
    private void emitFinal(String json) {
        if (json == null || json.isEmpty()) return;
        try {
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            if (!obj.has("text")) return;
            String text = obj.get("text").getAsString().trim().toLowerCase(Locale.ROOT);
            if (text.isEmpty() || text.equals("[unk]")) return;
            double conf = averageConfidence(obj);
            onPhrase.accept(text, true, conf);
        } catch (Throwable t) {
            VoiceSpells.LOGGER.debug("Vosk final parse: {}", t.toString());
        }
    }

    private static double averageConfidence(JsonObject obj) {
        if (!obj.has("result") || !obj.get("result").isJsonArray()) {
            return 1.0; // no per-word data (shouldn't happen with setWords(true)) — don't gate
        }
        JsonArray arr = obj.getAsJsonArray("result");
        if (arr.isEmpty()) return 1.0;
        double sum = 0;
        int n = 0;
        for (var el : arr) {
            JsonObject w = el.getAsJsonObject();
            if (w.has("conf")) { sum += w.get("conf").getAsDouble(); n++; }
        }
        return n == 0 ? 1.0 : sum / n;
    }

    /**
     * Open a session against an unzipped Vosk model directory. {@code phrases} restricts the
     * recognizer's vocabulary; "[unk]" is appended so it can refuse non-matching audio.
     */
    public static VoskSession open(Path modelPath, List<String> phrases, PhraseSink onPhrase)
            throws IOException {
        if (!Files.isDirectory(modelPath)) {
            throw new IOException("Vosk model directory not found: " + modelPath);
        }
        Model model;
        try {
            model = new Model(modelPath.toString());
        } catch (Throwable t) {
            throw new IOException("Failed to load Vosk model from " + modelPath + ": " + t, t);
        }
        Recognizer rec;
        try {
            String grammar = buildGrammarJson(phrases);
            rec = new Recognizer(model, SAMPLE_RATE, grammar);
            // Per-word confidences in final results — required for the consumer's confidence gate.
            rec.setWords(true);
        } catch (Throwable t) {
            try { model.close(); } catch (Throwable ignored) {}
            throw new IOException("Failed to create Vosk recognizer: " + t, t);
        }
        return new VoskSession(model, rec, onPhrase);
    }

    private static String buildGrammarJson(List<String> phrases) {
        StringBuilder sb = new StringBuilder();
        sb.append('[');
        boolean first = true;
        for (String p : phrases) {
            if (p == null) continue;
            String safe = sanitizeForGrammar(p);
            if (safe.isEmpty()) continue;
            if (!first) sb.append(',');
            sb.append('"').append(safe).append('"');
            first = false;
        }
        if (!first) sb.append(',');
        sb.append("\"[unk]\"]");
        return sb.toString();
    }

    /**
     * Reduce a phrase to something that cannot break the hand-built grammar JSON.
     *
     * <p>Only the double quote was stripped before. A single backslash in a phrase produced an
     * invalid grammar document, Vosk rejected it, and the failure surfaced to the player as "no
     * model" - sending them off to reinstall a model that was never the problem. Phrases come out
     * of a user-edited phrasebook, so a stray backslash is entirely plausible; control characters
     * are stripped for the same reason. Done character by character rather than with a regex,
     * because escaping a regex inside a Java string is exactly where a backslash level goes
     * missing unnoticed.
     */
    private static String sanitizeForGrammar(String phrase) {
        StringBuilder out = new StringBuilder(phrase.length());
        boolean lastWasSpace = false;
        for (int i = 0; i < phrase.length(); i++) {
            char c = phrase.charAt(i);
            boolean drop = c == '"' || c == 0x5C || c < 0x20 || c == 0x7F;
            char ch = drop ? ' ' : c;
            if (ch == ' ') {
                if (!lastWasSpace && out.length() > 0) out.append(' ');
                lastWasSpace = true;
            } else {
                out.append(ch);
                lastWasSpace = false;
            }
        }
        return out.toString().trim();
    }

    public static Path defaultModelPath() {
        // <gamedir>/config/voicespells/model
        return Path.of("config", VoiceSpells.MOD_ID, "model");
    }
}
