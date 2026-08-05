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
        PayloadRegistrar r = event.registrar("1");
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
