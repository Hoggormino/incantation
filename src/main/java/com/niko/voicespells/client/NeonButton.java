package com.niko.voicespells.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/**
 * Flat neon button matching the VoiceSpells theme. Vanilla MC buttons read as 2010-era chrome
 * against the midnight panels, so we override the rendering: dark inset background, soft border
 * that flips to neon accent on hover, and accent text on hover/focus. Click handling, focus,
 * narration and accessibility all inherit from {@link Button}.
 */
public final class NeonButton extends Button {

    private NeonButton(int x, int y, int w, int h, Component msg, OnPress onPress) {
        super(x, y, w, h, msg, onPress, Button.DEFAULT_NARRATION);
    }

    public static NeonButton of(int x, int y, int w, int h, Component msg, OnPress onPress) {
        return new NeonButton(x, y, w, h, msg, onPress);
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partial) {
        int x = getX(), y = getY(), w = getWidth(), h = getHeight();
        boolean hovered = isHoveredOrFocused();

        // Background: recessed surface, slightly more recessed when disabled. Both pull from
        // the theme so light-mode buttons aren't stuck with a hardcoded near-black fill.
        int bg = active ? Theme.C_INSET : Theme.C_INSET_2;
        g.fill(x, y, x + w, y + h, bg);

        // Hover halo lines just inside the border — soft neon glow.
        if (hovered && active) {
            g.fill(x + 2, y + 1,         x + w - 2, y + 2,     Theme.C_ACCENT_FAINT);
            g.fill(x + 2, y + h - 2,     x + w - 2, y + h - 1, Theme.C_ACCENT_FAINT);
        }

        // Soft rounded border. Neon when hovered, subtle violet otherwise.
        int border = !active ? Theme.C_DIVIDER : (hovered ? Theme.C_ACCENT : Theme.C_BORDER);
        Theme.roundedFrame(g, x, y, w, h, border);

        // Centered text. Accent-bright on hover lights it up without needing a fill.
        int textColor = !active ? Theme.C_FAINT
            : (hovered ? Theme.C_ACCENT_BRIGHT : Theme.C_TEXT);
        int textX = x + w / 2;
        int textY = y + (h - 8) / 2;
        g.drawCenteredString(Minecraft.getInstance().font, getMessage(), textX, textY, textColor);
    }
}
