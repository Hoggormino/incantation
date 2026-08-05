package com.niko.voicespells.advancements;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.advancements.critereon.ContextAwarePredicate;
import net.minecraft.advancements.critereon.EntityPredicate;
import net.minecraft.advancements.critereon.SimpleCriterionTrigger;
import net.minecraft.server.level.ServerPlayer;

import java.util.Optional;

/**
 * Custom advancement trigger fired on every successful voice cast.
 *
 * Datapack advancement JSONs reference this as {@code "voicespells:voice_cast"} and may include
 * the following optional condition fields:
 *
 *   - {@code min_total_casts}: int — current total voice casts must be ≥ this value
 *   - {@code min_streak}:       int — current consecutive-cast streak must be ≥ this value
 *   - {@code school}:           string — the cast spell's Iron's Spells school name (case-insensitive)
 *
 * Without conditions the criterion matches on every cast — useful for the "first voice cast"
 * advancement. With conditions the criterion gates a milestone (e.g. {@code min_total_casts: 10}).
 *
 * The trigger replaces the previous in-mod rank/achievement toast system. Players see vanilla
 * advancement toasts and the advancements appear in the regular L menu.
 */
public class VoiceCastTrigger extends SimpleCriterionTrigger<VoiceCastTrigger.Instance> {

    @Override
    public Codec<Instance> codec() {
        return Instance.CODEC;
    }

    /** Fire the trigger for {@code player} with the current per-player metrics. The server
     *  iterates listeners and calls {@link Instance#matches} on each one. */
    public void fire(ServerPlayer player, int totalCasts, int streak, String school) {
        this.trigger(player, instance -> instance.matches(totalCasts, streak, school));
    }

    public record Instance(
            Optional<ContextAwarePredicate> player,
            Optional<Integer> minTotalCasts,
            Optional<Integer> minStreak,
            Optional<String>  school
    ) implements SimpleCriterionTrigger.SimpleInstance {

        public static final Codec<Instance> CODEC = RecordCodecBuilder.create(i -> i.group(
            EntityPredicate.ADVANCEMENT_CODEC.optionalFieldOf("player").forGetter(Instance::player),
            Codec.INT.optionalFieldOf("min_total_casts").forGetter(Instance::minTotalCasts),
            Codec.INT.optionalFieldOf("min_streak").forGetter(Instance::minStreak),
            Codec.STRING.optionalFieldOf("school").forGetter(Instance::school)
        ).apply(i, Instance::new));

        public boolean matches(int total, int streak, String s) {
            if (minTotalCasts.isPresent() && total < minTotalCasts.get()) return false;
            if (minStreak.isPresent()    && streak < minStreak.get())     return false;
            if (school.isPresent()
                    && (s == null || !school.get().equalsIgnoreCase(s.trim()))) return false;
            return true;
        }
    }
}
