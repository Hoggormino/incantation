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
        // One palette, no accent, so there is nothing config-driven left to re-apply. The hook
        // stays wired because the config class calls it on load and reload and expects a target.
        com.niko.voicespells.VoiceSpellsConfig.themeApplier = Theme::applyPalette;
        Runtime.getRuntime().addShutdownHook(new Thread(VoiceController::shutdown,
            "VoiceSpells-Shutdown"));
    }

    private static void onClientSetup(FMLClientSetupEvent event) {
        // SpellIndex is populated in common setup which has already fired by now, so kicking off
        // the Vosk load here means the model is usually ready before the player's first sentence.
        event.enqueueWork(VoiceController::preloadAsync);
        // Deliberately does NOT open the microphone here. This used to call syncCapture(), guarded
        // by a comment claiming it was a no-op unless audioSource was OPENAL — but that config
        // went away with the Simple Voice Chat path, so the call became unconditional and opened
        // the capture device during client setup, i.e. while sitting on the title screen. The mic
        // then closed again on the first client tick, which made it easy to miss.
        //
        // tickCaptureSuspension() already owns the whole lifecycle: it opens the device once you
        // are in a world and releases it whenever you are not. Letting it be the only thing that
        // opens capture is what makes "the mic is not live on the title screen" actually true.
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
        String tagged = "[" + rank + "] " + original;
        // The chat packet caps the message at 256 characters and the server kicks the client
        // for exceeding it. Prefixing a rank can push a message that was legally just under the
        // limit past it, so a cosmetic feature would disconnect the player for typing a long
        // line. Dropping the tag is the right trade: the message still sends.
        if (tagged.length() > 256) return;
        event.setMessage(tagged);
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
        // Above the mc.player null-check on purpose: calibration can be started from the
        // first-run wizard or the config screen with no world loaded, and its window has to be
        // able to close there too. Also the only thing that ends calibration when the mic
        // delivers no frames at all — see VoiceController.tickCalibration.
        VoiceController.tickCalibration();
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
        /** Iron's Spells' client-side magic state. The package matters and has been wrong before:
         *  it is {@code ...ironsspellbooks.player.ClientMagicData}, NOT {@code ...api.magic.}.
         *  Confirmed against the shipped jar (irons_spellbooks-1.21.1-3.16.2.jar contains
         *  io/redspace/ironsspellbooks/player/ClientMagicData.class and no api/magic equivalent).
         *  The old name resolved to nothing, so every lookup below failed and each caller quietly
         *  took its fallback — which is why it went unnoticed across four releases. */
        private static final String CLIENT_MAGIC_DATA =
            "io.redspace.ironsspellbooks.player.ClientMagicData";

        /** {@code ClientMagicData.getCooldownPercent(AbstractSpell)} — static, returns
         *  remaining/total, so 1.0 right after a cast decaying to exactly 0 when ready. */
        static final java.lang.reflect.Method GET_CD_PERCENT;
        /** {@code SpellRegistry.getSpell(String)} — static, resolves an id to the AbstractSpell
         *  that {@link #GET_CD_PERCENT} needs. */
        static final java.lang.reflect.Method GET_SPELL;
        /** {@code AbstractSpell.getSpellId()} — used only to reject the NoneSpell sentinel. */
        static final java.lang.reflect.Method SPELL_ID;
        static final java.lang.reflect.Method IS_CASTING;

        /** spellId → AbstractSpell. The indicator resolves the same handful of ids every frame;
         *  a registry lookup per frame would be wasteful. Values are registry singletons. */
        static final java.util.Map<String, Object> SPELL_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

        static {
            java.lang.reflect.Method pct = null;
            java.lang.reflect.Method isCast = null;
            java.lang.reflect.Method getSpell = null;
            java.lang.reflect.Method spellId = null;
            try {
                Class<?> cmd = Class.forName(CLIENT_MAGIC_DATA);
                // Matched by name + arity rather than by parameter type, so we never have to load
                // AbstractSpell ourselves just to describe the signature.
                for (java.lang.reflect.Method m : cmd.getMethods()) {
                    if (m.getName().equals("getCooldownPercent")
                        && m.getParameterCount() == 1
                        && java.lang.reflect.Modifier.isStatic(m.getModifiers())) { pct = m; break; }
                }
                try { isCast = cmd.getMethod("isCasting"); }
                catch (NoSuchMethodException ignored) {}

                Class<?> reg = Class.forName("io.redspace.ironsspellbooks.api.registry.SpellRegistry");
                getSpell = reg.getMethod("getSpell", String.class);
                spellId = Class.forName("io.redspace.ironsspellbooks.api.spells.AbstractSpell")
                    .getMethod("getSpellId");
            } catch (Throwable t) {
                // Never silently: a swallowed failure here is invisible for releases at a time.
                // Debug rather than warn — a missing optional integration is not the user's problem.
                VoiceSpells.LOGGER.debug("Iron's Spells reflection unavailable ({}); "
                    + "cooldown and is-casting hints are disabled", t.toString());
            }
            GET_CD_PERCENT = pct;
            GET_SPELL = getSpell;
            SPELL_ID = spellId;
            IS_CASTING = isCast;
            if (pct == null || isCast == null || getSpell == null) {
                VoiceSpells.LOGGER.debug("Iron's Spells resolved but members missing "
                    + "(cooldownPercent={}, isCasting={}, getSpell={}); hints degrade to defaults",
                    pct != null, isCast != null, getSpell != null);
            }
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
            // percent is the fraction of the cooldown REMAINING. 0 means ready (or unknown —
            // see getCooldownPercent), so there is nothing to show; > 1 can't happen but is
            // clamped as a guard.
            if (percent <= 0f) return;
            percent = Math.min(1f, percent);
            int w = mc.getWindow().getGuiScaledWidth();
            int h = mc.getWindow().getGuiScaledHeight();
            // displayName(), not name - resolved in the player's own language.
            String name = com.niko.voicespells.spells.SpellInfo.of(spellId).displayName().getString();
            if (name == null || name.isEmpty()) {
                int colon = spellId.indexOf(':');
                name = (colon >= 0 ? spellId.substring(colon + 1) : spellId).replace('_', ' ');
            }
            // Shown as readiness, not as remaining, so the number and the bar below it move in
            // the same direction: both climb to 100% as the spell comes off cooldown.
            int ready = Math.round((1f - percent) * 100);
            String label = name + "  " + ready + "%";
            Font font = mc.font;
            int tw = font.width(label);
            int chipW = tw + 12;
            int chipH = 12;
            int x = (w - chipW) / 2;
            int y = h / 2 + 16; // just under the crosshair
            // Background + frame. A world HUD overlay reads best on dark whatever else is going
            // on — light text-on-cream over a bright sky is unreadable — so the chip background
            // is deliberately fixed dark and the border is plain white. There is no theme colour
            // to follow any more, and this is drawn over scenery rather than over a panel.
            g.fill(x, y, x + chipW, y + chipH, 0xAA0A0A12);
            int border = 0xAAFFFFFF;
            g.fill(x, y, x + chipW, y + 1, border);
            g.fill(x, y + chipH - 1, x + chipW, y + chipH, border);
            // tiny progress bar at the bottom: from full → empty as cooldown elapses
            int barW = (int) (chipW * (1f - percent));
            if (barW > 0) {
                g.fill(x, y + chipH - 1, x + barW, y + chipH, Theme.C_HL);
            }
            // Fixed light text — the chip background is fixed-dark above, so we can't use
            // Theme.C_TEXT (which goes dark in light mode and would vanish into the dark chip).
            g.drawString(font, Component.literal(label), x + 6, y + 2, 0xFFEAE6FA, true);
        }

        /** Returns the 0..1 fraction of the cooldown still to run: 1.0 the instant the spell is
         *  cast, decaying to exactly 0 the moment it is ready again.
         *
         *  <p>Returns {@code 0f} when we can't determine it — Iron's Spells absent, API
         *  mismatch, reflection failure. This javadoc used to promise -1 for that case; no
         *  such path exists, and callers were never checking for it. Be aware of what the
         *  fallback means: 0f reads as "ready", so an unavailable cooldown is indistinguishable
         *  from no cooldown, and the HUD will happily show a spell as castable when it has no
         *  idea. That is the safe direction for a hint, but it is a guess, not a reading.
         *
         *  <p>This used to probe {@code PlayerCooldowns} for a {@code (String)} overload of
         *  {@code getCooldownPercent} / {@code isOnCooldown}. No such overload exists on either
         *  1.20.1 or 1.21.1 — both take an {@code AbstractSpell} (verified with javap against
         *  irons-spells-n-spellbooks 3.16.2 for both versions) — so neither handle ever bound,
         *  every call fell through to {@code return 0f}, and this indicator had never once
         *  rendered. We now resolve the spell through {@code SpellRegistry.getSpell(String)}
         *  and call the static {@code ClientMagicData.getCooldownPercent(AbstractSpell)}. */
        private static float getCooldownPercent(String spellId) {
            if (IronsSpellsRefl.GET_CD_PERCENT == null || IronsSpellsRefl.GET_SPELL == null) return 0f;
            try {
                Object spell = resolveSpell(spellId);
                if (spell == null) return 0f;
                Object v = IronsSpellsRefl.GET_CD_PERCENT.invoke(null, spell);
                if (v instanceof Number n) {
                    float f = n.floatValue();
                    // NaN would sail past both bounds checks in render() and print as "NaN%".
                    if (Float.isNaN(f)) return 0f;
                    return Math.max(0f, Math.min(1f, f));
                }
            } catch (Throwable ignored) {}
            return 0f;
        }

        /** Registry lookup for an id, memoised. Returns null for ids Iron's Spells doesn't know:
         *  it answers unknown ids with a NoneSpell sentinel rather than null, so the result is
         *  only accepted when its own id round-trips (same check {@code SpellInfo} makes). */
        private static Object resolveSpell(String spellId) {
            Object cached = IronsSpellsRefl.SPELL_CACHE.get(spellId);
            if (cached != null) return cached;
            try {
                Object spell = IronsSpellsRefl.GET_SPELL.invoke(null, spellId);
                if (spell == null) return null;
                if (IronsSpellsRefl.SPELL_ID != null
                    && !spellId.equals(IronsSpellsRefl.SPELL_ID.invoke(spell))) return null;
                IronsSpellsRefl.SPELL_CACHE.put(spellId, spell);
                return spell;
            } catch (Throwable ignored) {
                return null;
            }
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
            // Off by default. This effect was written early but never actually drew: the
            // reflection it depends on (IronsSpellsRefl.IS_CASTING) resolved against a
            // ClientMagicData class that does not exist, so isCasting() always answered false.
            // Correcting that class name switched the effect on for the first time in any build,
            // which is a visual change nobody opted into — hence a config flag rather than a
            // silent new default. Checked before isCasting() so the common path is one boolean.
            if (!VoiceSpellsConfig.cCastVignette) return;
            if (!isCasting()) return;
            Minecraft mc = Minecraft.getInstance();
            if (mc.options.hideGui) return;
            int w = mc.getWindow().getGuiScaledWidth();
            int h = mc.getWindow().getGuiScaledHeight();
            float p = Theme.pulse(1800f); // 1.8s breath
            int alphaCore = (int) (45 + 35 * p);
            int alphaSoft = (int) (12 + 18 * p);
            int rgb = Theme.C_HL & 0x00FFFFFF;
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

        /** How long a history chip stays on screen. Shared by the renderer and the slot
         *  accounting above them, which must agree or the chips below drift out of place. */
        private static final long HISTORY_LIFETIME_NANOS = 4_500_000_000L;
        private static final int CHIP_H   = 14;
        private static final int PAD_X    = 5;
        // Back to the dot and the thin track, which is the shape the author asked for. The
        // reason it used to disappear was never the shape: its track was 0x90101010, the same
        // RGB-over-black value that made every WELL in the mod invisible. That colour adds a
        // fixed +9 luma floor, so below ground luma 16 it is brighter than what it sits on. Pure
        // black has no constant term, so the track is now genuinely darker than the world in
        // every scene - which is all the separation a 4px dot beside it ever needed.
        private static final int DOT_SIZE = 4;
        private static final int METER_W  = 26;
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
         *   <li><b>armed</b> (light grey) — listening, nothing above the noise gate yet.</li>
         *   <li><b>listening</b> (bright, pulsing) — audio is passing the gate and reaching Vosk.</li>
         * </ul>
         */
        private void drawMicChip(GuiGraphics g, Font font, int anchorX, int y, boolean isBottom) {
            boolean armed = VoiceController.isArmed();
            float level = VoiceController.audioLevel();
            boolean hot = armed && level > 0.02f;
            boolean calibrating = VoiceController.isTranscribing();
            boolean silent = !calibrating && VoiceController.deviceSilent();

            // Three states, three colours - and idle is clearly darker than armed, because those
            // two collapsed to one grey when the accents were deleted and this chip is the mod's
            // only listening indicator.
            int dotColor = silent      ? 0xFFFF6166   // red: device open, delivering silence
                         : calibrating ? 0xFFFFB347   // amber: calibrating, not casting
                         : !armed      ? 0xFF585858   // dark grey: not listening
                         : hot         ? 0xFFFFFFFF   // white: audio reaching the recogniser
                                       : 0xFFA0A0A0; // light grey: armed, below the gate

            String tail = calibrating ? "calibrating" : silent ? "mic silent" : "";
            int chipW = PAD_X + DOT_SIZE + 4 + METER_W
                      + (tail.isEmpty() ? 0 : 4 + font.width(tail)) + PAD_X;
            int x = alignX(anchorX, chipW);

            // Sits opposite the toast so the two never overlap as history stacks up - and is
            // then clamped onto the screen.
            //
            // On a TOP corner this sits 17px ABOVE the anchor, while hud.offsetY allows 0. So any
            // offset under 17 drew the chip off the top edge and it vanished entirely - taking
            // the mod's only listening indicator with it, on the corner where a player is most
            // likely to want the HUD tucked right up against the edge.
            int screenH = Minecraft.getInstance().getWindow().getGuiScaledHeight();
            int chipY = y + (isBottom ? CHIP_H + 3 : -(CHIP_H + 3));
            chipY = Math.max(0, Math.min(screenH - CHIP_H, chipY));
            float a = Math.max(0f, Math.min(1f, VoiceSpellsConfig.cOpacity));

            int dotX = x + PAD_X;
            int dotY = chipY + (CHIP_H - DOT_SIZE) / 2;
            g.fill(dotX, dotY, dotX + DOT_SIZE, dotY + DOT_SIZE, withAlpha(dotColor, a));

            // The track, in pure black.
            //
            // This is the one substantive change from the original. It was 0xB0101010 - RGB over
            // black - which carries a fixed +9 luma floor and is therefore BRIGHTER than any
            // ground below luma 16. That is why the indicator vanished against stone and why it
            // needed an outline bolted on to be seen at all. The same wrong value made every well
            // in the mod invisible; pure black has no constant term and is darker than the world
            // in every scene, so the shape works as drawn with nothing added to it.
            int meterX = dotX + DOT_SIZE + 4;
            int meterY = chipY + (CHIP_H - METER_H) / 2;
            g.fill(meterX, meterY, meterX + METER_W, meterY + METER_H, withAlpha(0xB0000000, a));
            if (armed && level > 0f) {
                int filled = Math.max(1, Math.min(METER_W, Math.round(level * METER_W)));
                g.fill(meterX, meterY, meterX + filled, meterY + METER_H, withAlpha(dotColor, a));
            }

            if (!tail.isEmpty()) {
                g.drawString(font, tail, meterX + METER_W + 4,
                    chipY + (CHIP_H - font.lineHeight) / 2 + 1,
                    silent ? 0xFFFF6166 : 0xFFFFFFFF, true);
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

            // On a right-hand corner the anchor is the RIGHT edge of the HUD block and every
            // element subtracts its own width (see alignX). Passing 0 as the width here is
            // therefore correct — it is what makes anchorX the screen edge minus the offset.
            // Each chip has a different width, so a single shared width could not right-align
            // them all; the alignment happens per element instead.
            int anchorX = anchorX(corner, screenW, 0, offX);
            int anchorY = anchorY(corner, screenH, CHIP_H, offY);
            boolean isBottom = (corner == VoiceSpellsConfig.Corner.BOTTOM_LEFT
                             || corner == VoiceSpellsConfig.Corner.BOTTOM_RIGHT);
            rightAligned = (corner == VoiceSpellsConfig.Corner.TOP_RIGHT
                         || corner == VoiceSpellsConfig.Corner.BOTTOM_RIGHT);

            drawMicChip(g, font, anchorX, anchorY, isBottom);
            drawToastIfActive(g, font, anchorX, anchorY);

            // History strip — older casts trail the most recent cast toast in a stack that
            // fades out individually. The freshest history entry is the same as the cast
            // toast, so we skip index 0 when rendering.
            drawHistoryStrip(g, font, anchorX, anchorY, isBottom);

            // Count only the history chips that will actually be drawn. drawHistoryStrip skips
            // entries older than HISTORY_LIFETIME_NANOS, but this used to count the whole list,
            // so once a burst of casts aged out the queued / miss / heard / suggestion chips
            // stayed pushed down into empty space — a permanent gap, because the history list
            // itself is never pruned.
            int slotsUsed = 0;
            {
                java.util.List<VoiceController.HistoryEntry> hist = VoiceController.spellHistory();
                long nowNanos = System.nanoTime();
                for (int i = 1; i < hist.size(); i++) {
                    long age = nowNanos - hist.get(i).nanoTime();
                    if (age >= 0 && age < HISTORY_LIFETIME_NANOS) slotsUsed++;
                }
            }

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
            //
            // Only claim the slot if the toast actually DREW. This chip is transient - it exists
            // for a few seconds after a phrase failed to match, which is almost never - but the
            // slot was reserved whenever the setting was merely enabled, so everything below it
            // sat one chip-height further out with a permanent hole where nothing is drawn.
            if (VoiceSpellsConfig.cShowMisses) {
                int missY = anchorY
                    + (isBottom ? -(CHIP_H + 3) * (slotsUsed + 1)
                                 :  (CHIP_H + 3) * (slotsUsed + 1));
                if (drawMissToastIfActive(g, font, anchorX, missY)) slotsUsed++;
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

        /** True while the HUD is anchored to a right-hand corner. Set once per frame at the top
         *  of render() and read by {@link #alignX}. Safe as a static: the HUD renders only on
         *  the client render thread, one frame at a time. */
        private static boolean rightAligned = false;

        /** Turns the frame's anchor into the left edge of an element {@code w} pixels wide.
         *
         *  <p>Every chip draws left-to-right from the x it is given. On a left corner the anchor
         *  is already the left edge, so it passes through. On a right corner the anchor is the
         *  screen's right edge, so the element has to be pulled back by its own width — without
         *  this, the entire HUD (mic chip, toasts, history, suggestions) rendered off the right
         *  side of the screen and was invisible on both right-hand corner settings. */
        private static int alignX(int anchorX, int w) {
            return rightAligned ? anchorX - w : anchorX;
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

            // No decorative glyph. Vanilla never prefixes a HUD line with one - the item-name
            // popup, the action bar and the subtitle overlay are all just the words, shadowed -
            // and a diamond in front of the spell name is exactly the invented chrome the screens
            // had stripped out of them. The school colour already carries the "this was a cast"
            // signal, and it does it without spending a character.
            String name = VoiceSpellsConfig.cStreamerMode ? obscure(spell) : capitalize(spell);
            int streak = VoiceController.castStreak();
            String tally = streak >= 2 ? "  ×" + streak : "";
            int textW = font.width(name) + font.width(tally);
            int toastW = PAD_X + textW + PAD_X;
            // With no persistent chip beneath us, the toast sits right at the anchor.
            int toastX = alignX(anchorX, toastW);
            int toastY = anchorY;

            drawChip(g, toastX, toastY, toastW, CHIP_H, alpha);
            // Per-school text color so the cast toast carries an extra cue, with the
            // configured [hud] opacity folded in explicitly (see below).
            int schoolRgb = com.niko.voicespells.spells.SpellSchools.colorFor(
                VoiceController.lastCastSchool());
            // cOpacity has to be applied explicitly here. Every other colour picks it up because
            // refreshCache() bakes it into cBg / cBorder / cTextToast, but the cast toast draws
            // with the per-school hue instead, so it bypassed both the configured toast colour
            // and the [hud] opacity setting entirely — turning opacity down faded the whole HUD
            // except the one element people actually look at. The comment below used to claim
            // cTextToast was "still used as the alpha source", which was not true of this call.
            int color = withAlpha(schoolRgb, alpha * VoiceSpellsConfig.cOpacity);
            int textY = toastY + (CHIP_H - 8) / 2;
            int nameX = toastX + PAD_X;
            g.drawString(font, Component.literal(name), nameX, textY, color, true);
            // The streak is secondary information and used to be drawn in the same weight and
            // hue as the spell name, so "Fireball x14" read as one four-word phrase. Muted, it
            // stops competing with the thing you actually want to read.
            if (!tally.isEmpty()) {
                g.drawString(font, Component.literal(tally), nameX + font.width(name), textY,
                    withAlpha(0xFFA0A0A0, alpha * VoiceSpellsConfig.cOpacity), true);
            }
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
            long lifetime = HISTORY_LIFETIME_NANOS;  // 4.5s on screen
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
                int toastX = alignX(anchorX, toastW);

                drawChip(g, toastX, toastY, toastW, CHIP_H, alpha);
                int color = withAlpha(VoiceSpellsConfig.cTextMuted, alpha);
                int textY = toastY + (CHIP_H - 8) / 2;
                g.drawString(font, Component.literal(text), toastX + PAD_X, textY, color, true);
            }
        }

        /** Always-show-heard chip — quiet, persistent display of the last heard phrase.
         *  Used for tuning recognition; doesn't fade like the toast / miss / suggestion chips. */
        private static void drawHeardChip(GuiGraphics g, Font font, int anchorX, int anchorY,
                                           String heard) {
            String text = "» " + (heard.length() > 28 ? heard.substring(0, 27) + "…" : heard);
            int toastW = PAD_X + font.width(text) + PAD_X;
            int x = alignX(anchorX, toastW);
            drawChip(g, x, anchorY, toastW, CHIP_H, 0.55f);
            int color = withAlpha(VoiceSpellsConfig.cTextMuted, 0.85f);
            int textY = anchorY + (CHIP_H - 8) / 2;
            g.drawString(font, Component.literal(text), x + PAD_X, textY, color, true);
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
            int x = alignX(anchorX, toastW);
            drawChip(g, x, anchorY, toastW, CHIP_H, alpha);
            // Bright accent text — this is actionable, treat it differently from a miss toast.
            int color = withAlpha(Theme.C_HL, alpha);
            int textY = anchorY + (CHIP_H - 8) / 2;
            g.drawString(font, Component.literal(text), x + PAD_X, textY, color, true);
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
            int x = alignX(anchorX, toastW);
            drawChip(g, x, anchorY, toastW, CHIP_H, 0.75f);
            int color = withAlpha(VoiceSpellsConfig.cTextToast, 0.85f);
            int textY = anchorY + (CHIP_H - 8) / 2;
            g.drawString(font, Component.literal(text), x + PAD_X, textY, color, true);
        }

        /** @return true only if a toast was actually drawn, so the caller knows whether the slot
         *  it reserved is occupied. False for the whole span between misses — nearly always. */
        private static boolean drawMissToastIfActive(GuiGraphics g, Font font, int anchorX, int anchorY) {
            String heard = VoiceController.lastMissText();
            long t = VoiceController.lastMissNanos();
            if (heard == null || heard.isEmpty() || t == 0L) return false;
            long elapsed = System.nanoTime() - t;
            if (elapsed >= VoiceController.TOAST_DURATION_NANOS) return false;

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
            int x = alignX(anchorX, toastW);
            drawChip(g, x, anchorY, toastW, CHIP_H, alpha * 0.85f);
            int color = withAlpha(VoiceSpellsConfig.cTextMuted, alpha);
            g.drawString(font, Component.literal(text), x + PAD_X,
                anchorY + (CHIP_H - 8) / 2, color, true);
            return true;
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
