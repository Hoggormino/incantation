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

        // Background: faint accent tint when ON so the state reads at a glance.
        int bg = value ? Theme.C_ACCENT_FAINT : Theme.C_INSET;
        g.fill(x, y, x + w, y + h, bg);

        if (hov && active) {
            g.fill(x + 2, y + 1,     x + w - 2, y + 2,     Theme.C_ACCENT_FAINT);
            g.fill(x + 2, y + h - 2, x + w - 2, y + h - 1, Theme.C_ACCENT_FAINT);
        }

        int border = !active ? Theme.C_DIVIDER
                   : (hov ? Theme.C_ACCENT : (value ? Theme.C_ACCENT_SOFT : Theme.C_BORDER));
        Theme.roundedFrame(g, x, y, w, h, border);

        String text = value ? "ON" : "OFF";
        int textColor = !active ? Theme.C_FAINT
                       : (value ? Theme.C_ACCENT_BRIGHT : Theme.C_MUTED);
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
}
