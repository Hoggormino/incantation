package com.niko.voicespells.spells;

import com.niko.voicespells.VoiceSpells;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceLocation;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Reflective index over Iron's Spells 'n Spellbooks' SpellRegistry. We discover
 * every enabled spell, derive a spoken phrase from each spell's resource path
 * (underscores become spaces), and expose:
 *   - {@link #getPhrases()} for building the Vosk grammar
 *   - {@link #lookup(String)} for resolving a heard phrase to a spell's
 *     ResourceLocation, which the server side then casts.
 *
 * Reflection lets us avoid compile-time coupling to Iron's Spells — convenient
 * when its jar isn't on the dev classpath, and harmless at runtime since the
 * mod is a hard dependency.
 */
public final class SpellIndex {
    private static final String REGISTRY_CLASS = "io.redspace.ironsspellbooks.api.registry.SpellRegistry";
    private static final String SPELL_CLASS    = "io.redspace.ironsspellbooks.api.spells.AbstractSpell";

    /**
     * Spell-name → extra pronunciations the user might say. Iron's Spells stores some compound
     * names as single tokens ("starfall", "counterspell"), which Vosk's small English model
     * usually doesn't have in its lexicon — those grammar entries silently never match. Adding
     * the space-split variant gives the recognizer something it can actually pronounce, and
     * because both spellings point at the same spell id either one casts.
     *
     * Values are short — most are obviously one or two splits. Extending this map costs nothing
     * but a line per spell, so when a user reports a stubborn spell, add it here.
     */
    private static final Map<String, List<String>> ALIASES = new HashMap<>();
    static {
        ALIASES.put("starfall",     List.of("star fall"));
        ALIASES.put("counterspell", List.of("counter spell"));
        ALIASES.put("heartstop",    List.of("heart stop"));
        ALIASES.put("firebolt",     List.of("fire bolt"));
        ALIASES.put("fireball",     List.of("fire ball"));
        ALIASES.put("frostbite",    List.of("frost bite"));
        ALIASES.put("frostwave",    List.of("frost wave"));
        ALIASES.put("oakskin",      List.of("oak skin"));
        // Words the small Vosk lexicon can't pronounce — give the recognizer something it can.
        ALIASES.put("wololo",       List.of("wo lo lo", "convert", "convert spell"));
        ALIASES.put("sculk",        List.of("skulk", "scull"));
        ALIASES.put("nullflare",    List.of("null flare", "no flare"));
    }

    private static final AtomicReference<State> STATE = new AtomicReference<>(State.EMPTY);

    /** Loadout name → ordered list of spell ids to try in that order. Populated from the
     *  {@code loadouts} config list at index time. Lookup is exact-name only to keep
     *  semantics predictable. */
    private static final Map<String, List<ResourceLocation>> LOADOUTS = new LinkedHashMap<>();

    /** Soundex code (per-word, hyphen-joined) → spell ids that hash to that code. Computed once
     *  after the phrase index is built; powers {@link #phoneticLookup(String)} as the final
     *  fallback after exact/fuzzy/substring all miss. */
    private static volatile Map<String, List<ResourceLocation>> phraseSoundex = Map.of();

    private SpellIndex() {}

    /** First-time index at startup — verbose (per-namespace breakdown for addon sanity). */
    public static void initialize() {
        index(true);
    }

    /** Re-index after a config change (customPhrases edited). Terse: one summary line. */
    public static void reindex() {
        index(false);
    }

    private static void index(boolean verbose) {
        // Probe before doing real work: Iron's Spells is now an optional dependency, so absence
        // is normal and shouldn't surface as an ERROR. If the registry class isn't present, we
        // just sit on State.EMPTY and the rest of the mod idles cleanly.
        try {
            Class.forName(REGISTRY_CLASS);
        } catch (ClassNotFoundException missing) {
            if (verbose) VoiceSpells.LOGGER.info("Iron's Spells not detected — voice casting will idle until it (or an addon) is installed");
            STATE.set(State.EMPTY);
            return;
        }
        try {
            State built = buildIndex();
            STATE.set(built);
            int uniqueSpells = Set.copyOf(built.phraseToId.values()).size();
            VoiceSpells.LOGGER.info("Indexed {} spells ({} grammar phrases incl. aliases)",
                uniqueSpells, built.phraseToId.size());
            if (verbose) {
                // Per-namespace breakdown — addons show up as separate rows here, which doubles
                // as a sanity check that the user's Iron's Spells addon is contributing.
                Map<String, Integer> perNs = new LinkedHashMap<>();
                for (ResourceLocation id : Set.copyOf(built.phraseToId.values())) {
                    perNs.merge(id.getNamespace(), 1, Integer::sum);
                }
                perNs.forEach((ns, count) ->
                    VoiceSpells.LOGGER.info("  {} : {} spells", ns, count));
            }
        } catch (Throwable t) {
            VoiceSpells.LOGGER.error("Failed to index spell registry: {}", t.toString());
            STATE.set(State.EMPTY);
        }
    }

    public static List<String> getPhrases() {
        Map<String, ResourceLocation> all = STATE.get().phraseToId;
        // Optional restriction: only include phrases whose target spell id is in the
        // currently-owned set. Owned set comes from VoiceController's periodic scan; when
        // it's null/empty we either pre-filter is off OR no scan has run yet — fall back to
        // the full set so recognition never silently goes blank.
        java.util.Set<String> ownedFilter = null;
        if (com.niko.voicespells.VoiceSpellsConfig.cRestrictToOwned) {
            java.util.Set<String> owned = com.niko.voicespells.client.VoiceController.ownedSpellIds();
            if (owned != null && !owned.isEmpty()) ownedFilter = owned;
        }
        List<String> base;
        if (ownedFilter == null) {
            base = List.copyOf(all.keySet());
        } else {
            base = new java.util.ArrayList<>();
            for (Map.Entry<String, ResourceLocation> e : all.entrySet()) {
                if (ownedFilter.contains(e.getValue().toString())) base.add(e.getKey());
            }
        }
        boolean needsHF = com.niko.voicespells.VoiceSpellsConfig.cHandsFreeConfirm;
        boolean needsHotbar = com.niko.voicespells.VoiceSpellsConfig.cVoiceHotbarSelect;
        if (!needsHF && !needsHotbar) return base;
        List<String> out = new java.util.ArrayList<>(base.size() + 16);
        out.addAll(base);
        if (needsHF) {
            if (!out.contains("yes")) out.add("yes");
            if (!out.contains("no"))  out.add("no");
        }
        if (needsHotbar) {
            String[] ordinals = { "one","two","three","four","five","six","seven","eight","nine" };
            for (String o : ordinals) {
                String p1 = "spell " + o;
                if (!out.contains(p1)) out.add(p1);
            }
        }
        return out;
    }

    /** If the phrase is a "spell N" hotbar-select command and hotbar select is enabled,
     *  returns the 1-based slot index (1..9). Returns -1 otherwise. */
    public static int matchHotbarSlot(String phrase) {
        if (!com.niko.voicespells.VoiceSpellsConfig.cVoiceHotbarSelect) return -1;
        if (phrase == null) return -1;
        String norm = normalize(phrase);
        if (!norm.startsWith("spell ")) return -1;
        String tail = norm.substring("spell ".length());
        return switch (tail) {
            case "one"   -> 1;
            case "two"   -> 2;
            case "three" -> 3;
            case "four"  -> 4;
            case "five"  -> 5;
            case "six"   -> 6;
            case "seven" -> 7;
            case "eight" -> 8;
            case "nine"  -> 9;
            default      -> -1;
        };
    }

    /** Lookup result carrying which matching tier produced the hit, so the debug monitor
     *  can render a per-row "why it matched" hint:
     *  <ul>
     *    <li>{@code E} - exact phrase</li>
     *    <li>{@code F} - fuzzy (Levenshtein)</li>
     *    <li>{@code S} - substring fallback</li>
     *    <li>{@code P} - phonetic (Soundex)</li>
     *    <li>{@code L} - loadout pick (set by VoiceController, not by this class)</li>
     *  </ul>
     */
    public record LookupResult(ResourceLocation id, char tier) {}

    /** One row per indexed spell id (deduped across alias phrases), sorted by id, each paired
     *  with the spoken phrases that map to it. Backs the in-game spell browser. */
    public static List<SpellRow> allSpells() {
        Map<String, java.util.TreeSet<String>> byId = new java.util.TreeMap<>();
        for (Map.Entry<String, ResourceLocation> e : STATE.get().phraseToId.entrySet()) {
            byId.computeIfAbsent(e.getValue().toString(), k -> new java.util.TreeSet<>())
                .add(e.getKey());
        }
        java.util.ArrayList<SpellRow> rows = new java.util.ArrayList<>(byId.size());
        byId.forEach((id, phrases) -> rows.add(new SpellRow(id, String.join(", ", phrases))));
        return rows;
    }

    public record SpellRow(String id, String phrases) {}

    public static Optional<ResourceLocation> lookup(String phrase) {
        return lookupWithTier(phrase).map(LookupResult::id);
    }

    /** Same as {@link #lookup(String)} but reports the matching tier so callers can
     *  surface "why it matched" diagnostics. */
    public static Optional<LookupResult> lookupWithTier(String phrase) {
        if (phrase == null) return Optional.empty();
        String norm = normalize(phrase);
        Map<String, ResourceLocation> phrases = STATE.get().phraseToId;
        ResourceLocation id = phrases.get(norm);
        if (id != null) return Optional.of(new LookupResult(id, 'E'));
        // Fuzzy fallback — edit-distance tolerance is configurable (0 disables it). Ambiguous
        // near-misses (multiple different spells tied at the minimum distance) still reject.
        int fuzzyMax = com.niko.voicespells.VoiceSpellsConfig.cFuzzyMaxDistance;
        if (fuzzyMax > 0) {
            Optional<ResourceLocation> fuzzy = fuzzyLookup(norm, phrases, fuzzyMax);
            if (fuzzy.isPresent()) return Optional.of(new LookupResult(fuzzy.get(), 'F'));
        }
        // Substring fallback (configurable) — Vosk's grammar mode chains words, so it often
        // emits "[unk] sunbeam" or "ender chest sunbeam" when the user said one spell. Take
        // the longest known phrase that appears on word boundaries inside the heard text.
        if (com.niko.voicespells.VoiceSpellsConfig.cSubstringMatch) {
            Optional<ResourceLocation> sub = substringLookup(norm, phrases);
            if (sub.isPresent()) return Optional.of(new LookupResult(sub.get(), 'S'));
        }
        // Phonetic last-resort: per-word Soundex collapse. Useful when Vosk produces a sound-
        // alike misspelling that survives fuzzy + substring (e.g. "fire boltz" → "fire bolt").
        // Only accepts unambiguous codes — multiple spells sharing a Soundex code reject so we
        // never cast the wrong one.
        return phoneticLookup(norm).map(rid -> new LookupResult(rid, 'P'));
    }

    /** Phonetic lookup: hash the heard phrase to a per-word Soundex code and return the spell
     *  if exactly one spell shares that code. Anything ambiguous or unmatched returns empty. */
    private static Optional<ResourceLocation> phoneticLookup(String phrase) {
        if (phrase == null || phrase.length() < 4) return Optional.empty(); // too short to safely match
        String code = soundexPhrase(phrase);
        if (code.isEmpty()) return Optional.empty();
        List<ResourceLocation> candidates = phraseSoundex.get(code);
        if (candidates == null || candidates.isEmpty()) return Optional.empty();
        java.util.Set<ResourceLocation> distinct = new java.util.HashSet<>(candidates);
        if (distinct.size() != 1) return Optional.empty(); // phonetic collision
        ResourceLocation hit = candidates.iterator().next();
        VoiceSpells.LOGGER.debug("Phonetic match \"{}\" ({}) -> {}", phrase, code, hit);
        return Optional.of(hit);
    }

    /** Standard 4-char Soundex on a single word. Returns "" for empty / non-letter input. */
    private static String soundex(String word) {
        if (word == null) return "";
        String upper = word.toUpperCase(java.util.Locale.ROOT).replaceAll("[^A-Z]", "");
        if (upper.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(4);
        sb.append(upper.charAt(0));
        char lastCode = soundexCode(upper.charAt(0));
        for (int i = 1; i < upper.length() && sb.length() < 4; i++) {
            char c = soundexCode(upper.charAt(i));
            if (c == '0') continue;        // skip vowels, h, w, y
            if (c == lastCode) continue;   // collapse repeats of same phonetic class
            sb.append(c);
            lastCode = c;
        }
        while (sb.length() < 4) sb.append('0');
        return sb.toString();
    }

    private static char soundexCode(char c) {
        return switch (c) {
            case 'B', 'F', 'P', 'V' -> '1';
            case 'C', 'G', 'J', 'K', 'Q', 'S', 'X', 'Z' -> '2';
            case 'D', 'T' -> '3';
            case 'L' -> '4';
            case 'M', 'N' -> '5';
            case 'R' -> '6';
            default -> '0';
        };
    }

    /** Multi-word Soundex: per-word codes joined with '-' so "fire ball" → "F600-B400".
     *  "fire boltz" and "fire bolt" both → "F600-B433" (B-L-T phonetic class), enabling
     *  matches that edit-distance might miss. */
    private static String soundexPhrase(String phrase) {
        if (phrase == null || phrase.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String word : phrase.split("\\s+")) {
            if (word.isEmpty()) continue;
            if (sb.length() > 0) sb.append('-');
            sb.append(soundex(word));
        }
        return sb.toString();
    }

    /**
     * Find the longest grammar phrase that occurs as a whitespace-bounded substring of
     * {@code input}. "Longest wins" protects against incidental short matches (e.g. picking
     * "fire" when "fire ball" is also present).
     */
    private static Optional<ResourceLocation> substringLookup(String input,
            Map<String, ResourceLocation> phrases) {
        String padded = " " + input + " ";
        ResourceLocation best = null;
        int bestLen = 0;
        for (Map.Entry<String, ResourceLocation> e : phrases.entrySet()) {
            String phrase = e.getKey();
            if (phrase.length() <= bestLen) continue; // can only be better if longer
            if (padded.contains(" " + phrase + " ")) {
                best = e.getValue();
                bestLen = phrase.length();
            }
        }
        if (best != null) {
            VoiceSpells.LOGGER.debug("Substring match in \"{}\" -> {}", input, best);
            return Optional.of(best);
        }
        return Optional.empty();
    }

    /**
     * Single nearest-neighbour search by edit distance, capped at {@code maxDistance}. Returns
     * empty unless exactly one phrase achieves the minimum distance — ambiguous matches are
     * rejected so we never autocorrect into the wrong spell.
     */
    private static Optional<ResourceLocation> fuzzyLookup(String input,
            Map<String, ResourceLocation> phrases, int maxDistance) {
        if (input.length() < 4) return Optional.empty(); // too short to safely autocorrect
        ResourceLocation best = null;
        int bestDist = maxDistance + 1;
        boolean tied = false;
        for (Map.Entry<String, ResourceLocation> e : phrases.entrySet()) {
            int d = boundedLevenshtein(input, e.getKey(), maxDistance);
            if (d < bestDist) {
                bestDist = d;
                best = e.getValue();
                tied = false;
            } else if (d == bestDist) {
                // Same distance from a different spell -> ambiguous, but only if it's a different
                // target id (multiple aliases of the same spell are fine).
                if (best != null && !best.equals(e.getValue())) tied = true;
            }
        }
        if (best != null && bestDist <= maxDistance && !tied) {
            VoiceSpells.LOGGER.debug("Autocorrected \"{}\" -> {} (distance {})", input, best, bestDist);
            return Optional.of(best);
        }
        return Optional.empty();
    }

    /**
     * Standard two-row Levenshtein with early termination when the entire current row exceeds
     * {@code max}. Length-difference shortcut keeps the cost down for the common case.
     */
    private static int boundedLevenshtein(String a, String b, int max) {
        int la = a.length();
        int lb = b.length();
        if (Math.abs(la - lb) > max) return max + 1;

        int[] prev = new int[lb + 1];
        int[] curr = new int[lb + 1];
        for (int j = 0; j <= lb; j++) prev[j] = j;
        for (int i = 1; i <= la; i++) {
            curr[0] = i;
            int rowMin = curr[0];
            char ac = a.charAt(i - 1);
            for (int j = 1; j <= lb; j++) {
                int cost = ac == b.charAt(j - 1) ? 0 : 1;
                int v = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
                curr[j] = v;
                if (v < rowMin) rowMin = v;
            }
            if (rowMin > max) return max + 1;
            int[] tmp = prev; prev = curr; curr = tmp;
        }
        return prev[lb];
    }

    public static boolean isReady() {
        return !STATE.get().phraseToId.isEmpty();
    }

    private static State buildIndex() throws Exception {
        Class<?> registryClass = Class.forName(REGISTRY_CLASS);
        Class<?> spellClass    = Class.forName(SPELL_CLASS);

        Field registryField = registryClass.getField("REGISTRY");
        Object registryObj  = registryField.get(null);
        if (!(registryObj instanceof Registry<?> registry)) {
            throw new IllegalStateException("SpellRegistry.REGISTRY is not a Registry instance");
        }

        Method getSpellId   = spellClass.getMethod("getSpellId");
        Method isEnabled    = spellClass.getMethod("isEnabled");

        Map<String, ResourceLocation> phraseToId = new LinkedHashMap<>();
        for (Object spell : registry) {
            if (!spellClass.isInstance(spell)) continue;
            try {
                boolean enabled = (boolean) isEnabled.invoke(spell);
                if (!enabled) continue;

                String idString = (String) getSpellId.invoke(spell);
                ResourceLocation id = ResourceLocation.parse(idString);
                String phrase = phraseFromPath(id.getPath());
                if (phrase.isEmpty()) continue;

                phraseToId.put(phrase, id);

                // Hardcoded aliases for the vanilla Iron's Spells compound names. (Auto-split
                // variant generation was removed — it polluted the grammar with non-words like
                // "abys sal" that competed during recognition and caused misfires. The bigger
                // model + custom phrases handle compounds far better.)
                List<String> extras = ALIASES.get(phrase);
                if (extras != null) {
                    for (String alias : extras) phraseToId.put(alias, id);
                }
            } catch (Throwable inner) {
                VoiceSpells.LOGGER.warn("Skipped spell during indexing: {}", inner.toString());
            }
        }

        // User-defined "phrase=namespace:spell_id" overrides. These win over generated phrases
        // (put, not putIfAbsent) so a user can re-point a phrase if they want. They're added to
        // the grammar like any other phrase, so Vosk will actively listen for them — the whole
        // point is to pick words the model can reliably hear for spells it otherwise can't.
        applyPhraseList(phraseToId,
            com.niko.voicespells.VoiceSpellsConfig.CLIENT.customPhrases.get(),
            "custom phrase");
        // Flavor incantations use the same mechanism but a separate config list so users can
        // keep theatrical phrases ("by the power of fire") tidy and separate from technical
        // pronunciation aliases.
        applyPhraseList(phraseToId,
            com.niko.voicespells.VoiceSpellsConfig.CLIENT.incantations.get(),
            "incantation");
        // Loadouts come after phrase overrides so loadout names land in the grammar; they're
        // resolved separately (LOADOUTS map) to pick the first castable spell at recognition time.
        applyLoadouts(phraseToId);
        applyBlocklist(phraseToId);

        // Build the Soundex bucket map for the phonetic fallback. Done last so it sees the
        // final phrase set (including custom phrases, incantations, loadout names) and skips
        // anything the blocklist has just removed.
        Map<String, List<ResourceLocation>> sx = new HashMap<>();
        for (Map.Entry<String, ResourceLocation> e : phraseToId.entrySet()) {
            String code = soundexPhrase(e.getKey());
            if (code.isEmpty()) continue;
            sx.computeIfAbsent(code, k -> new ArrayList<>()).add(e.getValue());
        }
        phraseSoundex = sx;

        return new State(phraseToId);
    }

    /**
     * Parse the {@code loadouts} config entries and:
     *  - populate the {@link #LOADOUTS} map for cooldown/mana-aware resolution
     *  - inject the loadout name into the grammar (pointing at the first spell as a fallback)
     *    so Vosk actively listens for the spoken category and {@link #lookupLoadout(String)} can
     *    later override the grammar's default with a smarter pick.
     */
    private static void applyLoadouts(Map<String, ResourceLocation> phraseToId) {
        LOADOUTS.clear();
        List<? extends String> entries;
        try {
            entries = com.niko.voicespells.VoiceSpellsConfig.CLIENT.loadouts.get();
        } catch (Throwable t) {
            return;
        }
        if (entries == null || entries.isEmpty()) return;
        int added = 0;
        for (String entry : entries) {
            if (entry == null) continue;
            int eq = entry.indexOf('=');
            if (eq <= 0 || eq >= entry.length() - 1) {
                VoiceSpells.LOGGER.warn("Bad loadouts entry (need name=spell_id,spell_id,...): {}", entry);
                continue;
            }
            String name = normalize(entry.substring(0, eq));
            if (name.isEmpty()) continue;
            List<ResourceLocation> ids = new ArrayList<>();
            for (String token : entry.substring(eq + 1).split(",")) {
                String trimmed = token.trim();
                if (trimmed.isEmpty()) continue;
                try {
                    ids.add(ResourceLocation.parse(trimmed));
                } catch (Throwable bad) {
                    VoiceSpells.LOGGER.warn("Bad spell id in loadouts entry: {}", trimmed);
                }
            }
            if (ids.isEmpty()) continue;
            LOADOUTS.put(name, List.copyOf(ids));
            // Fallback target so the phrase reaches the grammar even if lookupLoadout isn't
            // consulted (e.g. older callers).
            phraseToId.putIfAbsent(name, ids.get(0));
            added++;
        }
        if (added > 0) VoiceSpells.LOGGER.info("Loaded {} spell loadout(s)", added);
    }

    /** Returns the ordered spell-id list for the loadout matching the (normalized) phrase,
     *  or {@code null} if the phrase isn't a known loadout. */
    public static List<ResourceLocation> lookupLoadout(String phrase) {
        if (phrase == null) return null;
        return LOADOUTS.get(normalize(phrase));
    }

    /**
     * Strip every phrase that resolves to a blocked spell id. Done last so it also catches
     * custom-phrase mappings pointing at a blocked spell. Blocked spells never enter the
     * grammar, so Vosk won't even listen for them — the safest way to keep voice from
     * triggering an addon spell that crashes with the user's other mods.
     */
    private static void applyBlocklist(Map<String, ResourceLocation> phraseToId) {
        List<? extends String> entries;
        try {
            entries = com.niko.voicespells.VoiceSpellsConfig.CLIENT.blockedSpells.get();
        } catch (Throwable t) {
            return;
        }
        if (entries == null || entries.isEmpty()) return;
        java.util.Set<String> blocked = new java.util.HashSet<>();
        for (String e : entries) {
            if (e != null && !e.isBlank()) blocked.add(e.trim());
        }
        if (blocked.isEmpty()) return;
        int before = phraseToId.size();
        phraseToId.values().removeIf(id -> blocked.contains(id.toString()));
        int removed = before - phraseToId.size();
        if (removed > 0) {
            VoiceSpells.LOGGER.info("Blocklist removed {} phrase(s) for {} blocked spell(s)",
                removed, blocked.size());
        }
    }

    /**
     * Generic {@code "phrase=namespace:spell_id"} list parser. Powers both {@code customPhrases}
     * (technical pronunciation aliases) and {@code incantations} (flavor phrases). The two lists
     * have identical semantics, only the user-facing separation differs.
     */
    private static void applyPhraseList(Map<String, ResourceLocation> phraseToId,
                                         List<? extends String> entries,
                                         String labelForLogs) {
        if (entries == null || entries.isEmpty()) return;
        int added = 0;
        for (String entry : entries) {
            if (entry == null) continue;
            int eq = entry.indexOf('=');
            if (eq <= 0 || eq >= entry.length() - 1) {
                VoiceSpells.LOGGER.warn("Bad {} entry (need phrase=namespace:spell_id): {}",
                    labelForLogs, entry);
                continue;
            }
            String phrase = normalize(entry.substring(0, eq));
            String idStr  = entry.substring(eq + 1).trim();
            if (phrase.isEmpty()) continue;
            ResourceLocation id;
            try {
                id = ResourceLocation.parse(idStr);
            } catch (Throwable bad) {
                VoiceSpells.LOGGER.warn("Bad spell id in {} entry: {}", labelForLogs, entry);
                continue;
            }
            phraseToId.put(phrase, id);
            added++;
        }
        if (added > 0) {
            VoiceSpells.LOGGER.info("Added {} {} mapping(s)", added, labelForLogs);
        }
    }

    private static String phraseFromPath(String path) {
        // Spell paths are lowercase snake_case (e.g. "ray_of_siphoning").
        // Underscores → spaces gives a phonetic phrase suitable for Vosk grammar.
        return path.replace('_', ' ').trim();
    }

    /** Exact-only resolution: normalized phrase straight to the map, no fuzzy/substring. Used
     *  for partial (mid-utterance) results, which are too noisy to run the lenient fallbacks
     *  against without misfiring. */
    public static Optional<ResourceLocation> lookupExact(String phrase) {
        return lookupExactWithTier(phrase).map(LookupResult::id);
    }

    /**
     * "Best guess" lookup for the alias-suggestion path — runs the full matcher chain with
     * relaxed thresholds regardless of the user's config knobs. Exists so the "Did you mean
     * X?" prompt can fire even when {@code cFuzzyMaxDistance == 0} or substring matching is
     * off. Never used for actual casting; only for suggestion UX.
     */
    public static Optional<ResourceLocation> aggressiveLookup(String phrase) {
        if (phrase == null) return Optional.empty();
        String norm = normalize(phrase);
        Map<String, ResourceLocation> phrases = STATE.get().phraseToId;
        ResourceLocation id = phrases.get(norm);
        if (id != null) return Optional.of(id);
        Optional<ResourceLocation> fuzzy = fuzzyLookup(norm, phrases, 2);
        if (fuzzy.isPresent()) return fuzzy;
        Optional<ResourceLocation> sub = substringLookup(norm, phrases);
        if (sub.isPresent()) return sub;
        return phoneticLookup(norm);
    }

    /** Exact-only resolution with tier reporting (always {@code E} on success). */
    public static Optional<LookupResult> lookupExactWithTier(String phrase) {
        if (phrase == null) return Optional.empty();
        ResourceLocation id = STATE.get().phraseToId.get(normalize(phrase));
        return id == null ? Optional.empty() : Optional.of(new LookupResult(id, 'E'));
    }

    /** Substring-only resolution with tier reporting ({@code S} on success). Skips fuzzy and
     *  phonetic — they're too lenient for in-flight partials, where a half-spoken syllable
     *  could falsely resolve to a different spell. Caller is responsible for honouring the
     *  user's {@code substringMatch} toggle. */
    public static Optional<LookupResult> lookupSubstringWithTier(String phrase) {
        if (phrase == null) return Optional.empty();
        String norm = normalize(phrase);
        Map<String, ResourceLocation> phrases = STATE.get().phraseToId;
        return substringLookup(norm, phrases).map(rid -> new LookupResult(rid, 'S'));
    }

    /** Trailing-suffix resolution for partials. Matches only if a known spell phrase is the
     *  LAST whole-word run of the input — i.e. what the player just finished saying. Lets
     *  partials like {@code "[unk] fireball"} cast immediately without also matching stale
     *  earlier-utterance fragments. Returns {@code S} on success. */
    public static Optional<LookupResult> lookupTrailingWithTier(String phrase) {
        if (phrase == null) return Optional.empty();
        String norm = normalize(phrase);
        if (norm.isEmpty()) return Optional.empty();
        Map<String, ResourceLocation> phrases = STATE.get().phraseToId;
        // Direct hit on the whole text (covers single-word utterances).
        ResourceLocation direct = phrases.get(norm);
        if (direct != null) return Optional.of(new LookupResult(direct, 'S'));
        // Walk word-boundaries from left to right; each candidate is "input substring starting
        // at this boundary, through end-of-text". Take the LONGEST match so a "fire" prefix
        // doesn't beat the actual "fireball" trailing match.
        int n = norm.length();
        ResourceLocation best = null;
        int bestLen = 0;
        for (int i = 0; i < n; i++) {
            if (i > 0 && norm.charAt(i - 1) != ' ') continue;
            String tail = norm.substring(i);
            ResourceLocation rid = phrases.get(tail);
            if (rid != null && tail.length() > bestLen) {
                best = rid;
                bestLen = tail.length();
            }
        }
        return best == null ? Optional.empty() : Optional.of(new LookupResult(best, 'S'));
    }

    private static String normalize(String text) {
        return text.toLowerCase(java.util.Locale.ROOT).trim().replaceAll("\\s+", " ");
    }

    private record State(Map<String, ResourceLocation> phraseToId) {
        static final State EMPTY = new State(Collections.unmodifiableMap(new HashMap<>()));
    }
}
