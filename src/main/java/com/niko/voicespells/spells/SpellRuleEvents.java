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
 * <p>The three hooks, all of which exist with identical signatures on 1.20.1 and 1.21.1:
 * <ul>
 *   <li>{@code SpellCooldownAddedEvent$Pre} — {@code setEffectiveCooldown(int)}</li>
 *   <li>{@code ModifySpellLevelEvent} — {@code addLevels(int)}</li>
 *   <li>{@code SpellPreCastEvent} — cancellable, {@code getSpellId()}</li>
 * </ul>
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
            Player p = (Player) call(e, "getEntity");
            if (p == null) return;
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

    private static void onPreCast(Object e) {
        try {
            Object caster = call(e, "getEntity");
            if (!(caster instanceof Player p)) return;
            Object id = call(e, "getSpellId");
            if (!(id instanceof String spellId)) return;
            if (!SpellRules.blockClickedCast(p, spellId)) return;
            e.getClass().getMethod("setCanceled", boolean.class).invoke(e, true);
            SpellRules.explainBlocked(p, spellId);
        } catch (Throwable t) {
            warnOnce("incantation", t);
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
