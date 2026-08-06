package com.niko.voicespells.spells;

import com.niko.voicespells.VoiceSpells;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Client-side scan of the local player's actively-equipped spell containers.
 *
 * <p>Used by the "restrict to owned" feature: the controller's dispatch-time gate only lets a
 * voice cast through if its spell is in this set, so unequipped spells can't leak onto the HUD
 * as ghost casts. Only the actively-equipped slots count — main hand, off hand, and Curios —
 * mirroring Iron's Spells' own cast-time check. Imbued weapons (ISpellContainer but not
 * ISpellbook) count too. (This no longer narrows the Vosk grammar; the grammar stays broad and
 * enforcement happens at dispatch.)
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
    private static volatile Class<?> spellSlotClass;  // SpellSlot — getAllSpells() element type on current builds (may be null)
    private static volatile Method   spellSlotGetData;// SpellSlot.spellData()/getSpellData() -> SpellData

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
        // Current Iron's builds return SpellSlot[] from getAllSpells() — a record wrapping a
        // SpellData (+ slot index) — rather than SpellData[] directly. Resolve the unwrap
        // accessor (record component spellData(), or legacy getSpellData()) so addFromData can
        // peel the SpellData out. Best-effort: older builds that hand back SpellData[] leave
        // these null and addFromData uses the element as-is.
        try {
            spellSlotClass = Class.forName("io.redspace.ironsspellbooks.api.spells.SpellSlot");
            try { spellSlotGetData = spellSlotClass.getMethod("spellData"); }
            catch (NoSuchMethodException e) { spellSlotGetData = spellSlotClass.getMethod("getSpellData"); }
        } catch (Throwable t) {
            spellSlotClass = null;
            spellSlotGetData = null;
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

    /** Scan the local player's actively-equipped spell containers and collect the namespaced
     *  ids of every spell present.
     *
     *  <p>Returns {@link Optional#empty()} when the scan could not run <i>reliably</i> — Iron's
     *  reflection unavailable, no local player yet, or a reflection call threw mid-scan. The
     *  caller treats that as "no trustworthy data" and fails OPEN: a permanent reflection break
     *  (e.g. an Iron's/MC update shifting the API) must not silently block every voice cast. A
     *  present-but-<i>empty</i> set is different — it means the scan ran fine and the player
     *  genuinely has nothing castable equipped, which the caller fails CLOSED on.
     *
     *  <p>"Actively equipped" matches Iron's Spells' own cast-time check: only main hand,
     *  off hand, and Curios slots count. A spellbook sitting in the backpack (or even an
     *  unselected hotbar slot) does NOT count — casting it would fail server-side with
     *  "No spellbook or imbued weapon with X equipped," which would then leak through as a
     *  ghost cast on the HUD streak/history. */
    public static Optional<Set<String>> scan() {
        ensureReflection();
        if (ironsAbsent) return Optional.empty();
        Player p = Minecraft.getInstance().player;
        if (p == null) return Optional.empty();
        Set<String> out = new HashSet<>();
        boolean reliable = true;

        // Only the actively-held slots — main hand (current hotbar selection) + off hand.
        // The rest of the hotbar / inventory is excluded so unequipped spellbooks can't
        // trick the recognizer into dispatching a cast the server will reject.
        try {
            addSpellsFrom(p.getMainHandItem(), out);
            addSpellsFrom(p.getOffhandItem(),  out);
        } catch (Throwable t) {
            VoiceSpells.LOGGER.debug("Hand scan failed: {}", t.toString());
            reliable = false;
        }

        // Curios slots (typically the dedicated spellbook slot).
        if (curiosAvailable) {
            try {
//? if forge {
/*                // NOT a direct cast to Optional — 1.20.1 Forge returns LazyOptional here.
                Optional<Object> invOpt = CuriosCompat.inventory(curiosGetInventory, p);
*///?} else {
                @SuppressWarnings("unchecked")
                Optional<Object> invOpt = (Optional<Object>) curiosGetInventory.invoke(null, p);
//?}
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
                reliable = false;
            }
        }
        // A reflection failure mid-scan means `out` may be missing a spellbook the player
        // actually has equipped — treat the whole result as untrustworthy and fail open
        // rather than risk rejecting a spell they own.
        return reliable ? Optional.of(out) : Optional.empty();
    }

    /**
     * Is the player holding something that carries spells — a spellbook, staff or imbued weapon?
     *
     * <p>Backs {@code gatingMode = HOLD_ITEM}. Deliberately looser than {@link #scan()}: it does
     * not care <i>which</i> spells the item holds, only that the player is holding a spell focus at
     * all, so drawing your book is what opens the microphone.
     *
     * <p>Fails open on any reflective problem, matching the rest of this class — a broken probe
     * must not silently mute the mod.
     */
    public static boolean holdingSpellFocus() {
        ensureReflection();
        if (ironsAbsent) return true; // no Iron's Spells to ask; don't gate on it
        Player p = Minecraft.getInstance().player;
        if (p == null) return false;
        try {
            return isSpellContainer(p.getMainHandItem()) || isSpellContainer(p.getOffhandItem());
        } catch (Throwable t) {
            VoiceSpells.LOGGER.debug("Held-focus probe failed: {}", t.toString());
            return true;
        }
    }

    private static boolean isSpellContainer(ItemStack stack) throws Exception {
        if (stack == null || stack.isEmpty()) return false;
        return (boolean) isContainer.invoke(null, stack);
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
            // getAllSpells() yields SpellSlot[] on current Iron's builds — a record wrapping a
            // SpellData (+ slot index) — whereas getSpellAtIndex() hands back a SpellData directly.
            // Peel the SpellData out of a SpellSlot before reading the spell; tolerate both shapes
            // (older builds returning SpellData[] leave spellSlotClass null and fall straight through).
            Object spellData = data;
            if (spellSlotClass != null && spellSlotGetData != null && spellSlotClass.isInstance(data)) {
                spellData = spellSlotGetData.invoke(data);
                if (spellData == null) return; // empty slot
            }
            Object spell = getSpellFromData.invoke(spellData);
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
