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

    private static final int PANEL_W = 460;
    private static final int PANEL_H = 320;

    private final Screen parent;
    private int page = 0;
    private int px, py;

    /** Page content. Each page is a heading + body lines. Empty string lines = blank rows. */
    private static final String[][] PAGES = {
        {
            "What this mod does",
            "Voice Spells lets you cast Iron's Spells by",
            "speaking their names into your Simple Voice",
            "Chat microphone.",
            "",
            "No keybinds, no menus. Just say the spell",
            "name out loud — the recogniser hears it and",
            "the server casts it for you.",
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
            "Config → More... → Diagnostics runs 11",
            "checks across SVC, Iron's Spells, Curios,",
            "the Vosk model, the spell index, and the",
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
        super(Component.literal("Voice Spells — Help"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        px = (width - PANEL_W) / 2;
        py = (height - PANEL_H) / 2;

        StringWidget titleW = new StringWidget(px, py + (Theme.HEADER_H - 9) / 2,
            PANEL_W, 9, title, font);
        titleW.alignCenter();
        titleW.setColor(Theme.C_ACCENT);
        addRenderableWidget(titleW);

        int btnY = py + PANEL_H - 28;
        int btnW = 90;

        addRenderableWidget(NeonButton.of(px + Theme.PAD, btnY, btnW, 20,
            Component.literal("← Prev"), b -> { if (page > 0) { page--; rebuildWidgets(); } }))
            .active = page > 0;
        addRenderableWidget(NeonButton.of(px + Theme.PAD + btnW + 6, btnY, btnW, 20,
            Component.literal("Next →"), b -> { if (page < PAGES.length - 1) { page++; rebuildWidgets(); } }))
            .active = page < PAGES.length - 1;
        addRenderableWidget(NeonButton.of(px + PANEL_W - Theme.PAD - 80, btnY, 80, 20,
            CommonComponents.GUI_BACK, b -> onClose()));
    }

    @Override
    public void onClose() {
        if (minecraft != null) minecraft.setScreen(parent);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        g.fill(0, 0, this.width, this.height, Theme.C_SCRIM);
        g.fill(px, py, px + PANEL_W, py + PANEL_H, Theme.C_PANEL);
        Theme.headerBand(g, px, py, PANEL_W, Theme.HEADER_H);

        super.render(g, mouseX, mouseY, partial);

        Theme.roundedFrame(g, px, py, PANEL_W, PANEL_H, Theme.C_BORDER);
        Theme.accentGlow(g, px + Theme.PAD, py + Theme.HEADER_H, PANEL_W - Theme.PAD * 2);

        // Page indicator dots — same look as the first-run wizard, for visual consistency.
        int dotsY = py + Theme.HEADER_H + 10;
        int spacing = 12, dotW = 8, dotH = 2;
        int dotsX = px + PANEL_W / 2 - (PAGES.length * spacing - (spacing - dotW)) / 2;
        for (int i = 0; i < PAGES.length; i++) {
            int dx = dotsX + i * spacing;
            int color = (i == page) ? Theme.C_ACCENT_BRIGHT
                       : (i < page ? Theme.C_ACCENT_SOFT : Theme.C_DIVIDER);
            g.fill(dx, dotsY, dx + dotW, dotsY + dotH, color);
        }

        // Page content
        String[] lines = PAGES[page];
        int x = px + Theme.PAD;
        int y = py + Theme.HEADER_H + 24;
        // Heading in accent
        g.drawString(font, Component.literal(lines[0]), x, y, Theme.C_ACCENT, false);
        y += 14;
        for (int i = 1; i < lines.length; i++) {
            int color = lines[i].isEmpty() ? Theme.C_FAINT : Theme.C_TEXT;
            g.drawString(font, Component.literal(lines[i]), x, y + (i - 1) * 11, color, false);
        }
    }
}
