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

    /** True once {@link #load()} has run at least once this JVM. */
    private static volatile boolean loaded = false;

    private Phrasebook() {}

    /** Read the phrasebook from disk into the in-memory map. Creates an empty placeholder file
     *  if one doesn't exist yet. Called from {@link SpellIndex#initialize()} and again after
     *  index rebuilds so external edits take effect without a game restart. */
    public static synchronized void load() {
        loaded = true;
        if (!Files.exists(FILE)) {
            overrides = new LinkedHashMap<>();
            return;
        }
        try (Reader r = Files.newBufferedReader(FILE, StandardCharsets.UTF_8)) {
            JsonElement root = JsonParser.parseReader(r);
            if (!root.isJsonObject()) {
                VoiceSpells.LOGGER.warn("phrasebook.json root is not an object — ignoring");
                overrides = new LinkedHashMap<>();
                return;
            }
            JsonObject spells = root.getAsJsonObject().getAsJsonObject("spells");
            if (spells == null) {
                overrides = new LinkedHashMap<>();
                return;
            }
            Map<String, String> fresh = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> e : spells.entrySet()) {
                if (!e.getValue().isJsonObject()) continue;
                JsonElement ov = e.getValue().getAsJsonObject().get("override");
                if (ov != null && ov.isJsonPrimitive()) {
                    fresh.put(e.getKey(), ov.getAsString());
                } else {
                    fresh.put(e.getKey(), "");
                }
            }
            overrides = fresh;
            int customised = (int) fresh.values().stream().filter(s -> s != null && !s.isBlank()).count();
            if (customised > 0) {
                VoiceSpells.LOGGER.info("Loaded phrasebook ({} spells, {} custom override(s))",
                    fresh.size(), customised);
            }
        } catch (IOException | RuntimeException ex) {
            VoiceSpells.LOGGER.warn("Failed to read phrasebook.json: {} — using defaults", ex.toString());
            overrides = new LinkedHashMap<>();
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
     * @param spellIdToDefault ordered map of spell id → default English phrase, in the order
     *                         spells were discovered (so the file groups by namespace)
     */
    public static synchronized void rewrite(Map<String, String> spellIdToDefault) {
        if (!loaded) load(); // ensure we don't blow away an unread file
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
                String def = spellIdToDefault.getOrDefault(e.getKey(), "");
                entry.addProperty("default", def);
                entry.addProperty("override", e.getValue() == null ? "" : e.getValue());
                spellsObj.add(e.getKey(), entry);
            }
            root.add("spells", spellsObj);
            try (Writer w = Files.newBufferedWriter(FILE, StandardCharsets.UTF_8)) {
                GSON.toJson(root, w);
            }
        } catch (IOException ex) {
            VoiceSpells.LOGGER.warn("Failed to write phrasebook.json: {}", ex.toString());
        }
    }

    /** Count of in-memory non-empty overrides — for diagnostics. */
    public static int activeOverrideCount() {
        if (!loaded) return 0;
        int n = 0;
        for (String v : overrides.values()) if (v != null && !v.isBlank()) n++;
        return n;
    }
}
