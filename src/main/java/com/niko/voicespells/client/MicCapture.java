package com.niko.voicespells.client;

import com.niko.voicespells.VoiceSpells;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.ALC11;
import org.lwjgl.openal.ALUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.List;
import java.util.function.Consumer;

/**
 * Standalone microphone capture via OpenAL, replacing the Simple Voice Chat mic tap.
 *
 * <p>Deliberately depends on nothing new: LWJGL's OpenAL bindings already ship with Minecraft
 * because the game uses them for its own audio, so capture costs zero extra jars.
 *
 * <p>Opens the device at <b>16 kHz mono 16-bit</b> — the rate Vosk wants — so audio goes straight
 * to {@link VoskSession#feed16k(short[])} with no resampling. The SVC path had to decimate 48 kHz
 * 3:1 to get here.
 *
 * <p><b>Threading.</b> One daemon thread polls the device for ~100 ms chunks and hands them to the
 * consumer. It never touches the client or render thread, and never blocks them. All OpenAL calls
 * happen on that thread alone; the capture device is a separate ALC device from the game's
 * playback device, so this does not contend with Minecraft's own audio.
 *
 * <p><b>Failure is always non-fatal.</b> No microphone, a device that vanishes mid-session, a
 * driver that starts returning errors — every one of those parks the capture loop into a retry
 * state, warns once, and keeps trying. Nothing here is allowed to take the game down or to spam
 * the log: audio input is a convenience, and a mod that crashes because a USB headset was
 * unplugged is a worse mod than one that quietly stops listening.
 *
 * <p><b>Privacy.</b> Buffers are transient heap only. Audio is never written to disk, never
 * retained beyond the chunk being processed, and never leaves the process.
 */
public final class MicCapture implements AutoCloseable {

    /** Vosk's rate. Opening the device here avoids resampling entirely. */
    public static final int SAMPLE_RATE = 16_000;
    /** ~100 ms per chunk. Small enough for responsive partials, large enough to be cheap. */
    private static final int CHUNK_SAMPLES = 1_600;
    /** Device-side ring, several chunks deep, so a scheduling hiccup does not drop audio. */
    private static final int DEVICE_BUFFER_SAMPLES = CHUNK_SAMPLES * 8;

    /** How long to wait before re-probing after the device fails or disappears. */
    private static final long RETRY_INTERVAL_MS = 3_000L;
    /** Poll interval when the device has not yet accumulated a full chunk. */
    private static final long IDLE_POLL_MS = 10L;

    private final Consumer<short[]> sink;
    private final String requestedDevice;

    private volatile Thread thread;
    private volatile boolean running;
    private volatile long device = 0L;

    /** Latest RMS (0..1-ish, scaled off 16-bit full range) for the HUD level meter. */
    private volatile float level = 0f;
    /** Human-readable state for the HUD / diagnostics: "closed", "capturing", "no device", … */
    private volatile String status = "closed";
    /** Set once per failure episode so a missing mic warns once, not every retry. */
    private volatile boolean warnedThisEpisode = false;

    /**
     * @param requestedDevice exact device name from {@link #listDevices()}, or {@code null}/blank
     *                        for the system default
     * @param sink            receives 16 kHz mono frames on the capture thread; must not block
     */
    public MicCapture(String requestedDevice, Consumer<short[]> sink) {
        this.requestedDevice = (requestedDevice == null || requestedDevice.isBlank())
            ? null : requestedDevice.trim();
        this.sink = sink;
    }

    /** Capture device names as OpenAL reports them. Empty when enumeration is unsupported. */
    public static List<String> listDevices() {
        try {
            List<String> names = ALUtil.getStringList(0L, ALC11.ALC_CAPTURE_DEVICE_SPECIFIER);
            return names == null ? List.of() : List.copyOf(names);
        } catch (Throwable t) {
            VoiceSpells.LOGGER.debug("Capture device enumeration failed: {}", t.toString());
            return List.of();
        }
    }

    /** The system default capture device name, or empty string when unavailable. */
    public static String defaultDevice() {
        try {
            String s = ALC10.alcGetString(0L, ALC11.ALC_CAPTURE_DEFAULT_DEVICE_SPECIFIER);
            return s == null ? "" : s;
        } catch (Throwable t) {
            return "";
        }
    }

    public float level()      { return level; }
    public String status()    { return status; }
    public boolean isRunning() { return running; }

    /** Start the capture thread. Safe to call repeatedly; a second call is a no-op. */
    public synchronized void start() {
        if (running) return;
        running = true;
        Thread t = new Thread(this::loop, "VoiceSpells-MicCapture");
        t.setDaemon(true);
        // Below normal: recognition latency tolerates a few ms of jitter far better than the
        // render thread tolerates being starved.
        t.setPriority(Thread.NORM_PRIORITY - 1);
        thread = t;
        t.start();
    }

    /** Stop capture and release the device. Blocks briefly for the thread to unwind. */
    @Override
    public synchronized void close() {
        running = false;
        Thread t = thread;
        thread = null;
        if (t != null) {
            t.interrupt();
            try { t.join(500); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        }
        closeDevice();
        status = "closed";
        level = 0f;
    }

    // ---------------------------------------------------------------------------------------

    private void loop() {
        long nextRetryAt = 0L;
        // Reused across iterations: allocating a 3 KB direct buffer per 100 ms chunk would
        // generate needless garbage on a thread that runs for the whole session.
        ByteBuffer pcm = ByteBuffer.allocateDirect(CHUNK_SAMPLES * 2).order(ByteOrder.nativeOrder());
        short[] frame = new short[CHUNK_SAMPLES];

        while (running) {
            try {
                if (device == 0L) {
                    long now = System.currentTimeMillis();
                    if (now < nextRetryAt) {
                        sleep(Math.min(RETRY_INTERVAL_MS, nextRetryAt - now));
                        continue;
                    }
                    if (!openDevice()) {
                        nextRetryAt = now + RETRY_INTERVAL_MS;
                        continue;
                    }
                }

                int available = ALC10.alcGetInteger(device, ALC11.ALC_CAPTURE_SAMPLES);
                if (checkDeviceError("poll")) {
                    // The device errored — usually unplugged. Drop it and let the retry path
                    // re-open when it comes back.
                    dropDevice("device error while polling");
                    nextRetryAt = System.currentTimeMillis() + RETRY_INTERVAL_MS;
                    continue;
                }
                if (available < CHUNK_SAMPLES) {
                    sleep(IDLE_POLL_MS);
                    continue;
                }

                pcm.clear();
                ALC11.alcCaptureSamples(device, pcm, CHUNK_SAMPLES);
                if (checkDeviceError("read")) {
                    dropDevice("device error while reading");
                    nextRetryAt = System.currentTimeMillis() + RETRY_INTERVAL_MS;
                    continue;
                }

                ShortBuffer sb = pcm.asShortBuffer();
                sb.get(frame, 0, CHUNK_SAMPLES);
                level = rms(frame);

                // Copy before handing off: `frame` is reused next iteration, so passing it
                // directly would let the consumer observe audio being overwritten underneath it.
                short[] copy = new short[CHUNK_SAMPLES];
                System.arraycopy(frame, 0, copy, 0, CHUNK_SAMPLES);
                try {
                    sink.accept(copy);
                } catch (Throwable consumerFailure) {
                    // A failure downstream must not kill capture.
                    VoiceSpells.LOGGER.debug("Mic consumer threw: {}", consumerFailure.toString());
                }
            } catch (Throwable t) {
                // Belt and braces: the loop is the only thing standing between a driver quirk and
                // a dead capture thread.
                VoiceSpells.LOGGER.debug("Capture loop error: {}", t.toString());
                dropDevice("unexpected error");
                nextRetryAt = System.currentTimeMillis() + RETRY_INTERVAL_MS;
            }
        }
        closeDevice();
    }

    private boolean openDevice() {
        String name = requestedDevice;
        try {
            long d = ALC11.alcCaptureOpenDevice(name, SAMPLE_RATE,
                AL10.AL_FORMAT_MONO16, DEVICE_BUFFER_SAMPLES);
            if (d == 0L) {
                // A named device that has gone away is worth distinguishing from "no mic at all",
                // because the fix is different: pick another device vs plug one in.
                if (name != null) {
                    warnOnce("Capture device \"" + name + "\" is unavailable; "
                        + "run /voicespells devices to list what is connected");
                    status = "device missing";
                } else {
                    warnOnce("No microphone available; voice casting is idle until one is connected");
                    status = "no device";
                }
                return false;
            }
            ALC11.alcCaptureStart(d);
            if (checkErrorOn(d, "start")) {
                ALC11.alcCaptureCloseDevice(d);
                status = "start failed";
                return false;
            }
            device = d;
            status = "capturing";
            if (warnedThisEpisode) {
                // Only announce recovery if we actually complained — avoids a spurious
                // "microphone recovered" on a clean first start.
                VoiceSpells.LOGGER.info("Microphone available again; voice casting resumed");
            }
            warnedThisEpisode = false;
            VoiceSpells.LOGGER.info("Capturing from {} at {} Hz",
                name == null ? "the default device (" + defaultDevice() + ")" : name, SAMPLE_RATE);
            return true;
        } catch (Throwable t) {
            warnOnce("Could not open a capture device: " + t);
            status = "open failed";
            return false;
        }
    }

    private void dropDevice(String why) {
        if (device != 0L) {
            warnOnce("Microphone stopped (" + why + "); retrying every "
                + (RETRY_INTERVAL_MS / 1000) + "s");
        }
        closeDevice();
        status = "reconnecting";
        level = 0f;
    }

    private void closeDevice() {
        long d = device;
        device = 0L;
        if (d == 0L) return;
        try { ALC11.alcCaptureStop(d); } catch (Throwable ignored) {}
        try { ALC11.alcCaptureCloseDevice(d); } catch (Throwable ignored) {}
    }

    /** True when the device reported an error. Also clears the error state. */
    private boolean checkDeviceError(String what) {
        return checkErrorOn(device, what);
    }

    private boolean checkErrorOn(long d, String what) {
        if (d == 0L) return true;
        try {
            int err = ALC10.alcGetError(d);
            if (err != ALC10.ALC_NO_ERROR) {
                VoiceSpells.LOGGER.debug("ALC error 0x{} during {}", Integer.toHexString(err), what);
                return true;
            }
            return false;
        } catch (Throwable t) {
            return true;
        }
    }

    /** Warn at most once per failure episode; resets when the device comes back. */
    private void warnOnce(String message) {
        if (warnedThisEpisode) return;
        warnedThisEpisode = true;
        VoiceSpells.LOGGER.warn(message);
    }

    private static float rms(short[] frame) {
        long sum = 0;
        for (short s : frame) sum += (long) s * s;
        double mean = (double) sum / frame.length;
        // Scale against 16-bit full range so the value is comparable across devices.
        return (float) Math.min(1.0, Math.sqrt(mean) / 32768.0 * 4.0);
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }
}
