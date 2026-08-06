package com.niko.voicespells.svc;

import java.util.function.Consumer;

/**
 * Neutral hand-off point for mic frames between client init and the Simple Voice Chat plugin.
 *
 * <p>This class exists solely so that <b>neither</b> side has to touch the other's types:
 *
 * <ul>
 *   <li>{@code ClientEvents} writes {@link #sink} at startup. It must never reference
 *       {@link VoiceSpellsVoicechatPlugin} to do so, because that class {@code implements
 *       VoicechatPlugin} — writing a static field on it triggers its class initialisation, which
 *       resolves {@code de.maxhenkel.voicechat.api.VoicechatPlugin}. SVC is an <i>optional</i>
 *       dependency, so on a client without SVC installed that throws
 *       {@code NoClassDefFoundError} and takes mod loading down with it.</li>
 *   <li>{@code VoiceSpellsVoicechatPlugin} reads {@link #sink} instead of statically referencing
 *       the client-only {@code VoiceController}. SVC's annotation scan loads plugin classes on
 *       dedicated servers too, and resolving the client chain there is the crash 0.9.0 shipped.</li>
 * </ul>
 *
 * <p>So the indirection is load-bearing in both directions: client-without-SVC must not load the
 * plugin, and server-with-SVC must not load the client. This class references neither, which is
 * the whole point — keep it free of imports from both sides.
 */
public final class MicFrameSink {
    private MicFrameSink() {}

    /**
     * Wired client-side at startup to {@code VoiceController::onMicFrame}. Stays {@code null} on a
     * dedicated server (nothing wires it) and on a client where recognition never starts, so the
     * plugin's event body simply no-ops.
     */
    public static volatile Consumer<short[]> sink;
}
