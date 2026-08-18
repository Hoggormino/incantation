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
 * <p>Locked values stay in the cycle and are marked in the label rather than by colour, so the
 * state survives on any palette and for colour-blind players. Callers are expected to no-op when
 * the player lands on one — the point is to let them SEE what they have not unlocked yet.
 */
public final class NeonCycle<T> extends Button {

    private final T[] values;
    private final Function<T, String> labeller;
    private final Function<T, Boolean> isLocked;
    private final Consumer<T> onChange;
    private int idx;

    private NeonCycle(int x, int y, int w, int h, T[] values, T initial,
                      Function<T, String> labeller, Function<T, Boolean> isLocked,
                      Consumer<T> onChange) {
        super(x, y, w, h, Component.empty(), b -> {}, Button.DEFAULT_NARRATION);
        this.values = values;
        this.labeller = labeller;
        this.isLocked = isLocked;
        this.onChange = onChange;
        int start = 0;
        for (int i = 0; i < values.length; i++) {
            if (values[i] != null && values[i].equals(initial)) { start = i; break; }
        }
        this.idx = start;
        refreshLabel();
    }

    public static <T> NeonCycle<T> of(int x, int y, int w, int h, T[] values, T initial,
                                       Function<T, String> labeller, Consumer<T> onChange) {
        return new NeonCycle<>(x, y, w, h, values, initial, labeller, null, onChange);
    }

    /** Variant with an "isLocked" predicate — locked values still appear in the cycle but are
     *  marked, and the surrounding code is expected to no-op when the user lands on one. */
    public static <T> NeonCycle<T> withLocks(int x, int y, int w, int h, T[] values, T initial,
                                              Function<T, String> labeller,
                                              Function<T, Boolean> isLocked,
                                              Consumer<T> onChange) {
        return new NeonCycle<>(x, y, w, h, values, initial, labeller, isLocked, onChange);
    }

    public T value() { return values[idx]; }

    /** The label carries both the value and whether it is locked. */
    private void refreshLabel() {
        if (values.length == 0) { setMessage(Component.empty()); return; }
        T v = values[idx];
        boolean locked = isLocked != null && Boolean.TRUE.equals(isLocked.apply(v));
        String text = labeller.apply(v);
        setMessage(Component.literal(locked ? "✗ " + text : text));
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
