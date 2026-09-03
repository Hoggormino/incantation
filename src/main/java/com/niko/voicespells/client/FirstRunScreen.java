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
        super(Component.translatable("voicespells.wizard.title"));
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
        // Width from the row, not a fixed 96.
        //
        // Three 96px buttons plus padding need 336 logical pixels; Theme.fit clamps the panel to
        // the window, so below that the fixed-width Back / Skip / Next OVERLAPPED — and because
        // widgets are hit-tested in insertion order, Skip was registered before Next and
        // swallowed the click. Pressing "Next" on a small window silently finished the wizard
        // and marked first-run complete. Dividing the row that actually exists between three
        // buttons means they shrink instead of colliding.
        final int BTN_GAP = 6;
        int btnW = Math.min(96, (panelW - Theme.PAD * 2 - BTN_GAP * 2) / 3);
        int backX = px + Theme.PAD;
        int midX  = px + Theme.PAD + btnW + BTN_GAP;
        int nextX = px + Theme.PAD + (btnW + BTN_GAP) * 2;

        if (step > 0) {
            addRenderableWidget(NeonButton.of(backX, btnY, btnW, 20,
                Component.translatable("voicespells.wizard.back"),
                b -> { step--; rebuildWidgets(); }));
        }
        if (step < STEPS - 1) {
            addRenderableWidget(NeonButton.of(midX, btnY, btnW, 20,
                Component.translatable("voicespells.wizard.skip"), b -> finish()));
        }
        // On step 2 (Try a spell), offer a direct shortcut into the spell list since it's
        // the natural next destination.
        if (step == 2) {
            addRenderableWidget(NeonButton.of(midX, btnY, btnW, 20,
                Component.translatable("voicespells.config.spelllist"),
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
            // Width from the text, not a guess: "Meter dead? Choose microphone..." measured 6px
            // wider than the 160 it was given, so vanilla drew the label straight through the
            // button's right edge.
            Component micLabel = Component.translatable("voicespells.wizard.choose_microphone");
            int micBtnW = Math.min(panelW - Theme.PAD * 2,
                font.width(micLabel.getString()) + 24);
            addRenderableWidget(NeonButton.of(px + (panelW - micBtnW) / 2, btnY - 24, micBtnW, 20,
                micLabel,
                b -> { if (minecraft != null) minecraft.setScreen(new AudioDevicesScreen(this)); }));
        }
        Component nextLabel = Component.translatable(
            (step == STEPS - 1) ? "voicespells.wizard.done" : "voicespells.wizard.next");
        nextBtn = NeonButton.of(nextX, btnY, btnW, 20,
            nextLabel, b -> {
                if (step == STEPS - 1) finish();
                else { step++; rebuildWidgets(); }
            });
        // On the mic-check step, gate Next behind actually seeing audio - but not forever.
        //
        // The gate exists so nobody sleepwalks past a broken microphone. Left absolute it becomes
        // a wall: a player whose mic genuinely does not work cannot reach step 3 at all, and the
        // only way on is Skip, which ends the wizard rather than continuing it. After a dozen
        // seconds the point has been made and the button unlocks.
        if (step == 1 && !micDetectedThisVisit
                && System.nanoTime() - openedAtNanos < 12_000_000_000L) {
            nextBtn.active = false;
        }
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
        // ...and unlock it on the timeout too, from here rather than from init(): init() only
        // runs on a rebuild, so a player sitting on the step waiting would have waited forever.
        if (step == 1 && nextBtn != null && !nextBtn.active
                && System.nanoTime() - openedAtNanos >= 12_000_000_000L) {
            nextBtn.active = true;
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
        Theme.ground(this, g, mouseX, mouseY, partial);

        super.render(g, mouseX, mouseY, partial);


        // "Step 1 of 3" rather than indicator dots — see HelpScreen for why: the game paginates
        // with a written count, not with dots, and a count is legible on every surface.
        g.drawCenteredString(font, "Step " + (step + 1) + " of " + STEPS,
            px + panelW / 2, py + Theme.HEADER_H + 8, Theme.C_MUTED);

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
        drawHeading(g, x, y, Component.translatable("voicespells.wizard.p1.heading"));
        drawLines(g, x, y + 14, new Component[] {
            Component.translatable("voicespells.wizard.p1.1"),
            Component.translatable("voicespells.wizard.p1.2"),
            Component.empty(),
            Component.translatable("voicespells.wizard.p1.4"),
            Component.translatable("voicespells.wizard.p1.5"),
            Component.translatable("voicespells.wizard.p1.6"),
            Component.empty(),
            Component.translatable("voicespells.wizard.p1.8"),
            Component.translatable("voicespells.wizard.p1.9"),
        });
    }

    // ------------------------------------------------------------------------ Step 1
    private void renderMicCheck(GuiGraphics g, int x, int y) {
        drawHeading(g, x, y, Component.translatable("voicespells.wizard.p2.heading"));
        y += 14;

        // Three live status pills: Model, microphone, Audio detected.
        String modelStatus = VoiceController.statusLine();
        if (modelStatus == null || modelStatus.isEmpty()) {
            modelStatus = Component.translatable("voicespells.wizard.status_warming_up").getString();
        }
        boolean modelReady = "READY".equals(modelStatus);
        boolean micLive    = VoiceController.isHearingNow();

        int pillW = (panelW - Theme.PAD * 2 - 8 * 2) / 3;
        int pillY = y;
        drawPill(g, x,                       pillY, pillW,
            Component.translatable("voicespells.wizard.pill_model"),
            Component.literal(modelReady ? modelStatus : modelStatus + "…"), modelReady);
        drawPill(g, x + (pillW + 8),         pillY, pillW,
            Component.translatable("voicespells.wizard.pill_microphone"),
            Component.translatable(micLive
                ? "voicespells.wizard.mic_transmitting"
                : "voicespells.wizard.mic_idle"), micLive);
        drawPill(g, x + (pillW + 8) * 2,     pillY, pillW,
            Component.translatable("voicespells.wizard.pill_audio"),
            Component.translatable(micDetectedThisVisit
                ? "voicespells.wizard.audio_detected"
                : "voicespells.wizard.audio_waiting"), micDetectedThisVisit);
        y += 28;

        drawLines(g, x, y, new Component[] {
            Component.translatable("voicespells.wizard.p2.1"),
            Component.translatable("voicespells.wizard.p2.2"),
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
        String prefix = Component.translatable("voicespells.wizard.last_heard").getString();
        int availW = panelW - Theme.PAD * 2 - font.width(prefix) - font.width("\"\"");
        String shown = empty
            ? Component.translatable("voicespells.wizard.nothing_heard").getString()
            : "\"" + Theme.fitFromRight(font, last, availW) + "\"";
        g.drawString(font, Component.literal(prefix + shown), x, y,
            empty ? Theme.C_FAINT : Theme.C_TEXT, !Theme.lightSurface());
    }

    // ------------------------------------------------------------------------ Step 2
    private void renderTrySpell(GuiGraphics g, int x, int y) {
        drawHeading(g, x, y, Component.translatable("voicespells.wizard.p3.heading"));
        // Tightened from 7 lines to 4 — the original arrangement left a huge empty stretch
        // between the body and the feed box, which read as "broken layout" rather than
        // breathing room. The remaining hint is the actually-actionable part.
        Component[] body = {
            Component.translatable("voicespells.wizard.p3.1"),
            Component.translatable("voicespells.wizard.p3.2"),
            Component.translatable("voicespells.wizard.p3.3"),
            Component.translatable("voicespells.wizard.p3.4"),
        };
        drawLines(g, x, y + 14, body);

        // Live recent-recognition feed. Sized to fill the rest of the panel so there's no
        // dead space between the body text and the buttons.
        int feedX = x;
        int bodyBottom = y + 14 + linesHeight(y + 14, body.length);
        int feedY = bodyBottom + 10;
        int feedW = panelW - Theme.PAD * 2;
        int feedH = panelH - (feedY - py) - 40; // leave room for buttons + a real gap
        if (feedH > 30) {
            g.drawString(font, Component.translatable("voicespells.wizard.recent_heading"), feedX, feedY,
                Theme.C_MUTED, !Theme.lightSurface());
            feedY += 13;                          // label text + ~4px gap before the box
            feedH -= 13;
            Theme.well(g, feedX, feedY, feedW, feedH);

            List<VoiceController.RecognitionEvent> events = VoiceController.recentEvents();
            if (events.isEmpty()) {
                g.drawString(font, Component.translatable("voicespells.wizard.empty_feed"),
                    feedX + 4, feedY + 4, Theme.C_FAINT, !Theme.lightSurface());
            } else {
                long now = System.nanoTime();
                int rowY = feedY + 4;
                int lineH = 11;
                int maxRows = (feedH - 8) / lineH;
                for (int i = 0; i < events.size() && i < maxRows; i++) {
                    VoiceController.RecognitionEvent e = events.get(i);
                    long ageSec = TimeUnit.NANOSECONDS.toSeconds(now - e.nanoTime());
                    // matched() is null (no match), a bare spell id (a real cast), or an id with a
                    // trailing reason (suppressed: queued, not equipped, low confidence...). This
                    // used to colour every non-null value as a cast, so on the one screen whose
                    // Next button waits for the player to see casting work, a rejection drew
                    // green with an arrow - the wrong lesson at exactly the wrong moment. Same
                    // split as the Live Monitor and the Test Arena.
                    String raw = e.matched();
                    int sp = raw == null ? -1 : raw.indexOf(' ');
                    int color = raw == null ? Theme.F_NOMATCH : sp > 0 ? Theme.F_DEDUP : Theme.F_MATCH;
                    String outcome = raw == null
                        ? "— " + Component.translatable("voicespells.wizard.no_match").getString()
                        : sp > 0
                            ? "· " + shortId(raw.substring(0, sp)) + " " + raw.substring(sp + 1)
                            : "→ " + shortId(raw);
                    String text = String.format(java.util.Locale.ROOT, "%2ds  \"%s\"  %s",
                        ageSec, e.heard(), outcome);
                    // The heard string is whatever the recogniser produced and was drawn entirely
                    // unclipped - no truncation of any kind - so a long utterance ran straight out
                    // of the feed and across the wizard. This is the step whose Next button is
                    // gated on the player seeing this feed work, so it is a bad place to overflow.
                    text = fit(text, feedW - 8);
                    g.drawString(font, Component.literal(text), feedX + 4, rowY, color, !Theme.lightSurface());
                    rowY += lineH;
                }
            }
        }
    }

    // ------------------------------------------------------------------------ Shared
    private void drawHeading(GuiGraphics g, int x, int y, Component text) {
        g.drawString(font, text, x, y, Theme.C_HEADING, !Theme.lightSurface());
    }

    /**
     * Draw a block of lines, compressing and clipping so it can never reach the button row.
     *
     * <p>The 11px step was unconditional, and Theme.fit clamps the panel to the window, so on a
     * short window the tail of a wizard step was drawn over Back / Skip / Next — the controls the
     * player needs to get out. Deriving the step from the space that is actually left, and
     * refusing to draw a line that would still land on the buttons, keeps the navigation legible
     * on every window the panel fits in at all.
     */
    private void drawLines(GuiGraphics g, int x, int y, Component[] lines) {
        // On the mic-check step there is an extra button row above the nav row, so the text has
        // to stop higher or it draws into it.
        int limit = py + panelH - 28 - 4 - (step == 1 ? 24 : 0);
        int lineH = Math.max(8, Math.min(11, (limit - y) / Math.max(1, lines.length)));
        for (int i = 0; i < lines.length; i++) {
            int ly = y + i * lineH;
            if (ly + 8 > limit) break;
            int color = lines[i].getString().isEmpty() ? Theme.C_FAINT : Theme.C_TEXT;
            g.drawString(font, lines[i], x, ly, color, !Theme.lightSurface());
        }
    }

    /** The step-body line height {@link #drawLines} will use for {@code n} lines starting at
     *  {@code y} — so callers that lay out content BELOW a block agree with what was drawn. */
    private int linesHeight(int y, int n) {
        int limit = py + panelH - 28 - 4 - (step == 1 ? 24 : 0);
        return Math.max(8, Math.min(11, (limit - y) / Math.max(1, n))) * n;
    }

    /** Status pill: label above, value below, on a vanilla button face. */
    private void drawPill(GuiGraphics g, int x, int y, int w, Component label, Component value, boolean good) {
        int h = 24;
        Theme.rowFace(g, x, y, w, h);
        // Both strings are fitted to the pill. The value used to be drawn unclipped, and
        // VoiceController.statusLine() can return "ERROR no Vosk model - see chat", which is
        // roughly twice a pill wide at any window size - so it painted straight across the
        // Microphone pill beside it. That happens in exactly the state this screen exists to
        // diagnose: the player whose model failed cannot read whether their mic is working,
        // and Next is gated on the meter moving.
        g.drawString(font, Component.literal(fit(label.getString(), w - 12)), x + 6, y + 3,
            Theme.C_MUTED, !Theme.lightSurface());
        g.drawString(font, Component.literal(fit(value.getString(), w - 12)), x + 6, y + 13,
            good ? Theme.C_SUCCESS : Theme.C_TEXT, !Theme.lightSurface());
    }

    /** Truncate to {@code room} pixels with an ellipsis. Now just this screen's font bound to
     *  {@link Theme#fit(net.minecraft.client.gui.Font, String, int)}, which every screen shares. */
    private String fit(String s, int room) {
        return Theme.fit(font, s, room);
    }

    /** Big live audio meter — dim background, neon fill that grows with the smoothed RMS
     *  level. Same algorithm as the config-screen meter, just bigger. */
    private static void drawAudioMeter(GuiGraphics g, int x, int y, int w, int h) {
        Theme.well(g, x, y, w, h);
        float level = Math.max(0f, Math.min(1f, VoiceController.audioLevel()));
        int fillW = (int) (level * (w - 4));
        if (fillW > 0) {
            // Green, the way a level or a loading bar reads in vanilla: it means "this is
            // working", which is exactly what a live mic meter is telling you.
            g.fill(x + 2, y + 2, x + 2 + fillW, y + h - 2, Theme.C_SUCCESS);
        }
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
