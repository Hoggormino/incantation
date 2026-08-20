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
/*        MinecraftForge.EVENT_BUS.addListener(ServerLifecycle::onPlayerLoggedIn);
        MinecraftForge.EVENT_BUS.addListener(ServerLifecycle::onPlayerLoggedOut);
        MinecraftForge.EVENT_BUS.addListener(ServerLifecycle::onServerStopped);
        MinecraftForge.EVENT_BUS.addListener(ServerLifecycle::onServerStarted);
*///?} else {
        NeoForge.EVENT_BUS.addListener(ServerLifecycle::onPlayerLoggedIn);
        NeoForge.EVENT_BUS.addListener(ServerLifecycle::onPlayerLoggedOut);
        NeoForge.EVENT_BUS.addListener(ServerLifecycle::onServerStopped);
        NeoForge.EVENT_BUS.addListener(ServerLifecycle::onServerStarted);
//?}
    }

    /**
     * Record whether this player actually has Incantation, by asking their live connection
     * whether it negotiated the mod's channel.
     *
     * <p>This is what the incantation rule tests before it constrains anybody. It used to be
     * inferred from having received a cast packet, which meant the rule only ever applied to
     * players who had already voice-cast since the last server boot - so ALWAYS did nothing to
     * anyone who simply never spoke, and speaking was what armed the lock on yourself.
     *
     * <p>Asked once at login rather than per cast: the channel set is fixed for the life of a
     * connection, and a probe on the pre-cast event would run several times a second per player.
     */
    private static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        try {
            if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer sp
                    && com.niko.voicespells.network.Network.hasVoiceChannel(sp)) {
                SpellCaster.noteVoiceClient(sp.getUUID());
            }
        } catch (Throwable t) {
            // A failed probe must never keep a player out of the game, and it must never be the
            // reason a spell will not cast: unknown means exempt from the rule.
            VoiceSpells.LOGGER.debug("Voice-channel probe failed at login: {}", t.toString());
        }
    }

    /**
     * Drops only the rate-limit window; see {@link SpellCaster#forgetPlayer}.
     *
     * <p>Also flushes the learned-incantation store. It is written at server stop, which covers a
     * clean shutdown and nothing else - a crash or a killed process would take back every
     * incantation learned since the server came up, and under FIRST_CAST that is progress the
     * player earned. The write is guarded by a dirty flag, so on the overwhelmingly common logout
     * where nobody learned anything it does no I/O at all.
     */
    private static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        try {
            if (event.getEntity() != null) SpellCaster.forgetPlayer(event.getEntity().getUUID());
            com.niko.voicespells.spells.SpellRules.saveStore();
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
