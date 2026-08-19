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
    /**
     * Which of Minecraft's two screen archetypes this uses.
     *
     * <p>The game has exactly two, and mixing them is what makes a modded screen feel off. A
     * CONTAINER screen (chest, furnace) is a textured panel with dark text and no buttons. An
     * OPTIONS screen (Video Settings, Controls) has no panel at all — just the blurred world, a
     * white centred title and a grid of buttons. This screen is a settings screen wearing a
     * container's clothes, so both readings are defensible and it is worth being able to see
     * each one running rather than arguing about it.
     */
    private static final boolean PANELLESS = true;

    // Vanilla's own option-button metrics: 150 wide, 20 tall, 4 apart, which is what every
    // two-column options screen in the game uses.
    private static final int COL_W         = 150;
    private static final int COL_GAP       = 4;
    private static final int GRID_W        = COL_W * 2 + COL_GAP;

    private static final int PANEL_W_PREF  = GRID_W + 24;   // grid + a 12px margin each side
    private static final int PANEL_BASE_H  = 190;   // title + tabs + 3 grid rows + 2 button rows
    private static final int MONITOR_H     = 156;   // extra height when the monitor is shown
    private static final int TAB_H         = 18;
    /** Space above the title inside the panel, and below it before the tabs. */
    private static final int TITLE_TOP     = 8;
    private static final int TITLE_H       = 18;

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

    private boolean initializedOnce = false;
    private Tab currentTab = Tab.RECOGNITION;

    /** Runtime panel size — clamped to fit within the screen so the layout doesn't get cut off
     *  when the player picks a large Minecraft GUI Scale. Recomputed every {@link #init()}. */
    private int panelX, panelY, panelW, panelH;
    /** Y of the header and footer separator lines in panelless mode. */
    private int headerY, footerY;
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
            // Remembered so Cancel can undo the live theme preview — see onClose().
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
        // Geometry. In panelless mode the content is simply centred on the SCREEN, the way
        // vanilla options screens are; otherwise it is centred inside a panel sized to hug it.
        panelW = Theme.fit(PANEL_W_PREF, width);
        panelH = Theme.fit(PANEL_BASE_H + (showMonitor ? MONITOR_H : 0), height);
        panelX = (width  - panelW) / 2;
        panelY = (height - panelH) / 2;

        int gridW = Math.min(GRID_W, panelW - 24);
        int colW  = (gridW - COL_GAP) / 2;
        int gridX = PANELLESS ? (width - gridW) / 2 : panelX + (panelW - gridW) / 2;

        int titleY, tabsY, contentY, row1Y, row2Y;
        if (PANELLESS) {
            // One centred block: title, tabs, options, buttons, with a rule above and below it.
            //
            // The buttons used to be pinned to the bottom of the SCREEN while the options sat at
            // the top, which is what vanilla does — but vanilla fills the space between with a
            // scrolling list, and this screen has five fixed options. On a tall window that left
            // a several-hundred-pixel void in the middle and the screen read as broken. Every
            // other screen in the mod is a centred block, so this one is too; the two rules give
            // it the header/footer framing without pretending there is a list to fill.
            int blockH = 18                                  // title
                       + 6 + TAB_H                           // tab row
                       + Theme.GAP_MD + gridRows() * GRID_ROW
                       + 12 + 24 + 20;                       // the two button rows
            int blockTop = Math.max(8, (height - blockH) / 2);

            titleY   = blockTop;
            headerY  = blockTop + 14;                        // rule under the title
            tabsY    = headerY + 8;
            contentY = tabsY + TAB_H + Theme.GAP_MD;
            row1Y    = contentY + gridRows() * GRID_ROW + 12;
            row2Y    = row1Y + 24;
            footerY  = row1Y - 8;                            // rule above the buttons
        } else {
            titleY   = panelY + TITLE_TOP;
            tabsY    = panelY + TITLE_TOP + TITLE_H;
            contentY = tabsY + TAB_H + Theme.GAP_MD;
            row2Y    = panelY + panelH - 26;
            row1Y    = row2Y - 24;
        }

        // --- Title ---
        StringWidget titleW = new StringWidget(PANELLESS ? 0 : panelX, titleY,
            PANELLESS ? width : panelW, 9, title, font);
        titleW.alignCenter();
        // On a panelless screen the backdrop is the blurred world, so the title has to be white
        // and shadowed like vanilla's; on a container panel it is the panel's dark text tone.
        titleW.setColor(PANELLESS ? 0xFFFFFF : Theme.C_TEXT);
        addRenderableWidget(titleW);

        // --- Tab bar ---
        int tabW = gridW / 2;
        addRenderableWidget(new TabButton(gridX,        tabsY, tabW, TAB_H, "Recognition", Tab.RECOGNITION));
        addRenderableWidget(new TabButton(gridX + tabW, tabsY, tabW, TAB_H, "HUD",         Tab.HUD));

        // --- Tab content: a two-column option grid ---
        //
        // Three rows for five settings, three for six, on vanilla's own 150x20 option-button
        // metric. The old label-plus-control layout needed five and six rows, which is why it
        // had to compress rows on short windows and still collided with the buttons; halving the
        // row count made the whole compression mechanism unnecessary.
        int gridRows = gridRows();
        gridTop = contentY;
        buttonsTopY = row1Y;
        int gridBottom = contentY + gridRows * GRID_ROW;
        // Enough room for the header line plus a couple of rows, measured against the same
        // limit renderMonitor() will use. The old check compared free space to a bare 60 while
        // the monitor drew a fixed 140, so it passed in exactly the cases that then overlapped.
        monitorFits = !showMonitor || (row1Y - 6) - (gridBottom + 6) >= 46;

        switch (currentTab) {
            case RECOGNITION -> buildRecognitionTab(gridX, colW, contentY);
            case HUD         -> buildHudTab(gridX, colW, contentY);
        }

        // --- Bottom buttons, two rows (always present, span both tabs) ---
        // Secondary actions on one row, Cancel/Done underneath — vanilla gives its primary exits
        // their own row (see any options screen).
        int avail = gridW;
        int thirdW = (avail - 2 * COL_GAP) / 3;
        addRenderableWidget(NeonButton.of(gridX, row1Y, thirdW, 20,
            Component.translatable("voicespells.config.reset"), b -> resetDefaults()));
        addRenderableWidget(NeonButton.of(gridX + thirdW + COL_GAP, row1Y, thirdW, 20,
            Component.translatable("voicespells.config.spelllist"),
            b -> { if (minecraft != null) minecraft.setScreen(new VoiceSpellsSpellListScreen(this)); }));
        addRenderableWidget(NeonButton.of(gridX + (thirdW + COL_GAP) * 2, row1Y,
            avail - (thirdW + COL_GAP) * 2, 20, Component.literal("More..."),
            b -> { if (minecraft != null) minecraft.setScreen(new ConfigMoreScreen(this)); }));
        int halfW = (avail - COL_GAP) / 2;
        addRenderableWidget(NeonButton.of(gridX, row2Y, halfW, 20,
            CommonComponents.GUI_CANCEL, b -> onClose()));
        addRenderableWidget(NeonButton.of(gridX + halfW + COL_GAP, row2Y, avail - halfW - COL_GAP, 20,
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

    /** Header / footer rule. Delegates so every screen draws the identical line. */
    private void separator(GuiGraphics g, int y) {
        Theme.rule(g, 0, y, width);
    }

    /** Left edge of the monitor block: the grid's own left edge under either layout. */
    private int monitorX() {
        int gridW = Math.min(GRID_W, panelW - 24);
        return PANELLESS ? (width - gridW) / 2 : panelX + (panelW - gridW) / 2;
    }

    /** Rows the current tab's options occupy, two per row.
     *
     *  Counted, not hardcoded. It was fixed at 3 for both tabs, which was right only while the
     *  HUD tab had six options; removing the menu-style and accent cycles left it with four, and
     *  a hardcoded 3 would have reserved a phantom row and reopened the empty-space problem the
     *  layout was just fixed for. */
    private int gridRows() {
        int options = currentTab == Tab.HUD ? 4 : 5;
        return (options + 1) / 2;
    }

    /** Top of the monitor block: directly under the grid rows, in either layout. */
    private int monitorY() { return gridTop + gridRows() * GRID_ROW + 6; }

    /** The first thing below the monitor that it must not touch: the secondary button row. */
    private int monitorBottomLimit() { return buttonsTopY - 6; }

    /** Y of the upper button row, recorded by init() so the monitor can measure against it. */
    private int buttonsTopY;

    /** Y the option grid was actually laid out at; the monitor hangs off it. */
    private int gridTop;

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

        // Slider top end, NOT the config's own limit.
        //
        // The config allows 0..1000 so somebody on a 4K display can push the HUD a long way, and
        // the slider inherited that range — which meant the realistic values (the defaults are 5
        // and 28) put the handle hard against the left edge, where one pixel of travel is four
        // units and the control cannot be aimed at all. A HUD nudged off its corner lives in the
        // first couple of hundred pixels.
        //
        // The bound stretches to fit a value that is already larger, so somebody who set 800 in
        // the toml still sees their value and can still adjust it. Narrowing the range outright
        // would have silently clamped their setting the first time they pressed Done, which is
        // the kind of fix that is worse than the bug.
        int offsetMax = Math.max(200, Math.max(workOffsetX, workOffsetY));
        help(slot(new NeonSlider(0, 0, colW, 20, workOffsetX, 0, offsetMax,
                v -> "Offset X: " + v, v -> workOffsetX = v), i++, gridX, colW, y),
            "Nudge the HUD horizontally away from its corner.");

        help(slot(new NeonSlider(0, 0, colW, 20, workOffsetY, 0, offsetMax,
                v -> "Offset Y: " + v, v -> workOffsetY = v), i++, gridX, colW, y),
            "Nudge the HUD vertically away from its corner.");

        help(slot(new NeonSlider(0, 0, colW, 20, workOpacityPct, 0, 100,
                v -> "Opacity: " + v + "%", v -> workOpacityPct = v), i++, gridX, colW, y),
            "How solid the HUD is over the world.");

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
        // Nothing to undo on cancel any more: there is no live theme preview to roll back,
        // because there is no theme. Every remaining option is committed only by Done.
        if (minecraft != null) minecraft.setScreen(parent);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        // Vanilla backdrop first (blurred world in-game, dirt on the title screen, and it
        // honours the player's Menu Background Blur setting), then our own dim on top so
        // the panel still reads. Painting only a flat scrim, as this did before, opted out
        // of all of that and was a large part of why the screens felt foreign.
        Theme.background(this, g, mouseX, mouseY, partial);
        if (PANELLESS) {
            // The two separator lines vanilla puts under its header and above its footer. They
            // are what stop an options screen from being controls floating on scenery: the eye
            // reads a header band, a content band and a footer band instead of one soup. Drawn
            // rather than blitted because the sprites for them only exist from 1.20.5 on, and
            // this has to look the same on 1.20.1.
            separator(g, headerY);
            separator(g, footerY);
        } else {
            g.fill(0, 0, this.width, this.height, Theme.C_SCRIM);
            Theme.panel(g, panelX, panelY, panelW, panelH);
        }

        super.render(g, mouseX, mouseY, partial);

        // No accent rule under the title and no divider under the tabs. Neither exists in any
        // vanilla screen: a container has its title sitting straight on the panel, and an
        // options screen has nothing but the title and the buttons. Both lines were decoration
        // holding the two halves of the screen apart, and removing them is what lets the panel
        // shrink to hug its content.

        if (currentTab == Tab.RECOGNITION && workDebug) {
            if (monitorFits) {
                renderMonitor(g);
            } else {
                // Silently dropping it would read as a broken toggle, so state the reason.
                Theme.text(g, font, "Live Monitor needs a taller window",
                    monitorX(), monitorY(), Theme.C_WARN);
            }
        }
    }

    private void renderMonitor(GuiGraphics g) {
        int mx = monitorX();
        // Anchor the monitor right under the last row of the Recognition tab. We now have
        // 5 rows there (added "Only owned spells"), so step down by ROW_H*5 plus the tab-bar
        // and gap offsets.
        int my = monitorY();
        int mw = Math.min(GRID_W, panelW - 24);
        // Height from the space that EXISTS, not from the constant. MONITOR_H - 16 is 140px and
        // was drawn unconditionally, while the gate that decides whether to draw at all asked
        // only for 60px of clearance — so at common GUI scales the monitor drew straight over
        // the Reset / Spell List / More and Cancel / Done rows, and past the panel's bottom edge.
        // Deriving it means the well shrinks to fit instead of overlapping, and the gate below
        // refuses only when there is not even enough room to be useful.
        // Bounded at both ends: it must not overdraw the buttons (the limit) and it should not
        // balloon either — on a 1600x1000 window the derived height was 500px of mostly empty
        // log. MONITOR_H is what the block was designed to show, so it stays the ceiling.
        int mh = Math.max(0, Math.min(MONITOR_H - 16, monitorBottomLimit() - my - 4));

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
            // ~1.5s, draw the recent ones brighter so the eye reads the trace direction.
            int color = (i > bars * 3 / 4) ? 0xFFFFFFFF : 0xFFA0A0A0;
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
     * by hand — visibly a different shade sitting right beside the HUD tab.
     *
     * <p>To be precise about what the current tab actually looks like: vanilla's Button has no
     * "pressed" sprite, so an inactive tab draws with the DISABLED one — darker, with grey text.
     * That is the same convention vanilla uses for a control you cannot act on, which is exactly
     * true of the tab you are already on, and it is what the game itself does for the current
     * entry in several screens. It is not a pressed-in tab like the creative inventory's, and
     * claiming otherwise in a comment would only mislead the next person to read this.
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
