package com.niko.voicespells.client;

import com.niko.voicespells.VoiceSpellsConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;


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

    // Vanilla's own option-button metrics: 150 wide, 20 tall, 4 apart, which is what every
    // two-column options screen in the game uses.
    private static final int COL_W         = 150;
    // 10, which is what vanilla uses. Its options list puts two 150-wide buttons at centre-155
    // and centre+5, so the gutter between them is exactly 10px; at 4 the pair read as one wide
    // control split by a hairline rather than as two separate options.
    private static final int COL_GAP       = 10;
    private static final int GRID_W        = COL_W * 2 + COL_GAP;

    private static final int PANEL_W_PREF  = GRID_W + 24;   // grid + a 12px margin each side
    /** Tab height. The SAME as every other control on the screen: the tab row is a row like any
     *  other, and making it 2px shorter than the options meant its gaps could never line up with
     *  theirs no matter what padding was chosen. */
    private static final int TAB_H         = 20;

    /** Three pages, because the mod has 33 settings and the screen was showing nine of them.
     *  The rest were toml-only, which is also why the screen looked empty: it was empty. */
    private enum Tab { RECOGNITION, HUD, BEHAVIOUR }

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
    // Settings that existed only in the toml until now.
    private VoiceSpellsConfig.GatingMode workGating;
    private int     workMinConfPct;     // 0..100 in UI, 0..1 in config
    private boolean workRequireSneak;
    private int     workEchoLockout;
    private boolean workShowMisses;
    private boolean workAlwaysShowHeard;
    private boolean workCastVignette;
    private boolean workStreamer;
    private boolean workCombatOnly;
    private boolean workPauseAfk;
    private int     workAfkSeconds;
    private boolean workHandsFree;
    private int     workCastQueue;
    private boolean workSuspendUnfocused;
    private boolean workCooldownChip;
    private int     workCooldownChipY;

    private boolean initializedOnce = false;
    private Tab currentTab = Tab.RECOGNITION;

    /** Width the grid is allowed to occupy, clamped so a large GUI Scale cannot cut it off.
     *  The screen fills the window; only this one measurement is still panel-like. */
    private int panelW;
    /** Y of the header and footer separator lines in panelless mode. */
    private int headerY, footerY;
    /** The panel under the tab row, computed in {@link #init()} and drawn beneath the controls. */
    private int contentWellX, contentWellY, contentWellW, contentWellH;

    public VoiceSpellsConfigScreen(Screen parent) {
        super(Component.translatable("voicespells.config.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        VoiceSpellsConfig.Client c = VoiceSpellsConfig.CLIENT;
        // Reload from the config whenever the values on disk have moved on without us.
        //
        // initializedOnce exists so that switching tabs (which re-runs init) does not throw away
        // edits in progress. But it also meant that importing a profile from More... - which
        // writes the config directly - left this screen holding the values from before the
        // import, and pressing Done then wrote those stale values straight back over it. The
        // import appeared to work and silently undid itself. Comparing a cheap fingerprint of the
        // config catches any outside change without disturbing in-progress edits.
        long stamp = configStamp(c);
        if (initializedOnce && stamp != lastConfigStamp) initializedOnce = false;
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
            workGating          = c.gatingMode.get();
            workMinConfPct      = (int) Math.round(c.minConfidence.get() * 100);
            workRequireSneak    = c.requireSneak.get();
            workEchoLockout     = c.echoLockoutMillis.get();
            workShowMisses      = c.showMisses.get();
            workAlwaysShowHeard = c.alwaysShowHeard.get();
            workCastVignette    = c.castVignette.get();
            workStreamer        = c.streamerMode.get();
            workCombatOnly      = c.combatOnly.get();
            workPauseAfk        = c.pauseWhenAfk.get();
            workAfkSeconds      = c.afkSeconds.get();
            workHandsFree       = c.handsFreeConfirm.get();
            workCastQueue       = c.castQueueSize.get();
            workSuspendUnfocused = c.suspendWhenUnfocused.get();
            workCooldownChip    = c.castCooldownChip.get();
            workCooldownChipY   = c.castCooldownChipY.get();
            // Remembered so Cancel can undo the live theme preview — see onClose().
            initializedOnce = true;
            lastConfigStamp = configStamp(c);
        }
        panelW = Theme.fit(PANEL_W_PREF, width);

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
        // Header at the top, footer at the bottom, options between: vanilla's own options
        // layout, and the reason it works at any window size.
        //
        // A centred block was the alternative, and it is what this used to be — but a fixed block
        // in a large window is an island with nothing around it, which is exactly the complaint.
        // Pinning the two ends means the screen occupies the window it is given, and the options
        // hang under the header the way a list would. The Live Monitor, which used to fight these
        // controls for the middle of the screen, has its own screen now.
        titleY   = 14;
        headerY  = 32;                                   // rule under the header band
        tabsY    = headerY + 10;
        contentY = tabsY + GRID_ROW;                     // 20px tab + the shared 5px gap
        row2Y    = height - 28;                          // Cancel / Done on the bottom edge
        row1Y    = row2Y - GRID_ROW;
        footerY  = row1Y - 10;                           // rule above the footer

        // --- Title ---
        StringWidget titleW = new StringWidget(0, titleY, width, 9, title, font);
        titleW.alignCenter();
        // On a panelless screen the backdrop is the blurred world, so the title has to be white
        // and shadowed like vanilla's; on a container panel it is the panel's dark text tone.
        titleW.setColor(Theme.C_TEXT);   // white over the blurred world, like vanilla's
        addRenderableWidget(titleW);

        // --- Tab bar ---
        // Tabs sit on the SAME column metric as the options below them.
        //
        // They used to split gridW in half with no gutter, so the seam between the two tabs fell
        // 5px left of the gap between the two option columns and neither tab edge lined up with
        // the buttons under it. Nothing was broken and it looked wrong, which on a screen this
        // sparse is the same thing: with only eight controls on screen, a five-pixel disagreement
        // is the most visible feature of the layout. One metric, one alignment.
        // Three tabs share the grid width, on the same gutter as everything else.
        // The last tab absorbs the division remainder, exactly as the "More..." button does in
        // the footer. Three equal thirds of 310 leave 2px over, so the right edge of "Behaviour"
        // stopped 2px short of the grid it is supposed to span - visible as a notch against the
        // option column below it, on a screen where the alignment IS the design.
        // Flush, no gutter. Vanilla packs its tabs edge to edge, and the sprite's nine-slice
        // border is bottom:0 so the row merges into the content below it. A 10px gap between
        // open-bottomed tabs reads as three torn strips rather than one bar.
        tabColW = gridW / 3;
        int lastTabX = gridX + tabColW * 2;
        int lastTabW = gridX + gridW - lastTabX;
        addRenderableWidget(new TabButton(gridX, tabsY, tabColW, TAB_H,
            Component.translatable("voicespells.config.tab_recognition"), Tab.RECOGNITION));
        addRenderableWidget(new TabButton(gridX + tabColW, tabsY, tabColW, TAB_H,
            Component.translatable("voicespells.config.tab_hud"), Tab.HUD));
        addRenderableWidget(new TabButton(lastTabX, tabsY, lastTabW, TAB_H,
            Component.translatable("voicespells.config.tab_behaviour"), Tab.BEHAVIOUR));

        // The panel the tabs sit on. Its top edge is the tab row's bottom edge, deliberately:
        // the sprite's nine-slice border is bottom:0 so a selected tab has an OPEN bottom and is
        // supposed to merge into the surface beneath it. There was no surface, so the whole
        // idiom was doing nothing - three tabs with open bottoms opening onto the backdrop, and
        // the options below them floating unframed while every other screen in the mod put its
        // content in a well.
        contentWellX = gridX;
        contentWellY = tabsY + TAB_H;
        contentWellW = gridW;
        contentWellH = footerY - contentWellY;

        // --- Tab content: a two-column option grid ---
        //
        // Three rows for five settings, three for six, on vanilla's own 150x20 option-button
        // metric. The old label-plus-control layout needed five and six rows, which is why it
        // had to compress rows on short windows and still collided with the buttons; halving the
        // row count made the whole compression mechanism unnecessary.
        rowPitch = gridPitch(contentY, footerY);
        placed = 0;

        switch (currentTab) {
            case RECOGNITION -> buildRecognitionTab(gridX, colW, contentY);
            case HUD         -> buildHudTab(gridX, colW, contentY);
            case BEHAVIOUR   -> buildBehaviourTab(gridX, colW, contentY);
        }
        // The layout is sized from optionCount(); if a tab quietly grows past it, say so in the
        // log instead of drawing the extra row through the buttons. This is the check that was
        // missing when Recognition went to nine.
        if (placed != optionCount()) {
            com.niko.voicespells.VoiceSpells.LOGGER.warn(
                "Config tab {} placed {} controls but the layout budgeted {} - the grid may "
                + "overflow. Update optionCount().", currentTab, placed, optionCount());
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
            avail - (thirdW + COL_GAP) * 2, 20, Component.translatable("voicespells.config.more"),
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
    /** Rows the current tab's options occupy, two per row.
     *
     *  Counted, not hardcoded. It was fixed at 3 for both tabs, which was right only while the
     *  HUD tab had six options; removing the menu-style and accent cycles left it with four, and
     *  a hardcoded 3 would have reserved a phantom row and reopened the empty-space problem the
     *  layout was just fixed for. */
    private int gridRows() {
        return (optionCount() + 1) / 2;
    }

    /** How many controls the current tab builds. Kept beside the builders so adding one to a
     *  tab and forgetting this cannot silently clip the last row. */
    private int optionCount() {
        // These must match what the build*Tab methods actually add. They did not: Recognition
        // built nine controls against an asserted eight, so the fifth row was drawn below the
        // footer rule and, at 720p on GUI scale 3, straight through the button bar. The
        // assertion is checked at runtime now (see init) rather than trusted.
        return switch (currentTab) {
            case RECOGNITION -> 8;
            case HUD         -> 10;
            case BEHAVIOUR   -> 7;
        };
    }

    /** Row pitch for the option grid: GRID_ROW when it fits, compressed when the window is too
     *  short for it. A fixed pitch is what let a grid run through the footer. */
    private int gridPitch(int contentTop, int footerTop) {
        int rows = gridRows();
        int room = footerTop - 6 - contentTop;
        if (rows <= 0 || room >= rows * GRID_ROW) return GRID_ROW;
        return Math.max(21, room / rows);
    }

    /** Fingerprint of the config as this screen last loaded it, so an outside write (a profile
     *  import, an external toml edit) is noticed rather than silently overwritten on Done. */
    private long lastConfigStamp;

    /** Cheap order-sensitive hash of every value this screen owns. */
    private static long configStamp(VoiceSpellsConfig.Client c) {
        long h = 17;
        Object[] vals = {
            c.debugMonitor.get(), c.fuzzyMaxDistance.get(), c.substringMatch.get(),
            c.dedupMillis.get(), c.restrictToOwned.get(), c.hudCorner.get(),
            c.hudOffsetX.get(), c.hudOffsetY.get(), c.globalOpacity.get(),
            c.gatingMode.get(), c.minConfidence.get(), c.requireSneak.get(),
            c.echoLockoutMillis.get(), c.showMisses.get(), c.alwaysShowHeard.get(),
            c.castVignette.get(), c.streamerMode.get(), c.combatOnly.get(),
            c.pauseWhenAfk.get(), c.afkSeconds.get(), c.handsFreeConfirm.get(),
            c.castQueueSize.get(), c.suspendWhenUnfocused.get(),
            c.castCooldownChip.get(), c.castCooldownChipY.get(),
        };
        for (Object v : vals) h = h * 31 + (v == null ? 0 : v.hashCode());
        return h;
    }

    /** Pitch in use this init(), and how many controls the tab actually placed. */
    private int rowPitch = GRID_ROW;
    private int placed;

    /** Tab width. The rest of the row geometry died with the selection veil that read it. */
    private int tabColW;

    /** Place a control at grid slot {@code i}, left column for even, right for odd.
     *
     *  <p>COL_GAP, not a hardcoded 4. This was the last place still using its own gutter: the
     *  tabs and both button rows moved onto the shared 10px metric, so the right-hand OPTION
     *  column ended up 6px left of the tab above it and of the buttons below it. One constant,
     *  or the columns drift again the next time one of them is touched. */
    private <T extends AbstractWidget> T slot(
            T w, int i, int gridX, int colW, int y) {
        w.setX(gridX + (i % 2) * (colW + COL_GAP));
        w.setY(y + (i / 2) * rowPitch);
        placed = Math.max(placed, i + 1);   // what the tab REALLY built, for the runtime check
        return addRenderableWidget(w);
    }

    /** Hover tooltip, named by lang key so the whole help text is translatable.
     *
     *  <p>Takes a KEY, not the English sentence it used to take: every one of these strings is
     *  read by a player, so leaving them in Java put 25 paragraphs of copy out of a translator's
     *  reach. The key is resolved on every rebuild, which is when a language change re-inits the
     *  screen anyway. */
    private static void help(AbstractWidget w, String key) {
        w.setTooltip(net.minecraft.client.gui.components.Tooltip.create(
            Component.translatable(key)));
    }

    /** A lang key resolved to a plain String.
     *
     *  <p>{@link NeonToggle#named}, {@link NeonCycle#named} and {@link NeonSlider} all label
     *  themselves from a String rather than a Component, so a key cannot be handed to them
     *  directly. Resolving here keeps the option names translatable without changing any of
     *  those signatures. */
    private static String tr(String key) {
        return Component.translatable(key).getString();
    }

    /** Same, for the slider labels, which are built per value and so carry an argument. */
    private static String tr(String key, Object... args) {
        return Component.translatable(key, args).getString();
    }

    private void buildRecognitionTab(int gridX, int colW, int y) {
        int i = 0;
        help(slot(NeonToggle.named(0, 0, colW, 20, tr("voicespells.config.owned_only"),
                workRestrictToOwned, v -> workRestrictToOwned = v), i++, gridX, colW, y),
            "voicespells.config.owned_only_tip");

        help(slot(NeonToggle.named(0, 0, colW, 20, tr("voicespells.config.verbose_log"),
                workDebug, v -> workDebug = v), i++, gridX, colW, y),
            "voicespells.config.verbose_log_tip");

        help(slot(new NeonSlider(0, 0, colW, 20, workFuzzy, 0, 2,
                this::fuzzyLabel, v -> workFuzzy = v), i++, gridX, colW, y),
            "voicespells.config.fuzzy_tip");

        help(slot(NeonToggle.named(0, 0, colW, 20, tr("voicespells.config.substring"),
                workSubstring, v -> workSubstring = v), i++, gridX, colW, y),
            "voicespells.config.substring_tip");

        // Same stretch-to-fit as the HUD offsets below: the toml allows up to 5000 and this
        // slider stopped at 3000, so opening the screen and pressing Done rewrote a 4000ms
        // gap the player had set by hand down to 3000, silently.
        help(slot(new NeonSlider(0, 0, colW, 20, workDedup, 0, Math.max(3000, workDedup),
                v -> tr("voicespells.config.slider_repeat_gap", v), v -> workDedup = v), i++, gridX, colW, y),
            "voicespells.config.dedup_tip");

        help(slot(NeonCycle.named(0, 0, colW, 20, tr("voicespells.config.listen"),
                VoiceSpellsConfig.GatingMode.values(),
                workGating, VoiceSpellsConfigScreen::prettyGating, v -> workGating = v),
                i++, gridX, colW, y),
            "voicespells.config.listen_tip");

        help(slot(new NeonSlider(0, 0, colW, 20, workMinConfPct, 0, 100,
                v -> tr("voicespells.config.slider_confidence", v), v -> workMinConfPct = v), i++, gridX, colW, y),
            "voicespells.config.confidence_tip");

        help(slot(new NeonSlider(0, 0, colW, 20, workEchoLockout, 0, 10000,
                v -> tr("voicespells.config.slider_echo_guard", v), v -> workEchoLockout = v), i++, gridX, colW, y),
            "voicespells.config.echo_guard_tip");
    }

    private static String prettyGating(VoiceSpellsConfig.GatingMode m) {
        return switch (m) {
            case ALWAYS_ON          -> tr("voicespells.config.gating_always_on");
            case HOLD_KEY           -> tr("voicespells.config.gating_hold_key");
            case HOLD_ITEM          -> tr("voicespells.config.gating_hold_item");
            case HOLD_KEY_AND_ITEM  -> tr("voicespells.config.gating_key_and_item");
        };
    }

    private void buildHudTab(int gridX, int colW, int y) {
        int i = 0;
        help(slot(NeonCycle.named(0, 0, colW, 20, tr("voicespells.config.corner"),
                VoiceSpellsConfig.Corner.values(),
                workCorner, VoiceSpellsConfigScreen::prettyCorner, v -> workCorner = v),
                i++, gridX, colW, y),
            "voicespells.config.corner_tip");

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
                v -> tr("voicespells.config.slider_offset_x", v), v -> workOffsetX = v), i++, gridX, colW, y),
            "voicespells.config.offset_x_tip");

        help(slot(new NeonSlider(0, 0, colW, 20, workOffsetY, 0, offsetMax,
                v -> tr("voicespells.config.slider_offset_y", v), v -> workOffsetY = v), i++, gridX, colW, y),
            "voicespells.config.offset_y_tip");

        help(slot(new NeonSlider(0, 0, colW, 20, workOpacityPct, 0, 100,
                v -> tr("voicespells.config.slider_opacity", v), v -> workOpacityPct = v), i++, gridX, colW, y),
            "voicespells.config.opacity_tip");

        help(slot(NeonToggle.named(0, 0, colW, 20, tr("voicespells.config.show_misses"),
                workShowMisses, v -> workShowMisses = v), i++, gridX, colW, y),
            "voicespells.config.show_misses_tip");

        help(slot(NeonToggle.named(0, 0, colW, 20, tr("voicespells.config.show_heard"),
                workAlwaysShowHeard, v -> workAlwaysShowHeard = v), i++, gridX, colW, y),
            "voicespells.config.show_heard_tip");

        help(slot(NeonToggle.named(0, 0, colW, 20, tr("voicespells.config.cast_vignette"),
                workCastVignette, v -> workCastVignette = v), i++, gridX, colW, y),
            "voicespells.config.cast_vignette_tip");

        help(slot(NeonToggle.named(0, 0, colW, 20, tr("voicespells.config.streamer_mode"),
                workStreamer, v -> workStreamer = v), i++, gridX, colW, y),
            "voicespells.config.streamer_mode_tip");

        help(slot(NeonToggle.named(0, 0, colW, 20, tr("voicespells.config.cooldown_chip"),
                workCooldownChip, v -> workCooldownChip = v), i++, gridX, colW, y),
            "voicespells.config.cooldown_chip_tip");

        // Same trick the HUD offsets use: the slider covers the useful range rather than the
        // config's full one, but stretches to contain a value already set beyond it, so a
        // hand-edited toml is not silently clamped the first time Done is pressed. Negative is
        // upward, and -120 reaches the middle of an ordinary window.
        int chipMin = Math.min(-120, workCooldownChipY);
        int chipMax = Math.max(120, workCooldownChipY);
        help(slot(new NeonSlider(0, 0, colW, 20, workCooldownChipY, chipMin, chipMax,
                v -> tr("voicespells.config.slider_chip_y", v), v -> workCooldownChipY = v), i++, gridX, colW, y),
            "voicespells.config.chip_y_tip");
    }

    private void buildBehaviourTab(int gridX, int colW, int y) {
        int i = 0;
        help(slot(NeonToggle.named(0, 0, colW, 20, tr("voicespells.config.combat_only"),
                workCombatOnly, v -> workCombatOnly = v), i++, gridX, colW, y),
            "voicespells.config.combat_only_tip");

        help(slot(NeonToggle.named(0, 0, colW, 20, tr("voicespells.config.pause_afk"),
                workPauseAfk, v -> workPauseAfk = v), i++, gridX, colW, y),
            "voicespells.config.pause_afk_tip");

        // The toml ceiling is 3600. A slider that reached it would move in 24-second steps,
        // so it stops at 10 minutes and stretches for anyone who set more than that.
        help(slot(new NeonSlider(0, 0, colW, 20, workAfkSeconds, 5, Math.max(600, workAfkSeconds),
                v -> tr("voicespells.config.slider_afk", v), v -> workAfkSeconds = v), i++, gridX, colW, y),
            "voicespells.config.afk_seconds_tip");

        help(slot(NeonToggle.named(0, 0, colW, 20, tr("voicespells.config.pause_unfocused"),
                workSuspendUnfocused, v -> workSuspendUnfocused = v), i++, gridX, colW, y),
            "voicespells.config.pause_unfocused_tip");

        help(slot(NeonToggle.named(0, 0, colW, 20, tr("voicespells.config.hands_free"),
                workHandsFree, v -> workHandsFree = v), i++, gridX, colW, y),
            "voicespells.config.hands_free_tip");

        help(slot(new NeonSlider(0, 0, colW, 20, workCastQueue, 1, 5,
                v -> tr("voicespells.config.slider_queue", v), v -> workCastQueue = v), i++, gridX, colW, y),
            "voicespells.config.queue_tip");


        help(slot(NeonToggle.named(0, 0, colW, 20, tr("voicespells.config.sneak_to_cast"),
                workRequireSneak, v -> workRequireSneak = v), i++, gridX, colW, y),
            "voicespells.config.sneak_to_cast_tip");

    }

    private static String prettyCorner(VoiceSpellsConfig.Corner c) {
        return switch (c) {
            case TOP_LEFT     -> tr("voicespells.config.corner_top_left");
            case TOP_RIGHT    -> tr("voicespells.config.corner_top_right");
            case BOTTOM_LEFT  -> tr("voicespells.config.corner_bottom_left");
            case BOTTOM_RIGHT -> tr("voicespells.config.corner_bottom_right");
        };
    }


    private String fuzzyLabel(int v) {
        return switch (v) {
            case 0  -> tr("voicespells.config.fuzzy_exact");
            case 1  -> tr("voicespells.config.fuzzy_one");
            default -> tr("voicespells.config.fuzzy_two");
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
                workGating       = VoiceSpellsConfig.GatingMode.HOLD_ITEM;
                workMinConfPct   = 55;
                workEchoLockout  = 1500;
            }
            case HUD -> {
                workCorner    = VoiceSpellsConfig.Corner.BOTTOM_LEFT;
                workOffsetX   = 5;
                workOffsetY   = 28;
                workOpacityPct = 100;
                workShowMisses      = false;   // matches the shipped default
                workAlwaysShowHeard = false;
                workCastVignette    = false;
                workStreamer        = false;
            }
            case BEHAVIOUR -> {
                workCombatOnly       = false;
                workPauseAfk         = false;
                workAfkSeconds       = 60;
                workSuspendUnfocused = true;
                workCooldownChip     = true;
                workCooldownChipY    = 0;
                workHandsFree        = false;
                workCastQueue        = 3;
                workRequireSneak     = false;
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
        // The settings that used to be toml-only.
        c.gatingMode.set(workGating);
        c.minConfidence.set(workMinConfPct / 100.0);
        c.requireSneak.set(workRequireSneak);
        c.echoLockoutMillis.set(workEchoLockout);
        c.showMisses.set(workShowMisses);
        c.alwaysShowHeard.set(workAlwaysShowHeard);
        c.castVignette.set(workCastVignette);
        c.streamerMode.set(workStreamer);
        c.combatOnly.set(workCombatOnly);
        c.pauseWhenAfk.set(workPauseAfk);
        c.afkSeconds.set(workAfkSeconds);
        c.handsFreeConfirm.set(workHandsFree);
        c.castQueueSize.set(workCastQueue);
        c.suspendWhenUnfocused.set(workSuspendUnfocused);
        c.castCooldownChip.set(workCooldownChip);
        c.castCooldownChipY.set(workCooldownChipY);
        // set() alone does not reach the disk on NeoForge - see VoiceSpellsConfig.saveToDisk().
        VoiceSpellsConfig.saveToDisk();
        // set() persists but the reload event is debounced — refresh cache now so the mic
        // thread and the HUD pick up new values immediately, and rebuild the recognizer
        // grammar live so customPhrase / index changes apply without a restart.
        VoiceSpellsConfig.refreshCache();
        VoiceController.onConfigChanged();
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
        Theme.ground(this, g, mouseX, mouseY, partial);
        // The dim went missing here when the dead panelled branch was deleted — it used to sit
        // inside it — so this was the one screen with no ground under its controls at all.
        // The two separator lines vanilla puts under its header and above its footer. They
        // are what stop an options screen from being controls floating on scenery: the eye
        // reads a header band, a content band and a footer band instead of one soup. Drawn
        // rather than blitted because the sprites for them only exist from 1.20.5 on, and
        // this has to look the same on 1.20.1.

        // Drawn before the widgets so the tab row overlaps its top edge and the controls sit on
        // its face, which is the order vanilla draws a tabbed screen in.
        if (contentWellH > 0) Theme.well(g, contentWellX, contentWellY, contentWellW, contentWellH);

        super.render(g, mouseX, mouseY, partial);

        // Nothing drawn over the tabs. They paint their own state from Minecraft's tab art -
        // see TabButton.renderWidget. This used to fill a black rectangle plus a 1px rule over
        // each unselected tab, i.e. hand-drawn chrome on top of a real vanilla Button.

        // No accent rule under the title and no divider under the tabs. Neither exists in any
        // vanilla screen: a container has its title sitting straight on the panel, and an
        // options screen has nothing but the title and the buttons. Both lines were decoration
        // holding the two halves of the screen apart, and removing them is what lets the panel
        // shrink to hug its content.
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

        TabButton(int x, int y, int w, int h, Component label, Tab tab) {
            super(x, y, w, h, label, b -> {}, Button.DEFAULT_NARRATION);
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

        /**
         * Minecraft's tab art, not a Button with something painted over it.
         *
         * <p>The label sits 3px lower on an unselected tab, which is vanilla's own offset in
         * TabButton.renderString - it is what makes an unselected tab read as pushed back.
         */
        @Override
        public void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            boolean sel = currentTab == tab;
            Theme.tab(g, getX(), getY(), width, height, sel, isHoveredOrFocused());
            int ty = getY() + (height - 8) / 2 + (sel ? 0 : 3) - 1;
            g.drawCenteredString(net.minecraft.client.Minecraft.getInstance().font, getMessage(),
                getX() + width / 2, ty, sel ? Theme.C_TEXT : Theme.C_MUTED);
        }
    }

}
