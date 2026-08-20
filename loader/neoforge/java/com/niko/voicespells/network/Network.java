package com.niko.voicespells.network;

import com.niko.voicespells.spells.SpellCaster;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

public final class Network {
    private Network() {}

    /**
     * Whether this player's LIVE connection negotiated the mod's channel - i.e. whether they
     * actually have Incantation installed right now.
     *
     * <p>This replaces inferring it from "we once received a cast packet from them". That
     * inference was wrong in both directions and it defeated the incantation rule: a player who
     * never spoke was never recorded, so {@code ALWAYS} never constrained them, and speaking was
     * what armed the lock on yourself. It was also sticky - a player who removed the mod, or
     * whose microphone died, kept the flag for the rest of the server run and lost the ability to
     * cast at all.
     *
     * <p>The payload is registered {@code optional()}, so the channel is present exactly when the
     * client has the mod. {@code NetworkRegistry.hasChannel(ICommonPacketListener,
     * ResourceLocation)} is the supported way to ask, verified against neoforge-21.1 with javap.
     */
    public static boolean hasVoiceChannel(ServerPlayer sp) {
        try {
            return sp != null && net.neoforged.neoforge.network.registration.NetworkRegistry
                .hasChannel(sp.connection, CastSpellPayload.TYPE.id());
        } catch (Throwable t) {
            // Never let a channel probe decide a player cannot play. Unknown means exempt.
            return false;
        }
    }

    public static void register(IEventBus modBus) {
        modBus.addListener(Network::onRegister);
    }

    private static void onRegister(RegisterPayloadHandlersEvent event) {
        // optional(), NOT a plain registrar. A non-optional payload makes the channel part of
        // login negotiation, so a server running Incantation disconnects every client that does
        // not have it — vanilla clients included — with an "Incompatible" screen. Nothing here
        // is clientbound, so requiring the mod on the client buys the server nothing.
        //
        // Optional means: clients that have Incantation negotiate the channel and voice-cast as
        // normal; clients that don't simply never register it, join fine, and play without voice
        // casting. Their casts are the only thing they lose.
        PayloadRegistrar r = event.registrar("1").optional();
        r.playToServer(CastSpellPayload.TYPE, CastSpellPayload.CODEC, (payload, ctx) -> {
            if (ctx.player() instanceof ServerPlayer sp) {
                ctx.enqueueWork(() -> SpellCaster.cast(sp,
                    payload.spellId(),
                    payload.volumeScale(),
                    payload.totalCasts(),
                    payload.streak()));
            }
        });
    }
}
