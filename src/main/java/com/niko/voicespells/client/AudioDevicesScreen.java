package com.niko.voicespells.client;

import com.niko.voicespells.VoiceSpellsConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pick the microphone and the sound output device from inside the game.
 *
 * <p><b>Why this screen exists.</b> Until now the capture device was a bare string in the toml and
 * the only way to see the valid values was a chat command. That was survivable right up until the
 * common case turned out to be broken: on Windows the default recording device is very often a
 * <i>virtual</i> one — iVCam, VB-Cable, NVIDIA Broadcast, OBS, a webcam that is plugged in but not
 * listening — and blank {@code captureDevice} means "system default". Those devices enumerate like
 * real hardware, open without error, report samples available, and then return frames of value 0
 * forever. Nothing logs, nothing warns, the mic status says "capturing", and voice casting simply
 * never fires. It is the single most likely reason a working install appears dead.
 *
 * <p>So this screen does the one thing a device list cannot: it <b>measures</b>. "Test all"
 * opens each microphone in turn, listens for about a second, and labels it by what actually
 * arrived — {@code signal}, {@code quiet}, {@code silent} or {@code unavailable}. A player with
 * six enumerated inputs can see at a glance which two of them are real.
 *
 * <p>The live meter along the bottom does the same job continuously for the selected device, so
 * picking a mic and confirming it works is one screen and no restart. The microphone is held open
 * for the screen's lifetime (that is what {@link VoiceController#setDiagnosticCapture(String,
 * boolean)} is for) and released in both {@code onClose} and {@code removed}, because a screen can
 * leave by either path.
 *
 * <p><b>Output devices</b> are Minecraft's, not the mod's — the mod plays no sound of its own. The
 * list and the switch both go through vanilla's own {@code soundDevice} option, which exists with
 * the same signature on 1.20.1 and 1.21.1, so no Stonecutter split is needed here. Offering it is
 * worth the few lines: a player who has just learned their default input was a virtual cable has
 * every reason to suspect their output too, and vanilla buries this setting three menus deep.
 */
public final class AudioDevicesScreen extends Screen {

    /** Wider than the other panels on purpose: device names run to 60+ characters. */
    private static final int PANEL_W_PREF = 420;
    private static final int PANEL_H_PREF = 300;

    /** Peak above which a device is certainly live. A real mic's noise floor clears this easily. */
    private static final int SIGNAL_PEAK = 60;
    /** How long each device is listened to during a scan. */
    private static final long PROBE_MS = 900L;

    /** Row pitch for the device list. Deliberately tighter than Theme.ROW_H: that pitch is for
     *  option rows with a control on the right, whereas these rows are one line of text, and a
     *  machine with a webcam, a headset, an interface and a virtual cable can list seven of them.
     *  At 24px only four fit the clamped panel, so the active device was usually off-screen. */
    private static final int ROW = 18;

    /** Identifies this screen's claim on the microphone. */
    private static final String MIC_HOLD_OWNER = "device-picker";

    private final Screen parent;

    /** Which list is showing. Two short lists beat one long mixed one. */
    private boolean showingOutputs = false;

    private List<String> inputs = List.of();
    private List<String> outputs = List.of();
    private int scroll = 0;

    /** Scan results by raw device name: peak sample, or -1 for "would not open". */
    private final Map<String, Integer> peaks = new ConcurrentHashMap<>();
    private volatile boolean scanning = false;
    /** Set the moment the screen leaves, so a scan finishing afterwards cannot re-take the mic. */
    private volatile boolean closed = false;
    /** The device currently being probed, for the progress line. */
    private volatile String scanningNow = "";

    private NeonButton micTab, outTab, scanBtn;
    private int px, py, panelW, panelH;
    private int listX, listY, listW, listH, rowsVisible;

    public AudioDevicesScreen(Screen parent) {
        super(Component.literal("Microphone & Sound"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        panelW = Theme.fit(PANEL_W_PREF, width);
        panelH = Theme.fit(PANEL_H_PREF, height);
        px = (width - panelW) / 2;
        py = (height - panelH) / 2;

        inputs = MicCapture.listDevices();
        outputs = availableOutputs();

        // Hold the mic open so the meter is live the moment the screen opens. Idempotent, so
        // rebuildWidgets() re-entering init() cannot stack claims.
        VoiceController.setDiagnosticCapture(MIC_HOLD_OWNER, true);

        StringWidget titleW = new StringWidget(px, py + (Theme.HEADER_H - 9) / 2,
            panelW, 9, title, font);
        titleW.alignCenter();
        titleW.setColor(Theme.C_TEXT);
        addRenderableWidget(titleW);

        // --- Back first, so it wins hit-testing if a clamped panel makes rows overlap it.
        addRenderableWidget(NeonButton.of(px + panelW - Theme.PAD - 70, py + panelH - 26, 70, 20,
            CommonComponents.GUI_BACK, b -> onClose()));

        int tabY = py + Theme.HEADER_H + 6;
        int tabW = 96;
        micTab = addRenderableWidget(NeonButton.of(px + Theme.PAD, tabY, tabW, 18,
            Component.literal("Microphone"), b -> { showingOutputs = false; scroll = 0; rebuildWidgets(); }));
        outTab = addRenderableWidget(NeonButton.of(px + Theme.PAD + tabW + 4, tabY, tabW, 18,
            Component.literal("Sound output"), b -> { showingOutputs = true; scroll = 0; rebuildWidgets(); }));
        // An inactive vanilla button renders sunken, which is exactly how vanilla shows a
        // selected tab. Reusing that is cheaper and more consistent than a bespoke tab widget.
        // Switching tabs mid-scan would rebuild the screen under the worker thread.
        micTab.active = showingOutputs && !scanning;
        outTab.active = !showingOutputs && !scanning;

        scanBtn = addRenderableWidget(NeonButton.of(px + Theme.PAD, py + panelH - 26, 110, 20,
            Component.literal("Test all mics"), b -> startScan()));
        scanBtn.active = !showingOutputs && !scanning;

        listX = px + Theme.PAD;
        listW = panelW - Theme.PAD * 2;
        listY = tabY + 18 + 8;
        // Leave room for the meter row and the button row below the list.
        listH = (py + panelH - 26 - 8) - listY - (showingOutputs ? 0 : 22);
        rowsVisible = Math.max(1, listH / ROW);
        // After the geometry, never before: this needs the real row count to know what "in view"
        // means, and listH is still zero further up.
        scrollSelectedIntoView();
    }

    /**
     * Put the active device on screen. Opening on row 0 meant a player whose microphone is the
     * seventh entry saw four rows of devices they had not chosen and no indication of which one
     * was live — the exact question the screen exists to answer.
     */
    private void scrollSelectedIntoView() {
        List<String> list = showingOutputs ? outputs : inputs;
        String current = showingOutputs ? currentOutput() : currentInput();
        if (current.isEmpty()) { scroll = 0; return; }
        int idx = list.indexOf(current);
        if (idx < 0) { scroll = 0; return; }
        int row = idx + 1;                              // row 0 is "System default"
        if (row >= rowsVisible) scroll = row - rowsVisible + 1;
    }

    /** Vanilla's own output-device list, with "" standing for the system default. */
    private static List<String> availableOutputs() {
        try {
            List<String> l = new ArrayList<>(Minecraft.getInstance().getSoundManager()
                .getAvailableSoundDevices());
            l.removeIf(s -> s == null || s.isBlank());
            return List.copyOf(l);
        } catch (Throwable t) {
            return List.of();
        }
    }

    // -------------------------------------------------------------------------------------

    /**
     * Probe every capture device on a worker thread.
     *
     * <p>Off the render thread because a scan is six devices times ~0.9 s — a six-second freeze if
     * it ran inline. The mod's own capture is stopped for the duration so exactly one thread is
     * talking to OpenAL's capture side at a time, then restored: opening a device that another
     * thread already holds is the one thing here that is genuinely driver-dependent, and this
     * screen is precisely for people whose drivers are already misbehaving.
     */
    private void startScan() {
        if (scanning) return;
        scanning = true;
        peaks.clear();
        if (scanBtn != null) scanBtn.active = false;

        List<String> targets = new ArrayList<>(inputs);
        Thread t = new Thread(() -> {
            try {
                // A latch, not just "drop the hold". Releasing the hold left the ordinary rules
                // in charge, and tickCaptureSuspension() runs every client tick — so in a world
                // the mod reopened its own device a tick later and then probed the same device
                // from this thread, which is the one thing here that is genuinely driver
                // dependent. setProbing keeps captureAllowedNow() false for the whole scan.
                VoiceController.setProbing(true);
                VoiceController.setDiagnosticCapture(MIC_HOLD_OWNER, false);
                VoiceController.stopCapture();
                for (String dev : targets) {
                    scanningNow = MicCapture.prettyName(dev);
                    peaks.put(dev, MicCapture.probePeak(dev, PROBE_MS));
                }
            } finally {
                scanningNow = "";
                scanning = false;
                VoiceController.setProbing(false);
                // Back to the render thread to re-arm: setDiagnosticCapture reaches into
                // capture lifecycle and the session, which the client thread owns.
                //
                // The re-arm MUST check the screen is still open. A scan takes six seconds and
                // the player can close the screen at any point in it; onClose released the hold,
                // and then this ran afterwards and took it again — with nothing left to ever
                // release it. That pins diagnosticCaptureOverride on for the rest of the session,
                // and since it short-circuits captureArmed(), push-to-talk, hold-item gating and
                // the listening toggle all stop working: the mic stays live and every phrase is
                // recognised regardless of the player's settings. Closing a settings screen must
                // not silently disable the mod's entire gating model.
                Minecraft mc = Minecraft.getInstance();
                mc.execute(() -> {
                    if (closed || mc.screen != AudioDevicesScreen.this) {
                        VoiceController.setDiagnosticCapture(MIC_HOLD_OWNER, false);
                        VoiceController.syncCapture();
                        return;
                    }
                    VoiceController.setDiagnosticCapture(MIC_HOLD_OWNER, true);
                    if (scanBtn != null) scanBtn.active = !showingOutputs;
                });
            }
        }, "VoiceSpells-DeviceScan");
        t.setDaemon(true);
        t.start();
    }

    /** Persist a capture-device choice and reopen the mic on it immediately. */
    private void chooseInput(String rawName) {
        VoiceSpellsConfig.CLIENT.captureDevice.set(rawName == null ? "" : rawName);
        // set() alone does not reach the disk on NeoForge - see VoiceSpellsConfig.saveToDisk().
        VoiceSpellsConfig.saveToDisk();
        // The reload event is debounced, so refresh the cache by hand: syncCapture() reads
        // cCaptureDevice, and without this it would reopen the device it already had.
        VoiceSpellsConfig.refreshCache();
        VoiceController.stopCapture();
        VoiceController.syncCapture();
    }

    /** Switch Minecraft's output device through its own option, then reload the sound engine. */
    private void chooseOutput(String rawName) {
        try {
            Minecraft mc = Minecraft.getInstance();
            mc.options.soundDevice().set(rawName == null ? "" : rawName);
            mc.options.save();
            mc.getSoundManager().reload();
        } catch (Throwable t) {
            com.niko.voicespells.VoiceSpells.LOGGER.warn(
                "Could not switch sound output device: {}", t.toString());
        }
    }

    private String currentInput() {
        String s = VoiceSpellsConfig.CLIENT.captureDevice.get();
        return s == null ? "" : s;
    }

    private String currentOutput() {
        try {
            String s = Minecraft.getInstance().options.soundDevice().get();
            return s == null ? "" : s;
        } catch (Throwable t) {
            return "";
        }
    }

    // -------------------------------------------------------------------------------------

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (super.mouseClicked(mx, my, button)) return true;
        if (button != 0 || scanning) return false;
        if (mx < listX || mx > listX + listW || my < listY || my > listY + listH) return false;

        // Only rows that were actually DRAWN are clickable. listH is rarely an exact multiple of
        // ROW, so a strip of empty well is left under the last row — and clicking it computed a
        // row index one past the end of the visible page, silently switching the microphone to a
        // device the player could not see. The renderer stops at the same boundary.
        int row = ((int) my - listY) / ROW;
        if (row >= rowsVisible || listY + (row + 1) * ROW > listY + listH) return false;
        int idx = row + scroll;
        List<String> list = showingOutputs ? outputs : inputs;
        // Row 0 is the synthetic "System default" entry; the rest index into the device list.
        if (idx == 0) {
            if (showingOutputs) chooseOutput(""); else chooseInput("");
            playClick();
            return true;
        }
        int deviceIdx = idx - 1;
        if (deviceIdx < 0 || deviceIdx >= list.size()) return false;
        if (showingOutputs) chooseOutput(list.get(deviceIdx)); else chooseInput(list.get(deviceIdx));
        playClick();
        return true;
    }

    private void playClick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getSoundManager() != null) {
            mc.getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance
                .forUI(net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK, 1.0F));
        }
    }

    @Override
//? if forge {
/*    public boolean mouseScrolled(double mx, double my, double sy) {
*///?} else {
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
//?}
        int dir = sy > 0 ? -1 : 1;
        int total = (showingOutputs ? outputs.size() : inputs.size()) + 1;
        int maxScroll = Math.max(0, total - rowsVisible);
        scroll = Math.max(0, Math.min(maxScroll, scroll + dir));
        return true;
    }

    @Override
    public void onClose() {
        closed = true;
        VoiceController.setDiagnosticCapture(MIC_HOLD_OWNER, false);
        if (minecraft != null) minecraft.setScreen(parent);
    }

    @Override
    public void removed() {
        // Both paths release: onClose covers Esc and the Back button, removed() covers every
        // other way a screen can be swapped out from under us.
        closed = true;
        VoiceController.setDiagnosticCapture(MIC_HOLD_OWNER, false);
        super.removed();
    }

    // -------------------------------------------------------------------------------------

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        Theme.background(this, g, mouseX, mouseY, partial);
        g.fill(0, 0, this.width, this.height, Theme.C_SCRIM);
        Theme.panel(g, px, py, panelW, panelH);
        Theme.headerBand(g, px, py, panelW, Theme.HEADER_H);

        super.render(g, mouseX, mouseY, partial);


        // Recessed list well, the way a vanilla container inventory is recessed.
        g.fill(listX, listY, listX + listW, listY + listH, Theme.C_INSET);
        Theme.bevel(g, listX, listY, listW, listH, true);

        List<String> list = showingOutputs ? outputs : inputs;
        String current = showingOutputs ? currentOutput() : currentInput();
        int total = list.size() + 1;
        int maxScroll = Math.max(0, total - rowsVisible);
        if (scroll > maxScroll) scroll = maxScroll;

        boolean light = Theme.lightSurface();
        for (int row = 0; row < rowsVisible; row++) {
            int idx = row + scroll;
            if (idx >= total) break;
            int ry = listY + row * ROW;
            if (ry + ROW > listY + listH) break;

            boolean isDefaultRow = idx == 0;
            String raw = isDefaultRow ? "" : list.get(idx - 1);
            boolean selected = current.equals(raw);
            boolean hovered = mouseX >= listX && mouseX <= listX + listW
                && mouseY >= ry && mouseY < ry + ROW;

            // Selection needs to survive on a light panel, where an 0x11-alpha accent ghost is
            // invisible. A solid accent-soft band plus the marker below is unambiguous.
            if (selected) g.fill(listX + 1, ry, listX + listW - 1, ry + ROW, Theme.C_ACCENT_SOFT);
            else if (hovered) g.fill(listX + 1, ry, listX + listW - 1, ry + ROW, Theme.C_INSET_2);

            // Verdict chip on the right, so the name can use the full remaining width.
            String verdict = isDefaultRow ? verdictFor(defaultRaw()) : verdictFor(raw);
            int verdictW = verdict.isEmpty() ? 0 : font.width(verdict) + 8;
            if (!verdict.isEmpty()) {
                Theme.text(g, font, verdict, listX + listW - verdictW, ry + (ROW - 8) / 2,
                    verdictColor(isDefaultRow ? defaultRaw() : raw));
            }

            String label = isDefaultRow
                ? "System default" + defaultSuffix()
                : MicCapture.prettyName(raw);
            String mark = selected ? "▸ " : "  ";
            int nameMax = listW - 8 - verdictW - font.width(mark);
            Theme.text(g, font, mark + trim(label, nameMax),
                listX + 4, ry + (ROW - 8) / 2,
                selected ? Theme.C_HEADING : Theme.C_TEXT);

            if (row > 0) Theme.divider(g, listX + 2, ry, listW - 4);
        }

        Theme.scrollbar(g, listX + listW - 4, listY + 1, 3, listH - 2, total, rowsVisible, scroll);

        // Live meter for the mic tab only — there is nothing to measure on the output side.
        if (!showingOutputs) renderMeter(g, listY + listH + 5, light);
    }

    /** The bottom row: either the scan progress, the silence warning, or a live level bar. */
    private void renderMeter(GuiGraphics g, int y, boolean light) {
        int barX = listX;
        int barW = listW - 116;
        int barH = 8;

        if (scanning) {
            String now = scanningNow;
            Theme.text(g, font, "Testing " + trim(now.isEmpty() ? "devices" : now, barW + 100) + "...",
                barX, y, Theme.C_WARN);
            return;
        }

        // Track, then fill. Same construction as the HUD meter.
        g.fill(barX, y, barX + barW, y + barH, light ? 0xFF8B8B8B : 0xFF101010);
        Theme.bevel(g, barX, y, barW, barH, true);
        float level = Math.max(0f, Math.min(1f, VoiceController.audioLevel()));
        int fillW = (int) ((barW - 2) * level);
        if (fillW > 0) {
            g.fill(barX + 1, y + 1, barX + 1 + fillW, y + barH - 1,
                level > 0.75f ? Theme.C_WARN : Theme.C_SUCCESS);
        }

        String note;
        int noteColor;
        if (VoiceController.deviceSilent()) {
            note = "no signal - pick another";
            noteColor = Theme.C_DANGER;
        } else if (level > 0.02f) {
            note = String.format(Locale.ROOT, "hearing you (%.0f%%)", level * 100f);
            noteColor = Theme.C_SUCCESS;
        } else {
            note = "say something";
            noteColor = Theme.C_MUTED;
        }
        Theme.text(g, font, note, barX + barW + 6, y, noteColor);
    }

    /** Raw name of the system default capture device, for judging the "System default" row. */
    private String defaultRaw() {
        return showingOutputs ? "" : MicCapture.defaultDevice();
    }

    private String defaultSuffix() {
        String d = showingOutputs ? "" : MicCapture.prettyName(MicCapture.defaultDevice());
        return d.isEmpty() ? "" : " (" + d + ")";
    }

    /** Word for what the scan heard. Empty when this device has not been probed. */
    private String verdictFor(String raw) {
        if (showingOutputs || raw == null || raw.isEmpty()) return "";
        Integer p = peaks.get(raw);
        if (p == null) return "";
        if (p < 0) return "unavailable";
        if (p == 0) return "silent";
        return p >= SIGNAL_PEAK ? "signal" : "quiet";
    }

    private int verdictColor(String raw) {
        Integer p = peaks.get(raw);
        if (p == null) return Theme.C_MUTED;
        if (p < 0) return Theme.C_DANGER;
        if (p == 0) return Theme.C_DANGER;
        return p >= SIGNAL_PEAK ? Theme.C_SUCCESS : Theme.C_WARN;
    }

    /** Middle-free truncation: device names differ at the END ("Microphone (X)"), so cutting the
     *  tail off would make three of them identical. Keep the tail, drop from the front. */
    private String trim(String s, int maxWidth) {
        if (maxWidth <= 0) return "";
        if (font.width(s) <= maxWidth) return s;
        String ell = "...";
        int budget = maxWidth - font.width(ell);
        if (budget <= 0) return "";
        int lo = 0;
        while (lo < s.length() && font.width(s.substring(lo)) > budget) lo++;
        return ell + s.substring(Math.min(lo, s.length()));
    }
}
