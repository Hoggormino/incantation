package com.niko.voicespells.speech;

import com.niko.voicespells.VoiceSpells;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Vosk models this mod knows how to fetch, and where they live on disk.
 *
 * <p>Models are never bundled in the jar — they are tens to hundreds of megabytes and are
 * separately licensed, so they are downloaded on demand into
 * {@code config/voicespells/models/&lt;id&gt;/} instead.
 *
 * <p><b>On checksums.</b> {@link Entry#sha256()} is nullable and every shipped entry currently
 * carries {@code null}, which means downloads are <i>not</i> integrity-checked yet. That is
 * deliberate and it is a gap, not a design choice: Alpha Cephei does not publish checksums
 * alongside these archives, and inventing a hash would be worse than having none — a wrong pin
 * rejects a perfectly good download and looks exactly like a network fault. Instead the SHA-256 of
 * every download is computed and logged, so real values can be pinned here once observed from a
 * trusted fetch. When an entry does have a hash, a mismatch deletes the file and fails the install.
 */
public final class ModelCatalog {
    private ModelCatalog() {}

    /**
     * @param id          the archive's name, which is also its directory name
     * @param language    ISO-ish language tag; pairs a model with an incantation file
     * @param url         download location
     * @param approxBytes rough download size, for the "this will take a while" message
     * @param sha256      expected digest, or {@code null} when unknown — see the class note
     */
    public record Entry(String id, String language, String url, long approxBytes, String sha256) {
        public String prettySize() {
            return approxBytes <= 0 ? "unknown size"
                : String.format(java.util.Locale.ROOT, "~%d MB", approxBytes / (1024 * 1024));
        }
    }

    private static final Map<String, Entry> BY_ID = new LinkedHashMap<>();

    private static void add(Entry e) { BY_ID.put(e.id(), e); }

    static {
        // Small models are the default everywhere: they are a fraction of the size and, because
        // the recognizer runs against a restricted grammar rather than open dictation, the
        // accuracy gap matters far less here than it would for transcription.
        add(new Entry("vosk-model-small-en-us-0.15", "en",
            "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip",
            40L * 1024 * 1024, null));
        add(new Entry("vosk-model-en-us-0.22-lgraph", "en",
            "https://alphacephei.com/vosk/models/vosk-model-en-us-0.22-lgraph.zip",
            128L * 1024 * 1024, null));
        add(new Entry("vosk-model-small-es-0.42", "es",
            "https://alphacephei.com/vosk/models/vosk-model-small-es-0.42.zip",
            39L * 1024 * 1024, null));
        add(new Entry("vosk-model-small-ru-0.22", "ru",
            "https://alphacephei.com/vosk/models/vosk-model-small-ru-0.22.zip",
            45L * 1024 * 1024, null));
        add(new Entry("vosk-model-small-fr-0.22", "fr",
            "https://alphacephei.com/vosk/models/vosk-model-small-fr-0.22.zip",
            41L * 1024 * 1024, null));
        add(new Entry("vosk-model-small-de-0.15", "de",
            "https://alphacephei.com/vosk/models/vosk-model-small-de-0.15.zip",
            45L * 1024 * 1024, null));
    }

    public static final String DEFAULT_ID = "vosk-model-small-en-us-0.15";

    public static Entry byId(String id) {
        return id == null ? null : BY_ID.get(id.trim());
    }

    public static List<Entry> all() { return List.copyOf(BY_ID.values()); }

    /** {@code config/voicespells} */
    public static Path baseDir() {
        return Path.of("config", VoiceSpells.MOD_ID);
    }

    /** Where a catalogued model is installed: {@code config/voicespells/models/<id>/}. */
    public static Path dirFor(String id) {
        return baseDir().resolve("models").resolve(id);
    }

    /**
     * The pre-catalog install location, {@code config/voicespells/model/}.
     *
     * <p>Every existing user has their model here, so it stays supported indefinitely and is
     * preferred when populated. Silently ignoring it would make an update look like the model had
     * vanished and trigger a fresh multi-hundred-megabyte download.
     */
    public static Path legacyDir() {
        return baseDir().resolve("model");
    }

    /** A directory is a usable model if it has the subdirectories Vosk requires. */
    public static boolean looksLikeModel(Path dir) {
        return dir != null
            && Files.isDirectory(dir.resolve("am"))
            && Files.isDirectory(dir.resolve("conf"));
    }

    /**
     * Resolve which model directory to actually load, in priority order:
     * <ol>
     *   <li>an explicit {@code modelPath} in the config — absolute control, never overridden</li>
     *   <li>the legacy {@code model/} directory, if it holds a real model</li>
     *   <li>{@code models/&lt;modelId&gt;/}, which is where downloads land</li>
     * </ol>
     */
    public static Path resolveModelDir(String explicitPath, String modelId) {
        if (explicitPath != null && !explicitPath.isBlank()) {
            return Path.of(explicitPath.trim());
        }
        Path legacy = legacyDir();
        if (looksLikeModel(legacy)) return legacy;
        return dirFor(modelId == null || modelId.isBlank() ? DEFAULT_ID : modelId.trim());
    }
}
