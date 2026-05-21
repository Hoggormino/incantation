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

            // "Config" button in Mods → Voice Spells opens our screen.
            container.registerExtensionPoint(
                net.neoforged.neoforge.client.gui.IConfigScreenFactory.class,
                (c, parent) -> new com.niko.voicespells.client.VoiceSpellsConfigScreen(parent));

            com.niko.voicespells.client.ClientEvents.bootstrap(modBus);
        }
    }

    private static void onCommonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(SpellIndex::initialize);
        event.enqueueWork(com.niko.voicespells.spells.ApiSelfCheck::run);
        event.enqueueWork(VoiceSpells::logIntegrationStatus);
    }

    /**
     * Both Iron's Spells and Simple Voice Chat are optional. Log a compact status line so the
     * user can see at a glance whether the mod has a mic source, a spell registry, both, or
     * neither. Iron's Spells addons register into the same registry and are picked up by
     * SpellIndex automatically — no explicit per-addon check needed here.
     */
    private static void logIntegrationStatus() {
        ModList list = ModList.get();
        boolean svc  = list.isLoaded("voicechat");
        boolean iron = list.isLoaded("irons_spellbooks");
        LOGGER.info("Integrations — Simple Voice Chat: {}, Iron's Spells: {}",
            svc ? "found" : "missing", iron ? "found" : "missing");
        if (!svc && !iron) {
            LOGGER.info("Neither integration installed; Incantation will be inactive.");
        } else if (!svc) {
            LOGGER.info("No mic source (SVC missing); spells are indexed but won't be triggered by speech.");
        } else if (!iron) {
            LOGGER.info("No spells (Iron's Spells missing); mic events are received but nothing to match against.");
        }
    }
}
