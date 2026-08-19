package com.niko.voicespells;

import com.mojang.logging.LogUtils;
import com.niko.voicespells.network.Network;
import com.niko.voicespells.spells.SpellIndex;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.slf4j.Logger;

@Mod(VoiceSpells.MOD_ID)
public final class VoiceSpells {
    public static final String MOD_ID = "voicespells";
    public static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Forge 1.20.1 entry point.
     *
     * <p>Forge's {@code FMLModContainer} resolves exactly two constructor shapes —
     * {@code (FMLJavaModLoadingContext)} first, then a no-arg fallback. The NeoForge
     * {@code (IEventBus, ModContainer)} signature this class had on 1.21.1 matches neither and
     * would fail with {@code NoSuchMethodException} during CONSTRUCT, taking mod loading down
     * with it. Taking the context as a parameter also avoids {@code FMLJavaModLoadingContext.get()},
     * which current Forge 47 builds deprecate for removal.
     */
    public VoiceSpells(FMLJavaModLoadingContext context) {
        IEventBus modBus = context.getModEventBus();

        Network.register(modBus);
        com.niko.voicespells.advancements.ModTriggers.bootstrap(modBus);
        modBus.addListener(VoiceSpells::onCommonSetup);

        // Server-authoritative cast-mode config (both sides; on a dedicated server it's
        // per-world and authoritative for SpellCaster).
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, VoiceSpellsServerConfig.SERVER_SPEC);

        // Do not require this mod on the client to join a server that has it.
        //
        // The mods.toml `displayTest` key does NOT work here: it is a NeoForge-only key. A grep
        // over the whole of fmlloader-1.20.1-47.4.10 finds zero occurrences, and ModInfo's
        // constructor reads modId, namespace, version, displayName, description, logoFile,
        // logoBlur, updateJSONURL, modUrl, modproperties, features and dependencies — never
        // displayTest. NightConfig parses it and nothing ever looks at it: no error, no warning,
        // no effect. So 0.10.2 shipped this fix working on NeoForge and silently inert on Forge.
        //
        // The Forge equivalent is this extension point. IGNORESERVERONLY tells the client not to
        // compare versions, and the (remote, isServer) predicate accepting everything means a
        // server carrying this mod does not mark itself incompatible to a client without it.
        ModLoadingContext.get().registerExtensionPoint(
            net.minecraftforge.fml.IExtensionPoint.DisplayTest.class,
            () -> new net.minecraftforge.fml.IExtensionPoint.DisplayTest(
                () -> net.minecraftforge.fml.IExtensionPoint.DisplayTest.IGNORESERVERONLY,
                (remote, isServer) -> true));

        // Admin commands (/voicespells diag, follow, top) — game-bus listener.
        com.niko.voicespells.server.VoiceSpellsCommands.register();
        // Releases SpellCaster's static per-player state on logout / server stop. Matters
        // most for the integrated server: statics outlive a world, so without this one
        // world's subscribers and leaderboard leaked into the next.
        com.niko.voicespells.server.ServerLifecycle.register();
        // See the NeoForge entrypoint: same rules, same reflective hooks, Forge's bus.
        com.niko.voicespells.spells.SpellRuleEvents.register(
            net.minecraftforge.common.MinecraftForge.EVENT_BUS);

        if (FMLEnvironment.dist == Dist.CLIENT) {
            // Client-only config. Register on the mod bus so values are available before
            // FMLClientSetupEvent fires (where we preload the Vosk model).
            ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, VoiceSpellsConfig.CLIENT_SPEC);
            modBus.addListener(VoiceSpellsConfig::onConfigLoad);
            modBus.addListener(VoiceSpellsConfig::onConfigReload);

            // Everything else client-side — including the Mods-screen config extension point —
            // is wired inside ClientEvents. Nothing in this constructor may name a client-only
            // type, not even inside this dist check: Forge resolves the types a method
            // references when the method is prepared, which happens before the check runs. A
            // lambda for the config-screen factory used to live here and killed dedicated servers
            // with "Attempted to load class net/minecraft/client/gui/screens/Screen for invalid
            // dist DEDICATED_SERVER". This plain static call is safe because its descriptor
            // mentions only IEventBus.
            com.niko.voicespells.client.ClientEvents.bootstrap(modBus);
        }
    }

    private static void onCommonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(SpellIndex::initialize);
        event.enqueueWork(com.niko.voicespells.spells.ApiSelfCheck::run);
        event.enqueueWork(VoiceSpells::logIntegrationStatus);
        // On 1.20.1 the network channel is built here rather than from a dedicated registration
        // event — Forge has no RegisterPayloadHandlersEvent equivalent.
        event.enqueueWork(Network::registerMessages);
    }

    /**
     * Iron's Spells is optional. Log a compact status line so the user can see at a glance whether
     * the mod has a spell registry to listen for. Addons register into the same registry and are
     * picked up by SpellIndex automatically — no explicit per-addon check needed here.
     *
     * <p>There is no microphone check here: capture is client-only and reports its own state
     * through /voicespells devices and the HUD.
     */
    private static void logIntegrationStatus() {
        boolean iron = ModList.get().isLoaded("irons_spellbooks");
        LOGGER.info("Iron's Spells: {}", iron ? "found" : "missing");
        if (!iron) {
            LOGGER.info("No spells to listen for; Incantation will be inactive until Iron's Spells is installed.");
        }
    }
}
