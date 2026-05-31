package com.niko.voicespells.client;

import com.niko.voicespells.spells.SpellInfo;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Voice Codex — read-only stats screen. Shows aggregate totals on the left (rank, total casts,
 * best streak, schools touched, first/last cast) and the per-spell leaderboard on the right.
 *
 * Backed by {@link VoiceStats}, which persists to disk. Opened from {@link ConfigMoreScreen}.
 */
public final class VoiceCodexScreen extends Screen {

    // Preferred dimensions — clamped at init() time so the panel stays inside the screen
    // at every Minecraft GUI Scale. Layout math uses the runtime {@code panelW} / {@code panelH}
    // fields, not these constants.
    private static final int PANEL_W_PREF = 440;
    private static final int PANEL_H_PREF = 280;
    private static final int ROW_H   = 13;

    private final Screen parent;
    /** Top-left of the runtime panel + clamped dimensions; recomputed every {@link #init()}. */
    private int px, py, panelW, panelH;
    private TopList list;

    public VoiceCodexScreen(Screen parent) {
        super(Component.literal("Voice Codex"));
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

        int listX = px + panelW / 2 + 6;
        int listY = py + Theme.HEADER_H + Theme.GAP_MD;
        int listW = panelW / 2 - Theme.PAD - 6;
        int listH = panelH - Theme.HEADER_H - Theme.GAP_MD - 36;
        list = new TopList(listX, listY, listW, listH);
        addRenderableWidget(list);

        addRenderableWidget(NeonButton.of(px + panelW - Theme.PAD - 80, py + panelH - 28,
            80, 20, CommonComponents.GUI_BACK, b -> onClose()));
    }

    @Override
    public void onClose() {
        if (minecraft != null) minecraft.setScreen(parent);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        g.fill(0, 0, this.width, this.height, Theme.C_SCRIM);
        g.fill(px, py, px + panelW, py + panelH, Theme.C_PANEL);
        Theme.headerBand(g, px, py, panelW, Theme.HEADER_H);

        super.render(g, mouseX, mouseY, partial);

        Theme.roundedFrame(g, px, py, panelW, panelH, Theme.C_BORDER);
        Theme.accentGlow(g, px + Theme.PAD, py + Theme.HEADER_H, panelW - Theme.PAD * 2);

        renderSummary(g);
    }

    /** Left half: aggregate stats with quiet section headings. */
    private void renderSummary(GuiGraphics g) {
        int x = px + Theme.PAD;
        int y = py + Theme.HEADER_H + Theme.GAP_MD;
        int colW = panelW / 2 - Theme.PAD - 6;

        // Summary card. Three lines + badge row:
        //   1. "VOICE CASTING"  ............  "Tier: <name>"  (current rank)
        //   2. "N casts"  ..................  "next: <NextTier> (M)" or "max tier reached"
        //   3. progress bar to the next milestone
        //   4. badge row: ✓ 10  ✓ 50  ▶ 200  ○ 1000 — communicates what's done vs upcoming
        int total = VoiceStats.totalCasts();
        int[] mileVals = { 10, 50, 200, 1000 };
        String[] mileNames = { "Apprentice", "Adept", "Magus", "Archmage" };
        int nextIdx = mileVals.length - 1;
        for (int i = 0; i < mileVals.length; i++) {
            if (total < mileVals[i]) { nextIdx = i; break; }
        }
        int next = mileVals[nextIdx];
        int progress = Math.min(total, next);
        String currentTier = total >= 1000 ? "Archmage"
                          : total >= 200  ? "Magus"
                          : total >= 50   ? "Adept"
                          : total >= 10   ? "Apprentice"
                                          : "Novice";

        int cardH = 70;
        g.fill(x, y, x + colW, y + cardH, Theme.C_INSET);
        Theme.roundedFrame(g, x, y, colW, cardH, Theme.C_DIVIDER);

        // Row 1: section header + tier
        g.drawString(font, Component.literal("VOICE CASTING"), x + 8, y + 5,
            Theme.C_FAINT, false);
        String tierLabel = "Tier: " + currentTier;
        int tierW = font.width(tierLabel);
        g.drawString(font, Component.literal(tierLabel),
            x + colW - tierW - 8, y + 5, Theme.C_ACCENT_BRIGHT, false);

        // Row 2: count + next
        g.drawString(font, Component.literal(total + " casts"),
            x + 8, y + 18, Theme.C_TEXT, false);
        String nextLabel = (total >= 1000) ? "max tier reached"
            : "next: " + mileNames[nextIdx] + " (" + next + ")";
        int nextW = font.width(nextLabel);
        g.drawString(font, Component.literal(nextLabel),
            x + colW - nextW - 8, y + 18, Theme.C_FAINT, false);

        // Row 3: progress bar
        int barX = x + 8;
        int barY = y + 32;
        int barW = colW - 16;
        int barH = 6;
        g.fill(barX, barY, barX + barW, barY + barH, Theme.C_PANEL);
        Theme.roundedFrame(g, barX, barY, barW, barH, Theme.C_DIVIDER);
        int fillW = (int) ((double) progress / next * (barW - 2));
        if (fillW > 0) {
            g.fill(barX + 1, barY + 1, barX + 1 + fillW, barY + barH - 1, Theme.C_ACCENT);
            if (fillW > 1) {
                g.fill(barX + fillW, barY + 1, barX + 1 + fillW, barY + barH - 1,
                    Theme.C_ACCENT_BRIGHT);
            }
        }

        // Row 4: milestone badges — green ✓ for earned, accent ▶ for next, muted ○ for upcoming.
        int badgeY = y + 48;
        int badgeSpacing = (colW - 16) / mileVals.length;
        for (int i = 0; i < mileVals.length; i++) {
            int bx = x + 8 + i * badgeSpacing;
            boolean reached = total >= mileVals[i];
            boolean isNext  = (i == nextIdx) && !reached;
            String mark  = reached ? "✓" : (isNext ? "▶" : "○");
            int color    = reached ? Theme.F_MATCH
                         : isNext  ? Theme.C_ACCENT_BRIGHT
                                   : Theme.C_FAINT;
            g.drawString(font, Component.literal(mark + " " + mileVals[i]), bx, badgeY,
                color, false);
        }

        y += cardH + 6;

        // Stat rows
        line(g, x, y, "Total casts",       String.valueOf(VoiceStats.totalCasts())); y += ROW_H;
        line(g, x, y, "Best streak",       String.valueOf(VoiceStats.longestStreak())); y += ROW_H;
        line(g, x, y, "Schools touched",   String.valueOf(VoiceStats.distinctSchoolsCast())); y += ROW_H;
        line(g, x, y, "Distinct spells",   String.valueOf(VoiceStats.snapshotAll().size())); y += ROW_H;
        line(g, x, y, "First cast",        VoiceStats.fmtDate(VoiceStats.firstCastMs())); y += ROW_H;
        line(g, x, y, "Last cast",         VoiceStats.fmtElapsed(VoiceStats.lastCastMs())); y += ROW_H;
        double avgMs = VoiceController.averageLatencyMs();
        // "Speak to cast" — measured from first audio of the utterance to dispatch. Median,
        // not mean, so a hesitant cast every now and then doesn't blow the number up.
        line(g, x, y, "Speak to cast",
            avgMs < 0 ? "—" : String.format(java.util.Locale.ROOT, "%.0fms (median)", avgMs)); y += ROW_H;
        int streak = VoiceStats.sotdStreak();
        line(g, x, y, "Daily streak", streak > 0 ? streak + " day(s)" : "—"); y += ROW_H;

        // (Right-column heading is drawn inside TopList.renderWidget so it sits below the
        //  accent rule glow instead of overlapping it.)
    }

    private void line(GuiGraphics g, int x, int y, String label, String value) {
        g.drawString(font, Component.literal(label), x, y, Theme.C_MUTED, false);
        int colW = panelW / 2 - Theme.PAD - 6;
        int vw = font.width(value);
        g.drawString(font, Component.literal(value), x + colW - vw, y, Theme.C_TEXT, false);
    }

    /** Top-N spells by cast count. Single fixed view, not scrollable (keep it simple). */
    private final class TopList extends AbstractWidget {
        TopList(int x, int y, int w, int h) {
            super(x, y, w, h, Component.empty());
        }
        @Override
        protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partial) {
            int x = getX(), y = getY(), w = getWidth(), h = getHeight();
            g.fill(x, y, x + w, y + h, Theme.C_INSET);
            Theme.insetShadow(g, x, y, w);
            Theme.roundedFrame(g, x, y, w, h, Theme.C_DIVIDER);

            // Header drawn inside the widget so it's clear of the accent rule glow up top.
            g.drawString(font, Component.literal("MOST CAST"), x + 4, y + 4,
                Theme.C_FAINT, false);
            g.fill(x + 4, y + 14, x + w - 4, y + 15, Theme.C_DIVIDER);

            int headerOffset = 18;
            int rowsArea = h - 4 - headerOffset;
            List<Map.Entry<String, Integer>> top = VoiceStats.topSpells(Math.max(1, rowsArea / ROW_H));
            if (top.isEmpty()) {
                g.drawString(font, Component.literal("(no casts yet — speak a spell!)"),
                    x + 6, y + headerOffset + 2, Theme.C_FAINT, false);
                return;
            }
            int ry = y + headerOffset;
            int rank = 1;
            for (Map.Entry<String, Integer> e : top) {
                SpellInfo info = SpellInfo.of(e.getKey());
                String name = (info.name == null || info.name.isEmpty())
                    ? shortId(e.getKey()) : info.name;
                String countStr = "× " + e.getValue();
                g.drawString(font, Component.literal(rank + "."), x + 4, ry,
                    Theme.C_FAINT, false);
                g.drawString(font, Component.literal(name), x + 18, ry, Theme.C_TEXT, false);
                int cw = font.width(countStr);
                g.drawString(font, Component.literal(countStr), x + w - cw - 6, ry,
                    Theme.C_ACCENT, false);
                ry += ROW_H;
                rank++;
            }
        }

        private String shortId(String id) {
            int colon = id.indexOf(':');
            return (colon >= 0 ? id.substring(colon + 1) : id).replace('_', ' ');
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput n) {}
    }
}
