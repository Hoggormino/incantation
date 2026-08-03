package com.niko.voicespells.spells;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.niko.voicespells.VoiceSpells;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Per-spell phrase overrides driven by a JSON file at
 * {@code config/voicespells/phrasebook.json}. Powers the localisation workflow: a non-English
 * speaker can open the file, replace each spell's English default with the words they actually
 * want to say, and the recogniser's grammar rebuilds with the translated set.
 *
 * <p>The file is bootstrapped automatically the first time the spell index runs — every spell
 * Iron's Spells (and its addons) registers gets an entry with the English default phrase and an
 * empty {@code override} field. On subsequent runs we merge: brand-new spells are appended,
 * existing entries (and their overrides) are preserved untouched. Stale entries are kept so a
 * temporarily-uninstalled addon doesn't wipe the user's translations.
 *
 * <p>Note: the override phrase must consist of words Vosk's loaded model can pronounce. For
 * Spanish, German, French, etc. that means swapping in a same-language Vosk model — the
 * recogniser doesn't translate, it just matches whatever sounds are in its grammar to whatever
 * sounds are in its lexicon. Using a Spanish phrase against the English model will silently never
 * match. The README documents this.
 *
 * <p>Server-safe: this class lives in the spells package and references no client classes, so it
 * can be loaded on a dedicated server without dragging in the Minecraft GUI chain.
 */
public final class Phrasebook {

    /** Where the JSON lives. {@code <gamedir>/config/voicespells/phrasebook.json}. */
    private static final Path FILE = Path.of("config", VoiceSpells.MOD_ID, "phrasebook.json");

    /** Sibling temp path used by {@link #rewrite} for the atomic write-then-rename dance. */
    private static final Path TMP_FILE = Path.of("config", VoiceSpells.MOD_ID, "phrasebook.json.tmp");

    private static final Gson GSON = new GsonBuilder()
        .setPrettyPrinting()
        .disableHtmlEscaping()
        .create();

    private static final String HELP_TEXT =
        "Replace the 'override' value for each spell with the words you want to say in your "
        + "language. Empty override = use the English default. Vosk only recognises words "
        + "from the loaded model's lexicon — to use non-English phrases, install a same-"
        + "language Vosk model from alphacephei.com/vosk/models and point voicespells-"
        + "client.toml's modelPath at it. New spells installed later are appended "
        + "automatically; your existing overrides are preserved.";

    /** spellId (e.g. "irons_spellbooks:fireball") → user override phrase. Empty string = use
     *  the default. Lazily loaded from disk on first access. */
    private static volatile Map<String, String> overrides = Map.of();

    /** spellId → the {@code default} value we read from the file last time. Lets us preserve the
     *  English default for spells that are temporarily uninstalled, so when their addon is
     *  reinstalled the user's file still shows the original English alongside their translation. */
    private static volatile Map<String, String> defaultsFromFile = Map.of();

    /** True once {@link #load()} has run at least once this JVM. */
    private static volatile boolean loaded = false;

    /** Set when the most recent {@link #load()} hit an unreadable / malformed file. While true,
     *  {@link #rewrite} is a no-op — we never overwrite a file we couldn't parse, because the user
     *  may be mid-edit and a parse error here would mean we'd otherwise stomp their in-progress
     *  translation work with an empty-override rewrite. Cleared on the next successful load. */
    private static volatile boolean loadFailed = false;

    private Phrasebook() {}

    /** Read the phrasebook from disk into the in-memory map. Creates an empty placeholder file
     *  if one doesn't exist yet. Called from {@link SpellIndex#initialize()} and again after
     *  index rebuilds so external edits take effect without a game restart. */
    public static synchronized void load() {
        loaded = true;
        if (!Files.exists(FILE)) {
            overrides = new LinkedHashMap<>();
            defaultsFromFile = new LinkedHashMap<>();
            loadFailed = false;
            return;
        }
        try (Reader r = Files.newBufferedReader(FILE, StandardCharsets.UTF_8)) {
            JsonElement root = JsonParser.parseReader(r);
            if (!root.isJsonObject()) {
                VoiceSpells.LOGGER.warn("phrasebook.json root is not an object — ignoring (file left untouched)");
                loadFailed = true;
                return;
            }
            JsonObject spells = root.getAsJsonObject().getAsJsonObject("spells");
            if (spells == null) {
                // Valid JSON, just no "spells" object yet (e.g. user wiped the file to bootstrap fresh).
                overrides = new LinkedHashMap<>();
                defaultsFromFile = new LinkedHashMap<>();
                loadFailed = false;
                return;
            }
            Map<String, String> freshOverrides = new LinkedHashMap<>();
            Map<String, String> freshDefaults  = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> e : spells.entrySet()) {
                if (!e.getValue().isJsonObject()) continue;
                JsonObject entry = e.getValue().getAsJsonObject();
                JsonElement ov   = entry.get("override");
                JsonElement def  = entry.get("default");
                freshOverrides.put(e.getKey(),
                    (ov != null && ov.isJsonPrimitive()) ? ov.getAsString() : "");
                if (def != null && def.isJsonPrimitive()) {
                    freshDefaults.put(e.getKey(), def.getAsString());
                }
            }
            overrides = freshOverrides;
            defaultsFromFile = freshDefaults;
            loadFailed = false;
            int customised = (int) freshOverrides.values().stream().filter(s -> s != null && !s.isBlank()).count();
            if (customised > 0) {
                VoiceSpells.LOGGER.info("Loaded phrasebook ({} spells, {} custom override(s))",
                    freshOverrides.size(), customised);
            }
        } catch (IOException | RuntimeException ex) {
            // Don't clobber the in-memory state — keep whatever was last successfully loaded.
            // Set loadFailed so the next rewrite() skips and the user's file is preserved as-is.
            VoiceSpells.LOGGER.warn("Failed to read phrasebook.json: {} — file left untouched, "
                + "in-memory overrides preserved from the last successful load", ex.toString());
            loadFailed = true;
        }
    }

    /**
     * Look up the override phrase for a spell id. Returns {@code null} when the user hasn't set
     * one (or when the phrasebook hasn't been loaded yet), so the caller falls back to its
     * underscore-derived default.
     */
    public static String overrideFor(String spellId) {
        if (!loaded || spellId == null) return null;
        String s = overrides.get(spellId);
        if (s == null || s.isBlank()) return null;
        return s;
    }

    /**
     * Rewrite phrasebook.json with one entry per spell, preserving any existing overrides and
     * appending entries for spells that weren't there before. Called by {@link SpellIndex} after
     * a successful index so the file always mirrors the actual installed spell set.
     *
     * <p>If the most recent {@link #load()} failed to parse the file (e.g. the user is mid-edit
     * and saved a half-finished version), this method is a no-op — we never overwrite a file we
     * couldn't read, because doing so would silently destroy the user's translation work.
     *
     * @param spellIdToDefault ordered map of spell id → default English phrase, in the order
     *                         spells were discovered (so the file groups by namespace)
     */
    public static synchronized void rewrite(Map<String, String> spellIdToDefault) {
        if (!loaded) load(); // ensure we don't blow away an unread file
        if (loadFailed) {
            VoiceSpells.LOGGER.debug("Skipping phrasebook rewrite — last load failed, "
                + "file is being preserved as-is");
            return;
        }
        Map<String, String> merged = new LinkedHashMap<>();
        // 1. Every currently-installed spell, in registry order — fresh entries get the existing
        //    user override if there was one, else empty.
        for (Map.Entry<String, String> e : spellIdToDefault.entrySet()) {
            String existing = overrides.get(e.getKey());
            merged.put(e.getKey(), existing == null ? "" : existing);
        }
        // 2. Spells the user had overrides for but that aren't currently installed (addon
        //    uninstalled etc.) — keep them at the end so the user doesn't lose their translation
        //    work when they reinstall the addon later.
        for (Map.Entry<String, String> e : overrides.entrySet()) {
            if (!merged.containsKey(e.getKey()) && e.getValue() != null && !e.getValue().isBlank()) {
                merged.put(e.getKey(), e.getValue());
            }
        }
        // Update the in-memory cache to the merged view BEFORE writing — if writing fails we still
        // have the right runtime state.
        overrides = merged;

        try {
            Files.createDirectories(FILE.getParent());
            JsonObject root = new JsonObject();
            root.addProperty("_help", HELP_TEXT);
            JsonObject spellsObj = new JsonObject();
            for (Map.Entry<String, String> e : merged.entrySet()) {
                JsonObject entry = new JsonObject();
                // Default phrase priority: current-installed > remembered-from-last-load > "".
                // The middle tier keeps stale entries (uninstalled addons) showing their original
                // English phrase so the file remains useful documentation across addon swaps.
                String def = spellIdToDefault.get(e.getKey());
                if (def == null) def = defaultsFromFile.getOrDefault(e.getKey(), "");
                entry.addProperty("default", def);
                entry.addProperty("override", e.getValue() == null ? "" : e.getValue());
                spellsObj.add(e.getKey(), entry);
            }
            root.add("spells", spellsObj);
            // Write to a sibling .tmp file then atomic-rename onto the real path so the file is
            // either the old or the new content — never half-written. Important when the user is
            // tailing/watching the file or has it open in an editor.
            try (Writer w = Files.newBufferedWriter(TMP_FILE, StandardCharsets.UTF_8)) {
                GSON.toJson(root, w);
            }
            try {
                Files.move(TMP_FILE, FILE,
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailed) {
                // Some filesystems / OSes don't support atomic move across the rename — fall back
                // to a plain replace. Still safer than writing into FILE directly because we
                // wrote the whole payload to TMP first.
                Files.move(TMP_FILE, FILE, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ex) {
            VoiceSpells.LOGGER.warn("Failed to write phrasebook.json: {}", ex.toString());
            // Best-effort temp cleanup so a stale .tmp doesn't linger.
            try { Files.deleteIfExists(TMP_FILE); } catch (IOException ignored) {}
        }
    }
}
