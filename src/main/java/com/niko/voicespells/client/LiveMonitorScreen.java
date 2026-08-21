package com.niko.voicespells.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * The Live Monitor, on a screen of its own.
 *
 * <p>It used to be a block wedged into the config screen underneath the options, and that
 * placement caused four separate layout bugs in a row: it drew over the buttons, then reserving
 * room for it pushed the buttons off the window, then the gate that decided whether it fitted
 * could never pass, then the well overshot its own limit by the height of its header. Every one
 * of those came from the same thing — a variable-height diagnostic panel competing for space with
 * a fixed set of controls, on a screen that also has to work at 320x240.
 *
 * <p>On its own screen it simply fills what is there. It is also the loudest thing in the mod:
 * scrolling green recognition text belongs on a page a player opens deliberately, not underneath
 * the settings they are trying to read.
 *
 * <p>Holds the microphone open for its lifetime — that is the whole point of watching it — and
 * releases on both exit paths.
 */
public final class LiveMonitorScreen extends Screen {

    private static final String MIC_HOLD = "livemonitor-screen";
    private static final int LINE_H = 11;

    private final Screen parent;
    private int listX, listY, listW, listH;

    public LiveMonitorScreen(Screen parent) {
        super(Component.literal("Live Monitor"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        VoiceController.setDiagnosticCapture(MIC_HOLD, true);

        StringWidget titleW = new StringWidget(0, 14, width, 9, title, font);
        titleW.alignCenter();
        titleW.setColor(Theme.C_TEXT);
        addRenderableWidget(titleW);

        // Fills the window, with vanilla's footer button parked at the bottom.
        listX = Math.max(20, width / 2 - 220);
        // 48, not 46. The header rule at y=32 is three pixels tall (32-34) and the heap/
        // waveform row hangs 12px above the list, so at 46 that row started at 34 and painted
        // over the rule it is supposed to sit under - the separator simply vanished behind
        // the meter whenever anyone spoke.
        listY = 48;
        listW = Math.min(440, width - listX * 2);
        listH = Math.max(40, height - listY - 56);

        addRenderableWidget(NeonButton.of(width / 2 - 75, height - 28, 150, 20,
            CommonComponents.GUI_BACK, b -> onClose()));
    }

    @Override
    public void onClose() {
        VoiceController.setDiagnosticCapture(MIC_HOLD, false);
        if (minecraft != null) minecraft.setScreen(parent);
    }

    @Override
    public void removed() {
        VoiceController.setDiagnosticCapture(MIC_HOLD, false);
        super.removed();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        Theme.ground(this, g, mouseX, mouseY, partial);

        super.render(g, mouseX, mouseY, partial);

        // Heap + live waveform along the top of the well.
        Runtime rt = Runtime.getRuntime();
        long usedMb  = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
        long totalMb = rt.totalMemory() / (1024 * 1024);
        String heap = "heap " + usedMb + "/" + totalMb + "MB";
        Theme.text(g, font, heap, listX, listY - 11, Theme.C_MUTED);
        int meterX = listX + font.width(heap) + 10;
        drawWaveform(g, meterX, listY - 12, listX + listW - meterX, 9);

        Theme.well(g, listX, listY, listW, listH);

        List<VoiceController.RecognitionEvent> events = VoiceController.recentEvents();
        if (events.isEmpty()) {
            Theme.text(g, font, "Say a spell name — what the recogniser hears appears here.",
                listX + 6, listY + 6, Theme.C_FAINT);
            Theme.text(g, font, "E exact · F fuzzy · S substring · P phonetic",
                listX + 6, listY + 6 + LINE_H, Theme.C_FAINT);
            return;
        }
        long now = System.nanoTime();
        int rowY = listY + 5;
        int maxRows = (listH - 8) / LINE_H;
        for (int i = 0; i < events.size() && i < maxRows; i++) {
            VoiceController.RecognitionEvent e = events.get(i);
            long ageSec = TimeUnit.NANOSECONDS.toSeconds(now - e.nanoTime());
            int color;
            String outcome;
            if (e.matched() == null) {
                color = Theme.F_NOMATCH;
                outcome = "— no match";
            } else if (e.matched().endsWith("(deduped)")) {
                color = Theme.F_DEDUP;
                outcome = "↻ " + shortId(e.matched().replace(" (deduped)", "")) + " (dup)";
            } else {
                // Same distinction the Test Arena makes: a bare id is a cast, an id with a
                // trailing reason was suppressed. Drawing both with the arrow made one utterance
                // look like two casts.
                String raw = e.matched();
                int sp = raw.indexOf(' ');
                if (sp > 0) {
                    color = Theme.F_DEDUP;
                    outcome = "· " + shortId(raw.substring(0, sp)) + " " + raw.substring(sp + 1);
                } else {
                    color = Theme.F_MATCH;
                    outcome = "→ " + shortId(raw) + " CAST";
                }
            }
            String line = String.format(Locale.ROOT, "%2ds  c%.2f  [%s]  \"%s\"  %s",
                ageSec, e.confidence(), e.tier() == ' ' ? " " : String.valueOf(e.tier()),
                truncate(e.heard(), 24), outcome);
            // Fitted to the well by WIDTH, not by character count. truncate() above bounds only
            // the heard phrase at 24 chars; the outcome appended after it carries a spell's
            // display name, which is now localised and can be far longer than the English one -
            // so the assembled row ran off the well and off the screen with nothing clipping it.
            int room = listW - 12;
            if (font.width(line) > room) {
                line = font.plainSubstrByWidth(line, room - font.width("...")) + "...";
            }
            g.drawString(font, Component.literal(line), listX + 6, rowY, color, !Theme.lightSurface());
            rowY += LINE_H;
        }
    }

    /** Rolling waveform of recent audio levels — alive when you speak, flat when quiet. */
    private static void drawWaveform(GuiGraphics g, int x, int y, int w, int h) {
        if (w < 12) return;
        Theme.well(g, x, y, w, h);   // the same recessed surface every list sits on
        float[] data = VoiceController.waveformSnapshot();
        int bars = data.length;
        if (bars == 0) return;
        float barW = (float) (w - 4) / bars;
        for (int i = 0; i < bars; i++) {
            int barX = x + 2 + (int) (i * barW);
            int barNextX = x + 2 + (int) ((i + 1) * barW);
            int barH = Math.max(1, (int) (data[i] * (h - 4)));
            int barY = y + h - 2 - barH;
            g.fill(barX, barY, Math.max(barX + 1, barNextX - 1), y + h - 2,
                i > bars * 3 / 4 ? 0xFFFFFFFF : 0xFFA0A0A0);
        }
    }

    private static String shortId(String id) {
        int colon = id.indexOf(':');
        return colon >= 0 ? id.substring(colon + 1) : id;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
