package com.niko.voicespells.client;

import com.niko.voicespells.VoiceSpells;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
//? if !forge {
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.PacketDistributor;
//?}

import java.lang.reflect.Method;

/**
 * Casts a spell the way Iron's Spells' own keybind does — entirely from the client.
 *
 * <p>This exists so the mod does not need a server-side component. Iron's Spells accepts a
 * {@code QuickCastPacket(int slot)} from the client and handles it with
 * {@code api.util.Utils.serverSideInitiateQuickCast(ServerPlayer, int)}, which builds a fresh
 * {@code SpellSelectionManager} <b>on the server</b> from the server's own view of the player's
 * inventory, resolves the spell and its level from there, checks casting state, and then calls the
 * same {@code attemptInitiateCast} this mod used to call itself.
 *
 * <p>That makes this strictly safer than sending our own packet. The only thing the client
 * supplies is an {@code int}; the player identity comes from the authenticated connection, and the
 * spell, its level and its cast source are all read server-side. There is no field here a
 * malicious client could inflate — the worst it can do is pick a different slot of its own
 * spellbook, which pressing a key already allows.
 *
 * <p><b>The index is a list position, not an id.</b> {@code getSpellSlot(i)} is a bounds-checked
 * {@code selectionOptionList.get(i)}, and {@code getAllSpells()}, {@code getSpellCount()} and
 * {@code getSpellData(i)} all index that same list. {@code SelectionOption} also carries a public
 * {@code globalIndex} field, which is <i>not</i> the same thing and would cast the wrong spell
 * wherever the two diverge.
 *
 * <p><b>The load-bearing assumption</b> is that the client's {@code SpellSelectionManager} orders
 * its options identically to the one the server builds. Both derive from the same inventory and
 * Curios slots through the same constructor, so they should agree — but if they ever disagree, the
 * wrong spell casts, silently and plausibly. That is the reason the server fallback is kept rather
 * than deleted.
 *
 * <p>{@code QuickCastPacket} lives in {@code network.casting}, not in an {@code api} package, so it
 * is an internal class that an Iron's Spells update may rename. Everything here fails soft to let
 * the caller fall back.
 *
 * <p>Both loaders are supported. This used to return {@code false} immediately on Forge 1.20.1,
 * on the stated grounds that Iron's Spells' packets were unreachable there — but
 * {@code QuickCastPacket(int)} exists in the 1.20.1 jar (verified with javap against 3.16.2) and
 * {@code SpellSelector} was already dispatching Iron's packets on that version through the same
 * reflective route. The consequence of the early return was that a Forge client on a server
 * running Iron's Spells but NOT this mod had no cast path at all: the payload branch is skipped
 * because the server has no channel, and this branch declined, so every recognised spell was
 * silently dropped.
 */
public final class ClientCast {
    private ClientCast() {}

    private static final String SSM_CLASS =
        "io.redspace.ironsspellbooks.api.magic.SpellSelectionManager";
    private static final String QUICK_CAST_PACKET =
        "io.redspace.ironsspellbooks.network.casting.QuickCastPacket";
    private static final String SPELL_DATA =
        "io.redspace.ironsspellbooks.api.spells.SpellData";
    private static final String SPELL_CLASS =
        "io.redspace.ironsspellbooks.api.spells.AbstractSpell";

    /** Resolved once; {@code false} means this path is unavailable and callers should fall back. */
    private static volatile Boolean available = null;

    /**
     * Try to cast {@code spellId} through Iron's Spells' own client-to-server path.
     *
     * <p>Must run on the client thread — it reads the local player's inventory.
     *
     * @return {@code true} if a packet was sent, {@code false} if the spell is not in any equipped
     *         container or the reflective path is unavailable. A {@code false} means the caller
     *         should use the server-side fallback; it does <b>not</b> mean the cast failed, since
     *         nothing was sent.
     */
    public static boolean tryCast(ResourceLocation spellId) {
        if (Boolean.FALSE.equals(available)) return false;
        try {
            Player player = Minecraft.getInstance().player;
            if (player == null) return false;

            Class<?> ssmCls   = Class.forName(SSM_CLASS);
            Class<?> sdCls    = Class.forName(SPELL_DATA);
            Class<?> spellCls = Class.forName(SPELL_CLASS);

            Object ssm = ssmCls.getConstructor(Player.class).newInstance(player);
            int count = (int) ssmCls.getMethod("getSpellCount").invoke(ssm);
            if (count <= 0) return false;

            Method getSpellData = ssmCls.getMethod("getSpellData", int.class);
            Method getSpell     = sdCls.getMethod("getSpell");
            Method getSpellId   = spellCls.getMethod("getSpellId");

            String target = spellId.toString();
            for (int i = 0; i < count; i++) {
                Object sd = getSpellData.invoke(ssm, i);
                if (sd == null) continue;
                Object spell = getSpell.invoke(sd);
                if (spell == null) continue;
                if (!target.equals(getSpellId.invoke(spell))) continue;

                Class<?> pktCls = Class.forName(QUICK_CAST_PACKET);
                Object packet = pktCls.getConstructor(int.class).newInstance(i);
//? if forge {
/*                // 1.20.1 has no cross-mod payload routing, so the packet goes out through
                // Iron's Spells' own dispatcher, reached reflectively — the same mechanism
                // SpellSelector already uses to sync the spell bar.
                if (!sendIronsPacket(packet)) return false;
*///?} else {
                PacketDistributor.sendToServer((CustomPacketPayload) packet);
//?}
                available = Boolean.TRUE;
                return true;
            }
            // Spell genuinely isn't equipped. Not a failure of this path, so don't mark it
            // unavailable — the fallback will reach the same conclusion server-side.
            return false;
        } catch (Throwable t) {
            // Any reflective miss disables this path for the session rather than retrying (and
            // log-spamming) on every single cast.
            if (available == null) {
                VoiceSpells.LOGGER.warn(
                    "Iron's Spells client cast path unavailable ({}); falling back to the "
                    + "server-side cast for the rest of this session", t.toString());
            }
            available = Boolean.FALSE;
            return false;
        }
    }

//? if forge {
/*    /^*
     * Hand a packet to Iron's Spells' own dispatcher on 1.20.1, where every mod owns a private
     * SimpleChannel and there is no shared payload routing.
     *
     * @return true if a dispatcher accepted the packet.
     ^/
    private static boolean sendIronsPacket(Object packet) {
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
                    return true;
                }
            } catch (Throwable ignored) { /^ try the next candidate ^/ }
        }
        return false;
    }
*///?}

    /** Whether the client path has been proven to work this session. Null = untried. */
    public static Boolean availability() { return available; }
}
