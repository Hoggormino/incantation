package com.niko.voicespells.spells;

import com.niko.voicespells.VoiceSpells;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * One-shot startup probe of every Iron's Spells / Curios member this mod reaches by reflection.
 * If a future update of those mods renames or reshapes the API, the recognition still loads but
 * casting would silently no-op; this turns that into a single, explicit WARN naming exactly
 * which members are missing, so the breakage is diagnosable instead of mysterious.
 *
 * Runs at common setup. If Iron's Spells isn't installed at all it stays quiet — SpellIndex
 * already reports that case.
 */
public final class ApiSelfCheck {
    private ApiSelfCheck() {}

    public static void run() {
        if (!classPresent("io.redspace.ironsspellbooks.api.registry.SpellRegistry")) {
            return; // Iron's Spells absent — not an API mismatch, nothing to verify.
        }
        List<String> missing = new ArrayList<>();

        check(missing, "SpellRegistry.getSpell(String)", () -> Class
            .forName("io.redspace.ironsspellbooks.api.registry.SpellRegistry")
            .getMethod("getSpell", String.class));
        check(missing, "SpellRegistry.REGISTRY", () -> Class
            .forName("io.redspace.ironsspellbooks.api.registry.SpellRegistry")
            .getField("REGISTRY"));

        Class<?>[] spell = new Class<?>[1];
        check(missing, "AbstractSpell", () -> spell[0] = Class
            .forName("io.redspace.ironsspellbooks.api.spells.AbstractSpell"));
        if (spell[0] != null) {
            check(missing, "AbstractSpell.getSpellId()", () -> spell[0].getMethod("getSpellId"));
            check(missing, "AbstractSpell.isEnabled()", () -> spell[0].getMethod("isEnabled"));
            check(missing, "AbstractSpell.attemptInitiateCast(...)", () -> {
                Class<?> src = Class.forName("io.redspace.ironsspellbooks.api.spells.CastSource");
                return spell[0].getMethod("attemptInitiateCast",
                    ItemStack.class, int.class, Level.class,
                    net.minecraft.world.entity.player.Player.class,
                    src, boolean.class, String.class);
            });
        }

        check(missing, "CastSource.SPELLBOOK/COMMAND", () -> {
            Class<?> src = Class.forName("io.redspace.ironsspellbooks.api.spells.CastSource");
            Enum.valueOf(src.asSubclass(Enum.class), "SPELLBOOK");
            Enum.valueOf(src.asSubclass(Enum.class), "COMMAND");
            return src;
        });

        Class<?>[] cont = new Class<?>[1];
        check(missing, "ISpellContainer", () -> cont[0] = Class
            .forName("io.redspace.ironsspellbooks.api.spells.ISpellContainer"));
        if (cont[0] != null) {
            check(missing, "ISpellContainer.isSpellContainer", () -> cont[0].getMethod("isSpellContainer", ItemStack.class));
            check(missing, "ISpellContainer.get", () -> cont[0].getMethod("get", ItemStack.class));
            check(missing, "ISpellContainer.getSpellAtIndex", () -> cont[0].getMethod("getSpellAtIndex", int.class));
            if (spell[0] != null) {
                check(missing, "ISpellContainer.getIndexForSpell", () -> cont[0].getMethod("getIndexForSpell", spell[0]));
            }
        }

        check(missing, "SpellData.getLevel/getSpell", () -> {
            Class<?> sd = Class.forName("io.redspace.ironsspellbooks.api.spells.SpellData");
            sd.getMethod("getLevel");
            sd.getMethod("getSpell");
            return sd;
        });
        check(missing, "ISpellbook", () -> Class
            .forName("io.redspace.ironsspellbooks.api.item.ISpellbook"));
        check(missing, "MagicData.getPlayerMagicData/isCasting", () -> {
            Class<?> md = Class.forName("io.redspace.ironsspellbooks.api.magic.MagicData");
            md.getMethod("getPlayerMagicData", LivingEntity.class);
            md.getMethod("isCasting");
            return md;
        });

        if (classPresent("top.theillusivec4.curios.api.CuriosApi")) {
            check(missing, "CuriosApi.getCuriosInventory", () -> Class
                .forName("top.theillusivec4.curios.api.CuriosApi")
                .getMethod("getCuriosInventory", LivingEntity.class));
            check(missing, "ICuriosItemHandler.findCurios", () -> Class
                .forName("top.theillusivec4.curios.api.type.capability.ICuriosItemHandler")
                .getMethod("findCurios", Predicate.class));
            check(missing, "SlotResult.stack/slotContext", () -> {
                Class<?> sr = Class.forName("top.theillusivec4.curios.api.SlotResult");
                sr.getMethod("stack");
                sr.getMethod("slotContext");
                return sr;
            });
        } else {
            missing.add("Curios API (top.theillusivec4.curios.api.CuriosApi) — spellbook-slot casting will not work");
        }

        if (missing.isEmpty()) {
            VoiceSpells.LOGGER.info("Iron's Spells/Curios API self-check passed");
        } else {
            VoiceSpells.LOGGER.warn("Iron's Spells/Curios API self-check found {} problem(s) — "
                + "casting may not work; the integrating mod likely changed its API:", missing.size());
            for (String m : missing) VoiceSpells.LOGGER.warn("  missing: {}", m);
        }
    }

    private interface Probe { Object get() throws Throwable; }

    private static void check(List<String> missing, String label, Probe p) {
        try {
            p.get();
        } catch (Throwable t) {
            missing.add(label + " (" + t.getClass().getSimpleName() + ")");
        }
    }

    private static boolean classPresent(String fqcn) {
        try { Class.forName(fqcn); return true; }
        catch (Throwable t) { return false; }
    }
}
