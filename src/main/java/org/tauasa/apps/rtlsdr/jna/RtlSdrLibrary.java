package org.tauasa.apps.rtlsdr.jna;

import org.tauasa.apps.rtlsdr.model.TunerType;

import com.sun.jna.Callback;
import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;

/**
 * JNA bindings for librtlsdr.
 *
 * <p>Mirrors the public API of rtl-sdr.h. Function signatures follow the C library
 * exactly — uint32_t maps to {@code int} (JNA handles unsigned promotion), while
 * 64-bit frequencies use {@code long}.
 *
 * <p>Loading strategy: JNA searches for {@code librtlsdr.so} (Linux),
 * {@code librtlsdr.dylib} (macOS), or {@code rtlsdr.dll} (Windows) on the
 * system library path. Install the OS package ({@code librtlsdr-dev} / brew) or
 * place the shared library next to the JAR.
 *
 * <p>JNA does not synchronize native calls by default, which is what we want —
 * the async read loop runs on its own thread and must not block REST/TCP threads.
 */
public interface RtlSdrLibrary extends Library {

    /**
     * Lazy holder — {@code Native.load} is deferred until first access.
     * This prevents an {@link UnsatisfiedLinkError} from crashing the Spring
     * context on machines where librtlsdr is not installed.
     */
    final class Holder {
        public static final RtlSdrLibrary INSTANCE;
        public static final Throwable     LOAD_ERROR;
        static {
            RtlSdrLibrary lib = null;
            Throwable     err = null;
            try {
                lib = Native.load("rtlsdr", RtlSdrLibrary.class);
            } catch (Throwable t) {
                err = t;
            }
            INSTANCE   = lib;
            LOAD_ERROR = err;
        }
        private Holder() {}
    }

    // -------------------------------------------------------------------------
    // Device enumeration
    // -------------------------------------------------------------------------

    /** Returns the number of RTL-SDR devices detected. */
    int rtlsdr_get_device_count();

    /** Returns a human-readable name for the device at {@code index}. */
    String rtlsdr_get_device_name(int index);

    /**
     * Retrieves the USB string descriptors for a device.
     *
     * @param index   device index
     * @param manufact 256-byte buffer for manufacturer string
     * @param product  256-byte buffer for product string
     * @param serial   256-byte buffer for serial string
     * @return 0 on success, negative on error
     */
    int rtlsdr_get_device_usb_strings(int index, byte[] manufact, byte[] product, byte[] serial);

    /**
     * Returns the device index matching the given serial string, or negative on failure.
     */
    int rtlsdr_get_index_by_serial(String serial);

    // -------------------------------------------------------------------------
    // Open / close
    // -------------------------------------------------------------------------

    /**
     * Opens device at {@code index} and writes the handle into {@code dev}.
     *
     * @return 0 on success
     */
    int rtlsdr_open(PointerByReference dev, int index);

    /**
     * Closes the device and releases all associated resources.
     *
     * @return 0 on success
     */
    int rtlsdr_close(Pointer dev);

    // -------------------------------------------------------------------------
    // Crystal / clock
    // -------------------------------------------------------------------------

    int rtlsdr_set_xtal_freq(Pointer dev, int rtlFreqHz, int tunerFreqHz);
    int rtlsdr_get_xtal_freq(Pointer dev, IntByReference rtlFreqHz, IntByReference tunerFreqHz);

    // -------------------------------------------------------------------------
    // Tuner
    // -------------------------------------------------------------------------

    /**
     * Sets the center frequency in Hz.
     *
     * @return 0 on success
     */
    int rtlsdr_set_center_freq(Pointer dev, long freqHz);

    /**
     * Returns the current center frequency in Hz, or 0 on error.
     */
    long rtlsdr_get_center_freq(Pointer dev);

    /**
     * Sets the frequency correction in parts-per-million.
     */
    int rtlsdr_set_freq_correction(Pointer dev, int ppm);

    /** Returns the current frequency correction in PPM. */
    int rtlsdr_get_freq_correction(Pointer dev);

    /**
     * Returns the tuner type as an integer corresponding to {@link TunerType} enum values.
     */
    int rtlsdr_get_tuner_type(Pointer dev);

    /**
     * Writes available gain levels (tenths of dB) into {@code gains}.
     * Pass {@code null} to query the count.
     *
     * @return number of gain values on success
     */
    int rtlsdr_get_tuner_gains(Pointer dev, int[] gains);

    /**
     * Sets the tuner gain in tenths of dB (e.g., 100 = 10.0 dB).
     * Requires gain mode to be set to manual first.
     */
    int rtlsdr_set_tuner_gain(Pointer dev, int gainTenthsDb);

    /** Returns the current tuner gain in tenths of dB. */
    int rtlsdr_get_tuner_gain(Pointer dev);

    /**
     * Sets the tuner IF bandwidth in Hz. 0 = automatic (default).
     */
    int rtlsdr_set_tuner_bandwidth(Pointer dev, int bwHz);

    /**
     * Sets gain mode: 0 = automatic (hardware AGC), 1 = manual.
     */
    int rtlsdr_set_tuner_gain_mode(Pointer dev, int manual);

    /**
     * Sets gain for a specific IF stage (E4000 only).
     *
     * @param stage  1–6
     * @param gain   gain in tenths of dB
     */
    int rtlsdr_set_tuner_if_gain(Pointer dev, int stage, int gainTenthsDb);

    // -------------------------------------------------------------------------
    // Sample rate / data path
    // -------------------------------------------------------------------------

    /**
     * Sets the sample rate in Hz. Valid range: ~225001–300000, 900001–3200000 Hz.
     */
    int rtlsdr_set_sample_rate(Pointer dev, long rateHz);

    /** Returns the current sample rate in Hz. */
    long rtlsdr_get_sample_rate(Pointer dev);

    /**
     * Enables or disables test mode (replaces samples with a counter ramp).
     * Useful for verifying the pipeline without RF input.
     */
    int rtlsdr_set_testmode(Pointer dev, int on);

    /**
     * Enables or disables the RTL2832 digital AGC.
     *
     * @param on 1 = enable
     */
    int rtlsdr_set_agc_mode(Pointer dev, int on);

    /**
     * Sets direct sampling mode.
     * <ul>
     *   <li>0 = disabled (normal RF path)</li>
     *   <li>1 = I-ADC input enabled</li>
     *   <li>2 = Q-ADC input enabled</li>
     * </ul>
     */
    int rtlsdr_set_direct_sampling(Pointer dev, int on);

    /** Returns the current direct-sampling mode. */
    int rtlsdr_get_direct_sampling(Pointer dev);

    /**
     * Enables offset tuning (avoids DC spike at center; E4000 tuner only).
     */
    int rtlsdr_set_offset_tuning(Pointer dev, int on);

    /** Returns 1 if offset tuning is enabled. */
    int rtlsdr_get_offset_tuning(Pointer dev);

    /**
     * Enables the bias-tee (V3 hardware and later).
     *
     * @param on 1 = power on the SMA connector
     */
    int rtlsdr_set_bias_tee(Pointer dev, int on);

    // -------------------------------------------------------------------------
    // Streaming
    // -------------------------------------------------------------------------

    /**
     * Resets the internal sample buffer — call before starting a read loop.
     */
    int rtlsdr_reset_buffer(Pointer dev);

    /**
     * Synchronous read: fills {@code buf} with up to {@code len} bytes of IQ data.
     *
     * @param nRead receives the actual number of bytes read
     * @return 0 on success
     */
    int rtlsdr_read_sync(Pointer dev, Pointer buf, int len, IntByReference nRead);

    /**
     * Starts the async read loop. Blocks until {@link #rtlsdr_cancel_async} is called.
     * The callback is invoked from the librtlsdr event loop thread.
     *
     * @param cb      callback invoked for each buffer of IQ samples
     * @param ctx     user context pointer passed to the callback (may be null)
     * @param bufNum  number of USB transfer buffers (0 = default 32)
     * @param bufLen  bytes per buffer (0 = default 16384; must be multiple of 512)
     */
    int rtlsdr_read_async(Pointer dev, ReadAsyncCallback cb, Pointer ctx,
                          int bufNum, int bufLen);

    /**
     * Signals the async read loop to stop. Safe to call from any thread.
     */
    int rtlsdr_cancel_async(Pointer dev);

    // -------------------------------------------------------------------------
    // Callback interface
    // -------------------------------------------------------------------------

    /**
     * Functional interface for {@code rtlsdr_read_async_cb_t}.
     * Implementors must be extremely careful: this is called on a native thread.
     * Do NOT perform blocking operations here; copy the data and return fast.
     */
    interface ReadAsyncCallback extends Callback {
        /**
         * @param buf pointer to the interleaved IQ byte buffer (unsigned 8-bit, offset by 128)
         * @param len number of bytes (I+Q pairs, so len/2 complex samples)
         * @param ctx user context (unused here; always null)
         */
        void invoke(Pointer buf, int len, Pointer ctx);
    }
}
