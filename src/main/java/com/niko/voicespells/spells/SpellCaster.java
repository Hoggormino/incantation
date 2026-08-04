package com.niko.voicespells.spells;

import com.niko.voicespells.VoiceSpells;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Server-side caster.
 *
 * Resolves the spoken spell against Iron's Spells' registry, then requires the player to have a
 * spellbook ({@code ISpellbook}) equipped in a Curios slot — typically the dedicated "spellbook"
 * slot — containing that spell. If found, casts via {@code AbstractSpell#attemptInitiateCast}
 * with the spellbook's stored level and {@code CastSource.SPELLBOOK}, which respects mana and
 * cooldown.
 *
 * Reflection only — keeps the mod buildable without Iron's Spells / Curios on the dev classpath
 * while the published jar treats both as hard runtime dependencies.
 */
public final class SpellCaster {
    private static final String REGISTRY_CLASS    = "io.redspace.ironsspellbooks.api.registry.SpellRegistry";
    private static final String SPELL_CLASS       = "io.redspace.ironsspellbooks.api.spells.AbstractSpell";
    private static final String CAST_SOURCE_CLASS = "io.redspace.ironsspellbooks.api.spells.CastSource";
    private static final String SPELL_CONTAINER   = "io.redspace.ironsspellbooks.api.spells.ISpellContainer";
    private static final String SPELL_DATA        = "io.redspace.ironsspellbooks.api.spells.SpellData";
    private static final String SPELLBOOK_MARKER  = "io.redspace.ironsspellbooks.api.item.ISpellbook";
    private static final String MAGIC_DATA_CLASS  = "io.redspace.ironsspellbooks.api.magic.MagicData";
    private static final String CURIOS_API        = "top.theillusivec4.curios.api.CuriosApi";
    private static final String CURIOS_HANDLER    = "top.theillusivec4.curios.api.type.capability.ICuriosItemHandler";
    private static final String CURIOS_SLOT_RES   = "top.theillusivec4.curios.api.SlotResult";

    private SpellCaster() {}

    /** Per-player rolling window of cast timestamps (nanos) for the server-side rate limiter.
     *  Pruned and checked under a per-deque lock in {@link #tryRecordCast(ServerPlayer)}. */
    private static final Map<UUID, Deque<Long>> RECENT_CASTS = new ConcurrentHashMap<>();

    /**
     * Server-side rate limit. Returns {@code true} when the cast is allowed (and records the
     * timestamp); {@code false} when the player has already cast {@code maxCastsPerSecond}
     * times in the last second and should be dropped silently. {@code maxCastsPerSecond=0}
     * disables the limit.
     */
    private static boolean tryRecordCast(ServerPlayer player) {
        int max;
        try {
            max = com.niko.voicespells.VoiceSpellsServerConfig.SERVER.maxCastsPerSecond.get();
        } catch (Throwable t) {
            return true; // config not loaded yet — fail open
        }
        if (max <= 0) return true;
        long now = System.nanoTime();
        long cutoff = now - 1_000_000_000L;
        Deque<Long> recents = RECENT_CASTS.computeIfAbsent(player.getUUID(), k -> new ArrayDeque<>());
        synchronized (recents) {
            while (!recents.isEmpty() && recents.peekFirst() < cutoff) recents.pollFirst();
            if (recents.size() >= max) return false;
            recents.addLast(now);
            return true;
        }
    }

    public static boolean cast(ServerPlayer player, ResourceLocation spellId,
                                float volumeScale, int totalCasts, int streak) {
        // Per-player whitelist gate — empty list = everyone allowed; otherwise must match the
        // player's name or UUID exactly. Cheap string check, runs before everything else.
        try {
            java.util.List<? extends String> allowed =
                com.niko.voicespells.VoiceSpellsServerConfig.SERVER.voiceAllowedPlayers.get();
            if (allowed != null && !allowed.isEmpty()) {
                String name = player.getName().getString();
                String uuid = player.getUUID().toString();
                boolean ok = false;
                for (String token : allowed) {
                    if (token == null) continue;
                    if (token.equalsIgnoreCase(name) || token.equalsIgnoreCase(uuid)) { ok = true; break; }
                }
                if (!ok) {
                    VoiceSpells.LOGGER.debug("Voice cast denied by whitelist: {}", name);
                    feedback(player, "voicespells.cast.not_allowed");
                    return false;
                }
            }
        } catch (Throwable ignored) {}

        // Server-side spell blocklist — independent of any client toggle. Stops admin-banned
        // spells from being cast via voice no matter what the client says.
        try {
            java.util.List<? extends String> blocked =
                com.niko.voicespells.VoiceSpellsServerConfig.SERVER.serverBlockedSpells.get();
            if (blocked != null && !blocked.isEmpty()) {
                String idStr = spellId.toString();
                for (String b : blocked) {
                    if (b != null && b.equalsIgnoreCase(idStr)) {
                        feedback(player, "voicespells.cast.blocked",
                            spellId.getPath().replace('_', ' '));
                        return false;
                    }
                }
            }
        } catch (Throwable ignored) {}

        // Rate-limit before any heavy reflection. Silent rejection is intentional — a noisy
        // toast on every dropped cast would itself spam the player during voice flurry.
        if (!tryRecordCast(player)) {
            VoiceSpells.LOGGER.debug("Rate-limited cast from {} ({})",
                player.getName().getString(), spellId);
            return false;
        }
        try {
            Class<?> registryClass  = Class.forName(REGISTRY_CLASS);
            Class<?> spellClass     = Class.forName(SPELL_CLASS);
            Class<?> sourceClass    = Class.forName(CAST_SOURCE_CLASS);
            Class<?> containerClass = Class.forName(SPELL_CONTAINER);
            Class<?> spellDataClass = Class.forName(SPELL_DATA);
            Class<?> spellbookMark  = Class.forName(SPELLBOOK_MARKER);

            // --- look the spell up ---
            Method getSpell = registryClass.getMethod("getSpell", String.class);
            Object spell    = getSpell.invoke(null, spellId.toString());
            if (spell == null) {
                feedback(player, "voicespells.cast.unknown");
                return false;
            }
            String spellIdActual = (String) spellClass.getMethod("getSpellId").invoke(spell);
            if (!spellId.toString().equals(spellIdActual)) {
                // SpellRegistry returns the NoneSpell sentinel for unknown ids.
                feedback(player, "voicespells.cast.unknown");
                return false;
            }

            // --- skip entirely if the player is mid-cast; calling attemptInitiateCast on an
            //     already-casting player runs Utils.serverSideCancelCast, which kills any
            //     long-cast spell that's still on the bar. ---
            if (isPlayerCasting(player)) {
                VoiceSpells.LOGGER.debug("Player {} is mid-cast; ignoring voice-triggered {}",
                    player.getName().getString(), spellId);
                return false;
            }

            var mode = com.niko.voicespells.VoiceSpellsServerConfig.SERVER.castMode.get();

            Method cast = spellClass.getMethod(
                "attemptInitiateCast",
                ItemStack.class, int.class, Level.class,
                net.minecraft.world.entity.player.Player.class,
                sourceClass, boolean.class, String.class
            );

            ItemStack castStack;
            int       castLevel;
            String    castSlot;
            Object    castSource;
            boolean   triggerCooldown;

            if (mode == com.niko.voicespells.VoiceSpellsServerConfig.CastMode.FREE) {
                // No spellbook needed: level 1, COMMAND source — no mana, no cooldown.
                castStack       = ItemStack.EMPTY;
                castLevel       = 1;
                castSlot        = "mainhand";
                @SuppressWarnings({"unchecked", "rawtypes"})
                Object cmd      = Enum.valueOf((Class<Enum>) sourceClass, "COMMAND");
                castSource      = cmd;
                triggerCooldown = false;
            } else {
                // Spellbook required. Detection order:
                //   1. If Curios is loaded, the Curios spellbook slot (always tried first).
                //   2. ANY_SPELLBOOK: also mainhand/offhand.
                //   3. Curios not loaded: degrade to hand-only check regardless of mode, so the
                //      mod still functions on installs where Curios is absent.
                Method isContainer   = containerClass.getMethod("isSpellContainer", ItemStack.class);
                Method getContainer  = containerClass.getMethod("get", ItemStack.class);
                Method indexForSpell = containerClass.getMethod("getIndexForSpell", spellClass);
                Method getAtIndex    = containerClass.getMethod("getSpellAtIndex", int.class);
                Method getLevel      = spellDataClass.getMethod("getLevel");

                boolean curiosAvailable;
                try {
                    Class.forName(CURIOS_API);
                    curiosAvailable = true;
                } catch (ClassNotFoundException missingCurios) {
                    curiosAvailable = false;
                }

                SlotMatch match = curiosAvailable
                    ? findEquippedSpellbook(player, spell, spellbookMark,
                        isContainer, getContainer, indexForSpell, getAtIndex, getLevel)
                    : null;
                boolean tryHands =
                    mode == com.niko.voicespells.VoiceSpellsServerConfig.CastMode.ANY_SPELLBOOK
                    || !curiosAvailable;
                if (match == null && tryHands) {
                    match = findHeldSpellbook(player, spell, spellbookMark,
                        isContainer, getContainer, indexForSpell, getAtIndex, getLevel);
                }
                // Imbued weapons (swords/staves with spells embedded) carry an ISpellContainer
                // but are NOT ISpellbook. Always check hands for those so a player wielding an
                // imbued sword can voice-cast the imbued spell just like they'd right-click it.
                if (match == null) {
                    match = findHeldImbued(player, spell, spellbookMark,
                        isContainer, getContainer, indexForSpell, getAtIndex, getLevel);
                }
                if (match == null) {
                    // If we're in CURIO_SPELLBOOK mode and the player actually has the book
                    // on a hotbar/hand slot, tell them precisely that — saves a "wait, I have
                    // a spellbook!" support cycle.
                    if (mode == com.niko.voicespells.VoiceSpellsServerConfig.CastMode.CURIO_SPELLBOOK
                            && curiosAvailable) {
                        SlotMatch elsewhere = findHeldSpellbook(player, spell, spellbookMark,
                            isContainer, getContainer, indexForSpell, getAtIndex, getLevel);
                        if (elsewhere != null) {
                            feedback(player, "voicespells.cast.not_in_curios",
                                spellId.getPath().replace('_', ' '));
                            return false;
                        }
                    }
                    feedback(player, "voicespells.cast.no_spellbook",
                        spellId.getPath().replace('_', ' '));
                    return false;
                }
                castStack       = match.stack;
                castLevel       = match.level;
                castSlot        = match.slot;
                // Optional volume scaling: whisper -> level 1, shout -> level N. Clamps to
                // [1, spellbookLevel]; only active when the server config opts in.
                try {
                    if (com.niko.voicespells.VoiceSpellsServerConfig.SERVER.voiceVolumeScaling.get()
                            && match.level > 1) {
                        float clamped = Math.max(0f, Math.min(1f, volumeScale));
                        int scaled = Math.max(1, Math.round(match.level * clamped));
                        castLevel = scaled;
                    }
                } catch (Throwable ignored) {}
                // Imbued weapons cast via CastSource.SWORD — Iron's Spells treats them the
                // same as a player right-clicking the weapon, which respects per-weapon
                // cooldowns and the imbued-spell mana cost. Spellbooks use SPELLBOOK. If a
                // future Iron's Spells renames or removes SWORD we degrade to SPELLBOOK so
                // the cast still goes through rather than throwing.
                String sourceName = match.sourceName != null ? match.sourceName : "SPELLBOOK";
                Object src;
                try {
                    @SuppressWarnings({"unchecked", "rawtypes"})
                    Object resolved = Enum.valueOf((Class<Enum>) sourceClass, sourceName);
                    src = resolved;
                } catch (IllegalArgumentException unknownEnum) {
                    @SuppressWarnings({"unchecked", "rawtypes"})
                    Object fallback = Enum.valueOf((Class<Enum>) sourceClass, "SPELLBOOK");
                    src = fallback;
                }
                castSource      = src;
                triggerCooldown = true;
            }

            // Pre-flight: in spellbook modes the cast costs mana and triggers a cooldown.
            // attemptInitiateCast itself will silently fail when either's not satisfied; rather
            // than spamming the generic "Could not cast" toast we tell the player exactly why.
            if (mode != com.niko.voicespells.VoiceSpellsServerConfig.CastMode.FREE) {
                String reason = preflightCheck(player, spell, spellId, castLevel, spellClass);
                if (reason != null) {
                    feedback(player, "voicespells.cast." + reason,
                        spellId.getPath().replace('_', ' '));
                    return false;
                }
            }

            boolean ok = (boolean) cast.invoke(
                spell, castStack, castLevel, player.level(), player,
                castSource, triggerCooldown, castSlot);
            if (!ok) {
                feedback(player, "voicespells.cast.failed",
                    spellId.getPath().replace('_', ' '));
            } else {
                appendCastLog(player, spellId);
                fireVoiceCastTrigger(player, spell, spellClass, totalCasts, streak);
                broadcastNearby(player, spellId);
                recordPlayerTotal(player.getUUID(), player.getName().getString(), totalCasts);
            }
            return ok;
        } catch (ClassNotFoundException missing) {
            VoiceSpells.LOGGER.error("Required mod class not present at cast time: {}", missing.getMessage());
            feedback(player, "voicespells.cast.no_iron_spells");
            return false;
        } catch (Throwable t) {
            // Everything above runs through reflection, so anything thrown inside Iron's Spells —
            // or inside another mod's mixin on attemptInitiateCast — arrives wrapped in an
            // InvocationTargetException. Reporting the wrapper told the player literally nothing
            // ("Cast Error: InvocationTargetException") and hid which mod actually failed; one
            // reported instance took weeks to trace to a third-party addon. Unwrap to the real
            // cause for both the log and the on-screen message.
            Throwable cause = t;
            while (cause instanceof java.lang.reflect.InvocationTargetException
                    && cause.getCause() != null) {
                cause = cause.getCause();
            }
            VoiceSpells.LOGGER.error("Cast failed for {} — {}: {}", spellId,
                cause.getClass().getName(), cause.getMessage(), cause);
            VoiceSpells.LOGGER.error(
                "If the class above belongs to another mod, this is a conflict in that mod's "
                + "hook on Iron's Spells' cast path, not in Incantation itself.");
            String detail = cause.getMessage() == null || cause.getMessage().isBlank()
                ? cause.getClass().getSimpleName()
                : cause.getClass().getSimpleName() + ": " + cause.getMessage();
            if (detail.length() > 90) detail = detail.substring(0, 90) + "…";
            feedback(player, "voicespells.cast.error", detail);
            return false;
        }
    }

    /**
     * Walks the player's Curios inventory looking for the first {@code ISpellbook} stack whose
     * spell container has an entry for the cast spell. Iteration order is whatever Curios
     * returns — for vanilla setups that means the dedicated "spellbook" slot first because it's
     * the only slot a spellbook can occupy.
     */
    private static SlotMatch findEquippedSpellbook(
            ServerPlayer player, Object spell, Class<?> spellbookMark,
            Method isContainer, Method getContainer, Method indexForSpell,
            Method getAtIndex, Method getLevel) throws ReflectiveOperationException {
        Class<?> curiosApi      = Class.forName(CURIOS_API);
        Class<?> curiosHandler  = Class.forName(CURIOS_HANDLER);
        Class<?> slotResultCls  = Class.forName(CURIOS_SLOT_RES);

        Method getInventory = curiosApi.getMethod("getCuriosInventory", LivingEntity.class);
        // NOT a direct cast to Optional — on 1.20.1 Forge this returns LazyOptional. See
        // CuriosCompat for why that difference is invisible until the first cast fails.
        Optional<Object> invOpt = CuriosCompat.inventory(getInventory, player);
        if (invOpt.isEmpty()) return null;
        Object handler = invOpt.get();

        Predicate<ItemStack> isSpellbookStack = s -> s != null && !s.isEmpty()
            && spellbookMark.isInstance(s.getItem());

        Method findCurios = curiosHandler.getMethod("findCurios", Predicate.class);
        @SuppressWarnings("unchecked")
        List<Object> results = (List<Object>) findCurios.invoke(handler, isSpellbookStack);

        Method stackGetter = slotResultCls.getMethod("stack");
        Method slotCtxGetter = slotResultCls.getMethod("slotContext");
        Method identifierGetter = slotCtxGetter.getReturnType().getMethod("identifier");

        for (Object slotResult : results) {
            ItemStack stack = (ItemStack) stackGetter.invoke(slotResult);
            if (stack == null || stack.isEmpty()) continue;
            if (!(boolean) isContainer.invoke(null, stack)) continue;

            Object container = getContainer.invoke(null, stack);
            if (container == null) continue;
            int idx = (int) indexForSpell.invoke(container, spell);
            if (idx < 0) continue;

            Object data = getAtIndex.invoke(container, idx);
            if (data == null) continue;
            int level = (int) getLevel.invoke(data);

            String slotId;
            try {
                Object ctx = slotCtxGetter.invoke(slotResult);
                slotId = (String) identifierGetter.invoke(ctx);
            } catch (Throwable t) {
                slotId = "spellbook";
            }
            return new SlotMatch(stack, slotId, level);
        }
        return null;
    }

    /** ANY_SPELLBOOK fallback: a spellbook held in main/off hand <em>or</em> anywhere on the
     *  hotbar that contains the spell. Mainhand wins over offhand wins over hotbar so a
     *  player can manage which spellbook is "active" by holding it. */
    private static SlotMatch findHeldSpellbook(
            ServerPlayer player, Object spell, Class<?> spellbookMark,
            Method isContainer, Method getContainer, Method indexForSpell,
            Method getAtIndex, Method getLevel) throws ReflectiveOperationException {
        // Priority list of (stack, slotId).
        java.util.List<ItemStack> stacks = new java.util.ArrayList<>();
        java.util.List<String>    slots  = new java.util.ArrayList<>();
        stacks.add(player.getMainHandItem()); slots.add("mainhand");
        stacks.add(player.getOffhandItem());  slots.add("offhand");
        // Hotbar slots 0..8 — skip whichever one is mainhand to avoid double-checking it.
        int selected = player.getInventory().selected;
        for (int i = 0; i < 9; i++) {
            if (i == selected) continue;
            stacks.add(player.getInventory().getItem(i));
            slots.add("hotbar." + i);
        }
        for (int h = 0; h < stacks.size(); h++) {
            ItemStack stack = stacks.get(h);
            if (stack == null || stack.isEmpty()) continue;
            if (!spellbookMark.isInstance(stack.getItem())) continue;
            if (!(boolean) isContainer.invoke(null, stack)) continue;
            Object container = getContainer.invoke(null, stack);
            if (container == null) continue;
            int idx = (int) indexForSpell.invoke(container, spell);
            if (idx < 0) continue;
            Object data = getAtIndex.invoke(container, idx);
            if (data == null) continue;
            return new SlotMatch(stack, slots.get(h), (int) getLevel.invoke(data));
        }
        return null;
    }

    private record SlotMatch(ItemStack stack, String slot, int level, String sourceName) {
        SlotMatch(ItemStack stack, String slot, int level) { this(stack, slot, level, "SPELLBOOK"); }
    }

    /** Imbued-weapon scan. Mirrors {@link #findHeldSpellbook} but accepts items that are
     *  ISpellContainer but NOT ISpellbook — Iron's Spells swords/staves carry their imbued
     *  spell in the same container API, just on a non-book item. We return CastSource.SWORD
     *  so attemptInitiateCast goes through Iron's Spells' weapon-cast path (per-weapon
     *  cooldown + imbued mana cost, same as right-clicking the weapon). */
    private static SlotMatch findHeldImbued(
            ServerPlayer player, Object spell, Class<?> spellbookMark,
            Method isContainer, Method getContainer, Method indexForSpell,
            Method getAtIndex, Method getLevel) throws ReflectiveOperationException {
        java.util.List<ItemStack> stacks = new java.util.ArrayList<>();
        java.util.List<String>    slots  = new java.util.ArrayList<>();
        stacks.add(player.getMainHandItem()); slots.add("mainhand");
        stacks.add(player.getOffhandItem());  slots.add("offhand");
        int selected = player.getInventory().selected;
        for (int i = 0; i < 9; i++) {
            if (i == selected) continue;
            stacks.add(player.getInventory().getItem(i));
            slots.add("hotbar." + i);
        }
        for (int h = 0; h < stacks.size(); h++) {
            ItemStack stack = stacks.get(h);
            if (stack == null || stack.isEmpty()) continue;
            // Skip spellbooks here — findHeldSpellbook already handled those.
            if (spellbookMark.isInstance(stack.getItem())) continue;
            if (!(boolean) isContainer.invoke(null, stack)) continue;
            Object container = getContainer.invoke(null, stack);
            if (container == null) continue;
            int idx = (int) indexForSpell.invoke(container, spell);
            if (idx < 0) continue;
            Object data = getAtIndex.invoke(container, idx);
            if (data == null) continue;
            return new SlotMatch(stack, slots.get(h), (int) getLevel.invoke(data), "SWORD");
        }
        return null;
    }

    /**
     * Reflective mana + cooldown check. Returns {@code null} on success, or a short reason
     * string ("cooldown" / "no_mana") that maps to a translation key when blocking the cast.
     *
     * Any reflection failure is treated as "unknown" and we return null — the underlying
     * attemptInitiateCast still has the final say, so a missing API method means we just
     * fall back to its silent failure with the generic "Could not cast" toast.
     */
    private static String preflightCheck(ServerPlayer player, Object spell,
                                          ResourceLocation spellId, int level,
                                          Class<?> spellClass) {
        Object magicData;
        Class<?> magicDataCls;
        try {
            magicDataCls = Class.forName(MAGIC_DATA_CLASS);
            Method getMagicData = magicDataCls.getMethod("getPlayerMagicData", LivingEntity.class);
            magicData = getMagicData.invoke(null, player);
            if (magicData == null) return null;
        } catch (Throwable t) {
            return null;
        }

        // --- cooldown ---
        try {
            Method getCooldowns = magicDataCls.getMethod("getPlayerCooldowns");
            Object cooldowns = getCooldowns.invoke(magicData);
            if (cooldowns != null) {
                // Tries isOnCooldown(String) first (newer API), falls back to isOnCooldown(spell).
                Method isOnCooldown;
                Object arg;
                try {
                    isOnCooldown = cooldowns.getClass().getMethod("isOnCooldown", String.class);
                    arg = spellId.toString();
                } catch (NoSuchMethodException e) {
                    isOnCooldown = cooldowns.getClass().getMethod("isOnCooldown", spellClass);
                    arg = spell;
                }
                if ((boolean) isOnCooldown.invoke(cooldowns, arg)) return "cooldown";
            }
        } catch (Throwable ignored) {
            // No cooldown API — let attemptInitiateCast handle it.
        }

        // --- mana ---
        try {
            // Iron's Spells exposes both getManaCost(level) and getManaCost(level, caster). Pick
            // whichever's present; the long-arg form gives more accurate numbers for cost mods.
            Method getCost;
            Object[] costArgs;
            try {
                getCost = spellClass.getMethod("getManaCost", int.class, LivingEntity.class);
                costArgs = new Object[]{ level, player };
            } catch (NoSuchMethodException e) {
                getCost = spellClass.getMethod("getManaCost", int.class);
                costArgs = new Object[]{ level };
            }
            int cost = ((Number) getCost.invoke(spell, costArgs)).intValue();
            Method getMana = magicDataCls.getMethod("getMana");
            float mana = ((Number) getMana.invoke(magicData)).floatValue();
            if (cost > mana) return "no_mana";
        } catch (Throwable ignored) {
            // No mana API — let attemptInitiateCast handle it.
        }
        return null;
    }

    /**
     * Reflective check against {@code MagicData.isCasting()}. Returns {@code false} if Iron's
     * Spells classes can't be loaded for any reason — better to let the cast attempt through
     * than to silently swallow casts in an environment where the gate would always fire.
     */
    private static boolean isPlayerCasting(ServerPlayer player) {
        try {
            Class<?> magicDataCls = Class.forName(MAGIC_DATA_CLASS);
            Method getMagicData = magicDataCls.getMethod("getPlayerMagicData", LivingEntity.class);
            Object magicData = getMagicData.invoke(null, player);
            if (magicData == null) return false;
            Method isCasting = magicDataCls.getMethod("isCasting");
            return (boolean) isCasting.invoke(magicData);
        } catch (Throwable t) {
            VoiceSpells.LOGGER.debug("isCasting check failed: {}", t.toString());
            return false;
        }
    }

    private static void feedback(ServerPlayer player, String translationKey, Object... args) {
        player.displayClientMessage(Component.translatable(translationKey, args), true);
    }

    /** Resolve the spell's school (lowercase, no namespace) via reflection so the advancement
     *  trigger can match school-specific advancements. Empty when unavailable. */
    private static String resolveSchool(Object spell, Class<?> spellCls) {
        try {
            Object school = spellCls.getMethod("getSchoolType").invoke(spell);
            if (school == null) return "";
            for (String acc : new String[]{ "getId", "getSchoolId", "id" }) {
                try {
                    Object id = school.getClass().getMethod(acc).invoke(school);
                    if (id != null) {
                        String s = id.toString();
                        int colon = s.indexOf(':');
                        return (colon >= 0 ? s.substring(colon + 1) : s)
                            .toLowerCase(Locale.ROOT).replace('_', ' ');
                    }
                } catch (NoSuchMethodException ignored) {}
            }
        } catch (Throwable ignored) {}
        return "";
    }

    /** Fire the voicespells:voice_cast custom advancement trigger so datapack advancements
     *  unlock vanilla toasts. Best-effort — if the trigger registry didn't bind for any
     *  reason we just skip rather than break the cast. */
    private static void fireVoiceCastTrigger(ServerPlayer player, Object spell, Class<?> spellCls,
                                              int totalCasts, int streak) {
        try {
            String school = resolveSchool(spell, spellCls);
            com.niko.voicespells.advancements.ModTriggers.VOICE_CAST
                .fire(player, totalCasts, streak, school);
        } catch (Throwable t) {
            VoiceSpells.LOGGER.debug("Advancement trigger failed: {}", t.toString());
        }
    }

    /** Append-only audit log of every successful voice cast. Gated on
     *  {@link com.niko.voicespells.VoiceSpellsServerConfig.Server#logVoiceCasts} so the file
     *  doesn't fill up by default. One line per cast: ISO timestamp, player name, UUID, spell id. */
    private static void appendCastLog(ServerPlayer player, ResourceLocation spellId) {
        try {
            if (!com.niko.voicespells.VoiceSpellsServerConfig.SERVER.logVoiceCasts.get()) return;
            net.minecraft.server.MinecraftServer server = player.getServer();
            if (server == null) return;
            java.nio.file.Path logDir = server.getServerDirectory().toPath().resolve("logs");
            java.nio.file.Files.createDirectories(logDir);
            java.nio.file.Path logFile = logDir.resolve("voicespells-casts.log");
            String line = String.format(java.util.Locale.ROOT, "%s\t%s\t%s\t%s%n",
                java.time.Instant.now(),
                player.getName().getString(),
                player.getUUID(),
                spellId);
            java.nio.file.Files.writeString(logFile, line,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND);
            // Keep a small in-memory mirror for the /voicespells diag command.
            synchronized (RECENT_LOG) {
                RECENT_LOG.addFirst(line.trim());
                while (RECENT_LOG.size() > 50) RECENT_LOG.removeLast();
            }
        } catch (Throwable t) {
            VoiceSpells.LOGGER.debug("Cast log write failed: {}", t.toString());
        }
    }

    /** Server-side chat broadcast of a voice cast to nearby players. Off by default; opt-in
     *  via the server config. Useful for RP / streaming servers where the table wants to know
     *  what their teammate just yelled. */
    private static void broadcastNearby(ServerPlayer player, ResourceLocation spellId) {
        try {
            if (!com.niko.voicespells.VoiceSpellsServerConfig.SERVER.broadcastVoiceCasts.get()) return;
            int radius = com.niko.voicespells.VoiceSpellsServerConfig.SERVER.broadcastRadius.get();
            String name = player.getName().getString();
            String pretty = prettyName(spellId);
            Component msg = Component.literal("◈ ")
                .append(Component.literal(name).withStyle(net.minecraft.ChatFormatting.AQUA))
                .append(Component.literal(" cast "))
                .append(Component.literal(pretty).withStyle(net.minecraft.ChatFormatting.LIGHT_PURPLE));
            if (radius == 0) {
                player.sendSystemMessage(msg);
            } else {
                double r2 = (double) radius * radius;
                for (ServerPlayer other : player.serverLevel().players()) {
                    if (other == player) { other.sendSystemMessage(msg); continue; }
                    if (other.distanceToSqr(player) <= r2) other.sendSystemMessage(msg);
                }
            }
            // Also notify any /voicespells follow subscribers regardless of distance —
            // intended for admins watching from the lobby.
            if (!SUBSCRIBERS.isEmpty() && player.getServer() != null) {
                for (java.util.UUID uuid : SUBSCRIBERS) {
                    ServerPlayer admin = player.getServer().getPlayerList().getPlayer(uuid);
                    if (admin != null && admin != player) admin.sendSystemMessage(msg);
                }
            }
        } catch (Throwable t) {
            VoiceSpells.LOGGER.debug("Broadcast failed: {}", t.toString());
        }
    }

    /** Most recent ~50 voice cast lines, newest first. Drives /voicespells diag. */
    private static final java.util.Deque<String> RECENT_LOG = new java.util.ArrayDeque<>();
    public static java.util.List<String> recentLog() {
        synchronized (RECENT_LOG) { return new java.util.ArrayList<>(RECENT_LOG); }
    }

    /** Players who have opted in to live voice-cast notifications via {@code /voicespells follow}.
     *  Stored as UUIDs so the subscription survives reconnect. Cleared on server stop. */
    private static final java.util.Set<java.util.UUID> SUBSCRIBERS = java.util.concurrent.ConcurrentHashMap.newKeySet();
    public static boolean toggleSubscriber(java.util.UUID uuid) {
        if (SUBSCRIBERS.remove(uuid)) return false;
        SUBSCRIBERS.add(uuid);
        return true;
    }
    /** Highest total-cast count we've seen reported per player, used by /voicespells top. The
     *  count is sent by the client in {@link com.niko.voicespells.network.CastSpellPayload}
     *  and reflects the player's lifetime stats from their local {@code VoiceStats}. */
    private static final java.util.Map<java.util.UUID, Integer> PLAYER_TOTALS =
        new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<java.util.UUID, String>  PLAYER_NAMES  =
        new java.util.concurrent.ConcurrentHashMap<>();
    static void recordPlayerTotal(java.util.UUID uuid, String name, int total) {
        PLAYER_TOTALS.merge(uuid, total, Math::max);
        PLAYER_NAMES.put(uuid, name);
    }
    public static java.util.List<java.util.Map.Entry<String, Integer>> topPlayers(int limit) {
        java.util.List<java.util.Map.Entry<java.util.UUID, Integer>> entries =
            new java.util.ArrayList<>(PLAYER_TOTALS.entrySet());
        entries.sort(java.util.Map.Entry.<java.util.UUID, Integer>comparingByValue().reversed());
        java.util.List<java.util.Map.Entry<String, Integer>> out = new java.util.ArrayList<>();
        for (int i = 0; i < Math.min(limit, entries.size()); i++) {
            var e = entries.get(i);
            out.add(java.util.Map.entry(
                PLAYER_NAMES.getOrDefault(e.getKey(), e.getKey().toString().substring(0, 8)),
                e.getValue()));
        }
        return out;
    }

    public static String prettyName(ResourceLocation id) {
        // ID paths are lowercase snake_case (e.g. "ray_of_siphoning") → Title Case: "Ray Of
        // Siphoning". The previous .toLowerCase() at the end was undoing the per-word
        // capitalisation and forcing the HUD layer to redo it; leave the casing intact.
        String path = id.getPath().replace('_', ' ');
        StringBuilder sb = new StringBuilder(path.length());
        boolean capNext = true;
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            sb.append(capNext ? Character.toUpperCase(c) : c);
            capNext = (c == ' ');
        }
        return sb.toString();
    }
}
