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
 *
 * The two count fields exist so the server can fire the {@link com.niko.voicespells.advancements.VoiceCastTrigger}
 * with accurate per-player numbers without maintaining its own persistent counter — the client
 * is the source of truth for "how many times has this player voice-cast". Trust assumption is
 * acceptable here: a malicious client can already trigger any spell it wants.
 */
public record CastSpellPayload(ResourceLocation spellId, float volumeScale,
                                int totalCasts, int streak) implements CustomPacketPayload {
    public static final Type<CastSpellPayload> TYPE = new Type<>(
        ResourceLocation.fromNamespaceAndPath(VoiceSpells.MOD_ID, "cast_spell"));

    public static final StreamCodec<RegistryFriendlyByteBuf, CastSpellPayload> CODEC = StreamCodec.composite(
        ByteBufCodecs.STRING_UTF8, p -> p.spellId.toString(),
        ByteBufCodecs.FLOAT,       CastSpellPayload::volumeScale,
        ByteBufCodecs.VAR_INT,     CastSpellPayload::totalCasts,
        ByteBufCodecs.VAR_INT,     CastSpellPayload::streak,
        // tryParse, not parse: this string arrives from the client and a modified one can send
        // anything. parse() throws on an illegal id, and it throws on the netty decode thread —
        // which disconnects the sender with an "Internal Exception" and a stack trace in the
        // server log, per attempt. An unresolvable id is harmless here because SpellCaster
        // already answers an unknown spell with voicespells.cast.unknown. The Forge decoder has
        // always done this; the two had drifted.
        (s, v, total, streak) -> {
            ResourceLocation id = ResourceLocation.tryParse(s);
            return new CastSpellPayload(
                id != null ? id : ResourceLocation.withDefaultNamespace("empty"),
                v, total, streak);
        }
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
