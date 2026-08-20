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
    /** English name, always resolvable. Used as the fallback when the key has no translation. */
    public final String name;
    /**
     * Iron's Spells' own translation key for this spell, e.g. {@code spell.irons_spellbooks.fireball},
     * or empty when it could not be resolved. Prefer {@link #displayName()} over {@link #name}.
     */
    public final String nameKey;
    public final String school;
    public final int    manaCost;       // -1 if unknown
    public final int    castTimeTicks;  // -1 if unknown, 0 = instant

    private SpellInfo(String name, String nameKey, String school, int manaCost, int castTimeTicks) {
        this.name = name;
        this.nameKey = nameKey;
        this.school = school;
        this.manaCost = manaCost;
        this.castTimeTicks = castTimeTicks;
    }

    /**
     * The spell's name as a Component, resolved in whoever renders it.
     *
     * <p>This is the whole point of keeping the key instead of a string. A resolved string is
     * resolved on ONE side in ONE language: on a dedicated server that is always English, so a
     * Spanish player was told "Fireball" no matter what their game was set to, and Iron's Spells
     * already had "Bola de Fuego" sitting in its own lang file. Sending the key lets every client
     * render it in its own language, which is also the only correct answer on a mixed server.
     *
     * <p>{@code translatableWithFallback} rather than {@code translatable}: an addon that ships no
     * lang file for the player's language would otherwise render the raw key on screen. The
     * fallback is the English name we already derived. Present on both 1.20.1 and 1.21.1.
     */
    public net.minecraft.network.chat.Component displayName() {
        if (nameKey.isEmpty()) return net.minecraft.network.chat.Component.literal(name);
        return net.minecraft.network.chat.Component.translatableWithFallback(nameKey, name);
    }

    private static final SpellInfo EMPTY = new SpellInfo("", "", "", -1, -1);
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

            String name    = reflectName(spellCls, spell, spellId);
            String nameKey = reflectNameKey(spellCls, spell);
            String school  = reflectSchool(spellCls, spell);
            int mana       = reflectInt(spellCls, spell, "getManaCost");
            int castTicks  = reflectInt(spellCls, spell, "getCastTime");
            return new SpellInfo(name, nameKey, school, mana, castTicks);
        } catch (Throwable t) {
            return EMPTY;
        }
    }

    /**
     * Iron's Spells' translation key for this spell, or {@code ""}.
     *
     * <p>{@code getComponentId()} returns {@code String.format("spell.%s.%s", namespace,
     * spellName)} - verified by disassembling the method on both the 1.20.1 and 1.21.1 jars -
     * which is exactly the key its own lang files are written against
     * ({@code "spell.irons_spellbooks.fireball": "Fireball"}).
     */
    private static String reflectNameKey(Class<?> spellCls, Object spell) {
        try {
            Object o = spellCls.getMethod("getComponentId").invoke(spell);
            if (o instanceof String key && !key.isBlank()) return key;
        } catch (Throwable ignored) {}
        return "";
    }

    /**
     * The English name, used as the translation fallback.
     *
     * <p>This used to try a zero-argument {@code getDisplayName()}, which does not exist - the
     * real signature is {@code getDisplayName(Player)}. So {@code getMethod} threw
     * NoSuchMethodException on every call and the prettified path below has always been the only
     * code that ran. Keeping the path fallback and dropping the dead probe: the display name is
     * now obtained properly, as a translation key, by {@link #reflectNameKey}.
     */
    private static String reflectName(Class<?> spellCls, Object spell, String spellId) {
        // Prettify "ns:fire_ball" -> "Fire Ball"
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
