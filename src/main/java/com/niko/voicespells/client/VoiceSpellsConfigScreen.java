package com.niko.voicespells.client;

import com.niko.voicespells.VoiceSpellsConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Tabbed settings screen. Opens from Mods → Voice Spells → Config.
 *
 * Layout: header → tab bar → tab-specific controls → bottom buttons.
 *  - <b>Recognition</b> tab: speech-to-spell tuning (fuzzy, substring, dedup, debug monitor).
 *    When the monitor is on, the panel grows to show the recent recognition log + audio level.
 *  - <b>HUD</b> tab: chip corner, offset X/Y, and overall opacity (the previously toml-only
 *    knobs from {@code [hud]}). Live-applies to the in-game HUD without restart.
 *
 * Visual language is "minimalist magic with neon", driven by {@link Theme}.
 */
public final class VoiceSpellsConfigScreen extends Screen {

    // Preferred dimensions — the panel uses these when there's enough room. When the player
    // turns Minecraft's GUI Scale up far enough that the available screen is smaller, the
    // runtime {@code panelW} / {@code panelH} fields below get clamped via {@link Theme#fit}
    // so the panel always fits within the screen instead of being cut off.
    private static final int PANEL_W_PREF  = 384;
    private static final int PANEL_BASE_H  = 254;   // header + tabs + 6 rows + buttons
    private static final int MONITOR_H     = 156;   // extra height when the monitor is shown
    private static final int TAB_H         = 22;

    private enum Tab { RECOGNITION, HUD }

    private final Screen parent;

    // --- Recognition work state ---
    private boolean workDebug;
    private int     workFuzzy;
    private boolean workSubstring;
    private int     workDedup;
    private boolean workRestrictToOwned;
    // --- HUD work state ---
    private VoiceSpellsConfig.Corner workCorner;
    private int workOffsetX;
    private int workOffsetY;
    private int workOpacityPct; // 0..100 in UI, 0..1 in config
    private VoiceSpellsConfig.ThemePreset workTheme;
    private VoiceSpellsConfig.UiPalette   workPalette;

    private boolean initializedOnce = false;
    private Tab currentTab = Tab.RECOGNITION;

    /** Runtime panel size — clamped to fit within the screen so the layout doesn't get cut off
     *  when the player picks a large Minecraft GUI Scale. Recomputed every {@link #init()}. */
    private int panelX, panelY, panelW, panelH;

    /** Theme and palette as they were when the screen opened, plus whether Save ran.
     *
     *  <p>Cycling the theme or palette applies it immediately so the player can see the change
     *  on the panel in front of them. That preview is a live global mutation of {@link Theme},
     *  not screen-local state, so leaving via Cancel or Escape used to walk away with the
     *  previewed look still applied while the config on disk said something else — the whole
     *  UI stayed the wrong colour until something happened to reload the config. Restoring
     *  these in {@link #onClose()} makes Cancel mean cancel. */
    private VoiceSpellsConfig.ThemePreset origTheme;
    private VoiceSpellsConfig.UiPalette origPalette;
    private boolean saved = false;

    public VoiceSpellsConfigScreen(Screen parent) {
        super(Component.translatable("voicespells.config.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        VoiceSpellsConfig.Client c = VoiceSpellsConfig.CLIENT;
        if (!initializedOnce) {
            workDebug      = c.debugMonitor.get();
            workFuzzy      = c.fuzzyMaxDistance.get();
            workSubstring  = c.substringMatch.get();
            workDedup      = c.dedupMillis.get();
            workRestrictToOwned = c.restrictToOwned.get();
            workCorner     = c.hudCorner.get();
            workOffsetX    = c.hudOffsetX.get();
            workOffsetY    = c.hudOffsetY.get();
            workOpacityPct = (int) Math.round(c.globalOpacity.get() * 100);
            workTheme      = c.themePreset.get();
            workPalette    = c.uiPalette.get();
            // Remembered so Cancel can undo the live theme preview — see onClose().
            origTheme      = workTheme;
            origPalette    = workPalette;
            initializedOnce = true;
        }

        // Monitor only renders on the Recognition tab so the HUD tab stays compact.
        boolean showMonitor = currentTab == Tab.RECOGNITION && workDebug;
        // The Live Monitor is a real-time view of what the recognizer is hearing, so it needs
        // the device open. In single player any open screen pauses the game and
        // captureAllowedNow() then closes the mic, leaving the monitor permanently blank on
        // exactly the setup most people tune on. Only while the monitor is actually visible -
        // the config screen has no business holding the mic open on its other tabs.
        VoiceController.setDiagnosticCapture("livemonitor", showMonitor);
        // Clamp to fit the current screen so large GUI Scale settings don't push buttons off
        // the bottom or sides. The preferred dimensions still apply when there's enough room.
        panelW = Theme.fit(PANEL_W_PREF, width);
        panelH = Theme.fit(PANEL_BASE_H + (showMonitor ? MONITOR_H : 0), height);
        panelX = (width  - panelW) / 2;
        panelY = (height - panelH) / 2;

        // --- Title centered in the header band (StringWidget so it renders bright) ---
        StringWidget titleW = new StringWidget(panelX, panelY + (Theme.HEADER_H - 9) / 2,
            panelW, 9, title, font);
        titleW.alignCenter();
        titleW.setColor(Theme.C_ACCENT);
        addRenderableWidget(titleW);

        // --- Tab bar under the accent rule ---
        int tabsY = panelY + Theme.HEADER_H + 4;
        int tabW = (panelW - Theme.PAD * 2) / 2;
        addRenderableWidget(new TabButton(panelX + Theme.PAD,            tabsY, tabW, TAB_H,
            "Recognition", Tab.RECOGNITION));
        addRenderableWidget(new TabButton(panelX + Theme.PAD + tabW,     tabsY, tabW, TAB_H,
            "HUD",         Tab.HUD));

        // --- Tab content ---
        int contentY = tabsY + TAB_H + Theme.GAP_MD;
        int x = panelX + Theme.PAD;
        int ctrlX = panelX + panelW / 2;
        int ctrlW = panelW / 2 - Theme.PAD;

        switch (currentTab) {
            case RECOGNITION -> buildRecognitionTab(x, ctrlX, ctrlW, contentY);
            case HUD         -> buildHudTab(x, ctrlX, ctrlW, contentY);
        }

        // --- Bottom buttons (always present, span both tabs) ---
        int btnY = panelY + panelH - 28;
        int btnW = 60;
        int spellsW = 75;
        int moreW   = 55;
        addRenderableWidget(NeonButton.of(x, btnY, btnW, 20,
            Component.translatable("voicespells.config.reset"), b -> resetDefaults()));
        addRenderableWidget(NeonButton.of(x + btnW + 6, btnY, spellsW, 20,
            Component.translatable("voicespells.config.spelllist"),
            b -> { if (minecraft != null) minecraft.setScreen(new VoiceSpellsSpellListScreen(this)); }));
        addRenderableWidget(NeonButton.of(x + btnW + 6 + spellsW + 6, btnY, moreW, 20,
            Component.literal("More..."),
            b -> { if (minecraft != null) minecraft.setScreen(new ConfigMoreScreen(this)); }));
        addRenderableWidget(NeonButton.of(panelX + panelW - Theme.PAD - btnW * 2 - 6, btnY, btnW, 20,
            CommonComponents.GUI_CANCEL, b -> onClose()));
        addRenderableWidget(NeonButton.of(panelX + panelW - Theme.PAD - btnW, btnY, btnW, 20,
            CommonComponents.GUI_DONE, b -> save()));
    }

    private void buildRecognitionTab(int x, int ctrlX, int ctrlW, int y) {
        addLabel("Only owned spells", x, y + 6);
        addLabel("Debug Monitor",     x, y + Theme.ROW_H + 6);
        addLabel("Fuzzy Tolerance",   x, y + Theme.ROW_H * 2 + 6);
        addLabel("Substring Match",   x, y + Theme.ROW_H * 3 + 6);
        addLabel("Dedup Window",      x, y + Theme.ROW_H * 4 + 6);

        addRenderableWidget(NeonToggle.of(ctrlX, y, ctrlW, 20, workRestrictToOwned,
            val -> workRestrictToOwned = val));
        y += Theme.ROW_H;

        addRenderableWidget(NeonToggle.of(ctrlX, y, ctrlW, 20, workDebug,
            val -> { workDebug = val; rebuildWidgets(); }));
        y += Theme.ROW_H;

        addRenderableWidget(new NeonSlider(ctrlX, y, ctrlW, 20,
            workFuzzy, 0, 2, this::fuzzyLabel, v -> workFuzzy = v));
        y += Theme.ROW_H;

        addRenderableWidget(NeonToggle.of(ctrlX, y, ctrlW, 20, workSubstring,
            val -> workSubstring = val));
        y += Theme.ROW_H;

        addRenderableWidget(new NeonSlider(ctrlX, y, ctrlW, 20,
            workDedup, 0, 3000, v -> "Dedup: " + v + " ms", v -> workDedup = v));
    }

    private void buildHudTab(int x, int ctrlX, int ctrlW, int y) {
        addLabel("Corner",   x, y + 6);
        addLabel("Offset X", x, y + Theme.ROW_H + 6);
        addLabel("Offset Y", x, y + Theme.ROW_H * 2 + 6);
        addLabel("Opacity",  x, y + Theme.ROW_H * 3 + 6);
        addLabel("Palette",  x, y + Theme.ROW_H * 4 + 6);
        addLabel("Theme",    x, y + Theme.ROW_H * 5 + 6);

        addRenderableWidget(NeonCycle.of(ctrlX, y, ctrlW, 20,
            VoiceSpellsConfig.Corner.values(), workCorner,
            VoiceSpellsConfigScreen::prettyCorner,
            val -> workCorner = val));
        y += Theme.ROW_H;

        addRenderableWidget(new NeonSlider(ctrlX, y, ctrlW, 20,
            workOffsetX, 0, 1000, v -> "X: " + v + " px", v -> workOffsetX = v));
        y += Theme.ROW_H;

        addRenderableWidget(new NeonSlider(ctrlX, y, ctrlW, 20,
            workOffsetY, 0, 1000, v -> "Y: " + v + " px", v -> workOffsetY = v));
        y += Theme.ROW_H;

        addRenderableWidget(new NeonSlider(ctrlX, y, ctrlW, 20,
            workOpacityPct, 0, 100, v -> "Opacity: " + v + "%", v -> workOpacityPct = v));
        y += Theme.ROW_H;

        // Base palette: dark / light / midnight / slate. Independent of the accent Theme below.
        addRenderableWidget(NeonCycle.of(ctrlX, y, ctrlW, 20,
            VoiceSpellsConfig.UiPalette.values(), workPalette,
            p -> p.name().charAt(0) + p.name().substring(1).toLowerCase(),
            val -> {
                workPalette = val;
                Theme.applyPalette(val);
                // Same StringWidget caching gotcha as the theme cycle: existing widgets keep
                // their old colors so we rebuild to capture the fresh palette.
                rebuildWidgets();
            }));
        y += Theme.ROW_H;

        addRenderableWidget(NeonCycle.withLocks(ctrlX, y, ctrlW, 20,
            VoiceSpellsConfig.ThemePreset.values(), workTheme,
            p -> {
                String name = p.name().charAt(0) + p.name().substring(1).toLowerCase();
                // Locked themes show their required cast count so the player knows the goal.
                return VoiceStats.totalCasts() >= p.requiredCasts
                    ? name
                    : name + " [" + p.requiredCasts + " casts]";
            },
            p -> VoiceStats.totalCasts() < p.requiredCasts,
            val -> {
                // Locked? Don't commit / don't apply. The chip still shows the locked theme
                // (dim + ✗ prefix from the locked-aware render path) so the player sees the
                // goal, but the visual + persisted theme stay on whatever they had before.
                if (VoiceStats.totalCasts() < val.requiredCasts) return;
                workTheme = val;
                Theme.applyPreset(val);
                // StringWidget caches its color at construction time, so existing widgets
                // (title, labels) wouldn't pick up the new accent. Rebuild reruns init() and
                // captures the fresh palette into all freshly-constructed widgets.
                rebuildWidgets();
            }));
    }

    private static String prettyCorner(VoiceSpellsConfig.Corner c) {
        return switch (c) {
            case TOP_LEFT     -> "Top Left";
            case TOP_RIGHT    -> "Top Right";
            case BOTTOM_LEFT  -> "Bottom Left";
            case BOTTOM_RIGHT -> "Bottom Right";
        };
    }

    private void addLabel(String text, int x, int y) {
        StringWidget w = new StringWidget(x, y, font.width(text) + 2, 11,
            Component.literal(text), font);
        w.alignLeft();
        w.setColor(Theme.C_TEXT);
        addRenderableWidget(w);
    }

    private String fuzzyLabel(int v) {
        return switch (v) {
            case 0  -> "Fuzzy: Exact only";
            case 1  -> "Fuzzy: ±1 letter";
            default -> "Fuzzy: ±2 letters";
        };
    }

    private void resetDefaults() {
        // Reset only the controls on the visible tab so the user doesn't accidentally clobber
        // settings on the tab they aren't looking at.
        switch (currentTab) {
            case RECOGNITION -> {
                workDebug     = false;
                workFuzzy     = 1;
                workSubstring = true;
                workDedup     = 800;
                workRestrictToOwned = true;
            }
            case HUD -> {
                workCorner    = VoiceSpellsConfig.Corner.BOTTOM_LEFT;
                workOffsetX   = 5;
                workOffsetY   = 28;
                workOpacityPct = 100;
                workPalette   = VoiceSpellsConfig.UiPalette.DARK;
                workTheme     = VoiceSpellsConfig.ThemePreset.ARCANE;
                Theme.applyPalette(workPalette);
                Theme.applyPreset(workTheme);
            }
        }
        rebuildWidgets();
    }

    private void save() {
        VoiceSpellsConfig.Client c = VoiceSpellsConfig.CLIENT;
        c.debugMonitor.set(workDebug);
        c.fuzzyMaxDistance.set(workFuzzy);
        c.substringMatch.set(workSubstring);
        c.dedupMillis.set(workDedup);
        c.restrictToOwned.set(workRestrictToOwned);
        c.hudCorner.set(workCorner);
        c.hudOffsetX.set(workOffsetX);
        c.hudOffsetY.set(workOffsetY);
        c.globalOpacity.set(workOpacityPct / 100.0);
        // Belt-and-braces: don't persist a theme the player hasn't actually unlocked.
        if (VoiceStats.totalCasts() < workTheme.requiredCasts) {
            workTheme = VoiceSpellsConfig.ThemePreset.ARCANE;
            Theme.applyPreset(workTheme);
        }
        c.themePreset.set(workTheme);
        c.uiPalette.set(workPalette);
        // set() alone does not reach the disk on NeoForge - see VoiceSpellsConfig.saveToDisk().
        VoiceSpellsConfig.saveToDisk();
        // set() persists but the reload event is debounced — refresh cache now so the mic
        // thread and the HUD pick up new values immediately, and rebuild the recognizer
        // grammar live so customPhrase / index changes apply without a restart.
        VoiceSpellsConfig.refreshCache();
        VoiceController.onConfigChanged();
        saved = true;
        onClose();
    }

    @Override
    public void removed() {
        // Safety net: init() may have taken the mic for the Live Monitor, and a screen can be
        // swapped out without onClose() (another mod's setScreen, a disconnect, a resource
        // reload). Idempotent.
        try { VoiceController.setDiagnosticCapture("livemonitor", false); } catch (Throwable ignored) {}
        super.removed();
    }

    @Override
    public void onClose() {
        try { VoiceController.setDiagnosticCapture("livemonitor", false); } catch (Throwable ignored) {}
        // Cancel / Escape: drop the live theme preview back to what is actually persisted.
        if (!saved) {
            if (origPalette != null) Theme.applyPalette(origPalette);
            if (origTheme != null) Theme.applyPreset(origTheme);
        }
        if (minecraft != null) minecraft.setScreen(parent);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        // Vanilla backdrop first (blurred world in-game, dirt on the title screen, and it
        // honours the player's Menu Background Blur setting), then our own dim on top so
        // the panel still reads. Painting only a flat scrim, as this did before, opted out
        // of all of that and was a large part of why the screens felt foreign.
        Theme.background(this, g, mouseX, mouseY, partial);
        g.fill(0, 0, this.width, this.height, Theme.C_SCRIM);
        g.fill(panelX, panelY, panelX + panelW, panelY + panelH, Theme.C_PANEL);
        Theme.headerBand(g, panelX, panelY, panelW, Theme.HEADER_H);

        super.render(g, mouseX, mouseY, partial);

        Theme.roundedFrame(g, panelX, panelY, panelW, panelH, Theme.C_BORDER);
        Theme.accentGlow(g, panelX + Theme.PAD, panelY + Theme.HEADER_H,
            panelW - Theme.PAD * 2);
        // Thin divider just under the tab bar to separate tabs from content.
        Theme.divider(g, panelX + Theme.PAD,
            panelY + Theme.HEADER_H + 4 + TAB_H + 1,
            panelW - Theme.PAD * 2);

        if (currentTab == Tab.RECOGNITION && workDebug) renderMonitor(g);
    }

    private void renderMonitor(GuiGraphics g) {
        int mx = panelX + Theme.PAD;
        // Anchor the monitor right under the last row of the Recognition tab. We now have
        // 5 rows there (added "Only owned spells"), so step down by ROW_H*5 plus the tab-bar
        // and gap offsets.
        int my = panelY + Theme.HEADER_H + 4 + TAB_H + Theme.GAP_MD + Theme.ROW_H * 5 + 6;
        int mw = panelW - Theme.PAD * 2;
        int mh = MONITOR_H - 16;

        // Live JVM heap stat — gives the user a sanity check on whether the recogniser is
        // bloating memory (it shouldn't; Vosk is C++, but the bridging buffers + grammar grow
        // with the spell index).
        Runtime rt = Runtime.getRuntime();
        long usedMb  = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
        long totalMb = rt.totalMemory() / (1024 * 1024);
        String heap = " · heap " + usedMb + "/" + totalMb + "MB";
        g.drawString(font, Component.literal("LIVE MONITOR" + heap), mx, my, Theme.C_TEXT, !Theme.lightSurface());
        int meterX = mx + font.width("LIVE MONITOR" + heap) + 10;
        int meterW = mw - (meterX - mx);
        drawWaveform(g, meterX, my, meterW, 8);
        my += 11;
        g.fill(mx, my, mx + mw, my + mh, Theme.C_INSET);
        Theme.insetShadow(g, mx, my, mw);
        Theme.roundedFrame(g, mx, my, mw, mh, Theme.C_DIVIDER);

        List<VoiceController.RecognitionEvent> events = VoiceController.recentEvents();
        if (events.isEmpty()) {
            g.drawString(font, Component.literal("(say a spell — entries appear here)"),
                mx + 4, my + 4, Theme.C_FAINT, !Theme.lightSurface());
            return;
        }
        long now = System.nanoTime();
        int rowY = my + 4;
        int lineH = 11;
        int maxRows = (mh - 8) / lineH;
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
                color = Theme.F_MATCH;
                outcome = "→ " + shortId(e.matched());
            }
            String conf = String.format(Locale.ROOT, "c%.2f", e.confidence());
            String tier = e.tier() == ' ' ? " " : String.valueOf(e.tier());
            String line = String.format(Locale.ROOT, "%2ds %s [%s] \"%s\" %s",
                ageSec, conf, tier, truncate(e.heard(), 16), outcome);
            g.drawString(font, Component.literal(line), mx + 4, rowY, color, !Theme.lightSurface());
            rowY += lineH;
        }
    }

    /** Rolling waveform of recent audio levels — looks alive when you speak, flatlines when
     *  quiet. Replaces the older single-bar meter for more diagnostic value. */
    private static void drawWaveform(GuiGraphics g, int x, int y, int w, int h) {
        if (w < 12) return;
        g.fill(x, y, x + w, y + h, Theme.C_INSET);
        Theme.roundedFrame(g, x, y, w, h, Theme.C_DIVIDER);
        float[] data = VoiceController.waveformSnapshot();
        int bars = data.length;
        float barW = (float) (w - 4) / bars;
        for (int i = 0; i < bars; i++) {
            int barX = x + 2 + (int) (i * barW);
            int barNextX = x + 2 + (int) ((i + 1) * barW);
            int barH = Math.max(1, (int) (data[i] * (h - 4)));
            int barY = y + h - 2 - barH;
            // Newest bars get the brighter neon — the rightmost columns are the most recent
            // ~1.5s, draw them with C_ACCENT_BRIGHT so the eye reads the trace direction.
            int color = (i > bars * 3 / 4) ? Theme.C_ACCENT_BRIGHT : Theme.C_ACCENT;
            g.fill(barX, barY, Math.max(barX + 1, barNextX - 1), y + h - 2, color);
        }
    }

    private static String shortId(String id) {
        int colon = id.indexOf(':');
        return colon >= 0 ? id.substring(colon + 1) : id;
    }
    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    // ---------------------------------------------------------------------------------
    // Tab button — flat label with neon underline on active. Matches the panel chrome.
    // ---------------------------------------------------------------------------------
    private final class TabButton extends AbstractWidget {
        private final String label;
        private final Tab tab;

        TabButton(int x, int y, int w, int h, String label, Tab tab) {
            super(x, y, w, h, Component.literal(label));
            this.label = label;
            this.tab = tab;
        }

        @Override
        protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partial) {
            boolean active = currentTab == tab;
            boolean hov = isHoveredOrFocused();
            int color = active ? Theme.C_ACCENT_BRIGHT
                      : (hov ? Theme.C_TEXT : Theme.C_MUTED);
            int textX = getX() + getWidth() / 2;
            int textY = getY() + (getHeight() - 8) / 2;
            g.drawCenteredString(Minecraft.getInstance().font,
                Component.literal(label), textX, textY, color);

            int labelW = font.width(label);
            int barX = getX() + (getWidth() - labelW) / 2;
            int barY = getY() + getHeight() - 2;
            if (active) {
                // Neon underline beneath the label, the width of the text itself.
                g.fill(barX, barY, barX + labelW, barY + 1, Theme.C_ACCENT);
            } else if (hov) {
                // Faint half-width hint under hover.
                int hintW = labelW / 2;
                int hintX = getX() + (getWidth() - hintW) / 2;
                g.fill(hintX, barY, hintX + hintW, barY + 1, Theme.C_ACCENT_FAINT);
            }
        }

        @Override
        public void onClick(double mx, double my) {
            if (currentTab != tab) {
                currentTab = tab;
                rebuildWidgets();
            }
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput n) {}
    }

}
