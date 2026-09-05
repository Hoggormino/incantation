package com.niko.voicespells.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

/**
 * Client → server: "I heard the player say a spell name, please cast this id."
 *
 * Fields:
 *   - {@code spellId}     : which spell to cast
 *   - {@code volumeScale} : how loudly the spell was said, 0..1, relative to the client's own
 *                           calibrated speaking level. Under {@code voiceVolumeScaling} the
 *                           server scales the voice level bonus by it.
 *   - {@code totalCasts}  : client's lifetime voice-cast count (from VoiceStats)
 *   - {@code streak}      : current consecutive-cast streak (resets after inactivity)
 *
 * <p>{@code volumeScale} carries a second meaning in its SIGN: negative means the cast came from
 * the quick-recast keybind rather than from speech. See the 1.21.1 twin for why it is encoded
 * there instead of as a fifth field.
 *
 * <p>The MAGNITUDE needs care across versions, because 0.10.6 redefined what it means without
 * changing a byte of the format. Three cases, all harmless:
 *
 * <ul>
 *   <li>A pre-0.10.5 server clamps a negative magnitude to zero, as it always did, so a repeat
 *       casts at level 1 while its {@code voiceVolumeScaling} is on - a non-default option.</li>
 *   <li>A 0.10.5 server multiplies the SPELLBOOK's level by the magnitude, which is the contract
 *       it was written against. A 0.10.6 server scales only the level BONUS by it and never
 *       touches the inscribed level.</li>
 *   <li>A 0.10.5 client sends the level it read after the player had stopped speaking - close to
 *       zero - for a spoken cast, and a fixed 1.0 for a quick-recast. Against a 0.10.6 server it
 *       therefore earns almost none of the level bonus when it speaks and all of it when it
 *       repeats, until it updates. Neither is worth guarding against: the inscribed level is the
 *       floor, so nothing a client sends can make a cast weaker than the item it came from.</li>
 * </ul>
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
