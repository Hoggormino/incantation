package com.niko.voicespells.client;

import com.niko.voicespells.VoiceSpellsConfig;
import com.niko.voicespells.spells.SpellIndex;
import net.minecraft.world.entity.LivingEntity;
//? if forge {
/*import net.minecraftforge.fml.ModList;
*///?} else {
import net.neoforged.fml.ModList;
//?}

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

    /** Diagnostics rows carry plain strings, so they are localized here at construction. The
     *  screen rebuilds the whole list every time it opens, so a language change lands on reopen. */
    private static String tr(String key, Object... args) {
        return net.minecraft.network.chat.Component.translatable(key, args).getString();
    }

    /** Runs every check and returns the results in display order. */
    public static List<Result> runAll() {
        List<Result> out = new ArrayList<>();
        // --- Integrations ---
        out.add(checkMicrophone());
        out.add(checkCastPath());
        out.add(checkIronsSpells());
        out.add(checkCurios());
        // --- Recognition stack ---
        out.add(checkVoskModel());
        out.add(checkVocabulary());
        out.add(checkGrammarEncoding());
        out.add(checkSpellIndex());
        out.add(checkPhraseCollisions());
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
            return new Result(tr("voicespells.diag.name.custom_phrases"), Status.INFO, tr("voicespells.diag.custom.no_config"));
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
            return new Result(tr("voicespells.diag.name.custom_phrases"), Status.OK, tr("voicespells.diag.custom.ok", seen.size()));
        }
        return new Result(tr("voicespells.diag.name.custom_phrases"), Status.WARN, tr("voicespells.diag.custom.dupes", conflicts.size(), conflicts.get(0)) + (conflicts.size() > 1 ? tr("voicespells.diag.more_suffix", conflicts.size() - 1) : ""));
    }

    // -------------------------------------------------------------------- Integrations

    /** Microphone probe. Replaces the old Simple Voice Chat check now that the mod captures
     *  audio itself — the question is no longer "is SVC installed" but "is a device delivering
     *  samples". */
    private static Result checkMicrophone() {
        java.util.List<String> devices = com.niko.voicespells.client.MicCapture.listDevices();
        com.niko.voicespells.client.MicCapture cap = VoiceController.captureEngine();
        if (cap == null) {
            return new Result(tr("voicespells.diag.name.microphone"), Status.WARN, devices.isEmpty() ? tr("voicespells.diag.mic.idle_none") : tr("voicespells.diag.mic.idle", devices.size()));
        }
        String status = cap.status();
        if ("capturing".equals(status)) {
            // "capturing" alone used to report OK, and that was the most misleading line in the
            // whole diagnostics screen: a virtual audio driver that is the Windows default opens
            // fine, reports samples, and delivers nothing but zeroes. Every other check passed,
            // this one said OK, and voice casting still never fired. An open device that has
            // produced pure silence for seconds is a FAIL with a fix attached, not an OK.
            if (VoiceController.deviceSilent()) {
                return new Result(tr("voicespells.diag.name.microphone"), Status.FAIL, tr("voicespells.diag.mic.silent"));
            }
            return new Result(tr("voicespells.diag.name.microphone"), Status.OK, tr("voicespells.diag.mic.capturing", com.niko.voicespells.client.MicCapture.SAMPLE_RATE));
        }
        if ("no device".equals(status) || "device missing".equals(status)) {
            return new Result(tr("voicespells.diag.name.microphone"), Status.FAIL, tr("voicespells.diag.mic.no_device", status));
        }
        return new Result(tr("voicespells.diag.name.microphone"), Status.WARN, status);
    }

    /**
     * Phrases claimed by two spells. Silent until now: the index map simply overwrote the first
     * entry, so one of the two spells stopped working with nothing said anywhere. A translated
     * phrasebook hits this easily, because two mods' spells often share a word in another
     * language.
     */
    private static Result checkPhraseCollisions() {
        java.util.List<String> clashes = com.niko.voicespells.spells.SpellIndex.phraseCollisions();
        if (clashes.isEmpty()) {
            return new Result(tr("voicespells.diag.name.collisions"), Status.OK, tr("voicespells.diag.collide.ok"));
        }
        String first = clashes.get(0);
        return new Result(tr("voicespells.diag.name.collisions"), Status.WARN, tr("voicespells.diag.collide.warn", clashes.size(), first));
    }

    /** Which route casts actually take. The client path needs no server-side component; the
     *  fallback does, and silently requires the mod on the server. Worth surfacing, because the
     *  difference is invisible until someone joins a server without it. */
    private static Result checkCastPath() {
        Boolean avail = ClientCast.availability();
        if (Boolean.TRUE.equals(avail)) {
            return new Result(tr("voicespells.diag.name.cast_path"), Status.OK, tr("voicespells.diag.path.client"));
        }
        if (Boolean.FALSE.equals(avail)) {
            return new Result(tr("voicespells.diag.name.cast_path"), Status.WARN, tr("voicespells.diag.path.fallback"));
        }
        return new Result(tr("voicespells.diag.name.cast_path"), Status.INFO, tr("voicespells.diag.path.untried"));
    }

    private static Result checkIronsSpells() {
        if (!ModList.get().isLoaded("irons_spellbooks")) {
            return new Result(tr("voicespells.diag.name.irons_spells"), Status.FAIL, tr("voicespells.diag.iss.missing"));
        }
        // Verify the registry class is reachable. ApiSelfCheck at startup already does this
        // in detail; we just do a smoke probe here so the UI is fast.
        try {
            Class.forName("io.redspace.ironsspellbooks.api.registry.SpellRegistry");
            return new Result(tr("voicespells.diag.name.irons_spells"), Status.OK, tr("voicespells.diag.iss.ok"));
        } catch (Throwable t) {
            return new Result(tr("voicespells.diag.name.irons_spells"), Status.FAIL, tr("voicespells.diag.iss.no_registry", t.getMessage()));
        }
    }

    private static Result checkCurios() {
        if (!ModList.get().isLoaded("curios")) {
            return new Result(tr("voicespells.diag.name.curios"), Status.WARN, tr("voicespells.diag.curios.missing"));
        }
        try {
//? if forge {
/*            java.lang.reflect.Method m = Class.forName("top.theillusivec4.curios.api.CuriosApi")
*///?} else {
            Class.forName("top.theillusivec4.curios.api.CuriosApi")
//?}
                .getMethod("getCuriosInventory", LivingEntity.class);
//? if forge {
/*            // Reachability alone is not enough — the return type differs between Minecraft
            // versions (LazyOptional on 1.20.1, Optional on 1.21.1) and an unrecognised one means
            // every Curios spellbook lookup silently returns nothing.
            if (!com.niko.voicespells.spells.CuriosCompat.returnTypeSupported(m)) {
                return new Result(tr("voicespells.diag.name.curios"), Status.WARN, tr("voicespells.diag.curios.bad_type", m.getReturnType().getSimpleName()));
            }
*///?}
            return new Result(tr("voicespells.diag.name.curios"), Status.OK,
//? if forge {
/*                tr("voicespells.diag.curios.ok_type", m.getReturnType().getSimpleName()));
*///?} else {
                tr("voicespells.diag.curios.ok"));
//?}
        } catch (Throwable t) {
            return new Result(tr("voicespells.diag.name.curios"), Status.WARN, tr("voicespells.diag.curios.changed", t.getClass().getSimpleName()));
        }
    }

    // -------------------------------------------------------------------- Recognition

    /**
     * Whether the loaded model can actually pronounce the phrases the mod is listening for.
     *
     * <p>The failure this catches has no other symptom. Vosk cannot emit a word that is not in its
     * lexicon, so a phrase containing one never matches: the player says the spell's name, nothing
     * happens, and every other line on this screen says OK. Until the vocabulary could be read
     * there was no way to distinguish that from a microphone problem.
     */
    private static Result checkVocabulary() {
        com.niko.voicespells.spells.SpellIndex.VocabReport r =
            com.niko.voicespells.spells.SpellIndex.vocabularyReport();
        if (r.vocabularyWords() == 0) {
            // Not a failure on its own - the model may still be loading, and every consumer of the
            // vocabulary degrades to "change nothing" without it.
            return new Result(tr("voicespells.diag.name.vocabulary"), Status.WARN, tr("voicespells.diag.vocab.unread"));
        }
        if (!r.dead().isEmpty()) {
            String names = String.join(", ", r.dead().subList(0, Math.min(4, r.dead().size())));
            return new Result(tr("voicespells.diag.name.vocabulary"), Status.FAIL, tr("voicespells.diag.vocab.dead", r.dead().size(), names + (r.dead().size() > 4 ? ", ..." : "")));
        }
        if (!r.rescued().isEmpty()) {
            return new Result(tr("voicespells.diag.name.vocabulary"), Status.OK, tr("voicespells.diag.vocab.rescued", r.vocabularyWords(), r.rescued().size()));
        }
        return new Result(tr("voicespells.diag.name.vocabulary"), Status.OK, tr("voicespells.diag.vocab.ok", r.vocabularyWords()));
    }

    /**
     * Whether non-ASCII phrases survive the trip into the recogniser.
     *
     * <p>Only interesting to somebody using a non-English model or a translated phrasebook, which
     * is exactly the person who cannot tell a broken accent from a broken microphone.
     */
    private static Result checkGrammarEncoding() {
        if (VoskSession.utf8Grammar) {
            return new Result(tr("voicespells.diag.name.encoding"), Status.OK, tr("voicespells.diag.enc.ok"));
        }
        return new Result(tr("voicespells.diag.name.encoding"), Status.WARN, tr("voicespells.diag.enc.warn"));
    }

    private static Result checkVoskModel() {
        // Resolve exactly as loadVosk() does. This used to fall back to
        // VoskSession.defaultModelPath(), the LEGACY model/ directory, while downloads land in
        // models/<modelId>/ — so a perfectly healthy default install reported "Vosk model FAIL,
        // directory not present" and sent people chasing a problem they did not have. The check
        // and the loader must not be able to disagree about where the model lives.
        Path p = com.niko.voicespells.speech.ModelCatalog.resolveModelDir(
            VoiceSpellsConfig.CLIENT.modelPath.get(), VoiceSpellsConfig.cModelId);
        if (!Files.isDirectory(p)) {
            return new Result(tr("voicespells.diag.name.vosk_model"), Status.FAIL, tr("voicespells.diag.model.missing", p.toAbsolutePath()) + (VoiceSpellsConfig.CLIENT.autoDownloadModel.get() ? tr("voicespells.diag.model.autodl_on") : tr("voicespells.diag.model.autodl_off")));
        }
        String status = VoiceController.statusLine();
        if ("READY".equals(status)) {
            return new Result(tr("voicespells.diag.name.vosk_model"), Status.OK, tr("voicespells.diag.model.loaded", p));
        }
        if (status == null || status.isEmpty()) {
            return new Result(tr("voicespells.diag.name.vosk_model"), Status.WARN, tr("voicespells.diag.model.not_loaded"));
        }
        return new Result(tr("voicespells.diag.name.vosk_model"), Status.WARN, tr("voicespells.diag.model.status", status));
    }

    private static Result checkSpellIndex() {
        if (!SpellIndex.isReady()) {
            return new Result(tr("voicespells.diag.name.spell_index"), Status.FAIL, tr("voicespells.diag.index.empty"));
        }
        int n = SpellIndex.allSpells().size();
        int phrases = SpellIndex.getPhrases().size();
        Status st = (n < 20) ? Status.WARN : Status.OK;
        return new Result(tr("voicespells.diag.name.spell_index"), st, tr("voicespells.diag.index.ok", n, phrases));
    }

    private static Result checkRecognitionActivity() {
        List<VoiceController.RecognitionEvent> events = VoiceController.recentEvents();
        if (events.isEmpty()) {
            return new Result(tr("voicespells.diag.name.recognition"), Status.INFO, tr("voicespells.diag.recog.none"));
        }
        long ageSec = TimeUnit.NANOSECONDS.toSeconds(
            System.nanoTime() - events.get(0).nanoTime());
        if (ageSec > 300) {
            return new Result(tr("voicespells.diag.name.recognition"), Status.WARN, tr("voicespells.diag.recog.stalled", ageSec));
        }
        return new Result(tr("voicespells.diag.name.recognition"), Status.OK, tr("voicespells.diag.recog.ok", events.size(), ageSec));
    }

    // -------------------------------------------------------------------- Cast pipeline

    private static Result checkClientMagicData() {
        try {
            Class<?> cmd = Class.forName("io.redspace.ironsspellbooks.player.ClientMagicData");
            Method m = cmd.getMethod("isCasting");
            m.invoke(null);
            return new Result(tr("voicespells.diag.name.magic_data"), Status.OK, tr("voicespells.diag.cmd.ok"));
        } catch (ClassNotFoundException notLoaded) {
            return new Result(tr("voicespells.diag.name.magic_data"), Status.WARN, tr("voicespells.diag.cmd.missing"));
        } catch (Throwable t) {
            return new Result(tr("voicespells.diag.name.magic_data"), Status.WARN, tr("voicespells.diag.cmd.failed", t.getClass().getSimpleName()));
        }
    }

    private static Result checkRecentCasts() {
        int total = VoiceStats.totalCasts();
        long lastMs = VoiceStats.lastCastMs();
        if (total == 0) {
            return new Result(tr("voicespells.diag.name.cast_history"), Status.INFO, tr("voicespells.diag.history.none"));
        }
        return new Result(tr("voicespells.diag.name.cast_history"), Status.OK, tr("voicespells.diag.history.ok", total, VoiceStats.fmtElapsed(lastMs)));
    }

    // -------------------------------------------------------------------- Config

    private static Result checkClientConfig() {
        try {
            // A read confirms the spec loaded.
            VoiceSpellsConfig.CLIENT.dedupMillis.get();
            return new Result(tr("voicespells.diag.name.client_config"), Status.OK, tr("voicespells.diag.client.ok", VoiceSpellsConfig.cDedupNanos / 1_000_000L, VoiceSpellsConfig.cMinConfidence));
        } catch (Throwable t) {
            return new Result(tr("voicespells.diag.name.client_config"), Status.FAIL, tr("voicespells.diag.client.failed", t.getClass().getSimpleName()));
        }
    }

    private static Result checkServerConfig() {
        try {
            com.niko.voicespells.VoiceSpellsServerConfig.CastMode mode =
                com.niko.voicespells.VoiceSpellsServerConfig.SERVER.castMode.get();
            int max = com.niko.voicespells.VoiceSpellsServerConfig.SERVER.maxCastsPerSecond.get();
            return new Result(tr("voicespells.diag.name.server_config"), Status.OK, tr("voicespells.diag.server.ok", mode, max));
        } catch (IllegalStateException notLoaded) {
            return new Result(tr("voicespells.diag.name.server_config"), Status.INFO, tr("voicespells.diag.server.not_loaded"));
        } catch (Throwable t) {
            return new Result(tr("voicespells.diag.name.server_config"), Status.WARN, t.getClass().getSimpleName());
        }
    }

    private static Result checkMemory() {
        Runtime rt = Runtime.getRuntime();
        long usedMb  = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
        long totalMb = rt.totalMemory() / (1024 * 1024);
        long maxMb   = rt.maxMemory() / (1024 * 1024);
        return new Result(tr("voicespells.diag.name.jvm_memory"), Status.INFO, tr("voicespells.diag.jvm.ok", usedMb, totalMb, maxMb));
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
