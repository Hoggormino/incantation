package com.niko.voicespells.client;

import com.niko.voicespells.VoiceSpells;
import com.niko.voicespells.VoiceSpellsConfig;
// ModelCatalog stays in the common `speech` package: the config specs on both loaders reference
// it, and config is loaded on a dedicated server. Only the download/capture/recognition side moved
// to `client`, so this import now crosses a package boundary that is deliberate.
import com.niko.voicespells.speech.ModelCatalog;

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
 * First-run convenience: fetch the configured Vosk model if none is installed.
 *
 * <p>Which model is decided by {@link ModelCatalog} from the {@code modelId} config, so any
 * catalogued language can be installed from config alone. An existing model is always left
 * untouched — a user who deliberately installed one keeps it.
 *
 * <p>Only runs when {@code recognition.autoDownloadModel} is on. When it is off, or the download
 * fails for any reason, the manual-install path and URL are logged so the user can drop the
 * archive in by hand; a blocked download must never become a dead end with no instructions.
 *
 * <p>Runs on the Vosk loader thread (already off the main thread), streaming the zip to disk with
 * percentage callbacks, verifying its SHA-256 where the catalog pins one, and extracting with a
 * zip-slip guard. A failed or partial download cleans up after itself so a retry starts clean.
 */
public final class ModelDownloader {

    private static final AtomicBoolean inProgress = new AtomicBoolean(false);

    private ModelDownloader() {}

    /** A directory is a usable model if it has the acoustic-model / config subdirs Vosk needs. */
    public static boolean looksLikeModel(Path dir) {
        return ModelCatalog.looksLikeModel(dir);
    }

    /**
     * Ensure a model exists at {@code modelDir}. Returns true if one is already there or was
     * downloaded successfully; false if absent and we couldn't/shouldn't fetch it.
     */
    public static boolean ensureModel(Path modelDir, IntConsumer onPercent) {
        if (looksLikeModel(modelDir)) return true;

        String id = VoiceSpellsConfig.cModelId;
        ModelCatalog.Entry entry = ModelCatalog.byId(id);
        if (entry == null) {
            VoiceSpells.LOGGER.error("Unknown modelId \"{}\" - cannot download it. Known ids: {}",
                id, ModelCatalog.all().stream().map(ModelCatalog.Entry::id).toList());
            explainManualInstall(modelDir, null);
            return false;
        }
        if (!VoiceSpellsConfig.CLIENT.autoDownloadModel.get()) {
            VoiceSpells.LOGGER.info("No Vosk model and autoDownloadModel is off - skipping fetch");
            explainManualInstall(modelDir, entry);
            return false;
        }
        if (!inProgress.compareAndSet(false, true)) return false; // a download is already running
        try {
            boolean ok = download(modelDir, entry, onPercent);
            if (!ok) explainManualInstall(modelDir, entry);
            return ok;
        } finally {
            inProgress.set(false);
        }
    }

    /**
     * Tell the user exactly how to install by hand. Reached whenever the automatic path is
     * unavailable - disabled, blocked by a firewall or proxy, or simply failed - because otherwise
     * the mod just sits there reporting no model with no way forward.
     */
    private static void explainManualInstall(Path modelDir, ModelCatalog.Entry entry) {
        VoiceSpells.LOGGER.info("To install a model by hand:");
        if (entry != null) {
            VoiceSpells.LOGGER.info("  1. Download {} ({})", entry.url(), entry.prettySize());
        } else {
            VoiceSpells.LOGGER.info("  1. Download a model from https://alphacephei.com/vosk/models");
        }
        VoiceSpells.LOGGER.info("  2. Unzip it, then copy the CONTENTS of the folder inside into:");
        VoiceSpells.LOGGER.info("       {}", modelDir.toAbsolutePath());
        VoiceSpells.LOGGER.info("  3. That directory should then directly contain am/ and conf/");
    }

    private static boolean download(Path modelDir, ModelCatalog.Entry entry, IntConsumer onPercent) {
        final String MODEL_URL = entry.url();
        Path tmpZip = modelDir.resolveSibling("model-download.zip.part");
        try {
            Files.createDirectories(modelDir);
            VoiceSpells.LOGGER.info("Downloading Vosk model {} ({}) from {}",
                entry.id(), entry.prettySize(), MODEL_URL);

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

            String digest = sha256(tmpZip);
            String expected = entry.sha256();
            if (expected != null && !expected.isBlank()) {
                if (!expected.equalsIgnoreCase(digest)) {
                    VoiceSpells.LOGGER.error(
                        "Model checksum mismatch for {} - expected {}, got {}. Discarding.",
                        entry.id(), expected, digest);
                    Files.deleteIfExists(tmpZip);
                    return false;
                }
                VoiceSpells.LOGGER.info("Model checksum verified ({})", digest);
            } else {
                // No pinned hash for this entry. Log the digest so a real value can be recorded in
                // ModelCatalog from a fetch that is known to be good.
                VoiceSpells.LOGGER.info(
                    "Model {} has no pinned checksum; downloaded file SHA-256 is {}",
                    entry.id(), digest);
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

    private static String sha256(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[1 << 16];
            int r;
            while ((r = in.read(buf)) != -1) md.update(buf, 0, r);
            StringBuilder sb = new StringBuilder(64);
            for (byte b : md.digest()) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Throwable t) {
            VoiceSpells.LOGGER.debug("Could not hash {}: {}", file, t.toString());
            return "";
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
