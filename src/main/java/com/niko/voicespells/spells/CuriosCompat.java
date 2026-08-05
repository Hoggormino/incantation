package com.niko.voicespells.spells;

import com.niko.voicespells.VoiceSpells;
import net.minecraft.world.entity.LivingEntity;

import java.lang.reflect.Method;
import java.util.Optional;

/**
 * The one place that knows Curios changed its return type between Minecraft versions.
 *
 * <p>{@code CuriosApi.getCuriosInventory(LivingEntity)} returns
 * {@code net.minecraftforge.common.util.LazyOptional<ICuriosItemHandler>} on 1.20.1 Forge, but
 * plain {@code java.util.Optional} on 1.21.1. Everything downstream — {@code findCurios},
 * {@code SlotResult.stack()}, {@code SlotContext.identifier()} — is identical, so this single
 * unwrap is the entire difference.
 *
 * <p>Why it needs its own class rather than a cast at each call site: the access is reflective, so
 * a wrong assumption does not fail at compile time. Casting the 1.20.1 {@code LazyOptional}
 * straight to {@code Optional} throws {@code ClassCastException} at the first voice cast and takes
 * out the whole server-side cast path, while the mod loads and reports itself healthy. Worse, the
 * {@code ApiSelfCheck} and {@code Diagnostics} probes resolve the method with
 * {@code getMethod(name, LivingEntity.class)}, which ignores the return type — so they would both
 * report Curios as reachable while every cast failed.
 *
 * <p>Both shapes are accepted so the same source tree works on either version.
 */
public final class CuriosCompat {
    private CuriosCompat() {}

    /** Cached {@code LazyOptional#resolve()}, or null when the return type is a plain Optional. */
    private static volatile Method lazyResolve;
    private static volatile boolean probed = false;

    /**
     * Invoke a resolved {@code getCuriosInventory} handle and normalise whatever it returns into a
     * plain {@link Optional}. Returns {@link Optional#empty()} on any failure — callers treat that
     * as "no Curios data", which degrades to hand-only rather than blocking the cast.
     *
     * @param getCuriosInventory the reflected {@code CuriosApi.getCuriosInventory} method
     * @param player             the player whose curios inventory is wanted
     */
    public static Optional<Object> inventory(Method getCuriosInventory, LivingEntity player) {
        if (getCuriosInventory == null || player == null) return Optional.empty();
        Object raw;
        try {
            raw = getCuriosInventory.invoke(null, player);
        } catch (Throwable t) {
            VoiceSpells.LOGGER.debug("getCuriosInventory threw: {}", t.toString());
            return Optional.empty();
        }
        return unwrap(raw);
    }

    /** Normalise an {@code Optional} or a Forge {@code LazyOptional} into an {@link Optional}. */
    public static Optional<Object> unwrap(Object raw) {
        if (raw == null) return Optional.empty();
        if (raw instanceof Optional<?> o) {
            return Optional.ofNullable(o.orElse(null));
        }
        // Forge 1.20.1: LazyOptional#resolve() -> Optional<T>. Resolved reflectively so this class
        // still compiles and loads if LazyOptional is ever absent.
        try {
            if (!probed) {
                probed = true;
                lazyResolve = raw.getClass().getMethod("resolve");
            }
            Method m = lazyResolve;
            if (m == null) m = raw.getClass().getMethod("resolve");
            Object resolved = m.invoke(raw);
            if (resolved instanceof Optional<?> o) {
                return Optional.ofNullable(o.orElse(null));
            }
            return Optional.ofNullable(resolved);
        } catch (Throwable t) {
            VoiceSpells.LOGGER.debug("Could not unwrap curios inventory {}: {}",
                raw.getClass().getName(), t.toString());
            return Optional.empty();
        }
    }

    /**
     * True when {@code getCuriosInventory} returns a type this class knows how to unwrap. Used by
     * the self-check and the diagnostics screen, which previously only verified that the method
     * existed — a check that passes on both versions and therefore caught nothing.
     */
    public static boolean returnTypeSupported(Method getCuriosInventory) {
        if (getCuriosInventory == null) return false;
        String rt = getCuriosInventory.getReturnType().getName();
        return rt.equals("java.util.Optional")
            || rt.equals("net.minecraftforge.common.util.LazyOptional");
    }
}
