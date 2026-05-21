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

        // Locked chips get a darker background so it's obvious they're not the equipped value.
        g.fill(x, y, x + w, y + h, locked ? Theme.C_PANEL : Theme.C_INSET);
        if (hov && active && !locked) {
            g.fill(x + 2, y + 1,     x + w - 2, y + 2,     Theme.C_ACCENT_FAINT);
            g.fill(x + 2, y + h - 2, x + w - 2, y + h - 1, Theme.C_ACCENT_FAINT);
        }
        int border = !active ? Theme.C_DIVIDER
                   : locked  ? Theme.C_DIVIDER
                   : (hov ? Theme.C_ACCENT : Theme.C_BORDER);
        Theme.roundedFrame(g, x, y, w, h, border);

        // Label with a leading "✗ " when locked so the state reads at a glance.
        String text = labeller.apply(values[idx]);
        if (locked) text = "✗ " + text;
        int textColor = !active ? Theme.C_FAINT
                       : locked ? Theme.C_FAINT     // distinctly dim for locked
                       : (hov ? Theme.C_ACCENT_BRIGHT : Theme.C_TEXT);
        // Center the label, with a tiny "‹ ›" hint at each edge that brightens on hover so the
        // affordance is obvious ("click to cycle").
        Minecraft mc = Minecraft.getInstance();
        int textX = x + w / 2;
        int textY = y + (h - 8) / 2;
        g.drawCenteredString(mc.font, Component.literal(text), textX, textY, textColor);
        int chevronColor = locked ? Theme.C_FAINT
                         : hov    ? Theme.C_ACCENT_BRIGHT
                                  : Theme.C_FAINT;
        g.drawString(mc.font, Component.literal("‹"), x + 4,     textY, chevronColor, false);
        g.drawString(mc.font, Component.literal("›"), x + w - 8, textY, chevronColor, false);
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
}
