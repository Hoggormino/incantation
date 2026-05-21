package com.niko.voicespells.speech;

import com.niko.voicespells.VoiceSpells;
import com.niko.voicespells.VoiceSpellsConfig;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntConsumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * First-run convenience: if no Vosk model is installed, fetch the larger
 * {@code vosk-model-en-us-0.22-lgraph} (~128 MB) so recognition works out of
 * the box instead of requiring a manual download of the weaker small model.
 *
 * Only triggers when {@code config/voicespells/model/} has no usable model AND
 * {@code recognition.autoDownloadModel} is on. An existing model (small or
 * large) is left untouched — users who deliberately installed one keep it.
 *
 * Runs on the Vosk loader thread (already off the main thread), streaming the
 * zip to disk with percentage callbacks and extracting it with a zip-slip
 * guard. A failed/partial download cleans up after itself so a retry is clean.
 */
public final class ModelDownloader {

    private static final String MODEL_URL =
        "https://alphacephei.com/vosk/models/vosk-model-en-us-0.22-lgraph.zip";

    private static final AtomicBoolean inProgress = new AtomicBoolean(false);

    private ModelDownloader() {}

    /** A directory is a usable model if it has the acoustic-model / config subdirs Vosk needs. */
    public static boolean looksLikeModel(Path dir) {
        return Files.isDirectory(dir.resolve("am")) && Files.isDirectory(dir.resolve("conf"));
    }

    /**
     * Ensure a model exists at {@code modelDir}. Returns true if one is already there or was
     * downloaded successfully; false if absent and we couldn't/shouldn't fetch it.
     */
    public static boolean ensureModel(Path modelDir, IntConsumer onPercent) {
        if (looksLikeModel(modelDir)) return true;
        if (!VoiceSpellsConfig.CLIENT.autoDownloadModel.get()) {
            VoiceSpells.LOGGER.info("No Vosk model and autoDownloadModel is off — skipping fetch");
            return false;
        }
        if (!inProgress.compareAndSet(false, true)) return false; // a download is already running
        try {
            return download(modelDir, onPercent);
        } finally {
            inProgress.set(false);
        }
    }

    private static boolean download(Path modelDir, IntConsumer onPercent) {
        Path tmpZip = modelDir.resolveSibling("model-download.zip.part");
        try {
            Files.createDirectories(modelDir);
            VoiceSpells.LOGGER.info("Downloading Vosk model (~128 MB) from {}", MODEL_URL);

            HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30))
                .build();
            HttpRequest req = HttpRequest.newBuilder(URI.create(MODEL_URL)).GET().build();
            HttpResponse<InputStream> resp =
                client.send(req, HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() != 200) {
                VoiceSpells.LOGGER.error("Model download HTTP {}", resp.statusCode());
                return false;
            }
            long total = resp.headers().firstValueAsLong("content-length").orElse(-1L);

            try (InputStream in = resp.body();
                 OutputStream out = Files.newOutputStream(tmpZip)) {
                byte[] buf = new byte[1 << 16];
                long read = 0;
                int lastPct = -1;
                int r;
                while ((r = in.read(buf)) != -1) {
                    out.write(buf, 0, r);
                    read += r;
                    if (total > 0) {
                        int pct = (int) (read * 100 / total);
                        if (pct >= lastPct + 5) {
                            lastPct = pct;
                            if (onPercent != null) onPercent.accept(pct);
                            VoiceSpells.LOGGER.info("Vosk model download {}%", pct);
                        }
                    }
                }
            }

            extractStrippingTopDir(tmpZip, modelDir);
            Files.deleteIfExists(tmpZip);

            boolean ok = looksLikeModel(modelDir);
            VoiceSpells.LOGGER.info(ok ? "Vosk model installed at {}"
                                       : "Model archive extracted but layout looks wrong at {}",
                modelDir);
            return ok;
        } catch (Throwable t) {
            VoiceSpells.LOGGER.error("Model download failed: {}", t.toString());
            try { Files.deleteIfExists(tmpZip); } catch (Throwable ignored) {}
            return false;
        }
    }

    /**
     * Vosk archives wrap everything in a single top-level dir
     * ({@code vosk-model-en-us-0.22-lgraph/...}); we want its contents directly under
     * {@code modelDir}, so strip the first path segment of every entry.
     */
    private static void extractStrippingTopDir(Path zip, Path modelDir) throws Exception {
        try (ZipInputStream zin = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                String name = e.getName().replace('\\', '/');
                int slash = name.indexOf('/');
                if (slash < 0) continue;                 // the top-level dir entry itself
                String rel = name.substring(slash + 1);
                if (rel.isEmpty()) continue;

                Path dest = modelDir.resolve(rel).normalize();
                if (!dest.startsWith(modelDir)) continue; // zip-slip guard

                if (e.isDirectory()) {
                    Files.createDirectories(dest);
                } else {
                    if (dest.getParent() != null) Files.createDirectories(dest.getParent());
                    Files.copy(zin, dest, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }
}
