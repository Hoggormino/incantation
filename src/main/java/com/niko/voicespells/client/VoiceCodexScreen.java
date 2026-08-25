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
        super(Component.translatable("voicespells.codex.title"));
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
        // Vanilla backdrop first (blurred world in-game, dirt on the title screen, and it
        // honours the player's Menu Background Blur setting), then our own dim on top so
        // the panel still reads. Painting only a flat scrim, as this did before, opted out
        // of all of that and was a large part of why the screens felt foreign.
        Theme.ground(this, g, mouseX, mouseY, partial);

        super.render(g, mouseX, mouseY, partial);


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
        String[] mileNames = {
            Component.translatable("voicespells.codex.tier_apprentice").getString(),
            Component.translatable("voicespells.codex.tier_adept").getString(),
            Component.translatable("voicespells.codex.tier_magus").getString(),
            Component.translatable("voicespells.codex.tier_archmage").getString() };
        int nextIdx = mileVals.length - 1;
        for (int i = 0; i < mileVals.length; i++) {
            if (total < mileVals[i]) { nextIdx = i; break; }
        }
        int next = mileVals[nextIdx];
        int progress = Math.min(total, next);
        String currentTier = Component.translatable(
                            total >= 1000 ? "voicespells.codex.tier_archmage"
                          : total >= 200  ? "voicespells.codex.tier_magus"
                          : total >= 50   ? "voicespells.codex.tier_adept"
                          : total >= 10   ? "voicespells.codex.tier_apprentice"
                                          : "voicespells.codex.tier_novice").getString();

        int cardH = 70;
        Theme.well(g, x, y, colW, cardH);

        // Row 1: section header + tier. The right-hand value is measured FIRST so the left-hand
        // label can be trimmed to what it leaves. Below about a 400px window these two ran
        // straight through each other and the row read "VOICE CASTIier: Adept" - the same
        // collision the stat rows below already solve.
        String tierLabel = Component.translatable("voicespells.codex.tier").getString()
            + ": " + currentTier;
        int tierW = font.width(tierLabel);
        g.drawString(font, Component.literal(Theme.fit(font,
                Component.translatable("voicespells.codex.section_casting").getString(),
                colW - tierW - 22)),
            x + 8, y + 5, Theme.C_MUTED, !Theme.lightSurface());
        g.drawString(font, Component.literal(tierLabel),
            x + colW - tierW - 8, y + 5, Theme.C_TEXT, !Theme.lightSurface());

        // Row 2: count + next, same rule.
        String nextLabel = (total >= 1000)
            ? Component.translatable("voicespells.codex.max_tier").getString()
            : Component.translatable("voicespells.codex.next").getString() + ": "
              + mileNames[nextIdx] + " (" + next + ")";
        int nextW = font.width(nextLabel);
        g.drawString(font, Component.literal(Theme.fit(font, total + " casts", colW - nextW - 22)),
            x + 8, y + 18, Theme.C_TEXT, !Theme.lightSurface());
        g.drawString(font, Component.literal(nextLabel),
            x + colW - nextW - 8, y + 18, Theme.C_MUTED, !Theme.lightSurface());

        // Row 3: progress bar
        int barX = x + 8;
        int barY = y + 32;
        int barW = colW - 16;
        int barH = 6;
        g.fill(barX, barY, barX + barW, barY + barH, Theme.C_PANEL);
        int fillW = (int) ((double) progress / next * (barW - 2));
        if (fillW > 0) {
            g.fill(barX + 1, barY + 1, barX + 1 + fillW, barY + barH - 1, Theme.C_SUCCESS);
        }

        // Row 4: milestone badges — green ✓ for earned, accent ▶ for next, muted ○ for upcoming.
        int badgeY = y + 48;
        int badgeSpacing = (colW - 16) / mileVals.length;
        for (int i = 0; i < mileVals.length; i++) {
            int bx = x + 8 + i * badgeSpacing;
            boolean reached = total >= mileVals[i];
            boolean isNext  = (i == nextIdx) && !reached;
            String mark  = reached ? "✓" : (isNext ? "▶" : "○");
            int color    = reached ? Theme.C_SUCCESS
                         : isNext  ? Theme.C_TEXT
                                   : Theme.C_FAINT;
            g.drawString(font, Component.literal(mark + " " + mileVals[i]), bx, badgeY,
                color, !Theme.lightSurface());
        }

        // 2, not 6. Those four pixels are the difference between the eight rows fitting inside
        // the well and the last one drawing below it - see the pitch note below.
        y += cardH + 2;

        // The stat block gets the same well the card above and the list beside it have.
        // Unframed, it was the only bare element on a screen with two framed panels, so it read
        // as text spilled underneath the card rather than as the third panel it is. The well runs
        // to the same bottom edge as the list on the right, which is what makes the two columns
        // look like one layout instead of two.
        int statsTop = y - 4;
        int statsBottom = py + panelH - 36;
        Theme.well(g, x, statsTop, colW, statsBottom - statsTop);
        x += 8;                       // inset the rows inside their new frame
        y += 2;

        // Row pitch derived from the room that exists, not a fixed 13.
        //
        // Eight rows at ROW_H need a 251px panel, and the panel is min(280, height - 16). On a
        // 720p window at GUI Scale 3 - not an edge case - that is 224, so the pitch compresses.
        // Compressing is the right answer: losing "Speak to cast" and "Daily challenge" entirely
        // would be worse, and those are the two a returning player checks.
        //
        // But the 9px floor is a floor, so at the smallest panel the rows stopped compressing and
        // started overflowing instead - the last one drew 3px BELOW the well that is supposed to
        // contain it. rowsBottom is now the well's own inner edge, and the count is clamped to
        // what actually fits, so the block can never draw outside itself. At Minecraft's 240
        // minimum all eight still fit.
        // Pitch tops out at 16, not 13, because each row now carries a vanilla button face and a
        // face wants room to read as one. It still compresses, and below 12 it stops drawing the
        // face at all rather than squashing it - see line(). Losing the face on a tiny window is
        // a much smaller loss than losing a row.
        final int ROWS = 8;
        int rowsBottom = statsBottom - 4;
        int rowH = Math.max(9, Math.min(16, (rowsBottom - y) / ROWS));
        this.rowsFloor = rowsBottom;  // line() drops any row that would land past this
        this.rowPitch  = rowH;
        this.rowWidth  = colW - 16;   // the well minus the 8px inset applied to x on both sides

        // Stat rows
        line(g, x, y, "voicespells.codex.total_casts",     String.valueOf(VoiceStats.totalCasts())); y += rowH;
        line(g, x, y, "voicespells.codex.best_streak",     String.valueOf(VoiceStats.longestStreak())); y += rowH;
        line(g, x, y, "voicespells.codex.schools_touched", String.valueOf(VoiceStats.distinctSchoolsCast())); y += rowH;
        line(g, x, y, "voicespells.codex.distinct_spells", String.valueOf(VoiceStats.snapshotAll().size())); y += rowH;
        line(g, x, y, "voicespells.codex.first_cast",      VoiceStats.fmtDate(VoiceStats.firstCastMs())); y += rowH;
        line(g, x, y, "voicespells.codex.last_cast",       VoiceStats.fmtElapsed(VoiceStats.lastCastMs())); y += rowH;
        double avgMs = VoiceController.averageLatencyMs();
        // "Speak to cast" — measured from first audio of the utterance to dispatch. Median,
        // not mean, so a hesitant cast every now and then doesn't blow the number up.
        //
        // The window lives in memory only, so it is empty until the first cast of the session
        // even for a player with hundreds of lifetime casts. Showing a bare "—" next to
        // "Total casts 44" reads as a broken counter, so the label says which scope it means and
        // the placeholder says what to do about it.
        // "Speak to cast (session)" did not fit beside its own value and was being truncated to
        // "Speak to cast (ses..." - a label that has to be cut to make room for its value is the
        // wrong label. The scope moves into the value, which has room for it, and the empty case
        // still says what to do about it rather than showing a bare dash.
        line(g, x, y, "voicespells.codex.speak_to_cast",
            avgMs < 0 ? Component.translatable("voicespells.codex.latency_pending").getString()
                      : String.format(Locale.ROOT, "%.0fms this session", avgMs)); y += rowH;
        int streak = VoiceStats.sotdStreak();
        // This counts consecutive days of completing the SPELL-OF-THE-DAY challenge, not days
        // the mod was used — "Daily streak" invited the second reading and then showed "—" to a
        // player casting every day, which looks like a bug rather than an uncompleted challenge.
        line(g, x, y, "voicespells.codex.daily_challenge", streak > 0 ? streak + " day(s)"
            : Component.translatable("voicespells.codex.daily_none").getString()); y += rowH;

        // (Right-column heading is drawn inside TopList.renderWidget so it sits below the
        //  accent rule glow instead of overlapping it.)
    }

    /**
     * Bottom edge the stat rows may not cross, set by the layout pass each frame. The rows are
     * drawn as a flat sequence rather than a loop, so this is how the sequence learns where the
     * well it lives in ends.
     */
    private int rowsFloor = Integer.MAX_VALUE;
    /** Row pitch and inner width for this frame, set by the layout pass. */
    private int rowPitch = ROW_H;
    private int rowWidth = 0;

    private void line(GuiGraphics g, int x, int y, String labelKey, String value) {
        // A row that would draw below the well is dropped, not clipped. The pitch above already
        // compresses to fit, and at every window size Minecraft allows all eight rows survive -
        // this is the guard for the size that does not exist yet.
        if (y + 8 > rowsFloor) return;
        // The rows are drawn inset inside the stats well, so the column they share is narrower
        // than the well by that inset on both sides.
        int colW = rowWidth;

        // The row sits on a vanilla button face, the same surface the config screen's settings
        // use. That screen is built from real widgets and these rows were bare drawString onto a
        // flat panel, which is why the two halves of the mod did not look like the same mod.
        //
        // Below a 12px pitch the face is dropped rather than squashed: its nine-slice is 4px of
        // border top and bottom, so under 12 there is nothing left of the middle and it reads as
        // a smear rather than a control.
        if (rowPitch >= 12 && rowWidth > 0) {
            Theme.rowFace(g, x - 4, y - 2, rowWidth + 8, rowPitch - 1);
        }

        int vw = font.width(value);
        // Trim the LABEL to whatever the value leaves, rather than letting the two overlap.
        // "Speak to cast (session)" against "after 1st cast" needs more than the column has, and
        // the two strings were drawn straight through each other.
        String label = Component.translatable(labelKey).getString();
        String shown = Theme.fit(font, label, colW - vw - 6);
        g.drawString(font, Component.literal(shown), x, y, Theme.C_MUTED, !Theme.lightSurface());
        g.drawString(font, Component.literal(value), x + colW - vw, y, Theme.C_TEXT, !Theme.lightSurface());
    }

    /** Top-N spells by cast count. Single fixed view, not scrollable (keep it simple). */
    private final class TopList extends AbstractWidget {
        TopList(int x, int y, int w, int h) {
            super(x, y, w, h, Component.empty());
        }
        @Override
        protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partial) {
            int x = getX(), y = getY(), w = getWidth(), h = getHeight();
            Theme.well(g, x, y, w, h);

            // Header drawn inside the widget so it's clear of the accent rule glow up top.
            // No rule under it. It was the only horizontal separator in twelve screens, and the
            // 18px headerOffset below already supplies the gap it was drawing. x+8/y+5 matches
            // "VOICE CASTING" in the card to its left, which sat 4px further in and 1px lower.
            g.drawString(font, Component.translatable("voicespells.codex.most_cast"), x + 8, y + 5,
                Theme.C_MUTED, !Theme.lightSurface());

            int headerOffset = 18;
            int rowsArea = h - 4 - headerOffset;
            List<Map.Entry<String, Integer>> top = VoiceStats.topSpells(Math.max(1, rowsArea / ROW_H));
            if (top.isEmpty()) {
                g.drawString(font, Component.translatable("voicespells.codex.empty"),
                    x + 8, y + headerOffset + 2, Theme.C_FAINT, !Theme.lightSurface());
                return;
            }
            int ry = y + headerOffset;
            int rank = 1;
            for (Map.Entry<String, Integer> e : top) {
                SpellInfo info = SpellInfo.of(e.getKey());
                // displayName(), not name - the localised spell name, not the registry path.
                String dn = info.displayName().getString();
                String name = (dn == null || dn.isEmpty()) ? shortId(e.getKey()) : dn;
                String countStr = "× " + e.getValue();
                g.drawString(font, Component.literal(rank + "."), x + 8, ry,
                    Theme.C_MUTED, !Theme.lightSurface());
                int cw = font.width(countStr);
                // Trim to what the count leaves. Long names ("Pillar of the Resounding Earth")
                // were drawn full-length straight through the right-aligned "x 22".
                String shown = Theme.fit(font, name, w - 22 - cw - 14);
                g.drawString(font, Component.literal(shown), x + 22, ry, Theme.C_TEXT, !Theme.lightSurface());
                g.drawString(font, Component.literal(countStr), x + w - cw - 8, ry,
                    Theme.C_TEXT, !Theme.lightSurface());
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
