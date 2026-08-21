package com.niko.voicespells.client;

import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/**
 * A button that reads as part of Minecraft.
 *
 * <p>This class used to deliberately reject the vanilla look — its own javadoc argued that
 * "vanilla MC buttons read as 2010-era chrome against the midnight panels", and it drew a flat
 * recessed rectangle with a neon hover halo instead. That is a reasonable opinion about web
 * design and the wrong one for a Minecraft mod: the whole point of the game's GUI is that every
 * control looks like the same physical object, and a flat neon rectangle sitting next to a
 * vanilla button is instantly identifiable as foreign.
 *
 * <p>It now renders the way a vanilla button does — a raised bevel, a lighter fill on hover, a
 * darkened fill when disabled, and vanilla's own three text colours (white normally, the pale
 * yellow 0xFFFFA0 on hover, grey when inactive). The accent survives as a single seated
 * underline on hover, which is the one place the player's chosen theme still shows through.
 *
 * <p>The name is unchanged because it is referenced across every screen; only the pixels moved.
 * Click handling, focus, narration and accessibility still come from {@link Button}.
 */
public final class NeonButton extends Button {
    private NeonButton(int x, int y, int w, int h, Component msg, OnPress onPress) {
        super(x, y, w, h, msg, onPress, Button.DEFAULT_NARRATION);
    }

    public static NeonButton of(int x, int y, int w, int h, Component msg, OnPress onPress) {
        return new NeonButton(x, y, w, h, msg, onPress);
    }

    // No renderWidget override, and no bevel/shade helpers either.
    //
    // Those drew a flat fill plus hand-computed edge lines: a careful imitation of a Minecraft
    // button, which is not the same as one. The real control is a nine-sliced texture, so the
    // imitation always sat at slightly the wrong tone with slightly the wrong edges next to a
    // vanilla control — and it could not follow a resource pack, so a player with a GUI pack
    // watched every vanilla button restyle while ours stayed put. Vanilla's own renderWidget
    // draws the genuine sprite, with its hover and disabled variants and its label colours, and
    // it already differs correctly between 1.20.1 and 1.21.1 so the version split disappears too.
    //
    // The class stays because every screen references it, and because `of(...)` is a shorter
    // constructor than Button.builder(...).bounds(...).build() at 40-odd call sites.
}
