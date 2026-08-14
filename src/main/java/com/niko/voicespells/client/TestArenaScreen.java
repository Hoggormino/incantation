package com.niko.voicespells.client;

import com.niko.voicespells.spells.SpellIndex;
import com.niko.voicespells.spells.SpellInfo;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Test Arena — practice voice casting without consequence.
 *
 * The base "no cast while a screen is open" rule already in {@link VoiceController} means
 * that with this screen up, recognised phrases get recorded (with "(menu)" suffix) but never
 * dispatch as real casts. The arena leans into that: it surfaces what the recogniser is
 * hearing live, what spell it would have picked, and the current confidence + tier.
 *
 * Use it to learn spell pronunciations safely — no mana spent, no cooldowns triggered, no
 * mobs killed by accident. Bonus: mic frames are still flowing in the background so the
 * audio meter + last-heard updates in real time.
 */
public final class TestArenaScreen extends Screen {

    // Preferred dimensions — clamped at init() time so the panel stays inside the screen
    // at every Minecraft GUI Scale. Layout math uses the runtime {@code panelW} / {@code panelH}
    // fields, not these constants.
    private static final int PANEL_W_PREF = 460;
    private static final int PANEL_H_PREF = 308;
    private static final int ROW_H   = 12;

    private final Screen parent;
    /** Top-left of the runtime panel + clamped dimensions; recomputed every {@link #init()}. */
    private int px, py, panelW, panelH;
    private final long openedAtNanos = System.nanoTime();

    public TestArenaScreen(Screen parent) {
        super(Component.literal("Test Arena"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        // Hold the microphone open for as long as this screen is up.
        //
        // The whole point of the arena is watching the meter move and reading what was heard,
        // but captureAllowedNow() closes the device when the game is paused — and in single
        // player opening any screen pauses the game. So the one place the live feed matters
        // most was the one place it could never arrive. Same override the first-run wizard
        // uses for its mic check. Released in onClose() and, as a safety net, removed().
        VoiceController.setDiagnosticCapture("testarena", true);

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

        addRenderableWidget(NeonButton.of(px + panelW - Theme.PAD - 80, py + panelH - 28,
            80, 20, CommonComponents.GUI_BACK, b -> onClose()));
    }

    @Override
    public void onClose() {
        try { VoiceController.setDiagnosticCapture("testarena", false); } catch (Throwable ignored) {}
        if (minecraft != null) minecraft.setScreen(parent);
    }

    @Override
    public void removed() {
        // Safety net for exits that bypass onClose() — another mod calling setScreen, a
        // disconnect, a resource reload. Leaving the device open behind the title screen is
        // exactly the bug the gating work set out to kill. setDiagnosticCapture is idempotent.
        try { VoiceController.setDiagnosticCapture("testarena", false); } catch (Throwable ignored) {}
        super.removed();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        // Vanilla backdrop first (blurred world in-game, dirt on the title screen, and it
        // honours the player's Menu Background Blur setting), then our own dim on top so
        // the panel still reads. Painting only a flat scrim, as this did before, opted out
        // of all of that and was a large part of why the screens felt foreign.
        Theme.background(this, g, mouseX, mouseY, partial);
        g.fill(0, 0, this.width, this.height, Theme.C_SCRIM);
        g.fill(px, py, px + panelW, py + panelH, Theme.C_PANEL);
        Theme.headerBand(g, px, py, panelW, Theme.HEADER_H);

        super.render(g, mouseX, mouseY, partial);

        Theme.roundedFrame(g, px, py, panelW, panelH, Theme.C_BORDER);
        Theme.accentGlow(g, px + Theme.PAD, py + Theme.HEADER_H, panelW - Theme.PAD * 2);

        int x = px + Theme.PAD;
        int y = py + Theme.HEADER_H + Theme.GAP_MD;

        // Loud disclaimer banner — players were closing the arena and reporting "spells don't
        // cast" because they didn't read the intro. Promote it to a bordered banner with a
        // distinct color so it's hard to miss.
        int discW = panelW - Theme.PAD * 2;
        int discH = 22;
        g.fill(x, y, x + discW, y + discH, Theme.C_INSET);
        Theme.roundedFrame(g, x, y, discW, discH, Theme.C_ACCENT_SOFT);
        g.fill(x + 1, y + 1, x + 3, y + discH - 1, Theme.C_ACCENT_BRIGHT);
        g.drawString(font, Component.literal("PRACTICE MODE — spells will NOT actually cast."),
            x + 8, y + 3, Theme.C_ACCENT_BRIGHT, !Theme.lightSurface());
        g.drawString(font, Component.literal("Close this screen to cast for real."),
            x + 8, y + 13, Theme.C_MUTED, !Theme.lightSurface());
        y += discH + 6;

        // Hard-warn if the player isn't in a world: capture is paused there, so nothing on
        // this screen will move. Without this banner the screen looks broken.
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            int bannerW = panelW - Theme.PAD * 2;
            int bannerH = 24;
            g.fill(x, y, x + bannerW, y + bannerH, Theme.C_INSET);
            Theme.roundedFrame(g, x, y, bannerW, bannerH, Theme.C_DANGER);
            g.drawString(font, Component.literal("⚠ Open a world first."), x + 6, y + 4,
                Theme.C_DANGER, !Theme.lightSurface());
            g.drawString(font,
                Component.literal("Recognition only runs in-game, so nothing fires here."),
                x + 6, y + 14, Theme.C_MUTED, !Theme.lightSurface());
            y += bannerH + 8;
        }

        // Big audio meter so the player can confirm their mic is being heard.
        int meterW = panelW - Theme.PAD * 2;
        int meterH = 12;
        g.fill(x, y, x + meterW, y + meterH, Theme.C_INSET);
        Theme.roundedFrame(g, x, y, meterW, meterH, Theme.C_DIVIDER);
        float level = Math.max(0f, Math.min(1f, VoiceController.audioLevel()));
        int fillW = (int) (level * (meterW - 4));
        if (fillW > 0) {
            g.fill(x + 2, y + 2, x + 2 + fillW, y + meterH - 2, Theme.C_ACCENT);
            g.fill(x + 2 + fillW - 1, y + 2, x + 2 + fillW, y + meterH - 2, Theme.C_ACCENT_BRIGHT);
        }
        y += meterH + 8;

        // "Try saying:" — pick a deterministic-per-session suggestion. Encourages the player
        // to attempt something specific rather than just stare at the meter.
        String suggestion = sessionSuggestion();
        if (!suggestion.isEmpty()) {
            g.drawString(font, Component.literal("Try saying: "), x, y, Theme.C_FAINT, !Theme.lightSurface());
            int prefixW = font.width("Try saying: ");
            g.drawString(font, Component.literal(suggestion), x + prefixW, y,
                Theme.C_ACCENT_BRIGHT, !Theme.lightSurface());
            y += 14;
        }

        // Last heard line — what Vosk thinks the player just said, regardless of match.
        // Vosk partials accumulate within an utterance, so a long continuous string of words
        // can pile up. Cap to the panel width so the line never bleeds off-screen.
        String last = VoiceController.lastHeard();
        if (last == null || last.isEmpty()) last = "(say something)";
        int labelW = font.width("Last heard:  ");
        int availW = panelW - Theme.PAD * 2 - labelW;
        last = fitToWidth(last, availW);
        g.drawString(font, Component.literal("Last heard:  "), x, y, Theme.C_FAINT, !Theme.lightSurface());
        g.drawString(font, Component.literal(last),
            x + labelW, y, Theme.C_TEXT, !Theme.lightSurface());
        y += 14;

        // Status + transmission badges.
        String status = VoiceController.statusLine();
        String mic = VoiceController.isHearingNow() ? "transmitting" : "idle";
        String statusLine = "Status: " + (status.isEmpty() ? "warming up" : status)
            + "    Mic: " + mic;
        g.drawString(font, Component.literal(statusLine), x, y, Theme.C_FAINT, !Theme.lightSurface());
        y += 16;

        // Recent recognition list — same source as the Live Monitor, narrower view. Entries
        // with the "(menu)" suffix are what *would have* cast; perfect for practice mode.
        g.drawString(font, Component.literal("RECENT — would have cast:"), x, y,
            Theme.C_MUTED, !Theme.lightSurface());
        y += 12;
        int listH = panelH - (y - py) - 36;
        g.fill(x, y, x + meterW, y + listH, Theme.C_INSET);
        Theme.insetShadow(g, x, y, meterW);
        Theme.roundedFrame(g, x, y, meterW, listH, Theme.C_DIVIDER);

        List<VoiceController.RecognitionEvent> events = VoiceController.recentEvents();
        if (events.isEmpty()) {
            g.drawString(font, Component.literal("(no recognitions yet — try saying a spell)"),
                x + 6, y + 6, Theme.C_FAINT, !Theme.lightSurface());
        } else {
            long now = System.nanoTime();
            int rowY = y + 4;
            int maxRows = (listH - 8) / ROW_H;
            int shown = 0;
            for (VoiceController.RecognitionEvent e : events) {
                if (shown >= maxRows) break;
                // Skip events that landed before this arena opened — keep the focus on
                // the player's current practice session.
                if (e.nanoTime() < openedAtNanos) continue;
                long ageSec = TimeUnit.NANOSECONDS.toSeconds(now - e.nanoTime());
                String outcome;
                int color;
                if (e.matched() == null) {
                    outcome = "no match";
                    color = Theme.F_NOMATCH;
                } else if (e.matched().contains("low conf")) {
                    outcome = "rejected: " + e.matched();
                    color = Theme.F_DEDUP;
                } else {
                    String spellId = e.matched().split(" ")[0];
                    SpellInfo info = SpellInfo.of(spellId);
                    String name = (info.name != null && !info.name.isEmpty())
                        ? info.name : prettyId(spellId);
                    outcome = "→ " + name;
                    color = Theme.F_MATCH;
                }
                String tier = e.tier() == ' ' ? " " : String.valueOf(e.tier());
                String line = String.format(Locale.ROOT, "%2ds [%s] c%.2f  \"%s\"  %s",
                    ageSec, tier, e.confidence(), truncate(e.heard(), 16), outcome);
                g.drawString(font, Component.literal(line), x + 4, rowY, color, !Theme.lightSurface());
                rowY += ROW_H;
                shown++;
            }
            if (shown == 0) {
                g.drawString(font, Component.literal("(speak — entries appear here)"),
                    x + 6, y + 6, Theme.C_FAINT, !Theme.lightSurface());
            }
        }
    }

    /** Pick a deterministic-per-screen-session suggestion so the player has something to try. */
    private String sessionSuggestion() {
        List<SpellIndex.SpellRow> all = SpellIndex.allSpells();
        if (all.isEmpty()) return "";
        int idx = (int) (openedAtNanos & 0x7FFFFFFF) % all.size();
        SpellIndex.SpellRow row = all.get(idx);
        SpellInfo info = SpellInfo.of(row.id());
        return info.name == null || info.name.isEmpty() ? prettyId(row.id()) : info.name;
    }

    private static String prettyId(String id) {
        int colon = id.indexOf(':');
        return (colon >= 0 ? id.substring(colon + 1) : id).replace('_', ' ');
    }

    private static String truncate(String s, int max) {
        return s == null ? "" : (s.length() <= max ? s : s.substring(0, max - 1) + "…");
    }

    /** Trim {@code s} (from the LEFT, preserving the most recent words) so it renders in
     *  {@code maxW} pixels. Most recent text is what the player cares about — when Vosk
     *  keeps appending words to a long utterance, the tail is the live feedback. */
    private String fitToWidth(String s, int maxW) {
        if (s == null) return "";
        if (font.width(s) <= maxW) return s;
        String trimmed = s;
        while (!trimmed.isEmpty() && font.width("…" + trimmed) > maxW) {
            trimmed = trimmed.substring(1);
        }
        return "…" + trimmed;
    }
}
