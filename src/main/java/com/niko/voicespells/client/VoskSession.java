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
 * Both paths also carry per-word timings ({@code setWords} plus {@code setPartialWords}), which
 * is how the consumer can tell a spell name that was actually spoken from a short noise the
 * grammar force-fitted onto one - confidence alone never could, and on partials there is none.
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
        try {
            // Inside the try, not before it. This call is what forces LibVosk's clinit, which
            // unpacks and loads the native library - so it is also the statement that throws when
            // the library is missing, the wrong architecture, or has been quarantined by
            // antivirus. Outside, that became an ExceptionInInitializerError from this class's
            // own static block, after which every touch of VoskSession throws NoClassDefFoundError
            // and the player gets a bare stack trace instead of the mod saying what is wrong.
            LibVosk.setLogLevel(LogLevel.WARNINGS);
            String lib = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
                ? "libvosk" : "vosk";
            // Scoped to this library, not global. Native.getDefaultStringEncoding() is left alone,
            // so Minecraft's own oshi - the reason both buildscripts pin JNA 5.12.1 - is untouched.
            com.sun.jna.Native.register(LibVosk.class,
                com.sun.jna.NativeLibrary.getInstance(lib,
                    java.util.Map.of(com.sun.jna.Library.OPTION_STRING_ENCODING, "UTF-8")));
            utf8Grammar = "UTF-8".equalsIgnoreCase(com.sun.jna.Native.getStringEncoding(LibVosk.class));
        } catch (UnsatisfiedLinkError | NoClassDefFoundError nativeMissing) {
            utf8Grammar = false;
            VoiceSpells.LOGGER.error("The Vosk speech library could not be loaded ({}). Voice "
                + "casting will not work. This is usually a missing or quarantined native "
                + "library - check that antivirus has not removed libvosk from the mod jar's "
                + "extracted natives.", nativeMissing.toString());
        } catch (Throwable t) {
            utf8Grammar = false;
            VoiceSpells.LOGGER.warn("Could not force UTF-8 on the Vosk native boundary ({}); "
                + "phrases containing non-ASCII characters will not reach the recogniser intact",
                t.toString());
        }
    }

    /**
     * One recognised word with the recogniser's own timing, in seconds counted from the moment
     * the recogniser was created.
     *
     * <p>Only {@code end - start} differences <i>within one event</i> carry meaning. The clock is
     * the recogniser's audio clock: it keeps running across in-stream finals and has no relation
     * to {@link System#nanoTime()}, so comparing a value here against a wall clock produces a
     * number that looks plausible and is nonsense.
     *
     * <p>{@code conf} is {@link Double#NaN} only when the element had no {@code conf} member at
     * all. Partials carry a flat 1.0 that means nothing (the same 1.0-and-ignore rule as the
     * event's own confidence); a grammar-mode final carries a real value on only some of its
     * words and nothing on the rest.
     */
    public record Word(String word, double start, double end, double conf) {}

    /** Consumer callback. {@code isFinal} distinguishes a settled utterance result (with a
     *  meaningful {@code confidence} 0..1) from a mid-utterance partial (confidence is 1.0 and
     *  should be ignored — partials are handled strictly instead).
     *
     *  <p>{@code words} holds the recogniser's per-word timings for this same text, in order,
     *  including any {@code [unk]} the recogniser inserted. It is immutable and never null; an
     *  empty list means no timing was available for this event: a consumer that measures audio
     *  may fall back on its own speech-start stamp for it, and otherwise has to fail open — never
     *  read it as a zero-length utterance.
     *  Partials carry timings too, because the session asks for them with
     *  {@code setPartialWords(true)}. */
    @FunctionalInterface
    public interface PhraseSink {
        void accept(String text, boolean isFinal, double confidence, java.util.List<Word> words);
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
            // Timings on PARTIAL results too. Partials are the path that used to dispatch a cast
            // with nothing checked at all, so the completeness gate downstream needs to know how
            // much audio a partial match actually covered. Every recogniser this class hands out
            // has to ask for them - a site that forgets shows up not as an error but as casts
            // that never get measured.
            fresh.setPartialWords(true);
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
            fresh.setPartialWords(true); // as in rebuildGrammar - every recogniser reports timings
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

    /** Mid-utterance partial: {@code {"partial":"fire ball","partial_result":[{"word":"fire",
     *  "start":0.03,"end":0.42},…]}}. No confidence; flagged !final. The timings ride along
     *  because every recogniser here is created with {@code setPartialWords(true)}. */
    private void emitPartial(String json) {
        if (json == null || json.isEmpty()) return;
        try {
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            if (!obj.has("partial")) return;
            String text = obj.get("partial").getAsString().trim().toLowerCase(Locale.ROOT);
            if (text.isEmpty() || text.equals("[unk]")) return;
            // Deduped on the TEXT alone, and it has to stay that way. A partial trails the audio
            // by the better part of a second and arrives with its words already fully timed - a
            // word's end does not creep forward while the text stands still - so an unchanged
            // partial carries nothing new to judge, and re-delivering it every frame would only
            // buy a monitor row per frame. It also means a partial the consumer finds too short
            // is an early no rather than a wait for more audio: the final answer decides.
            if (text.equals(lastTriedPartial)) return; // unchanged since last frame
            lastTriedPartial = text;
            onPhrase.accept(text, false, 1.0, parseWords(obj, "partial_result"));
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
            onPhrase.accept(text, true, conf, parseWords(obj, "result"));
        } catch (Throwable t) {
            VoiceSpells.LOGGER.debug("Vosk final parse: {}", t.toString());
        }
    }

    /**
     * Per-word timings out of a result object — {@code "result"} on a final,
     * {@code "partial_result"} on a partial. Never null.
     *
     * <p>A word is only usable with {@code word}, {@code start} and {@code end}, and if a single
     * element is missing one of those the WHOLE list is thrown away. Half a timing list is worse
     * than none: the consumer lines these words up against the matched phrase to measure it, so a
     * gap would silently measure the wrong stretch of audio and reject a spell the player really
     * did say. An empty list means "cannot judge": the consumer may fall back on its own
     * speech-start stamp, and otherwise fails open.
     *
     * <p>{@code conf} is the exception and must never cost the list: grammar-mode finals carry it
     * on only some words, and partials carry a flat 1.0 that means nothing, so an absent member
     * is recorded as {@link Double#NaN} rather than thrown away with everything else. Words are lowercased with {@link Locale#ROOT} to match the text the
     * emitters hand over alongside them.
     */
    private static List<Word> parseWords(JsonObject obj, String key) {
        try {
            if (!obj.has(key) || !obj.get(key).isJsonArray()) return List.of();
            JsonArray arr = obj.getAsJsonArray(key);
            if (arr.isEmpty()) return List.of();
            java.util.ArrayList<Word> out = new java.util.ArrayList<>(arr.size());
            for (var el : arr) {
                if (!el.isJsonObject()) return List.of();
                JsonObject w = el.getAsJsonObject();
                if (!w.has("word") || !w.has("start") || !w.has("end")) return List.of();
                double conf = w.has("conf") ? w.get("conf").getAsDouble() : Double.NaN;
                out.add(new Word(w.get("word").getAsString().toLowerCase(Locale.ROOT),
                    w.get("start").getAsDouble(), w.get("end").getAsDouble(), conf));
            }
            return List.copyOf(out);
        } catch (Throwable t) {
            VoiceSpells.LOGGER.debug("Vosk word timing parse: {}", t.toString());
            return List.of();
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
            rec.setPartialWords(true); // and timings on partials - see rebuildGrammar
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
}
