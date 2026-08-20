package com.niko.voicespells.network;

import com.niko.voicespells.VoiceSpells;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client → server: "I heard the player say a spell name, please cast this id."
 *
 * Fields:
 *   - {@code spellId}     : which spell to cast
 *   - {@code volumeScale} : client's current audio RMS (0..1). Optional spellbook-level scaling.
 *   - {@code totalCasts}  : client's lifetime voice-cast count (from VoiceStats)
 *   - {@code streak}      : current consecutive-cast streak (resets after inactivity)
 *   - {@code spoken}      : true when the player actually said the spell; false when this is a
 *                           quick-recast of the previous one from the keybind
 *
 * The two count fields exist so the server can fire the {@link com.niko.voicespells.advancements.VoiceCastTrigger}
 * with accurate per-player numbers without maintaining its own persistent counter — the client
 * is the source of truth for "how many times has this player voice-cast". Trust assumption is
 * acceptable here: a malicious client can already trigger any spell it wants.
 */
public record CastSpellPayload(ResourceLocation spellId, float volumeScale,
                                int totalCasts, int streak, boolean spoken)
        implements CustomPacketPayload {
    public static final Type<CastSpellPayload> TYPE = new Type<>(
        ResourceLocation.fromNamespaceAndPath(VoiceSpells.MOD_ID, "cast_spell"));

    /**
     * Written by hand rather than with {@code StreamCodec.composite}, because composite has a
     * fixed arity and cannot read a field conditionally.
     *
     * <p>That matters: {@code spoken} was added after release, and the channel is registered
     * {@code optional()} with {@code displayTest = IGNORE_ALL_VERSION}, so a client on an older
     * build can connect to a newer server. Its payload is four fields long. A composite codec
     * would throw on the netty decode thread trying to read a fifth, which disconnects the
     * sender with an "Internal Exception" - the exact failure mode the tryParse note below
     * describes. Reading the flag only when bytes remain lets an old client degrade to the old
     * behaviour instead.
     *
     * <p>Absent means {@code spoken = true}: every cast an old client can send came from speech,
     * because the version that could send anything else is the version that writes the flag.
     */
    public static final StreamCodec<RegistryFriendlyByteBuf, CastSpellPayload> CODEC =
        new StreamCodec<>() {
            @Override
            public void encode(RegistryFriendlyByteBuf buf, CastSpellPayload p) {
                buf.writeUtf(p.spellId.toString());
                buf.writeFloat(p.volumeScale);
                buf.writeVarInt(p.totalCasts);
                buf.writeVarInt(p.streak);
                buf.writeBoolean(p.spoken);
            }

            @Override
            public CastSpellPayload decode(RegistryFriendlyByteBuf buf) {
                // tryParse, not parse: this string arrives from the client and a modified one can
                // send anything. parse() throws on an illegal id, on the netty decode thread,
                // which disconnects the sender with a stack trace in the server log per attempt.
                // An unresolvable id is harmless because SpellCaster already answers an unknown
                // spell with voicespells.cast.unknown.
                String raw = buf.readUtf();
                ResourceLocation id = ResourceLocation.tryParse(raw);
                float volume = buf.readFloat();
                int total = buf.readVarInt();
                int streak = buf.readVarInt();
                boolean spoken = buf.readableBytes() > 0 ? buf.readBoolean() : true;
                return new CastSpellPayload(
                    id != null ? id : ResourceLocation.withDefaultNamespace("empty"),
                    volume, total, streak, spoken);
            }
        };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
