package com.niko.voicespells.client;

import com.niko.voicespells.VoiceSpellsConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
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
    // 320 wide, sized toward Simple Voice Chat's fixed 248x219 rather than the old 384 slab
    // that filled most of the screen at GUI-scale auto. Bottom buttons moved to two rows to
    // make the narrower width workable and to remove the dead vertical gap the single
    // overcrowded row used to leave above itself.
    private static final int PANEL_W_PREF  = 320;
    private static final int PANEL_BASE_H  = 206;   // header + tabs + 3 grid rows + 2 button rows
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
    /** Per-row pitch for the current layout — Theme.ROW_H normally, smaller when the panel is
     *  height-clamped on small windows. Set in init() before the tab builders run. */
    /** Whether the Live Monitor has room to draw without colliding with the option rows.
     *  Computed in init() from the grid geometry; read by render(). */
    private boolean monitorFits = true;

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
        titleW.setColor(Theme.C_TEXT);
        addRenderableWidget(titleW);

        // --- Tab bar under the accent rule ---
        int tabsY = panelY + Theme.HEADER_H + 4;
        int tabW = (panelW - Theme.PAD * 2) / 2;
        addRenderableWidget(new TabButton(panelX + Theme.PAD,            tabsY, tabW, TAB_H,
            "Recognition", Tab.RECOGNITION));
        addRenderableWidget(new TabButton(panelX + Theme.PAD + tabW,     tabsY, tabW, TAB_H,
            "HUD",         Tab.HUD));

        // --- Tab content: a two-column option grid ---
        //
        // Three rows for five settings, three for six. The old label-plus-control layout needed
        // five and six rows respectively, which is why it had to compress rows on short windows
        // and still collided with the buttons. Halving the row count made the whole compression
        // mechanism unnecessary; what remains is a fixed, uniform grid.
        int contentY = tabsY + TAB_H + Theme.GAP_MD;
        int gridX = panelX + Theme.PAD;
        int colW = (panelW - Theme.PAD * 2 - 4) / 2;
        int gridRows = 3;                                   // both tabs land on three
        // The monitor renders under the grid, so it only fits if there is room left below it.
        int gridBottom = contentY + gridRows * GRID_ROW;
        monitorFits = !showMonitor || (panelY + panelH - 58) - gridBottom >= 60;

        switch (currentTab) {
            case RECOGNITION -> buildRecognitionTab(gridX, colW, contentY);
            case HUD         -> buildHudTab(gridX, colW, contentY);
        }

        // --- Bottom buttons, two rows (always present, span both tabs) ---
        // Secondary actions on one row, Cancel/Done underneath — vanilla gives its primary
        // exits their own row (see any options screen). Five buttons on one row needed 328px
        // of labels and is what previously forced the panel out to 384 wide.
        int avail = panelW - Theme.PAD * 2;
        int row1Y = panelY + panelH - 52;
        int row2Y = panelY + panelH - 28;
        int thirdW = (avail - 12) / 3;
        addRenderableWidget(NeonButton.of(gridX, row1Y, thirdW, 20,
            Component.translatable("voicespells.config.reset"), b -> resetDefaults()));
        addRenderableWidget(NeonButton.of(gridX + thirdW + 6, row1Y, thirdW, 20,
            Component.translatable("voicespells.config.spelllist"),
            b -> { if (minecraft != null) minecraft.setScreen(new VoiceSpellsSpellListScreen(this)); }));
        addRenderableWidget(NeonButton.of(gridX + (thirdW + 6) * 2, row1Y, avail - (thirdW + 6) * 2, 20,
            Component.literal("More..."),
            b -> { if (minecraft != null) minecraft.setScreen(new ConfigMoreScreen(this)); }));
        int halfW = (avail - 6) / 2;
        addRenderableWidget(NeonButton.of(gridX, row2Y, halfW, 20,
            CommonComponents.GUI_CANCEL, b -> onClose()));
        addRenderableWidget(NeonButton.of(gridX + halfW + 6, row2Y, avail - halfW - 6, 20,
            CommonComponents.GUI_DONE, b -> save()));
    }

    // ---------------------------------------------------------------------------------
    // Option grid.
    //
    // Both tabs used to be a left column of labels with a control column beside it, each row
    // 24px tall. That is a form layout, not a Minecraft one: the game has no label column
    // anywhere in its options: every setting is one wide button reading "Graphics: Fancy", and
    // the screen is a two-column grid of those. Adopting it removes the cramped left column,
    // makes every control twice as wide (so values stop truncating), halves the row count —
    // five settings become three rows, which is what killed the whole row-compression problem —
    // and gives each setting one click target instead of two aligned pieces.
    //
    // Every control also carries a hover tooltip explaining what it does, the way vanilla's own
    // options do, because "Substring Match: ON" tells a new player nothing on its own.
    // ---------------------------------------------------------------------------------

    /** Pitch of one grid row: a 20px control plus a 4px gutter. */
    private static final int GRID_ROW = 24;

    /** Place a control at grid slot {@code i}, left column for even, right for odd. */
    private <T extends net.minecraft.client.gui.components.AbstractWidget> T slot(
            T w, int i, int gridX, int colW, int y) {
        w.setX(gridX + (i % 2) * (colW + 4));
        w.setY(y + (i / 2) * GRID_ROW);
        return addRenderableWidget(w);
    }

    private static void help(net.minecraft.client.gui.components.AbstractWidget w, String text) {
        w.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(text)));
    }

    private void buildRecognitionTab(int gridX, int colW, int y) {
        int i = 0;
        help(slot(NeonToggle.named(0, 0, colW, 20, "Owned only", workRestrictToOwned,
                v -> workRestrictToOwned = v), i++, gridX, colW, y),
            "Only listen for spells you actually have equipped. Off means every indexed spell "
            + "is a candidate, which makes false matches far more likely.");

        help(slot(NeonToggle.named(0, 0, colW, 20, "Live monitor", workDebug,
                v -> { workDebug = v; rebuildWidgets(); }), i++, gridX, colW, y),
            "Show a real-time panel of what the recogniser is hearing, with the match tier for "
            + "each phrase. Useful while tuning, noisy the rest of the time.");

        help(slot(new NeonSlider(0, 0, colW, 20, workFuzzy, 0, 2,
                this::fuzzyLabel, v -> workFuzzy = v), i++, gridX, colW, y),
            "How many letters a heard phrase may differ from a spell name and still match. "
            + "Higher catches more mispronunciations and more wrong spells.");

        help(slot(NeonToggle.named(0, 0, colW, 20, "Substring", workSubstring,
                v -> workSubstring = v), i++, gridX, colW, y),
            "Match when a spell name appears inside a longer sentence, so talking normally can "
            + "still cast. Turn off if casual conversation keeps triggering spells.");

        help(slot(new NeonSlider(0, 0, colW, 20, workDedup, 0, 3000,
                v -> "Repeat gap: " + v + " ms", v -> workDedup = v), i++, gridX, colW, y),
            "Ignore the same spell heard again within this long. Stops one drawn-out word from "
            + "casting twice.");
    }

    private void buildHudTab(int gridX, int colW, int y) {
        int i = 0;
        help(slot(NeonCycle.named(0, 0, colW, 20, "Corner", VoiceSpellsConfig.Corner.values(),
                workCorner, VoiceSpellsConfigScreen::prettyCorner, v -> workCorner = v),
                i++, gridX, colW, y),
            "Which corner of the screen the voice HUD sits in.");

        help(slot(new NeonSlider(0, 0, colW, 20, workOffsetX, 0, 1000,
                v -> "Offset X: " + v, v -> workOffsetX = v), i++, gridX, colW, y),
            "Nudge the HUD horizontally away from its corner.");

        help(slot(new NeonSlider(0, 0, colW, 20, workOffsetY, 0, 1000,
                v -> "Offset Y: " + v, v -> workOffsetY = v), i++, gridX, colW, y),
            "Nudge the HUD vertically away from its corner.");

        help(slot(new NeonSlider(0, 0, colW, 20, workOpacityPct, 0, 100,
                v -> "Opacity: " + v + "%", v -> workOpacityPct = v), i++, gridX, colW, y),
            "How solid the HUD is over the world.");

        help(slot(NeonCycle.named(0, 0, colW, 20, "Menu style", VoiceSpellsConfig.UiPalette.values(),
                workPalette, VoiceSpellsConfigScreen::prettyPalette,
                val -> {
                    workPalette = val;
                    Theme.applyPalette(val);
                    // StringWidget caches its colour at construction, so existing widgets keep
                    // the old palette until the screen is rebuilt.
                    rebuildWidgets();
                }), i++, gridX, colW, y),
            "The menu surface. Vanilla is the light container look; Midnight is a dark panel; "
            + "Slate sits between them.");

        help(slot(NeonCycle.namedWithLocks(0, 0, colW, 20, "Accent",
                VoiceSpellsConfig.ThemePreset.values(), workTheme,
                p -> {
                    String name = p.name().charAt(0) + p.name().substring(1).toLowerCase();
                    // Locked themes show their required cast count so the player knows the goal.
                    return VoiceStats.totalCasts() >= p.requiredCasts
                        ? name
                        : name + " [" + p.requiredCasts + "]";
                },
                p -> VoiceStats.totalCasts() < p.requiredCasts,
                val -> {
                    // Locked? Don't commit and don't apply. The label still shows the locked
                    // theme with its goal, but nothing visual or persisted changes.
                    if (VoiceStats.totalCasts() < val.requiredCasts) return;
                    workTheme = val;
                    Theme.applyPreset(val);
                    rebuildWidgets();
                }), i++, gridX, colW, y),
            "Accent colour, used for meters and highlights. Locked themes show the number of "
            + "voice casts needed to unlock them.");
    }

    private static String prettyPalette(VoiceSpellsConfig.UiPalette p) {
        // DARK is the light vanilla-container palette; the constant name is kept for config
        // compatibility, so the LABEL has to say what the player actually gets.
        return switch (p) {
            case DARK     -> "Vanilla";
            case MIDNIGHT -> "Midnight";
            case SLATE    -> "Slate";
        };
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
        Theme.panel(g, panelX, panelY, panelW, panelH);
        Theme.headerBand(g, panelX, panelY, panelW, Theme.HEADER_H);

        super.render(g, mouseX, mouseY, partial);

        Theme.accentGlow(g, panelX + Theme.PAD, panelY + Theme.HEADER_H,
            panelW - Theme.PAD * 2);
        // Thin divider just under the tab bar to separate tabs from content.
        Theme.divider(g, panelX + Theme.PAD,
            panelY + Theme.HEADER_H + 4 + TAB_H + 1,
            panelW - Theme.PAD * 2);

        if (currentTab == Tab.RECOGNITION && workDebug) {
            if (monitorFits) {
                renderMonitor(g);
            } else {
                // Silently dropping it would read as a broken toggle, so state the reason.
                Theme.text(g, font, "Live Monitor needs a taller window",
                    panelX + Theme.PAD,
                    panelY + Theme.HEADER_H + 4 + TAB_H + Theme.GAP_MD + 3 * GRID_ROW + 6,
                    Theme.C_WARN);
            }
        }
    }

    private void renderMonitor(GuiGraphics g) {
        int mx = panelX + Theme.PAD;
        // Anchor the monitor right under the last row of the Recognition tab. We now have
        // 5 rows there (added "Only owned spells"), so step down by ROW_H*5 plus the tab-bar
        // and gap offsets.
        int my = panelY + Theme.HEADER_H + 4 + TAB_H + Theme.GAP_MD + 3 * GRID_ROW + 6;
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
    // Tab button.
    // ---------------------------------------------------------------------------------
    /**
     * A tab in the tab bar: a vanilla button whose selected state is PRESSED — the language the
     * creative inventory and the recipe book use for "you are here".
     *
     * <p>Renders nothing itself. It used to paint its own fill and bevel lines, and once every
     * other control on the screen became a real textured widget this was the one box still drawn
     * by hand — visibly a different shade sitting right beside the HUD tab. Vanilla draws the
     * pressed (inactive) variant of its own button sprite for the current tab, which is exactly
     * the effect the hand-drawn version was reaching for.
     */
    private final class TabButton extends Button {
        private final Tab tab;

        TabButton(int x, int y, int w, int h, String label, Tab tab) {
            super(x, y, w, h, Component.literal(label), b -> {}, Button.DEFAULT_NARRATION);
            this.tab = tab;
            // Not clickable while it IS the current tab, which is also what draws it pressed.
            this.active = currentTab != tab;
        }

        @Override
        public void onPress() {
            if (currentTab != tab) {
                currentTab = tab;
                rebuildWidgets();
            }
        }
    }

}
