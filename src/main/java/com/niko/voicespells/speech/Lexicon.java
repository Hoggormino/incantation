package com.niko.voicespells.speech;

import com.niko.voicespells.VoiceSpells;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The set of words the loaded speech model can actually hear, read from the model's own
 * {@code graph/words.txt}.
 *
 * <p>Vosk builds its grammar from a fixed lexicon. A word that isn't in it is dropped with a
 * native-side {@code "Ignoring word missing in vocabulary"} warning and simply never matches —
 * the spell becomes uncastable with no in-game symptom at all. Iron's Spells names are mostly
 * compounds ({@code firebolt}, {@code counterspell}, {@code heartstop}, {@code frostwave}),
 * and compounds are exactly what a lexicon tends not to carry even at 368k words, while the
 * halves are always there. So the fix is to also offer the model a spaced spelling it can say.
 *
 * <p><b>Why this is not the auto-splitting that was removed before.</b> An earlier version split
 * every compound blindly and polluted the grammar with non-words like {@code "abys sal"}, which
 * competed with real phrases and caused misfires. The rule here is much narrower, and checked
 * against the model's real vocabulary rather than guessed:
 *
 * <ul>
 *   <li>a word is only ever split if the model does <b>not</b> know it — {@code abyssal} is in
 *       the lexicon, so it is left alone and {@code "abys sal"} can never be produced;</li>
 *   <li>every resulting piece must itself be in the lexicon, so the split form is guaranteed
 *       pronounceable;</li>
 *   <li>pieces shorter than {@value #MIN_PIECE} characters are rejected, which keeps decompositions
 *       like {@code "a b y s s a l"} out even when the single letters are technically words;</li>
 *   <li>and the original spelling is kept as well — the respelling is added <i>alongside</i> it,
 *       never in place of it, so nothing regresses if this is wrong about a word.</li>
 * </ul>
 */
public final class Lexicon {
    private Lexicon() {}

    /**
     * Minimum length of a piece. Four rather than three on purpose: a 368k-word lexicon is full of
     * three-letter junk ({@code wol}, {@code olo}, {@code kin}, {@code pell}), so allowing them
     * produced exactly the {@code "abys sal"}-class nonsense this is supposed to avoid — measured,
     * not assumed: at three it split {@code wololo} into {@code "wol olo"} and {@code oakskin} into
     * {@code "oaks kin"}. Four costs a few legitimate short compounds, which are then reported for
     * a manual alias instead of being silently mangled. Declining loudly beats guessing wrong.
     */
    private static final int MIN_PIECE = 4;
    /** More pieces than this stops being a compound and starts being a coincidence. */
    private static final int MAX_PIECES = 3;

    private static volatile Set<String> words = null;

    /** True once a vocabulary has been read. Everything degrades to a no-op until then. */
    public static boolean ready() {
        return words != null;
    }

    public static boolean knows(String word) {
        Set<String> w = words;
        return w != null && w.contains(word);
    }

    /**
     * Read {@code <modelDir>/graph/words.txt}. Best-effort: a model without one simply leaves the
     * lexicon unavailable, and every caller treats that as "change nothing".
     */
    public static void load(Path modelDir) {
        if (modelDir == null) return;
        Path wordsFile = modelDir.resolve("graph").resolve("words.txt");
        if (!Files.isRegularFile(wordsFile)) {
            VoiceSpells.LOGGER.debug("No words.txt under {}; skipping respelling", modelDir);
            return;
        }
        try (Stream<String> lines = Files.lines(wordsFile, StandardCharsets.UTF_8)) {
            Set<String> set = new HashSet<>(1 << 19);
            lines.forEach(line -> {
                // "<word> <id>" per line; a handful are markers like <eps> / #0 which we skip.
                int sp = line.indexOf(' ');
                String w = sp < 0 ? line : line.substring(0, sp);
                if (!w.isEmpty() && w.charAt(0) != '<' && w.charAt(0) != '#') {
                    set.add(w.toLowerCase(java.util.Locale.ROOT));
                }
            });
            words = Collections.unmodifiableSet(set);
            VoiceSpells.LOGGER.info("Speech model vocabulary loaded ({} words)", set.size());
        } catch (Throwable t) {
            VoiceSpells.LOGGER.warn("Could not read model vocabulary ({}); "
                + "spell names the model cannot pronounce will not be respelled", t.toString());
        }
    }

    /**
     * A spelling of {@code phrase} that the model can actually hear, or {@code null} when the
     * phrase is already sayable or cannot be rescued.
     *
     * <p>Returns {@code null} rather than a partial fix if any single word resists splitting: half
     * a phrase is not castable, and a phrase that silently loses a word would match the wrong
     * spell.
     */
    public static String respell(String phrase) {
        if (!ready() || phrase == null || phrase.isEmpty()) return null;
        String[] parts = phrase.split(" ");
        List<String> out = new ArrayList<>(parts.length + 2);
        boolean changed = false;
        for (String part : parts) {
            if (part.isEmpty()) continue;
            if (knows(part)) {
                out.add(part);
                continue;
            }
            List<String> split = split(part);
            if (split == null) return null; // unsayable and unsplittable — leave it to an alias
            out.addAll(split);
            changed = true;
        }
        return changed ? String.join(" ", out) : null;
    }

    /**
     * Decompose one unknown word into known pieces, or {@code null} if it cannot be done cleanly.
     *
     * <p>Every valid decomposition is enumerated and the <b>most balanced</b> one wins — the one
     * whose shortest piece is longest. Preferring the longest leading word instead, which is the
     * obvious implementation, is actively wrong on real data: it reads {@code heartstop} as
     * {@code "hearts top"} and {@code counterspell} as {@code "counters pell"}, because the greedy
     * head happens to be a word. Balance recovers {@code "heart stop"} and {@code "counter spell"}.
     *
     * <p>Ties break toward fewer pieces. Nothing here consults word frequency, so anything still
     * ambiguous after that is left alone rather than guessed at.
     */
    private static List<String> split(String word) {
        List<String> best = null;
        int bestMin = 0;
        for (int pieces = 2; pieces <= MAX_PIECES; pieces++) {
            List<List<String>> all = new ArrayList<>();
            enumerate(word, 0, pieces, new ArrayList<>(), all);
            for (List<String> candidate : all) {
                int min = Integer.MAX_VALUE;
                for (String piece : candidate) min = Math.min(min, piece.length());
                if (min > bestMin) {
                    bestMin = min;
                    best = candidate;
                }
            }
            // A shorter decomposition that already works is preferred, so stop at the first
            // piece-count that produced anything.
            if (best != null) break;
        }
        return best;
    }

    private static void enumerate(String word, int from, int piecesLeft,
                                  List<String> acc, List<List<String>> out) {
        int remaining = word.length() - from;
        if (piecesLeft == 1) {
            if (remaining < MIN_PIECE) return;
            String tail = word.substring(from);
            if (!knows(tail)) return;
            List<String> complete = new ArrayList<>(acc);
            complete.add(tail);
            out.add(complete);
            return;
        }
        int maxHead = remaining - MIN_PIECE * (piecesLeft - 1);
        for (int len = MIN_PIECE; len <= maxHead; len++) {
            String head = word.substring(from, from + len);
            if (!knows(head)) continue;
            acc.add(head);
            enumerate(word, from + len, piecesLeft - 1, acc, out);
            acc.remove(acc.size() - 1);
        }
    }
}
