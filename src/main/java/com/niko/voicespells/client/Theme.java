package com.niko.voicespells.client;

import net.minecraft.client.gui.GuiGraphics;

/**
 * Shared visual language for VoiceSpells screens.
 *
 * Minimalist magic with neon energy: deep midnight surfaces, a bright electric-purple accent
 * that breathes, and very restrained chrome. The palette is intentionally tiny — three surface
 * tones, three text tones, one neon accent — so the screens read as quiet and mystical, with
 * the neon doing the heavy lifting for state and hierarchy.
 *
 * Polish comes from the helpers: gradient header band, multi-layer accent glow with a slow
 * pulse, 2px rounded corners, inset shadows, and a thin neon scrollbar.
 */
public final class Theme {
    private Theme() {}

    // ---- Layout ------------------------------------------------------------
    public static final int PAD       = 16;
    public static final int HEADER_H  = 32;
    public static final int ROW_H     = 24;
    public static final int GAP_SM    = 6;
    public static final int GAP_MD    = 10;
    public static final int GAP_LG    = 14;

    /** Minimum screen margin we keep around a centred panel before it starts overflowing —
     *  picked to match vanilla Minecraft's own screens (the title screen / pause menu use
     *  ~8 px of vertical breathing room at small GUI scales). */
    public static final int PANEL_MARGIN = 8;

    /**
     * Fit a panel dimension to the available screen dimension so the user can see everything at
     * any Minecraft GUI scale. Returns the preferred size when it fits with a small margin, or
     * shrinks down to {@code (available - 2*PANEL_MARGIN)} when the screen is tighter (e.g.
     * Video Settings → GUI Scale = 4 on a small window). Never returns a negative or zero size —
     * caps at a sensible floor so the panel doesn't collapse to invisibility on absurd configs.
     *
     * <p>Use this in every {@code Screen#init} when computing {@code panelW} / {@code panelH}.
     * Layout math elsewhere in the screen should reference those runtime values, not the
     * compile-time preferred constants, so big GUI scales don't push controls off-screen.
     */
    public static int fit(int preferred, int available) {
        int max = Math.max(80, available - 2 * PANEL_MARGIN);
        return Math.min(preferred, max);
    }

    // ---- Surfaces (driven by UiPalette — defaults to DARK) ----------------
    public static int C_SCRIM;
    public static int C_PANEL;
    public static int C_HEADER_T;
    public static int C_HEADER_B;
    public static int C_INSET;
    public static int C_INSET_2;

    // ---- Lines (also palette-driven) --------------------------------------
    public static int C_BORDER;
    public static int C_DIVIDER;
    public static int C_SHADOW;

    /** The hero neon — driven by the current {@link com.niko.voicespells.VoiceSpellsConfig.ThemePreset}.
     *  Not final so the user can swap the whole palette without restarting. */
    public static int C_ACCENT;
    public static int C_ACCENT_BRIGHT;
    public static int C_ACCENT_SOFT;
    public static int C_ACCENT_FAINT;
    public static int C_ACCENT_GHOST;

    // ---- Text (palette-driven) --------------------------------------------
    public static int C_TEXT;
    public static int C_MUTED;
    public static int C_FAINT;

    /** Tracks the current palette so {@link #applyPreset} can pick accent variants that read
     *  on the background (purple on white needs darkening; the alpha-blend layers used for
     *  glow on dark just fade to invisible on light). */
    private static com.niko.voicespells.VoiceSpellsConfig.UiPalette currentPalette =
        com.niko.voicespells.VoiceSpellsConfig.UiPalette.DARK;
    /** Tracks the current accent preset so {@link #applyPalette} can re-derive accent layers
     *  immediately on a palette flip (otherwise the accent would only refresh on the next
     *  preset change). */
    private static com.niko.voicespells.VoiceSpellsConfig.ThemePreset currentPreset =
        com.niko.voicespells.VoiceSpellsConfig.ThemePreset.ARCANE;

    static {
        applyPalette(com.niko.voicespells.VoiceSpellsConfig.UiPalette.DARK);
        applyPreset(com.niko.voicespells.VoiceSpellsConfig.ThemePreset.ARCANE);
    }

    /** Switch the base surface + text palette. Independent of {@link #applyPreset}, which
     *  only changes the accent color. */
    public static void applyPalette(com.niko.voicespells.VoiceSpellsConfig.UiPalette p) {
        boolean changed = currentPalette != p;
        currentPalette = p;
        switch (p) {
            case MIDNIGHT -> {
                // TRUE black. No purple tint, no warm tint — pure flat black for streamers
                // who want minimum light spill. Borders/text use very-cool greys.
                C_SCRIM    = 0xEE000000;
                C_PANEL    = 0xFF000000;
                C_HEADER_T = 0xFF0A0A0A;
                C_HEADER_B = 0xFF030303;
                C_INSET    = 0xFF050505;
                C_INSET_2  = 0xFF0F0F0F;
                C_BORDER   = 0xFF333333;
                C_DIVIDER  = 0xFF1A1A1A;
                C_SHADOW   = 0xFF000000;
                C_TEXT     = 0xFFEEEEEE;
                C_MUTED    = 0xFF9A9A9A;
                C_FAINT    = 0xFF5A5A5A;
            }
            case SLATE -> {
                // True neutral grey — zero blue/purple bleed. Distinctly lighter than DARK
                // and clearly cooler than LIGHT. Reads as gunmetal / aluminum.
                C_SCRIM    = 0xCC202020;
                C_PANEL    = 0xFF454545;     // visibly lighter than DARK's 0x101020
                C_HEADER_T = 0xFF555555;
                C_HEADER_B = 0xFF3D3D3D;
                C_INSET    = 0xFF333333;
                C_INSET_2  = 0xFF4A4A4A;
                C_BORDER   = 0xFF7A7A7A;
                C_DIVIDER  = 0xFF5C5C5C;
                C_SHADOW   = 0xFF1A1A1A;
                C_TEXT     = 0xFFF0F0F0;
                C_MUTED    = 0xFFB0B0B0;
                C_FAINT    = 0xFF808080;
            }
            case DARK -> {
                // Purple-tinged midnight — the original VoiceSpells look. Distinct from both
                // SLATE (neutral) and MIDNIGHT (true black) because of the lavender shift.
                C_SCRIM    = 0xCC050410;
                C_PANEL    = 0xFF101020;
                C_HEADER_T = 0xFF1E1A38;
                C_HEADER_B = 0xFF131225;
                C_INSET    = 0xFF0A0913;
                C_INSET_2  = 0xFF14122A;
                C_BORDER   = 0xFF2E2A52;
                C_DIVIDER  = 0xFF221E3C;
                C_SHADOW   = 0xFF050410;
                C_TEXT     = 0xFFEAE6FA;
                C_MUTED    = 0xFF9892B8;
                C_FAINT    = 0xFF6A6390;
            }
        }
        // Re-derive accent layers immediately if the palette flipped (e.g. LIGHT needs darker
        // accent variants than DARK). Skipped on first call (before applyPreset has been
        // invoked at all) — the static initializer drives that path.
        if (changed) applyPreset(currentPreset);
    }

    /** Re-derive all accent variants from the given preset. All remaining palettes are dark
     *  surfaces, so we always use alpha-faded copies for the glow layers. */
    public static void applyPreset(com.niko.voicespells.VoiceSpellsConfig.ThemePreset p) {
        currentPreset = p;
        int rgb = p.accent & 0x00FFFFFF;
        C_ACCENT        = 0xFF000000 | rgb;
        C_ACCENT_BRIGHT = brighten(p.accent, 1.15f);
        C_ACCENT_SOFT   = 0x66000000 | rgb;
        C_ACCENT_FAINT  = 0x33000000 | rgb;
        C_ACCENT_GHOST  = 0x11000000 | rgb;
    }

    private static int brighten(int argb, float scale) {
        int r = Math.min(255, Math.max(0, (int) (((argb >> 16) & 0xFF) * scale)));
        int g = Math.min(255, Math.max(0, (int) (((argb >> 8)  & 0xFF) * scale)));
        int b = Math.min(255, Math.max(0, (int) (( argb        & 0xFF) * scale)));
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    // ---- Status (monitor) - neon variants (palette-independent) -----------
    public static final int F_MATCH   = 0xFF6BFFB0; // neon mint
    public static final int F_DEDUP   = 0xFFFFD060; // neon gold
    public static final int F_NOMATCH = 0xFF9892B8;

    // ---- Semantic state colors --------------------------------------------
    // Used for status messages, banners, and diagnostic outputs across screens. Kept
    // palette-independent so error / success messaging reads the same on dark and light.
    public static final int C_DANGER  = 0xFFFF6166; // soft coral red — errors, warnings, FAIL state
    public static final int C_SUCCESS = 0xFF6BFFB0; // neon mint     — confirmations, OK state
    public static final int C_WARN    = 0xFFFFB347; // warm amber    — caution, in-progress

    // -----------------------------------------------------------------------
    // Animated helpers — time-based, called every frame, no state.
    // -----------------------------------------------------------------------

    /**
     * Returns a 0..1 sine wave from the system clock. Used to pulse neon glows so the screen
     * feels quietly alive. Period in millis controls how fast it breathes.
     */
    public static float pulse(float periodMs) {
        long t = System.currentTimeMillis();
        double phase = (t % (long) periodMs) / (double) periodMs;
        return (float) ((Math.sin(phase * Math.PI * 2) + 1.0) / 2.0);
    }

    /** Lerp the alpha channel of a base color between two 0..1 alphas. */
    public static int withPulsedAlpha(int baseRgb, float minAlpha, float maxAlpha) {
        float a = minAlpha + (maxAlpha - minAlpha) * pulse(2400f);
        int aByte = Math.max(0, Math.min(255, (int) (a * 255)));
        return (baseRgb & 0x00FFFFFF) | (aByte << 24);
    }

    // -----------------------------------------------------------------------
    // Chrome helpers — keep both screens visually identical via these.
    // -----------------------------------------------------------------------

    /**
     * Rounded frame with 2px diagonal corner notches. Two pixels of softening gives a
     * noticeably softer silhouette without looking pixellated.
     */
    public static void roundedFrame(GuiGraphics g, int x, int y, int w, int h, int color) {
        g.fill(x + 2,     y,         x + w - 2, y + 1,     color); // top
        g.fill(x + 2,     y + h - 1, x + w - 2, y + h,     color); // bottom
        g.fill(x,         y + 2,     x + 1,     y + h - 2, color); // left
        g.fill(x + w - 1, y + 2,     x + w,     y + h - 2, color); // right
        g.fill(x + 1,     y + 1,     x + 2,     y + 2,     color); // tl
        g.fill(x + w - 2, y + 1,     x + w - 1, y + 2,     color); // tr
        g.fill(x + 1,     y + h - 2, x + 2,     y + h - 1, color); // bl
        g.fill(x + w - 2, y + h - 2, x + w - 1, y + h - 1, color); // br
    }

    /** Header band with a top-to-bottom gradient — quiet elevation. */
    public static void headerBand(GuiGraphics g, int x, int y, int w, int h) {
        g.fillGradient(x, y, x + w, y + h, C_HEADER_T, C_HEADER_B);
    }

    /**
     * Five-layer neon accent rule: three fade-up layers above the crisp line, two below.
     * The outermost halo pulses so the rule feels lit.
     */
    public static void accentGlow(GuiGraphics g, int x, int y, int w) {
        int outer = withPulsedAlpha((C_ACCENT & 0x00FFFFFF), 0.06f, 0.16f);
        g.fill(x, y - 3, x + w, y - 2, outer);            // outer halo (pulsed)
        g.fill(x, y - 2, x + w, y - 1, C_ACCENT_FAINT);   // dim above
        g.fill(x, y - 1, x + w, y,     C_ACCENT_SOFT);    // soft above
        g.fill(x, y,     x + w, y + 1, C_ACCENT_BRIGHT);  // crisp center (bright)
        g.fill(x, y + 1, x + w, y + 2, C_ACCENT_SOFT);    // soft below
        g.fill(x, y + 2, x + w, y + 3, C_ACCENT_FAINT);   // dim below
        g.fill(x, y + 3, x + w, y + 4, outer);            // outer halo (pulsed)
    }

    /** Inset shadow — 1px darker line at the top of a recessed surface so it reads as
     *  "below" the panel. */
    public static void insetShadow(GuiGraphics g, int x, int y, int w) {
        g.fill(x, y, x + w, y + 1, C_SHADOW);
    }

    /** Thin horizontal divider, low contrast. */
    public static void divider(GuiGraphics g, int x, int y, int w) {
        g.fill(x, y, x + w, y + 1, C_DIVIDER);
    }

    /** Thin neon scrollbar — track + thumb. The thumb gets the soft accent so the eye finds it. */
    public static void scrollbar(GuiGraphics g, int x, int y, int w, int h,
                                  int total, int visible, int scroll) {
        if (total <= visible) return; // no overflow, no bar
        g.fill(x, y, x + w, y + h, C_DIVIDER);
        int thumbH = Math.max(12, h * visible / total);
        int maxScroll = total - visible;
        int thumbY = y + (h - thumbH) * Math.min(scroll, maxScroll) / Math.max(1, maxScroll);
        g.fill(x, thumbY, x + w, thumbY + thumbH, C_ACCENT_SOFT);
        // Subtle bright tip on the thumb for that neon edge.
        g.fill(x, thumbY, x + w, thumbY + 1,            C_ACCENT_BRIGHT);
        g.fill(x, thumbY + thumbH - 1, x + w, thumbY + thumbH, C_ACCENT_BRIGHT);
    }
}
