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
    /** Section headings: darker than body on light panels, white on dark ones. The headings
     *  used to be accent purple, which was the last routine use of the accent outside the
     *  Codex progression screen. */
    public static int C_HEADING;
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
    /**
     * Whether screens draw as vanilla OPTIONS screens (no panel, content on the blurred world)
     * rather than as container windows.
     *
     * <p>Minecraft has exactly two screen archetypes and mixing them is what makes a modded UI
     * feel foreign. A container screen is a textured panel with dark text; an options screen has
     * no panel at all — Video Settings, Controls, Sound, every settings screen in the game. This
     * mod's screens ARE settings screens, so they follow the options archetype.
     *
     * <p>It lives here rather than in each screen because the choice has to be global: a
     * panelless config screen that opens a panelled sub-menu is worse than either style applied
     * consistently. Every screen already routes its surface through {@link #panel} and its text
     * through the palette, so this one field moves all of them.
     */
    public static boolean panelless = true;   // set from the palette on every applyPalette

    public static void applyPalette(com.niko.voicespells.VoiceSpellsConfig.UiPalette p) {
        // The style IS the palette choice, so a player can go back to a container window without
        // editing a file. OPTIONS keeps the panel tones below unused; everything visible comes
        // from the panelless block further down.
        panelless = p == com.niko.voicespells.VoiceSpellsConfig.UiPalette.OPTIONS;
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
                C_HEADING  = 0xFFFFFFFF;
            }
            case OPTIONS -> {
                // Overridden wholesale by the panelless block below; these are only the values
                // any stray panel-mode helper would fall back on.
                C_SCRIM    = 0x00000000;
                C_PANEL    = 0xFF303030;
                C_HEADER_T = 0xFF303030;
                C_HEADER_B = 0xFF303030;
                C_INSET    = 0x90101010;
                C_INSET_2  = 0x60FFFFFF;
                C_BORDER   = 0xFF000000;
                C_DIVIDER  = 0x40FFFFFF;
                C_SHADOW   = 0xFF000000;
                C_TEXT     = 0xFFFFFFFF;
                C_MUTED    = 0xFFA0A0A0;
                C_FAINT    = 0xFF808080;
                C_HEADING  = 0xFFFFFFFF;
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
                // Dark text, not white. SLATE's panel is 0x8B8B8B, whose luminance is 139, so
                // lightSurface() classes it as a LIGHT surface and every screen therefore draws
                // its text unshadowed. White unshadowed text on mid grey is about 2:1 contrast —
                // the one combination vanilla never uses, and it was made worse by C_MUTED being
                // 0x3F3F3F: the "muted" tone had BETTER contrast than the primary one, so the
                // hierarchy ran backwards. Going dark settles both, and matches this palette's
                // own stated aim of looking like a real inventory panel, which has dark text.
                C_TEXT     = 0xFF2A2A2A;
                C_MUTED    = 0xFF484848;
                C_FAINT    = 0xFF646464;
                C_HEADING  = 0xFF141414;
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
                // The inset well has to stay close enough to the panel that text tuned for the
                // panel is still readable inside it. At 0x8B8B8B it was 55 levels darker, and
                // C_MUTED detail lines (0x6A6A6A) sat on it at about 1.35:1 — the diagnostics
                // screen's reasons, the one thing that screen exists to convey, were washed out
                // to the edge of legibility. A gentler step still reads as recessed once the
                // sunken bevel is drawn around it, which is what actually communicates depth.
                C_INSET    = 0xFFB2B2B2;
                C_INSET_2  = 0xFFDBDBDB;
                C_BORDER   = 0xFF000000;
                C_DIVIDER  = 0xFF8B8B8B;
                C_SHADOW   = 0xFF555555;
                C_TEXT     = 0xFF404040;   // vanilla's container text colour
                C_MUTED    = 0xFF5C5C5C;   // secondary text; must clear the inset well too
                // 0x8B8B8B against the 0xC6C6C6 panel is under 2:1, which is fine for a divider
                // and not fine for words. C_FAINT does carry real text — the spell-of-the-day
                // hint, the wizard's secondary lines — so it has to stay legible while still
                // reading as the quietest tier.
                C_FAINT    = 0xFF757575;
                C_HEADING  = 0xFF1E1E1E;
            }
        }
        // Panelless screens sit on the blurred world, which is dark and BUSY, so they need
        // vanilla's menu text treatment — white with a shadow — regardless of which palette the
        // player picked for the panel tones. Applied after the switch so it wins, and before the
        // status colours so they derive from the right surface.
        if (panelless) {
            C_SCRIM    = 0x00000000;   // vanilla's own backdrop is the whole point
            C_PANEL    = 0xFF303030;   // only used by insets/fallbacks now
            C_INSET    = 0x90101010;   // wells are translucent dark over the world
            C_INSET_2  = 0x60FFFFFF;
            C_DIVIDER  = 0x40FFFFFF;
            C_SHADOW   = 0xFF000000;
            C_BORDER   = 0xFF000000;
            C_TEXT     = 0xFFFFFFFF;
            C_MUTED    = 0xFFA0A0A0;
            C_FAINT    = 0xFF808080;
            C_HEADING  = 0xFFFFFFFF;
        }
        // Status colours key off panel lightness, so they must be re-derived here — after
        // C_PANEL has been assigned for this palette and regardless of `changed`, since the
        // first call has to initialise them from zero.
        applyStatusColors();
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

    // ---- Status colours ---------------------------------------------------
    //
    // These were `static final` neon tones and documented as "kept palette-independent so error /
    // success messaging reads the same on dark and light". That reasoning inverted the moment the
    // default container palette became LIGHT: neon mint (0x6BFFB0, luminance ~0.85) drawn on the
    // 0xC6C6C6 panel is very nearly the same brightness as the panel itself, so every "Profile
    // copied", "Applied N settings", "Grammar reload requested" and every diagnostics OK row went
    // from readable to almost invisible. A status message nobody can read is worse than none,
    // because the player concludes the button did nothing.
    //
    // So they are palette-derived now, like C_TEXT and C_HEADING. Meaning is carried by HUE, which
    // survives the switch; only the LIGHTNESS flips, which is what contrast actually needs. Every
    // screen picked up the fix without a single call site changing — nine screens and about thirty
    // references — which is the same leverage that made the panel redesign tractable.
    //
    // Deliberately NOT used by the HUD: that draws over the world, where neither a light-panel nor
    // a dark-panel tone is right, so the overlay uses its own fixed colours.
    public static int F_MATCH;    // matched a spell
    public static int F_DEDUP;    // deduplicated / repeat
    public static int F_NOMATCH;  // heard, matched nothing
    public static int C_DANGER;   // errors, FAIL state
    public static int C_SUCCESS;  // confirmations, OK state
    public static int C_WARN;     // caution, in-progress

    /** Re-derive the status colours for the current panel lightness. */
    private static void applyStatusColors() {
        if (lightSurface()) {
            // Saturated and dark, the way vanilla colours text on a light container.
            F_MATCH   = 0xFF176B3A;
            F_DEDUP   = 0xFF7A5300;
            F_NOMATCH = 0xFF5A5A6B;
            C_DANGER  = 0xFFA01B1B;
            C_SUCCESS = 0xFF176B3A;
            C_WARN    = 0xFF7A5300;
        } else {
            F_MATCH   = 0xFF6BFFB0;
            F_DEDUP   = 0xFFFFD060;
            F_NOMATCH = 0xFF9892B8;
            C_DANGER  = 0xFFFF6166;
            C_SUCCESS = 0xFF6BFFB0;
            C_WARN    = 0xFFFFB347;
        }
    }

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

    // ---- The panel, drawn from Minecraft's own container texture ------------------------
    //
    // The comment above used to justify drawing this with fills: "the sprite APIs diverge
    // between the two shipping versions (1.21.1 has blitSprite, 1.20.1 only blitNineSliced),
    // and the texture paths moved too". That was checked and it is wrong for the call actually
    // needed here — `blit(ResourceLocation, x, y, u, v, w, h)` has an identical signature on
    // both, and assets/minecraft/textures/gui/container/generic_54.png exists unchanged in both,
    // 256x256 with the panel at (0,0,176,222). Verified by reading the pixels out of each
    // client jar rather than by assuming.
    //
    // It matters because a hand-drawn panel is an IMITATION of a texture. Vanilla's border is a
    // 1px black outline, then a 2px pure-white bevel that tapers across the corner, over a
    // 0xC6C6C6 body — and an approximation of that, at slightly the wrong tone with slightly
    // the wrong corner, is what made these screens read as a web page pasted into the game no
    // matter how the numbers were tuned. Blitting the real thing also means a player's resource
    // pack restyles our panels along with every container in the game.
    //
    // Nine-sliced by hand: 4px corners lifted from the four corners of the panel region, edges
    // and interior tiled from 4px chunks of known-uniform areas of the same texture. Tiling
    // rather than stretching keeps the border crisp at any panel size.

    private static final net.minecraft.resources.ResourceLocation PANEL_TEX =
//? if forge {
/*        new net.minecraft.resources.ResourceLocation("minecraft", "textures/gui/container/generic_54.png");
*///?} else {
        net.minecraft.resources.ResourceLocation.withDefaultNamespace(
            "textures/gui/container/generic_54.png");
//?}

    /** Corner slice size: the 1px outline plus vanilla's 3px bevel. */
    private static final int SLICE = 4;
    /** Panel region inside the 256x256 texture. */
    private static final int SRC_W = 176, SRC_H = 222;
    /** A patch of flat body colour inside the texture (the title area, above the slots). */
    private static final int BODY_U = 8, BODY_V = 6;

    /**
     * A complete Minecraft panel, blitted from the game's own container texture and tinted to
     * the active palette. This is the one call a screen needs for its main surface — it draws
     * the body AND the border, so callers must not add a frame of their own on top.
     */
    public static void panel(GuiGraphics g, int x, int y, int w, int h) {
        if (panelless) {
            // No window. Two rules instead, at the top and bottom of where the panel would have
            // been: the header/footer separators every vanilla options screen draws. They are
            // what stops content floating on scenery — the eye reads a header band, a content
            // band and a footer band. Drawn rather than blitted because the sprites for them
            // only exist from 1.20.5 onward and this has to match on 1.20.1.
            //
            // Full WINDOW width, not the notional panel's: vanilla's rules run edge to edge, and
            // a rule that stops short of the edges reads as the top of a box that is not there.
            int screenW = net.minecraft.client.Minecraft.getInstance()
                .getWindow().getGuiScaledWidth();
            separatorRule(g, 0, y, screenW);
            separatorRule(g, 0, y + h, screenW);
            return;
        }
        if (w < SLICE * 2 || h < SLICE * 2) {           // too small to slice; fall back
            g.fill(x, y, x + w, y + h, C_PANEL);
            return;
        }
        // Nine STRETCHED blits, not tiled 4x4 chunks.
        //
        // The tiled version issued one draw call per 4x4 pixel of panel: a 328x190 panel is
        // about 3,900 tiles, and GuiGraphics flushes per blit, so simply having a settings
        // screen open cost several thousand immediate-mode draw calls EVERY frame. Stretching is
        // pixel-identical here because each source region is uniform along the axis it spans —
        // the border strips do not vary along their length and the interior is flat 0xC6C6C6 —
        // and GUI blits sample nearest-neighbour. Same pixels, nine calls instead of thousands.
        float[] t = panelTint();
        try {
            // Tint carries the palette. The texture is a light stone panel, so multiplying gives
            // a darker panel that KEEPS the real border relief, which recolouring a flat fill
            // cannot.
            g.setColor(t[0], t[1], t[2], 1.0F);

            int iw = w - SLICE * 2, ih = h - SLICE * 2;     // interior span to fill
            // Corners
            g.blit(PANEL_TEX, x,             y,             0,             0,             SLICE, SLICE);
            g.blit(PANEL_TEX, x + w - SLICE, y,             SRC_W - SLICE, 0,             SLICE, SLICE);
            g.blit(PANEL_TEX, x,             y + h - SLICE, 0,             SRC_H - SLICE, SLICE, SLICE);
            g.blit(PANEL_TEX, x + w - SLICE, y + h - SLICE, SRC_W - SLICE, SRC_H - SLICE, SLICE, SLICE);
            // Edges: one stretched blit each.
            g.blit(PANEL_TEX, x + SLICE,     y,             iw,    SLICE, SLICE,         0,             SLICE, SLICE, 256, 256);
            g.blit(PANEL_TEX, x + SLICE,     y + h - SLICE, iw,    SLICE, SLICE,         SRC_H - SLICE, SLICE, SLICE, 256, 256);
            g.blit(PANEL_TEX, x,             y + SLICE,     SLICE, ih,    0,             SLICE,         SLICE, SLICE, 256, 256);
            g.blit(PANEL_TEX, x + w - SLICE, y + SLICE,     SLICE, ih,    SRC_W - SLICE, SLICE,         SLICE, SLICE, 256, 256);
            // Interior: a flat fill in the palette colour, not a tinted blit.
            //
            // The body of a vanilla container panel is uniform 0xC6C6C6 — there is no texture
            // detail to lose — so filling it costs nothing in fidelity and gains the exact
            // palette colour. It also fixes MIDNIGHT: multiplying by 0x141414/0xC6C6C6 collapsed
            // the white bevel and the body to within four 8-bit levels of each other, so the
            // dark palette lost its border relief completely and became a black rectangle. The
            // border ring keeps a floored tint (below) so the frame stays visible, and the
            // interior lands on precisely the colour the palette asked for.
            g.setColor(1.0F, 1.0F, 1.0F, 1.0F);
            g.fill(x + SLICE, y + SLICE, x + w - SLICE, y + h - SLICE, C_PANEL);
        } finally {
            // In a finally so an exception mid-panel cannot leave every later draw on the screen
            // tinted — a leaked setColor is invisible in the code that caused it and baffling in
            // the code that suffers it.
            g.setColor(1.0F, 1.0F, 1.0F, 1.0F);
        }
    }

    /** One of vanilla's header/footer rules: a dark line with a soft highlight under it. */
    /** Public form of the header/footer rule, for screens that place their own. */
    public static void rule(GuiGraphics g, int x, int y, int w) { separatorRule(g, x, y, w); }

    private static void separatorRule(GuiGraphics g, int x, int y, int w) {
        // A black line over a blurred night-time world is invisible, which is exactly what the
        // first attempt produced. Lead with the light line and back it with black, so the rule
        // reads against both a bright scene and a dark one.
        g.fill(x, y, x + w, y + 1, 0x60000000);
        g.fill(x, y + 1, x + w, y + 2, 0x90FFFFFF);
        g.fill(x, y + 2, x + w, y + 3, 0x60000000);
    }

    /**
     * Multiplier applied to the panel texture for the active palette.
     *
     * <p>Derived from C_PANEL against vanilla's own body tone, so a palette only has to state
     * what colour it wants the panel to be and the texture follows — no per-palette tint table
     * to keep in sync with the colours right above it.
     */
    private static float[] panelTint() {
        final float VANILLA_BODY = 0xC6 / 255.0F;
        // Floored, because a multiply is a ratio and ratios stop being visible near black. At
        // MIDNIGHT's 0x141414 the raw factor is 0.08, which maps vanilla's white bevel to 0x14
        // and its body to 0x10 — a four-level difference nobody can see, so the frame vanished
        // and the panel became a plain black rectangle. The floor keeps the border ring readable
        // as a frame; the interior no longer depends on this at all, it is filled with C_PANEL.
        final float FLOOR = 0.34F;
        float r = ((C_PANEL >> 16) & 0xFF) / 255.0F / VANILLA_BODY;
        float gg = ((C_PANEL >> 8) & 0xFF) / 255.0F / VANILLA_BODY;
        float b = (C_PANEL & 0xFF) / 255.0F / VANILLA_BODY;
        return new float[] {
            Math.min(1.0F, Math.max(FLOOR, r)),
            Math.min(1.0F, Math.max(FLOOR, gg)),
            Math.min(1.0F, Math.max(FLOOR, b)),
        };
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
        // Deliberately empty. A container screen has no header band — the title is text on the
        // panel — and now that the panel is a real texture, painting a flat rectangle over the
        // top of it would erase exactly the part the player notices. Kept as a call so the ten
        // screens that invoke it did not each need editing.
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
        // A panelless screen's surface is the blurred world, not C_PANEL — always treat it as
        // dark so text is drawn white AND shadowed, which is the only way it stays readable over
        // arbitrary scenery.
        if (panelless) return false;
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
        // Vanilla list scrollbar: dark track, plain grey thumb with a light top edge —
        // the construction every vanilla selection list uses. No accent; this was the last
        // place the neon survived.
        // Track keyed to the surface, not hard-coded black. A black groove is right on a dark
        // options screen and reads as a hole cut in a light container panel, which is where the
        // default palette now lives.
        g.fill(x, y, x + w, y + h, lightSurface() ? 0xFF6E6E6E : 0xFF000000);
        int thumbH = Math.max(12, h * visible / total);
        int maxScroll = total - visible;
        int thumbY = y + (h - thumbH) * Math.min(scroll, maxScroll) / Math.max(1, maxScroll);
        g.fill(x, thumbY, x + w, thumbY + thumbH, 0xFF8B8B8B);
        g.fill(x, thumbY, x + w, thumbY + 1, 0xFFC6C6C6);
        g.fill(x, thumbY + thumbH - 1, x + w, thumbY + thumbH, 0xFF555555);
    }
}
