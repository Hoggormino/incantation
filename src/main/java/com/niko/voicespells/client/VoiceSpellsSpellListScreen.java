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
    private int px, py, panelW;
    /** Header / footer rule positions for the shared chrome. */
    private int headerRuleY, footerRuleY;

    public VoiceSpellsSpellListScreen(Screen parent) {
        super(Component.translatable("voicespells.spelllist.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        // Clamp to fit the current screen so large GUI Scale settings don't push buttons off
        // the bottom or sides. The preferred dimensions still apply when there's enough room.
        // Full window: a list of 111 spells should use the screen it is given. The old centred
        // panel left a band of empty space below the list while clipping rows off the top of it.
        panelW = Math.min(560, width - 40);
        px = (width - panelW) / 2;
        py = 46;
        headerRuleY = 32;

        StringWidget titleW = new StringWidget(0, 14, width, 9, title, font);
        titleW.alignCenter();
        titleW.setColor(0xFFFFFF);
        addRenderableWidget(titleW);

        int controlsY = py;
        // Wide enough for the longest label each chip can show, now that they carry their own
        // names: "School: Lightning" and "Sort: Mana". Measured rather than guessed, because a
        // vanilla button truncates nothing — it draws the text straight through its own border.
        int schoolW = Math.max(96, font.width("School: Lightning") + 12);
        int sortW   = Math.max(76, font.width("Sort: Casts") + 12);
        int searchW = panelW - schoolW - sortW - 8;

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

        // These name what they cycle again.
        //
        // The prefix was dropped back when the widget drew its own chevrons at each edge, on the
        // grounds that the arrows plus the toolbar position said enough. NeonCycle now renders as
        // a plain vanilla button with no chevrons, so a chip reading just "Fire" or "Name" gave
        // no hint that it cycles at all — it looked like a button that would do something. The
        // label carries it instead, which is what vanilla's own cycle options do.
        addRenderableWidget(NeonCycle.named(px, controlsY, schoolW, 18, "School",
            schoolOptions, currentSchool,
            s -> capitalizeOne(s),
            val -> {
                currentSchool = val;
                applyFilter(search != null ? search.getValue() : "");
            }));
        addRenderableWidget(NeonCycle.named(px + schoolW + 4, controlsY, sortW, 18,
            "Sort", SortMode.values(), currentSort,
            s -> s.label,
            val -> {
                currentSort = val;
                applyFilter(search != null ? search.getValue() : "");
            }));

        int searchX = px + schoolW + sortW + 8;
        int searchY = controlsY;
        search = new EditBox(font, searchX, searchY, searchW, 18,
            Component.translatable("voicespells.spelllist.search"));
        search.setHint(Component.translatable("voicespells.spelllist.search"));
        search.setResponder(this::applyFilter);
        addRenderableWidget(search);
        setInitialFocus(search);

        // Layout bottom-up: buttons 28px off the bottom, footer just above them, list fills
        // the remaining space above the footer. Keeps the footer text from being clipped by
        // the action buttons sitting at the same y-row.
        int buttonsY = height - 28;
        // The rule goes ABOVE the status line, not through it. footerRuleY used to be
        // buttonsY - 8, which lands inside the 9px band the footer text occupies, so the
        // separator drew a line straight through the middle of its own caption.
        footerRuleY  = buttonsY - 20;
        int footerY  = footerRuleY + 6;          // 9px text, 5px clear of the buttons below
        int listX = px;
        int listY = searchY + 18 + Theme.GAP_MD;
        int listW = panelW;
        // Whole rows only: a partial row at the bottom is a row the player can see half of and
        // not click, which is how the last hit-test bug got in.
        int listH = ((footerRuleY - Theme.GAP_SM) - listY) / ROW_H * ROW_H + 4;
        list = new ListWidget(listX, listY, listW, listH);
        addRenderableWidget(list);

        // Custom footer widget — draws the current footerText() fresh every frame inside the
        // widget pass so there's no stale-message risk and no overdraw on state transitions.
        addRenderableWidget(new FooterWidget(px, footerY, panelW, 9));

        // "Add alias..." pops the inline editor for the selected row. Disabled while nothing
        // is selected so the affordance points at the click-a-row UX.
        aliasButton = NeonButton.of(px, buttonsY, 110, 20,
            Component.literal("Add alias..."), b -> openAliasEditor());
        aliasButton.active = !selectedId.isEmpty();
        addRenderableWidget(aliasButton);

        addRenderableWidget(NeonButton.of(px + panelW - 80, buttonsY, 80, 20,
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
        // Vanilla backdrop first (blurred world in-game, dirt on the title screen, and it
        // honours the player's Menu Background Blur setting), then our own dim on top so
        // the panel still reads. Painting only a flat scrim, as this did before, opted out
        // of all of that and was a large part of why the screens felt foreign.
        Theme.screenChrome(this, g, mouseX, mouseY, partial, headerRuleY, footerRuleY, px, panelW);
        tooltipId = null;
        super.render(g, mouseX, mouseY, partial);

        // Tooltip last, and raised in Z so its background lands above the rows' text rather
        // than beneath it. 400 is the offset vanilla uses for its own tooltips.
        if (tooltipId != null && list != null) {
            g.pose().pushPose();
            g.pose().translate(0.0F, 0.0F, 400.0F);
            list.drawSpellTooltip(g, tooltipId, tooltipX, tooltipY);
            g.pose().popPose();
        }
    }

    /** Spell hovered this frame, captured by the list widget and drawn by {@link #render}. */
    private String tooltipId;
    private int tooltipX, tooltipY;

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
            g.drawString(font, text, getX(), getY(), Theme.C_MUTED, !Theme.lightSurface());
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
            this.rowsVisible = Math.max(0, (h - 4) / ROW_H);   // -4 for the frame inset
        }

        @Override
        protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partial) {
            int x = getX(), y = getY(), w = getWidth(), h = getHeight();
            Theme.well(g, x, y, w, h);

            if (filtered.isEmpty()) {
                g.drawString(font, Component.translatable("voicespells.spelllist.empty"),
                    x + 6, y + 6, Theme.C_FAINT, !Theme.lightSurface());
                return;
            }
            int clampedScroll = clampScroll();
            int end = Math.min(filtered.size(), clampedScroll + rowsVisible);
            int contentRight = x + w - 8; // leave 6px gutter for the scrollbar
            int hoveredRow = -1;
            for (int i = clampedScroll; i < end; i++) {
                SpellIndex.SpellRow r = filtered.get(i);
                // +2 so the first row clears the well's own frame. Drawn flush, the top row
                // lost its first pixel row of glyphs — the "cut off at the top" report.
                int ry = y + 2 + (i - clampedScroll) * ROW_H;
                boolean hov = mouseX >= x && mouseX <= contentRight && mouseY >= ry && mouseY < ry + ROW_H;
                boolean sel = !selectedId.isEmpty() && selectedId.equals(r.id());
                // Very subtle zebra striping — gives the eye a row anchor without chrome.
                // No zebra striping. It made the list read as bands first and text second, and
                // hover plus selection already say everything striping was saying.
                // Selection the way vanilla marks a list row: an opaque outline over an opaque
                // black body, not a translucent wash with an accent bar down the left edge. The
                // bar was an invented idiom with no counterpart anywhere in the game, and it was
                // repeated on five screens. AbstractSelectionList.renderSelection does exactly
                // this - white when the list has focus, dark grey when it does not.
                if (sel) {
                    Theme.rowSelection(g, x + 1, ry, contentRight - (x + 1), ROW_H, isFocused());
                }
                // Hover composites ON TOP of selection rather than replacing it. Drawn with an
                // else-if, hovering a selected row erased the very indicator that says it is
                // selected; vanilla draws the two independently.
                if (hov) {
                    g.fill(x + 1, ry, contentRight, ry + ROW_H, 0x40FFFFFF);
                    hoveredRow = i;
                }
                String id = r.id();
                int idW = font.width(id);
                int textColor = (hov || sel) ? 0xFFFFFFFF : Theme.C_TEXT;
                g.drawString(font, Component.literal(id), x + 6, ry + 2, textColor, !Theme.lightSurface());
                int avail = (contentRight - x) - idW - 14;
                if (avail > 30) {
                    g.drawString(font, Component.literal(trim(r.phrases(), avail)),
                        x + idW + 12, ry + 2, Theme.C_MUTED, !Theme.lightSurface());
                }
            }
            Theme.listScrollbar(g, x + w - 7, y, h, filtered.size(), rowsVisible, clampScroll());

            // Record the hover instead of drawing it here.
            //
            // "Last in the widget pass" is not the same as "on top", and that assumption is what
            // made the tooltip look transparent: GuiGraphics batches fills and text into separate
            // buffers, and the text buffer is flushed after the fills. So the card's background
            // was painted before every row's TEXT, and the rows read straight through it —
            // tooltip text and row text interleaved into unreadable mush. It has to be drawn
            // after the whole widget pass and at a raised Z, which is what the screen does with
            // this below, and is exactly how vanilla renders its own tooltips.
            if (hoveredRow >= 0) {
                tooltipId = filtered.get(hoveredRow).id();
                tooltipX = mouseX;
                tooltipY = mouseY;
            }
        }

        /** Neon tooltip card for the hovered row: spell name in accent, school + mana + cast
         *  time in muted, then phrases ("say X") and the spell id. Positioned to stay
         *  on-screen relative to the cursor. */
        void drawSpellTooltip(GuiGraphics g, String spellId, int mouseX, int mouseY) {
            SpellInfo info = SpellInfo.of(spellId);
            // displayName(), not name: the first is Iron's Spells' translation key resolved in
            // the player's own language, the second is the English fallback.
            String resolved = info.displayName().getString();
            String title = (resolved == null || resolved.isEmpty()) ? prettifyId(spellId) : resolved;

            List<String> body = new ArrayList<>();
            StringBuilder meta = new StringBuilder();
            if (!info.school.isEmpty()) meta.append(capitalize(info.school));
            // At the level the player's own book inscribes it at, not level 1. Iron's Spells
            // scales cost with level, so the level-1 figure is simply the wrong number for
            // anyone with an upgraded spellbook - and it is the number they compare against
            // their mana bar when a cast does not happen.
            int lv = OwnedSpells.levelOf(spellId);
            int cost = SpellInfo.manaCostAt(spellId, lv);
            if (cost < 0) cost = info.manaCost;
            if (cost > 0) {
                if (meta.length() > 0) meta.append("  ·  ");
                meta.append(cost).append(" mana");
                if (lv > 1) meta.append(" (lv ").append(lv).append(')');
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


            // Hand the whole card to vanilla.
            //
            // What was here drew a bespoke one: a C_PANEL background, an accent frame, an accent
            // title and hand-positioned lines with their own clamping. Two things were wrong with
            // it. It did not look like a Minecraft tooltip — the game's tooltip is dark with a
            // violet gradient border and light text, and no player has ever seen a light one — and
            // on the container palette its accent title came out as a pastel on pale grey, barely
            // readable. renderComponentTooltip draws the real thing, handles the wrapping and the
            // keep-it-on-screen clamping, and has the same signature on 1.20.1 and 1.21.1.
            //
            // Colour now travels in the Components themselves, which is also how vanilla item
            // tooltips carry their name-vs-lore distinction.
            java.util.List<net.minecraft.network.chat.Component> lines = new ArrayList<>();
            lines.add(Component.literal(title).withStyle(net.minecraft.ChatFormatting.WHITE));
            for (int i = 0; i < body.size(); i++) {
                net.minecraft.ChatFormatting style;
                if (i == body.size() - 1)                   style = net.minecraft.ChatFormatting.DARK_GRAY;
                else if (descStart >= 0 && i >= descStart)   style = net.minecraft.ChatFormatting.GRAY;
                else                                        style = net.minecraft.ChatFormatting.GRAY;
                lines.add(Component.literal(body.get(i)).withStyle(style));
            }
            g.renderComponentTooltip(font, lines, mouseX, mouseY);
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
//? if forge {
/*        public boolean mouseScrolled(double mx, double my, double sy) {
*///?} else {
        public boolean mouseScrolled(double mx, double my, double sx, double sy) {
//?}
            if (!isMouseOver(mx, my)) return false;
            scroll -= (int) Math.signum(sy);
            clampScroll();
            return true;
        }

        @Override
        public void onClick(double mx, double my) {
            // Ignore clicks in the scrollbar gutter (rightmost ~8px).
            if (mx > getX() + getWidth() - 8) return;
            // Only rows that were actually DRAWN are clickable. The well is rarely an exact
            // multiple of ROW_H, so a strip of empty list sits under the last row — clicking it
            // computed a row one past the visible page and silently selected, and clipboard-
            // copied, a spell the player could not see.
            // Reject the inset BEFORE dividing. A click in the top 2px frame gives a numerator
            // of -2..-1, and an int cast truncates toward zero, so it produced row 0 and slipped
            // past the < 0 guard - selecting and clipboard-copying the first row from a band
            // that never highlights.
            double rel = my - getY() - 2;                          // matches the +2 draw inset
            if (rel < 0) return;
            int visibleRow = (int) (rel / ROW_H);
            if (visibleRow >= rowsVisible) return;
            int row = visibleRow + clampScroll();
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
