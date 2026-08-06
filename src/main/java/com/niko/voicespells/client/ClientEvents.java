package com.niko.voicespells.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.niko.voicespells.VoiceSpells;
import com.niko.voicespells.VoiceSpellsConfig;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
//? if forge {
/*import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.fml.ModLoadingContext;
*///?} else {
import net.minecraft.client.gui.LayeredDraw;
//?}
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
//? if forge {
/*import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.common.MinecraftForge;
*///?} else {
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.common.NeoForge;
//?}
import org.lwjgl.glfw.GLFW;

public final class ClientEvents {

    private static final KeyMapping TOGGLE_LISTENING = new KeyMapping(
        "key.voicespells.toggle_listening",
        KeyConflictContext.IN_GAME,
        InputConstants.Type.KEYSYM,
        GLFW.GLFW_KEY_V,
        "key.categories.voicespells"
    );

    private static final KeyMapping TOGGLE_HUD = new KeyMapping(
        "key.voicespells.toggle_hud",
        KeyConflictContext.IN_GAME,
        InputConstants.Type.KEYSYM,
        GLFW.GLFW_KEY_B,
        "key.categories.voicespells"
    );

    /** Press to accept the most recent alias suggestion — opens AddAliasScreen pre-filled
     *  with the heard phrase + the suggested spell id, so it's a single keystroke + Enter. */
    private static final KeyMapping ACCEPT_SUGGESTION = new KeyMapping(
        "key.voicespells.accept_suggestion",
        KeyConflictContext.IN_GAME,
        InputConstants.Type.KEYSYM,
        GLFW.GLFW_KEY_Y,
        "key.categories.voicespells"
    );

    /** Quick-recast the most recently voice-cast spell. Lets the player chain a repeat cast
     *  without speaking the phrase a second time — useful for fast-cast spells where speech
     *  recognition latency would otherwise be a bottleneck. */
    private static final KeyMapping QUICK_RECAST = new KeyMapping(
        "key.voicespells.quick_recast",
        KeyConflictContext.IN_GAME,
        InputConstants.Type.KEYSYM,
        GLFW.GLFW_KEY_RIGHT_BRACKET,
        "key.categories.voicespells"
    );

    /** Push-to-talk, used only when gatingMode = HOLD_KEY. Held rather than toggled, so it is
     *  read directly from the key state each frame instead of via consumeClick(). */
    private static final KeyMapping PUSH_TO_TALK = new KeyMapping(
        "key.voicespells.push_to_talk",
        KeyConflictContext.IN_GAME,
        InputConstants.Type.KEYSYM,
        GLFW.GLFW_KEY_GRAVE_ACCENT,
        "key.categories.voicespells"
    );

    /** True while the push-to-talk key is physically down. */
    public static boolean isPushToTalkDown() {
        return PUSH_TO_TALK.isDown();
    }

    private ClientEvents() {}

//? if forge {
/*
    /^*
     * Client-side wiring, called from the {@link VoiceSpells} constructor under a dist guard.
     *
     * <p>The config-screen extension point is registered <i>here</i> rather than in the mod
     * constructor, and that placement is load-bearing rather than stylistic.
     * {@code ConfigScreenHandler.ConfigScreenFactory} wraps a {@code BiFunction<Minecraft, Screen,
     * Screen>}, so a lambda for it desugars into a synthetic method whose descriptor names
     * {@code Screen}. If that lambda sits in the mod constructor, Forge's dist-aware class loader
     * tries to resolve {@code Screen} while the constructor is being prepared — before any
     * {@code FMLEnvironment.dist} check inside the body can run — and a dedicated server dies with
     * "Attempted to load class net/minecraft/client/gui/screens/Screen for invalid dist
     * DEDICATED_SERVER". A runtime {@code if} cannot guard a class-loading event that happens at
     * method preparation time; only moving the reference into a class the server never touches
     * can. This whole class is client-only and is reached from exactly one dist-guarded static
     * call, so it is safe here.
     *
     * <p>Unlike NeoForge there is no {@code ModContainer} parameter — Forge exposes it through the
     * {@link ModLoadingContext} singleton instead.
     ^/
    public static void bootstrap(IEventBus modBus) {
*///?} else {
    /** Called from {@link VoiceSpells} constructor on the client. */
    /**
     * Client-side wiring. Takes the {@link ModContainer} so the config-screen extension point is
     * registered <i>here</i> rather than in the mod constructor.
     *
     * <p>That placement is load-bearing, not stylistic. {@code IConfigScreenFactory} is a
     * functional interface whose method takes a {@code Screen}, so a lambda implementing it
     * desugars into a synthetic method whose descriptor names {@code Screen}. If that lambda sits
     * in the mod constructor, NeoForge's dist-aware class loader tries to resolve {@code Screen}
     * while the constructor is being prepared — before any {@code FMLEnvironment.dist} check
     * inside the body can run — and a dedicated server dies with "Attempted to load class
     * net/minecraft/client/gui/screens/Screen for invalid dist DEDICATED_SERVER". A runtime
     * {@code if} cannot guard a class-loading event that happens at method preparation time; only
     * moving the reference into a class the server never touches can. This whole class is
     * client-only and is reached from exactly one dist-guarded static call, so it is safe here.
     */
    public static void bootstrap(IEventBus modBus, ModContainer container) {
//?}
        // "Config" button in Mods → Incantation opens our screen.
//? if forge {
/*        ModLoadingContext.get().registerExtensionPoint(
            ConfigScreenHandler.ConfigScreenFactory.class,
            () -> new ConfigScreenHandler.ConfigScreenFactory(
                (mc, parent) -> new VoiceSpellsConfigScreen(parent)));
*///?} else {
        container.registerExtensionPoint(IConfigScreenFactory.class,
            (c, parent) -> new VoiceSpellsConfigScreen(parent));
//?}

        modBus.addListener(ClientEvents::onClientSetup);
//? if forge {
/*        modBus.addListener(ClientEvents::onRegisterGuiOverlays);
*///?} else {
        modBus.addListener(ClientEvents::onRegisterGuiLayers);
//?}
        modBus.addListener(ClientEvents::onRegisterKeys);
//? if forge {
/*        MinecraftForge.EVENT_BUS.addListener(ClientEvents::onClientTickPost);
        MinecraftForge.EVENT_BUS.addListener(ClientEvents::onClientChat);
        MinecraftForge.EVENT_BUS.addListener(VoiceSpellsClientCommands::onRegister);
*///?} else {
        NeoForge.EVENT_BUS.addListener(ClientEvents::onClientTickPost);
        NeoForge.EVENT_BUS.addListener(ClientEvents::onClientChat);
        NeoForge.EVENT_BUS.addListener(VoiceSpellsClientCommands::onRegister);
//?}
        // Live-apply external edits to voicespells-client.toml (no game restart).
        com.niko.voicespells.VoiceSpellsConfig.reloadCallback = () -> {
            VoiceController.onConfigChanged();
            VoiceController.syncCapture();
        };
        com.niko.voicespells.VoiceSpellsConfig.themeApplier = () -> {
            com.niko.voicespells.VoiceSpellsConfig.Client c = com.niko.voicespells.VoiceSpellsConfig.CLIENT;
            Theme.applyPalette(c.uiPalette.get());
            Theme.applyPreset(c.themePreset.get());
        };
        Runtime.getRuntime().addShutdownHook(new Thread(VoiceController::shutdown,
            "VoiceSpells-Shutdown"));
    }

    private static void onClientSetup(FMLClientSetupEvent event) {
        // SpellIndex is populated in common setup which has already fired by now, so kicking off
        // the Vosk load here means the model is usually ready before the player's first sentence.
        event.enqueueWork(VoiceController::preloadAsync);
        // Bring the microphone up if OpenAL capture is the configured source. Safe when it is
        // not — syncCapture() is a no-op unless audioSource is OPENAL.
        event.enqueueWork(VoiceController::syncCapture);
    }

    private static void onRegisterKeys(RegisterKeyMappingsEvent event) {
        event.register(TOGGLE_LISTENING);
        event.register(TOGGLE_HUD);
        event.register(ACCEPT_SUGGESTION);
        event.register(QUICK_RECAST);
        event.register(PUSH_TO_TALK);
    }

//? if forge {
/*
    /^* Forge takes a bare id string here and namespaces it under the mod itself, where NeoForge
     *  wanted a full ResourceLocation. ^/
    private static void onRegisterGuiOverlays(RegisterGuiOverlaysEvent event) {
        event.registerAboveAll("voice_hud",           new HudOverlay());
        event.registerAboveAll("cast_vignette",       new CastingVignette());
        event.registerAboveAll("cooldown_indicator",  new CooldownIndicator());
*///?} else {
    private static void onRegisterGuiLayers(RegisterGuiLayersEvent event) {
        event.registerAboveAll(
            ResourceLocation.fromNamespaceAndPath(VoiceSpells.MOD_ID, "voice_hud"),
            new HudOverlay()
        );
        event.registerAboveAll(
            ResourceLocation.fromNamespaceAndPath(VoiceSpells.MOD_ID, "cast_vignette"),
            new CastingVignette()
        );
        event.registerAboveAll(
            ResourceLocation.fromNamespaceAndPath(VoiceSpells.MOD_ID, "cooldown_indicator"),
            new CooldownIndicator()
        );
//?}
    }

    /** Drain the keybind click queue every tick. consumeClick() returns true once per press,
     *  which is exactly the toggle semantics we want — holding the key won't spam. Also
     *  drains the cast queue so a spell parked during a long cast fires when the cast ends.
     *  Pops the first-run wizard once we're in a world, the model is ready and no other
     *  screen is up — that's the first moment the wizard can demonstrate anything live. */
    private static boolean firstRunPopped = false;
    private static long    firstRunEligibleSinceMs = 0L;
//? if forge {
/*
    /^* Prepend the player's voice-cast rank to outgoing chat messages when {@code chatRankTag}
     *  is on. Cosmetic; sent BEFORE the server sees it so other players see the prefix too. ^/
    private static void onClientChat(net.minecraftforge.client.event.ClientChatEvent event) {
*///?} else {

    /** Prepend the player's voice-cast rank to outgoing chat messages when {@code chatRankTag}
     *  is on. Cosmetic; sent BEFORE the server sees it so other players see the prefix too. */
    private static void onClientChat(net.neoforged.neoforge.client.event.ClientChatEvent event) {
//?}
        if (!VoiceSpellsConfig.cChatRankTag) return;
        String original = event.getMessage();
        if (original == null || original.isEmpty()) return;
        // Skip if the player already prefixed something brackety so we don't double up.
        if (original.startsWith("[")) return;
        String rank = com.niko.voicespells.client.VoiceStats.currentRank();
        event.setMessage("[" + rank + "] " + original);
    }

//? if forge {
/*
    /^* 1.20.1 has one ClientTickEvent with a phase field rather than NeoForge's Pre/Post
     *  subclasses, so the END-phase filter has to be explicit. ^/
    private static void onClientTickPost(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
*///?} else {
    private static void onClientTickPost(ClientTickEvent.Post event) {
//?}
        Minecraft mc = Minecraft.getInstance();
        // Device-level suspend, distinct from the per-frame gate in captureArmed(). The frame gate
        // is what makes HOLD_KEY feel instant; this is what makes "the mic is off" literally true —
        // the OpenAL device is closed, not merely ignored, while you are tabbed out, paused, or on
        // a menu. Cheap to evaluate and idempotent, so running it every tick is fine.
        VoiceController.tickCaptureSuspension();
        if (mc.player == null) {
            firstRunEligibleSinceMs = 0L; // reset settle timer between worlds
            return;
        }
        VoiceController.tryDrainCastQueue();
        VoiceController.sampleWaveformIfDue();
        VoiceController.tickAfkPosition(mc.player);
        VoiceController.refreshOwnedSpellsIfDue();

        // First-run wizard: pop once in-world, after the Vosk model has finished loading and
        // the player has been stable on no-screen for ~1.5s. Doing it in-game means the
        // audio meter and recognition feed actually work, which is the whole point.
        if (!firstRunPopped && mc.screen == null
                && "READY".equals(VoiceController.statusLine())) {
            long nowMs = System.currentTimeMillis();
            if (firstRunEligibleSinceMs == 0L) {
                firstRunEligibleSinceMs = nowMs;
            } else if (nowMs - firstRunEligibleSinceMs >= 1500L) {
                firstRunPopped = true;
                try {
                    // Pop only if BOTH the config flag is still true AND the stats-side flag
                    // hasn't latched. The stats flag is the reliable one — config writes can
                    // be lost on a fast exit, so checking stats keeps the wizard from re-
                    // appearing in that case.
                    if (VoiceSpellsConfig.CLIENT.firstRun.get() && !VoiceStats.wizardSeen()) {
                        mc.setScreen(new FirstRunScreen(null));
                    }
                } catch (Throwable ignored) { /* config not ready — try again on next session */ }
            }
        } else if (mc.screen != null) {
            // Player opened something — restart the settle timer when they close it.
            firstRunEligibleSinceMs = 0L;
        }
        while (TOGGLE_LISTENING.consumeClick()) {
            boolean on = VoiceController.toggleListening();
            mc.player.displayClientMessage(
                Component.translatable(on ? "text.voicespells.listening_on"
                                          : "text.voicespells.listening_off"),
                true);
        }
        while (TOGGLE_HUD.consumeClick()) {
            boolean on = VoiceController.toggleHud();
            mc.player.displayClientMessage(
                Component.translatable(on ? "text.voicespells.hud_on"
                                          : "text.voicespells.hud_off"),
                true);
        }
        while (QUICK_RECAST.consumeClick()) {
            String last = VoiceController.lastDispatchedSpellId();
            if (last == null || last.isEmpty()) {
                mc.player.displayClientMessage(
                    Component.literal("Nothing to recast yet — say a spell first."), true);
            } else {
                VoiceController.quickRecastLast();
            }
        }
        while (ACCEPT_SUGGESTION.consumeClick()) {
            VoiceController.AliasSuggestion s = VoiceController.lastSuggestion();
            if (s == null) continue;
            long age = System.nanoTime() - s.shownAtNanos();
            if (age > VoiceController.SUGGESTION_LIFETIME_NANOS) {
                VoiceController.clearSuggestion();
                continue;
            }
            // Open the alias editor pre-filled with the heard phrase + suggested spell id.
            mc.setScreen(new AddAliasScreen(null, s.candidateSpellId().toString(), s.heardPhrase()));
            VoiceController.clearSuggestion();
        }
    }

    /**
     * Cooldown indicator. Shows a small accent chip just under the crosshair with the name of
     * the last voice-cast spell when it's currently on cooldown, plus the remaining cooldown
     * percentage. Disappears once the cooldown clears. Reflective so it stays silent when
     * Iron's Spells isn't present or the API shape shifts.
     */
    /** Iron's Spells reflection cache. Resolving Class.forName + getMethod each frame for the
     *  cooldown indicator and cast vignette burned measurable CPU in long sessions. Resolve
     *  once on first use, then call through the cached Method handle every frame. */
    private static final class IronsSpellsRefl {
        static final Class<?> CMD;                // ClientMagicData
        static final java.lang.reflect.Method GET_COOLDOWNS;
        static final java.lang.reflect.Method IS_CASTING;
        static volatile java.lang.reflect.Method GET_PCT;        // (String) -> float
        static volatile java.lang.reflect.Method IS_ON_COOLDOWN; // (String) -> boolean fallback
        static volatile boolean cooldownReady = false;
        static {
            Class<?> cmd = null;
            java.lang.reflect.Method getCd = null;
            java.lang.reflect.Method isCast = null;
            try {
                cmd = Class.forName("io.redspace.ironsspellbooks.api.magic.ClientMagicData");
                for (String m : new String[]{ "getPlayerCooldowns", "getCooldowns" }) {
                    try { getCd = cmd.getMethod(m); break; }
                    catch (NoSuchMethodException ignored) {}
                }
                try { isCast = cmd.getMethod("isCasting"); }
                catch (NoSuchMethodException ignored) {}
            } catch (Throwable ignored) {}
            CMD = cmd;
            GET_COOLDOWNS = getCd;
            IS_CASTING = isCast;
        }
        private IronsSpellsRefl() {}
    }

//? if forge {
/*    private static final class CooldownIndicator implements IGuiOverlay {
*///?} else {
    private static final class CooldownIndicator implements LayeredDraw.Layer {
//?}
        @Override
//? if forge {
/*        public void render(ForgeGui gui, GuiGraphics g, float partialTick, int screenWidth, int screenHeight) {
*///?} else {
        public void render(GuiGraphics g, net.minecraft.client.DeltaTracker delta) {
//?}
            String spellId = VoiceController.lastDispatchedSpellId();
            if (spellId == null || spellId.isEmpty()) return;
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || mc.options.hideGui) return;
            float percent = getCooldownPercent(spellId);
            if (percent <= 0f || percent >= 1f) return; // 1.0 = no cooldown, 0 = unknown
            int w = mc.getWindow().getGuiScaledWidth();
            int h = mc.getWindow().getGuiScaledHeight();
            String name = com.niko.voicespells.spells.SpellInfo.of(spellId).name;
            if (name == null || name.isEmpty()) {
                int colon = spellId.indexOf(':');
                name = (colon >= 0 ? spellId.substring(colon + 1) : spellId).replace('_', ' ');
            }
            String label = name + "  " + Math.round(percent * 100) + "%";
            Font font = mc.font;
            int tw = font.width(label);
            int chipW = tw + 12;
            int chipH = 12;
            int x = (w - chipW) / 2;
            int y = h / 2 + 16; // just under the crosshair
            // Background + frame. World HUD overlays read best on dark regardless of menu
            // palette — light cream behind text on a bright sky becomes unreadable — so the
            // chip background is intentionally fixed-dark. Only the accent border follows
            // the current theme.
            g.fill(x, y, x + chipW, y + chipH, 0xAA0A0A12);
            int border = (Theme.C_ACCENT & 0x00FFFFFF) | 0xAA000000;
            g.fill(x, y, x + chipW, y + 1, border);
            g.fill(x, y + chipH - 1, x + chipW, y + chipH, border);
            // tiny progress bar at the bottom: from full → empty as cooldown elapses
            int barW = (int) (chipW * (1f - percent));
            if (barW > 0) {
                g.fill(x, y + chipH - 1, x + barW, y + chipH, Theme.C_ACCENT_BRIGHT);
            }
            // Fixed light text — the chip background is fixed-dark above, so we can't use
            // Theme.C_TEXT (which goes dark in light mode and would vanish into the dark chip).
            g.drawString(font, Component.literal(label), x + 6, y + 2, 0xFFEAE6FA, false);
        }

        /** Returns 0..1 cooldown remaining (1.0 = full cooldown active, 0.0 = ready). Returns
         *  -1 when we can't determine (Iron's Spells absent / API mismatch). All reflection
         *  is resolved once via {@link IronsSpellsRefl} and cached — the per-frame path is
         *  just two virtual calls. */
        private static float getCooldownPercent(String spellId) {
            if (IronsSpellsRefl.GET_COOLDOWNS == null) return 0f;
            try {
                Object cooldowns = IronsSpellsRefl.GET_COOLDOWNS.invoke(null);
                if (cooldowns == null) return 0f;
                if (!IronsSpellsRefl.cooldownReady) {
                    // First time we see a non-null cooldowns object — bind the per-spell
                    // method handles. Synchronized once; subsequent frames skip this branch.
                    synchronized (IronsSpellsRefl.class) {
                        if (!IronsSpellsRefl.cooldownReady) {
                            Class<?> cls = cooldowns.getClass();
                            for (String m : new String[]{ "getCooldownPercent", "getCurrentCooldownPercent" }) {
                                try { IronsSpellsRefl.GET_PCT = cls.getMethod(m, String.class); break; }
                                catch (NoSuchMethodException ignored) {}
                            }
                            try { IronsSpellsRefl.IS_ON_COOLDOWN = cls.getMethod("isOnCooldown", String.class); }
                            catch (NoSuchMethodException ignored) {}
                            IronsSpellsRefl.cooldownReady = true;
                        }
                    }
                }
                if (IronsSpellsRefl.GET_PCT != null) {
                    Object v = IronsSpellsRefl.GET_PCT.invoke(cooldowns, spellId);
                    if (v instanceof Number n) return Math.max(0f, Math.min(1f, n.floatValue()));
                }
                if (IronsSpellsRefl.IS_ON_COOLDOWN != null) {
                    boolean onCd = (boolean) IronsSpellsRefl.IS_ON_COOLDOWN.invoke(cooldowns, spellId);
                    return onCd ? 0.5f : 0f;
                }
            } catch (Throwable ignored) {}
            return 0f;
        }
    }
//? if forge {
/*
    /^*
     * Cinematic accent edges while the player is casting a long spell. Reflectively reads
     * ClientMagicData.isCasting() each frame — when true, draws a quietly pulsing accent line
     * at the top and bottom of the screen plus a corner glow. Disabled when no cast is in
     * flight so it never interferes with normal play.
     ^/
    private static final class CastingVignette implements IGuiOverlay {
*///?} else {

    /**
     * Cinematic accent edges while the player is casting a long spell. Reflectively reads
     * ClientMagicData.isCasting() each frame — when true, draws a quietly pulsing accent line
     * at the top and bottom of the screen plus a corner glow. Disabled when no cast is in
     * flight so it never interferes with normal play.
     */
    private static final class CastingVignette implements LayeredDraw.Layer {
//?}
        @Override
//? if forge {
/*        public void render(ForgeGui gui, GuiGraphics g, float partialTick, int screenWidth, int screenHeight) {
*///?} else {
        public void render(GuiGraphics g, net.minecraft.client.DeltaTracker delta) {
//?}
            if (!isCasting()) return;
            Minecraft mc = Minecraft.getInstance();
            if (mc.options.hideGui) return;
            int w = mc.getWindow().getGuiScaledWidth();
            int h = mc.getWindow().getGuiScaledHeight();
            float p = Theme.pulse(1800f); // 1.8s breath
            int alphaCore = (int) (45 + 35 * p);
            int alphaSoft = (int) (12 + 18 * p);
            int rgb = Theme.C_ACCENT & 0x00FFFFFF;
            int core = (Math.min(255, alphaCore) << 24) | rgb;
            int soft = (Math.min(255, alphaSoft) << 24) | rgb;
            // Top and bottom 1px crisp accent bars.
            g.fill(0, 0,         w, 1, core);
            g.fill(0, h - 1,     w, h, core);
            // Soft 6px band just inside the crisp lines.
            g.fill(0, 1,         w, 7,     soft);
            g.fill(0, h - 7,     w, h - 1, soft);
            // Corner glows — small 24x6 boxes at each corner for a "cinematic" feel.
            g.fill(0, 0,             24, 6,         core);
            g.fill(w - 24, 0,        w,  6,         core);
            g.fill(0, h - 6,         24, h,         core);
            g.fill(w - 24, h - 6,    w,  h,         core);
        }

        private static boolean isCasting() {
            if (IronsSpellsRefl.IS_CASTING == null) return false;
            try {
                return (boolean) IronsSpellsRefl.IS_CASTING.invoke(null);
            } catch (Throwable ignored) {
                return false;
            }
        }
    }
//? if forge {
/*
    /^*
     * Slick bottom-left HUD. Two stacked chips:
     *   - main:  status dot + label + audio meter, always shown while HUD is visible
     *   - toast: fades in/out when a spell just cast, positioned just above the main chip
     *
     * Chip frames use a one-pixel border drawn as two nested rects — cheap "rounded enough"
     * look that fits MC's pixel aesthetic without needing custom textures.
     ^/
    private static final class HudOverlay implements IGuiOverlay {
*///?} else {

    /**
     * Slick bottom-left HUD. Two stacked chips:
     *   - main:  status dot + label + audio meter, always shown while HUD is visible
     *   - toast: fades in/out when a spell just cast, positioned just above the main chip
     *
     * Chip frames use a one-pixel border drawn as two nested rects — cheap "rounded enough"
     * look that fits MC's pixel aesthetic without needing custom textures.
     */
    private static final class HudOverlay implements LayeredDraw.Layer {
//?}

        private static final int CHIP_H   = 14;
        private static final int PAD_X    = 5;
        private static final int DOT_SIZE = 4;
        private static final int METER_W  = 24;
        private static final int METER_H  = 4;

        /**
         * Mic state dot plus a live level meter.
         *
         * <p>This chip used to be deleted on the grounds that Simple Voice Chat already showed
         * whether you were transmitting. That is no longer true — the mod owns the microphone now,
         * and without this there is nothing anywhere in the UI that says whether it is listening.
         * That matters most in HOLD_KEY and HOLD_ITEM, where the mic being closed is the normal
         * state and is otherwise indistinguishable from the mod being broken.
         *
         * <p>Three states, deliberately distinct at a glance:
         * <ul>
         *   <li><b>idle</b> (faint) — capture suspended or gate closed: not listening.</li>
         *   <li><b>armed</b> (accent) — listening, nothing above the noise gate yet.</li>
         *   <li><b>listening</b> (bright, pulsing) — audio is passing the gate and reaching Vosk.</li>
         * </ul>
         */
        private void drawMicChip(GuiGraphics g, Font font, int x, int y, boolean isBottom) {
            boolean armed = VoiceController.isArmed();
            float level = VoiceController.audioLevel();
            boolean hot = armed && level > 0.02f;

            int dotColor = !armed ? Theme.C_FAINT
                         : hot    ? Theme.withPulsedAlpha(Theme.C_ACCENT_BRIGHT & 0x00FFFFFF, 0.75f, 1.0f)
                                  : Theme.C_ACCENT_SOFT;

            // Sits opposite the toast so the two never overlap as history stacks up.
            int chipY = y + (isBottom ? CHIP_H + 3 : -(CHIP_H + 3));
            int dotX = x + PAD_X;
            int dotY = chipY + (CHIP_H - DOT_SIZE) / 2;
            g.fill(dotX, dotY, dotX + DOT_SIZE, dotY + DOT_SIZE, dotColor);

            // Level meter. Drawn even when idle (as an empty track) so the chip keeps a stable
            // width and does not jitter as speech starts and stops.
            int meterX = dotX + DOT_SIZE + 4;
            int meterY = chipY + (CHIP_H - METER_H) / 2;
            g.fill(meterX, meterY, meterX + METER_W, meterY + METER_H, Theme.C_PANEL);
            if (armed && level > 0f) {
                int filled = Math.max(1, Math.min(METER_W, Math.round(level * METER_W)));
                g.fill(meterX, meterY, meterX + filled, meterY + METER_H, dotColor);
            }

            // Calibration mode replaces casting entirely, so say so rather than letting the chip
            // imply spells are about to fire.
            if (VoiceController.isTranscribing()) {
                g.drawString(font, "calibrating", meterX + METER_W + 4,
                    chipY + (CHIP_H - font.lineHeight) / 2 + 1, Theme.C_MUTED, false);
            }
        }

        @Override
//? if forge {
/*        public void render(ForgeGui gui, GuiGraphics g, float partialTick, int screenWidth, int screenHeight) {
*///?} else {
        public void render(GuiGraphics g, net.minecraft.client.DeltaTracker delta) {
//?}
            Minecraft mc = Minecraft.getInstance();
            if (mc.options.hideGui || mc.player == null) return;
            if (!VoiceController.isHudVisible()) return;
            // Don't draw the toast while any screen is open (config menu, inventory, pause…).
            // The HUD layer otherwise renders into the same pass behind the screen's dim
            // backdrop, so the toast bleeds through the config panel.
            if (mc.screen != null) return;

            Font font = mc.font;

            int screenW = mc.getWindow().getGuiScaledWidth();
            int screenH = mc.getWindow().getGuiScaledHeight();
            VoiceSpellsConfig.Corner corner = VoiceSpellsConfig.cHudCorner;
            int offX = VoiceSpellsConfig.cHudOffsetX;
            int offY = VoiceSpellsConfig.cHudOffsetY;

            int anchorX = anchorX(corner, screenW, 0, offX);
            int anchorY = anchorY(corner, screenH, CHIP_H, offY);
            boolean isBottom = (corner == VoiceSpellsConfig.Corner.BOTTOM_LEFT
                             || corner == VoiceSpellsConfig.Corner.BOTTOM_RIGHT);

            drawMicChip(g, font, anchorX, anchorY, isBottom);
            drawToastIfActive(g, font, anchorX, anchorY);

            // History strip — older casts trail the most recent cast toast in a stack that
            // fades out individually. The freshest history entry is the same as the cast
            // toast, so we skip index 0 when rendering.
            drawHistoryStrip(g, font, anchorX, anchorY, isBottom);

            int slotsUsed = Math.max(0, VoiceController.spellHistory().size() - 1);

            // "Queued" chip — visible while a spell is parked in the cast queue waiting for
            // the current cast to finish. Slot lives just past the history strip.
            String queuedName = VoiceController.queuedSpellDisplay();
            if (!queuedName.isEmpty()) {
                int qY = anchorY + (isBottom ? -(CHIP_H + 3) * (slotsUsed + 1)
                                              :  (CHIP_H + 3) * (slotsUsed + 1));
                drawQueuedChip(g, font, anchorX, qY, queuedName);
                slotsUsed++;
            }

            // Miss toast (showMisses): a muted note one slot outward from the queue chip.
            if (VoiceSpellsConfig.cShowMisses) {
                int missY = anchorY
                    + (isBottom ? -(CHIP_H + 3) * (slotsUsed + 1)
                                 :  (CHIP_H + 3) * (slotsUsed + 1));
                drawMissToastIfActive(g, font, anchorX, missY);
                slotsUsed++;
            }

            // Always-show-heard chip: persistent chip showing the last heard phrase. Lower
            // visual weight than the cast toast — meant for tuning sessions.
            if (VoiceSpellsConfig.cAlwaysShowHeard) {
                String heard = VoiceController.lastHeard();
                if (heard != null && !heard.isEmpty()) {
                    int hY = anchorY
                        + (isBottom ? -(CHIP_H + 3) * (slotsUsed + 1)
                                     :  (CHIP_H + 3) * (slotsUsed + 1));
                    drawHeardChip(g, font, anchorX, hY, heard);
                    slotsUsed++;
                }
            }

            // "Did you mean ...?" alias suggestion chip — fades after SUGGESTION_LIFETIME_NANOS,
            // dismissable by pressing the configured keybind.
            VoiceController.AliasSuggestion suggestion = VoiceController.lastSuggestion();
            if (suggestion != null) {
                long age = System.nanoTime() - suggestion.shownAtNanos();
                if (age > VoiceController.SUGGESTION_LIFETIME_NANOS) {
                    VoiceController.clearSuggestion();
                } else {
                    int sY = anchorY
                        + (isBottom ? -(CHIP_H + 3) * (slotsUsed + 1)
                                     :  (CHIP_H + 3) * (slotsUsed + 1));
                    drawAliasSuggestionChip(g, font, anchorX, sY, suggestion, age);
                }
            }

            // (Custom achievement toasts removed — vanilla advancements now drive the toasts
            //  via voicespells:voice_cast and the JSONs under data/voicespells/advancement/.)
        }

        private static int anchorX(VoiceSpellsConfig.Corner corner, int screenW, int chipW, int offX) {
            switch (corner) {
                case TOP_RIGHT:
                case BOTTOM_RIGHT: return screenW - chipW - offX;
                default:           return offX;
            }
        }
        private static int anchorY(VoiceSpellsConfig.Corner corner, int screenH, int chipH, int offY) {
            switch (corner) {
                case BOTTOM_LEFT:
                case BOTTOM_RIGHT: return screenH - chipH - offY;
                default:           return offY;
            }
        }

        private static void drawToastIfActive(GuiGraphics g, Font font, int anchorX, int anchorY) {
            String spell = VoiceController.lastCastDisplay();
            long castTime = VoiceController.lastCastNanos();
            if (spell == null || spell.isEmpty() || castTime == 0L) return;
            long elapsed = System.nanoTime() - castTime;
            if (elapsed >= VoiceController.TOAST_DURATION_NANOS) return;

            float alpha;
            if (elapsed < VoiceController.TOAST_FADE_IN_NANOS) {
                alpha = elapsed / (float) VoiceController.TOAST_FADE_IN_NANOS;
            } else {
                long fadeStart = VoiceController.TOAST_DURATION_NANOS - VoiceController.TOAST_FADE_OUT_NANOS;
                if (elapsed > fadeStart) {
                    alpha = 1f - (elapsed - fadeStart) / (float) VoiceController.TOAST_FADE_OUT_NANOS;
                } else {
                    alpha = 1f;
                }
            }
            alpha = Math.max(0f, Math.min(1f, alpha));

            String text = VoiceSpellsConfig.cStreamerMode
                ? "✦ " + obscure(spell)
                : "✦ " + capitalize(spell);
            int streak = VoiceController.castStreak();
            if (streak >= 2) text += "  ×" + streak; // chained casts get a streak badge
            int textW = font.width(text);
            int toastW = PAD_X + textW + PAD_X;
            // With no persistent chip beneath us, the toast sits right at the anchor.
            int toastX = anchorX;
            int toastY = anchorY;

            drawChip(g, toastX, toastY, toastW, CHIP_H, alpha);
            // Per-school text color so the cast toast carries an extra cue. The user's
            // configured cTextToast is still used as the alpha source so opacity stays
            // wired to the toml [colors] section.
            int schoolRgb = com.niko.voicespells.spells.SpellSchools.colorFor(
                VoiceController.lastCastSchool());
            int color = withAlpha(schoolRgb, alpha);
            int textY = toastY + (CHIP_H - 8) / 2;
            g.drawString(font, Component.literal(text), toastX + PAD_X, textY, color, false);
        }

        /** Stack of recent-cast history chips trailing the main cast toast. Index 0 of the
         *  history is the same event as the cast toast, so we render indices 1..N-1 only.
         *  Each chip fades in/out on its own clock and lives longer than the main toast so a
         *  burst of casts visibly accumulates before settling back down. */
        private static void drawHistoryStrip(GuiGraphics g, Font font, int anchorX, int anchorY,
                                              boolean isBottom) {
            java.util.List<VoiceController.HistoryEntry> history = VoiceController.spellHistory();
            if (history.size() <= 1) return;
            long now = System.nanoTime();
            long lifetime = 4_500_000_000L;          // 4.5s on screen
            long fadeIn   =   180_000_000L;          // 0.18s
            long fadeOut  =   900_000_000L;          // 0.9s
            float maxAlpha = 0.55f;                  // history is dimmer than the main toast

            for (int i = 1; i < history.size(); i++) {
                VoiceController.HistoryEntry entry = history.get(i);
                long elapsed = now - entry.nanoTime();
                if (elapsed < 0 || elapsed >= lifetime) continue;
                float alpha;
                if (elapsed < fadeIn) {
                    alpha = (elapsed / (float) fadeIn) * maxAlpha;
                } else if (elapsed > lifetime - fadeOut) {
                    alpha = ((lifetime - elapsed) / (float) fadeOut) * maxAlpha;
                } else {
                    alpha = maxAlpha;
                }
                alpha = Math.max(0f, Math.min(maxAlpha, alpha));

                String text = VoiceSpellsConfig.cStreamerMode
                    ? "↳ " + obscure(entry.display())
                    : "↳ " + capitalize(entry.display());
                int toastW = PAD_X + font.width(text) + PAD_X;
                int yOff = (isBottom ? -1 : 1) * (CHIP_H + 3) * i;
                int toastY = anchorY + yOff;

                drawChip(g, anchorX, toastY, toastW, CHIP_H, alpha);
                int color = withAlpha(VoiceSpellsConfig.cTextMuted, alpha);
                int textY = toastY + (CHIP_H - 8) / 2;
                g.drawString(font, Component.literal(text), anchorX + PAD_X, textY, color, false);
            }
        }

        /** Always-show-heard chip — quiet, persistent display of the last heard phrase.
         *  Used for tuning recognition; doesn't fade like the toast / miss / suggestion chips. */
        private static void drawHeardChip(GuiGraphics g, Font font, int anchorX, int anchorY,
                                           String heard) {
            String text = "» " + (heard.length() > 28 ? heard.substring(0, 27) + "…" : heard);
            int toastW = PAD_X + font.width(text) + PAD_X;
            drawChip(g, anchorX, anchorY, toastW, CHIP_H, 0.55f);
            int color = withAlpha(VoiceSpellsConfig.cTextMuted, 0.85f);
            int textY = anchorY + (CHIP_H - 8) / 2;
            g.drawString(font, Component.literal(text), anchorX + PAD_X, textY, color, false);
        }

        /** "Did you mean X?" alias-suggestion chip — appears after a near-miss recognition,
         *  fades in/out, and tells the player which key to press to add the alias. */
        private static void drawAliasSuggestionChip(GuiGraphics g, Font font, int anchorX, int anchorY,
                                                     VoiceController.AliasSuggestion s, long elapsed) {
            long lifetime = VoiceController.SUGGESTION_LIFETIME_NANOS;
            float alpha;
            if (elapsed < 200_000_000L) alpha = elapsed / 200_000_000f;
            else if (elapsed > lifetime - 600_000_000L) alpha = (lifetime - elapsed) / 600_000_000f;
            else alpha = 1f;
            alpha = Math.max(0f, Math.min(1f, alpha));

            String text = "? " + capitalize(s.candidateDisplay()) + "  [press Y]";
            int toastW = PAD_X + font.width(text) + PAD_X;
            drawChip(g, anchorX, anchorY, toastW, CHIP_H, alpha);
            // Bright accent text — this is actionable, treat it differently from a miss toast.
            int color = withAlpha(Theme.C_ACCENT, alpha);
            int textY = anchorY + (CHIP_H - 8) / 2;
            g.drawString(font, Component.literal(text), anchorX + PAD_X, textY, color, false);
        }

        /** Persistent chip while a spell is queued — gives the player visible confirmation
         *  that the spell will fire after the current cast ends, instead of disappearing
         *  into silent state. Slightly dimmer than the live toast so it reads as "pending". */
        private static void drawQueuedChip(GuiGraphics g, Font font, int anchorX, int anchorY,
                                            String name) {
            String body = VoiceSpellsConfig.cStreamerMode ? obscure(name) : name;
            int extra = Math.max(0, VoiceController.queuedCount() - 1);
            String text = extra == 0 ? "▷ " + body : "▷ " + body + "  (+" + extra + ")";
            int toastW = PAD_X + font.width(text) + PAD_X;
            drawChip(g, anchorX, anchorY, toastW, CHIP_H, 0.75f);
            int color = withAlpha(VoiceSpellsConfig.cTextToast, 0.85f);
            int textY = anchorY + (CHIP_H - 8) / 2;
            g.drawString(font, Component.literal(text), anchorX + PAD_X, textY, color, false);
        }

        private static void drawMissToastIfActive(GuiGraphics g, Font font, int anchorX, int anchorY) {
            String heard = VoiceController.lastMissText();
            long t = VoiceController.lastMissNanos();
            if (heard == null || heard.isEmpty() || t == 0L) return;
            long elapsed = System.nanoTime() - t;
            if (elapsed >= VoiceController.TOAST_DURATION_NANOS) return;

            float alpha;
            if (elapsed < VoiceController.TOAST_FADE_IN_NANOS) {
                alpha = elapsed / (float) VoiceController.TOAST_FADE_IN_NANOS;
            } else {
                long fadeStart = VoiceController.TOAST_DURATION_NANOS - VoiceController.TOAST_FADE_OUT_NANOS;
                alpha = elapsed > fadeStart
                    ? 1f - (elapsed - fadeStart) / (float) VoiceController.TOAST_FADE_OUT_NANOS
                    : 1f;
            }
            alpha = Math.max(0f, Math.min(1f, alpha));

            String text = "? " + (VoiceSpellsConfig.cStreamerMode ? obscure(heard) : heard);
            int toastW = PAD_X + font.width(text) + PAD_X;
            drawChip(g, anchorX, anchorY, toastW, CHIP_H, alpha * 0.85f);
            int color = withAlpha(VoiceSpellsConfig.cTextMuted, alpha);
            g.drawString(font, Component.literal(text), anchorX + PAD_X,
                anchorY + (CHIP_H - 8) / 2, color, false);
        }

        private static void drawChip(GuiGraphics g, int x, int y, int w, int h, float alpha) {
            int border = withAlpha(VoiceSpellsConfig.cBorder, alpha);
            int bg     = withAlpha(VoiceSpellsConfig.cBg,     alpha);
            // Border is drawn as four thin edge rectangles, NOT as a full fill behind the body.
            // If we filled the whole area first, an alpha=0 background would leave the border
            // colour visible across the entire chip (alpha-blend "src*0 + dst*1 = dst" never
            // overwrites). With edge lines, a transparent body is actually transparent.
            g.fill(x,         y,         x + w,     y + 1,     border); // top
            g.fill(x,         y + h - 1, x + w,     y + h,     border); // bottom
            g.fill(x,         y + 1,     x + 1,     y + h - 1, border); // left
            g.fill(x + w - 1, y + 1,     x + w,     y + h - 1, border); // right
            // Inner body; transparent bg here is a real no-op now.
            if (((bg >>> 24) & 0xFF) > 0) {
                g.fill(x + 1, y + 1, x + w - 1, y + h - 1, bg);
            }
        }

        private static int withAlpha(int argb, float alpha) {
            int origA = (argb >>> 24) & 0xFF;
            int newA = Math.max(0, Math.min(255, Math.round(origA * alpha)));
            return (newA << 24) | (argb & 0x00FFFFFF);
        }

        /** Streamer-safe display: keep the word/letter count so the chip width feels stable,
         *  but replace every non-space character with a bullet. Viewers reading the screen
         *  see "✦ ●●●●●●●●" instead of "✦ Fireball". */
        private static String obscure(String s) {
            if (s == null || s.isEmpty()) return s;
            StringBuilder sb = new StringBuilder(s.length());
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                sb.append(c == ' ' ? ' ' : '●');
            }
            return sb.toString();
        }

        private static String capitalize(String s) {
            if (s == null || s.isEmpty()) return "";
            StringBuilder sb = new StringBuilder(s.length());
            boolean capNext = true;
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (capNext && Character.isLetter(c)) {
                    sb.append(Character.toUpperCase(c));
                    capNext = false;
                } else {
                    sb.append(c);
                    if (c == ' ') capNext = true;
                }
            }
            return sb.toString();
        }
    }
}
