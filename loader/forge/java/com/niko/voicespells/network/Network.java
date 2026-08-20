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
 * <p>The channel is OPTIONAL in both directions. It used to require an exact protocol match on
 * both sides, on the reasoning that "both sides run the same build" — but that reasoning only
 * holds if every player is forced to install the mod. In practice it meant a server running
 * Incantation rejected every client without it, vanilla included, with a bare
 * "Connection closed - mismatched mod channel list". Nothing on this channel is clientbound,
 * so the requirement bought the server nothing.
 *
 * <p>{@link NetworkRegistry#acceptMissingOr} accepts either the matching protocol version or the
 * absence of the channel entirely. Clients with Incantation negotiate it and voice-cast as usual;
 * clients without it join and play normally, and simply never send a cast. This mirrors
 * {@code registrar("1").optional()} on the NeoForge side — the two must stay in step.
 */
public final class Network {
    private Network() {}

    private static final String PROTOCOL = "1";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
        new ResourceLocation(VoiceSpells.MOD_ID, "main"),
        () -> PROTOCOL,
        NetworkRegistry.acceptMissingOr(PROTOCOL),
        NetworkRegistry.acceptMissingOr(PROTOCOL)
    );

    /**
     * Whether this player's LIVE connection negotiated the mod's channel - see the 1.21.1 twin
     * for why this replaced "we once received a cast packet from them".
     *
     * <p>{@code SimpleChannel.isRemotePresent(Connection)} is the Forge equivalent, verified
     * against forge-1.20.1-47.4.10 with javap. The channel is declared with
     * {@code acceptMissingOr}, so its presence means the remote actually has the mod.
     */
    public static boolean hasVoiceChannel(ServerPlayer sp) {
        try {
            // sp.connection is the ServerGamePacketListenerImpl; its Connection is a public
            // FIELD on 1.20.1, not a getter - that accessor only arrives in 1.20.5.
            return sp != null && CHANNEL.isRemotePresent(sp.connection.connection);
        } catch (Throwable t) {
            // Never let a channel probe decide a player cannot play. Unknown means exempt.
            return false;
        }
    }

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
