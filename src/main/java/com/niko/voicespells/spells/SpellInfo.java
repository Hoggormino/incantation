package com.niko.voicespells.spells;

import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lazy reflective metadata for an Iron's Spells spell — name, school, mana cost, cast time.
 * Cached per spell id so the spell-list tooltip can read from this every frame without
 * incurring repeated reflection cost.
 *
 * Every field is best-effort: if a method is missing or throws, the corresponding field is
 * empty / {@code -1} and the tooltip simply omits that line. Iron's Spells API has churned
 * across versions, so we try a couple of likely method names per field.
 */
public final class SpellInfo {
    public final String name;
    public final String school;
    public final int    manaCost;       // -1 if unknown
    public final int    castTimeTicks;  // -1 if unknown, 0 = instant

    private SpellInfo(String name, String school, int manaCost, int castTimeTicks) {
        this.name = name;
        this.school = school;
        this.manaCost = manaCost;
        this.castTimeTicks = castTimeTicks;
    }

    private static final SpellInfo EMPTY = new SpellInfo("", "", -1, -1);
    private static final Map<String, SpellInfo> CACHE = new ConcurrentHashMap<>();

    public static SpellInfo of(String spellId) {
        if (spellId == null || spellId.isEmpty()) return EMPTY;
        return CACHE.computeIfAbsent(spellId, SpellInfo::reflect);
    }

    /** Clear the cache — call after a config reload in case custom phrases changed semantics. */
    public static void clearCache() {
        CACHE.clear();
    }

    private static SpellInfo reflect(String spellId) {
        try {
            Class<?> registryCls = Class.forName("io.redspace.ironsspellbooks.api.registry.SpellRegistry");
            Class<?> spellCls    = Class.forName("io.redspace.ironsspellbooks.api.spells.AbstractSpell");
            Method getSpell = registryCls.getMethod("getSpell", String.class);
            Object spell = getSpell.invoke(null, spellId);
            if (spell == null) return EMPTY;
            // Iron's Spells returns a "NoneSpell" sentinel for unknown ids; verify by id.
            String actualId = (String) spellCls.getMethod("getSpellId").invoke(spell);
            if (!spellId.equals(actualId)) return EMPTY;

            String name   = reflectName(spellCls, spell, spellId);
            String school = reflectSchool(spellCls, spell);
            int mana      = reflectInt(spellCls, spell, "getManaCost");
            int castTicks = reflectInt(spellCls, spell, "getCastTime");
            return new SpellInfo(name, school, mana, castTicks);
        } catch (Throwable t) {
            return EMPTY;
        }
    }

    /** Try {@code getSpellName()} (returns Component) and fall back to a prettified id. */
    private static String reflectName(Class<?> spellCls, Object spell, String spellId) {
        try {
            Method m = spellCls.getMethod("getDisplayName");
            Object o = m.invoke(spell);
            if (o != null) {
                try {
                    Method getStr = o.getClass().getMethod("getString");
                    Object s = getStr.invoke(o);
                    if (s instanceof String str && !str.isBlank()) return str;
                } catch (NoSuchMethodException ignored) { /* not a Component */ }
                if (o instanceof CharSequence cs && cs.length() > 0) return cs.toString();
            }
        } catch (Throwable ignored) {}
        // Fallback: prettify "ns:fire_ball" → "Fire Ball"
        int colon = spellId.indexOf(':');
        String path = colon >= 0 ? spellId.substring(colon + 1) : spellId;
        StringBuilder sb = new StringBuilder(path.length());
        boolean capNext = true;
        for (char c : path.toCharArray()) {
            if (c == '_') { sb.append(' '); capNext = true; continue; }
            sb.append(capNext ? Character.toUpperCase(c) : c);
            capNext = false;
        }
        return sb.toString();
    }

    private static String reflectSchool(Class<?> spellCls, Object spell) {
        try {
            Method m = spellCls.getMethod("getSchoolType");
            Object school = m.invoke(spell);
            if (school == null) return "";
            // SchoolType API has churned across Iron's Spells versions. Try each known accessor
            // in priority order until one yields something usable. Records/enums fall through
            // to toString() as a last resort.
            for (String acc : new String[]{
                    "getId", "getSchoolId", "id",
                    "getName", "name",
                    "getSerializedName", "getDescriptionId" }) {
                try {
                    Method g = school.getClass().getMethod(acc);
                    Object val = g.invoke(school);
                    if (val == null) continue;
                    String s = val.toString();
                    if (s.isEmpty()) continue;
                    return cleanSchoolName(s);
                } catch (NoSuchMethodException nsm) { /* try next */ }
            }
            // Enum subclasses expose name() — captured above via "name", but defensive recheck.
            if (school instanceof Enum<?> e) {
                return cleanSchoolName(e.name());
            }
            // toString() as a last resort. Most JVM "Object@hash" garbage gets stripped below.
            return cleanSchoolName(school.toString());
        } catch (Throwable ignored) {}
        return "";
    }

    /** Normalise whatever the reflection produced into a clean lowercase short name —
     *  strip the namespace prefix, lop off any Java {@code @abcd1234} object-hash suffix,
     *  and turn underscores into spaces. "irons_spellbooks:fire" → "fire". */
    private static String cleanSchoolName(String raw) {
        if (raw == null) return "";
        String s = raw;
        int colon = s.indexOf(':');
        if (colon >= 0) s = s.substring(colon + 1);
        int at = s.indexOf('@');
        if (at >= 0) s = s.substring(0, at);
        return s.toLowerCase(Locale.ROOT).replace('_', ' ').trim();
    }

    /** Try {@code name(int)} first (level=1), then {@code name()}. -1 if neither works. */
    private static int reflectInt(Class<?> spellCls, Object spell, String methodName) {
        try {
            Method m = spellCls.getMethod(methodName, int.class);
            Object r = m.invoke(spell, 1);
            if (r instanceof Number n) return n.intValue();
        } catch (NoSuchMethodException ignored) {
            try {
                Method m = spellCls.getMethod(methodName);
                Object r = m.invoke(spell);
                if (r instanceof Number n) return n.intValue();
            } catch (Throwable t) { /* give up */ }
        } catch (Throwable ignored) {}
        return -1;
    }

    /** Cast time in seconds, or "instant" if 0. Empty if unknown. */
    public String castTimeFormatted() {
        if (castTimeTicks < 0) return "";
        if (castTimeTicks == 0) return "instant";
        return String.format(Locale.ROOT, "%.1fs", castTimeTicks / 20f);
    }

    /**
     * Reflectively call {@code AbstractSpell.getUniqueInfo(level, caster)} to get the rich
     * per-spell description Iron's Spells shows in its own spellbook UI. Used by the spell-list
     * tooltip to enrich the hover card with damage/range/duration etc., whatever the mod
     * authors decided to surface.
     *
     * Not cached — getUniqueInfo can be locale- and caster-sensitive. Single call per hover
     * frame is cheap enough.
     */
    public static java.util.List<String> descriptionFor(String spellId, net.minecraft.world.entity.LivingEntity caster) {
        if (spellId == null || spellId.isEmpty()) return java.util.List.of();
        try {
            Class<?> registryCls = Class.forName("io.redspace.ironsspellbooks.api.registry.SpellRegistry");
            Class<?> spellCls    = Class.forName("io.redspace.ironsspellbooks.api.spells.AbstractSpell");
            Object spell = registryCls.getMethod("getSpell", String.class).invoke(null, spellId);
            if (spell == null) return java.util.List.of();
            // Try the (level, LivingEntity) signature first; some versions just take int.
            java.util.List<?> infos = null;
            try {
                java.lang.reflect.Method m = spellCls.getMethod("getUniqueInfo",
                    int.class, net.minecraft.world.entity.LivingEntity.class);
                infos = (java.util.List<?>) m.invoke(spell, 1, caster);
            } catch (NoSuchMethodException nsm) {
                try {
                    java.lang.reflect.Method m = spellCls.getMethod("getUniqueInfo", int.class);
                    infos = (java.util.List<?>) m.invoke(spell, 1);
                } catch (NoSuchMethodException nsm2) {
                    return java.util.List.of();
                }
            }
            if (infos == null || infos.isEmpty()) return java.util.List.of();
            java.util.List<String> out = new java.util.ArrayList<>(infos.size());
            for (Object info : infos) {
                if (info == null) continue;
                try {
                    java.lang.reflect.Method getStr = info.getClass().getMethod("getString");
                    Object s = getStr.invoke(info);
                    if (s instanceof String str && !str.isBlank()) out.add(str);
                } catch (Throwable ignored) {}
            }
            return out;
        } catch (Throwable t) {
            return java.util.List.of();
        }
    }
}
