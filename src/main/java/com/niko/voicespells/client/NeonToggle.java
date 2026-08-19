package com.niko.voicespells.client;

import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

/**
 * Boolean ON/OFF toggle. A real {@link Button} carrying an ON/OFF label.
 *
 * <p>This used to extend {@code AbstractWidget} and paint its own body: a flat fill plus bevel
 * lines computed in code. That is a good imitation of a Minecraft button and it is not a Minecraft
 * button — the real one is a nine-sliced texture, so a hand-drawn one sits next to a vanilla
 * control at slightly the wrong tone, with slightly the wrong edges, and reads as foreign no
 * matter how carefully the numbers are chosen. Worse, the flat version cannot follow a resource
 * pack: a player with a custom GUI pack saw every vanilla button restyle and ours stay put.
 *
 * <p>Extending {@link Button} and drawing nothing ourselves means the game renders this with its
 * own widget sprite, its own hover and disabled variants, its own label colours, and whatever
 * texture the active resource pack supplies. It also costs less code than the imitation did, and
 * the version split disappears: the sprite lookup lives in vanilla, which already differs
 * correctly between 1.20.1 and 1.21.1.
 *
 * <p>Vanilla communicates a binary option through the LABEL — "Option: ON" — rather than through
 * colour, which is why the value is rendered as text here instead of as a tinted pill.
 */
public final class NeonToggle extends Button {

    private boolean value;
    private final String name;
    private final Consumer<Boolean> onChange;

    private NeonToggle(int x, int y, int w, int h, String name, boolean initial,
                       Consumer<Boolean> onChange) {
        super(x, y, w, h, label(name, initial), b -> {}, Button.DEFAULT_NARRATION);
        this.name = name;
        this.value = initial;
        this.onChange = onChange;
    }

    /** Bare ON/OFF, for layouts that put the option name in a separate label. */
    public static NeonToggle of(int x, int y, int w, int h, boolean initial, Consumer<Boolean> onChange) {
        return new NeonToggle(x, y, w, h, "", initial, onChange);
    }

    /**
     * Self-labelling form: the control reads "Option: ON".
     *
     * <p>This is how Minecraft states an option — Video Settings has no separate label column,
     * every setting is one full-width button carrying its own name and value. Doing the same
     * removes a whole column of cramped left-hand text, lets each control be twice as wide, and
     * makes the option and its value a single click target instead of two aligned pieces.
     */
    public static NeonToggle named(int x, int y, int w, int h, String name, boolean initial,
                                   Consumer<Boolean> onChange) {
        return new NeonToggle(x, y, w, h, name, initial, onChange);
    }

    /**
     * "Name: VALUE", with the value carrying the colour.
     *
     * <p>Every control being the same grey made the screen read as a wall of identical boxes with
     * no way to skim it. Colouring only the VALUE - green for on, grey for off - means a glance
     * tells you what is enabled without reading a word, and it costs no extra chrome: the styling
     * rides on the Component, which vanilla's own button renderer already draws.
     */
    private static Component label(String name, boolean v) {
        Component state = Component.literal(v ? "ON" : "OFF")
            .withStyle(v ? net.minecraft.ChatFormatting.GREEN : net.minecraft.ChatFormatting.GRAY);
        if (name.isEmpty()) return state;
        return Component.literal(name + ": ").append(state);
    }

    public boolean value() { return value; }

    /** Flip on click. The message is the value, so it has to be updated with it. */
    @Override
    public void onPress() {
        if (!active) return;
        value = !value;
        setMessage(label(name, value));
        onChange.accept(value);
    }
}
