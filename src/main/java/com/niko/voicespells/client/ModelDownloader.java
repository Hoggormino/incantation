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

    /**
     * Hard ceiling on both the downloaded archive and everything extracted from it.
     *
     * <p>Neither loop had one. The download wrote every byte the connection produced — a body
     * that streams past its declared Content-Length, or declares none at all, filled the disk
     * until something else in the game failed for lack of space. Extraction had the matching
     * hole: entries were streamed straight to disk with no running total, so a small archive of
     * highly-compressed entries reached the same place faster, and {@code looksLikeModel} only
     * runs afterwards. Catalogued models run from tens of MB to about 2 GB, so 4 GiB clears every
     * legitimate one by a wide margin and still stops a runaway well short of a full disk.
     */
    private static final long MAX_MODEL_BYTES = 4L * 1024 * 1024 * 1024;

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

        // Which model to fetch is decided by the DIRECTORY we were asked to fill, not by re-reading
        // the config. ModelCatalog.dirFor(id) is models/<id>, so the folder name is the id.
        //
        // Reading VoiceSpellsConfig.cModelId here made this a second source of truth about a
        // decision resolveModelDir had already made, and the two disagreed in a way that fetched
        // the wrong file: a player with modelPath pointed at an empty directory got whatever
        // modelId said - by default the ENGLISH archive - unpacked into the directory they had set
        // aside for a different model. It then looked like a real model and loaded, so the mod
        // listened in the wrong language and nothing anywhere said why.
        String id = modelDir.getFileName() == null ? "" : modelDir.getFileName().toString();
        ModelCatalog.Entry entry = ModelCatalog.byId(id);
        if (entry == null) {
            // A directory we cannot name is a directory we must not guess about. This is the
            // modelPath case: the player chose the location, so the model in it is their choice
            // too, and the only safe thing is to say what is missing.
            VoiceSpells.LOGGER.error("No Vosk model in \"{}\", and that folder is not a catalogue "
                + "model id, so there is nothing to download for it. Install a model there by "
                + "hand, or clear modelPath to use a catalogued one. Known ids: {}",
                modelDir, ModelCatalog.all().stream().map(ModelCatalog.Entry::id).toList());
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
            // Create the PARENT only, never modelDir itself. Pre-creating modelDir handed the
            // publish guard an existing-but-empty directory, which looksLikeModel rejects — so
            // the ownership rule ("never replace a directory that is not a model root") refused
            // to install into it, and auto-download could never complete on a fresh install:
            // precisely the first-launch path every new player takes. The parent is all the
            // zip download below actually needs.
            Path dlParent = modelDir.toAbsolutePath().getParent();
            if (dlParent != null) Files.createDirectories(dlParent);
            VoiceSpells.LOGGER.info("Downloading Vosk model {} ({}) from {}",
                entry.id(), entry.prettySize(), MODEL_URL);

            HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30))
                .build();
            // A READ timeout, not just a connect one. connectTimeout only covers the handshake;
            // a server that accepts the connection and then stalls mid-body left this blocked in
            // read() forever, on the Vosk loader thread — so the recogniser never came up for the
            // rest of the session and nothing in the UI could explain why. 10 minutes is generous
            // for a 1.8GB model on a slow line while still being finite.
            HttpRequest req = HttpRequest.newBuilder(URI.create(MODEL_URL))
                .timeout(Duration.ofMinutes(10))
                .GET().build();
            HttpResponse<InputStream> resp =
                client.send(req, HttpResponse.BodyHandlers.ofInputStream());
            long total = resp.headers().firstValueAsLong("content-length").orElse(-1L);

            // The body is closed on EVERY exit from here, the failure path included. ofInputStream
            // hands back a body that is still streaming, and the HTTP/2 connection is not released
            // until it is drained or closed - so the old early "return false" on a non-200 leaked
            // one held connection per attempt, and a mirror answering 403 leaked one per retry.
            try (InputStream in = resp.body()) {
                if (resp.statusCode() != 200) {
                    VoiceSpells.LOGGER.error("Model download HTTP {}", resp.statusCode());
                    return false;
                }
                // What the body is allowed to be. A declared length is trusted only as far as a
                // little slack past it; without one, the hard ceiling is all there is.
                long limit = total > 0
                    ? Math.min(MAX_MODEL_BYTES, total + (1 << 20))
                    : MAX_MODEL_BYTES;
                try (OutputStream out = Files.newOutputStream(tmpZip)) {
                    byte[] buf = new byte[1 << 16];
                    long read = 0;
                    int lastPct = -1;
                    int r;
                    while ((r = in.read(buf)) != -1) {
                        if (read + r > limit) {
                            VoiceSpells.LOGGER.warn(
                                "Model download from {} exceeded {} bytes (content-length said {})"
                                + " — abandoning it rather than filling the disk.",
                                MODEL_URL, limit, total > 0 ? total : "nothing");
                            out.close();
                            Files.deleteIfExists(tmpZip);
                            return false;
                        }
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

            // Extract to a staging directory and only publish it once it validates.
            //
            // Extracting straight into modelDir meant an interrupted or corrupt extraction left
            // a half-model behind, and looksLikeModel only asks whether am/ and conf/ exist —
            // which a partial extract satisfies easily. ensureModel checks that first and
            // returns true, so the download never retried: the model stayed broken, recognition
            // stayed dead, and it survived every restart with no way out but deleting the
            // folder by hand. Staging means a failed attempt leaves the previous state intact.
            Path staging = modelDir.resolveSibling(modelDir.getFileName() + ".incoming");
            deleteRecursively(staging);
            try {
                extractStrippingTopDir(tmpZip, staging);
                Files.deleteIfExists(tmpZip);

                if (!looksLikeModel(staging)) {
                    VoiceSpells.LOGGER.error(
                        "Model archive extracted but layout looks wrong; discarding {}", staging);
                    deleteRecursively(staging);
                    return false;
                }
                // Publish — but NEVER delete a directory this mod does not own.
                //
                // This used to call deleteRecursively(modelDir) unconditionally. modelDir is
                // whatever the player typed into `modelPath`, passed through verbatim, so that
                // line would recursively erase a user-chosen directory and everything under it.
                // The README itself tells non-English players to point modelPath at a model they
                // downloaded by hand, and Vosk archives wrap their contents in a top-level
                // folder — so the natural result of following those instructions is a path whose
                // am/ and conf/ sit one level deeper, which fails looksLikeModel, triggers the
                // auto-download, and then deletes the model they just installed. Pointing
                // modelPath at a shared folder of several models deleted all of them.
                //
                // The rule now: we only ever replace a directory that is already a model root,
                // i.e. one this mod would itself have produced. Anything else is the player's
                // and is left untouched, with a log line explaining why.
                Path absolute = modelDir.toAbsolutePath();
                Path parent = absolute.getParent();
                // getParent() is null for a single-segment relative path like modelPath="voskmodel";
                // createDirectories(null) throws NPE, which previously fired AFTER the delete.
                if (parent != null) Files.createDirectories(parent);

                Path retired = null;
                if (Files.exists(modelDir)) {
                    // An EMPTY directory cannot be anyone's data — replacing it is always safe,
                    // and older builds (and interrupted runs) leave one behind at exactly this
                    // path. Only a NON-empty non-model directory is refused.
                    boolean empty;
                    try (java.util.stream.Stream<Path> entries = Files.list(modelDir)) {
                        empty = entries.findFirst().isEmpty();
                    }
                    if (!empty && !looksLikeModel(modelDir)) {
                        VoiceSpells.LOGGER.error(
                            "Refusing to replace {} — it exists but is not a Vosk model directory "
                            + "(no am/ and conf/ inside it), so it is not ours to delete. If you "
                            + "meant to point modelPath at a hand-installed model, point it at the "
                            + "folder that directly contains am/ and conf/. Downloaded model left "
                            + "in {} and nothing was removed.", modelDir, staging);
                        return false;
                    }
                    // It is a model root, so it is safe to swap. Move it aside rather than
                    // deleting first: if the move of the new one fails we can put it back.
                    retired = absolute.resolveSibling(absolute.getFileName() + ".old");
                    deleteRecursively(retired);
                    Files.move(modelDir, retired);
                }

                // Restore in a finally, not in a catch. The previous shape put the non-atomic
                // fallback INSIDE a catch clause and the restore in a sibling catch — and sibling
                // catches do not cover each other, so if the fallback move itself failed (a full
                // disk, a file lock, a network share) the restore never ran: the player's working
                // model stayed parked in <name>.old, modelDir did not exist, and the mod reported
                // no model installed. The failure mode that most needed the fallback was the one
                // path with no recovery. A finally cannot be skipped by any of them.
                boolean moved = false;
                try {
                    try {
                        Files.move(staging, modelDir, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                    } catch (java.nio.file.AtomicMoveNotSupportedException amnse) {
                        Files.move(staging, modelDir);
                    }
                    moved = true;
                } finally {
                    if (!moved && retired != null && !Files.exists(modelDir)) {
                        try {
                            Files.move(retired, modelDir);
                            VoiceSpells.LOGGER.warn("Install failed; restored the previous model");
                        } catch (Throwable restoreFailed) {
                            // Last resort: tell the player exactly how to get their model back
                            // by hand, since we could neither install nor restore.
                            VoiceSpells.LOGGER.error(
                                "Install failed AND the previous model could not be restored. "
                                + "Your model is intact at {} — rename that directory back to {} "
                                + "to recover it.", retired, modelDir.getFileName());
                        }
                    }
                }
                if (retired != null) deleteRecursively(retired);
                VoiceSpells.LOGGER.info("Vosk model installed at {}", modelDir);
                return true;
            } catch (Throwable t) {
                deleteRecursively(staging);
                throw t;
            }
        } catch (Throwable t) {
            VoiceSpells.LOGGER.error("Model download failed: {}", t.toString());
            try { Files.deleteIfExists(tmpZip); } catch (Throwable ignored) {}
            return false;
        }
    }

    /** Delete a directory tree, best effort. Missing paths are not an error. */
    private static void deleteRecursively(Path dir) {
        if (dir == null || !Files.exists(dir)) return;
        try (java.util.stream.Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(pth -> {
                try { Files.deleteIfExists(pth); } catch (Throwable ignored) {}
            });
        } catch (Throwable t) {
            VoiceSpells.LOGGER.debug("Could not fully delete {}: {}", dir, t.toString());
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
        long written = 0;
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
                    // Count what actually lands on disk, across all entries, and check it per
                    // chunk rather than per entry. The declared size is not worth consulting: it
                    // is written by whoever built the archive, which is the party a size cap
                    // exists to distrust. Files.copy would not do either: it streams the WHOLE
                    // entry before the total can be looked at, so a single-entry archive - the
                    // cheapest bomb there is - would fill the disk exactly as if there were no cap.
                    try (OutputStream out = Files.newOutputStream(dest)) {
                        byte[] buf = new byte[1 << 16];
                        int n;
                        while ((n = zin.read(buf)) != -1) {
                            written += n;
                            if (written > MAX_MODEL_BYTES) {
                                throw new java.io.IOException(
                                    "Model archive expands past " + MAX_MODEL_BYTES + " bytes; "
                                    + "refusing to extract the rest of it");
                            }
                            out.write(buf, 0, n);
                        }
                    }
                }
            }
        }
    }
}
