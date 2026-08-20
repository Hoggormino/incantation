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

    private static final String PROFILE_HEADER = "voicespells-profile:1";

    private final Screen parent;
    private StringWidget statusLabel;
    private NeonButton calibBtn;
    private long statusFlashUntil = 0L;
    /** Until when the calibrate button shows the resulting threshold instead of its label. */
    private long calibResultUntil = 0L;
    /** Previous frame's calibrating state, so the finish can be detected as an edge. */
    private boolean wasCalibrating = false;
    /** Top-left of the runtime panel + clamped dimensions; recomputed every {@link #init()}. */
    private int px, py, panelW;
    /** Header / footer rule positions for the shared chrome. */
    private int headerY, footerY;

    public ConfigMoreScreen(Screen parent) {
        super(Component.literal("More"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        // Full-window layout, matching the config screen: title at the top, actions under the
        // header rule, Back on the bottom edge. The old centred panel is why this screen drew two
        // rules at apparently random heights — they were the panel's edges, and the panel was
        // nowhere near the content.
        int colGap = 10;
        int gridW  = Math.min(310, width - 40);
        int colW   = (gridW - colGap) / 2;
        px = (width - gridW) / 2;
        panelW = gridW;
        // 42, not 46. Four pixels reclaimed for the button grid - see the stride note below.
        // The title occupies 14..23 and nothing is drawn at headerY any more, so there is no
        // chrome between them to clear.
        py = 42;
        headerY = 32;
        footerY = height - 40;

        StringWidget titleW = new StringWidget(0, 14, width, 9, title, font);
        titleW.alignCenter();
        titleW.setColor(0xFFFFFF);
        addRenderableWidget(titleW);

        // Back is registered BEFORE the button stack so it wins hit-testing. Widgets are probed
        // in insertion order, and under the old height-clamped panel the stack grew down into the Back
        // row — "Import Profile from Clipboard" spans nearly the full panel width, so it sat on
        // top of Back and swallowed the click, leaving Escape as the only way out.
        addRenderableWidget(NeonButton.of(width / 2 - 75, height - 28, 150, 20,
            CommonComponents.GUI_BACK, b -> onClose()));

        // Two-column grid, the Video Settings idiom. The single full-width stack needed
        // roughly 300px of height for nine rows and overflowed the panel even at fullscreen
        // (the old panel clamped to ~254 at 1080p GUI-scale auto) — the Today's-spell label collided
        // with a button and the last rows ran off the bottom. Nine actions in two columns is
        // five rows, which fits every window down to the 854x480 dev size, and it is how
        // vanilla lays out exactly this many options.
        int y = py;
        int colX1 = px;
        int colX2 = px + colW + colGap;
        boolean inWorld = minecraft != null && minecraft.player != null;
        int slot = 0;

        java.util.List<NeonButton> grid = new java.util.ArrayList<>();
        // First slot, and it earns it: picking the wrong capture device is the most common reason
        // the mod appears to do nothing at all, and until this screen existed the only cure was
        // hand-editing a device name into the toml.
        grid.add(NeonButton.of(0, 0, colW, 20, Component.literal("Microphone & Sound"),
            b -> { if (minecraft != null) minecraft.setScreen(new AudioDevicesScreen(this)); }));
        grid.add(NeonButton.of(0, 0, colW, 20, Component.literal("Welcome Wizard"),
            b -> { if (minecraft != null) minecraft.setScreen(new FirstRunScreen(this)); }));
        grid.add(NeonButton.of(0, 0, colW, 20, Component.literal("Voice Codex"),
            b -> { if (minecraft != null) minecraft.setScreen(new VoiceCodexScreen(this)); }));
        grid.add(NeonButton.of(0, 0, colW, 20, Component.literal("Diagnostics"),
            b -> { if (minecraft != null) minecraft.setScreen(new DiagnosticsScreen(this)); }));
        // The Live Monitor moved off the config screen and onto its own, so it needs a door.
        grid.add(NeonButton.of(0, 0, colW, 20, Component.literal("Live Monitor"),
            b -> { if (minecraft != null) minecraft.setScreen(new LiveMonitorScreen(this)); }));
        grid.add(NeonButton.of(0, 0, colW, 20, Component.literal("Help / Guide"),
            b -> { if (minecraft != null) minecraft.setScreen(new HelpScreen(this)); }));
        NeonButton arenaBtn = NeonButton.of(0, 0, colW, 20,
            Component.literal(inWorld ? "Test Arena" : "Test Arena (in-world)"),
            b -> { if (minecraft != null) minecraft.setScreen(new TestArenaScreen(this)); });
        arenaBtn.active = inWorld;
        grid.add(arenaBtn);
        grid.add(NeonButton.of(0, 0, colW, 20, Component.literal("Reload grammar"),
            b -> {
                VoiceController.onConfigChanged();
                flashStatus("Grammar reload requested", Theme.F_MATCH);
            }));
        calibBtn = NeonButton.of(0, 0, colW, 20,
            Component.literal("Calibrate mic (5s)"),
            b -> {
                if (!VoiceController.isCalibrating()) {
                    VoiceController.startNoiseGateCalibration();
                    // C_WARN, which means "in progress" - this used to use the theme accent,
                    // back when there was one.
                    flashStatus("Calibrating — say a few spell names…", Theme.C_WARN);
                }
            });
        grid.add(calibBtn);
        grid.add(NeonButton.of(0, 0, colW, 20, Component.literal("Export profile"),
            b -> exportProfile()));
        grid.add(NeonButton.of(0, 0, colW, 20, Component.literal("Import profile"),
            b -> importProfile()));

        // Stride derived from the space that actually exists, not the 24px this used to assume.
        // Everything below the grid (spell-of-the-day, status line, Back) is anchored to the
        // PANEL BOTTOM, while the grid grew downward from a fixed stride — so the two only
        // cleared each other by luck, and the margin was one button row wide. Adding a tenth
        // action spent the last of it. Dividing the real gap among the real rows means the next
        // button added compresses the grid instead of silently landing on the status line.
        int gridRows = (grid.size() + 1) / 2;
        // footerY - 26, not - 34. The two hint lines start at footerY - 24 and are 9px tall,
        // so 34 reserved ten pixels for nothing.
        //
        // Those fourteen pixels are the difference between vanilla-sized buttons and not. Six
        // rows need a stride of 22 to carry a 20px button; the old arithmetic yielded 20 at the
        // most common window sizes, so setHeight clamped every button to 18 and the whole screen
        // read as slightly shrunken next to any real Minecraft menu. Reclaiming the padding
        // rather than letting the buttons absorb the shortfall keeps them at vanilla's height,
        // which is the one dimension a player's eye actually calibrates against.
        int gridBottom = footerY - 26;
        int stride = Math.max(15, Math.min(24, (gridBottom - y) / Math.max(1, gridRows)));
        for (NeonButton btn : grid) {
            btn.setX(slot % 2 == 0 ? colX1 : colX2);
            btn.setY(y + (slot / 2) * stride);
            // A 20px-tall button in a compressed stride would overlap its neighbour; shrink the
            // buttons to match so the rows stay visually separate.
            btn.setHeight(Math.min(20, stride - 2));
            addRenderableWidget(btn);
            slot++;
        }
        y += gridRows * stride + 4;

        // Anchored just above the Back row rather than to the accumulated y. The button stack
        // added up to roughly py+262 from the old 340px preferred panel, which was clamped to the
        // window, so at GUI Scale 3-4 (including 1080p on Auto) the accumulated y landed below
        // the panel and the status line — the only feedback Import/Export gives — rendered
        // off-screen entirely.
        // 8px apart for 9px text: the status line and the spell-of-the-day hint below it
        // overlapped by a pixel, so every "Profile copied" landed on top of "Today's spell".
        // Left edge of the button column, not the screen's centre axis.
        //
        // Centring it made it the loudest thing on the panel every time you pressed Reload
        // grammar or Calibrate: a line of text arriving on the screen's own axis, directly under
        // the spell-of-the-day hint, competing with it. A transient status is not a headline.
        // The left edge is a real anchor here - it is where both button columns start - and it
        // keeps the message out of the middle where the eye is already looking.
        statusLabel = new StringWidget(px, footerY - 12, panelW, 9,
            Component.empty(), font);
        statusLabel.alignLeft();
        statusLabel.setColor(Theme.C_MUTED);
        addRenderableWidget(statusLabel);

        // Spell-of-the-day: deterministic-per-date suggestion to nudge players to try
        // unfamiliar spells. Sits at the bottom of the panel as a quiet hint.
        String suggestion = spellOfTheDay();
        if (!suggestion.isEmpty()) {
            StringWidget sotd = new StringWidget(px, footerY - 24, panelW, 9,
                Component.literal("Today's spell: " + suggestion), font);
            sotd.alignLeft();
            // C_MUTED, not C_FAINT. This line names a spell and tracks progress toward it, so it
            // is content; the faintest tier is for things whose absence would not be noticed.
            sotd.setColor(Theme.C_MUTED);
            addRenderableWidget(sotd);
        }

    }

    private void flashStatus(String text, int color) {
        if (statusLabel != null) {
            statusLabel.setMessage(Component.literal(text));
            statusLabel.setColor(color);
        }
        // Cleared by tick() when the flash lapses, so the line does not sit there afterwards as
        // a permanent label. A status that never leaves stops reading as a status.
        statusFlashUntil = System.currentTimeMillis() + 3500;
    }

    private void exportProfile() {
        VoiceSpellsConfig.Client c = VoiceSpellsConfig.CLIENT;
        StringBuilder sb = new StringBuilder();
        sb.append(PROFILE_HEADER).append('\n');
        // gatingMode and restrictToOwned decide WHEN the mod listens and WHAT it will match -
        // arguably the two most consequential settings there are - and a profile that omitted
        // them silently handed the importer someone else's tuning on top of their own gating.
        sb.append("gatingMode=").append(c.gatingMode.get().name()).append('\n');
        sb.append("restrictToOwned=").append(c.restrictToOwned.get()).append('\n');
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
        // Recognition tuning + modes added in newer builds
        sb.append("noiseGateRms=").append(c.noiseGateRms.get()).append('\n');
        sb.append("handsFreeConfirm=").append(c.handsFreeConfirm.get()).append('\n');
        sb.append("alwaysShowHeard=").append(c.alwaysShowHeard.get()).append('\n');
        sb.append("combatOnly=").append(c.combatOnly.get()).append('\n');
        sb.append("pauseWhenAfk=").append(c.pauseWhenAfk.get()).append('\n');
        sb.append("afkSeconds=").append(c.afkSeconds.get()).append('\n');
        sb.append("chatRankTag=").append(c.chatRankTag.get()).append('\n');
        sb.append("voiceHotbarSelect=").append(c.voiceHotbarSelect.get()).append('\n');
        // Both are on a settings tab, so a profile that omitted them handed the importer a
        // half-applied screen: their own vignette and focus behaviour under someone else's
        // tuning. Every setting the UI can change now round-trips.
        sb.append("castVignette=").append(c.castVignette.get()).append('\n');
        sb.append("suspendWhenUnfocused=").append(c.suspendWhenUnfocused.get()).append('\n');
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
        // An imported profile that evaporates on restart is the worst kind of silent failure -
        // see VoiceSpellsConfig.saveToDisk().
        VoiceSpellsConfig.saveToDisk();
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
            case "gatingMode":
                c.gatingMode.set(VoiceSpellsConfig.GatingMode.valueOf(
                    val.trim().toUpperCase(Locale.ROOT)));
                return true;
            case "restrictToOwned":   c.restrictToOwned.set(Boolean.parseBoolean(val)); return true;
            case "streamerMode":      c.streamerMode.set(Boolean.parseBoolean(val)); return true;
            case "sassMode":          c.sassMode.set(Boolean.parseBoolean(val)); return true;
            case "castQueueSize":     c.castQueueSize.set(Integer.parseInt(val.trim())); return true;
            case "clientPreflight":   c.clientPreflight.set(Boolean.parseBoolean(val)); return true;
            case "hudCorner":         c.hudCorner.set(VoiceSpellsConfig.Corner.valueOf(val.trim().toUpperCase(Locale.ROOT))); return true;
            case "hudOffsetX":        c.hudOffsetX.set(Integer.parseInt(val.trim())); return true;
            case "hudOffsetY":        c.hudOffsetY.set(Integer.parseInt(val.trim())); return true;
            case "globalOpacity":     c.globalOpacity.set(Double.parseDouble(val.trim())); return true;
            // Accepted and ignored: profiles exported by 0.10.3 and earlier carry these, and
            // the settings they named no longer exist. Silently skipping them keeps an old
            // profile importable instead of failing on its first line.
            case "themePreset", "uiPalette": return true;
            case "noiseGateRms":      c.noiseGateRms.set(Double.parseDouble(val.trim())); return true;
            case "handsFreeConfirm":  c.handsFreeConfirm.set(Boolean.parseBoolean(val)); return true;
            case "alwaysShowHeard":   c.alwaysShowHeard.set(Boolean.parseBoolean(val)); return true;
            case "combatOnly":        c.combatOnly.set(Boolean.parseBoolean(val)); return true;
            case "pauseWhenAfk":      c.pauseWhenAfk.set(Boolean.parseBoolean(val)); return true;
            case "afkSeconds":        c.afkSeconds.set(Integer.parseInt(val.trim())); return true;
            case "chatRankTag":       c.chatRankTag.set(Boolean.parseBoolean(val)); return true;
            case "voiceHotbarSelect": c.voiceHotbarSelect.set(Boolean.parseBoolean(val)); return true;
            case "castVignette":      c.castVignette.set(Boolean.parseBoolean(val)); return true;
            case "suspendWhenUnfocused": c.suspendWhenUnfocused.set(Boolean.parseBoolean(val)); return true;
//? if forge {
/*            case "customPhrases":     ((net.minecraftforge.common.ForgeConfigSpec.ConfigValue) c.customPhrases).set(splitList(val)); return true;
            case "incantations":      ((net.minecraftforge.common.ForgeConfigSpec.ConfigValue) c.incantations).set(splitList(val)); return true;
            case "loadouts":          ((net.minecraftforge.common.ForgeConfigSpec.ConfigValue) c.loadouts).set(splitList(val)); return true;
            case "blockedSpells":     ((net.minecraftforge.common.ForgeConfigSpec.ConfigValue) c.blockedSpells).set(splitList(val)); return true;
            case "triggerWords":      ((net.minecraftforge.common.ForgeConfigSpec.ConfigValue) c.triggerWords).set(splitList(val)); return true;
            case "perSpellMinConfidence": ((net.minecraftforge.common.ForgeConfigSpec.ConfigValue) c.perSpellMinConfidence).set(splitList(val)); return true;
*///?} else {
            case "customPhrases":     ((net.neoforged.neoforge.common.ModConfigSpec.ConfigValue) c.customPhrases).set(splitList(val)); return true;
            case "incantations":      ((net.neoforged.neoforge.common.ModConfigSpec.ConfigValue) c.incantations).set(splitList(val)); return true;
            case "loadouts":          ((net.neoforged.neoforge.common.ModConfigSpec.ConfigValue) c.loadouts).set(splitList(val)); return true;
            case "blockedSpells":     ((net.neoforged.neoforge.common.ModConfigSpec.ConfigValue) c.blockedSpells).set(splitList(val)); return true;
            case "triggerWords":      ((net.neoforged.neoforge.common.ModConfigSpec.ConfigValue) c.triggerWords).set(splitList(val)); return true;
            case "perSpellMinConfidence": ((net.neoforged.neoforge.common.ModConfigSpec.ConfigValue) c.perSpellMinConfidence).set(splitList(val)); return true;
//?}
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
        // The result window starts when calibration FINISHES. It used to be derived from
        // statusFlashUntil — the status line's own timer, for an unrelated message — and that
        // timer is set when the button is pressed and cleared a couple of seconds later, while a
        // calibration runs for five. It had therefore always expired by the time there was a
        // result to show, so "Gate set: N" never appeared at all and the player got no
        // confirmation of the number that had just been written to their config.
        boolean calibratingNow = VoiceController.isCalibrating();
        if (wasCalibrating && !calibratingNow) {
            calibResultUntil = System.currentTimeMillis() + 4000L;
        }
        wasCalibrating = calibratingNow;
        if (calibBtn != null) {
            if (calibratingNow) {
                long remainingS = (VoiceController.calibRemainingNanos() + 999_999_999L) / 1_000_000_000L;
                calibBtn.setMessage(Component.literal("Listening… " + remainingS + "s"));
            } else if (VoiceController.lastCalibThreshold() > 0
                    && System.currentTimeMillis() < calibResultUntil) {
                calibBtn.setMessage(Component.literal(
                    "Gate set: " + Math.round(VoiceController.lastCalibThreshold())));
            } else {
                // Must fit the half-width grid button; the old full-width label spilled out.
                calibBtn.setMessage(Component.literal("Calibrate mic (5s)"));
            }
        }
        // Vanilla backdrop first (blurred world in-game, dirt on the title screen, and it
        // honours the player's Menu Background Blur setting), then our own dim on top so
        // the panel still reads. Painting only a flat scrim, as this did before, opted out
        // of all of that and was a large part of why the screens felt foreign.
        Theme.screenChrome(this, g, mouseX, mouseY, partial, headerY, footerY, px, panelW);
        super.render(g, mouseX, mouseY, partial);
    }
}
