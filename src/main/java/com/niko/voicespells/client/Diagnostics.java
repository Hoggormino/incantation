package com.niko.voicespells.client;

import com.niko.voicespells.VoiceSpellsConfig;
import com.niko.voicespells.spells.SpellIndex;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.fml.ModList;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Runtime self-checks. Probes every subsystem the mod depends on (microphone, Iron's Spells, Curios,
 * Vosk, spell index, recognition pipeline, config) and returns a structured report.
 *
 * Used by {@link DiagnosticsScreen} to surface problems to the user. Each check returns a
 * {@link Result} with one of four statuses:
 *   - OK      : working as expected
 *   - WARN    : working but degraded (e.g. microphone not started yet, spell index small)
 *   - FAIL    : broken — cast / recognition can't function
 *   - INFO    : neutral diagnostic info, not a pass/fail
 */
public final class Diagnostics {
    private Diagnostics() {}

    public enum Status { OK, WARN, FAIL, INFO }
    public record Result(String name, Status status, String detail) {}

    /** Runs every check and returns the results in display order. */
    public static List<Result> runAll() {
        List<Result> out = new ArrayList<>();
        // --- Integrations ---
        out.add(checkMicrophone());
        out.add(checkIronsSpells());
        out.add(checkCurios());
        // --- Recognition stack ---
        out.add(checkVoskModel());
        out.add(checkSpellIndex());
        out.add(checkRecognitionActivity());
        // --- Cast pipeline ---
        out.add(checkClientMagicData());
        out.add(checkRecentCasts());
        // --- Config + runtime ---
        out.add(checkClientConfig());
        out.add(checkServerConfig());
        out.add(checkPhraseConflicts());
        out.add(checkMemory());
        return out;
    }

    /** Scan customPhrases + incantations for entries where the same phrase maps to different
     *  spell ids — flag the conflict so users can resolve which one they actually meant. */
    private static Result checkPhraseConflicts() {
        java.util.Map<String, String> seen = new java.util.HashMap<>();
        java.util.List<String> conflicts = new java.util.ArrayList<>();
        java.util.List<String> all = new java.util.ArrayList<>();
        try {
            all.addAll(VoiceSpellsConfig.CLIENT.customPhrases.get());
            all.addAll(VoiceSpellsConfig.CLIENT.incantations.get());
        } catch (Throwable ignored) {
            return new Result("Phrase conflicts", Status.INFO, "Config not loaded");
        }
        for (String entry : all) {
            if (entry == null) continue;
            int eq = entry.indexOf('=');
            if (eq <= 0 || eq >= entry.length() - 1) continue;
            String phrase = entry.substring(0, eq).trim().toLowerCase(Locale.ROOT);
            String id     = entry.substring(eq + 1).trim();
            if (seen.containsKey(phrase) && !seen.get(phrase).equals(id)) {
                conflicts.add("'" + phrase + "' → " + seen.get(phrase) + " then " + id);
            }
            seen.put(phrase, id);
        }
        if (conflicts.isEmpty()) {
            return new Result("Phrase conflicts", Status.OK,
                seen.size() + " custom/incantation phrase(s), no duplicates");
        }
        return new Result("Phrase conflicts", Status.WARN,
            conflicts.size() + " duplicate(s): " + conflicts.get(0)
            + (conflicts.size() > 1 ? " (+" + (conflicts.size() - 1) + " more)" : ""));
    }

    // -------------------------------------------------------------------- Integrations

    /** Microphone probe. Replaces the old Simple Voice Chat check now that the mod captures
     *  audio itself — the question is no longer "is SVC installed" but "is a device delivering
     *  samples". */
    private static Result checkMicrophone() {
        java.util.List<String> devices = com.niko.voicespells.speech.MicCapture.listDevices();
        com.niko.voicespells.speech.MicCapture cap = VoiceController.captureEngine();
        if (cap == null) {
            return new Result("Microphone", Status.WARN,
                devices.isEmpty()
                    ? "Capture not started, and no devices detected"
                    : "Capture not started (" + devices.size() + " device(s) available)");
        }
        String status = cap.status();
        if ("capturing".equals(status)) {
            return new Result("Microphone", Status.OK,
                "Capturing at " + com.niko.voicespells.speech.MicCapture.SAMPLE_RATE + " Hz");
        }
        if ("no device".equals(status) || "device missing".equals(status)) {
            return new Result("Microphone", Status.FAIL,
                status + " — run /voicespells devices to see what is connected");
        }
        return new Result("Microphone", Status.WARN, status);
    }

    private static Result checkIronsSpells() {
        if (!ModList.get().isLoaded("irons_spellbooks")) {
            return new Result("Iron's Spells", Status.FAIL,
                "Not installed — there are no spells to cast");
        }
        // Verify the registry class is reachable. ApiSelfCheck at startup already does this
        // in detail; we just do a smoke probe here so the UI is fast.
        try {
            Class.forName("io.redspace.ironsspellbooks.api.registry.SpellRegistry");
            return new Result("Iron's Spells", Status.OK, "Registry reachable");
        } catch (Throwable t) {
            return new Result("Iron's Spells", Status.FAIL,
                "Registry class missing: " + t.getMessage());
        }
    }

    private static Result checkCurios() {
        if (!ModList.get().isLoaded("curios")) {
            return new Result("Curios API", Status.WARN,
                "Not installed — spellbook detection falls back to mainhand/offhand only");
        }
        try {
            Class.forName("top.theillusivec4.curios.api.CuriosApi")
                .getMethod("getCuriosInventory", LivingEntity.class);
            return new Result("Curios API", Status.OK,
                "getCuriosInventory reachable");
        } catch (Throwable t) {
            return new Result("Curios API", Status.WARN,
                "Loaded but API method changed: " + t.getClass().getSimpleName());
        }
    }

    // -------------------------------------------------------------------- Recognition

    private static Result checkVoskModel() {
        String override = VoiceSpellsConfig.CLIENT.modelPath.get();
        Path p = (override == null || override.isBlank())
            ? com.niko.voicespells.speech.VoskSession.defaultModelPath()
            : Path.of(override.trim());
        if (!Files.isDirectory(p)) {
            return new Result("Vosk model", Status.FAIL,
                "Directory not present: " + p.toAbsolutePath()
                + (VoiceSpellsConfig.CLIENT.autoDownloadModel.get()
                    ? " (auto-download is on — wait for first launch to finish)"
                    : " (auto-download is off; install a model manually)"));
        }
        String status = VoiceController.statusLine();
        if ("READY".equals(status)) {
            return new Result("Vosk model", Status.OK, "Loaded from " + p);
        }
        if (status == null || status.isEmpty()) {
            return new Result("Vosk model", Status.WARN, "Model on disk but not loaded yet");
        }
        return new Result("Vosk model", Status.WARN, "Status: " + status);
    }

    private static Result checkSpellIndex() {
        if (!SpellIndex.isReady()) {
            return new Result("Spell index", Status.FAIL,
                "Empty — Iron's Spells didn't expose any enabled spells");
        }
        int n = SpellIndex.allSpells().size();
        int phrases = SpellIndex.getPhrases().size();
        Status st = (n < 20) ? Status.WARN : Status.OK;
        return new Result("Spell index", st,
            n + " spells, " + phrases + " grammar phrases (incl. aliases)");
    }

    private static Result checkRecognitionActivity() {
        List<VoiceController.RecognitionEvent> events = VoiceController.recentEvents();
        if (events.isEmpty()) {
            return new Result("Recognition activity", Status.INFO,
                "No recognition events yet this session");
        }
        long ageSec = TimeUnit.NANOSECONDS.toSeconds(
            System.nanoTime() - events.get(0).nanoTime());
        if (ageSec > 300) {
            return new Result("Recognition activity", Status.WARN,
                "Last event " + ageSec + "s ago — recognizer may have stalled");
        }
        return new Result("Recognition activity", Status.OK,
            events.size() + " events buffered, freshest " + ageSec + "s ago");
    }

    // -------------------------------------------------------------------- Cast pipeline

    private static Result checkClientMagicData() {
        try {
            Class<?> cmd = Class.forName("io.redspace.ironsspellbooks.api.magic.ClientMagicData");
            Method m = cmd.getMethod("isCasting");
            m.invoke(null);
            return new Result("ClientMagicData", Status.OK,
                "isCasting() reflective call succeeded");
        } catch (ClassNotFoundException notLoaded) {
            return new Result("ClientMagicData", Status.WARN,
                "Iron's Spells client class missing — cast queue degrades to no-op");
        } catch (Throwable t) {
            return new Result("ClientMagicData", Status.WARN,
                "Reflective call failed: " + t.getClass().getSimpleName());
        }
    }

    private static Result checkRecentCasts() {
        int total = VoiceStats.totalCasts();
        long lastMs = VoiceStats.lastCastMs();
        if (total == 0) {
            return new Result("Cast history", Status.INFO,
                "No successful casts recorded yet");
        }
        return new Result("Cast history", Status.OK,
            total + " total casts · last " + VoiceStats.fmtElapsed(lastMs));
    }

    // -------------------------------------------------------------------- Config

    private static Result checkClientConfig() {
        try {
            // A read confirms the spec loaded.
            VoiceSpellsConfig.CLIENT.dedupMillis.get();
            return new Result("Client config", Status.OK,
                "Loaded; dedupMillis=" + VoiceSpellsConfig.cDedupNanos / 1_000_000L
                + ", minConfidence=" + VoiceSpellsConfig.cMinConfidence);
        } catch (Throwable t) {
            return new Result("Client config", Status.FAIL,
                "Not loaded: " + t.getClass().getSimpleName());
        }
    }

    private static Result checkServerConfig() {
        try {
            com.niko.voicespells.VoiceSpellsServerConfig.CastMode mode =
                com.niko.voicespells.VoiceSpellsServerConfig.SERVER.castMode.get();
            int max = com.niko.voicespells.VoiceSpellsServerConfig.SERVER.maxCastsPerSecond.get();
            return new Result("Server config", Status.OK,
                "castMode=" + mode + ", maxCastsPerSecond=" + max);
        } catch (IllegalStateException notLoaded) {
            return new Result("Server config", Status.INFO,
                "Not loaded (singleplayer needs a world; dedicated server: per-world)");
        } catch (Throwable t) {
            return new Result("Server config", Status.WARN, t.getClass().getSimpleName());
        }
    }

    private static Result checkMemory() {
        Runtime rt = Runtime.getRuntime();
        long usedMb  = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
        long totalMb = rt.totalMemory() / (1024 * 1024);
        long maxMb   = rt.maxMemory() / (1024 * 1024);
        return new Result("JVM memory", Status.INFO,
            String.format(Locale.ROOT, "%dMB used / %dMB committed / %dMB max",
                usedMb, totalMb, maxMb));
    }

    /** Compact one-line summary for the More menu or chat. */
    public static String shortSummary(List<Result> results) {
        int ok = 0, warn = 0, fail = 0;
        for (Result r : results) {
            switch (r.status()) {
                case OK -> ok++;
                case WARN -> warn++;
                case FAIL -> fail++;
                case INFO -> { /* ignored in summary */ }
            }
        }
        if (fail > 0) return fail + " failing, " + warn + " warning";
        if (warn > 0) return ok + " OK, " + warn + " warning";
        return ok + " OK";
    }
}
