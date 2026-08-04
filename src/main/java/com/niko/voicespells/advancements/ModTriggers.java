package com.niko.voicespells.advancements;

import net.minecraft.advancements.CriteriaTriggers;
import net.minecraftforge.eventbus.api.IEventBus;

/**
 * Registers our custom advancement criterion triggers.
 *
 * <p><b>1.20.1 shape.</b> There is no {@code Registries.TRIGGER_TYPE} registry to defer into —
 * that arrived with the 1.20.2 criterion rewrite. Triggers are added to a plain static map by
 * {@link CriteriaTriggers#register}, which must happen before any datapack is parsed, so the call
 * runs eagerly in the static initialiser rather than on a registry event. The JSON criteria in
 * {@code data/voicespells/advancements/} can then reference {@code voicespells:voice_cast}.
 *
 * <p>{@code VOICE_CAST} is a direct instance reference rather than a {@code DeferredHolder}, so
 * call sites use it directly instead of going through {@code .get()}.
 */
public final class ModTriggers {
    private ModTriggers() {}

    public static final VoiceCastTrigger VOICE_CAST =
        CriteriaTriggers.register(new VoiceCastTrigger());

    /**
     * Kept for symmetry with the 1.21.1 entry point. Registration already happened in this class's
     * static initialiser; touching the class here is what forces that to run at a well-defined
     * point during mod construction rather than lazily at first cast.
     */
    public static void bootstrap(IEventBus modBus) {
        // Referencing VOICE_CAST forces class init (and therefore registration) now.
        if (VOICE_CAST == null) {
            throw new IllegalStateException("voice_cast trigger failed to register");
        }
    }
}
