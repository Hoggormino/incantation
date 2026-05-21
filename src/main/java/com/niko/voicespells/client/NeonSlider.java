package com.niko.voicespells.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;
import java.util.function.IntFunction;

/**
 * Neon-styled integer slider — replacement for vanilla {@link AbstractSliderButton}'s grey
 * chrome. Drag or click+arrow to move. Drawn as a progress-bar style fill (left of the thumb is
 * accent, right is recessed) with the formatted label centered on top.
 *
 * Logic borrows from {@link AbstractSliderButton}: {@code value} is normalised 0..1, the
 * concrete int comes from {@code current()}.
 */
public final class NeonSlider extends AbstractSliderButton {

    private final int min, max;
    private final IntFunction<String> label;
    private final Consumer<Integer> onChange;

    public NeonSlider(int x, int y, int w, int h, int initial, int min, int max,
                      IntFunction<String> label, Consumer<Integer> onChange) {
        super(x, y, w, h, Component.empty(), (initial - min) / (double) Math.max(1, (max - min)));
        this.min = min;
        this.max = max;
        this.label = label;
        this.onChange = onChange;
        updateMessage();
    }

    private int current() { return (int) Math.round(min + value * (max - min)); }

    @Override protected void updateMessage() {
        setMessage(Component.literal(label.apply(current())));
    }
    @Override protected void applyValue() {
        onChange.accept(current());
    }

    @Override
    public void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partial) {
        int x = getX(), y = getY(), w = getWidth(), h = getHeight();
        boolean hov = isHoveredOrFocused();

        // Recessed track background.
        g.fill(x, y, x + w, y + h, Theme.C_INSET);

        // Progress fill from the left up to the current position.
        int fillW = (int) (this.value * (w - 4));
        if (fillW > 0) {
            g.fill(x + 2, y + 2, x + 2 + fillW, y + h - 2, Theme.C_ACCENT_FAINT);
        }

        // Thumb — a narrow neon column where the value sits.
        int thumbX = x + 2 + Math.max(0, fillW - 2);
        int thumbW = 4;
        g.fill(thumbX, y + 1, thumbX + thumbW, y + h - 1,
            hov ? Theme.C_ACCENT_BRIGHT : Theme.C_ACCENT);

        // Soft hover halo.
        if (hov && active) {
            g.fill(x + 2, y + 1,     x + w - 2, y + 2,     Theme.C_ACCENT_FAINT);
            g.fill(x + 2, y + h - 2, x + w - 2, y + h - 1, Theme.C_ACCENT_FAINT);
        }

        int border = !active ? Theme.C_DIVIDER
                   : (hov ? Theme.C_ACCENT : Theme.C_BORDER);
        Theme.roundedFrame(g, x, y, w, h, border);

        // Label centered on top of the bar.
        int textColor = !active ? Theme.C_FAINT
                       : (hov ? Theme.C_ACCENT_BRIGHT : Theme.C_TEXT);
        int textX = x + w / 2;
        int textY = y + (h - 8) / 2;
        g.drawCenteredString(Minecraft.getInstance().font, getMessage(), textX, textY, textColor);
    }
}
