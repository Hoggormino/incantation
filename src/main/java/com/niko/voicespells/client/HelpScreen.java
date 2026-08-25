package com.niko.voicespells.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * Multi-page in-game guide. Plain flat help screen — no entities, no images, just text +
 * the existing accent rule chrome. Pagination via the bottom Prev/Next pair, no scrolling
 * required.
 *
 * Topics: what the mod is, the no-keybind workflow, how matching works, aliases + loadouts,
 * the rest of the More menu, troubleshooting via Diagnostics.
 */
public final class HelpScreen extends Screen {

    // Preferred dimensions — clamped at init() time so the panel stays inside the screen
    // at every Minecraft GUI Scale. Layout math uses the runtime {@code panelW} / {@code panelH}
    // fields, not these constants.
    private static final int PANEL_W_PREF = 460;
    private static final int PANEL_H_PREF = 320;

    private final Screen parent;
    private int page = 0;
    /** Top-left of the runtime panel + clamped dimensions; recomputed every {@link #init()}. */
    private int px, py, panelW, panelH;

    /** Marks a page line as a lang key rather than copy to draw as-is. */
    private static final String KEY_PREFIX = "voicespells.";

    /**
     * Page content. Each page is a heading key + body lines, and every line is a lang key so the
     * guide can be translated. Two kinds of entry are not keys and are drawn verbatim: an empty
     * string, which is a blank row, and the loadout sample on page 4, which is TOML syntax and
     * spell ids rather than prose. {@link #line(String)} tells them apart.
     */
    private static final String[][] PAGES = {
        {
            "voicespells.help.p1.heading",
            "voicespells.help.p1.1",
            "voicespells.help.p1.2",
            "voicespells.help.p1.3",
            "voicespells.help.p1.4",
            "",
            "voicespells.help.p1.6",
            "voicespells.help.p1.7",
            "voicespells.help.p1.8",
            "",
            "voicespells.help.p1.10",
            "voicespells.help.p1.11",
            "voicespells.help.p1.12"
        },
        {
            "voicespells.help.p2.heading",
            "voicespells.help.p2.1",
            "voicespells.help.p2.2",
            "",
            "voicespells.help.p2.4",
            "voicespells.help.p2.5",
            "voicespells.help.p2.6",
            "voicespells.help.p2.7",
            "voicespells.help.p2.8",
            "",
            "voicespells.help.p2.10",
            "voicespells.help.p2.11",
            "voicespells.help.p2.12",
            "voicespells.help.p2.13"
        },
        {
            "voicespells.help.p3.heading",
            "voicespells.help.p3.1",
            "voicespells.help.p3.2",
            "voicespells.help.p3.3",
            "voicespells.help.p3.4",
            "voicespells.help.p3.5",
            "",
            "voicespells.help.p3.7",
            "voicespells.help.p3.8",
            "voicespells.help.p3.9",
            "voicespells.help.p3.10",
            "",
            "voicespells.help.p3.12",
            "voicespells.help.p3.13",
            "voicespells.help.p3.14"
        },
        {
            "voicespells.help.p4.heading",
            "voicespells.help.p4.1",
            "voicespells.help.p4.2",
            "voicespells.help.p4.3",
            "",
            "  loadouts = [",
            "    \"offense=irons_spellbooks:fireball,",
            "      irons_spellbooks:lightning_lance\"",
            "  ]",
            "",
            "voicespells.help.p4.10",
            "voicespells.help.p4.11",
            "voicespells.help.p4.12"
        },
        {
            "voicespells.help.p5.heading",
            "voicespells.help.p5.1",
            "voicespells.help.p5.2",
            "voicespells.help.p5.3",
            "voicespells.help.p5.4",
            "",
            "voicespells.help.p5.6",
            "voicespells.help.p5.7",
            "voicespells.help.p5.8",
            "",
            "voicespells.help.p5.10",
            "voicespells.help.p5.11",
            "",
            "voicespells.help.p5.13"
        }
    };

    /**
     * Resolves one page line. A lang key becomes translated text; anything else — a blank row,
     * the loadout sample — is drawn exactly as written, because it carries ids and config syntax
     * a translator must not touch.
     */
    private static Component line(String s) {
        return s.startsWith(KEY_PREFIX) ? Component.translatable(s) : Component.literal(s);
    }

    public HelpScreen(Screen parent) {
        super(Component.translatable("voicespells.help.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        // Clamp to fit the current screen so large GUI Scale settings don't push buttons off
        // the bottom or sides. The preferred dimensions still apply when there's enough room.
        panelW = Theme.fit(PANEL_W_PREF, width);
        panelH = Theme.fit(PANEL_H_PREF, height);
        px = (width - panelW) / 2;
        py = (height - panelH) / 2;

        StringWidget titleW = new StringWidget(px, py + (Theme.HEADER_H - 9) / 2,
            panelW, 9, title, font);
        titleW.alignCenter();
        titleW.setColor(Theme.C_TEXT);
        addRenderableWidget(titleW);

        int btnY = py + panelH - 28;
        int btnW = 90;

        addRenderableWidget(NeonButton.of(px + Theme.PAD, btnY, btnW, 20,
            Component.translatable("voicespells.help.prev"), b -> { if (page > 0) { page--; rebuildWidgets(); } }))
            .active = page > 0;
        addRenderableWidget(NeonButton.of(px + Theme.PAD + btnW + 6, btnY, btnW, 20,
            Component.translatable("voicespells.help.next"), b -> { if (page < PAGES.length - 1) { page++; rebuildWidgets(); } }))
            .active = page < PAGES.length - 1;
        addRenderableWidget(NeonButton.of(px + panelW - Theme.PAD - 80, btnY, 80, 20,
            CommonComponents.GUI_BACK, b -> onClose()));
    }

    @Override
    public void onClose() {
        if (minecraft != null) minecraft.setScreen(parent);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        // Vanilla backdrop first (blurred world in-game, dirt on the title screen, and it
        // honours the player's Menu Background Blur setting), then our own dim on top so
        // the panel still reads. Painting only a flat scrim, as this did before, opted out
        // of all of that and was a large part of why the screens felt foreign.
        Theme.ground(this, g, mouseX, mouseY, partial);

        // The body sits in a well, like every other content screen. Without one this page was
        // bare text on the backdrop - the guide is the screen a confused player is sent to, and
        // it was the one that looked least like part of the mod.
        int bodyTop = py + Theme.HEADER_H + 18;
        // Inset PAD, matching the copy inside it, and stopping at btnY - GAP_SM so the frame does
        // not run under the Prev/Next/Back row - it was crossing 4px into the button tops.
        int wellBottom = py + panelH - 34;
        Theme.well(g, px + Theme.PAD, bodyTop, panelW - Theme.PAD * 2, wellBottom - bodyTop);

        super.render(g, mouseX, mouseY, partial);


        // "Page 1 of 5", which is what Minecraft itself writes at the top of a written book.
        //
        // This was a row of indicator dots — a website idiom that appears nowhere in the game,
        // and one that had no readable colour on either surface: the accent tones vanished on
        // the light container palette and the inactive dots were barely there on the dark one. A
        // count says the same thing in words, works on any background, and is the form a player
        // has already seen in vanilla.
        String pageLabel = "Page " + (page + 1) + " of " + PAGES.length;
        g.drawCenteredString(font, pageLabel, px + panelW / 2, py + Theme.HEADER_H + 8,
            Theme.C_MUTED);

        // Page content.
        //
        // Line height is DERIVED, not fixed at 11. The pages carry up to 13 body lines and the
        // panel is clamped to the window by Theme.fit, so on any window shorter than about 340
        // logical pixels the block simply kept going: the last lines were drawn straight through
        // the Prev / Next / Back row, text over button faces, unreadable. Compressing to the space
        // that exists fixes every window that can hold the text at all, and the clip below refuses
        // to draw a line that would still collide — losing the tail of a page is bad, but drawing
        // it on top of the navigation is worse, because that is how the player leaves.
        String[] lines = PAGES[page];
        int x = px + Theme.PAD + 8;
        int y = py + Theme.HEADER_H + 24;
        int bodyLimit = wellBottom;   // the frame's own inner edge - these must not drift apart
        int bodyCount = Math.max(1, lines.length - 1);
        int avail = bodyLimit - (y + 14);
        int lineH = Math.max(8, Math.min(11, avail / bodyCount));
        // Heading
        g.drawString(font, Component.translatable(lines[0]), x, y, Theme.C_HEADING, !Theme.lightSurface());
        y += 14;
        for (int i = 1; i < lines.length; i++) {
            int ly = y + (i - 1) * lineH;
            if (ly + 8 > bodyLimit) break;
            int color = lines[i].isEmpty() ? Theme.C_FAINT : Theme.C_TEXT;
            g.drawString(font, line(lines[i]), x, ly, color, !Theme.lightSurface());
        }
    }
}
