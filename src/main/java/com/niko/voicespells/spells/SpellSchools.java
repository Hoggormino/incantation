package com.niko.voicespells.spells;

import net.minecraft.core.Holder;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;

import java.util.Locale;
import java.util.Map;

/**
 * Per-school visual + audio mappings used by the HUD (toast color) and the recognition echo
 * SFX (chime pitch and instrument). School names come from Iron's Spells'
 * {@code SchoolType.getId()} via {@link SpellInfo} and are normalized to a short lowercase
 * key — e.g. {@code "fire"}, {@code "ice"}, {@code "lightning"}.
 *
 * Anything unrecognised falls back to {@link #DEFAULT_COLOR} / {@link #DEFAULT_SOUND}, which
 * is the previous "all spells get the same look" behaviour.
 */
public final class SpellSchools {
    private SpellSchools() {}

    public static final int DEFAULT_COLOR = 0xFFE6E3F0; // cool off-white (matches Theme.C_TEXT)

    /** School key → ARGB color for the cast toast and history strip. Chosen for a quick "I can
     *  tell what school just landed" read; tuned to remain legible against the neon panel. */
    private static final Map<String, Integer> COLORS = Map.ofEntries(
        Map.entry("fire",       0xFFFFA255),
        Map.entry("ice",        0xFF9BD4FF),
        Map.entry("lightning",  0xFFFFE066),
        Map.entry("nature",     0xFF8BE08F),
        Map.entry("holy",       0xFFFFD475),
        Map.entry("ender",      0xFFC49AFF),
        Map.entry("evocation",  0xFFC49AFF),
        Map.entry("blood",      0xFFFF6D6D),
        Map.entry("void",       0xFFA48FE0),
        Map.entry("eldritch",   0xFF8FE0C0)
    );

    /** School key → (sound event, pitch). Each school gets a distinct instrument so the echo
     *  chime gives an audio cue beyond just "spell heard". Volume is owned by the caller. */
    public record Cue(Holder<SoundEvent> sound, float pitch) {}

    public static final Cue DEFAULT_SOUND = new Cue(SoundEvents.NOTE_BLOCK_CHIME, 1.5f);

    private static final Map<String, Cue> CUES = Map.ofEntries(
        Map.entry("fire",       new Cue(SoundEvents.NOTE_BLOCK_BELL,           1.6f)),
        Map.entry("ice",        new Cue(SoundEvents.NOTE_BLOCK_BELL,           1.0f)),
        Map.entry("lightning",  new Cue(SoundEvents.NOTE_BLOCK_XYLOPHONE,      1.7f)),
        Map.entry("nature",     new Cue(SoundEvents.NOTE_BLOCK_HARP,           1.2f)),
        Map.entry("holy",       new Cue(SoundEvents.NOTE_BLOCK_CHIME,          1.8f)),
        Map.entry("ender",      new Cue(SoundEvents.NOTE_BLOCK_COW_BELL,       1.1f)),
        Map.entry("evocation",  new Cue(SoundEvents.NOTE_BLOCK_CHIME,          1.4f)),
        Map.entry("blood",      new Cue(SoundEvents.NOTE_BLOCK_BASS,           1.4f)),
        Map.entry("void",       new Cue(SoundEvents.NOTE_BLOCK_BASS,           1.0f)),
        Map.entry("eldritch",   new Cue(SoundEvents.NOTE_BLOCK_DIDGERIDOO,     1.2f))
    );

    /** Normalize an Iron's Spells school name down to a lookup key. */
    private static String key(String school) {
        return school == null ? "" : school.toLowerCase(Locale.ROOT).trim();
    }

    /** ARGB color for the school's toast / history chip. */
    public static int colorFor(String school) {
        return COLORS.getOrDefault(key(school), DEFAULT_COLOR);
    }

    /** Audio cue (sound + pitch) for the school. */
    public static Cue cueFor(String school) {
        return CUES.getOrDefault(key(school), DEFAULT_SOUND);
    }
}
