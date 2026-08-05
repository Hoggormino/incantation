package com.niko.voicespells.network;

import com.niko.voicespells.VoiceSpells;
import com.niko.voicespells.spells.SpellCaster;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

/**
 * Forge 1.20.1 networking.
 *
 * <p>1.20.1 predates {@code CustomPacketPayload} / {@code StreamCodec} entirely, so the payload is
 * carried by a {@link SimpleChannel} with hand-written encode/decode instead of a generated codec,
 * and there is no {@code RegisterPayloadHandlersEvent} — the channel is a static singleton and
 * messages are registered from common setup (see {@code VoiceSpells#onCommonSetup}).
 *
 * <p>The protocol version string is compared between client and server on connect. Both sides run
 * the same build, so an exact match is required in both directions; a mismatched pair is rejected
 * with a clear message rather than desyncing.
 */
public final class Network {
    private Network() {}

    private static final String PROTOCOL = "1";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
        new ResourceLocation(VoiceSpells.MOD_ID, "main"),
        () -> PROTOCOL,
        PROTOCOL::equals,
        PROTOCOL::equals
    );

    /** Kept for symmetry with the 1.21.1 entry point; the channel itself needs no bus listener. */
    public static void register(IEventBus modBus) {
        // No-op on Forge 1.20.1. Message registration happens in registerMessages(), called from
        // FMLCommonSetupEvent — NetworkRegistry has no registration event to listen for.
    }

    private static boolean registered = false;

    /** Idempotent: common setup can fire more than once in a dev workspace. */
    public static synchronized void registerMessages() {
        if (registered) return;
        registered = true;

        CHANNEL.messageBuilder(CastSpellPayload.class, 0)
            .encoder(CastSpellPayload::encode)
            .decoder(CastSpellPayload::decode)
            .consumerMainThread((payload, ctx) -> {
                // consumerMainThread already enqueues onto the server thread and calls
                // setPacketHandled(true) for us.
                ServerPlayer sp = ctx.get().getSender();
                if (sp != null) {
                    SpellCaster.cast(sp,
                        payload.spellId(),
                        payload.volumeScale(),
                        payload.totalCasts(),
                        payload.streak());
                }
            })
            .add();
    }

    /** Client → server. Mirrors {@code PacketDistributor.sendToServer} on 1.21.1. */
    public static void sendToServer(CastSpellPayload payload) {
        CHANNEL.send(PacketDistributor.SERVER.noArg(), payload);
    }
}
