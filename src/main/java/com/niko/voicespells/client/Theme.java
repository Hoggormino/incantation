package com.niko.voicespells.client;

import net.minecraft.client.gui.GuiGraphics;

/**
 * Shared visual language for VoiceSpells screens.
 *
 * <p><b>Goal: look like it shipped with the game.</b> Minecraft's GUI has a very specific and
 * very consistent grammar — flat desaturated stone-grey surfaces, a 1px black outline around
 * every raised element, a light bevel on the top and left with a dark one on the bottom and
 * right, drop-shadowed text, square corners, and nothing that animates. Matching that grammar
 * is what makes a modded screen feel native instead of pasted in.
 *
 * <p>This used to be the opposite of all of it: saturated lavender-midnight surfaces, a
 * pulsing seven-layer neon rule, 2px "rounded" corners, and unshadowed text. It looked like a
 * web dashboard. The palettes have been moved onto vanilla's neutral grey axis, the chrome
 * helpers now draw vanilla bevels, and text goes through helpers that always shadow.
 *
 * <p>The accent colour survives — it is the player's unlocked {@code ThemePreset} and carries
 * the mod's identity — but it is now used the way vanilla uses colour: sparingly, for state
 * and for a single seated rule, never as a glow.
 *
 * <p>Screens are expected to call {@link #background} first, {@link #panel} for their surface,
 * and {@link #text} / {@link #title} for copy. The older helpers kept their names and
 * signatures so all existing screens inherit the new look without being rewritten.
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
                // Near-black, but no longer pure 0x000000: a true-black panel makes the
                // vanilla bevel invisible, since the bevel is derived from the panel tone and
                // the outline is black. 0x141414 keeps it as dark as a streamer wants while
                // leaving the edges readable.
                C_SCRIM    = 0xE0000000;
                C_PANEL    = 0xFF141414;
                C_HEADER_T = 0xFF1E1E1E;
                C_HEADER_B = 0xFF0A0A0A;
                C_INSET    = 0xFF0A0A0A;
                C_INSET_2  = 0xFF1A1A1A;
                C_BORDER   = 0xFF000000;
                C_DIVIDER  = 0xFF3A3A3A;
                C_SHADOW   = 0xFF000000;
                C_TEXT     = 0xFFFFFFFF;
                C_MUTED    = 0xFFA0A0A0;
                C_FAINT    = 0xFF6A6A6A;
            }
            case SLATE -> {
                // Vanilla's actual stone-grey widget tone — this is the closest of the three
                // to a real Minecraft inventory panel.
                C_SCRIM    = 0xC0202020;
                C_PANEL    = 0xFF8B8B8B;
                C_HEADER_T = 0xFF9E9E9E;
                C_HEADER_B = 0xFF7A7A7A;
                C_INSET    = 0xFF5B5B5B;
                C_INSET_2  = 0xFF969696;
                C_BORDER   = 0xFF000000;
                C_DIVIDER  = 0xFF373737;
                C_SHADOW   = 0xFF373737;
                C_TEXT     = 0xFFFFFFFF;
                C_MUTED    = 0xFF3F3F3F;
                C_FAINT    = 0xFF5A5A5A;
            }
            case DARK -> {
                // The default, retuned to read as Minecraft rather than as a neon web app.
                //
                // Vanilla's GUI palette is desaturated stone grey — the inventory is 0xC6C6C6
                // with 0x8B8B8B and 0x373737 bevels, and the modern dark menus sit near
                // 0x2B2B2B. The old values here were a saturated lavender midnight (panel
                // 0x101020, text 0xEAE6FA) which never appears anywhere in the game. These are
                // the same tonal RELATIONSHIPS, moved onto vanilla's neutral grey axis, so the
                // panels sit next to a vanilla inventory without clashing. Text is plain white
                // and vanilla's own GRAY (0xA0A0A0), which is what Minecraft uses for secondary
                // labels everywhere.
                // The vanilla container-GUI palette, i.e. what a chest or a furnace looks
                // like, which is also exactly what Simple Voice Chat uses: a light stone
                // panel with dark grey text. Its VoiceChatScreenBase.FONT_COLOR is 4210752 =
                // 0x404040, and its panels are plain light PNGs with no accent anywhere.
                // That understatement is the whole point — a modded screen that looks like
                // an inventory reads as part of the game, and one with a dark neon panel
                // reads as an overlay.
                C_SCRIM    = 0x00000000;   // no scrim; vanilla's own backdrop is enough
                C_PANEL    = 0xFFC6C6C6;
                C_HEADER_T = 0xFFC6C6C6;   // container GUIs have no header band at all
                C_HEADER_B = 0xFFC6C6C6;
                C_INSET    = 0xFF8B8B8B;
                C_INSET_2  = 0xFFDBDBDB;
                C_BORDER   = 0xFF000000;
                C_DIVIDER  = 0xFF8B8B8B;
                C_SHADOW   = 0xFF555555;
                C_TEXT     = 0xFF404040;   // vanilla's container text colour
                C_MUTED    = 0xFF6A6A6A;
                C_FAINT    = 0xFF8B8B8B;
            }
        }
        // Re-derive accent layers immediately if the palette flipped (SLATE needs different
        // accent variants than MIDNIGHT). Skipped on first call (before applyPreset has been
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

    // -----------------------------------------------------------------------
    // Vanilla bevel construction.
    //
    // Minecraft's GUI has one unmistakable signature: every raised surface is a flat fill
    // wrapped in a 1px black outline, with a lighter edge along the top and left and a darker
    // edge along the bottom and right. That is what makes a vanilla button read as a physical
    // object, and its absence is why a flat coloured rectangle always looks like a web page
    // pasted into the game. Everything below is built from that one idea, so the whole mod
    // inherits it without any screen having to change.
    //
    // Drawn with fills rather than a vanilla texture on purpose: the sprite APIs diverge
    // between the two shipping versions (1.21.1 has blitSprite, 1.20.1 only blitNineSliced),
    // and the texture paths moved too, so a hardcoded path that is subtly wrong on one loader
    // renders as missing-texture magenta. Bevels are identical on both and cannot 404.
    // -----------------------------------------------------------------------

    /** Lighten an ARGB toward white by {@code f} (0..1), preserving alpha. */
    private static int lighten(int argb, float f) {
        int a = (argb >>> 24) & 0xFF;
        int r = (argb >> 16) & 0xFF, gg = (argb >> 8) & 0xFF, b = argb & 0xFF;
        r = (int) (r + (255 - r) * f);
        gg = (int) (gg + (255 - gg) * f);
        b = (int) (b + (255 - b) * f);
        return (a << 24) | (r << 16) | (gg << 8) | b;
    }

    /** Darken an ARGB toward black by {@code f} (0..1), preserving alpha. */
    private static int darken(int argb, float f) {
        int a = (argb >>> 24) & 0xFF;
        int r = (int) (((argb >> 16) & 0xFF) * (1 - f));
        int gg = (int) (((argb >> 8) & 0xFF) * (1 - f));
        int b = (int) ((argb & 0xFF) * (1 - f));
        return (a << 24) | (r << 16) | (gg << 8) | b;
    }

    /**
     * A complete Minecraft-style raised panel: black outline, bevelled edges, flat fill.
     * This is the one call a screen needs for its main surface.
     */
    public static void panel(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, C_PANEL);
        bevel(g, x, y, w, h, false);
    }

    /**
     * Draw the vanilla bevel over an already-filled rect.
     *
     * @param sunken {@code true} inverts the light so the surface reads as recessed — vanilla
     *               uses this for text fields, list backgrounds and slots.
     */
    public static void bevel(GuiGraphics g, int x, int y, int w, int h, boolean sunken) {
        // Vanilla container chrome: a 4px light border with a white inner highlight on the top
        // and left and a mid-grey on the bottom and right, seated on black. This is the exact
        // construction of a chest GUI's edge, and it is why those screens read as a physical
        // panel rather than a coloured box.
        int hi = sunken ? darken(C_PANEL, 0.40f) : lighten(C_PANEL, 0.60f);
        int lo = sunken ? lighten(C_PANEL, 0.45f) : darken(C_PANEL, 0.32f);
        int outline = 0xFF000000;
        g.fill(x, y, x + w, y + 1, outline);
        g.fill(x, y + h - 1, x + w, y + h, outline);
        g.fill(x, y, x + 1, y + h, outline);
        g.fill(x + w - 1, y, x + w, y + h, outline);
        g.fill(x + 1, y + 1, x + w - 1, y + 3, hi);
        g.fill(x + 1, y + 1, x + 3, y + h - 1, hi);
        g.fill(x + 1, y + h - 3, x + w - 1, y + h - 1, lo);
        g.fill(x + w - 3, y + 1, x + w - 1, y + h - 1, lo);
    }

    /**
     * Frame helper kept at its original name and signature so every screen picks up the new
     * look for free — it is called ~28 times across the UI. It used to draw a 2px-notched
     * "rounded" outline in a single flat colour, which is a web idiom; Minecraft has no
     * rounded corners anywhere in its GUI. Now it draws the vanilla bevel instead, tinted
     * toward the requested colour so callers that pass an accent still get their emphasis.
     */
    public static void roundedFrame(GuiGraphics g, int x, int y, int w, int h, int color) {
        g.fill(x, y, x + w, y + 1, 0xFF000000);
        g.fill(x, y + h - 1, x + w, y + h, 0xFF000000);
        g.fill(x, y, x + 1, y + h, 0xFF000000);
        g.fill(x + w - 1, y, x + w, y + h, 0xFF000000);
        g.fill(x + 1, y + 1, x + w - 1, y + 2, lighten(color, 0.25f));
        g.fill(x + 1, y + 1, x + 2, y + h - 1, lighten(color, 0.25f));
        g.fill(x + 1, y + h - 2, x + w - 1, y + h - 1, darken(color, 0.4f));
        g.fill(x + w - 2, y + 1, x + w - 1, y + h - 1, darken(color, 0.4f));
    }

    /**
     * Header band — intentionally almost nothing now.
     *
     * <p>A container screen has no header band; the title is simply text sitting on the same
     * panel. Anything more is decoration the game does not have. Kept as a no-op-shaped call
     * (it still paints the panel tone, so callers that draw it before their title do not get
     * a hole) so the nine screens did not each need editing.
     */
    public static void headerBand(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, C_HEADER_T);
    }

    /**
     * Section rule under a header.
     *
     * <p>This was a seven-layer pulsing neon halo — the single most un-Minecraft thing in the
     * mod, since nothing in vanilla glows or animates in a menu. It is now a 2px seated rule
     * in the accent colour: a light line over a dark one, the same construction vanilla uses
     * to separate the header from the body of a container screen. The accent still carries the
     * player's chosen theme, so the personality survives without the neon.
     */
    public static void accentGlow(GuiGraphics g, int x, int y, int w) {
        // A single hairline separator in the panel's own shadow tone. Simple Voice Chat has no
        // accent rule at all; this is the least that still separates a title from its body.
        // It was a seven-layer pulsing neon halo two revisions ago.
        g.fill(x, y, x + w, y + 1, C_SHADOW);
    }

    /**
     * Paint the screen backdrop the way vanilla does, then dim behind the panel.
     *
     * <p>Every screen in this mod used to fill its own flat scrim and never call
     * {@code Screen.renderBackground} at all. That skipped Minecraft's own backdrop entirely,
     * so the mod ignored the player's Menu Background Blur setting, did not show the blurred
     * world behind an in-game menu, and did not show the dirt texture on the title screen —
     * three things every vanilla and well-behaved modded screen does. Routing through vanilla
     * first means the mod now matches whatever the player has configured.
     *
     * <p>The signature diverged between the two shipping versions, hence the split:
     * 1.21.1 takes (graphics, mouseX, mouseY, partialTick); 1.20.1 takes just (graphics).
     * Verified with javap against neoforge-21.1.219-merged and forge-1.20.1-47.4.10-merged.
     */
    public static void background(net.minecraft.client.gui.screens.Screen screen, GuiGraphics g,
                                  int mouseX, int mouseY, float partialTick) {
//? if forge {
/*        screen.renderBackground(g);
*///?} else {
        screen.renderBackground(g, mouseX, mouseY, partialTick);
//?}
    }

    /**
     * True when the current panel is light enough that text on it should be dark and
     * UNSHADOWED, which is what vanilla does on container screens.
     *
     * <p>Shadowing is not a blanket rule. Vanilla shadows white text on dark surfaces (the
     * options menu, the HUD) and deliberately does not shadow the dark 0x404040 text on a
     * container panel — a shadow under dark-on-light text just looks like a rendering bug.
     * Deriving it from panel luminance keeps every palette correct without each caller
     * having to know which one is active.
     */
    public static boolean lightSurface() {
        int r = (C_PANEL >> 16) & 0xFF, gg = (C_PANEL >> 8) & 0xFF, b = C_PANEL & 0xFF;
        return (r * 299 + gg * 587 + b * 114) / 1000 > 128;
    }

    /** Draw text with the shadow convention the current surface calls for. */
    public static void text(GuiGraphics g, net.minecraft.client.gui.Font font, String s,
                            int x, int y, int color) {
        g.drawString(font, s, x, y, color, !lightSurface());
    }

    /** Centred, shadowed — vanilla's own title treatment. */
    public static void title(GuiGraphics g, net.minecraft.client.gui.Font font, String s,
                             int cx, int y, int color) {
        g.drawCenteredString(font, s, cx, y, color);
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
