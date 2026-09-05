package com.niko.voicespells.network;

import com.niko.voicespells.VoiceSpells;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
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
 * the quick-recast keybind rather than from speech. See {@link #spoken()}.
 *
 * The two count fields exist so the server can fire the {@link com.niko.voicespells.advancements.VoiceCastTrigger}
 * with accurate per-player numbers without maintaining its own persistent counter — the client
 * is the source of truth for "how many times has this player voice-cast". Trust assumption is
 * acceptable here: a malicious client can already trigger any spell it wants.
 */
public record CastSpellPayload(ResourceLocation spellId, float volumeScale,
                                int totalCasts, int streak) implements CustomPacketPayload {

    /**
     * Whether the player actually SAID this spell, as opposed to repeating it with the
     * quick-recast key. The server needs to know, because {@code incantationOnly = ALWAYS} means
     * every cast must be spoken and a repeat is not.
     *
     * <p>Encoded in the sign of {@code volumeScale} rather than as a fifth field, and that is
     * deliberate. A fifth field changes the wire format, and this channel is registered
     * {@code optional()} with {@code displayTest = IGNORE_ALL_VERSION} - so clients and servers on
     * different builds do connect to each other. A newer client sending five fields to an older
     * server leaves a trailing byte its fixed-arity codec rejects, on the netty decode thread,
     * which DISCONNECTS the player on their first cast. There is no way to fix that from the new
     * side once the format has changed.
     *
     * <p>The sign costs nothing and degrades correctly in both directions: an older client never
     * sends a negative, so it reads as spoken, which is what it always was.
     *
     * <p>The MAGNITUDE needs the same care, because 0.10.6 redefined what it means without
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
     */
    public boolean spoken() {
        return volumeScale >= 0f;
    }
    public static final Type<CastSpellPayload> TYPE = new Type<>(
        ResourceLocation.fromNamespaceAndPath(VoiceSpells.MOD_ID, "cast_spell"));

    public static final StreamCodec<RegistryFriendlyByteBuf, CastSpellPayload> CODEC = StreamCodec.composite(
        net.minecraft.network.codec.ByteBufCodecs.STRING_UTF8, p -> p.spellId.toString(),
        net.minecraft.network.codec.ByteBufCodecs.FLOAT,       CastSpellPayload::volumeScale,
        net.minecraft.network.codec.ByteBufCodecs.VAR_INT,     CastSpellPayload::totalCasts,
        net.minecraft.network.codec.ByteBufCodecs.VAR_INT,     CastSpellPayload::streak,
        // tryParse, not parse: this string arrives from the client and a modified one can send
        // anything. parse() throws on an illegal id, on the netty decode thread, which
        // disconnects the sender with a stack trace in the server log per attempt. An
        // unresolvable id is harmless because SpellCaster answers an unknown spell with
        // voicespells.cast.unknown.
        (str, v, total, streak) -> {
            ResourceLocation id = ResourceLocation.tryParse(str);
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
