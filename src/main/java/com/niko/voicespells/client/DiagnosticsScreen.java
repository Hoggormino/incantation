package com.niko.voicespells.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * Diagnostics screen — runs {@link Diagnostics#runAll()} and renders the results as a
 * colour-coded list. Status colours match the cast monitor: green OK, gold warning, red fail,
 * muted grey for informational entries.
 *
 * "Re-run" reruns every check so the user can verify a fix without closing/reopening the screen.
 * "Copy report" dumps the whole list to the clipboard for bug-report sharing.
 */
public final class DiagnosticsScreen extends Screen {

    // Preferred dimensions — clamped at init() time so the panel stays inside the screen
    // at every Minecraft GUI Scale. Layout math uses the runtime {@code panelW} / {@code panelH}
    // fields, not these constants.
    private static final int PANEL_W_PREF = 480;
    private static final int PANEL_H_PREF = 320;
    private static final int ROW_H   = 28;

    private final Screen parent;
    /** Top-left of the runtime panel + clamped dimensions; recomputed every {@link #init()}. */
    private int px, py, panelW, panelH;
    private List<Diagnostics.Result> results = List.of();
    private DiagList list;
    private StringWidget summaryLabel;
    private long flashUntil = 0L;

    public DiagnosticsScreen(Screen parent) {
        super(Component.literal("Diagnostics"));
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

        results = Diagnostics.runAll();

        // Summary chip just under the accent rule — green when everything's OK, gold/red when not.
        int sumY = py + Theme.HEADER_H + Theme.GAP_MD;
        summaryLabel = new StringWidget(px + Theme.PAD, sumY, panelW - Theme.PAD * 2, 11,
            Component.literal(Diagnostics.shortSummary(results)), font);
        summaryLabel.alignLeft();
        summaryLabel.setColor(summaryColor());
        addRenderableWidget(summaryLabel);

        int listX = px + Theme.PAD;
        int listY = sumY + 14;
        int listW = panelW - Theme.PAD * 2;
        int listH = (py + panelH - 28) - listY - Theme.GAP_MD;
        list = new DiagList(listX, listY, listW, listH);
        addRenderableWidget(list);

        int btnY = py + panelH - 28;
        int btnW = 90;
        addRenderableWidget(NeonButton.of(px + Theme.PAD, btnY, btnW, 20,
            Component.literal("Re-run"), b -> rerun()));
        addRenderableWidget(NeonButton.of(px + Theme.PAD + btnW + 6, btnY, btnW + 18, 20,
            Component.literal("Copy report"), b -> copyReport()));
        addRenderableWidget(NeonButton.of(px + panelW - Theme.PAD - 80, btnY, 80, 20,
            CommonComponents.GUI_BACK, b -> onClose()));
    }

    private int summaryColor() {
        boolean anyFail = results.stream().anyMatch(r -> r.status() == Diagnostics.Status.FAIL);
        boolean anyWarn = results.stream().anyMatch(r -> r.status() == Diagnostics.Status.WARN);
        if (anyFail) return Theme.C_DANGER;
        if (anyWarn) return Theme.C_WARN;
        return Theme.C_SUCCESS;
    }

    private void rerun() {
        results = Diagnostics.runAll();
        summaryLabel.setMessage(Component.literal(Diagnostics.shortSummary(results)));
        summaryLabel.setColor(summaryColor());
        list.scroll = 0;
    }

    private void copyReport() {
        if (minecraft == null) return;
        StringBuilder sb = new StringBuilder();
        sb.append("Incantation diagnostics — ").append(Diagnostics.shortSummary(results)).append('\n');
        for (Diagnostics.Result r : results) {
            sb.append('[').append(r.status()).append("] ")
                .append(r.name()).append(" — ").append(r.detail()).append('\n');
        }
        minecraft.keyboardHandler.setClipboard(sb.toString());
        flashUntil = System.currentTimeMillis() + 2500;
    }

    @Override
    public void onClose() {
        if (minecraft != null) minecraft.setScreen(parent);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        if (flashUntil > 0 && System.currentTimeMillis() > flashUntil) {
            summaryLabel.setMessage(Component.literal(Diagnostics.shortSummary(results)));
            summaryLabel.setColor(summaryColor());
            flashUntil = 0L;
        } else if (flashUntil > 0) {
            summaryLabel.setMessage(Component.literal("Report copied to clipboard."));
            summaryLabel.setColor(Theme.F_MATCH);
        }
        // Vanilla backdrop first (blurred world in-game, dirt on the title screen, and it
        // honours the player's Menu Background Blur setting), then our own dim on top so
        // the panel still reads. Painting only a flat scrim, as this did before, opted out
        // of all of that and was a large part of why the screens felt foreign.
        Theme.background(this, g, mouseX, mouseY, partial);
        g.fill(0, 0, this.width, this.height, Theme.C_SCRIM);
        Theme.panel(g, px, py, panelW, panelH);
        Theme.headerBand(g, px, py, panelW, Theme.HEADER_H);
        super.render(g, mouseX, mouseY, partial);
        Theme.headerRule(g, px + Theme.PAD, py + Theme.HEADER_H, panelW - Theme.PAD * 2);
    }

    /** Scrollable list of diagnostic rows. Each row: status pill + name + detail line. */
    private final class DiagList extends AbstractWidget {
        private int scroll = 0;

        DiagList(int x, int y, int w, int h) {
            super(x, y, w, h, Component.empty());
        }

        @Override
        protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partial) {
            int x = getX(), y = getY(), w = getWidth(), h = getHeight();
            g.fill(x, y, x + w, y + h, Theme.C_INSET);
            Theme.insetShadow(g, x, y, w);
            Theme.roundedFrame(g, x, y, w, h, Theme.C_DIVIDER);
            int rowsVisible = (h - 8) / ROW_H;
            int start = Math.max(0, Math.min(scroll, Math.max(0, results.size() - rowsVisible)));
            int end = Math.min(results.size(), start + rowsVisible);
            int ry = y + 4;
            for (int i = start; i < end; i++) {
                Diagnostics.Result r = results.get(i);
                drawRow(g, x + 4, ry, w - 8, r);
                ry += ROW_H;
            }
            Theme.scrollbar(g, x + w - 4, y + 2, 2, h - 4,
                results.size(), rowsVisible, start);
        }

        private void drawRow(GuiGraphics g, int x, int y, int w, Diagnostics.Result r) {
            int statusColor = colorForStatus(r.status());
            String pill = "[" + r.status() + "]";
            int pillW = font.width(pill);
            g.drawString(font, Component.literal(pill), x, y + 1, statusColor, !Theme.lightSurface());
            // Left-edge accent bar in the status colour for quick scan.
            g.fill(x - 3, y, x - 1, y + ROW_H - 2, statusColor);
            // Name in main text colour.
            g.drawString(font, Component.literal(r.name()),
                x + pillW + 6, y + 1, Theme.C_TEXT, !Theme.lightSurface());
            // Detail in muted, on the next line.
            String detail = r.detail();
            if (font.width(detail) > w - 12) {
                detail = font.plainSubstrByWidth(detail, w - 16) + "…";
            }
            g.drawString(font, Component.literal(detail),
                x + 4, y + 12, Theme.C_MUTED, !Theme.lightSurface());
        }

        private int colorForStatus(Diagnostics.Status s) {
            return switch (s) {
                case OK   -> Theme.C_SUCCESS;
                case WARN -> Theme.C_WARN;
                case FAIL -> Theme.C_DANGER;
                // C_MUTED, not C_FAINT: this pill sits on the inset well, where the faintest
                // tier is indistinguishable from the surface it is drawn on.
                case INFO -> Theme.C_MUTED;
            };
        }

        @Override
//? if forge {
/*        public boolean mouseScrolled(double mx, double my, double sy) {
*///?} else {
        public boolean mouseScrolled(double mx, double my, double sx, double sy) {
//?}
            if (!isMouseOver(mx, my)) return false;
            // Clamp against the end of the list, not just at zero. Without the upper bound the
            // counter kept climbing while scrolling down past the last row, and the same number
            // of scrolls back up did nothing visible — the list looked frozen until you
            // out-scrolled the accumulated slack. renderWidget clamped only for drawing and
            // threw the clamped value away, so the field itself was never corrected.
            int rowsVisible = (getHeight() - 8) / ROW_H;
            int maxScroll = Math.max(0, results.size() - rowsVisible);
            scroll = Math.max(0, Math.min(scroll - (int) Math.signum(sy), maxScroll));
            return true;
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput n) {}
    }
}
