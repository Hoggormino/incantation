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
    // Vanilla's own option-button metrics: 150 wide, 20 tall, 4 apart, which is what every
    // two-column options screen in the game uses.
    private static final int COL_W         = 150;
    // 10, which is what vanilla uses. Its options list puts two 150-wide buttons at centre-155
    // and centre+5, so the gutter between them is exactly 10px; at 4 the pair read as one wide
    // control split by a hairline rather than as two separate options.
    private static final int COL_GAP       = 10;
    private static final int GRID_W        = COL_W * 2 + COL_GAP;

    private static final int PANEL_W_PREF  = GRID_W + 24;   // grid + a 12px margin each side
    private static final int PANEL_BASE_H  = 190;   // title + tabs + 3 grid rows + 2 button rows
    private static final int MONITOR_H     = 156;   // extra height when the monitor is shown
    /** Smallest monitor worth drawing: the header line, the level meter beside it, and one
     *  entry. A single most-recent recognition plus a live meter is genuinely useful — refusing
     *  to draw anything at 854x480, an ordinary window at GUI scale 2, was not. */
    private static final int MONITOR_MIN   = 34;
    /** Tab height. The SAME as every other control on the screen: the tab row is a row like any
     *  other, and making it 2px shorter than the options meant its gaps could never line up with
     *  theirs no matter what padding was chosen. */
    private static final int TAB_H         = 20;
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
    /** Whether this layout actually set space aside for the monitor block. */
    private boolean monitorReserved = false;

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
        // Geometry. In panelless mode the content is simply centred on the SCREEN, the way
        // vanilla options screens are; otherwise it is centred inside a panel sized to hug it.
        panelW = Theme.fit(PANEL_W_PREF, width);
        panelH = Theme.fit(PANEL_BASE_H + (showMonitor ? MONITOR_H : 0), height);
        panelX = (width  - panelW) / 2;
        panelY = (height - panelH) / 2;

        int gridW = Math.min(GRID_W, panelW - 24);
        int colW  = (gridW - COL_GAP) / 2;
        int gridX = (width - gridW) / 2;

        int titleY, tabsY, contentY, row1Y, row2Y;
        // One centred block: title, tabs, options, buttons, with a rule above and below it.
        //
        // The buttons used to be pinned to the bottom of the SCREEN while the options sat at
        // the top, which is what vanilla does — but vanilla fills the space between with a
        // scrolling list, and this screen has five fixed options. On a tall window that left
        // a several-hundred-pixel void in the middle and the screen read as broken. Every
        // other screen in the mod is a centred block, so this one is too; the two rules give
        // it the header/footer framing without pretending there is a list to fill.
        // The monitor is part of the block, not something that squeezes in afterwards.
        //
        // blockH never budgeted for it, so the buttons sat directly under the last option
        // row and the clearance the gate measures — (row1Y - 6) - (gridBottom + 6) — was
        // exactly 0 against a required 46. The Live Monitor could therefore NEVER draw: the
        // toggle turned on, held the microphone open, and showed "needs a taller window" on
        // a 4K display. Budgeting it here moves the buttons down and makes the gate mean
        // what it says.
        // The block without the monitor. This part always has to fit.
        // One rhythm for the whole screen: every row is a 20px control on a 25px pitch, so every
        // gap between rows is 5px - including the one under the tab bar, which used GAP_MD and
        // was therefore double the size of the gaps between the options underneath it.
        int baseH = 22                                   // title + the rule under it
                  + GRID_ROW                             // tab row, on the same pitch
                  + gridRows() * GRID_ROW
                  + 12 + GRID_ROW + 20;                  // the two button rows
        // ...and the monitor takes whatever room is left over, rather than demanding all of it.
        //
        // Three versions of this were wrong in three different ways. It first drew a fixed 156px
        // with no reservation, straight over the buttons. Reserving the 156 fixed that but let
        // the block outgrow the window and push Cancel / Done off the bottom. Dropping it
        // whenever 156 did not fit then meant a 427x240 window — an ordinary 854x480 at GUI
        // scale 2 — refused to show the monitor at all and just said "needs a taller window",
        // which is a strange thing for a screen to say about a panel it could perfectly well
        // have drawn smaller.
        //
        // So it scales. It uses up to MONITOR_H, never more than the space that actually exists,
        // and only declines when what is left cannot even hold a header and a couple of rows.
        int monitorRoom  = (height - 16) - baseH;
        int monitorBlock = 0;
        if (showMonitor && monitorRoom >= MONITOR_MIN) {
            monitorBlock = Math.min(MONITOR_H, monitorRoom);
        }
        int blockH = baseH + monitorBlock;
        int blockTop = Math.max(8, (height - blockH) / 2);

        titleY   = blockTop;
        headerY  = blockTop + 14;                        // rule under the title
        tabsY    = headerY + 8;
        contentY = tabsY + GRID_ROW;                     // 20px tab + the same 5px gap as the rows
        monitorReserved = monitorBlock > 0;
        row1Y    = contentY + gridRows() * GRID_ROW + monitorBlock + 12;
        row2Y    = row1Y + GRID_ROW;
        footerY  = row1Y - 8;                            // rule above the buttons

        // --- Title ---
        StringWidget titleW = new StringWidget(0, titleY, width, 9, title, font);
        titleW.alignCenter();
        // On a panelless screen the backdrop is the blurred world, so the title has to be white
        // and shadowed like vanilla's; on a container panel it is the panel's dark text tone.
        titleW.setColor(0xFFFFFF);   // white over the blurred world, like vanilla's
        addRenderableWidget(titleW);

        // --- Tab bar ---
        // Tabs sit on the SAME column metric as the options below them.
        //
        // They used to split gridW in half with no gutter, so the seam between the two tabs fell
        // 5px left of the gap between the two option columns and neither tab edge lined up with
        // the buttons under it. Nothing was broken and it looked wrong, which on a screen this
        // sparse is the same thing: with only eight controls on screen, a five-pixel disagreement
        // is the most visible feature of the layout. One metric, one alignment.
        tabRowY = tabsY; tabRowX = gridX; tabRowW = gridW; tabColW = colW;
        addRenderableWidget(new TabButton(gridX, tabsY, colW, TAB_H,
            "Recognition", Tab.RECOGNITION));
        addRenderableWidget(new TabButton(gridX + colW + COL_GAP, tabsY, colW, TAB_H,
            "HUD", Tab.HUD));

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
        // One source of truth: the monitor draws exactly when space was reserved for it. The
        // sizing above already refused anything under MONITOR_MIN, so re-deriving a second
        // opinion here from row positions is how the two halves disagreed before.
        monitorFits = !showMonitor || monitorReserved;
        // The hold goes HERE, after monitorFits is known — asking for it earlier read the value
        // left over from the previous init(). Only hold the microphone open when the monitor can
        // actually be seen: holding it for a block that refuses to draw is the worst of both,
        // the device open and nothing shown.
        VoiceController.setDiagnosticCapture("livemonitor", showMonitor && monitorFits);

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

    /** Pitch of one grid row: a 20px control plus vanilla's 5px gap. Vanilla's own options rows
     *  are 25px apart, and matching it is the difference between rows that breathe and rows that
     *  look stuck together. */
    private static final int GRID_ROW = 25;

    /** Header / footer rule. Delegates so every screen draws the identical line. */
    private void separator(GuiGraphics g, int y) {
        Theme.rule(g, 0, y, width);
    }

    /** Left edge of the monitor block: the grid's own left edge under either layout. */
    private int monitorX() {
        int gridW = Math.min(GRID_W, panelW - 24);
        return (width - gridW) / 2;
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
    /** Tab row geometry, kept so render() can draw the selection rule under it. */
    private int tabRowY, tabRowX, tabRowW, tabColW;

    /** Place a control at grid slot {@code i}, left column for even, right for odd.
     *
     *  <p>COL_GAP, not a hardcoded 4. This was the last place still using its own gutter: the
     *  tabs and both button rows moved onto the shared 10px metric, so the right-hand OPTION
     *  column ended up 6px left of the tab above it and of the buttons below it. One constant,
     *  or the columns drift again the next time one of them is touched. */
    private <T extends net.minecraft.client.gui.components.AbstractWidget> T slot(
            T w, int i, int gridX, int colW, int y) {
        w.setX(gridX + (i % 2) * (colW + COL_GAP));
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
        // The two separator lines vanilla puts under its header and above its footer. They
        // are what stop an options screen from being controls floating on scenery: the eye
        // reads a header band, a content band and a footer band instead of one soup. Drawn
        // rather than blitted because the sprites for them only exist from 1.20.5 on, and
        // this has to look the same on 1.20.1.
        separator(g, headerY);
        separator(g, footerY);

        super.render(g, mouseX, mouseY, partial);

        // Tabs, drawn the way tabs have always been drawn: the selected one is joined to the
        // content, the other one sits behind a rule.
        //
        // Two earlier attempts read wrong. Setting the current tab inactive used vanilla's
        // DISABLED sprite, so it looked broken rather than selected; making both plain buttons
        // then made the row indistinguishable from the options below it, and a bright underline
        // on its own just looked like a stray line. What actually says "tab" is the CONTENT
        // EDGE: a rule runs under the row, and it BREAKS under the tab you are on, so that tab
        // and the panel below it are visibly one surface. The other tab is dimmed to sit behind.
        int ruleY  = tabRowY + TAB_H;
        int leftX  = tabRowX;
        int rightX = tabRowX + tabColW + COL_GAP;
        boolean recogOn = currentTab == Tab.RECOGNITION;
        int unselX = recogOn ? rightX : leftX;

        // The content edge, everywhere except under the selected tab.
        g.fill(unselX, ruleY, unselX + tabColW, ruleY + 1, 0x70FFFFFF);
        g.fill(tabRowX + tabColW, ruleY, rightX, ruleY + 1, 0x70FFFFFF);
        // Push the unselected tab back.
        g.fill(unselX, tabRowY, unselX + tabColW, ruleY, 0x55000000);

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
                // At gridBottom, not monitorY(): monitorY() is the top of a block that does not
                // fit, which on a short window is under the buttons.
                Theme.text(g, font, "Live Monitor needs a taller window",
                    monitorX(), gridTop + gridRows() * GRID_ROW + 6, Theme.C_WARN);
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
        // Height from the space init() actually set aside, measured from BELOW the header.
        //
        // This subtracted the header's 11px after computing mh rather than before, so the well
        // ran 7px past its own limit and clipped the top of the button row. The limit derives
        // from the button row, so honouring it exactly is the whole point.
        final int HEADER_LINE = 11;
        int mh = Math.max(0, monitorBottomLimit() - (my + HEADER_LINE) - 4);

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
        my += HEADER_LINE;
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
            // Both tabs stay ACTIVE.
            //
            // The current tab used to be set inactive, which draws it with vanilla's DISABLED
            // sprite: darker, with grey text. That is the game's way of saying "you cannot use
            // this", not "you are here" — so the tab bar read as one broken button beside one
            // working one, rather than as a switch between two pages. Selection is shown by the
            // marker in renderWidget instead, and pressing the tab you are already on is simply
            // a no-op.
            this.active = true;
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
