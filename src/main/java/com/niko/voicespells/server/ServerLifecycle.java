package com.niko.voicespells.server;

import com.niko.voicespells.VoiceSpells;
import com.niko.voicespells.spells.SpellCaster;
//? if forge {
/*import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
*///?} else {
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
//?}

/**
 * Releases the server-side state {@link SpellCaster} accumulates.
 *
 * <p>Those collections are {@code static} and were never pruned: {@code RECENT_CASTS} gained an
 * entry per player who ever cast, and {@code SUBSCRIBERS} / {@code PLAYER_TOTALS} /
 * {@code PLAYER_NAMES} grew with every distinct player seen. A comment claimed they were "cleared
 * on server stop"; nothing did that, because the mod had no lifecycle listener of any kind.
 *
 * <p>The dedicated-server leak is slow and bounded by the player count, so on its own it would be
 * a footnote. The case that actually bites is the integrated server: statics outlive a world, so
 * opening a world, quitting to the title screen and opening another carried the previous world's
 * subscribers and leaderboard into the new one. That is a correctness bug, not just memory.
 *
 * <p>Registered from the mod constructor on both loaders, next to
 * {@link VoiceSpellsCommands#register()}, which is the established pattern here — NeoForge 1.21
 * deprecated the {@code @EventBusSubscriber} bus enum, so listeners are added by hand.
 */
public final class ServerLifecycle {
    private ServerLifecycle() {}

    /** Wire this up from the mod's constructor — see VoiceSpells.java. */
    public static void register() {
//? if forge {
/*        MinecraftForge.EVENT_BUS.addListener(ServerLifecycle::onPlayerLoggedOut);
        MinecraftForge.EVENT_BUS.addListener(ServerLifecycle::onServerStopped);
        MinecraftForge.EVENT_BUS.addListener(ServerLifecycle::onServerStarted);
*///?} else {
        NeoForge.EVENT_BUS.addListener(ServerLifecycle::onPlayerLoggedOut);
        NeoForge.EVENT_BUS.addListener(ServerLifecycle::onServerStopped);
        NeoForge.EVENT_BUS.addListener(ServerLifecycle::onServerStarted);
//?}
    }

    /** Drops only the rate-limit window; see {@link SpellCaster#forgetPlayer}. */
    private static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        try {
            if (event.getEntity() != null) SpellCaster.forgetPlayer(event.getEntity().getUUID());
        } catch (Throwable t) {
            // Cleanup must never take the server down with it.
            VoiceSpells.LOGGER.debug("Logout cleanup failed: {}", t.toString());
        }
    }

    /**
     * Load the learned-incantation store for this server.
     *
     * <p>Only meaningful under the FIRST_CAST incantation rule, but loaded unconditionally: the
     * rule can be switched on mid-life, and a store that only starts recording once someone flips
     * the setting would hand every player a clean slate at exactly the wrong moment.
     */
    private static void onServerStarted(ServerStartedEvent event) {
        try {
            com.niko.voicespells.spells.SpellRules.openStore(
                event.getServer()
                     .getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                     .resolve("voicespells"));
        } catch (Throwable t) {
            VoiceSpells.LOGGER.debug("Could not open the learned-incantation store: {}", t.toString());
        }
    }

    private static void onServerStopped(ServerStoppedEvent event) {
        try {
            SpellCaster.clearServerState();
        } catch (Throwable t) {
            VoiceSpells.LOGGER.debug("Server-stop cleanup failed: {}", t.toString());
        }
    }
}
