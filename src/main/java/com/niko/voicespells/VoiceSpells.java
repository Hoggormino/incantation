package com.niko.voicespells;

import com.mojang.logging.LogUtils;
import com.niko.voicespells.network.Network;
import com.niko.voicespells.spells.SpellIndex;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.fml.loading.FMLEnvironment;
import org.slf4j.Logger;

@Mod(VoiceSpells.MOD_ID)
public final class VoiceSpells {
    public static final String MOD_ID = "voicespells";
    public static final Logger LOGGER = LogUtils.getLogger();

    public VoiceSpells(IEventBus modBus, ModContainer container) {
        Network.register(modBus);
        com.niko.voicespells.advancements.ModTriggers.bootstrap(modBus);
        modBus.addListener(VoiceSpells::onCommonSetup);

        // Server-authoritative cast-mode config (both sides; on a dedicated server it's
        // per-world and authoritative for SpellCaster).
        container.registerConfig(ModConfig.Type.SERVER, VoiceSpellsServerConfig.SERVER_SPEC);

        // Admin commands (/voicespells diag, /voicespells rank) — game-bus listener.
        com.niko.voicespells.server.VoiceSpellsCommands.register();

        if (FMLEnvironment.dist == Dist.CLIENT) {
            // Client-only config. Register on the mod bus so values are available before
            // FMLClientSetupEvent fires (where we preload the Vosk model).
            container.registerConfig(ModConfig.Type.CLIENT, VoiceSpellsConfig.CLIENT_SPEC);
            modBus.addListener(VoiceSpellsConfig::onConfigLoad);
            modBus.addListener(VoiceSpellsConfig::onConfigReload);

            // Everything else client-side — including the Mods-screen config extension point —
            // is wired inside ClientEvents. Nothing in this constructor may name a client-only
            // type, not even inside this dist check: NeoForge resolves the types a method
            // references when the method is prepared, which happens before the check runs. A
            // lambda for IConfigScreenFactory used to live here and killed dedicated servers
            // with "Attempted to load class net/minecraft/client/gui/screens/Screen for invalid
            // dist DEDICATED_SERVER". This plain static call is safe because its descriptor
            // mentions only IEventBus and ModContainer.
            com.niko.voicespells.client.ClientEvents.bootstrap(modBus, container);
        }
    }

    private static void onCommonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(SpellIndex::initialize);
        event.enqueueWork(com.niko.voicespells.spells.ApiSelfCheck::run);
        event.enqueueWork(VoiceSpells::logIntegrationStatus);
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
