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
    /** Scrolls are ISpellContainer but not ISpellbook, so the imbued-weapon scan picks them up.
     *  They must be cast as CastSource.SCROLL, not SWORD — see findHeldImbued. */
    private static final String SCROLL_MARKER     = "io.redspace.ironsspellbooks.api.item.IScroll";
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

    /**
     * Whether {@code voiceAllowedPlayers} permits this player to voice-cast at all.
     *
     * <p>Exposed because the incantation rule has to know. A host who sets an allowlist AND
     * {@code incantationOnly = ALWAYS} was otherwise handing everyone off the list a mod that
     * cannot cast: voice refused by the allowlist, clicking refused by the incantation rule, and
     * no combination of player action able to resolve it. Somebody who is not allowed to speak
     * cannot be required to speak.
     */
    public static boolean voiceAllowedFor(net.minecraft.world.entity.player.Player player) {
        try {
            java.util.List<? extends String> allowed =
                com.niko.voicespells.VoiceSpellsServerConfig.SERVER.voiceAllowedPlayers.get();
            if (allowed == null || allowed.isEmpty()) return true;   // empty means everyone
            String name = player.getName().getString();
            String uuid = player.getUUID().toString();
            for (String token : allowed) {
                if (token == null) continue;
                if (token.equalsIgnoreCase(name) || token.equalsIgnoreCase(uuid)) return true;
            }
            return false;
        } catch (Throwable t) {
            return true;   // a config read that fails must never restrict anybody
        }
    }

    public static boolean cast(ServerPlayer player, ResourceLocation spellId,
                                float volumeScale, int totalCasts, int streak) {
        return cast(player, spellId, volumeScale, totalCasts, streak, true);
    }

    /**
     * @param spoken false when this came from the quick-recast keybind rather than from speech.
     *               Under {@code incantationOnly = ALWAYS} that is the difference between a
     *               legitimate cast and a way to bypass the rule entirely - speak once, then
     *               hold the key. The server cannot infer it, so the client says so.
     */
    public static boolean cast(ServerPlayer player, ResourceLocation spellId,
                                float volumeScale, int totalCasts, int streak, boolean spoken) {
        if (!spoken && SpellRules.requiresSpeechEveryCast(player)) {
            feedback(player, "voicespells.cast.recast_blocked",
                SpellInfo.of(spellId.toString()).displayName());
            return false;
        }
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
                            SpellInfo.of(spellId.toString()).displayName());
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
                                SpellInfo.of(spellId.toString()).displayName());
                            return false;
                        }
                    }
                    feedback(player, "voicespells.cast.no_spellbook",
                        SpellInfo.of(spellId.toString()).displayName());
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

            // Voice level bonus, applied HERE because this is the number Iron's Spells will
            // actually cast with. The first implementation tried ModifySpellLevelEvent, which
            // never fires on this path - the level is already fixed by the time
            // attemptInitiateCast is called - so the option silently did nothing.
            //
            // Outside the spellbook branch on purpose. It used to sit inside it, which meant that
            // under castMode=FREE - the mode an event server reaches for, because it needs no
            // spellbook - castLevel stayed at the hardcoded 1 and voiceLevelBonus did nothing at
            // all. That is the one mode where the bonus is the ONLY thing distinguishing a spoken
            // cast, since FREE also passes triggerCooldown=false and so voiceCooldownPercent has
            // no cooldown to scale.
            //
            // Allowed to exceed the spellbook's inscribed level on purpose: Iron's Spells itself
            // lets getLevelFor exceed it through Curios, and a server that turns this on is asking
            // for spoken casts to be stronger than clicked ones. Clamped only against absurd
            // values.
            //
            // The bonus is FREE: the extra levels are refunded in mana after the cast, so a
            // spoken spell hits like a higher-level one without costing like one. Iron's Spells
            // derives mana cost from the level it is handed, so without the refund
            // voiceLevelBonus = 5 roughly doubles what a cast costs - "voice casts hit harder,
            // and drain you twice as fast" is not the advantage the host who asked for this
            // wanted, and nothing in the game would have told the player it was happening.
            int baseLevel  = castLevel;
            int levelBonus = SpellRules.configuredLevelBonus();
            if (levelBonus > 0) castLevel = Math.min(castLevel + levelBonus, 10);
            // The preflight below still checks against the BOOSTED level, so a player can never
            // start a cast they could not have afforded outright - the refund lands after, not
            // instead. That keeps mana honest under any failure path.

            // Pre-flight: in spellbook modes the cast costs mana and triggers a cooldown.
            // attemptInitiateCast itself will silently fail when either's not satisfied; rather
            // than spamming the generic "Could not cast" toast we tell the player exactly why.
            if (mode != com.niko.voicespells.VoiceSpellsServerConfig.CastMode.FREE) {
                String reason = preflightCheck(player, spell, spellId, castLevel, spellClass);
                if (reason != null) {
                    feedback(player, "voicespells.cast." + reason,
                        SpellInfo.of(spellId.toString()).displayName());
                    return false;
                }
            }

            // Mark the cast as ours for the duration of the invoke.
            //
            // Iron's Spells' cooldown, level and pre-cast events fire deep inside this call and
            // carry nothing that says who started it, so SpellRules reads this stamp to tell a
            // spoken cast from a clicked one. In a finally, because a cast that throws must not
            // leave the player marked as permanently voice-casting - that would hand them the
            // voice bonuses for free and, under the ALWAYS rule, let them click-cast forever.
            boolean ok;
            SpellRules.beginVoiceCast(player.getUUID(), spellId.toString());
            try {
                ok = (boolean) cast.invoke(
                    spell, castStack, castLevel, player.level(), player,
                    castSource, triggerCooldown, castSlot);
            } catch (Throwable castFailed) {
                // Only the FAILURE path clears the mark. Clearing it on success - which is what
                // the first version did in a finally - killed the feature outright: initiation
                // only starts a cast, and Iron's Spells applies the cooldown when the cast
                // RESOLVES, which for anything with a cast time is seconds later. The mark is
                // consumed by the cooldown hook, or expires on its own.
                SpellRules.endVoiceCast(player.getUUID());
                throw castFailed;
            }
            if (!ok) SpellRules.endVoiceCast(player.getUUID());
            if (!ok) {
                feedback(player, "voicespells.cast.failed",
                    SpellInfo.of(spellId.toString()).displayName());
            } else {
                refundLevelBonusMana(player, spell, spellClass, baseLevel, castLevel);
                appendCastLog(player, spellId);
                broadcastNearby(player, spellId);
                // Count on the SERVER first, then award from the server's number.
                //
                // totalCasts and streak arrive in the packet, i.e. from the client, and
                // recordPlayerTotal was already hardened against them for the leaderboard. The
                // advancement trigger was still being handed the raw client values, so a crafted
                // packet could claim any milestone it liked. Using the value the server just
                // recorded closes that without changing anything for an honest client.
                int serverTotal = recordPlayerTotal(
                    player.getUUID(), player.getName().getString(), totalCasts);
                fireVoiceCastTrigger(player, spell, spellClass, serverTotal, streak);
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
            String culprit = blameThirdPartyMod(cause);
            if (culprit != null) {
                VoiceSpells.LOGGER.error(
                    "This failure came from {}, which hooks Iron's Spells' cast path. It is a "
                    + "conflict between that mod and Iron's Spells, not a bug in Incantation — "
                    + "the same error occurs on any cast that reaches its hook. Removing or "
                    + "reconfiguring {} resolves it.", culprit, culprit);
            } else {
                VoiceSpells.LOGGER.error(
                    "If the class above belongs to another mod, this is a conflict in that mod's "
                    + "hook on Iron's Spells' cast path, not in Incantation itself.");
            }
            String detail = cause.getMessage() == null || cause.getMessage().isBlank()
                ? cause.getClass().getSimpleName()
                : cause.getClass().getSimpleName() + ": " + cause.getMessage();
            if (detail.length() > 90) detail = detail.substring(0, 90) + "…";
            if (culprit != null) {
                feedback(player, "voicespells.cast.error_mod", culprit);
            } else {
                feedback(player, "voicespells.cast.error", detail);
            }
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
//? if forge {
/*        // NOT a direct cast to Optional — on 1.20.1 Forge this returns LazyOptional. See
        // CuriosCompat for why that difference is invisible until the first cast fails.
        Optional<Object> invOpt = CuriosCompat.inventory(getInventory, player);
*///?} else {
        @SuppressWarnings("unchecked")
        Optional<Object> invOpt = (Optional<Object>) getInventory.invoke(null, player);
//?}
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
        // Hands only. This used to walk hotbar slots 0..8 as well, which no cast mode asks
        // for: ANY_SPELLBOOK is documented as "a spellbook in mainhand/offhand counts too",
        // and CURIO_SPELLBOOK does not reach this method at all. Scanning the hotbar let a
        // book you were merely carrying satisfy a check that is supposed to be about what you
        // are holding.
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
        // Hands only, for the same reason as findHeldSpellbook — and here it mattered more.
        // This scan is NOT gated by castMode, so while the hotbar was included an imbued sword
        // or a scroll sitting unheld in the hotbar could be voice-cast even in CURIO_SPELLBOOK,
        // the default and strictest mode. The method's own javadoc says "a player wielding an
        // imbued sword", and right-clicking — the behaviour this mirrors — only ever works on
        // what is actually in hand.
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
            // Scrolls reach this scan too: Scroll implements IScroll, not ISpellbook, so the
            // skip above does not catch them and they satisfy ISpellContainer. Casting one as
            // SWORD meant Iron's Spells never consumed it — ServerPlayerEvents only calls
            // ItemStack.shrink(1) when the source is CastSource.SCROLL — so a single-use scroll
            // could be voice-cast without limit. SCROLL also matches right-click semantics:
            // CastSource.consumesMana() is true only for SPELLBOOK and (configurably) SWORD.
            String source = isScroll(stack) ? "SCROLL" : "SWORD";
            return new SlotMatch(stack, slots.get(h), (int) getLevel.invoke(data), source);
        }
        return null;
    }

    /** True when the item implements Iron's Spells' IScroll marker. Resolved lazily and cached;
     *  a missing class simply means no item is ever treated as a scroll. */
    private static volatile Class<?> scrollMark;
    private static volatile boolean scrollMarkResolved = false;
    private static boolean isScroll(ItemStack stack) {
        if (!scrollMarkResolved) {
            synchronized (SpellCaster.class) {
                if (!scrollMarkResolved) {
                    try { scrollMark = Class.forName(SCROLL_MARKER); }
                    catch (Throwable t) { scrollMark = null; }
                    scrollMarkResolved = true;
                }
            }
        }
        Class<?> m = scrollMark;
        return m != null && m.isInstance(stack.getItem());
    }

    /**
     * Reflective mana + cooldown check. Returns {@code null} on success, or a short reason
     * string ("cooldown" / "no_mana") that maps to a translation key when blocking the cast.
     *
     * Any reflection failure is treated as "unknown" and we return null — the underlying
     * attemptInitiateCast still has the final say, so a missing API method means we just
     * fall back to its silent failure with the generic "Could not cast" toast.
     */
    /**
     * Give back the mana the level bonus cost, so the bonus is an advantage rather than a trade.
     *
     * <p>Iron's Spells charges by the level it is handed and there is no hook to change that, so
     * the only honest way to make the bonus free is to put the difference back afterwards. Done
     * AFTER a successful cast, never before: the preflight has already required the player to
     * afford the boosted cost, so nobody can start a cast on credit, and a cast that fails costs
     * exactly what it always did.
     *
     * <p>Best-effort. If any of this reflection is unavailable the player simply pays full
     * price - which is the behaviour before this existed, and strictly safer than guessing.
     */
    private static void refundLevelBonusMana(ServerPlayer player, Object spell,
                                             Class<?> spellClass, int baseLevel, int castLevel) {
        if (castLevel <= baseLevel) return;
        try {
            Class<?> magicDataCls = Class.forName(MAGIC_DATA_CLASS);
            Object magicData = magicDataCls
                .getMethod("getPlayerMagicData", LivingEntity.class).invoke(null, player);
            if (magicData == null) return;

            java.lang.reflect.Method getCost;
            boolean withCaster;
            try {
                getCost = spellClass.getMethod("getManaCost", int.class, LivingEntity.class);
                withCaster = true;
            } catch (NoSuchMethodException e) {
                getCost = spellClass.getMethod("getManaCost", int.class);
                withCaster = false;
            }
            int paid = ((Number) (withCaster ? getCost.invoke(spell, castLevel, player)
                                             : getCost.invoke(spell, castLevel))).intValue();
            int owed = ((Number) (withCaster ? getCost.invoke(spell, baseLevel, player)
                                             : getCost.invoke(spell, baseLevel))).intValue();
            int refund = paid - owed;
            if (refund <= 0) return;

            // addMana, not setMana: Iron's Spells clamps addMana to the player's maximum, and
            // computing a new absolute value here would race the regeneration tick.
            magicDataCls.getMethod("addMana", float.class).invoke(magicData, (float) refund);
            VoiceSpells.LOGGER.debug("Refunded {} mana for the voice level bonus ({} -> {})",
                refund, baseLevel, castLevel);
        } catch (Throwable t) {
            VoiceSpells.LOGGER.debug("Could not refund level-bonus mana: {}", t.toString());
        }
    }

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

    /**
     * Work out which third-party mod a cast failure actually came from, by walking the stack trace
     * for the first frame that is neither Minecraft, nor Iron's Spells, nor this mod, and asking
     * the mod loader which loaded mod owns that class.
     *
     * <p>Worth the effort because the failure mode it addresses is genuinely misleading. Every cast
     * goes through reflection, so a mod that mixes into Iron's Spells' cast path and throws
     * surfaces as an error from Incantation — users reasonably conclude this mod is broken and
     * report it here. One such report took weeks to trace, and was ultimately resolved by another
     * user noticing a conflict with a progression mod that gates casting. Naming the mod turns that
     * into a one-line answer.
     *
     * @return the offending mod's display name, or {@code null} if the failure looks like it came
     *         from Iron's Spells or vanilla, where blaming a third party would be wrong
     */
    private static String blameThirdPartyMod(Throwable cause) {
        try {
            for (StackTraceElement frame : cause.getStackTrace()) {
                String cls = frame.getClassName();
                if (cls.startsWith("net.minecraft.")
                    || cls.startsWith("com.mojang.")
                    || cls.startsWith("net.neoforged.")
                    || cls.startsWith("net.minecraftforge.")
                    || cls.startsWith("java.")
                    || cls.startsWith("jdk.")
                    || cls.startsWith("com.niko.voicespells.")) {
                    continue;
                }
                // Iron's Spells' own code is not a third party here — but a mixin injected INTO it
                // keeps the target's package name, so those are identified by the mixin marker
                // rather than skipped outright.
                boolean isIronsOwn = cls.startsWith("io.redspace.ironsspellbooks.")
                    && !cls.toLowerCase(Locale.ROOT).contains("mixin");
                if (isIronsOwn) continue;

                String owner = modNameForClass(cls);
                if (owner != null) return owner;
            }
        } catch (Throwable ignored) {
            // Diagnostics must never themselves throw during error handling.
        }
        return null;
    }

    /** Map a class name to the display name of the mod that loaded it, if any. */
    private static String modNameForClass(String className) {
        try {
            Class<?> cls = Class.forName(className, false, SpellCaster.class.getClassLoader());
            String pkg = cls.getPackageName();
//? if forge {
/*            for (var mod : net.minecraftforge.fml.ModList.get().getMods()) {
*///?} else {
            for (var mod : net.neoforged.fml.ModList.get().getMods()) {
//?}
                String id = mod.getModId();
                if (id.equals("minecraft") || id.equals("neoforge") || id.equals("forge")
                    || id.equals(VoiceSpells.MOD_ID)) continue;
                // Mixin classes injected into another mod keep the TARGET's package, so also match
                // on the mod id appearing in the package path — which is how a mixin package like
                // "io.redspace.ironsspellbooks.ironsrestrictionsmixin" gives up its real owner.
                String squashed = id.replace("_", "");
                String pkgSquashed = pkg.toLowerCase(Locale.ROOT).replace("_", "");
                if (pkgSquashed.contains(squashed) || pkg.contains(id)) {
                    return mod.getDisplayName() + " (" + id + ")";
                }
            }
        } catch (Throwable ignored) {}
        return null;
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
//? if forge {
/*            com.niko.voicespells.advancements.ModTriggers.VOICE_CAST
*///?} else {
            com.niko.voicespells.advancements.ModTriggers.VOICE_CAST.get()
//?}
                .fire(player, totalCasts, streak, school);
        } catch (Throwable t) {
            VoiceSpells.LOGGER.debug("Advancement trigger failed: {}", t.toString());
        }
    }

    /** Append-only audit log of every successful voice cast. Gated on
     *  {@link com.niko.voicespells.VoiceSpellsServerConfig.Server#logVoiceCasts} so the file
     *  doesn't fill up by default. One line per cast: ISO timestamp, player name, UUID, spell id. */
    private static void appendCastLog(ServerPlayer player, ResourceLocation spellId) {
        // The in-memory mirror is built first and unconditionally. It used to be populated at
        // the bottom of this method — behind the logVoiceCasts gate AND behind a successful file
        // write — so on a default server (logVoiceCasts is off) /voicespells diag always
        // answered "No voice casts logged this session", including while casts were plainly
        // happening. An admin diagnostic that reports nothing by default is worse than none: it
        // reads as evidence the mod is broken.
        String entry = String.format(java.util.Locale.ROOT, "%s\t%s\t%s\t%s",
            java.time.Instant.now(),
            player.getName().getString(),
            player.getUUID(),
            spellId);
        synchronized (RECENT_LOG) {
            RECENT_LOG.addFirst(entry);
            while (RECENT_LOG.size() > 50) RECENT_LOG.removeLast();
        }
        try {
            if (!com.niko.voicespells.VoiceSpellsServerConfig.SERVER.logVoiceCasts.get()) return;
            net.minecraft.server.MinecraftServer server = player.getServer();
            if (server == null) return;
//? if forge {
/*            java.nio.file.Path logDir = server.getServerDirectory().toPath().resolve("logs");
*///?} else {
            java.nio.file.Path logDir = server.getServerDirectory().resolve("logs");
//?}
            java.nio.file.Files.createDirectories(logDir);
            java.nio.file.Path logFile = logDir.resolve("voicespells-casts.log");
            java.nio.file.Files.writeString(logFile, entry + System.lineSeparator(),
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND);
        } catch (Throwable t) {
            VoiceSpells.LOGGER.debug("Cast log write failed: {}", t.toString());
        }
    }

    /** Server-side chat broadcast of a voice cast to nearby players. Off by default; opt-in
     *  via the server config. Useful for RP / streaming servers where the table wants to know
     *  what their teammate just yelled. */
    private static void broadcastNearby(ServerPlayer player, ResourceLocation spellId) {
        try {
            boolean broadcast = com.niko.voicespells.VoiceSpellsServerConfig.SERVER.broadcastVoiceCasts.get();
            // /voicespells follow is an admin tool and is deliberately independent of the
            // broadcastVoiceCasts server option: an admin who explicitly asked to follow casts
            // should keep receiving them on a server that has ambient broadcasting switched off
            // (which is the default). So build the message first and only return early when
            // there is nobody at all to send it to.
            if (!broadcast && SUBSCRIBERS.isEmpty()) return;

            String name = player.getName().getString();
            String pretty = prettyName(spellId);
            Component msg = Component.literal("◈ ")
                .append(Component.literal(name).withStyle(net.minecraft.ChatFormatting.AQUA))
                .append(Component.literal(" cast "))
                .append(Component.literal(pretty).withStyle(net.minecraft.ChatFormatting.LIGHT_PURPLE));

            // Track who already received it, so a subscriber standing next to the caster is not
            // told twice.
            java.util.Set<java.util.UUID> sent = new java.util.HashSet<>();
            if (broadcast) {
                int radius = com.niko.voicespells.VoiceSpellsServerConfig.SERVER.broadcastRadius.get();
                if (radius == 0) {
                    player.sendSystemMessage(msg);
                    sent.add(player.getUUID());
                } else {
                    double r2 = (double) radius * radius;
                    for (ServerPlayer other : player.serverLevel().players()) {
                        if (other != player && other.distanceToSqr(player) > r2) continue;
                        other.sendSystemMessage(msg);
                        sent.add(other.getUUID());
                    }
                }
            }
            // Notify any /voicespells follow subscribers regardless of distance —
            // intended for admins watching from the lobby.
            if (!SUBSCRIBERS.isEmpty() && player.getServer() != null) {
                for (java.util.UUID uuid : SUBSCRIBERS) {
                    if (uuid.equals(player.getUUID()) || sent.contains(uuid)) continue;
                    ServerPlayer admin = player.getServer().getPlayerList().getPlayer(uuid);
                    if (admin != null) admin.sendSystemMessage(msg);
                }
            }
        } catch (Throwable t) {
            VoiceSpells.LOGGER.debug("Broadcast failed: {}", t.toString());
        }
    }

    /**
     * Drop the per-player state that has no meaning once a player is gone.
     *
     * <p>Only the rate-limit window. {@code SUBSCRIBERS} is deliberately kept — a /voicespells
     * follow subscription is documented to survive reconnect — and {@code PLAYER_TOTALS} /
     * {@code PLAYER_NAMES} back the /voicespells top leaderboard, which would be pointless if it
     * forgot everyone who logged off. Those are released on server stop instead.
     */
    public static void forgetPlayer(UUID uuid) {
        if (uuid == null) return;
        RECENT_CASTS.remove(uuid);
        // The mod-present flag is per CONNECTION, not per server run. Leaving it set meant a
        // player who rejoined without the mod - uninstalled it, or came back on a vanilla
        // profile - still counted as able to speak, so under ALWAYS every spell they owned was
        // silently uncastable with no settings screen to explain it. Re-established at login.
        VOICE_CLIENTS.remove(uuid);
        // Drop the in-flight voice-cast stamp too. A player who disconnects mid-cast would
        // otherwise stay marked as casting until the stamp aged out, and under the ALWAYS
        // incantation rule that is a window in which their next clicked cast would be allowed.
        SpellRules.endVoiceCast(uuid);
    }

    /**
     * Release everything held for the lifetime of a server.
     *
     * <p>These are static, so on a client they outlive the integrated server: open a world, quit
     * to title, open another, and without this the previous world's subscribers and totals would
     * still be here. On a dedicated server it only matters at shutdown, but the client case is
     * real and is why this is wired to ServerStopped rather than left to process exit.
     */
    public static void clearServerState() {
        // Learned incantations and any in-flight stamp are per server run, like the totals below.
        SpellRules.forgetAll();
        RECENT_CASTS.clear();
        SUBSCRIBERS.clear();
        PLAYER_TOTALS.clear();
        VOICE_CLIENTS.clear();
        PLAYER_NAMES.clear();
        synchronized (RECENT_LOG) { RECENT_LOG.clear(); }
    }

    /** Most recent ~50 voice cast lines, newest first. Drives /voicespells diag. */
    private static final java.util.Deque<String> RECENT_LOG = new java.util.ArrayDeque<>();
    public static java.util.List<String> recentLog() {
        synchronized (RECENT_LOG) { return new java.util.ArrayList<>(RECENT_LOG); }
    }

    /** Players who have opted in to live voice-cast notifications via {@code /voicespells follow}.
     *  Stored as UUIDs so the subscription deliberately survives reconnect — which is why logout
     *  does NOT drop it. Cleared on server stop by {@link #clearServerState()}, wired up in
     *  {@link com.niko.voicespells.server.ServerLifecycle}. */
    private static final java.util.Set<java.util.UUID> SUBSCRIBERS = java.util.concurrent.ConcurrentHashMap.newKeySet();
    public static boolean toggleSubscriber(java.util.UUID uuid) {
        if (SUBSCRIBERS.remove(uuid)) return false;
        SUBSCRIBERS.add(uuid);
        return true;
    }
    /** Highest total-cast count we've seen reported per player, used by /voicespells top. The
     *  count is sent by the client in {@link com.niko.voicespells.network.CastSpellPayload}
     *  and reflects the player's lifetime stats from their local {@code VoiceStats}. */
    /**
     * Players the server has actually heard from over the mod's channel.
     *
     * <p>Ground truth for "this player has Incantation installed": the payload is registered as
     * OPTIONAL, so a client without the mod simply never negotiates the channel and can never
     * send one. It matters for the incantation rule - locking spells behind speech would
     * otherwise permanently brick anyone playing without the mod, who has no way to comply and
     * may not even have the translation string to be told why.
     *
     * <p>Populated on receipt rather than by a handshake because the mod has no handshake, and
     * adding one to a released network protocol is a compatibility break for a check that this
     * answers exactly. The consequence is honest and documented in the config: the ALWAYS rule
     * only constrains players the server has heard speak at least once.
     */
    private static final java.util.Set<java.util.UUID> VOICE_CLIENTS =
        java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Called when a cast packet arrives, before anything else is validated. */
    public static void noteVoiceClient(java.util.UUID uuid) {
        if (uuid != null) VOICE_CLIENTS.add(uuid);
    }

    /** Whether this player has ever spoken to the server over the mod's channel. */
    public static boolean hasVoiceClient(java.util.UUID uuid) {
        return uuid != null && VOICE_CLIENTS.contains(uuid);
    }

    private static final java.util.Map<java.util.UUID, Integer> PLAYER_TOTALS =
        new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<java.util.UUID, String>  PLAYER_NAMES  =
        new java.util.concurrent.ConcurrentHashMap<>();
    static int recordPlayerTotal(java.util.UUID uuid, String name, int clientTotal) {
        // Count server-side rather than believing the number in the packet.
        //
        // This used to be merge(uuid, clientTotal, Math::max) on a value the client supplies,
        // so a single crafted packet claiming Integer.MAX_VALUE pinned that player at the top
        // of /voicespells top permanently — max() means it can never come back down, and the
        // map is not persisted per-world but does live for the whole server run. Counting the
        // casts we actually authorised is both untrusted-input-free and a more honest answer
        // to "who has voice-cast the most on this server".
        int serverTotal = PLAYER_TOTALS.merge(uuid, 1, Integer::sum);
        PLAYER_NAMES.put(uuid, name);
        // Returned so the advancement trigger can award from the same trusted number rather
        // than from the one in the packet.
        return serverTotal;
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
