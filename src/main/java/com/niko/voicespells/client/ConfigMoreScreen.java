package com.niko.voicespells.client;

import com.niko.voicespells.VoiceSpellsConfig;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * "More..." submenu for the config screen. Houses actions that don't fit the bottom button bar
 * — currently: open the welcome wizard, export the active settings as a clipboard string, and
 * import a previously-exported profile.
 *
 * Profile format is intentionally simple plain-text key=value with a single header line so
 * users can paste it into chat / Discord / docs and back without escaping ceremony.
 */
public final class ConfigMoreScreen extends Screen {

    // Preferred dimensions — clamped at init() time so the panel stays inside the screen
    // at every Minecraft GUI Scale. Layout math uses the runtime {@code panelW} / {@code panelH}
    // fields, not these constants.
    private static final int PANEL_W_PREF = 340;
    private static final int PANEL_H_PREF = 340;
    private static final String PROFILE_HEADER = "voicespells-profile:1";

    private final Screen parent;
    private StringWidget statusLabel;
    private NeonButton calibBtn;
    private long statusFlashUntil = 0L;
    /** Top-left of the runtime panel + clamped dimensions; recomputed every {@link #init()}. */
    private int px, py, panelW, panelH;

    public ConfigMoreScreen(Screen parent) {
        super(Component.literal("More"));
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

        int y = py + Theme.HEADER_H + Theme.GAP_MD;
        int btnW = panelW - Theme.PAD * 2;

        addRenderableWidget(NeonButton.of(px + Theme.PAD, y, btnW, 20,
            Component.literal("Open Welcome Wizard"),
            b -> { if (minecraft != null) minecraft.setScreen(new FirstRunScreen(this)); }));
        y += 24;

        addRenderableWidget(NeonButton.of(px + Theme.PAD, y, btnW, 20,
            Component.literal("Voice Codex (stats + rank)"),
            b -> { if (minecraft != null) minecraft.setScreen(new VoiceCodexScreen(this)); }));
        y += 24;

        addRenderableWidget(NeonButton.of(px + Theme.PAD, y, btnW, 20,
            Component.literal("Diagnostics (check everything)"),
            b -> { if (minecraft != null) minecraft.setScreen(new DiagnosticsScreen(this)); }));
        y += 24;

        addRenderableWidget(NeonButton.of(px + Theme.PAD, y, btnW, 20,
            Component.literal("Help / Guide"),
            b -> { if (minecraft != null) minecraft.setScreen(new HelpScreen(this)); }));
        y += 24;

        // Test Arena requires SVC mic frames, which only flow when in a world. Disable the
        // button from the main menu so users don't open an empty practice screen.
        boolean inWorld = minecraft != null && minecraft.player != null;
        NeonButton arenaBtn = NeonButton.of(px + Theme.PAD, y, btnW, 20,
            Component.literal(inWorld ? "Test Arena (safe practice)"
                                       : "Test Arena (load a world first)"),
            b -> { if (minecraft != null) minecraft.setScreen(new TestArenaScreen(this)); });
        arenaBtn.active = inWorld;
        addRenderableWidget(arenaBtn);
        y += 24;

        addRenderableWidget(NeonButton.of(px + Theme.PAD, y, btnW, 20,
            Component.literal("Reload grammar now"),
            b -> {
                VoiceController.onConfigChanged();
                flashStatus("Grammar reload requested", Theme.F_MATCH);
            }));
        y += 24;

        // Auto-calibrate noise gate — samples 5 seconds of mic input then picks a threshold.
        // Button label flips into a countdown while sampling so the player has visible feedback
        // (label is refreshed each frame in render()).
        calibBtn = NeonButton.of(px + Theme.PAD, y, btnW, 20,
            Component.literal("Auto-calibrate noise gate (talk for 5s)"),
            b -> {
                if (!VoiceController.isCalibrating()) {
                    VoiceController.startNoiseGateCalibration();
                    flashStatus("Calibrating — say a few spell names…", Theme.C_ACCENT_BRIGHT);
                }
            });
        addRenderableWidget(calibBtn);
        y += 24;

        addRenderableWidget(NeonButton.of(px + Theme.PAD, y, btnW, 20,
            Component.literal("Export Profile to Clipboard"),
            b -> exportProfile()));
        y += 24;

        addRenderableWidget(NeonButton.of(px + Theme.PAD, y, btnW, 20,
            Component.literal("Import Profile from Clipboard"),
            b -> importProfile()));
        y += 28;

        statusLabel = new StringWidget(px + Theme.PAD, y, btnW, 9, Component.empty(), font);
        statusLabel.alignLeft();
        statusLabel.setColor(Theme.C_MUTED);
        addRenderableWidget(statusLabel);

        // Spell-of-the-day: deterministic-per-date suggestion to nudge players to try
        // unfamiliar spells. Sits at the bottom of the panel as a quiet hint.
        String suggestion = spellOfTheDay();
        if (!suggestion.isEmpty()) {
            StringWidget sotd = new StringWidget(px + Theme.PAD, py + panelH - 50,
                btnW, 9, Component.literal("Today's spell: " + suggestion), font);
            sotd.alignLeft();
            sotd.setColor(Theme.C_FAINT);
            addRenderableWidget(sotd);
        }

        addRenderableWidget(NeonButton.of(px + panelW - Theme.PAD - 80, py + panelH - 28,
            80, 20, CommonComponents.GUI_BACK, b -> onClose()));
    }

    private void flashStatus(String text, int color) {
        if (statusLabel != null) {
            statusLabel.setMessage(Component.literal(text));
            statusLabel.setColor(color);
        }
        statusFlashUntil = System.currentTimeMillis() + 3500;
    }

    private void exportProfile() {
        VoiceSpellsConfig.Client c = VoiceSpellsConfig.CLIENT;
        StringBuilder sb = new StringBuilder();
        sb.append(PROFILE_HEADER).append('\n');
        sb.append("debugMonitor=").append(c.debugMonitor.get()).append('\n');
        sb.append("fuzzyMaxDistance=").append(c.fuzzyMaxDistance.get()).append('\n');
        sb.append("substringMatch=").append(c.substringMatch.get()).append('\n');
        sb.append("dedupMillis=").append(c.dedupMillis.get()).append('\n');
        sb.append("echoLockoutMillis=").append(c.echoLockoutMillis.get()).append('\n');
        sb.append("minConfidence=").append(c.minConfidence.get()).append('\n');
        sb.append("requireSneak=").append(c.requireSneak.get()).append('\n');
        sb.append("triggerWord=").append(c.triggerWord.get()).append('\n');
        sb.append("showMisses=").append(c.showMisses.get()).append('\n');
        sb.append("enableEchoSfx=").append(c.enableEchoSfx.get()).append('\n');
        sb.append("streamerMode=").append(c.streamerMode.get()).append('\n');
        sb.append("sassMode=").append(c.sassMode.get()).append('\n');
        sb.append("castQueueSize=").append(c.castQueueSize.get()).append('\n');
        sb.append("clientPreflight=").append(c.clientPreflight.get()).append('\n');
        sb.append("hudCorner=").append(c.hudCorner.get().name()).append('\n');
        sb.append("hudOffsetX=").append(c.hudOffsetX.get()).append('\n');
        sb.append("hudOffsetY=").append(c.hudOffsetY.get()).append('\n');
        sb.append("globalOpacity=").append(c.globalOpacity.get()).append('\n');
        // Visual / palette
        sb.append("themePreset=").append(c.themePreset.get().name()).append('\n');
        sb.append("uiPalette=").append(c.uiPalette.get().name()).append('\n');
        // Recognition tuning + modes added in newer builds
        sb.append("noiseGateRms=").append(c.noiseGateRms.get()).append('\n');
        sb.append("handsFreeConfirm=").append(c.handsFreeConfirm.get()).append('\n');
        sb.append("alwaysShowHeard=").append(c.alwaysShowHeard.get()).append('\n');
        sb.append("combatOnly=").append(c.combatOnly.get()).append('\n');
        sb.append("pauseWhenAfk=").append(c.pauseWhenAfk.get()).append('\n');
        sb.append("afkSeconds=").append(c.afkSeconds.get()).append('\n');
        sb.append("chatRankTag=").append(c.chatRankTag.get()).append('\n');
        sb.append("voiceHotbarSelect=").append(c.voiceHotbarSelect.get()).append('\n');
        // Lists exported with one entry per pipe-separated token so it survives a single line
        // paste cycle. Less elegant than JSON but matches the spirit of the toml.
        sb.append("customPhrases=").append(String.join("|", c.customPhrases.get())).append('\n');
        sb.append("incantations=").append(String.join("|", c.incantations.get())).append('\n');
        sb.append("loadouts=").append(String.join("|", c.loadouts.get())).append('\n');
        sb.append("blockedSpells=").append(String.join("|", c.blockedSpells.get())).append('\n');
        sb.append("triggerWords=").append(String.join("|", c.triggerWords.get())).append('\n');
        sb.append("perSpellMinConfidence=").append(String.join("|", c.perSpellMinConfidence.get())).append('\n');

        if (minecraft != null) {
            minecraft.keyboardHandler.setClipboard(sb.toString());
            flashStatus("Profile copied to clipboard", Theme.F_MATCH);
        }
    }

    private void importProfile() {
        if (minecraft == null) return;
        String raw = minecraft.keyboardHandler.getClipboard();
        if (raw == null || raw.isBlank()) {
            flashStatus("Clipboard is empty", Theme.C_DANGER);
            return;
        }
        String[] lines = raw.split("\\r?\\n");
        if (lines.length == 0 || !lines[0].trim().equals(PROFILE_HEADER)) {
            flashStatus("Not a VoiceSpells profile (need '" + PROFILE_HEADER + "' header)", Theme.C_DANGER);
            return;
        }
        VoiceSpellsConfig.Client c = VoiceSpellsConfig.CLIENT;
        int applied = 0;
        for (int i = 1; i < lines.length; i++) {
            String ln = lines[i].trim();
            if (ln.isEmpty()) continue;
            int eq = ln.indexOf('=');
            if (eq <= 0) continue;
            String key = ln.substring(0, eq).trim();
            String val = ln.substring(eq + 1);
            try {
                if (applyKv(c, key, val)) applied++;
            } catch (Throwable ignored) {}
        }
        VoiceSpellsConfig.refreshCache();
        VoiceController.onConfigChanged();
        flashStatus("Applied " + applied + " setting(s)", Theme.F_MATCH);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private boolean applyKv(VoiceSpellsConfig.Client c, String key, String val) {
        switch (key) {
            case "debugMonitor":      c.debugMonitor.set(Boolean.parseBoolean(val)); return true;
            case "fuzzyMaxDistance":  c.fuzzyMaxDistance.set(Integer.parseInt(val.trim())); return true;
            case "substringMatch":    c.substringMatch.set(Boolean.parseBoolean(val)); return true;
            case "dedupMillis":       c.dedupMillis.set(Integer.parseInt(val.trim())); return true;
            case "echoLockoutMillis": c.echoLockoutMillis.set(Integer.parseInt(val.trim())); return true;
            case "minConfidence":     c.minConfidence.set(Double.parseDouble(val.trim())); return true;
            case "requireSneak":      c.requireSneak.set(Boolean.parseBoolean(val)); return true;
            case "triggerWord":       c.triggerWord.set(val); return true;
            case "showMisses":        c.showMisses.set(Boolean.parseBoolean(val)); return true;
            case "enableEchoSfx":     c.enableEchoSfx.set(Boolean.parseBoolean(val)); return true;
            case "streamerMode":      c.streamerMode.set(Boolean.parseBoolean(val)); return true;
            case "sassMode":          c.sassMode.set(Boolean.parseBoolean(val)); return true;
            case "castQueueSize":     c.castQueueSize.set(Integer.parseInt(val.trim())); return true;
            case "clientPreflight":   c.clientPreflight.set(Boolean.parseBoolean(val)); return true;
            case "hudCorner":         c.hudCorner.set(VoiceSpellsConfig.Corner.valueOf(val.trim().toUpperCase(Locale.ROOT))); return true;
            case "hudOffsetX":        c.hudOffsetX.set(Integer.parseInt(val.trim())); return true;
            case "hudOffsetY":        c.hudOffsetY.set(Integer.parseInt(val.trim())); return true;
            case "globalOpacity":     c.globalOpacity.set(Double.parseDouble(val.trim())); return true;
            case "themePreset":       c.themePreset.set(VoiceSpellsConfig.ThemePreset.valueOf(val.trim().toUpperCase(Locale.ROOT))); return true;
            case "uiPalette":         c.uiPalette.set(VoiceSpellsConfig.UiPalette.valueOf(val.trim().toUpperCase(Locale.ROOT))); return true;
            case "noiseGateRms":      c.noiseGateRms.set(Double.parseDouble(val.trim())); return true;
            case "handsFreeConfirm":  c.handsFreeConfirm.set(Boolean.parseBoolean(val)); return true;
            case "alwaysShowHeard":   c.alwaysShowHeard.set(Boolean.parseBoolean(val)); return true;
            case "combatOnly":        c.combatOnly.set(Boolean.parseBoolean(val)); return true;
            case "pauseWhenAfk":      c.pauseWhenAfk.set(Boolean.parseBoolean(val)); return true;
            case "afkSeconds":        c.afkSeconds.set(Integer.parseInt(val.trim())); return true;
            case "chatRankTag":       c.chatRankTag.set(Boolean.parseBoolean(val)); return true;
            case "voiceHotbarSelect": c.voiceHotbarSelect.set(Boolean.parseBoolean(val)); return true;
            case "customPhrases":     ((net.neoforged.neoforge.common.ModConfigSpec.ConfigValue) c.customPhrases).set(splitList(val)); return true;
            case "incantations":      ((net.neoforged.neoforge.common.ModConfigSpec.ConfigValue) c.incantations).set(splitList(val)); return true;
            case "loadouts":          ((net.neoforged.neoforge.common.ModConfigSpec.ConfigValue) c.loadouts).set(splitList(val)); return true;
            case "blockedSpells":     ((net.neoforged.neoforge.common.ModConfigSpec.ConfigValue) c.blockedSpells).set(splitList(val)); return true;
            case "triggerWords":      ((net.neoforged.neoforge.common.ModConfigSpec.ConfigValue) c.triggerWords).set(splitList(val)); return true;
            case "perSpellMinConfidence": ((net.neoforged.neoforge.common.ModConfigSpec.ConfigValue) c.perSpellMinConfidence).set(splitList(val)); return true;
            default: return false; // unknown key — quietly skip
        }
    }

    /** Returns the formatted spell-of-the-day hint, including the player's progress toward
     *  the daily challenge target. Empty until the spell index is ready. */
    private String spellOfTheDay() {
        String id = VoiceStats.spellOfTheDayId();
        if (id == null || id.isEmpty()) return "";
        com.niko.voicespells.spells.SpellInfo info = com.niko.voicespells.spells.SpellInfo.of(id);
        String name = info.name == null || info.name.isEmpty() ? id : info.name;
        int casts = VoiceStats.spellOfTheDayCasts();
        if (casts >= VoiceStats.SOTD_TARGET) {
            return name + "  (✓ challenge complete)";
        }
        return name + "  (" + casts + "/" + VoiceStats.SOTD_TARGET + ")";
    }

    private List<String> splitList(String s) {
        if (s == null || s.isBlank()) return new ArrayList<>();
        List<String> out = new ArrayList<>();
        for (String part : s.split("\\|")) {
            if (!part.isEmpty()) out.add(part);
        }
        return out;
    }

    @Override
    public void onClose() {
        if (minecraft != null) minecraft.setScreen(parent);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        if (statusFlashUntil > 0 && System.currentTimeMillis() > statusFlashUntil && statusLabel != null) {
            statusLabel.setMessage(Component.empty());
            statusFlashUntil = 0L;
        }
        // Live countdown on the calibrate button. Switches to the resulting threshold for a
        // few seconds after calibration finishes, then returns to the default label.
        if (calibBtn != null) {
            if (VoiceController.isCalibrating()) {
                long remainingS = (VoiceController.calibRemainingNanos() + 999_999_999L) / 1_000_000_000L;
                calibBtn.setMessage(Component.literal("Listening… " + remainingS + "s"));
            } else if (VoiceController.lastCalibThreshold() > 0
                    && System.currentTimeMillis() < statusFlashUntil + 3000) {
                calibBtn.setMessage(Component.literal(
                    "Set noise gate to " + Math.round(VoiceController.lastCalibThreshold())));
            } else {
                calibBtn.setMessage(Component.literal("Auto-calibrate noise gate (talk for 5s)"));
            }
        }
        g.fill(0, 0, this.width, this.height, Theme.C_SCRIM);
        g.fill(px, py, px + panelW, py + panelH, Theme.C_PANEL);
        Theme.headerBand(g, px, py, panelW, Theme.HEADER_H);
        super.render(g, mouseX, mouseY, partial);
        Theme.roundedFrame(g, px, py, panelW, panelH, Theme.C_BORDER);
        Theme.accentGlow(g, px + Theme.PAD, py + Theme.HEADER_H, panelW - Theme.PAD * 2);
    }
}
