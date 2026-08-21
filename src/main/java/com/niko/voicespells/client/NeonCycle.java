package com.niko.voicespells.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Multi-value cycle button — the same thing Video Settings uses for Graphics or Clouds.
 *
 * <p>A real {@link Button} now, rather than an {@code AbstractWidget} painting its own body. See
 * {@link NeonToggle} for why: a hand-drawn body is an imitation of a texture, it cannot follow a
 * resource pack, and vanilla already renders the genuine article on both loaders.
 *
 * <p>Left-click advances, right-click steps back, matching vanilla's own cycle buttons so the
 * muscle memory carries over. The current value IS the label, so every change updates the message.
 *
 */
public final class NeonCycle<T> extends Button {

    /** Option name shown before the value ("Corner: Bottom Right"), or blank for value only. */
    private final String name;
    private final T[] values;
    private final Function<T, String> labeller;
    private final Consumer<T> onChange;
    private int idx;

    private NeonCycle(int x, int y, int w, int h, String name, T[] values, T initial,
                      Function<T, String> labeller, Consumer<T> onChange) {
        super(x, y, w, h, Component.empty(), b -> {}, Button.DEFAULT_NARRATION);
        this.name = name;
        this.values = values;
        this.labeller = labeller;
        this.onChange = onChange;
        int start = 0;
        for (int i = 0; i < values.length; i++) {
            if (values[i] != null && values[i].equals(initial)) { start = i; break; }
        }
        this.idx = start;
        refreshLabel();
    }

    /** Self-labelling: reads "Corner: Bottom Right", the way every vanilla cycle option does. */
    public static <T> NeonCycle<T> named(int x, int y, int w, int h, String name, T[] values,
                                          T initial, Function<T, String> labeller,
                                          Consumer<T> onChange) {
        return new NeonCycle<>(x, y, w, h, name, values, initial, labeller, onChange);
    }

    public T value() { return values[idx]; }

    /** The value IS the label, so every change goes through here. */
    private void refreshLabel() {
        if (values.length == 0) { setMessage(Component.empty()); return; }
        // Same rule as NeonToggle: the NAME is plain and the VALUE carries the colour, so a row
        // of these can be skimmed for what is set rather than read word by word.
        Component value = Component.literal(labeller.apply(values[idx]))
            .withStyle(net.minecraft.ChatFormatting.AQUA);
        setMessage(name.isEmpty() ? value : Component.literal(name + ": ").append(value));
    }

    private void step(int delta) {
        if (!active || values.length == 0) return;
        idx = (idx + delta + values.length) % values.length;
        refreshLabel();
        onChange.accept(values[idx]);
    }

    @Override
    public void onPress() {
        step(1);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (active && button == 1 && isMouseOver(mx, my)) {   // right-click steps back
            step(-1);
            playDownSound(Minecraft.getInstance().getSoundManager());
            return true;
        }
        return super.mouseClicked(mx, my, button);
    }
}
