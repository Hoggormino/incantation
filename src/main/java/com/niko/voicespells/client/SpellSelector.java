package com.niko.voicespells.client;

import com.niko.voicespells.VoiceSpells;
import net.minecraft.resources.ResourceLocation;

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
            sendIronsPacket(packet);
        } catch (Throwable t) {
            VoiceSpells.LOGGER.debug("Spell-selection server sync skipped: {}", t.toString());
        }
    }

    /**
     * Send one of Iron's Spells' own packets on Iron's Spells' own channel.
     *
     * <p>This is the one place where the 1.20.1 port genuinely cannot mirror what 1.21.1 did. On
     * 1.20.5+ networking routes by payload id, so {@code PacketDistributor.sendToServer} would
     * happily deliver another mod's {@code CustomPacketPayload}. Forge 1.20.1 has no such common
     * channel: every mod owns a private {@code SimpleChannel} and there is no way to hand a
     * foreign packet to your own. The packet therefore has to go out through Iron's Spells'
     * dispatcher, which is reached reflectively like everything else here.
     *
     * <p>Fails soft by design. Selection sync only keeps Iron's own spell bar visually in step
     * with what was just voice-cast — {@code SpellCaster} resolves and casts the spell server-side
     * on its own, so a miss here costs a cosmetic highlight, never a cast.
     */
    private static void sendIronsPacket(Object packet) {
        // Candidate dispatchers across Iron's Spells' 1.20.1 builds. First one that resolves wins.
        String[][] candidates = {
            { "io.redspace.ironsspellbooks.setup.Messages",   "sendToServer" },
            { "io.redspace.ironsspellbooks.network.Messages", "sendToServer" },
            { "io.redspace.ironsspellbooks.IronsSpellbooks",  "sendToServer" },
        };
        for (String[] c : candidates) {
            try {
                Class<?> cls = Class.forName(c[0]);
                for (Method m : cls.getMethods()) {
                    if (!m.getName().equals(c[1])) continue;
                    if (m.getParameterCount() != 1) continue;
                    if (!m.getParameterTypes()[0].isInstance(packet)
                            && m.getParameterTypes()[0] != Object.class) continue;
                    m.invoke(null, packet);
                    return;
                }
            } catch (Throwable ignored) {
                // Try the next candidate.
            }
        }
        VoiceSpells.LOGGER.debug(
            "No Iron's Spells packet dispatcher found; spell-bar selection will not sync. "
            + "Casting is unaffected.");
    }
}
