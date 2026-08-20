package com.niko.voicespells.network;

import net.minecraft.network.FriendlyByteBuf;
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
 *
 * <p>On 1.20.1 there is no {@code StreamCodec}; {@link #encode} and {@link #decode} are wired into
 * the {@link Network#CHANNEL} message builder by hand. Field order must match between the two.
 */
public record CastSpellPayload(ResourceLocation spellId, float volumeScale,
                                int totalCasts, int streak, boolean spoken) {

    public static void encode(CastSpellPayload msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.spellId.toString());
        buf.writeFloat(msg.volumeScale);
        buf.writeVarInt(msg.totalCasts);
        buf.writeVarInt(msg.streak);
        buf.writeBoolean(msg.spoken);
    }

    public static CastSpellPayload decode(FriendlyByteBuf buf) {
        // Untrusted input: a malformed id must not take the network thread down. The
        // ResourceLocation constructor throws on an invalid string, so parse leniently and fall
        // back to a value the server will simply fail to resolve into a spell.
        String raw = buf.readUtf();
        ResourceLocation id = ResourceLocation.tryParse(raw);
        float volume = buf.readFloat();
        int total = buf.readVarInt();
        int streak = buf.readVarInt();
        // Read only when bytes remain. spoken was added after release and the channel accepts a
        // client on an older build, whose payload is four fields long; reading unconditionally
        // would throw on the netty decode thread and disconnect them. Absent means spoken=true,
        // because the version that could send anything else is the version that writes the flag.
        boolean spoken = buf.readableBytes() > 0 ? buf.readBoolean() : true;
        return new CastSpellPayload(
            id != null ? id : new ResourceLocation("minecraft", "empty"),
            volume, total, streak, spoken);
    }
}
