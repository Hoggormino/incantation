package com.niko.voicespells.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/**
 * A button that reads as part of Minecraft.
 *
 * <p>This class used to deliberately reject the vanilla look — its own javadoc argued that
 * "vanilla MC buttons read as 2010-era chrome against the midnight panels", and it drew a flat
 * recessed rectangle with a neon hover halo instead. That is a reasonable opinion about web
 * design and the wrong one for a Minecraft mod: the whole point of the game's GUI is that every
 * control looks like the same physical object, and a flat neon rectangle sitting next to a
 * vanilla button is instantly identifiable as foreign.
 *
 * <p>It now renders the way a vanilla button does — a raised bevel, a lighter fill on hover, a
 * darkened fill when disabled, and vanilla's own three text colours (white normally, the pale
 * yellow 0xFFFFA0 on hover, grey when inactive). The accent survives as a single seated
 * underline on hover, which is the one place the player's chosen theme still shows through.
 *
 * <p>The name is unchanged because it is referenced across every screen; only the pixels moved.
 * Click handling, focus, narration and accessibility still come from {@link Button}.
 */
public final class NeonButton extends Button {

    /** Vanilla's hovered-button text tint. */
    private static final int TEXT_HOVER = 0xFFFFFFA0;

    private NeonButton(int x, int y, int w, int h, Component msg, OnPress onPress) {
        super(x, y, w, h, msg, onPress, Button.DEFAULT_NARRATION);
    }

    public static NeonButton of(int x, int y, int w, int h, Component msg, OnPress onPress) {
        return new NeonButton(x, y, w, h, msg, onPress);
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partial) {
        int x = getX(), y = getY(), w = getWidth(), h = getHeight();
        boolean hovered = isHoveredOrFocused() && active;

        // Fill derived from the panel tone so all three palettes stay coherent: a button is a
        // raised sibling of the panel, brighter on hover, flatter when dead.
        int fill = !active ? shade(Theme.C_PANEL, -0.18f)
                 : hovered ? shade(Theme.C_PANEL, 0.30f)
                           : shade(Theme.C_PANEL, 0.12f);
        g.fill(x, y, x + w, y + h, fill);

        // The vanilla bevel: black outline, light top/left, dark bottom/right. Inactive buttons
        // are drawn sunken, which is how vanilla communicates "not pressable" without colour.
        bevel(g, x, y, w, h, fill, !active);

        // One accent touch, seated like a vanilla underline rather than a glow.
        if (hovered) g.fill(x + 2, y + h - 2, x + w - 2, y + h - 1, Theme.C_ACCENT);

        int textColor = !active ? 0xFFA0A0A0 : (hovered ? TEXT_HOVER : 0xFFFFFFFF);
        g.drawCenteredString(Minecraft.getInstance().font, getMessage(),
            x + w / 2, y + (h - 8) / 2, textColor);
    }

    /** Local bevel so the button can key its edges off its own fill rather than the panel's. */
    private static void bevel(GuiGraphics g, int x, int y, int w, int h, int base, boolean sunken) {
        int hi = sunken ? shade(base, -0.35f) : shade(base, 0.35f);
        int lo = sunken ? shade(base, 0.20f) : shade(base, -0.40f);
        g.fill(x, y, x + w, y + 1, 0xFF000000);
        g.fill(x, y + h - 1, x + w, y + h, 0xFF000000);
        g.fill(x, y, x + 1, y + h, 0xFF000000);
        g.fill(x + w - 1, y, x + w, y + h, 0xFF000000);
        g.fill(x + 1, y + 1, x + w - 1, y + 2, hi);
        g.fill(x + 1, y + 1, x + 2, y + h - 1, hi);
        g.fill(x + 1, y + h - 2, x + w - 1, y + h - 1, lo);
        g.fill(x + w - 2, y + 1, x + w - 1, y + h - 1, lo);
    }

    /** Shift an ARGB toward white (positive f) or black (negative f), preserving alpha. */
    private static int shade(int argb, float f) {
        int a = (argb >>> 24) & 0xFF;
        int r = (argb >> 16) & 0xFF, gg = (argb >> 8) & 0xFF, b = argb & 0xFF;
        if (f >= 0) {
            r = (int) (r + (255 - r) * f);
            gg = (int) (gg + (255 - gg) * f);
            b = (int) (b + (255 - b) * f);
        } else {
            float k = 1 + f;
            r = (int) (r * k); gg = (int) (gg * k); b = (int) (b * k);
        }
        return (a << 24) | (r << 16) | (gg << 8) | b;
    }
}
