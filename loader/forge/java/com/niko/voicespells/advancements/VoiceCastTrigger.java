package com.niko.voicespells.advancements;

import com.google.gson.JsonObject;
import com.niko.voicespells.VoiceSpells;
import net.minecraft.advancements.critereon.AbstractCriterionTriggerInstance;
import net.minecraft.advancements.critereon.ContextAwarePredicate;
import net.minecraft.advancements.critereon.DeserializationContext;
import net.minecraft.advancements.critereon.SimpleCriterionTrigger;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.GsonHelper;

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
 * <p><b>1.20.1 shape.</b> Criteria here predate the Codec rewrite that landed in 1.20.2: a trigger
 * carries its own {@link ResourceLocation} id via {@link #getId()}, deserialises conditions from a
 * raw {@link JsonObject}, and its instance extends {@link AbstractCriterionTriggerInstance} rather
 * than implementing a {@code SimpleInstance} record with a {@code Codec}. Absent fields are
 * represented by sentinel values instead of {@code Optional}, since there is no codec to express
 * optionality.
 */
public class VoiceCastTrigger extends SimpleCriterionTrigger<VoiceCastTrigger.Instance> {

    public static final ResourceLocation ID =
        new ResourceLocation(VoiceSpells.MOD_ID, "voice_cast");

    @Override
    public ResourceLocation getId() {
        return ID;
    }

    @Override
    protected Instance createInstance(JsonObject json, ContextAwarePredicate player,
                                      DeserializationContext context) {
        // -1 / null mean "condition absent", matching the Optional.empty() semantics the 1.21
        // codec expressed directly.
        int minTotal  = GsonHelper.getAsInt(json, "min_total_casts", -1);
        int minStreak = GsonHelper.getAsInt(json, "min_streak", -1);
        String school = GsonHelper.getAsString(json, "school", null);
        return new Instance(player, minTotal, minStreak, school);
    }

    /** Fire the trigger for {@code player} with the current per-player metrics. The server
     *  iterates listeners and calls {@link Instance#matches} on each one. */
    public void fire(ServerPlayer player, int totalCasts, int streak, String school) {
        this.trigger(player, instance -> instance.matches(totalCasts, streak, school));
    }

    public static class Instance extends AbstractCriterionTriggerInstance {
        private final int minTotalCasts;
        private final int minStreak;
        private final String school;

        public Instance(ContextAwarePredicate player, int minTotalCasts, int minStreak, String school) {
            super(ID, player);
            this.minTotalCasts = minTotalCasts;
            this.minStreak = minStreak;
            this.school = school;
        }

        public boolean matches(int total, int streak, String s) {
            if (minTotalCasts >= 0 && total  < minTotalCasts) return false;
            if (minStreak     >= 0 && streak < minStreak)     return false;
            if (school != null
                    && (s == null || !school.equalsIgnoreCase(s.trim()))) return false;
            return true;
        }

        @Override
        public JsonObject serializeToJson(net.minecraft.advancements.critereon.SerializationContext context) {
            JsonObject json = super.serializeToJson(context);
            if (minTotalCasts >= 0) json.addProperty("min_total_casts", minTotalCasts);
            if (minStreak     >= 0) json.addProperty("min_streak", minStreak);
            if (school != null)     json.addProperty("school", school);
            return json;
        }
    }
}
