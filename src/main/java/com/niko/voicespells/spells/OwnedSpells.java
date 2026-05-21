package com.niko.voicespells.spells;

import com.niko.voicespells.VoiceSpells;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Client-side scan of the local player's inventory for spell containers.
 *
 * <p>Used by the "restrict to owned" feature: instead of letting Vosk consider every spell in
 * Iron's Spells' registry, we narrow the grammar to spells the player actually has on them
 * — Curios spellbook slot, both hands, and the hotbar. Imbued weapons (ISpellContainer but
 * not ISpellbook) count too, mirroring the server-side cast path.
 *
 * <p>All Iron's Spells / Curios access is reflective so the mod compiles and loads without
 * those mods on the classpath. Reflection resolution is cached statically; the scan itself
 * is a per-slot loop, intended to be called every ~1 second.
 */
public final class OwnedSpells {
    private static final String CONTAINER_CLS = "io.redspace.ironsspellbooks.api.spells.ISpellContainer";
    private static final String SPELL_DATA    = "io.redspace.ironsspellbooks.api.spells.SpellData";
    private static final String SPELL_CLASS   = "io.redspace.ironsspellbooks.api.spells.AbstractSpell";
    private static final String CURIOS_API    = "top.theillusivec4.curios.api.CuriosApi";
    private static final String CURIOS_HANDLER= "top.theillusivec4.curios.api.type.capability.ICuriosItemHandler";
    private static final String SLOT_RESULT   = "top.theillusivec4.curios.api.SlotResult";

    private OwnedSpells() {}

    // --- Cached reflection ---------------------------------------------------
    private static volatile boolean reflectionReady = false;
    private static volatile boolean ironsAbsent     = false;
    private static volatile Class<?> containerCls;
    private static volatile Method   isContainer;     // (ItemStack) -> boolean
    private static volatile Method   getContainer;    // (ItemStack) -> ISpellContainer
    private static volatile Method   getAllSpells;    // (container)  -> SpellData[]  -- may be null
    private static volatile Method   getActiveCount;  // (container)  -> int          -- fallback API
    private static volatile Method   getAtIndex;      // (int)        -> SpellData
    private static volatile Method   getSpellFromData;// (SpellData)  -> AbstractSpell
    private static volatile Method   getSpellId;      // (AbstractSpell) -> String

    private static volatile boolean curiosAvailable = false;
    private static volatile Method  curiosGetInventory;       // (LivingEntity) -> Optional<handler>
    private static volatile Method  curiosFindCurios;         // (handler, Predicate<ItemStack>) -> List<SlotResult>
    private static volatile Method  curiosStackGetter;        // SlotResult.stack() -> ItemStack

    private static synchronized void ensureReflection() {
        if (reflectionReady) return;
        try {
            containerCls    = Class.forName(CONTAINER_CLS);
            Class<?> dataCls= Class.forName(SPELL_DATA);
            Class<?> spellCls = Class.forName(SPELL_CLASS);
            isContainer     = containerCls.getMethod("isSpellContainer", ItemStack.class);
            getContainer    = containerCls.getMethod("get", ItemStack.class);
            // Try several spell-listing APIs; different Iron's Spells builds expose different ones.
            for (String m : new String[]{ "getAllSpells", "getActiveSpells", "getSpells" }) {
                try { getAllSpells = containerCls.getMethod(m); break; }
                catch (NoSuchMethodException ignored) {}
            }
            // Fallback path: per-index access (always present)
            try {
                getActiveCount = containerCls.getMethod("getActiveSpellCount");
            } catch (NoSuchMethodException ignored) {
                try { getActiveCount = containerCls.getMethod("getSpellCount"); }
                catch (NoSuchMethodException ignored2) {}
            }
            getAtIndex      = containerCls.getMethod("getSpellAtIndex", int.class);
            getSpellFromData= dataCls.getMethod("getSpell");
            getSpellId      = spellCls.getMethod("getSpellId");
        } catch (Throwable t) {
            ironsAbsent = true;
            VoiceSpells.LOGGER.debug("Owned-spells reflection skipped (Iron's Spells absent?): {}", t.toString());
            reflectionReady = true;
            return;
        }
        // Optional Curios resolution — we degrade to hand-only when Curios is missing.
        try {
            Class<?> capiCls   = Class.forName(CURIOS_API);
            Class<?> handCls   = Class.forName(CURIOS_HANDLER);
            Class<?> slotCls   = Class.forName(SLOT_RESULT);
            curiosGetInventory = capiCls.getMethod("getCuriosInventory", LivingEntity.class);
            curiosFindCurios   = handCls.getMethod("findCurios", Predicate.class);
            curiosStackGetter  = slotCls.getMethod("stack");
            curiosAvailable    = true;
        } catch (Throwable t) {
            curiosAvailable = false;
        }
        reflectionReady = true;
    }

    /** Scan the local player's reachable spell containers and collect the namespaced ids of
     *  every spell present. Empty set on any failure — caller treats that as "no restriction
     *  data available" so listening doesn't silently break. */
    public static Set<String> scan() {
        ensureReflection();
        if (ironsAbsent) return Collections.emptySet();
        Player p = Minecraft.getInstance().player;
        if (p == null) return Collections.emptySet();
        Set<String> out = new HashSet<>();

        // Main hand, off hand, full hotbar + inventory. We scan everything reachable so a
        // spellbook in slot 12 is still considered "owned" — matches server-side intent.
        try {
            for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
                ItemStack s = p.getInventory().getItem(i);
                addSpellsFrom(s, out);
            }
            addSpellsFrom(p.getOffhandItem(), out);
        } catch (Throwable t) {
            VoiceSpells.LOGGER.debug("Inventory scan failed: {}", t.toString());
        }

        // Curios slots (typically the dedicated spellbook slot).
        if (curiosAvailable) {
            try {
                @SuppressWarnings("unchecked")
                Optional<Object> invOpt = (Optional<Object>) curiosGetInventory.invoke(null, p);
                if (invOpt.isPresent()) {
                    Object handler = invOpt.get();
                    Predicate<ItemStack> isAnySpellContainer = stack -> {
                        try {
                            return stack != null && !stack.isEmpty()
                                && (boolean) isContainer.invoke(null, stack);
                        } catch (Throwable t) { return false; }
                    };
                    @SuppressWarnings("unchecked")
                    java.util.List<Object> results = (java.util.List<Object>)
                        curiosFindCurios.invoke(handler, isAnySpellContainer);
                    for (Object slotResult : results) {
                        ItemStack stack = (ItemStack) curiosStackGetter.invoke(slotResult);
                        addSpellsFrom(stack, out);
                    }
                }
            } catch (Throwable t) {
                VoiceSpells.LOGGER.debug("Curios scan failed: {}", t.toString());
            }
        }
        return out;
    }

    private static void addSpellsFrom(ItemStack stack, Set<String> sink) {
        if (stack == null || stack.isEmpty()) return;
        try {
            if (!(boolean) isContainer.invoke(null, stack)) return;
            Object container = getContainer.invoke(null, stack);
            if (container == null) return;
            // Prefer the bulk listing API; fall back to per-index iteration.
            if (getAllSpells != null) {
                Object raw = getAllSpells.invoke(container);
                if (raw instanceof Object[] arr) {
                    for (Object data : arr) addFromData(data, sink);
                    return;
                }
                if (raw instanceof Iterable<?> iter) {
                    for (Object data : iter) addFromData(data, sink);
                    return;
                }
            }
            if (getActiveCount != null) {
                int n = ((Number) getActiveCount.invoke(container)).intValue();
                for (int i = 0; i < n; i++) {
                    Object data = getAtIndex.invoke(container, i);
                    addFromData(data, sink);
                }
            }
        } catch (Throwable t) {
            // Individual stack scan failures are non-fatal — skip and continue.
        }
    }

    private static void addFromData(Object data, Set<String> sink) {
        if (data == null) return;
        try {
            Object spell = getSpellFromData.invoke(data);
            if (spell == null) return;
            Object id = getSpellId.invoke(spell);
            if (id != null) {
                String s = id.toString();
                // Some builds return the bare "none" sentinel — never include that.
                if (!s.isEmpty() && !"irons_spellbooks:none".equals(s)) sink.add(s);
            }
        } catch (Throwable ignored) {}
    }
}
