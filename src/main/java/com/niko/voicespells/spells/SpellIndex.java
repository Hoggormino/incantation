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
 *   - {@link #lookupWithTier(String)} for resolving a heard phrase to a spell's
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
        // sculk_tentacles' path-derived phrase is the TWO-word "sculk tentacles", and the lookup
        // is ALIASES.get(defaultPhrase) — so the bare "sculk" key above never fires for it. Key
        // the alias by the full phrase. ("tentacles" is in-vocab; only "sculk" is the OOV word.)
        ALIASES.put("sculk tentacles", List.of("skulk tentacles", "scull tentacles"));
        ALIASES.put("nullflare",    List.of("null flare", "no flare"));
    }

    private static final AtomicReference<State> STATE = new AtomicReference<>(State.EMPTY);

    /** Loadout name → ordered list of spell ids to try in that order. Populated from the
     *  {@code loadouts} config list at index time. Lookup is exact-name only to keep
     *  semantics predictable. */
    /**
     * Loadout categories, read from the CAPTURE thread and rebuilt on the main thread.
     *
     * <p>A LinkedHashMap that reindex clear()s and then repopulates is not safe to read
     * concurrently: a lookup landing mid-rebuild can see an empty map (a loadout phrase silently
     * doing nothing) or, on a resize, an inconsistent one. ConcurrentHashMap makes the reader's
     * view always coherent — worst case it sees the old or the new entry, never a broken table.
     * Insertion order is not relied on: lookups are by key.
     */
    private static final Map<String, List<ResourceLocation>> LOADOUTS = new java.util.concurrent.ConcurrentHashMap<>();

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

    /**
     * Read one of the client-only config lists, tolerating its absence.
     *
     * <p>{@code buildIndex} runs from {@code FMLCommonSetupEvent}, i.e. on both sides, but the
     * CLIENT config spec is only registered when {@code FMLEnvironment.dist == Dist.CLIENT}.
     * Calling {@code .get()} on a spec that was never registered throws
     * {@code IllegalStateException: Cannot get config value before config is loaded}, which used
     * to abort the whole index and leave every dedicated server with zero indexed spells plus an
     * ERROR on each boot. These four lists (custom phrases, incantations, loadouts, blocked
     * spells) are per-player personalisation with no server-side meaning, so an empty list is the
     * correct answer on a server rather than a failure.
     */
    private static List<? extends String> clientList(
            java.util.function.Function<com.niko.voicespells.VoiceSpellsConfig.Client,
                                        List<? extends String>> getter) {
        try {
            com.niko.voicespells.VoiceSpellsConfig.Client c =
                com.niko.voicespells.VoiceSpellsConfig.CLIENT;
            if (c == null) return List.of();
            List<? extends String> v = getter.apply(c);
            return v == null ? List.of() : v;
        } catch (IllegalStateException notLoaded) {
            // Dedicated server (spec never registered), or called before config load.
            return List.of();
        } catch (Throwable t) {
            VoiceSpells.LOGGER.debug("Client config list unavailable: {}", t.toString());
            return List.of();
        }
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
            // Say it out loud. A phrase claimed twice costs the player a spell, and until now it
            // did so in complete silence — the map simply overwrote the earlier entry.
            java.util.List<String> clashes = phraseCollisions();
            if (!clashes.isEmpty()) {
                VoiceSpells.LOGGER.warn("{} phrase(s) are claimed by more than one spell. The "
                    + "first spell listed keeps the phrase; the second CANNOT be voice-cast "
                    + "until you give it a different one in config/voicespells/phrasebook.json:",
                    clashes.size());
                for (String c : clashes) VoiceSpells.LOGGER.warn("  {}", c);
            }
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

    /**
     * Grammar for a player who currently has {@code ownedIds} castable, padded to a floor.
     *
     * <p>Narrowing the grammar to what the player can actually cast is what makes recognition
     * accurate — a handful of candidate phrases is far easier to discriminate between than a
     * hundred and fifty. But narrowing alone is a trap, and this codebase learned it the hard
     * way: Kaldi with a closed grammar does not reject, it picks the least-bad match. Get down to
     * one or two phrases and <i>any</i> sound in the room becomes a spell. Shipping that is what
     * forced the earlier retreat to a permanently broad grammar.
     *
     * <p>{@code [unk]} alone did not save it. That entry was already present throughout, and the
     * force-fitting happened anyway — it is necessary but nowhere near sufficient.
     *
     * <p>So the grammar is narrowed <b>and</b> floored: if the castable set is smaller than
     * {@code floor}, it is padded with phrases for spells the player does <i>not</i> have. Those
     * make excellent padding precisely because they are real spell names — in-lexicon, drawn from
     * the same phonetic distribution as the real targets, and therefore genuine acoustic
     * competitors rather than filler the recognizer can dismiss. And when one of them wins, the
     * outcome is already correct: the dispatch gate refuses to cast a spell the player does not
     * have, so a decoy match is silently a no-op.
     *
     * <p>Padding is deterministic (sorted, then taken in order) so the same equipment produces the
     * same grammar every time — a recognizer that reshuffles its own decoys between rebuilds would
     * be unreproducible to debug.
     *
     * @param ownedIds namespaced ids the player can currently cast, or empty to fall back to the
     *                 full grammar (an unreliable ownership scan must not shrink the grammar)
     * @param floor    minimum number of spell phrases to keep in the grammar
     */
    public static List<String> phrasesFor(Set<String> ownedIds, int floor) {
        Map<String, ResourceLocation> all = STATE.get().phraseToId;
        if (ownedIds == null || ownedIds.isEmpty()) return getPhrases();

        List<String> owned = new java.util.ArrayList<>();
        List<String> rest  = new java.util.ArrayList<>();
        for (Map.Entry<String, ResourceLocation> e : all.entrySet()) {
            if (ownedIds.contains(e.getValue().toString()) || loadoutIsOwned(e.getKey(), ownedIds)) {
                owned.add(e.getKey());
            } else {
                rest.add(e.getKey());
            }
        }
        if (owned.isEmpty()) return getPhrases(); // nothing matched — don't narrow to nothing

        java.util.Collections.sort(rest);
        List<String> out = new java.util.ArrayList<>(owned);
        for (String decoy : rest) {
            if (out.size() >= floor) break;
            out.add(decoy);
        }
        return withVoiceCommands(out);
    }

    public static List<String> getPhrases() {
        Map<String, ResourceLocation> all = STATE.get().phraseToId;
        List<String> base = List.copyOf(all.keySet());
        return withVoiceCommands(base);
    }

    /**
     * True when {@code phrase} names a loadout with at least one currently-equipped spell.
     *
     * <p>A loadout is registered in {@code phraseToId} against {@code ids.get(0)} only, as a
     * fallback target. So the narrowed grammar judged the whole loadout by whether its FIRST
     * entry happened to be equipped: if it was not, the loadout's name was demoted to a decoy
     * and dropped as soon as the grammar floor filled — the recognizer then could not hear the
     * word at all, and a loadout whose other spells were perfectly castable became unusable.
     */
    private static boolean loadoutIsOwned(String phrase, java.util.Set<String> ownedIds) {
        List<ResourceLocation> ids = LOADOUTS.get(phrase);
        if (ids == null) return false;
        for (ResourceLocation rid : ids) {
            if (ownedIds.contains(rid.toString())) return true;
        }
        return false;
    }

    /** Append the non-spell control words the recognizer also has to hear. */
    private static List<String> withVoiceCommands(List<String> base) {
        boolean needsHF = com.niko.voicespells.VoiceSpellsConfig.cHandsFreeConfirm;
        boolean needsHotbar = com.niko.voicespells.VoiceSpellsConfig.cVoiceHotbarSelect;
        java.util.Set<String> triggers = com.niko.voicespells.VoiceSpellsConfig.cTriggerWords;
        if (!needsHF && !needsHotbar && triggers.isEmpty()) return base;
        List<String> out = new java.util.ArrayList<>(base.size() + 16 + triggers.size());
        out.addAll(base);
        // Trigger words have to be in the grammar or the feature cannot work at all.
        //
        // The recognizer runs in grammar mode: it can only emit tokens the grammar contains,
        // and it decodes an utterance as a sequence of grammar entries. "cast" was never added
        // here, so saying "cast fireball" could never produce a transcript containing the word
        // "cast" — which is precisely what the trigger gate in VoiceController then tested for.
        // The gate therefore rejected every phrase, and configuring a trigger word silently
        // disabled voice casting entirely, with no log line and no HUD miss. Adding them as
        // standalone entries is enough: the decoder is free to emit "<trigger> <spell>" as two
        // consecutive entries, exactly as it already does for "yes"/"no".
        for (String tw : triggers) {
            if (!tw.isEmpty() && !out.contains(tw)) out.add(tw);
        }
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

    /** Full resolution chain (exact → fuzzy → substring → phonetic), reporting the matching
     *  tier so callers can surface "why it matched" diagnostics. */
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
     * Find the spell phrase that occurs EARLIEST as a whitespace-bounded substring of
     * {@code input}. Ties on start position resolve to the longer phrase, so "fire ball" still
     * beats "fire" when both anchor at position 0.
     *
     * Earliest-wins matters because Vosk's grammar mode keeps producing tokens until the
     * utterance ends — if the user says "sunbeam" and Vosk grammar-forces the tail audio
     * into "thunderstorm", the heard text becomes "sunbeam ... thunderstorm". A longest-wins
     * policy would pick the hallucinated "thunderstorm"; earliest-wins picks the real
     * "sunbeam" the user actually said.
     */
    private static Optional<ResourceLocation> substringLookup(String input,
            Map<String, ResourceLocation> phrases) {
        String padded = " " + input + " ";
        ResourceLocation best = null;
        int bestStart = Integer.MAX_VALUE;
        int bestLen = 0;
        for (Map.Entry<String, ResourceLocation> e : phrases.entrySet()) {
            String phrase = e.getKey();
            int start = padded.indexOf(" " + phrase + " ");
            if (start < 0) continue;
            // Strictly earlier wins; same start → longer phrase wins (keeps "fire ball" >
            // "fire" disambiguation when both anchor at the same position).
            if (start < bestStart || (start == bestStart && phrase.length() > bestLen)) {
                best = e.getValue();
                bestStart = start;
                bestLen = phrase.length();
            }
        }
        if (best != null) {
            VoiceSpells.LOGGER.debug("Substring match in \"{}\" -> {} (at offset {})",
                input, best, bestStart);
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

        // SpellRegistry.REGISTRY is not the same type across Minecraft versions:
        //   1.21.x NeoForge : net.minecraft.core.Registry<AbstractSpell>
        //   1.20.1 Forge    : Supplier<net.minecraftforge.registries.IForgeRegistry<AbstractSpell>>
        // Unwrap the supplier when present. Both end types implement Iterable<AbstractSpell>, and
        // iteration is all this method needs, so accepting Iterable covers both with one code path
        // instead of a version conditional.
        //
        // This mattered: requiring a vanilla Registry meant the mod loaded cleanly on 1.20.1,
        // reported Iron's Spells as found, passed its own API self-check, and then indexed ZERO
        // spells - a total functional failure that nothing short of running it with Iron's Spells
        // actually installed could reveal.
        if (registryObj instanceof java.util.function.Supplier<?> supplier) {
            registryObj = supplier.get();
        }
        if (!(registryObj instanceof Iterable<?> registry)) {
            throw new IllegalStateException(
                "SpellRegistry.REGISTRY is not iterable (got "
                + (registryObj == null ? "null" : registryObj.getClass().getName()) + ")");
        }

        Method getSpellId   = spellClass.getMethod("getSpellId");
        Method isEnabled    = spellClass.getMethod("isEnabled");

        // Make sure the phrasebook is loaded BEFORE we derive phrases, so per-spell overrides
        // take effect on every indexed entry from the very first pass.
        Phrasebook.load();

        Map<String, ResourceLocation> phraseToId = new LinkedHashMap<>();
        phraseCollisions.clear();   // this build's clashes only; the index is rebuilt wholesale
        // Capture the English-default phrase for every spell as we go, so we can rewrite
        // phrasebook.json with the current installed-spell set at the end of indexing.
        Map<String, String> defaultsForPhrasebook = new LinkedHashMap<>();
        for (Object spell : registry) {
            if (!spellClass.isInstance(spell)) continue;
            try {
                boolean enabled = (boolean) isEnabled.invoke(spell);
                if (!enabled) continue;

                String idString = (String) getSpellId.invoke(spell);
//? if forge {
/*                // tryParse returns null for a malformed id where 1.21's parse() threw; a spell
                // with an unparseable id is simply skipped rather than aborting the index.
                ResourceLocation id = ResourceLocation.tryParse(idString);
                if (id == null) continue;
*///?} else {
                ResourceLocation id = ResourceLocation.parse(idString);
//?}
                String defaultPhrase = phraseFromPath(id.getPath());
                if (defaultPhrase.isEmpty()) continue;
                defaultsForPhrasebook.put(idString, defaultPhrase);

                // User override (from phrasebook.json) wins over the underscore-derived default
                // — this is the bulk-translation workflow. If no override is set, we use the
                // English default exactly as before.
                String override = Phrasebook.overrideFor(idString);
                String phrase = (override != null) ? normalize(override) : defaultPhrase;
                if (phrase.isEmpty()) continue;

                // First writer wins, and a clash is REPORTED rather than swallowed.
                //
                // This was a plain put(), so two spells resolving to the same phrase meant the
                // second silently replaced the first and that spell became uncastable with
                // nothing logged anywhere. It is not hypothetical: a translated phrasebook
                // reaches this easily, because two mods' spells often mean the same thing in
                // another language — a real Spanish phrasebook mapped both
                // gtbcs_geomancy_plus:solar_beam and irons_spellbooks:sunbeam to "rayo solar",
                // which quietly cost the player one of them. Whichever spell was indexed first
                // keeps the phrase, and the collision is recorded so the player can be told
                // exactly which two ids to disambiguate.
                ResourceLocation clash = phraseToId.putIfAbsent(phrase, id);
                if (clash != null && !clash.equals(id)) {
                    phraseCollisions.add(phrase + "  ->  " + clash + "  /  " + idString);
                }

                // Hardcoded aliases for the vanilla Iron's Spells compound names. (Auto-split
                // variant generation was removed — it polluted the grammar with non-words like
                // "abys sal" that competed during recognition and caused misfires. The bigger
                // model + custom phrases handle compounds far better.)
                //
                // Aliases only apply when the user hasn't replaced the default with their own
                // override; otherwise we'd quietly pollute their grammar with English aliases for
                // a spell they've already translated.
                if (override == null) {
                    List<String> extras = ALIASES.get(defaultPhrase);
                    if (extras != null) {
                        for (String alias : extras) {
                            // Same guard as the primary phrase: an alias that collides used to
                            // overwrite whatever held the phrase, silently. Tagged so the player
                            // knows the fix is to drop the colliding override, not rename a spell.
                            ResourceLocation prevAlias = phraseToId.putIfAbsent(alias, id);
                            if (prevAlias != null && !prevAlias.equals(id)) {
                                phraseCollisions.add(alias + "  ->  " + prevAlias + "  /  "
                                    + idString + "  (alias)");
                            }
                        }
                    }
                }
            } catch (Throwable inner) {
                VoiceSpells.LOGGER.warn("Skipped spell during indexing: {}", inner.toString());
            }
        }

        // Refresh phrasebook.json with the current installed-spell set. Preserves existing
        // overrides, appends entries for any newly-discovered spell, and keeps stale entries so
        // a temporarily-uninstalled addon doesn't wipe the user's translation work.
        Phrasebook.rewrite(defaultsForPhrasebook);

        // User-defined "phrase=namespace:spell_id" overrides. These win over generated phrases
        // (put, not putIfAbsent) so a user can re-point a phrase if they want. They're added to
        // the grammar like any other phrase, so Vosk will actively listen for them — the whole
        // point is to pick words the model can reliably hear for spells it otherwise can't.
        applyPhraseList(phraseToId, clientList(c -> c.customPhrases.get()), "custom phrase");
        // Flavor incantations use the same mechanism but a separate config list so users can
        // keep theatrical phrases ("by the power of fire") tidy and separate from technical
        // pronunciation aliases.
        applyPhraseList(phraseToId, clientList(c -> c.incantations.get()), "incantation");
        // Loadouts come after phrase overrides so loadout names land in the grammar; they're
        // resolved separately (LOADOUTS map) to pick the first castable spell at recognition time.
        // Blocklist FIRST. It used to run after loadouts, and it only removes phrases whose
        // target is blocked — so a blocked spell still sat inside a loadout's id list and the
        // runtime picker happily chose it. "Blocked" has to mean blocked everywhere, or the
        // setting is a trap: the player believes a spell can no longer fire and it still does.
        applyBlocklist(phraseToId);
        applyLoadouts(phraseToId);

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
//? if forge {
/*                // tryParse returns null rather than throwing, so a bad id has to be caught by
                // the null check, not only by the catch below.
                ResourceLocation parsed = ResourceLocation.tryParse(trimmed);
                if (parsed == null) {
*///?} else {
                try {
                    ids.add(ResourceLocation.parse(trimmed));
                } catch (Throwable bad) {
//?}
                    VoiceSpells.LOGGER.warn("Bad spell id in loadouts entry: {}", trimmed);
//? if forge {
/*                    continue;
*///?}
                }
//? if forge {
/*                ids.add(parsed);
*///?}
            }
            // Drop blocked spells from the category, and drop the category if nothing survives.
            // Without this a blocked spell stayed reachable by saying its loadout name.
            if (!blockedIds.isEmpty()) {
                int before = ids.size();
                ids.removeIf(candidate -> blockedIds.contains(candidate.toString()));
                if (ids.size() != before) {
                    VoiceSpells.LOGGER.info("Loadout \"{}\": dropped {} blocked spell(s)",
                        name, before - ids.size());
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
        List<ResourceLocation> ids = LOADOUTS.get(normalize(phrase));
        if (ids == null || blockedIds.isEmpty()) return ids;
        // Second gate at the runtime path: the config can change between a reindex and a cast,
        // and "blocked" is a promise that should not depend on which order two lists were parsed.
        List<ResourceLocation> allowed = new ArrayList<>(ids.size());
        for (ResourceLocation id : ids) {
            if (!blockedIds.contains(id.toString())) allowed.add(id);
        }
        return allowed.isEmpty() ? null : allowed;
    }

    /**
     * Strip every phrase that resolves to a blocked spell id. Done last so it also catches
     * custom-phrase mappings pointing at a blocked spell. Blocked spells never enter the
     * grammar, so Vosk won't even listen for them — the safest way to keep voice from
     * triggering an addon spell that crashes with the user's other mods.
     */
    /** Ids the player has blocked, as of the last {@link #applyBlocklist} call. Read by
     *  {@link #applyLoadouts} so a blocked spell cannot be reached through a category either. */
    private static volatile java.util.Set<String> blockedIds = java.util.Set.of();

    private static void applyBlocklist(Map<String, ResourceLocation> phraseToId) {
        blockedIds = java.util.Set.of();
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
        blockedIds = java.util.Set.copyOf(blocked);
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
//? if forge {
/*                id = ResourceLocation.tryParse(idStr);
*///?} else {
                id = ResourceLocation.parse(idStr);
//?}
            } catch (Throwable bad) {
//? if forge {
/*                id = null;
            }
            if (id == null) {
                // tryParse signals failure with null instead of an exception.
*///?}
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

    /**
     * Add a sayable spelling for every indexed phrase the speech model cannot pronounce.
     *
     * <p>Called once the model directory is known, because it needs the model's own vocabulary to
     * decide what "cannot pronounce" means. Purely additive: the original phrase stays mapped, and
     * the respelling is only inserted when nothing already claims it, so custom phrases and
     * phrasebook overrides always win.
     *
     * <p>Without this, a spell whose name is a compound the lexicon lacks — {@code firebolt},
     * {@code counterspell}, {@code heartstop} — is dropped from the grammar by Vosk with only a
     * native stderr warning, and is simply uncastable with no symptom the player can see.
     *
     * @return how many respellings were added
     */
    public static int registerRespellings() {
        if (!com.niko.voicespells.speech.Lexicon.ready()) return 0;
        State current = STATE.get();
        Map<String, ResourceLocation> existing = current.phraseToId();
        if (existing.isEmpty()) return 0;

        Map<String, ResourceLocation> merged = new HashMap<>(existing);
        List<String> examples = new ArrayList<>();
        List<String> unfixable = new ArrayList<>();
        int added = 0;
        for (Map.Entry<String, ResourceLocation> e : existing.entrySet()) {
            String phrase = e.getKey();
            String respelled = com.niko.voicespells.speech.Lexicon.respell(phrase);
            if (respelled == null) {
                // Only worth reporting when the model genuinely can't say it as written.
                if (!sayable(phrase) && unfixable.size() < 10) unfixable.add(phrase);
                continue;
            }
            if (merged.putIfAbsent(respelled, e.getValue()) == null) {
                added++;
                if (examples.size() < 6) examples.add(phrase + " -> " + respelled);
            }
        }
        if (added > 0) {
            STATE.set(new State(Collections.unmodifiableMap(merged)));
            VoiceSpells.LOGGER.info("Added {} spoken spelling(s) for spell names the model cannot "
                + "pronounce: {}", added, String.join(", ", examples));
        }
        if (!unfixable.isEmpty()) {
            VoiceSpells.LOGGER.warn("These spell names are not in the speech model's vocabulary "
                + "and could not be respelled automatically: {}. Bind an alias for them in "
                + "config/voicespells-client.toml or phrasebook.json.", String.join(", ", unfixable));
        }
        return added;
    }

    /** Whether every word of a phrase is already in the model's vocabulary. */
    private static boolean sayable(String phrase) {
        for (String w : phrase.split(" ")) {
            if (!w.isEmpty() && !com.niko.voicespells.speech.Lexicon.knows(w)) return false;
        }
        return true;
    }

    private static String phraseFromPath(String path) {
        // Spell paths are lowercase snake_case (e.g. "ray_of_siphoning").
        // Underscores → spaces gives a phonetic phrase suitable for Vosk grammar.
        return path.replace('_', ' ').trim();
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

    /**
     * Phrases claimed by more than one spell in the last index build.
     *
     * <p>Surfaced by diagnostics and logged once per rebuild. Kept as formatted strings because
     * the only consumer is a human deciding which of the two to rename.
     */
    private static final java.util.List<String> phraseCollisions =
        java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    /** Collisions from the most recent index build; empty when every phrase is unique. */
    public static java.util.List<String> phraseCollisions() {
        synchronized (phraseCollisions) { return java.util.List.copyOf(phraseCollisions); }
    }

    private record State(Map<String, ResourceLocation> phraseToId) {
        static final State EMPTY = new State(Collections.unmodifiableMap(new HashMap<>()));
    }
}
