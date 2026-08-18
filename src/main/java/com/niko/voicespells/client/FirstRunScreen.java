package com.niko.voicespells.client;

import com.niko.voicespells.VoiceSpellsConfig;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Three-step welcome wizard. Designed to be opened <b>in-game</b>, after the Vosk model and
 * the microphone are both live — that's the only context where the mic test and recent
 * recognitions are actually meaningful.
 *
 *  1. <b>Welcome</b> — what the mod does, no-keybind flow.
 *  2. <b>Mic check</b> — live audio meter + last heard phrase + status indicators. Tracks
 *     whether the user has actually demonstrated mic activity; the Next button stays muted
 *     until we've seen the meter move so the player can't sleepwalk past a broken mic.
 *  3. <b>Try a spell</b> — live recent-recognition feed, button to open the spell list, and a
 *     pointer to equipping a spellbook.
 *
 * Finishing (Done, Skip, or Esc) sets {@code firstRun = false} so this only pops once. Users
 * can re-open it manually from the "Welcome" button on the config screen.
 */
public final class FirstRunScreen extends Screen {

    // Preferred dimensions — clamped at init() time so the panel stays inside the screen
    // at every Minecraft GUI Scale. Layout math uses the runtime {@code panelW} / {@code panelH}
    // fields, not these constants.
    private static final int PANEL_W_PREF = 360;
    private static final int PANEL_H_PREF = 230;
    private static final int STEPS        = 3;

    private final Screen parent;
    private int step = 0;
    /** Top-left of the runtime panel + clamped dimensions; recomputed every {@link #init()}. */
    private int px, py, panelW, panelH;

    /** Set once we ever see a non-trivial audio level during this screen's lifetime — used to
     *  brighten the Next button on the mic-check step so the player has a clear "I demonstrated
     *  the meter, I'm ready" signal. */
    private boolean micDetectedThisVisit = false;
    /** Nanotime when the screen opened, so we can detect "a cast happened while this screen
     *  was up" and auto-advance step 3. */
    private final long openedAtNanos = System.nanoTime();

    private NeonButton nextBtn;

    public FirstRunScreen(Screen parent) {
        super(Component.literal("Welcome to Incantation"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        // This wizard normally runs from the title screen, where there is no level and no player,
        // so the ordinary capture gate keeps the device closed. The mic check explicitly asks the
        // player to speak, so hold the device open for as long as the wizard is showing. Released
        // in finish(), which every exit path goes through.
        VoiceController.setDiagnosticCapture("wizard", true);

        // Clamp the panel to the available screen so a large GUI Scale doesn't push the Next /
        // Skip buttons off the bottom or the title off the right edge.
        panelW = Theme.fit(PANEL_W_PREF, width);
        panelH = Theme.fit(PANEL_H_PREF, height);
        px = (width  - panelW) / 2;
        py = (height - panelH) / 2;

        StringWidget titleW = new StringWidget(px, py + (Theme.HEADER_H - 9) / 2,
            panelW, 9, title, font);
        titleW.alignCenter();
        titleW.setColor(Theme.C_TEXT);
        addRenderableWidget(titleW);

        int btnY = py + panelH - 28;
        int btnW = 96;

        if (step > 0) {
            addRenderableWidget(NeonButton.of(px + Theme.PAD, btnY, btnW, 20,
                Component.literal("Back"), b -> { step--; rebuildWidgets(); }));
        }
        if (step < STEPS - 1) {
            addRenderableWidget(NeonButton.of(px + panelW / 2 - btnW / 2, btnY, btnW, 20,
                Component.literal("Skip"), b -> finish()));
        }
        // On step 2 (Try a spell), offer a direct shortcut into the spell list since it's
        // the natural next destination.
        if (step == 2) {
            addRenderableWidget(NeonButton.of(px + panelW / 2 - btnW / 2, btnY, btnW, 20,
                Component.literal("Open Spell List"),
                b -> {
                    if (minecraft != null) minecraft.setScreen(
                        new VoiceSpellsSpellListScreen(this));
                }));
        }
        // On the mic-check step, offer the device picker on its own row above the nav buttons.
        // This is the step a player with a dead default capture device gets stuck on — the meter
        // never moves, Next stays disabled, and Skip was the only way out. Since the Windows
        // default recording device is so often a virtual driver that returns pure silence, the
        // fix has to be reachable from exactly here rather than from a submenu the player has
        // not discovered yet. The keyed capture holds mean the wizard and the picker can both
        // want the microphone without either revoking it from the other.
        if (step == 1) {
            addRenderableWidget(NeonButton.of(px + panelW / 2 - 80, btnY - 24, 160, 20,
                Component.literal("Meter dead? Choose microphone…"),
                b -> { if (minecraft != null) minecraft.setScreen(new AudioDevicesScreen(this)); }));
        }
        String nextLabel = (step == STEPS - 1) ? "Done" : "Next";
        nextBtn = NeonButton.of(px + panelW - Theme.PAD - btnW, btnY, btnW, 20,
            Component.literal(nextLabel), b -> {
                if (step == STEPS - 1) finish();
                else { step++; rebuildWidgets(); }
            });
        // On the mic-check step, gate Next behind actually seeing audio. Keeps the player from
        // sleepwalking past a broken mic by clicking past with no proof things work.
        if (step == 1 && !micDetectedThisVisit) nextBtn.active = false;
        addRenderableWidget(nextBtn);
    }

    private void finish() {
        // Drop the mic before anything else — if a write below throws, the device must still be
        // released rather than left open behind the title screen.
        try { VoiceController.setDiagnosticCapture("wizard", false); } catch (Throwable ignored) {}

        // Belt-and-braces persistence. saveToDisk() below writes the toml synchronously, but
        // it can still fail (read-only config dir, IO error), so we also flip a sticky flag in
        // VoiceStats, which writes stats.dat separately. The trigger checks BOTH, so as long as
        // either landed the wizard won't re-pop.
        //
        // This used to say the toml write was "async and can be lost on a fast exit". That was
        // not the real mechanism: on NeoForge the write simply never happened at all, because
        // set() does not persist and nothing called save(). The stats latch is the only reason
        // the wizard did not greet 1.21.1 players on every single launch.
        try {
            VoiceSpellsConfig.CLIENT.firstRun.set(false);
            // Persist immediately; the VoiceStats latch is the backup, not the record.
            VoiceSpellsConfig.saveToDisk();
            VoiceSpellsConfig.refreshCache();
        } catch (Throwable ignored) { /* close even if write fails */ }
        try { VoiceStats.markWizardSeen(); } catch (Throwable ignored) {}
        if (minecraft != null) minecraft.setScreen(parent);
    }

    @Override
    public void onClose() { finish(); }

    @Override
    public void removed() {
        // Safety net. finish() covers the normal exits, but a screen can be swapped out without
        // it — another mod calling setScreen, a disconnect, a resource reload. Leaving the device
        // open behind the title screen is precisely the bug the gating work set out to kill, so
        // release it unconditionally here too. setDiagnosticCapture is idempotent.
        try { VoiceController.setDiagnosticCapture("wizard", false); } catch (Throwable ignored) {}
        super.removed();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        // Latch mic-detected if we ever see audio during this screen's lifetime. Threshold
        // is small but above background noise so a quiet mic doesn't false-positive.
        if (VoiceController.audioLevel() > 0.06f) {
            // Just transitioned: if we were on the mic-check step, re-enable Next live.
            if (!micDetectedThisVisit && step == 1 && nextBtn != null) nextBtn.active = true;
            micDetectedThisVisit = true;
        }
        // Step 3 auto-advance: if the player actually casts something while this screen is up,
        // they've passed "try a spell" — finish the wizard for them.
        if (step == STEPS - 1
                && VoiceController.lastCastNanos() > openedAtNanos) {
            finish();
            return;
        }

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

        // Step indicator dots just below the accent rule.
        int dotsY = py + Theme.HEADER_H + 10;
        int spacing = 14, dotW = 8, dotH = 2;
        int dotsX = px + panelW / 2 - (STEPS * spacing - (spacing - dotW)) / 2;
        for (int i = 0; i < STEPS; i++) {
            int dx = dotsX + i * spacing;
            int color = (i == step) ? Theme.C_ACCENT_BRIGHT
                       : (i < step ? Theme.C_ACCENT_SOFT : Theme.C_DIVIDER);
            g.fill(dx, dotsY, dx + dotW, dotsY + dotH, color);
        }

        int x = px + Theme.PAD;
        int y = py + Theme.HEADER_H + 24;
        switch (step) {
            case 0 -> renderWelcome(g, x, y);
            case 1 -> renderMicCheck(g, x, y);
            case 2 -> renderTrySpell(g, x, y);
        }
    }

    // ------------------------------------------------------------------------ Step 0
    private void renderWelcome(GuiGraphics g, int x, int y) {
        drawHeading(g, x, y, "What this does");
        drawLines(g, x, y + 14, new String[] {
            "Cast Iron's Spells by speaking their",
            "names out loud — no other mod needed.",
            "",
            "You don't press anything to cast.",
            "When you say a spell name you have",
            "equipped, it casts.",
            "",
            "This wizard only opens once.",
            "You can reopen it from the config screen.",
        });
    }

    // ------------------------------------------------------------------------ Step 1
    private void renderMicCheck(GuiGraphics g, int x, int y) {
        drawHeading(g, x, y, "Mic + model check");
        y += 14;

        // Three live status pills: Model, microphone, Audio detected.
        String modelStatus = VoiceController.statusLine();
        if (modelStatus == null || modelStatus.isEmpty()) modelStatus = "warming up";
        boolean modelReady = "READY".equals(modelStatus);
        boolean micLive    = VoiceController.isHearingNow();

        int pillW = (panelW - Theme.PAD * 2 - 8 * 2) / 3;
        int pillY = y;
        drawPill(g, x,                       pillY, pillW, "Model",
            modelReady ? modelStatus : modelStatus + "…", modelReady);
        drawPill(g, x + (pillW + 8),         pillY, pillW, "Microphone",
            micLive ? "transmitting" : "idle", micLive);
        drawPill(g, x + (pillW + 8) * 2,     pillY, pillW, "Audio",
            micDetectedThisVisit ? "detected" : "waiting", micDetectedThisVisit);
        y += 28;

        drawLines(g, x, y, new String[] {
            "Talk into your mic now.",
            "The meter below should move while you speak.",
        });
        y += 22;

        // Big live audio meter. Reads VoiceController.audioLevel() each frame so this is
        // the canonical "is the mic actually reaching us" indicator.
        int meterH = 14;
        int meterW = panelW - Theme.PAD * 2;
        drawAudioMeter(g, x, y, meterW, meterH);
        y += meterH + 8;

        // Last heard phrase — the proof that recognition is actually firing. Empty until
        // the user says something a Vosk recognises. Cap to the panel width: Vosk partials
        // can accumulate across a long utterance and otherwise bleed off-screen.
        String last = VoiceController.lastHeard();
        boolean empty = (last == null || last.isEmpty());
        String prefix = "Last heard: ";
        int availW = panelW - Theme.PAD * 2 - font.width(prefix) - font.width("\"\"");
        String shown = empty ? "(nothing yet)" : "\"" + fitFromRight(last, availW) + "\"";
        g.drawString(font, Component.literal(prefix + shown), x, y,
            empty ? Theme.C_FAINT : Theme.C_TEXT, !Theme.lightSurface());
    }

    // ------------------------------------------------------------------------ Step 2
    private void renderTrySpell(GuiGraphics g, int x, int y) {
        drawHeading(g, x, y, "Try a spell");
        // Tightened from 7 lines to 4 — the original arrangement left a huge empty stretch
        // between the body and the feed box, which read as "broken layout" rather than
        // breathing room. The remaining hint is the actually-actionable part.
        String[] body = {
            "Equip a spellbook in your Curios slot",
            "(or hold a spellbook / imbued weapon).",
            "Then say a spell name — it casts.",
            "Open Spell List below to see every phrase.",
        };
        drawLines(g, x, y + 14, body);

        // Live recent-recognition feed. Sized to fill the rest of the panel so there's no
        // dead space between the body text and the buttons.
        int feedX = x;
        int bodyBottom = y + 14 + body.length * 11;
        int feedY = bodyBottom + 10;
        int feedW = panelW - Theme.PAD * 2;
        int feedH = panelH - (feedY - py) - 40; // leave room for buttons + a real gap
        if (feedH > 30) {
            g.drawString(font, Component.literal("Recent recognitions:"), feedX, feedY,
                Theme.C_MUTED, !Theme.lightSurface());
            feedY += 13;                          // label text + ~4px gap before the box
            feedH -= 13;
            g.fill(feedX, feedY, feedX + feedW, feedY + feedH, Theme.C_INSET);
            Theme.roundedFrame(g, feedX, feedY, feedW, feedH, Theme.C_DIVIDER);

            List<VoiceController.RecognitionEvent> events = VoiceController.recentEvents();
            if (events.isEmpty()) {
                g.drawString(font, Component.literal("(say a spell — entries appear here)"),
                    feedX + 4, feedY + 4, Theme.C_FAINT, !Theme.lightSurface());
            } else {
                long now = System.nanoTime();
                int rowY = feedY + 4;
                int lineH = 11;
                int maxRows = (feedH - 8) / lineH;
                for (int i = 0; i < events.size() && i < maxRows; i++) {
                    VoiceController.RecognitionEvent e = events.get(i);
                    long ageSec = TimeUnit.NANOSECONDS.toSeconds(now - e.nanoTime());
                    int color = e.matched() == null ? Theme.F_NOMATCH : Theme.F_MATCH;
                    String text = String.format(java.util.Locale.ROOT, "%2ds  \"%s\"  %s",
                        ageSec, e.heard(),
                        e.matched() == null ? "— no match" : "→ " + shortId(e.matched()));
                    g.drawString(font, Component.literal(text), feedX + 4, rowY, color, !Theme.lightSurface());
                    rowY += lineH;
                }
            }
        }
    }

    // ------------------------------------------------------------------------ Shared
    private void drawHeading(GuiGraphics g, int x, int y, String text) {
        g.drawString(font, Component.literal(text), x, y, Theme.C_HEADING, !Theme.lightSurface());
    }

    private void drawLines(GuiGraphics g, int x, int y, String[] lines) {
        for (int i = 0; i < lines.length; i++) {
            int color = lines[i].isEmpty() ? Theme.C_FAINT : Theme.C_TEXT;
            g.drawString(font, Component.literal(lines[i]), x, y + i * 11, color, !Theme.lightSurface());
        }
    }

    /** Status pill: label + value + a left-edge bar that goes neon green when good. */
    private void drawPill(GuiGraphics g, int x, int y, int w, String label, String value, boolean good) {
        int h = 24;
        g.fill(x, y, x + w, y + h, Theme.C_INSET);
        Theme.roundedFrame(g, x, y, w, h, good ? Theme.C_ACCENT_SOFT : Theme.C_DIVIDER);
        // Left-edge state bar: neon when good, faint otherwise.
        g.fill(x + 1, y + 1, x + 3, y + h - 1, good ? Theme.F_MATCH : Theme.C_FAINT);
        g.drawString(font, Component.literal(label),     x + 6, y + 3,
            Theme.C_MUTED, !Theme.lightSurface());
        g.drawString(font, Component.literal(value),     x + 6, y + 13,
            good ? Theme.C_ACCENT_BRIGHT : Theme.C_TEXT, !Theme.lightSurface());
    }

    /** Big live audio meter — dim background, neon fill that grows with the smoothed RMS
     *  level. Same algorithm as the config-screen meter, just bigger. */
    private static void drawAudioMeter(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, Theme.C_INSET);
        Theme.roundedFrame(g, x, y, w, h, Theme.C_DIVIDER);
        float level = Math.max(0f, Math.min(1f, VoiceController.audioLevel()));
        int fillW = (int) (level * (w - 4));
        if (fillW > 0) {
            g.fill(x + 2, y + 2, x + 2 + fillW, y + h - 2, Theme.C_ACCENT);
            g.fill(x + 2 + fillW - 1, y + 2, x + 2 + fillW, y + h - 2, Theme.C_ACCENT_BRIGHT);
        }
    }

    private String fitFromRight(String s, int maxW) {
        if (s == null) return "";
        if (font.width(s) <= maxW) return s;
        String trimmed = s;
        while (!trimmed.isEmpty() && font.width("…" + trimmed) > maxW) {
            trimmed = trimmed.substring(1);
        }
        return "…" + trimmed;
    }

    private static String shortId(String id) {
        if (id == null) return "";
        // Strip trailing " (queued)", "(deduped)", etc., and the namespace prefix.
        String s = id;
        int paren = s.indexOf(' ');
        if (paren > 0) s = s.substring(0, paren);
        int colon = s.indexOf(':');
        return colon >= 0 ? s.substring(colon + 1) : s;
    }
}
