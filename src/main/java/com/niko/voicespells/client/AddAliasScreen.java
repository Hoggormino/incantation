package com.niko.voicespells.client;

import com.niko.voicespells.VoiceSpellsConfig;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Tiny modal for adding a {@code customPhrases} entry for a specific spell id.
 *
 * The toml-based config flow ({@code abyss blast=traveloptics:abyssal_blast}) works but is
 * intimidating for users who just want to teach the recognizer a word it can pronounce. This
 * screen exposes the same write path through a single text field — the spell id is already
 * locked in by whichever row was clicked in the parent {@link VoiceSpellsSpellListScreen}.
 *
 * Saving appends {@code "phrase=spellId"} to the client config list and triggers
 * {@link VoiceController#onConfigChanged()} so the grammar rebuilds immediately.
 */
public final class AddAliasScreen extends Screen {

    // Preferred dimensions — clamped at init() time so the panel stays inside the screen
    // at every Minecraft GUI Scale. Layout math uses the runtime {@code panelW} / {@code panelH}
    // fields, not these constants.
    private static final int PANEL_W_PREF = 340;
    private static final int PANEL_H_PREF = 252;
    private static final int MAX_ROWS = 5;

    private final Screen parent;
    private final String spellId;
    /** Pre-fill text for the EditBox — used by the alias-suggestion path so the player
     *  doesn't have to retype the phrase they just said. Null = empty box. */
    private final String prefillAlias;

    private EditBox phraseBox;
    private StringWidget infoLabel;

    /** Top-left of the runtime panel + clamped dimensions; recomputed every {@link #init()}. */
    private int px, py, panelW, panelH;

    public AddAliasScreen(Screen parent, String spellId) {
        this(parent, spellId, null);
    }

    public AddAliasScreen(Screen parent, String spellId, String prefillAlias) {
        super(Component.literal("Add Alias"));
        this.parent = parent;
        this.spellId = spellId;
        this.prefillAlias = prefillAlias;
    }

    @Override
    protected void init() {
        // Clamp to fit the current screen so large GUI Scale settings don't push buttons off
        // the bottom or sides. The preferred dimensions still apply when there's enough room.
        panelW = Theme.fit(PANEL_W_PREF, width);
        panelH = Theme.fit(PANEL_H_PREF, height);
        px = (width - panelW) / 2;
        py = (height - panelH) / 2;

        // Centered title in the gradient header band.
        StringWidget titleW = new StringWidget(px, py + (Theme.HEADER_H - 9) / 2,
            panelW, 9, title, font);
        titleW.alignCenter();
        titleW.setColor(Theme.C_TEXT);
        addRenderableWidget(titleW);

        int y = py + Theme.HEADER_H + Theme.GAP_MD;

        // "For: <spell_id>" label so the user knows what they're aliasing.
        StringWidget forLabel = new StringWidget(px + Theme.PAD, y,
            panelW - Theme.PAD * 2, 9,
            Component.literal("For:  " + spellId), font);
        forLabel.alignLeft();
        forLabel.setColor(Theme.C_MUTED);
        addRenderableWidget(forLabel);
        y += 14;

        // Alias text field.
        phraseBox = new EditBox(font, px + Theme.PAD, y,
            panelW - Theme.PAD * 2, 20, Component.literal("Alias phrase"));
        phraseBox.setHint(Component.literal("e.g. abyss blast"));
        phraseBox.setMaxLength(80);
        if (prefillAlias != null && !prefillAlias.isBlank()) {
            phraseBox.setValue(prefillAlias);
        }
        addRenderableWidget(phraseBox);
        setInitialFocus(phraseBox);
        y += 26;

        // Inline info / error line. Starts empty; populated by validation in save().
        infoLabel = new StringWidget(px + Theme.PAD, y,
            panelW - Theme.PAD * 2, 9, Component.empty(), font);
        infoLabel.alignLeft();
        infoLabel.setColor(Theme.C_FAINT);
        addRenderableWidget(infoLabel);
        y += 16;

        // Section header for existing aliases.
        StringWidget sectionLabel = new StringWidget(px + Theme.PAD, y,
            panelW - Theme.PAD * 2, 9, Component.literal("EXISTING ALIASES"), font);
        sectionLabel.alignLeft();
        sectionLabel.setColor(Theme.C_MUTED);
        addRenderableWidget(sectionLabel);
        y += 12;

        // List existing alias rows from BOTH customPhrases and incantations that point at this
        // spell. Each row has a phrase label + an X button that removes it.
        List<String> aliasRows = collectExistingAliases();
        if (aliasRows.isEmpty()) {
            StringWidget emptyLabel = new StringWidget(px + Theme.PAD, y,
                panelW - Theme.PAD * 2, 9,
                Component.literal("(none — add one above)"), font);
            emptyLabel.alignLeft();
            emptyLabel.setColor(Theme.C_FAINT);
            addRenderableWidget(emptyLabel);
        } else {
            int rowH = 16;
            // Rows are dropped to make room for the overflow line, rather than the overflow line
            // being clamped on top of the last row. MAX_ROWS is a fixed five but the panel is
            // clamped to the window, so at 240 logical height the clamp below pulled
            // "...and N more" up into the fifth alias and into the footer rule - the one line
            // that tells you entries are hidden was the hardest one to read.
            int roomBottom = py + panelH - 34 - 4;      // above the footer rule
            // The 12px for the overflow line is reserved unconditionally: dropping a row can
            // itself be what creates the overflow, so deciding the reservation from the
            // untruncated count would leave that case with nowhere to put the line.
            int fits = Math.max(1, (roomBottom - y - 12) / rowH);
            int shown = Math.min(Math.min(MAX_ROWS, fits), aliasRows.size());
            for (int i = 0; i < shown; i++) {
                String entry = aliasRows.get(i);
                String phrase = entry.substring(0, entry.indexOf('='));
                // Phrase label on the left.
                StringWidget row = new StringWidget(px + Theme.PAD, y + 4,
                    panelW - Theme.PAD * 2 - 24, 9,
                    Component.literal("•  " + phrase), font);
                row.alignLeft();
                row.setColor(Theme.C_TEXT);
                addRenderableWidget(row);
                // Tiny X button on the right.
                addRenderableWidget(NeonButton.of(px + panelW - Theme.PAD - 16, y, 16, rowH - 2,
                    Component.literal("×"), b -> removeAlias(entry)));
                y += rowH;
            }
            if (aliasRows.size() > shown) {
                // The row count above already leaves room for this line; the clamp stays as a
                // floor for the degenerate case where even one row does not fit.
                int moreY = Math.min(y, py + panelH - 30 - 9);
                StringWidget moreLabel = new StringWidget(px + Theme.PAD, moreY,
                    panelW - Theme.PAD * 2, 9,
                    Component.literal("...and " + (aliasRows.size() - shown) + " more (edit toml)"),
                    font);
                moreLabel.alignLeft();
                moreLabel.setColor(Theme.C_MUTED);
                addRenderableWidget(moreLabel);
            }
        }

        int btnY = py + panelH - 28;
        int btnW = 90;
        addRenderableWidget(NeonButton.of(px + Theme.PAD, btnY, btnW, 20,
            CommonComponents.GUI_CANCEL, b -> onClose()));
        addRenderableWidget(NeonButton.of(px + panelW - Theme.PAD - btnW, btnY, btnW, 20,
            Component.literal("Save"), b -> save()));
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // Enter saves; Esc falls through to onClose via the default behaviour.
        if (keyCode == 257 || keyCode == 335) { // GLFW_KEY_ENTER / GLFW_KEY_KP_ENTER
            save();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    /** Walks both alias lists (customPhrases + incantations) and returns the raw entries
     *  whose right-hand-side equals this screen's spell id. */
    private List<String> collectExistingAliases() {
        List<String> out = new ArrayList<>();
        for (String e : VoiceSpellsConfig.CLIENT.customPhrases.get()) {
            if (matchesSpell(e)) out.add(e);
        }
        for (String e : VoiceSpellsConfig.CLIENT.incantations.get()) {
            if (matchesSpell(e)) out.add(e);
        }
        return out;
    }

    private boolean matchesSpell(String entry) {
        if (entry == null) return false;
        int eq = entry.indexOf('=');
        if (eq <= 0 || eq >= entry.length() - 1) return false;
        return spellId.equals(entry.substring(eq + 1).trim());
    }

    /** Remove this exact entry from whichever list contains it, refresh, rebuild widgets. */
    private void removeAlias(String entry) {
        boolean changed = removeFromList(VoiceSpellsConfig.CLIENT.customPhrases, entry);
        if (!changed) changed = removeFromList(VoiceSpellsConfig.CLIENT.incantations, entry);
        if (changed) {
            // Persist, exactly as the add path does. set() does not reach the disk on NeoForge,
            // so without this the row vanished from the screen and came back on the next launch.
            // The add path got this when config persistence was fixed; removal was missed, which
            // is the more annoying half — an alias you deliberately deleted reappearing.
            VoiceSpellsConfig.saveToDisk();
            VoiceSpellsConfig.refreshCache();
            VoiceController.onConfigChanged();
            rebuildWidgets();
        }
    }

    @SuppressWarnings("unchecked")
//? if forge {
/*    private boolean removeFromList(net.minecraftforge.common.ForgeConfigSpec.ConfigValue<List<? extends String>> spec,
*///?} else {
    private boolean removeFromList(net.neoforged.neoforge.common.ModConfigSpec.ConfigValue<List<? extends String>> spec,
//?}
                                    String entry) {
        List<String> current = (List<String>) (List<?>) spec.get();
        if (!current.contains(entry)) return false;
        List<String> updated = new ArrayList<>(current);
        updated.remove(entry);
//? if forge {
/*        ((net.minecraftforge.common.ForgeConfigSpec.ConfigValue) spec).set(updated);
*///?} else {
        ((net.neoforged.neoforge.common.ModConfigSpec.ConfigValue) spec).set(updated);
//?}
        return true;
    }

    private void save() {
        String raw = phraseBox.getValue();
        String phrase = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        if (phrase.isEmpty()) {
            infoLabel.setMessage(Component.literal("Alias can't be empty"));
            infoLabel.setColor(Theme.C_DANGER);
            return;
        }
        if (phrase.indexOf('=') >= 0) {
            infoLabel.setMessage(Component.literal("'=' is not allowed in an alias"));
            infoLabel.setColor(Theme.C_DANGER);
            return;
        }
        // Append to customPhrases, write back. defineListAllowEmpty stores List<? extends String>,
        // and the spec's set() wants a concrete List — copy into ArrayList to satisfy both.
        @SuppressWarnings("unchecked")
        List<String> current = (List<String>) (List<?>) VoiceSpellsConfig.CLIENT.customPhrases.get();
        List<String> updated = new ArrayList<>(current);
        String entry = phrase + "=" + spellId;
        if (!updated.contains(entry)) updated.add(entry);
        VoiceSpellsConfig.CLIENT.customPhrases.set(updated);
        // Without this the new alias is lost on restart - see VoiceSpellsConfig.saveToDisk().
        VoiceSpellsConfig.saveToDisk();
        VoiceSpellsConfig.refreshCache();
        VoiceController.onConfigChanged();
        onClose();
    }

    @Override
    public void onClose() {
        if (minecraft != null) minecraft.setScreen(parent);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        // Vanilla backdrop first (blurred world in-game, dirt on the title screen, and it
        // honours the player's Menu Background Blur setting), then our own dim on top so
        // the panel still reads. Painting only a flat scrim, as this did before, opted out
        // of all of that and was a large part of why the screens felt foreign.
        Theme.ground(this, g, mouseX, mouseY, partial);
        super.render(g, mouseX, mouseY, partial);
    }
}
