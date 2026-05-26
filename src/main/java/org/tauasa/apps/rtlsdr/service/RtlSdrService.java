package org.tauasa.apps.rtlsdr.service;

import com.sun.jna.Memory;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import org.tauasa.apps.rtlsdr.config.RtlSdrProperties;
import org.tauasa.apps.rtlsdr.jna.RtlSdrLibrary;
import org.tauasa.apps.rtlsdr.model.DeviceInfo;
import org.tauasa.apps.rtlsdr.model.SdrState;
import org.tauasa.apps.rtlsdr.model.TunerType;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * Core service wrapping librtlsdr.
 *
 * <p>All device operations are protected by {@link #lock} to prevent concurrent
 * access from REST calls, TCP clients, and the async read thread.
 *
 * <h2>IQ data consumers</h2>
 * Register a {@link Consumer}{@code <byte[]>} via {@link #addIqConsumer}. Each
 * buffer delivered to the callback is a fresh copy safe to hand off to other threads.
 * The TCP server and WebSocket handler both use this mechanism.
 */
@Service
public class RtlSdrService {

    private static final Logger log = LoggerFactory.getLogger(RtlSdrService.class);

    private final RtlSdrLibrary         lib   = RtlSdrLibrary.INSTANCE;
    private final RtlSdrProperties      props;
    private final ReentrantLock         lock  = new ReentrantLock();
    private final AtomicBoolean         streaming = new AtomicBoolean(false);
    private final List<Consumer<byte[]>> iqConsumers = new CopyOnWriteArrayList<>();

    // Guarded by lock
    private Pointer   device;
    private int       deviceIndex       = -1;
    private long      centerFreqHz;
    private long      sampleRateHz;
    private int       gainTenthsDb      = 0;
    private boolean   autoGain          = true;
    private boolean   agcEnabled        = false;
    private int       freqCorrectionPpm = 0;
    private int       directSampling    = 0;
    private boolean   offsetTuning      = false;
    private boolean   biasTee           = false;
    private TunerType tunerType         = TunerType.UNKNOWN;
    private int[]     availableGains    = new int[0];

    // Keep a strong reference to the callback so it isn't GC'd while native code holds it
    @SuppressWarnings("FieldCanBeLocal")
    private RtlSdrLibrary.ReadAsyncCallback asyncCallback;

    private final ExecutorService asyncThread =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "rtlsdr-async");
                t.setDaemon(true);
                return t;
            });

    public RtlSdrService(RtlSdrProperties props) {
        this.props = props;
    }

    // =========================================================================
    // Device management
    // =========================================================================

    /** Lists all RTL-SDR devices currently attached to the system. */
    public List<DeviceInfo> listDevices() {
        int count = lib.rtlsdr_get_device_count();
        List<DeviceInfo> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            byte[] mfr  = new byte[256];
            byte[] prod = new byte[256];
            byte[] ser  = new byte[256];
            lib.rtlsdr_get_device_usb_strings(i, mfr, prod, ser);
            result.add(new DeviceInfo(
                    i,
                    lib.rtlsdr_get_device_name(i),
                    nullTerminated(mfr),
                    nullTerminated(prod),
                    nullTerminated(ser)
            ));
        }
        return result;
    }

    /**
     * Opens the device at {@code index} and applies the configured initial settings.
     *
     * @throws IllegalStateException if a device is already open
     * @throws RtlSdrException       on librtlsdr failure
     */
    public void open(int index) {
        lock.lock();
        try {
            if (device != null) throw new IllegalStateException("A device is already open");

            PointerByReference ref = new PointerByReference();
            int rc = lib.rtlsdr_open(ref, index);
            checkRc("rtlsdr_open", rc);

            device      = ref.getValue();
            deviceIndex = index;

            // Read tuner capabilities
            tunerType     = TunerType.fromCode(lib.rtlsdr_get_tuner_type(device));
            availableGains = readAvailableGains();

            log.info("Opened device {} — tuner: {}", index, tunerType.getDisplayName());

            // Apply initial settings
            applyFrequency(props.initialFrequencyHz());
            applySampleRate(props.initialSampleRateHz());
            if (props.initialGainTenthsDb() < 0) {
                applyAutoGain(true);
            } else {
                applyAutoGain(false);
                applyGain(props.initialGainTenthsDb());
            }
        } finally {
            lock.unlock();
        }
    }

    /** Stops streaming (if running) and closes the device. */
    public void close() {
        lock.lock();
        try {
            if (device == null) return;
            if (streaming.get()) stopStreaming();
            lib.rtlsdr_close(device);
            device      = null;
            deviceIndex = -1;
            tunerType   = TunerType.UNKNOWN;
            log.info("Device closed");
        } finally {
            lock.unlock();
        }
    }

    public boolean isOpen() {
        lock.lock();
        try { return device != null; }
        finally { lock.unlock(); }
    }

    // =========================================================================
    // Configuration — all apply immediately if a device is open
    // =========================================================================

    public void setFrequency(long hz) {
        lock.lock();
        try {
            requireOpen();
            applyFrequency(hz);
        } finally {
            lock.unlock();
        }
    }

    public void setSampleRate(long hz) {
        lock.lock();
        try {
            requireOpen();
            applySampleRate(hz);
        } finally {
            lock.unlock();
        }
    }

    /** Sets gain in tenths of dB. Also disables auto-gain if it was on. */
    public void setGain(int tenthsDb) {
        lock.lock();
        try {
            requireOpen();
            applyAutoGain(false);
            applyGain(tenthsDb);
        } finally {
            lock.unlock();
        }
    }

    public void setAutoGain(boolean auto) {
        lock.lock();
        try {
            requireOpen();
            applyAutoGain(auto);
        } finally {
            lock.unlock();
        }
    }

    public void setAgcMode(boolean enabled) {
        lock.lock();
        try {
            requireOpen();
            checkRc("rtlsdr_set_agc_mode", lib.rtlsdr_set_agc_mode(device, enabled ? 1 : 0));
            agcEnabled = enabled;
            log.debug("AGC mode: {}", enabled);
        } finally {
            lock.unlock();
        }
    }

    public void setFreqCorrection(int ppm) {
        lock.lock();
        try {
            requireOpen();
            checkRc("rtlsdr_set_freq_correction",
                    lib.rtlsdr_set_freq_correction(device, ppm));
            freqCorrectionPpm = ppm;
            log.debug("Freq correction: {} ppm", ppm);
        } finally {
            lock.unlock();
        }
    }

    public void setDirectSampling(int mode) {
        lock.lock();
        try {
            requireOpen();
            checkRc("rtlsdr_set_direct_sampling",
                    lib.rtlsdr_set_direct_sampling(device, mode));
            directSampling = mode;
            log.debug("Direct sampling: {}", mode);
        } finally {
            lock.unlock();
        }
    }

    public void setOffsetTuning(boolean enabled) {
        lock.lock();
        try {
            requireOpen();
            checkRc("rtlsdr_set_offset_tuning",
                    lib.rtlsdr_set_offset_tuning(device, enabled ? 1 : 0));
            offsetTuning = enabled;
            log.debug("Offset tuning: {}", enabled);
        } finally {
            lock.unlock();
        }
    }

    public void setBiasTee(boolean enabled) {
        lock.lock();
        try {
            requireOpen();
            checkRc("rtlsdr_set_bias_tee",
                    lib.rtlsdr_set_bias_tee(device, enabled ? 1 : 0));
            biasTee = enabled;
            log.debug("Bias-tee: {}", enabled);
        } finally {
            lock.unlock();
        }
    }

    public void setTunerIfGain(int stage, int tenthsDb) {
        lock.lock();
        try {
            requireOpen();
            checkRc("rtlsdr_set_tuner_if_gain",
                    lib.rtlsdr_set_tuner_if_gain(device, stage, tenthsDb));
        } finally {
            lock.unlock();
        }
    }

    public void setTunerBandwidth(int hz) {
        lock.lock();
        try {
            requireOpen();
            checkRc("rtlsdr_set_tuner_bandwidth",
                    lib.rtlsdr_set_tuner_bandwidth(device, hz));
        } finally {
            lock.unlock();
        }
    }

    // =========================================================================
    // Streaming
    // =========================================================================

    /**
     * Starts the async IQ sample loop. Calls
     * {@code rtlsdr_read_async} on a dedicated background thread.
     */
    public void startStreaming() {
        lock.lock();
        try {
            requireOpen();
            if (streaming.get()) {
                log.warn("Streaming already active — ignoring startStreaming()");
                return;
            }
            checkRc("rtlsdr_reset_buffer", lib.rtlsdr_reset_buffer(device));

            final Pointer devRef = device; // capture before releasing lock

            asyncCallback = (buf, len, ctx) -> {
                if (len > 0) {
                    byte[] data = buf.getByteArray(0, len);
                    for (Consumer<byte[]> consumer : iqConsumers) {
                        try { consumer.accept(data); }
                        catch (Exception e) { log.warn("IQ consumer threw", e); }
                    }
                }
            };

            streaming.set(true);

            asyncThread.submit(() -> {
                log.info("Async IQ read loop starting");
                int rc = lib.rtlsdr_read_async(
                        devRef,
                        asyncCallback,
                        null,
                        props.asyncBufferCount(),
                        props.asyncBufferLengthBytes()
                );
                streaming.set(false);
                if (rc != 0) {
                    log.warn("rtlsdr_read_async returned {}", rc);
                } else {
                    log.info("Async IQ read loop stopped cleanly");
                }
            });

        } finally {
            lock.unlock();
        }
    }

    /** Cancels the async loop. Returns once the loop thread has finished. */
    public void stopStreaming() {
        lock.lock();
        try {
            if (!streaming.get() || device == null) return;
            log.info("Stopping IQ stream…");
            lib.rtlsdr_cancel_async(device);
        } finally {
            lock.unlock();
        }
        // Spin-wait for the async thread to clear the flag (usually < 200 ms)
        long deadline = System.currentTimeMillis() + 3_000;
        while (streaming.get() && System.currentTimeMillis() < deadline) {
            Thread.onSpinWait();
        }
    }

    public boolean isStreaming() { return streaming.get(); }

    // =========================================================================
    // IQ consumer registration
    // =========================================================================

    public void addIqConsumer(Consumer<byte[]> consumer) {
        iqConsumers.add(consumer);
    }

    public void removeIqConsumer(Consumer<byte[]> consumer) {
        iqConsumers.remove(consumer);
    }

    // =========================================================================
    // State snapshot
    // =========================================================================

    public SdrState getState() {
        lock.lock();
        try {
            return new SdrState(
                    device != null,
                    deviceIndex,
                    centerFreqHz,
                    sampleRateHz,
                    gainTenthsDb,
                    autoGain,
                    agcEnabled,
                    freqCorrectionPpm,
                    directSampling,
                    offsetTuning,
                    biasTee,
                    streaming.get(),
                    tunerType,
                    Arrays.copyOf(availableGains, availableGains.length)
            );
        } finally {
            lock.unlock();
        }
    }

    // =========================================================================
    // Synchronous read (alternative to async loop)
    // =========================================================================

    /**
     * Performs a single synchronous read of {@code lengthBytes} bytes.
     * Useful for one-shot captures; caller must call {@link #resetBuffer()} first.
     *
     * @return the IQ byte array (may be shorter than requested on error)
     */
    public byte[] readSync(int lengthBytes) {
        lock.lock();
        try {
            requireOpen();
            Memory buf      = new Memory(lengthBytes);
            IntByReference nRead = new IntByReference(0);
            int rc = lib.rtlsdr_read_sync(device, buf, lengthBytes, nRead);
            if (rc != 0) throw new RtlSdrException("rtlsdr_read_sync failed: " + rc);
            return buf.getByteArray(0, nRead.getValue());
        } finally {
            lock.unlock();
        }
    }

    public void resetBuffer() {
        lock.lock();
        try {
            requireOpen();
            lib.rtlsdr_reset_buffer(device);
        } finally {
            lock.unlock();
        }
    }

    // =========================================================================
    // Lifecycle
    // =========================================================================

    @PreDestroy
    public void shutdown() {
        log.info("Shutting down RTL-SDR service…");
        close();
        asyncThread.shutdown();
        try { asyncThread.awaitTermination(5, TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    private void applyFrequency(long hz) {
        checkRc("rtlsdr_set_center_freq", lib.rtlsdr_set_center_freq(device, hz));
        centerFreqHz = hz;
        log.debug("Center frequency: {} Hz", hz);
    }

    private void applySampleRate(long hz) {
        checkRc("rtlsdr_set_sample_rate", lib.rtlsdr_set_sample_rate(device, hz));
        sampleRateHz = hz;
        log.debug("Sample rate: {} Hz", hz);
    }

    private void applyAutoGain(boolean auto) {
        checkRc("rtlsdr_set_tuner_gain_mode",
                lib.rtlsdr_set_tuner_gain_mode(device, auto ? 0 : 1));
        autoGain = auto;
        log.debug("Auto-gain: {}", auto);
    }

    private void applyGain(int tenthsDb) {
        checkRc("rtlsdr_set_tuner_gain", lib.rtlsdr_set_tuner_gain(device, tenthsDb));
        gainTenthsDb = tenthsDb;
        log.debug("Gain: {} (tenths dB)", tenthsDb);
    }

    private int[] readAvailableGains() {
        int count = lib.rtlsdr_get_tuner_gains(device, null);
        if (count <= 0) return new int[0];
        int[] gains = new int[count];
        lib.rtlsdr_get_tuner_gains(device, gains);
        return gains;
    }

    private void requireOpen() {
        if (device == null) throw new IllegalStateException("No device open");
    }

    private static void checkRc(String fn, int rc) {
        if (rc != 0) throw new RtlSdrException(fn + " failed with code " + rc);
    }

    private static String nullTerminated(byte[] buf) {
        int len = 0;
        while (len < buf.length && buf[len] != 0) len++;
        return new String(buf, 0, len, StandardCharsets.UTF_8).trim();
    }

    // -------------------------------------------------------------------------
    // Exception
    // -------------------------------------------------------------------------

    public static class RtlSdrException extends RuntimeException {
        public RtlSdrException(String msg) { super(msg); }
    }
}
