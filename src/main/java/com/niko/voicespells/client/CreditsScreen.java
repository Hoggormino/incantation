package com.niko.voicespells.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
//? if forge {
/*import net.minecraftforge.fml.ModList;
*///?} else {
import net.neoforged.fml.ModList;
//?}

import java.util.ArrayList;
import java.util.List;

/**
 * Credits and licences.
 *
 * <p>Built from the Credits section of the README rather than invented here, plus the two things
 * that section does not mention and legally should: JNA, which is how Vosk reaches native code and
 * ships under different terms to Vosk itself, and the speech models, which are downloaded
 * separately and are not all under the same licence as the toolkit that reads them.
 *
 * <p>Paginated the way {@link HelpScreen} is, for the same reason: the panel is clamped to the
 * window, so a single long scroll would need its own scrollbar to say anything a page count does
 * not already say.
 *
 * <p>The THANKS page names contributors, and only ones Hoggormino has asked for by name — see the
 * comment on {@link #THANKS}.
 */
public final class CreditsScreen extends Screen {

    private static final int PANEL_W_PREF = 460;
    private static final int PANEL_H_PREF = 320;

    private final Screen parent;
    private int page = 0;
    private int px, py, panelW, panelH;
    /** Built once in {@link #init()}. render() runs 60+ times a second and the content never
     *  changes while the screen is open, so building the page list there would have re-allocated
     *  every row and re-queried the mod container on every frame. */
    private List<Page> pages = List.of();

    /** One credit line: a name on the left, what it is on the right. A blank name draws the value
     *  as a full-width note instead, which is how the disclaimers and licence lines are set.
     *
     *  <p>Either field may hold a lang key or literal text. Prose is a key so it can be
     *  translated; people's handles, mod names, licence identifiers, version numbers and the
     *  model URL are literal, because translating them would be wrong. {@link #text(String)}
     *  tells the two apart by the mod-id prefix and resolves the row at draw time. */
    private record Row(String name, String note) {
        static Row of(String name, String note) { return new Row(name, note); }
        static Row note(String text)            { return new Row("", text); }
        static Row gap()                        { return new Row("", ""); }
    }

    /** Marks a row field or heading as a lang key rather than text to draw as-is. */
    private static final String KEY_PREFIX = "voicespells.";

    /** Resolves one row field: a lang key becomes translated text, anything else is used as is.
     *  Resolved per frame rather than cached because the player can change language without
     *  reopening the screen, and because truncation has to measure the translated string. */
    private static String text(String s) {
        return s.startsWith(KEY_PREFIX) ? Component.translatable(s).getString() : s;
    }

    private record Page(String heading, List<Row> rows) {}

    /**
     * People to thank. Named with Hoggormino's say-so, one at a time — a real person's handle in
     * a published mod is not something to fill in from a comment thread. Empty list drops the
     * page entirely rather than shipping a heading with nothing under it.
     */
    private static final List<Row> THANKS = List.of(
        Row.of("NeoTargetStudios", "Spidercat0926"),
        Row.gap(),
        Row.note("voicespells.credits.thanks_1"),
        Row.note("voicespells.credits.thanks_2"),
        Row.note("voicespells.credits.thanks_3")
    );

    private static List<Page> pages() {
        List<Page> out = new ArrayList<>();

        out.add(new Page("voicespells.credits.mod_heading", List.of(
            Row.of("voicespells.config.title", "v" + modVersion()),
            Row.of("voicespells.credits.author", "Hoggormino"),
            Row.of("voicespells.credits.licence", "MIT"),
            Row.gap(),
            Row.note("voicespells.credits.about_1"),
            Row.note("voicespells.credits.about_2"),
            Row.note("voicespells.credits.about_3")
        )));

        out.add(new Page("voicespells.credits.speech_heading", List.of(
            Row.of("Vosk", "Alpha Cephei - Apache 2.0"),
            Row.of("JNA", "Apache 2.0 / LGPL 2.1"),
            Row.gap(),
            Row.note("voicespells.credits.vosk_1"),
            Row.note("voicespells.credits.vosk_2"),
            Row.gap(),
            Row.note("voicespells.credits.models_1"),
            Row.note("voicespells.credits.models_2"),
            Row.note("voicespells.credits.models_3"),
            Row.note("alphacephei.com/vosk/models")
        )));

        out.add(new Page("voicespells.credits.built_for_heading", List.of(
            Row.of("Iron's Spells 'n Spellbooks", "iron431"),
            Row.of("Curios API", "voicespells.credits.curios_note"),
            Row.gap(),
            Row.note("voicespells.credits.addon_1"),
            Row.note("voicespells.credits.addon_2"),
            Row.note("voicespells.credits.addon_3"),
            Row.note("voicespells.credits.addon_4")
        )));

        if (!THANKS.isEmpty()) out.add(new Page("voicespells.credits.thanks_heading", THANKS));
        return out;
    }

    /** Read at runtime rather than baked in, so it cannot drift from the jar it is printed on.
     *  Resolved once per game session — it cannot change while the game is running. */
    private static String cachedVersion;

    private static String modVersion() {
        if (cachedVersion != null) return cachedVersion;
        String v;
        try {
            v = ModList.get().getModContainerById("voicespells")
                .map(c -> c.getModInfo().getVersion().toString())
                .orElse("?");
        } catch (Throwable t) {
            v = "?";
        }
        cachedVersion = v;
        return v;
    }

    public CreditsScreen(Screen parent) {
        super(Component.translatable("voicespells.credits.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        pages = pages();
        panelW = Theme.fit(PANEL_W_PREF, width);
        panelH = Theme.fit(PANEL_H_PREF, height);
        px = (width - panelW) / 2;
        py = (height - panelH) / 2;

        StringWidget titleW = new StringWidget(px, py + (Theme.HEADER_H - 9) / 2,
            panelW, 9, title, font);
        titleW.alignCenter();
        titleW.setColor(Theme.C_TEXT);
        addRenderableWidget(titleW);

        int total = pages.size();
        int btnY = py + panelH - 28;
        int btnW = 90;

        addRenderableWidget(NeonButton.of(px + Theme.PAD, btnY, btnW, 20,
            Component.translatable("voicespells.credits.prev"), b -> { if (page > 0) { page--; rebuildWidgets(); } }))
            .active = page > 0;
        addRenderableWidget(NeonButton.of(px + Theme.PAD + btnW + 6, btnY, btnW, 20,
            Component.translatable("voicespells.credits.next"), b -> { if (page < total - 1) { page++; rebuildWidgets(); } }))
            .active = page < total - 1;
        addRenderableWidget(NeonButton.of(px + panelW - Theme.PAD - 80, btnY, 80, 20,
            CommonComponents.GUI_BACK, b -> onClose()));
    }

    @Override
    public void onClose() {
        if (minecraft != null) minecraft.setScreen(parent);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        Theme.ground(this, g, mouseX, mouseY, partial);

        // Same well the guide uses - these two are the mod's only pages of flat prose and they
        // should read as one pair.
        int bodyTop = py + Theme.HEADER_H + 18;
        // Inset PAD, matching the copy inside it, and stopping at btnY - GAP_SM so the frame does
        // not run under the Prev/Next/Back row - it was crossing 4px into the button tops.
        int wellBottom = py + panelH - 34;
        Theme.well(g, px + Theme.PAD, bodyTop, panelW - Theme.PAD * 2, wellBottom - bodyTop);

        super.render(g, mouseX, mouseY, partial);


        if (pages.isEmpty()) return;   // nothing to draw before init() has run
        // The page index survives a resize that shortens the list; clamp rather than crash.
        int idx = Math.max(0, Math.min(page, pages.size() - 1));
        Page current = pages.get(idx);

        g.drawCenteredString(font, "Page " + (idx + 1) + " of " + pages.size(),
            px + panelW / 2, py + Theme.HEADER_H + 8, Theme.C_MUTED);

        int x = px + Theme.PAD + 8;
        int y = py + Theme.HEADER_H + 24;
        int bodyLimit = wellBottom;   // the frame's own inner edge - these must not drift apart

        g.drawString(font, Component.translatable(current.heading()), x, y,
            Theme.C_HEADING, !Theme.lightSurface());
        y += 14;

        // Same derived line height as the guide: the panel is clamped to the window, so a fixed
        // pitch drew the tail of a long page straight through the Prev/Next/Back row.
        int rows = Math.max(1, current.rows().size());
        int lineH = Math.max(8, Math.min(11, (bodyLimit - y) / rows));

        int innerW = panelW - Theme.PAD * 2 - 16;
        for (int i = 0; i < current.rows().size(); i++) {
            int ly = y + i * lineH;
            if (ly + 8 > bodyLimit) break;
            Row row = current.rows().get(i);
            if (row.name().isEmpty()) {
                if (row.note().isEmpty()) continue;    // spacer
                g.drawString(font, Component.literal(Theme.fit(font, text(row.note()), innerW)),
                    x, ly, Theme.C_MUTED, !Theme.lightSurface());
                continue;
            }
            // Name left, note right-aligned, with the NAME giving way when the two collide -
            // the note carries the licence, which is the half that has to stay readable.
            // Both sides are resolved before measuring: a translated note is a different width.
            String name = text(row.name());
            String note = text(row.note());
            int noteW = font.width(note);
            g.drawString(font, Component.literal(Theme.fit(font, name, innerW - noteW - 8)),
                x, ly, Theme.C_TEXT, !Theme.lightSurface());
            g.drawString(font, Component.literal(note),
                x + innerW - noteW, ly, Theme.C_FAINT, !Theme.lightSurface());
        }
    }
}
