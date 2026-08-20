package com.niko.voicespells.speech;

import com.niko.voicespells.VoiceSpells;

import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Reads the word list a Vosk model can actually hear, out of the model's own {@code graph/Gr.fst}.
 *
 * <p><b>Why this exists.</b> {@link Lexicon} read {@code graph/words.txt}, and that file is not in
 * the model the mod downloads by default - {@code vosk-model-small-en-us-0.15} ships
 * {@code Gr.fst}, {@code HCLr.fst}, {@code disambig_tid.int} and {@code phones}, and nothing else.
 * Of the six models in the catalogue only {@code en-us-0.22-lgraph} carries a {@code words.txt}.
 * So {@code Lexicon.ready()} has been false for essentially every player since it shipped, and the
 * respelling rescue it exists to drive has never run for anybody.
 *
 * <p>That matters more than it sounds. Vosk cannot emit a word that is not in its lexicon, so a
 * spell phrase containing one can never match - the spell is uncastable with no in-game symptom,
 * no chat message, and nothing above DEBUG in the log. On the shipped English model, ten of the
 * indexed phrases contain such a word.
 *
 * <p><b>The format.</b> An OpenFST binary begins with a header, and when its flags say so, an
 * input symbol table follows immediately. Every integer is little-endian; every string is a 32-bit
 * length followed by that many UTF-8 bytes:
 *
 * <pre>
 *   int32   0x7eb2fdd6            FST magic
 *   string  "ngram"               fst type
 *   string  "standard"            arc type
 *   int32   version
 *   int32   flags                 bit 0 set means an input symbol table follows
 *   int64   properties, start, nstates, narcs
 *   int32   0x7eb2fb74            symbol-table magic
 *   string  name                  e.g. "exp/chain_a/tdnn/lgraph/words.txt"
 *   int64   available_key
 *   int64   nsymbols
 *   nsymbols repeats of ( string symbol , int64 key )
 * </pre>
 *
 * <p>Measured on the shipped English model: 152,217 symbols, the input table complete at byte
 * 2,983,178 of a 24 MB file. The read stops there - the arcs after it are the bulk of the file and
 * are of no interest.
 *
 * <p><b>Fails open, always.</b> Every failure path leaves the vocabulary unavailable, which every
 * caller already treats as "change nothing". A wrong guess here must never remove a phrase that
 * works today: that is the failure this class was written to fix, not to introduce.
 */
public final class Vocabulary {
    private Vocabulary() {}

    private static final int FST_MAGIC = 0x7eb2fdd6;
    private static final int SYM_MAGIC = 0x7eb2fb74;

    /**
     * Bounds on a believable symbol table, used to reject a file that parsed by coincidence.
     *
     * <p>The smallest catalogued model carries about 152k symbols and the large English one carries
     * far more, so anything under a few thousand means the parse has wandered into arc data and is
     * reading garbage as lengths. The ceiling stops a bogus count from driving a huge allocation.
     */
    private static final long MIN_SYMBOLS = 5_000L;
    private static final long MAX_SYMBOLS = 5_000_000L;

    /** Longest believable symbol. Guards against a garbage length being used as an array size. */
    private static final int MAX_SYMBOL_BYTES = 512;

    /**
     * Read the input symbol table, or {@code null} if this is not a file we understand.
     *
     * <p>Streamed rather than mapped: the table is a few megabytes at the head of a file that can
     * be hundreds, so reading only as far as needed keeps the cost proportional to the vocabulary
     * rather than to the model.
     */
    public static Set<String> read(Path grFst) {
        if (grFst == null || !Files.isRegularFile(grFst)) return null;
        try (InputStream raw = Files.newInputStream(grFst);
             BufferedInputStream in = new BufferedInputStream(raw, 1 << 16)) {

            if (readInt(in) != FST_MAGIC) return reject(grFst, "not an OpenFST file");
            readString(in);                    // fst type, e.g. "ngram"
            readString(in);                    // arc type, e.g. "standard"
            readInt(in);                       // version
            int flags = readInt(in);
            readLong(in); readLong(in); readLong(in); readLong(in);  // properties, start, nstates, narcs

            if ((flags & 1) == 0) return reject(grFst, "no input symbol table");
            if (readInt(in) != SYM_MAGIC) return reject(grFst, "symbol table magic mismatch");
            readString(in);                    // table name
            readLong(in);                      // available key
            long n = readLong(in);
            if (n < MIN_SYMBOLS || n > MAX_SYMBOLS) {
                return reject(grFst, "implausible symbol count " + n);
            }

            Set<String> words = new HashSet<>(Math.max(16, (int) (n * 4 / 3)));
            boolean sawEps = false;
            boolean sawUnk = false;
            for (long i = 0; i < n; i++) {
                String sym = readString(in);
                readLong(in);                  // key - the id is of no use here
                if (sym.isEmpty()) continue;
                char c = sym.charAt(0);
                if (c == '<' || c == '#' || c == '[' || c == '!') {
                    // Markers, not words: <eps>, [unk], #0, !SIL. Kept out of the word set, but
                    // their presence is what proves a real table was parsed rather than noise.
                    if ("<eps>".equals(sym)) {
                        sawEps = true;
                    } else if ("[unk]".equals(sym) || "<unk>".equals(sym)) {
                        sawUnk = true;
                    }
                    continue;
                }
                words.add(sym.toLowerCase(Locale.ROOT));
            }
            if (!sawEps || !sawUnk) {
                return reject(grFst, "no <eps>/[unk] markers - the parse is not trustworthy");
            }
            return words;
        } catch (Throwable t) {
            return reject(grFst, t.toString());
        }
    }

    private static Set<String> reject(Path p, String why) {
        VoiceSpells.LOGGER.warn("Could not read the speech model's vocabulary from {} ({}); "
            + "spell names the model cannot pronounce will not be detected", p, why);
        return null;
    }

    // ---- little-endian primitives -------------------------------------------------------------

    private static int readInt(InputStream in) throws IOException {
        int b0 = need(in);
        int b1 = need(in);
        int b2 = need(in);
        int b3 = need(in);
        return b0 | (b1 << 8) | (b2 << 16) | (b3 << 24);
    }

    private static long readLong(InputStream in) throws IOException {
        long v = 0;
        for (int i = 0; i < 8; i++) {
            v |= ((long) need(in)) << (8 * i);
        }
        return v;
    }

    private static String readString(InputStream in) throws IOException {
        int len = readInt(in);
        if (len < 0 || len > MAX_SYMBOL_BYTES) {
            throw new IOException("implausible string length " + len);
        }
        byte[] d = new byte[len];
        int off = 0;
        while (off < len) {
            int r = in.read(d, off, len - off);
            if (r < 0) throw new EOFException("truncated string");
            off += r;
        }
        return new String(d, StandardCharsets.UTF_8);
    }

    private static int need(InputStream in) throws IOException {
        int b = in.read();
        if (b < 0) throw new EOFException("truncated header");
        return b;
    }
}
