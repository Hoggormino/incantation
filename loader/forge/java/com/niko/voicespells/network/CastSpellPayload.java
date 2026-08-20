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
 *
 * <p>{@code volumeScale} carries a second meaning in its SIGN: negative means the cast came from
 * the quick-recast keybind rather than from speech. See the 1.21.1 twin for why it is encoded
 * there instead of as a fifth field.
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
                                int totalCasts, int streak) {

    /** Whether the player actually SAID this spell, rather than repeating it with the
     *  quick-recast key. Encoded in the sign of volumeScale so the wire format is unchanged and
     *  a newer client cannot be disconnected by an older server. */
    public boolean spoken() {
        return volumeScale >= 0f;
    }

    public static void encode(CastSpellPayload msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.spellId.toString());
        buf.writeFloat(msg.volumeScale);
        buf.writeVarInt(msg.totalCasts);
        buf.writeVarInt(msg.streak);
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
        return new CastSpellPayload(
            id != null ? id : new ResourceLocation("minecraft", "empty"),
            volume, total, streak);
    }
}
