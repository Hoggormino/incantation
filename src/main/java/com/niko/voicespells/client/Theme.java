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
 * <p>This used to be the opposite of all of it: saturated lavender-midnight surfaces, a pulsing
 * seven-layer neon rule, 2px "rounded" corners, and unshadowed text. It looked like a web
 * dashboard.
 *
 * <p>The way out was not to tune those numbers but to stop drawing chrome at all. Every hand-drawn
 * helper this class used to own - the panel, the bevels, the rounded frames, the inset shadows,
 * the separators, the scrollbar - has been deleted in favour of the real thing: vanilla widgets,
 * vanilla sprites, and vanilla's own recessed-list tone. "An imitation is what it looks like, and
 * no amount of tuning the numbers fixes a difference in kind."
 *
 * <p>What is left is a palette, a few layout constants, and the handful of draws that genuinely
 * have no vanilla counterpart. Screens call {@link #ground} first, {@link #well} for a recessed
 * surface, and {@link #text} / {@link #title} for copy.
 */
public final class Theme {
    private Theme() {}

    // ---- Layout ------------------------------------------------------------
    public static final int PAD       = 16;
    public static final int HEADER_H  = 32;
    public static final int ROW_H     = 24;
    public static final int GAP_SM    = 6;
    public static final int GAP_MD    = 10;

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

    /**
     * Truncate a string to {@code room} pixels, with an ellipsis when it had to be cut. Returns
     * the string unchanged when it already fits.
     *
     * <p>Here because three screens had grown their own copy — two of them a character-at-a-time
     * shrink loop that re-measures the whole string on every iteration. This is the single place
     * that decides what a truncated label looks like, which is the only way the mod's screens end
     * up cutting text the same way.
     *
     * <p>Screens that draw their own text should call this rather than relying on a widget to do
     * it: {@code StringWidget} only started scrolling overflowing text in 1.21, so on 1.20.1 the
     * same label that scrolls politely on one loader simply runs off the panel on the other.
     */
    public static String fit(net.minecraft.client.gui.Font font, String s, int room) {
        if (s == null || s.isEmpty()) return s;
        if (room <= 0) return "";
        if (font.width(s) <= room) return s;
        int ell = font.width("...");
        if (room <= ell) return "";
        return font.plainSubstrByWidth(s, room - ell) + "...";
    }

    // ---- Surfaces -----------------------------------------------------------
    public static int C_SCRIM;
    public static int C_PANEL;
    public static int C_INSET;

    // ---- Lines --------------------------------------------------------------
    public static int C_DIVIDER;

    // ---- Text ---------------------------------------------------------------
    // Four tiers, loudest to quietest. All are read against the same surface (the blurred world)
    // now that there is one style, which is what lets them be picked once and trusted everywhere.
    public static int C_HEADING;
    public static int C_TEXT;
    public static int C_MUTED;
    public static int C_FAINT;

    // ---- Highlight -----------------------------------------------------------
    //
    // What used to be five theme-derived accent variants driven by ten unlockable colour presets.
    // They are gone: a settings screen tinted in the player's chosen colour reads as a mod skin
    // rather than as part of the game, and ten presets meant every screen had to be legible
    // against all of them, which is why contrast bugs kept surfacing. One fixed pair, used only
    // by the in-world HUD, cannot clash with anything.
    public static final int C_HL     = 0xFFFFFFFF;   // active / speaking

    /**
     * The palette. There is exactly one.
     *
     * <p>There used to be four surfaces (a light container, near-black, stone grey, and no panel
     * at all) crossed with ten accent presets. Every screen therefore had to stay legible against
     * forty combinations, which is the actual reason contrast bugs kept appearing — a tone chosen
     * to read on one surface disappeared on another, and the sweeps kept finding text nobody
     * could see. One surface is not a limitation here, it is the whole point: the mod's screens
     * are settings screens, Minecraft draws settings screens one way, and matching it exactly is
     * what makes them look like part of the game rather than like a skin.
     *
     */
    public static void applyPalette() {
        // A dim over vanilla's backdrop, not instead of it.
        //
        // This was fully transparent on the reasoning that the game's own blurred backdrop was
        // enough. It is not: on the title screen the panorama is bright and busy, and in a world
        // the blur keeps plenty of colour, so grey buttons and white text sat on top of moving
        // scenery with nothing to separate them. That is what made the screens look cheap — not
        // the controls, which are vanilla's own, but the lack of any ground beneath them.
        // Vanilla's renderTransparentBackground uses ~0xC0101010; this is a little lighter so
        // the world still reads through it.
        C_SCRIM    = 0x99101010;
        C_PANEL    = 0xFF303030;   // opaque track behind the Codex's rank bar
        // 0x70000000, not 0x90101010. This one number is why the screens read as empty.
        //
        // RGB 0x101010 at alpha 0.565 contributes a FIXED +9.0 luma floor, so a well's luma is
        // 0.5647*ground + 9.035. Solve for the fixed point: at ground luma 16 the well and its
        // background are identical, and BELOW 16 the well is BRIGHTER than the surface it is
        // supposed to be cut into. In a cave it was 3 luma from its own ground; at night it
        // inverted. The only thing the eye could find was the 2px frame drawn around it - which
        // is exactly what "boxes around nothing" looks like.
        //
        // Pure black has no constant term: contrast is always 0.4392 * ground and can never
        // invert. And it is not a guess - vanilla's own inworld_menu_list_background.png is
        // 16x16 with all 256 pixels at #70000000. The mod had the right primitive and the wrong
        // number.
        C_INSET    = 0x70000000;   // wells are translucent black over the world
        C_DIVIDER  = 0x40FFFFFF;
        C_TEXT     = 0xFFFFFFFF;
        C_MUTED    = 0xFFA0A0A0;
        C_FAINT    = 0xFF808080;
        C_HEADING  = 0xFFFFFFFF;
        applyStatusColors();
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
    /** Status tones for text over the world. One surface, so one set. */
    private static void applyStatusColors() {
        F_MATCH   = 0xFF6BFFB0;
        F_DEDUP   = 0xFFFFD060;
        F_NOMATCH = 0xFF9892B8;
        C_DANGER  = 0xFFFF6166;
        C_SUCCESS = 0xFF6BFFB0;
        C_WARN    = 0xFFFFB347;
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




    // The header/footer separators are gone.
    //
    // They were meant to band a header, a body and a footer the way a vanilla options
    // screen does. Over a BLURRED backdrop that is not what they did: a 3px light-on-dark
    // sandwich sitting on a blur reads as two smeared streaks behind the content rather
    // than as chrome in front of it, and that is what they were reported as, twice.
    //
    // Vanilla can draw them because its options screens are not blurred behind their own
    // list. Ours are. The title and the button rows already establish the bands on their
    // own through spacing, which is how every 1.20.1 options screen works - that version
    // has no separators at all.
    //
    // headerY / footerY are still passed and still meaningful: screens position content
    // against them. Nothing is drawn at those rows any more.

    // screenChrome() went with the separators. Once nothing was drawn at headerY or footerY it
    // was a wrapper around ground() that ignored four of its six parameters, so every screen
    // calls ground() directly. Screens still compute those rows; they are layout anchors now,
    // not chrome.





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
     * Vanilla's backdrop plus the mod's dim - and the ONE place that dim is version-split.
     *
     * <p>On 1.21.1 the pair is correct and deliberate: {@code renderBackground} paints the
     * blurred world, and {@code C_SCRIM} is a little lighter than the ~0xC0101010 vanilla itself
     * uses, which is the value the author tuned on that loader.
     *
     * <p>On 1.20.1 it is double-counting. That version's {@code Screen.renderBackground} in a
     * level IS {@code fillGradient(0xC0101010, 0xD0101010)} - literally the constant the 1.21.1
     * comment cites as its reference - so filling the scrim again dimmed the world twice and made
     * Forge players 1.8x darker than NeoForge players from identical code. Ground luma at a
     * mid-grey world: 27.1 against 43.7.
     *
     * <p>1.20.1 also has no blur of any kind, so it now shows more of a SHARP world where 1.21.1
     * shows a soft one. That is where every vanilla 1.20.1 options screen already sits, because
     * on that loader this is now exactly vanilla's own backdrop and nothing else.
     */
    public static void ground(net.minecraft.client.gui.screens.Screen screen, GuiGraphics g,
                              int mouseX, int mouseY, float partialTick) {
        background(screen, g, mouseX, mouseY, partialTick);
//? if forge {
/*        // Deliberately nothing. See above: renderBackground has already dimmed it.
*///?} else {
        int w = net.minecraft.client.Minecraft.getInstance().getWindow().getGuiScaledWidth();
        int h = net.minecraft.client.Minecraft.getInstance().getWindow().getGuiScaledHeight();
        g.fill(0, 0, w, h, C_SCRIM);
//?}
    }

    /**
     * A recessed list surface, the way vanilla draws one.
     *
     * <p>Vanilla ships this as a 16x16 PNG whose 256 pixels are all {@code #70000000} - a flat
     * translucent black, shipped as a texture rather than a constant precisely so a resource pack
     * can restyle it. The 1.20.1 fill is therefore pixel-identical to the blit; the blit exists
     * on 1.21.1 only so a GUI pack reaches it, which is the same argument that turned this mod's
     * hand-painted buttons into real vanilla widgets.
     *
     * <p>1.20.1 ships no translucent GUI texture at all - its list ground is the OPAQUE dirt of
     * options_background.png, which over an in-world screen is worse than the fill at night.
     */
    public static void well(GuiGraphics g, int x, int y, int w, int h) {
//? if forge {
/*        g.fill(x, y, x + w, y + h, C_INSET);
*///?} else {
        com.mojang.blaze3d.systems.RenderSystem.enableBlend();
        g.blit(net.minecraft.resources.ResourceLocation.withDefaultNamespace(
                net.minecraft.client.Minecraft.getInstance().level == null
                    ? "textures/gui/menu_list_background.png"
                    : "textures/gui/inworld_menu_list_background.png"),
            x, y, 0.0F, 0.0F, w, h, 32, 32);
        com.mojang.blaze3d.systems.RenderSystem.disableBlend();
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
        // The surface is the blurred world, never a panel, so text is always drawn white AND
        // shadowed - the only way it stays readable over arbitrary scenery. Kept as a method
        // because ~40 call sites pass its result to drawString's shadow flag.
        return false;
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

    /**
     * A selected list row, exactly as {@code AbstractSelectionList.renderSelection} draws one:
     * an opaque outline over an opaque black interior. Byte-identical on both versions.
     *
     * <p>Two fills, not six. Vanilla's selected row is DARKER than its neighbours; the mod did
     * the opposite with a translucent white wash, which faded precisely when the backdrop was
     * busiest. Opaque means it computes the same over a bright sky and a cave.
     *
     * <p>{@code focused} picks vanilla's own two colours - white when the list has keyboard
     * focus, grey when it does not.
     */
    public static void rowSelection(GuiGraphics g, int x, int y, int w, int h, boolean focused) {
        g.fill(x, y, x + w, y + h, focused ? 0xFFFFFFFF : 0xFF808080);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, 0xFF000000);
    }

    /**
     * A tab, drawn with Minecraft's own tab art.
     *
     * <p>The config screen painted a black rectangle plus a 1px rule OVER a real vanilla Button
     * to say "not selected" - the most literal case in this codebase of hand-drawing chrome the
     * game already ships. Vanilla has all four states on both versions.
     *
     * <p>The sprites are translucent, which is why they work here at all: an unselected tab is
     * about 86% opaque and reads as recessed behind the content, while the selected one is
     * mostly transparent so it merges into the panel beneath it. That merge is the whole idiom -
     * their nine-slice border is {@code bottom: 0} precisely so the bottom edge is open.
     */
    public static void tab(GuiGraphics g, int x, int y, int w, int h,
                           boolean selected, boolean hovered) {
        if (w <= 0 || h <= 0) return;   // nothing to draw, and a zero dimension is a divisor below
//? if forge {
/*        // TabButton.getTextureY(): 0 selected, 1 selected+hovered, 2 plain, 3 hovered.
        int row = selected ? (hovered ? 1 : 0) : (hovered ? 3 : 2);
        // The last border is 1, where vanilla's own TabButton passes 0.
        //
        // Copying vanilla's arguments is right; copying them without its height was not. Vanilla
        // tabs are exactly 24 tall, which equals the sprite's vHeight, and blitNineSliced
        // short-circuits on that - it blits three pieces and never looks at the bottom border.
        // Our tabs are TAB_H = 20, so it takes the full nine-slice path instead, where the bottom
        // border IS the source height of a strip. blitRepeating then divides the destination
        // height by it, and Mth.positiveCeilDiv(_, 0) throws ArithmeticException - on every frame,
        // as soon as the config screen draws a single tab. The screen was unopenable on Forge.
        //
        // 1.20.1 only: 1.21's blitSprite reads nine-slice metadata from the sprite and handles a
        // zero border, which is why the branch below never had this problem and why it went
        // unnoticed - the config screen was only ever opened on NeoForge.
        //
        // 1px rather than 0 costs nothing visually: it is the bottom row of a tab sprite, which is
        // the open edge where the tab meets the panel, and it is transparent.
        g.blitNineSliced(new net.minecraft.resources.ResourceLocation("textures/gui/tab_button.png"),
            x, y, w, h, 2, 2, 2, 1, 130, 24, 0, row * 24);
*///?} else {
        com.mojang.blaze3d.systems.RenderSystem.enableBlend();
        g.blitSprite(net.minecraft.resources.ResourceLocation.withDefaultNamespace(
            selected ? (hovered ? "widget/tab_selected_highlighted" : "widget/tab_selected")
                     : (hovered ? "widget/tab_highlighted"          : "widget/tab")),
            x, y, w, h);
        com.mojang.blaze3d.systems.RenderSystem.disableBlend();
//?}
    }

    /**
     * Vanilla's list scrollbar, at vanilla's width.
     *
     * <p>Six pixels on both versions - a literal 6 in 1.21.1's AbstractSelectionList and
     * {@code int j = i + 6} in 1.20.1's. The mod drew a 2-3px stub with a thumb lit from ABOVE
     * (a light top row over mid grey over dark); the real sprite is a light body with its dark
     * edges on the RIGHT and BOTTOM. Three shades off and lit from the wrong side, which is
     * exactly the "slightly the wrong edges" that took the hand-drawn widgets out of this mod.
     *
     * <p>1.20.1 has no scroller sprite, but its own list draws the identical art with three
     * fills, so the split costs nothing in fidelity - only a resource pack can tell them apart,
     * which is the reason the blit is there at all.
     *
     * <p>Fully opaque, so this is the most blur-proof element in the mod - and it was the
     * flimsiest. It appears only when the list overflows, matching vanilla's scrollbarVisible().
     */
    public static void listScrollbar(GuiGraphics g, int x, int y, int h,
                                     int total, int visible, int scroll) {
        if (total <= visible) return;
        int thumbH = Math.max(8, Math.min(h, h * visible / total));
        // Vanilla clamps the thumb to 32..h-8. Guarded on h, because a list can be one row tall
        // here and that clamp would then invert.
        if (h >= 40) thumbH = net.minecraft.util.Mth.clamp(thumbH, 32, h - 8);
        int maxScroll = total - visible;
        int thumbY = y + (h - thumbH) * Math.min(scroll, maxScroll) / Math.max(1, maxScroll);
//? if forge {
/*        g.fill(x, y,      x + 6, y + h,                0xFF000000);
        g.fill(x, thumbY, x + 6, thumbY + thumbH,     0xFF808080);
        g.fill(x, thumbY, x + 5, thumbY + thumbH - 1, 0xFFC0C0C0);
*///?} else {
        com.mojang.blaze3d.systems.RenderSystem.enableBlend();
        g.blitSprite(net.minecraft.resources.ResourceLocation.withDefaultNamespace(
            "widget/scroller_background"), x, y, 6, h);
        g.blitSprite(net.minecraft.resources.ResourceLocation.withDefaultNamespace(
            "widget/scroller"), x, thumbY, 6, thumbH);
        com.mojang.blaze3d.systems.RenderSystem.disableBlend();
//?}
    }
}
