package com.niko.voicespells.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Multi-value cycle button styled to match {@link NeonButton}. Drop-in replacement for vanilla's
 * grey {@code CycleButton.builder(...)} when cycling through an enum or fixed list of values.
 *
 * Left-click advances to the next value, right-click goes back one (matches vanilla CycleButton
 * convention so the muscle memory carries over).
 */
public final class NeonCycle<T> extends AbstractWidget {
    /** Vanilla's button body tone. Fixed, NOT derived from the panel: a vanilla button keeps
     *  the same dark grey whether it sits on a light container panel or a dark options screen,
     *  which is what lets its white text stay readable everywhere. */
    private static final int BTN        = 0xFF6C6C6C;
    private static final int BTN_HOVER  = 0xFF8A8A8A;
    private static final int BTN_OFF    = 0xFF4A4A4A;


    private final T[] values;
    private final Function<T, String> labeller;
    private final Function<T, Boolean> isLocked;
    private final Consumer<T> onChange;
    private int idx;

    private NeonCycle(int x, int y, int w, int h, T[] values, T initial,
                      Function<T, String> labeller, Function<T, Boolean> isLocked,
                      Consumer<T> onChange) {
        super(x, y, w, h, Component.empty());
        this.values = values;
        this.labeller = labeller;
        this.isLocked = isLocked;
        this.onChange = onChange;
        int start = 0;
        for (int i = 0; i < values.length; i++) {
            if (values[i] != null && values[i].equals(initial)) { start = i; break; }
        }
        this.idx = start;
    }

    public static <T> NeonCycle<T> of(int x, int y, int w, int h, T[] values, T initial,
                                       Function<T, String> labeller, Consumer<T> onChange) {
        return new NeonCycle<>(x, y, w, h, values, initial, labeller, null, onChange);
    }

    /** Variant with an "isLocked" predicate — locked values still appear in the cycle but
     *  render dim + with a lock prefix, and the surrounding code is expected to no-op when
     *  the user lands on one. Lets the player see what's locked without applying it. */
    public static <T> NeonCycle<T> withLocks(int x, int y, int w, int h, T[] values, T initial,
                                              Function<T, String> labeller,
                                              Function<T, Boolean> isLocked,
                                              Consumer<T> onChange) {
        return new NeonCycle<>(x, y, w, h, values, initial, labeller, isLocked, onChange);
    }

    public T value() { return values[idx]; }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partial) {
        int x = getX(), y = getY(), w = getWidth(), h = getHeight();
        boolean hov = isHoveredOrFocused();
        boolean locked = isLocked != null && isLocked.apply(values[idx]);

        // A cycling option button, drawn like vanilla's — which is exactly what this is
        // (Video Settings cycles Graphics/Clouds/Attack Indicator the same way). Locked values
        // render sunken and dimmed rather than merely darker, so "you cannot pick this" is
        // carried by shape as well as by the leading mark and the grey text.
        int fill = (!active || locked) ? BTN_OFF : (hov ? BTN_HOVER : BTN);
        g.fill(x, y, x + w, y + h, fill);
        bevel(g, x, y, w, h, fill, locked || !active);
        // No accent underline, matching NeonButton: vanilla's hover cue is the brighter
        // fill and the pale yellow label, and the SVC-style container look has no accent.

        // Label with a leading "✗ " when locked so the state reads at a glance.
        String text = labeller.apply(values[idx]);
        if (locked) text = "✗ " + text;
        int textColor = (!active || locked) ? 0xFFA0A0A0 : (hov ? 0xFFFFFFA0 : 0xFFFFFFFF);
        // Center the label, with a tiny "‹ ›" hint at each edge that brightens on hover so the
        // affordance is obvious ("click to cycle").
        Minecraft mc = Minecraft.getInstance();
        int textX = x + w / 2;
        int textY = y + (h - 8) / 2;
        g.drawCenteredString(mc.font, Component.literal(text), textX, textY, textColor);
        // Fixed greys, for the same reason the label above uses them: these chevrons are drawn
        // on the BUTTON, whose body is a fixed vanilla grey, not on the panel. Theme.C_FAINT is a
        // panel text tone — on the light default palette it is 0x757575, i.e. grey chevrons on a
        // grey button, which is invisible. The accent is kept for hover because the accent comes
        // from the preset rather than the palette and stays readable on the button.
        int chevronColor = locked ? 0xFF8A8A8A
                         : hov    ? Theme.C_ACCENT_BRIGHT
                                  : 0xFFD0D0D0;
        g.drawString(mc.font, Component.literal("‹"), x + 4,     textY, chevronColor, true);
        g.drawString(mc.font, Component.literal("›"), x + w - 8, textY, chevronColor, true);
    }

    @Override
    public void onClick(double mx, double my) {
        if (!active || values.length == 0) return;
        idx = (idx + 1) % values.length;
        onChange.accept(values[idx]);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (!active) return false;
        if (isMouseOver(mx, my)) {
            if (button == 1) { // right-click goes back
                idx = (idx - 1 + values.length) % values.length;
                onChange.accept(values[idx]);
                playDownSound(Minecraft.getInstance().getSoundManager());
                return true;
            }
        }
        return super.mouseClicked(mx, my, button);
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
