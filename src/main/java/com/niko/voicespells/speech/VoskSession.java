package com.niko.voicespells.speech;

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
 * SVC delivers 48 kHz mono short[] frames; Vosk expects 16 kHz mono 16-bit PCM.
 * We downsample 3:1 with a 3-tap box filter — crude but adequate for speech.
 *
 * Recognizer is not thread-safe; all entry points synchronize on this instance.
 */
public final class VoskSession implements AutoCloseable {
    private static final float SAMPLE_RATE = 16_000f;
    private static final int   DECIMATION  = 3; // 48 kHz → 16 kHz

    static {
        LibVosk.setLogLevel(LogLevel.WARNINGS);
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

    public synchronized void feed(short[] frame48k) {
        if (closed || frame48k == null || frame48k.length == 0) return;
        byte[] bytes = downsampleAndPack(frame48k);
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
            String safe = p.replace("\"", "").trim();
            if (safe.isEmpty()) continue;
            if (!first) sb.append(',');
            sb.append('"').append(safe).append('"');
            first = false;
        }
        if (!first) sb.append(',');
        sb.append("\"[unk]\"]");
        return sb.toString();
    }

    private static byte[] downsampleAndPack(short[] in) {
        int outLen = in.length / DECIMATION;
        ByteBuffer bb = ByteBuffer.allocate(outLen * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0, j = 0; i < outLen; i++, j += DECIMATION) {
            int sum = in[j] + in[j + 1] + in[j + 2];
            bb.putShort((short) (sum / DECIMATION));
        }
        return bb.array();
    }

    public static Path defaultModelPath() {
        // <gamedir>/config/voicespells/model
        return Path.of("config", VoiceSpells.MOD_ID, "model");
    }
}
