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

    // No renderWidget override. AbstractSliderButton already draws vanilla's slider — the
    // nine-sliced track and the real handle sprite, in whatever the active resource pack
    // supplies. The hand-drawn version here was a fill plus edge lines: a good imitation, still
    // an imitation, and it drew a near-black trough that looked like a hole punched in a light
    // container panel. Vanilla's own is correct on both 1.20.1 and 1.21.1 with no version split.
}
