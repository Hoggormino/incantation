package com.niko.voicespells.spells;

import com.niko.voicespells.VoiceSpells;
import net.minecraft.world.entity.player.Player;

import java.lang.reflect.Method;
import java.util.function.Consumer;

/**
 * Binds {@link SpellRules} to Iron's Spells' own event API, reflectively.
 *
 * <p>Reflection rather than typed imports because Iron's Spells is a SOFT dependency here — it is
 * not on the compile classpath at all, and the mod is expected to load and idle politely when it
 * is absent. Every other integration point in this codebase is reflective for the same reason, so
 * this follows the house style instead of introducing a build dependency that would have to be
 * resolvable for anyone building the mod.
 *
 * <p>Three hooks, all with identical signatures on 1.20.1 and 1.21.1:
 * <ul>
 *   <li>{@code SpellCooldownAddedEvent$Pre} — {@code setEffectiveCooldown(int)}</li>
 *   <li>{@code SpellPreCastEvent} — cancellable, {@code getSpellId()}</li>
 *   <li>{@code SpellOnCastEvent} — {@code setManaCost(int)}, posted immediately before the
 *       mana is deducted</li>
 * </ul>
 *
 * <p>{@code ModifySpellLevelEvent} is deliberately NOT among them. It exists and its
 * {@code addLevels(int)} is real, but it never fires on the path a voice cast takes — the level
 * is already fixed by the time the cast is initiated — so a hook there registered cleanly and
 * did nothing. The level bonus is applied in {@link SpellCaster} instead, where the mod owns the
 * number it passes in.
 *
 * <p>Server-side. Cooldown and spell level are gameplay, so a client cannot be trusted with them,
 * and an incantation requirement that only the client enforced would be worth nothing.
 */
public final class SpellRuleEvents {

    private SpellRuleEvents() {}

    private static final String PKG = "io.redspace.ironsspellbooks.api.events.";

    /**
     * Register the three listeners on the given bus.
     *
     * <p>Silent no-op when Iron's Spells is absent: there is nothing to hook, and a missing soft
     * dependency is a normal state for this mod rather than an error.
     *
     * @param bus the game event bus, as an Object so this class needs no loader-specific import
     */
    public static void register(Object bus) {
        try {
            Class.forName(PKG + "SpellPreCastEvent");
        } catch (ClassNotFoundException noIrons) {
            VoiceSpells.LOGGER.debug("Iron's Spells events absent; voice-advantage rules idle");
            return;
        }
        int hooked = 0;
        hooked += addListener(bus, PKG + "SpellCooldownAddedEvent$Pre", SpellRuleEvents::onCooldown) ? 1 : 0;
        hooked += addListener(bus, PKG + "SpellPreCastEvent",           SpellRuleEvents::onPreCast) ? 1 : 0;
        hooked += addListener(bus, PKG + "SpellOnCastEvent",            SpellRuleEvents::onCast)    ? 1 : 0;
        if (hooked > 0) {
            VoiceSpells.LOGGER.info("Voice-advantage rules active ({} Iron's Spells hook(s))", hooked);
        }
    }

    /** {@code bus.addListener(Class, Consumer)} without naming either bus type. */
    private static boolean addListener(Object bus, String eventClassName, Consumer<Object> handler) {
        try {
            Class<?> evt = Class.forName(eventClassName);
            for (Method m : bus.getClass().getMethods()) {
                if (!m.getName().equals("addListener")) continue;
                Class<?>[] p = m.getParameterTypes();
                // NeoForge: (Class, Consumer). Forge: (EventPriority, boolean, Class, Consumer).
                if (p.length == 2 && p[0] == Class.class && p[1] == Consumer.class) {
                    m.invoke(bus, evt, handler);
                    return true;
                }
                if (p.length == 4 && p[2] == Class.class && p[3] == Consumer.class) {
                    Object normalPriority = Enum.valueOf(
                        p[0].asSubclass(Enum.class), "NORMAL");
                    m.invoke(bus, normalPriority, false, evt, handler);
                    return true;
                }
            }
            VoiceSpells.LOGGER.warn("No usable addListener overload for {}", eventClassName);
        } catch (Throwable t) {
            VoiceSpells.LOGGER.warn("Could not hook {}: {}", eventClassName, t.toString());
        }
        return false;
    }

    // ---- handlers ----------------------------------------------------------------------------

    private static void onCooldown(Object e) {
        try {
            // getEntity(), not getPlayer(). SpellCoolddownAddedEvent has no getPlayer, so the
            // first version threw NoSuchMethodException on every cast, caught it, logged at
            // DEBUG and did nothing - the option looked implemented and was dead. Verified
            // against both jars with javap rather than assumed a second time.
            // instanceof, not a cast. getEntity() is a LivingEntity, so a spellcasting mob going
            // on cooldown threw ClassCastException into warnOnce and latched a permanent WARN on
            // any server that has them - a scary log line for a case that is simply not ours.
            if (!(call(e, "getEntity") instanceof Player p)) return;
            int base = (int) call(e, "getEffectiveCooldown");
            int scaled = SpellRules.voiceCooldown(p, base);
            if (scaled >= 0) {
                e.getClass().getMethod("setEffectiveCooldown", int.class).invoke(e, scaled);
            }
        } catch (Throwable t) {
            warnOnce("cooldown", t);
        }
    }

    // No ModifySpellLevelEvent hook.
    //
    // It was registered here and never fired on the voice-cast path: by the time
    // attemptInitiateCast runs, the level is already decided, so the bonus silently did nothing.
    // SpellCaster applies it directly to the level it is about to cast with, which is the value
    // Iron's Spells actually uses.

    /**
     * Charge the level bonus at base price.
     *
     * <p>{@code SpellOnCastEvent} is posted inside {@code castSpell} immediately before
     * {@code MagicData.setMana}, and the deduction reads {@code event.getManaCost()} - so
     * setting it here is the only point at which the cost can be changed rather than
     * compensated for afterwards. {@code setManaCost(int)} exists on both jars.
     */
    private static void onCast(Object e) {
        try {
            if (!(call(e, "getEntity") instanceof Player p)) return;
            Object id = call(e, "getSpellId");
            if (!(id instanceof String spellId)) return;
            int base = (int) call(e, "getManaCost");
            int discounted = SpellRules.discountedManaCost(p, spellId, base);
            if (discounted >= 0 && discounted != base) {
                e.getClass().getMethod("setManaCost", int.class).invoke(e, discounted);
            }
        } catch (Throwable t) {
            warnOnce("oncast", t);
        }
    }

    private static void onPreCast(Object e) {
        try {
            Object caster = call(e, "getEntity");
            if (!(caster instanceof Player p)) return;
            Object id = call(e, "getSpellId");
            if (!(id instanceof String spellId)) return;
            // Only a cast the PLAYER initiated can be required to have been spoken. A command
            // block, a datapack function or /cast produces a SpellPreCastEvent whose entity is
            // the targeted player, and cancelling those makes map mechanics stop working for
            // exactly the players who use voice - the ones the rule has switched on for. Compared
            // by name so the CastSource class is never imported; an unrecognised source is left
            // alone rather than blocked.
            String source = String.valueOf(call(e, "getCastSource"));
            if (!source.equals("SPELLBOOK") && !source.equals("SCROLL") && !source.equals("SWORD")) return;
            if (!SpellRules.blockClickedCast(p, spellId)) return;
            e.getClass().getMethod("setCanceled", boolean.class).invoke(e, true);
            clearDesignatedTarget(p);
            SpellRules.explainBlocked(p, spellId);
        } catch (Throwable t) {
            warnOnce("incantation", t);
        }
    }

    /**
     * Undo the target a refused cast just designated.
     *
     * <p>Iron's Spells picks the target inside {@code checkPreCastConditions}, which
     * {@code attemptInitiateCast} runs BEFORE it posts the cancellable pre-cast event - verified
     * in the bytecode: {@code checkPreCastConditions} at offset 87, the event constructed at 96
     * and its cancel checked at 124. So by the time this mod can refuse a cast, a targeted spell
     * has already called {@code MagicData.setAdditionalCastData} and told the player "X targeted
     * with Y".
     *
     * <p>The message cannot be unsent - it left before any listener existed. The STATE can, and
     * should: leaving it means a refused cast silently arms a target that some later cast picks
     * up, which is a worse surprise than the stray line. Roughly twenty spells use this helper -
     * Sunbeam, Telekinesis, Slow, Wololo, Ice Block, Chain Lightning among them.
     */
    private static void clearDesignatedTarget(Player p) {
        try {
            Class<?> magicDataCls = Class.forName("io.redspace.ironsspellbooks.api.magic.MagicData");
            Object md = magicDataCls
                .getMethod("getPlayerMagicData", net.minecraft.world.entity.LivingEntity.class)
                .invoke(null, p);
            if (md != null) magicDataCls.getMethod("resetAdditionalCastData").invoke(md);
        } catch (Throwable t) {
            // Best effort. A stale target is untidy, not dangerous, and must never cost a cast.
            VoiceSpells.LOGGER.debug("Could not clear the designated target: {}", t.toString());
        }
    }

    /** Latched so a broken reflective contract is reported once, loudly, instead of per cast at
     *  DEBUG - which is exactly how a dead feature went unnoticed. */
    private static final java.util.Set<String> WARNED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static void warnOnce(String hook, Throwable t) {
        if (WARNED.add(hook)) {
            VoiceSpells.LOGGER.warn("Voice-advantage {} hook is not working against this build of "
                + "Iron's Spells and has been disabled for this session: {}", hook, t.toString());
        }
    }

    /** Call a no-arg getter, searching up the hierarchy so inherited getters resolve. */
    private static Object call(Object target, String method) throws Exception {
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Method m = c.getMethod(method);
                m.setAccessible(true);
                return m.invoke(target);
            } catch (NoSuchMethodException keepLooking) { /* try the superclass */ }
        }
        throw new NoSuchMethodException(method + " on " + target.getClass());
    }
}
