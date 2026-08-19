package com.niko.voicespells.network;

import com.niko.voicespells.spells.SpellCaster;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

public final class Network {
    private Network() {}

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
                // Receiving this at all proves the client has the mod: the payload is optional,
                // so a client without it never negotiates the channel.
                SpellCaster.noteVoiceClient(sp.getUUID());
                ctx.enqueueWork(() -> SpellCaster.cast(sp,
                    payload.spellId(),
                    payload.volumeScale(),
                    payload.totalCasts(),
                    payload.streak()));
            }
        });
    }
}
