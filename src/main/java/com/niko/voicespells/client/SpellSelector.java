package com.niko.voicespells.client;

import com.niko.voicespells.VoiceSpells;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.PacketDistributor;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * Client-side: when a voice cast fires, also move the spellbook's <em>selected</em> spell to
 * the one we cast, so the spell bar / wheel reflects it and a follow-up manual keybind cast
 * uses the same spell.
 *
 * Iron's Spells tracks selection client-side in
 * {@code ClientMagicData.getSpellSelectionManager()} (a per-player
 * {@code SpellSelectionManager}); the server is kept in sync with a
 * {@code SelectSpellPacket}. Both are reached reflectively so we keep zero compile-time
 * coupling and fail soft if the API shifts — selection is a nicety, never block the cast on it.
 */
public final class SpellSelector {
    private static final String CLIENT_MAGIC    = "io.redspace.ironsspellbooks.player.ClientMagicData";
    private static final String SSM             = "io.redspace.ironsspellbooks.api.magic.SpellSelectionManager";
    private static final String SPELL_DATA      = "io.redspace.ironsspellbooks.api.spells.SpellData";
    private static final String SPELL_CLASS     = "io.redspace.ironsspellbooks.api.spells.AbstractSpell";
    private static final String SPELL_SELECTION = "io.redspace.ironsspellbooks.gui.overlays.SpellSelection";
    private static final String SELECT_PACKET   = "io.redspace.ironsspellbooks.network.gui.SelectSpellPacket";

    private SpellSelector() {}

    /** Select the spell at the given hotbar index in the active spellbook (1-based — the
     *  player says "spell one" for slot 0). Returns true if the selection took, false if the
     *  index is out of range or reflection failed. */
    public static boolean selectByIndex(int oneBasedIndex) {
        try {
            Class<?> cmd      = Class.forName(CLIENT_MAGIC);
            Object ssm        = cmd.getMethod("getSpellSelectionManager").invoke(null);
            if (ssm == null) return false;
            Class<?> ssmCls   = Class.forName(SSM);
            int count = (int) ssmCls.getMethod("getSpellCount").invoke(ssm);
            int zero = oneBasedIndex - 1;
            if (zero < 0 || zero >= count) return false;
            ssmCls.getMethod("makeSelection", int.class).invoke(ssm, zero);
            syncToServer(ssmCls, ssm);
            return true;
        } catch (Throwable t) {
            VoiceSpells.LOGGER.debug("Spell index selection failed: {}", t.toString());
            return false;
        }
    }

    /** Best-effort. Must be called on the client thread (touches ClientMagicData). */
    public static void select(ResourceLocation spellId) {
        try {
            Class<?> cmd      = Class.forName(CLIENT_MAGIC);
            Object ssm        = cmd.getMethod("getSpellSelectionManager").invoke(null);
            if (ssm == null) return;

            Class<?> ssmCls   = Class.forName(SSM);
            Class<?> sdCls    = Class.forName(SPELL_DATA);
            Class<?> spellCls = Class.forName(SPELL_CLASS);

            int count            = (int) ssmCls.getMethod("getSpellCount").invoke(ssm);
            Method getSpellData  = ssmCls.getMethod("getSpellData", int.class);
            Method makeSelection = ssmCls.getMethod("makeSelection", int.class);
            Method getSpell      = sdCls.getMethod("getSpell");
            Method getSpellIdM   = spellCls.getMethod("getSpellId");

            String target = spellId.toString();
            for (int i = 0; i < count; i++) {
                Object sd = getSpellData.invoke(ssm, i);
                if (sd == null) continue;
                Object spell = getSpell.invoke(sd);
                if (spell == null) continue;
                if (!target.equals(getSpellIdM.invoke(spell))) continue;

                makeSelection.invoke(ssm, i);          // updates the client HUD selection
                syncToServer(ssmCls, ssm);             // keep server selection consistent
                return;
            }
            // Spell not in any equipped spellbook slot — nothing to select, that's fine.
        } catch (Throwable t) {
            VoiceSpells.LOGGER.debug("Spell selection skipped: {}", t.toString());
        }
    }

    private static void syncToServer(Class<?> ssmCls, Object ssm) {
        try {
            Object selection = ssmCls.getMethod("getCurrentSelection").invoke(ssm);
            if (selection == null) return;
            Class<?> selCls = Class.forName(SPELL_SELECTION);
            Class<?> pktCls = Class.forName(SELECT_PACKET);
            Constructor<?> ctor = pktCls.getConstructor(selCls);
            Object packet = ctor.newInstance(selection);
            // SelectSpellPacket implements CustomPacketPayload and is registered on Iron's
            // Spells' own channel, so the vanilla distributor routes it by payload id.
            PacketDistributor.sendToServer((CustomPacketPayload) packet);
        } catch (Throwable t) {
            VoiceSpells.LOGGER.debug("Spell-selection server sync skipped: {}", t.toString());
        }
    }
}
