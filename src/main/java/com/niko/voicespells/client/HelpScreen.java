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

    /** Page content. Each page is a heading + body lines. Empty string lines = blank rows. */
    private static final String[][] PAGES = {
        {
            "What this mod does",
            "Incantation lets you cast Iron's Spells by",
            "speaking their names out loud. It listens on",
            "your microphone on its own — no other mod",
            "is needed.",
            "",
            "You don't press anything to cast. Say the",
            "spell name out loud — the recogniser hears",
            "it and the server casts it for you.",
            "",
            "Spellbook rules still apply: you need the",
            "spell equipped (Curios slot or hand depending",
            "on cast mode), and you need the mana/cooldown."
        },
        {
            "How matching works",
            "When Vosk hears you, the mod tries four",
            "levels of matching, in order:",
            "",
            "  E - exact phrase",
            "  F - fuzzy (±1-2 letter typos)",
            "  S - substring (longer phrase contains a",
            "      known spell name)",
            "  P - phonetic (Soundex sound-alike fallback)",
            "",
            "The Live Monitor in Config shows the tier",
            "letter so you can see WHY a phrase matched.",
            "Tune Fuzzy Tolerance + Substring Match in the",
            "Recognition tab if you get false matches."
        },
        {
            "Aliases + incantations",
            "If Vosk can't pronounce a spell name (e.g.",
            "'starfall', 'counterspell'), add a custom",
            "phrase: open the Spell List, click the row,",
            "press 'Add alias...' and type words the model",
            "can say. Example: 'star fall'.",
            "",
            "Incantations are the same mechanism with a",
            "flavor framing — say 'by the power of fire'",
            "to cast fireball. Edit them in the toml or",
            "via the alias screen.",
            "",
            "If a near-miss happens, the HUD pops a hint",
            "'? Spell Name [press Y]' — one keystroke",
            "opens the alias screen pre-filled."
        },
        {
            "Loadouts",
            "Loadouts let you say a category name and",
            "the mod picks the first castable spell from",
            "the list. Configure in voicespells-client.toml:",
            "",
            "  loadouts = [",
            "    \"offense=irons_spellbooks:fireball,",
            "      irons_spellbooks:lightning_lance\"",
            "  ]",
            "",
            "Saying 'offense' picks the first one that's",
            "not on cooldown and that you can afford.",
            "Great for combat panic-casts."
        },
        {
            "Troubleshooting",
            "Config → More... → Diagnostics checks the",
            "mic, Iron's Spells, Curios, the speech model",
            "and its vocabulary, the spell index, and the",
            "cast pipeline.",
            "",
            "Each check shows OK / WARN / FAIL with a",
            "one-line reason. 'Copy report' bundles them",
            "to your clipboard for sharing.",
            "",
            "Achievements live in the vanilla L menu",
            "under the 'Voice Casting' tab.",
            "",
            "Stats live in Voice Codex under More..."
        }
    };

    public HelpScreen(Screen parent) {
        super(Component.literal("Incantation — Help"));
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
            Component.literal("← Prev"), b -> { if (page > 0) { page--; rebuildWidgets(); } }))
            .active = page > 0;
        addRenderableWidget(NeonButton.of(px + Theme.PAD + btnW + 6, btnY, btnW, 20,
            Component.literal("Next →"), b -> { if (page < PAGES.length - 1) { page++; rebuildWidgets(); } }))
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
        Theme.well(g, px + Theme.PAD, bodyTop, panelW - Theme.PAD * 2, (py + panelH - 34) - bodyTop);

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
        int bodyLimit = py + panelH - 28 - 4;          // top of the button row, minus breathing room
        int bodyCount = Math.max(1, lines.length - 1);
        int avail = bodyLimit - (y + 14);
        int lineH = Math.max(8, Math.min(11, avail / bodyCount));
        // Heading
        g.drawString(font, Component.literal(lines[0]), x, y, Theme.C_HEADING, !Theme.lightSurface());
        y += 14;
        for (int i = 1; i < lines.length; i++) {
            int ly = y + (i - 1) * lineH;
            if (ly + 8 > bodyLimit) break;
            int color = lines[i].isEmpty() ? Theme.C_FAINT : Theme.C_TEXT;
            g.drawString(font, Component.literal(lines[i]), x, ly, color, !Theme.lightSurface());
        }
    }
}
