package com.niko.voicespells.advancements;

import com.niko.voicespells.VoiceSpells;
import net.minecraft.advancements.CriterionTrigger;
import net.minecraft.core.registries.Registries;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Registers our custom advancement criterion triggers. Hooked from the mod constructor; the
 * registry runs during mod startup before any datapacks are loaded, so the JSON criteria in
 * {@code data/voicespells/advancement/} can reference {@code voicespells:voice_cast}.
 */
public final class ModTriggers {
    private ModTriggers() {}

    public static final DeferredRegister<CriterionTrigger<?>> TRIGGERS =
        DeferredRegister.create(Registries.TRIGGER_TYPE, VoiceSpells.MOD_ID);

    public static final DeferredHolder<CriterionTrigger<?>, VoiceCastTrigger> VOICE_CAST =
        TRIGGERS.register("voice_cast", VoiceCastTrigger::new);

    public static void bootstrap(IEventBus modBus) {
        TRIGGERS.register(modBus);
    }
}
