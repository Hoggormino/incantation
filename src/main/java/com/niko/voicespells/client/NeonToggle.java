package com.niko.voicespells.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

/**
 * Boolean ON/OFF toggle styled to match {@link NeonButton}. Drop-in replacement for vanilla's
 * grey {@code CycleButton.onOffBuilder(...)}.
 *
 * Visuals:
 *  - OFF : recessed inset background, muted "OFF" label
 *  - ON  : faint accent fill, bright accent "ON" label
 *  - hover: neon-purple border + the soft halo lines used elsewhere
 */
public final class NeonToggle extends AbstractWidget {
    /** Vanilla's button body tone. Fixed, NOT derived from the panel: a vanilla button keeps
     *  the same dark grey whether it sits on a light container panel or a dark options screen,
     *  which is what lets its white text stay readable everywhere. */
    private static final int BTN        = 0xFF6C6C6C;
    private static final int BTN_HOVER  = 0xFF8A8A8A;
    private static final int BTN_OFF    = 0xFF4A4A4A;


    private boolean value;
    private final Consumer<Boolean> onChange;

    private NeonToggle(int x, int y, int w, int h, boolean initial, Consumer<Boolean> onChange) {
        super(x, y, w, h, Component.empty());
        this.value = initial;
        this.onChange = onChange;
    }

    public static NeonToggle of(int x, int y, int w, int h, boolean initial, Consumer<Boolean> onChange) {
        return new NeonToggle(x, y, w, h, initial, onChange);
    }

    public boolean value() { return value; }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partial) {
        int x = getX(), y = getY(), w = getWidth(), h = getHeight();
        boolean hov = isHoveredOrFocused();

        // Rendered as a vanilla button, because that is what it is. Vanilla signals a binary
        // option with the LABEL ("Option: ON" / "Option: OFF"), not with a coloured pill — see
        // every toggle in Video Settings. A faint accent tint plus a neon border was doing the
        // job with colour that vanilla does with words, which is exactly the kind of thing that
        // makes a modded screen feel like a different application.
        //
        // A pressed toggle is drawn sunken, so ON/OFF is also readable without relying on hue —
        // which matters for colour-blind players and on the light SLATE palette.
        int fill = !active ? BTN_OFF : (hov ? BTN_HOVER : BTN);
        g.fill(x, y, x + w, y + h, fill);
        bevel(g, x, y, w, h, fill, value || !active);

        if (hov && active) g.fill(x + 2, y + h - 2, x + w - 2, y + h - 1, Theme.C_ACCENT);

        String text = value ? "ON" : "OFF";
        int textColor = !active ? 0xFFA0A0A0 : (hov ? 0xFFFFFFA0 : 0xFFFFFFFF);
        int textX = x + w / 2;
        int textY = y + (h - 8) / 2;
        g.drawCenteredString(Minecraft.getInstance().font, Component.literal(text), textX, textY, textColor);
    }

    @Override
    public void onClick(double mx, double my) {
        if (!active) return;
        value = !value;
        onChange.accept(value);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput n) {}

    /** Vanilla bevel keyed off the widget's own fill. Mirrors NeonButton so the two never drift. */
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
