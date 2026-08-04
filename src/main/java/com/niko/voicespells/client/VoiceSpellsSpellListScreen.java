package com.niko.voicespells.client;

import com.niko.voicespells.spells.SpellIndex;
import com.niko.voicespells.spells.SpellInfo;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Searchable list of every indexed spell — id plus the phrases that map to it. Click a row to
 * copy its id to the clipboard so it can be pasted straight into {@code customPhrases} /
 * {@code blockedSpells}.
 *
 * All text is rendered through vanilla widgets (StringWidget for the title/footer, a custom
 * AbstractWidget for the scrollable list). Hand-drawn {@code g.drawString} text in this
 * environment was being dimmed by something between the pre-widget draws and the widget pass;
 * widget-pass text isn't, so everything that must be legible is a widget.
 *
 * Palette comes from {@link Theme} — minimalist magic.
 */
public final class VoiceSpellsSpellListScreen extends Screen {

    // Preferred dimensions — clamped at init() time so the panel stays inside the screen
    // at every Minecraft GUI Scale. Layout math uses the runtime {@code panelW} / {@code panelH}
    // fields, not these constants.
    private static final int PANEL_W_PREF = 444;
    private static final int PANEL_H_PREF = 308;
    private static final int ROW_H    = 14;

    /** Sort modes are fixed — they don't depend on what's indexed. */
    private enum SortMode {
        NAME("Name"), SCHOOL("School"), MANA("Mana"), CAST("Cast");
        final String label;
        SortMode(String l) { this.label = l; }
    }

    /** Sentinel used by the school cycle to mean "no filter". */
    private static final String SCHOOL_ALL = "All";

    private final Screen parent;
    private final List<SpellIndex.SpellRow> all = SpellIndex.allSpells();
    private List<SpellIndex.SpellRow> filtered = all;
    /** Schools the school-filter cycle iterates through. Computed at init() from the actual
     *  indexed spells, so addon schools appear and empty placeholders never do. */
    private String[] schoolOptions = new String[]{ SCHOOL_ALL };
    private String   currentSchool = SCHOOL_ALL;
    private SortMode currentSort = SortMode.NAME;

    private EditBox search;
    private ListWidget list;
    private NeonButton aliasButton;
    private long copiedFlashUntil = 0;
    private String copiedId = "";
    /** The most recently clicked spell id — also drives the persistent neon highlight and the
     *  "Add alias..." button (which is disabled until something is selected). */
    private String selectedId = "";

    /** Top-left of the runtime panel + clamped dimensions; recomputed every {@link #init()}. */
    private int px, py, panelW, panelH;

    public VoiceSpellsSpellListScreen(Screen parent) {
        super(Component.translatable("voicespells.spelllist.title"));
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
        titleW.setColor(Theme.C_ACCENT);
        addRenderableWidget(titleW);

        int controlsY = py + Theme.HEADER_H + Theme.GAP_MD;
        int schoolW = 96;
        int sortW   = 76;
        int searchW = (panelW - Theme.PAD * 2) - schoolW - sortW - 8;

        // Rebuild the school list from the actually-indexed spells. Sorted alphabetically,
        // with "All" pinned to the front so the chip always has the no-filter option first.
        java.util.TreeSet<String> uniqueSchools = new java.util.TreeSet<>();
        for (SpellIndex.SpellRow row : all) {
            String s = SpellInfo.of(row.id()).school;
            if (s != null && !s.isBlank()) uniqueSchools.add(s);
        }
        java.util.List<String> opts = new java.util.ArrayList<>();
        opts.add(SCHOOL_ALL);
        for (String s : uniqueSchools) opts.add(s);
        schoolOptions = opts.toArray(new String[0]);
        // Snap back to "All" if the previously selected school no longer exists in the index.
        boolean stillValid = false;
        for (String s : schoolOptions) if (s.equals(currentSchool)) { stillValid = true; break; }
        if (!stillValid) currentSchool = SCHOOL_ALL;

        // Chip text drops the "School:" / "Sort:" prefix so longer values ("Lightning",
        // "Eldritch") don't collide with the cycle chevrons at the edges. The chevrons + the
        // chip's location in the toolbar already communicate what's being cycled.
        addRenderableWidget(NeonCycle.of(px + Theme.PAD, controlsY, schoolW, 18,
            schoolOptions, currentSchool,
            s -> capitalizeOne(s),
            val -> {
                currentSchool = val;
                applyFilter(search != null ? search.getValue() : "");
            }));
        addRenderableWidget(NeonCycle.of(px + Theme.PAD + schoolW + 4, controlsY, sortW, 18,
            SortMode.values(), currentSort,
            s -> s.label,
            val -> {
                currentSort = val;
                applyFilter(search != null ? search.getValue() : "");
            }));

        int searchX = px + Theme.PAD + schoolW + sortW + 8;
        int searchY = controlsY;
        search = new EditBox(font, searchX, searchY, searchW, 18,
            Component.translatable("voicespells.spelllist.search"));
        search.setHint(Component.translatable("voicespells.spelllist.search"));
        search.setResponder(this::applyFilter);
        addRenderableWidget(search);
        setInitialFocus(search);

        // Layout bottom-up: buttons at panelH-28, footer just above the buttons, list fills
        // the remaining space above the footer. Keeps the footer text from being clipped by
        // the action buttons sitting at the same y-row.
        int buttonsY = py + panelH - 28;
        int footerY  = buttonsY - 12;            // 9px text + 3px gap above buttons
        int listX = px + Theme.PAD;
        int listY = searchY + 18 + Theme.GAP_MD;
        int listW = panelW - Theme.PAD * 2;
        int listH = (footerY - Theme.GAP_SM) - listY;
        list = new ListWidget(listX, listY, listW, listH);
        addRenderableWidget(list);

        // Custom footer widget — draws the current footerText() fresh every frame inside the
        // widget pass so there's no stale-message risk and no overdraw on state transitions.
        addRenderableWidget(new FooterWidget(px + Theme.PAD, footerY,
            panelW - Theme.PAD * 2, 9));

        // "Add alias..." pops the inline editor for the selected row. Disabled while nothing
        // is selected so the affordance points at the click-a-row UX.
        aliasButton = NeonButton.of(px + Theme.PAD, py + panelH - 28, 110, 20,
            Component.literal("Add alias..."), b -> openAliasEditor());
        aliasButton.active = !selectedId.isEmpty();
        addRenderableWidget(aliasButton);

        addRenderableWidget(NeonButton.of(px + panelW - Theme.PAD - 80, py + panelH - 28, 80, 20,
            CommonComponents.GUI_BACK, b -> onClose()));

        // Re-apply filter/sort so the list reflects the persisted SchoolFilter / SortMode after
        // a re-init (e.g. window resize).
        applyFilter(search.getValue());
    }

    /** Title-case a single-word (or two-word) school name for the cycle chip. "ice" → "Ice",
     *  "ender" → "Ender". Different from the cell-level capitalize because we only need to
     *  uppercase per-word starts. */
    private static String capitalizeOne(String s) {
        if (s == null || s.isEmpty()) return s;
        StringBuilder sb = new StringBuilder(s.length());
        boolean capNext = true;
        for (char c : s.toCharArray()) {
            sb.append(capNext && Character.isLetter(c) ? Character.toUpperCase(c) : c);
            capNext = (c == ' ');
        }
        return sb.toString();
    }

    private void openAliasEditor() {
        if (selectedId.isEmpty() || minecraft == null) return;
        minecraft.setScreen(new AddAliasScreen(this, selectedId));
    }

    private Component footerText() {
        if (System.currentTimeMillis() < copiedFlashUntil) {
            return Component.literal("Copied: " + copiedId);
        }
        if (!selectedId.isEmpty()) {
            return Component.literal("Selected: " + selectedId + "  ·  click 'Add alias...' to map a phrase");
        }
        return Component.literal(filtered.size() + " spells — click a row to select + copy id");
    }

    private void applyFilter(String q) {
        String needle = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        java.util.List<SpellIndex.SpellRow> out = new java.util.ArrayList<>();
        for (SpellIndex.SpellRow r : all) {
            // Text search across id + aliases
            if (!needle.isEmpty()
                && !r.id().toLowerCase(Locale.ROOT).contains(needle)
                && !r.phrases().toLowerCase(Locale.ROOT).contains(needle)) {
                continue;
            }
            // School chip filter — exact match against the dynamic school list (case-insensitive).
            if (!SCHOOL_ALL.equals(currentSchool)) {
                String school = SpellInfo.of(r.id()).school;
                if (!currentSchool.equalsIgnoreCase(school)) continue;
            }
            out.add(r);
        }
        // Sort. Unknown numeric values sink to the bottom so "Sort: Mana" with some unknowns
        // doesn't put them at position 0.
        Comparator<SpellIndex.SpellRow> cmp = switch (currentSort) {
            case NAME   -> Comparator.comparing(SpellIndex.SpellRow::id);
            case SCHOOL -> Comparator.<SpellIndex.SpellRow, String>comparing(
                              r -> SpellInfo.of(r.id()).school == null ? "~" : SpellInfo.of(r.id()).school)
                          .thenComparing(SpellIndex.SpellRow::id);
            case MANA   -> Comparator.<SpellIndex.SpellRow>comparingInt(
                              r -> { int c = SpellInfo.of(r.id()).manaCost; return c < 0 ? Integer.MAX_VALUE : c; })
                          .thenComparing(SpellIndex.SpellRow::id);
            case CAST   -> Comparator.<SpellIndex.SpellRow>comparingInt(
                              r -> { int t = SpellInfo.of(r.id()).castTimeTicks; return t < 0 ? Integer.MAX_VALUE : t; })
                          .thenComparing(SpellIndex.SpellRow::id);
        };
        out.sort(cmp);
        filtered = out;
        if (list != null) list.scroll = 0;
    }

    @Override
    public void onClose() {
        if (minecraft != null) minecraft.setScreen(parent);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        // Midnight scrim + panel + gradient header band BEFORE widgets.
        g.fill(0, 0, this.width, this.height, Theme.C_SCRIM);
        g.fill(px, py, px + panelW, py + panelH, Theme.C_PANEL);
        Theme.headerBand(g, px, py, panelW, Theme.HEADER_H);

        super.render(g, mouseX, mouseY, partial);

        // Soft rounded frame + glowing neon rule under the header.
        Theme.roundedFrame(g, px, py, panelW, panelH, Theme.C_BORDER);
        Theme.accentGlow(g, px + Theme.PAD, py + Theme.HEADER_H,
            panelW - Theme.PAD * 2);
    }

    /**
     * Custom footer widget — recomputes the message every frame and draws it inside the widget
     * pass (so its text isn't dimmed). Avoids StringWidget's apparent ghosting on rapid
     * message changes (e.g. when transitioning "Copied: id" → "Selected: id" → "N spells").
     */
    private final class FooterWidget extends AbstractWidget {
        FooterWidget(int x, int y, int w, int h) {
            super(x, y, w, h, Component.empty());
        }
        @Override
        protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partial) {
            Component msg = footerText();
            // Truncate to fit if a long selected-id pushes the text past the panel width.
            String text = msg.getString();
            if (font.width(text) > getWidth()) {
                text = font.plainSubstrByWidth(text, getWidth() - font.width("…")) + "…";
            }
            g.drawString(font, text, getX(), getY(), Theme.C_MUTED, false);
        }
        @Override
        protected void updateWidgetNarration(NarrationElementOutput n) {}
    }

    /**
     * The scrollable spell list as a single widget. Renders in super.render()'s bright widget
     * pass (so its text isn't dimmed) and owns its own scroll + click-to-copy.
     */
    private final class ListWidget extends AbstractWidget {
        private int scroll = 0;
        private final int rowsVisible;

        ListWidget(int x, int y, int w, int h) {
            super(x, y, w, h, Component.empty());
            this.rowsVisible = h / ROW_H;
        }

        @Override
        protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partial) {
            int x = getX(), y = getY(), w = getWidth(), h = getHeight();
            g.fill(x, y, x + w, y + h, Theme.C_INSET);
            Theme.insetShadow(g, x, y, w); // recessed top edge
            Theme.roundedFrame(g, x, y, w, h, Theme.C_DIVIDER);

            if (filtered.isEmpty()) {
                g.drawString(font, Component.translatable("voicespells.spelllist.empty"),
                    x + 6, y + 6, Theme.C_FAINT, false);
                return;
            }
            int clampedScroll = clampScroll();
            int end = Math.min(filtered.size(), clampedScroll + rowsVisible);
            int contentRight = x + w - 8; // leave 6px gutter for the scrollbar
            int hoveredRow = -1;
            for (int i = clampedScroll; i < end; i++) {
                SpellIndex.SpellRow r = filtered.get(i);
                int ry = y + (i - clampedScroll) * ROW_H;
                boolean hov = mouseX >= x && mouseX <= contentRight && mouseY >= ry && mouseY < ry + ROW_H;
                boolean sel = !selectedId.isEmpty() && selectedId.equals(r.id());
                // Very subtle zebra striping — gives the eye a row anchor without chrome.
                if ((i & 1) == 1 && !hov && !sel) g.fill(x + 1, ry, contentRight, ry + ROW_H, Theme.C_INSET_2);
                if (sel && !hov) {
                    // Persistent selection: dim accent so the row stays anchored after click.
                    g.fill(x + 1, ry, contentRight, ry + ROW_H, Theme.C_ACCENT_FAINT);
                    g.fill(x + 1, ry, x + 3, ry + ROW_H, Theme.C_ACCENT);
                }
                if (hov) {
                    g.fill(x + 1, ry, contentRight, ry + ROW_H, Theme.C_ACCENT_SOFT);
                    // Neon left-edge marker on hover — confident, sleek.
                    g.fill(x + 1, ry, x + 3, ry + ROW_H, Theme.C_ACCENT_BRIGHT);
                    hoveredRow = i;
                }
                String id = r.id();
                int idW = font.width(id);
                int textColor = hov ? Theme.C_ACCENT_BRIGHT : (sel ? Theme.C_ACCENT : Theme.C_TEXT);
                g.drawString(font, Component.literal(id), x + 6, ry + 2, textColor, false);
                int avail = (contentRight - x) - idW - 14;
                if (avail > 30) {
                    g.drawString(font, Component.literal(trim(r.phrases(), avail)),
                        x + idW + 12, ry + 2, Theme.C_MUTED, false);
                }
            }
            // Neon scrollbar on the right gutter.
            Theme.scrollbar(g, x + w - 4, y + 2, 2, h - 4,
                filtered.size(), rowsVisible, clampScroll());

            // Hover tooltip last so it sits on top of everything in the widget pass.
            if (hoveredRow >= 0) drawSpellTooltip(g, filtered.get(hoveredRow).id(), mouseX, mouseY);
        }

        /** Neon tooltip card for the hovered row: spell name in accent, school + mana + cast
         *  time in muted, then phrases ("say X") and the spell id. Positioned to stay
         *  on-screen relative to the cursor. */
        private void drawSpellTooltip(GuiGraphics g, String spellId, int mouseX, int mouseY) {
            SpellInfo info = SpellInfo.of(spellId);
            String title = (info.name == null || info.name.isEmpty()) ? prettifyId(spellId) : info.name;

            List<String> body = new ArrayList<>();
            StringBuilder meta = new StringBuilder();
            if (!info.school.isEmpty()) meta.append(capitalize(info.school));
            if (info.manaCost > 0) {
                if (meta.length() > 0) meta.append("  ·  ");
                meta.append(info.manaCost).append(" mana");
            }
            if (meta.length() > 0) body.add(meta.toString());
            String ct = info.castTimeFormatted();
            if (!ct.isEmpty()) body.add("Cast: " + ct);
            // How many times the player has voice-cast this specific spell. Only shows once
            // we have at least one cast to report.
            int castCount = VoiceStats.castCount(spellId);
            if (castCount > 0) {
                long lastMs = VoiceStats.lastCastMsFor(spellId);
                String when = lastMs > 0 ? "  · last " + VoiceStats.fmtElapsed(lastMs) : "";
                body.add("Cast " + castCount + "× via voice" + when);
            }

            // The phrases column is already shown inline on the row, but spelling out
            // "Say: <phrases>" in the tooltip frames it as the actual incantation -
            // particularly nice once a user adds an incantation alias.
            String phrases = filtered.stream()
                .filter(r -> r.id().equals(spellId))
                .findFirst().map(SpellIndex.SpellRow::phrases).orElse("");
            if (!phrases.isEmpty()) body.add("Say: " + phrases);

            // Reflective per-spell description (damage / range / duration etc. — whatever
            // Iron's Spells' getUniqueInfo decides to surface).
            net.minecraft.world.entity.LivingEntity caster =
                (minecraft != null) ? minecraft.player : null;
            java.util.List<String> desc = SpellInfo.descriptionFor(spellId, caster);
            int descStart = -1;
            if (!desc.isEmpty()) {
                descStart = body.size(); // remember where the description block starts
                body.addAll(desc);
            }

            body.add(spellId); // always include the id at the bottom in muted

            int maxW = font.width(title);
            for (String line : body) maxW = Math.max(maxW, font.width(line));
            int padX = 8, padY = 6, lineH = 11;
            int tw = maxW + padX * 2;
            int dividerExtra = (descStart >= 0) ? 4 : 0;
            int th = padY * 2 + lineH + (body.size() * lineH) + 2 + dividerExtra;

            // Keep the tooltip on screen. Prefer above-right of the cursor.
            int screenW = VoiceSpellsSpellListScreen.this.width;
            int screenH = VoiceSpellsSpellListScreen.this.height;
            int tx = mouseX + 12;
            int ty = mouseY - th - 4;
            if (tx + tw > screenW - 2) tx = mouseX - tw - 8;
            if (ty < 2)                 ty = mouseY + 12;
            if (ty + th > screenH - 2)  ty = screenH - th - 2;

            // Background + neon frame.
            g.fill(tx, ty, tx + tw, ty + th, Theme.C_PANEL);
            Theme.roundedFrame(g, tx, ty, tw, th, Theme.C_ACCENT);
            // Subtle inner halo so the card glows a touch.
            g.fill(tx + 2, ty + 1, tx + tw - 2, ty + 2, Theme.C_ACCENT_FAINT);
            g.fill(tx + 2, ty + th - 2, tx + tw - 2, ty + th - 1, Theme.C_ACCENT_FAINT);

            int ly = ty + padY;
            g.drawString(font, Component.literal(title), tx + padX, ly,
                Theme.C_ACCENT_BRIGHT, false);
            ly += lineH;
            // Thin accent rule under the title.
            g.fill(tx + padX, ly - 1, tx + tw - padX, ly, Theme.C_ACCENT_SOFT);
            ly += 1;
            for (int i = 0; i < body.size(); i++) {
                // Inject a quiet divider just before the reflective description block.
                if (i == descStart) {
                    g.fill(tx + padX, ly + 2, tx + tw - padX, ly + 3, Theme.C_DIVIDER);
                    ly += 4;
                }
                int color;
                if (i == body.size() - 1)                color = Theme.C_FAINT; // id row
                else if (descStart >= 0 && i >= descStart) color = Theme.C_MUTED; // description rows
                else                                      color = Theme.C_TEXT;  // meta rows
                g.drawString(font, Component.literal(body.get(i)), tx + padX, ly, color, false);
                ly += lineH;
            }
        }

        private String prettifyId(String spellId) {
            int colon = spellId.indexOf(':');
            String path = colon >= 0 ? spellId.substring(colon + 1) : spellId;
            return capitalize(path.replace('_', ' '));
        }

        private static String capitalize(String s) {
            if (s == null || s.isEmpty()) return s;
            StringBuilder sb = new StringBuilder(s.length());
            boolean capNext = true;
            for (char c : s.toCharArray()) {
                sb.append(capNext && Character.isLetter(c) ? Character.toUpperCase(c) : c);
                capNext = (c == ' ');
            }
            return sb.toString();
        }

        private int clampScroll() {
            int max = Math.max(0, filtered.size() - rowsVisible);
            if (scroll < 0) scroll = 0;
            if (scroll > max) scroll = max;
            return scroll;
        }

        @Override
        public boolean mouseScrolled(double mx, double my, double sy) {
            if (!isMouseOver(mx, my)) return false;
            scroll -= (int) Math.signum(sy);
            clampScroll();
            return true;
        }

        @Override
        public void onClick(double mx, double my) {
            // Ignore clicks in the scrollbar gutter (rightmost ~8px).
            if (mx > getX() + getWidth() - 8) return;
            int row = (int) ((my - getY()) / ROW_H) + clampScroll();
            if (row >= 0 && row < filtered.size()) {
                String id = filtered.get(row).id();
                if (minecraft != null) minecraft.keyboardHandler.setClipboard(id);
                copiedId = id;
                copiedFlashUntil = System.currentTimeMillis() + 1500;
                selectedId = id;
                if (aliasButton != null) aliasButton.active = true;
            }
        }

        private String trim(String s, int pxWidth) {
            if (font.width(s) <= pxWidth) return s;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < s.length(); i++) {
                if (font.width(sb.toString() + s.charAt(i) + "…") > pxWidth) { sb.append('…'); break; }
                sb.append(s.charAt(i));
            }
            return sb.toString();
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput n) {}
    }
}
