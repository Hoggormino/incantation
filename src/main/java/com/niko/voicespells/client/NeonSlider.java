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

        // Vanilla slider construction: a dark sunken track the full width, a raised
        // button-style handle riding on it, and the label drawn over the middle in white.
        // Sliders are buttons in vanilla — they keep the dark widget tone no matter what
        // panel they sit on, which is what keeps the white label readable. The previous
        // version was an accent-filled progress bar with a neon thumb, a web idiom;
        // vanilla sliders show position with the handle alone, not a coloured fill.
        g.fill(x, y, x + w, y + h, 0xFF2B2B2B);
        g.fill(x, y, x + w, y + 1, 0xFF000000);
        g.fill(x, y + h - 1, x + w, y + h, 0xFF000000);
        g.fill(x, y, x + 1, y + h, 0xFF000000);
        g.fill(x + w - 1, y, x + w, y + h, 0xFF000000);
        g.fill(x + 1, y + 1, x + w - 1, y + 2, 0xFF1A1A1A);   // sunken top shadow

        int handleW = 8;
        int handleX = x + 1 + (int) (this.value * (w - 2 - handleW));
        int hFill = hov && active ? 0xFF8A8A8A : 0xFF6C6C6C;
        g.fill(handleX, y + 1, handleX + handleW, y + h - 1, hFill);
        g.fill(handleX, y + 1, handleX + handleW, y + 2, 0xFF9E9E9E);
        g.fill(handleX, y + h - 2, handleX + handleW, y + h - 1, 0xFF3F3F3F);
        g.fill(handleX, y + 1, handleX + 1, y + h - 1, 0xFF9E9E9E);
        g.fill(handleX + handleW - 1, y + 1, handleX + handleW, y + h - 1, 0xFF3F3F3F);

        int textColor = !active ? 0xFFA0A0A0 : (hov ? 0xFFFFFFA0 : 0xFFFFFFFF);
        g.drawCenteredString(Minecraft.getInstance().font, getMessage(),
            x + w / 2, y + (h - 8) / 2, textColor);
    }
}
