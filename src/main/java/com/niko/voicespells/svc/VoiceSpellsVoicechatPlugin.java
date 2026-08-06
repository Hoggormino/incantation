package com.niko.voicespells.svc;

import com.niko.voicespells.VoiceSpells;
import de.maxhenkel.voicechat.api.ForgeVoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatClientApi;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.events.ClientSoundEvent;
import de.maxhenkel.voicechat.api.events.EventRegistration;

import java.util.function.Consumer;

/**
 * Discovered by SVC via {@link ForgeVoicechatPlugin}. We forward the local mic's raw PCM frames
 * to the voice-controller whenever the user is transmitting AND not muted in SVC.
 *
 * <p><b>Server-safe by design.</b> SVC loads plugin classes on BOTH the client and the dedicated
 * server (the annotation scan doesn't discriminate). This class therefore never statically
 * references the client-only {@code VoiceController} — instead the client init wires
 * {@link MicFrameSink#sink} at startup, and on a dedicated server the sink stays {@code null}
 * so we no-op. Without this indirection the JVM verifier would resolve the client class chain
 * (Minecraft, GuiGraphics, etc.) when SVC instantiates the plugin server-side, which is exactly
 * the "loads up a gui issue" crash users hit in 0.9.0.
 *
 * <p>SVC fires {@link ClientSoundEvent} from its capture thread before the mute-aware encoding
 * stage, so we have to check {@link VoicechatClientApi#isMuted()} ourselves to honour the user's
 * mute toggle. We also force a flush at the mute→unmute boundary so a partial recognition left
 * over from before the mute can't bleed into the next utterance.
 */
@ForgeVoicechatPlugin
public final class VoiceSpellsVoicechatPlugin implements VoicechatPlugin {

    private static final short[] EMPTY_FRAME = new short[0];

    /** @deprecated the sink now lives on {@link MicFrameSink}, which references neither SVC nor
     *  the client. Writing this field would have required loading this class, and this class
     *  cannot load without SVC present — see {@link MicFrameSink} for why that mattered. */
    @Deprecated
    public static volatile Consumer<short[]> micFrameSink;

    private volatile VoicechatClientApi clientApi;
    private boolean wasMuted = false;

    @Override
    public String getPluginId() {
        return VoiceSpells.MOD_ID;
    }

    @Override
    public void initialize(VoicechatApi api) {
        // On the client the API object SVC hands us is always a VoicechatClientApi; on a dedicated
        // server it'd be a VoicechatServerApi (which we don't care about — no mic there).
        if (api instanceof VoicechatClientApi c) {
            this.clientApi = c;
        }
        VoiceSpells.LOGGER.info("Registered with Simple Voice Chat as plugin '{}'", getPluginId());
    }

    @Override
    public void registerEvents(EventRegistration registration) {
        registration.registerEvent(ClientSoundEvent.class, this::onClientSound);
    }

    private void onClientSound(ClientSoundEvent event) {
        Consumer<short[]> sink = MicFrameSink.sink;
        if (sink == null) return; // Dedicated server, or client not initialised yet — no mic source.

        VoicechatClientApi api = clientApi;
        boolean muted = api != null && api.isMuted();

        // Mute transition: if we just got muted mid-utterance, push a synthetic end-of-transmission
        // so any partial recognition flushes and doesn't poison the next session.
        if (muted != wasMuted) {
            if (muted) sink.accept(EMPTY_FRAME);
            wasMuted = muted;
        }
        if (muted) return;

        short[] pcm = event.getRawAudio();
        if (pcm == null) return;
        // Empty arrays are SVC's end-of-transmission marker — pass them through so the controller
        // can flush the recognizer instead of dropping them here.
        sink.accept(pcm);
    }
}
