package org.tauasa.apps.rtlsdr.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Application-level configuration for the RTL-SDR server.
 * All values are read from {@code application.yml} under the {@code rtlsdr} prefix.
 */
@ConfigurationProperties(prefix = "rtlsdr")
public record RtlSdrProperties(

        /** Index of the RTL-SDR device to open on startup (or -1 to stay closed). */
        int deviceIndex,

        /** TCP port to bind the rtl_tcp-compatible server on. Default 6218. */
        int tcpPort,

        /** Whether to start the TCP server automatically. */
        boolean tcpAutoStart,

        /** Initial center frequency in Hz. */
        long initialFrequencyHz,

        /** Initial sample rate in Hz. */
        long initialSampleRateHz,

        /** Initial gain in tenths of dB (e.g. 496 = 49.6 dB). -1 = auto. */
        int initialGainTenthsDb,

        /** Number of libusb transfer buffers for the async read loop. 0 = default (32). */
        int asyncBufferCount,

        /**
         * Bytes per transfer buffer. Must be a multiple of 512.
         * 0 = default (16384). Larger values reduce CPU at cost of latency.
         */
        int asyncBufferLengthBytes

) {
    /** Canonical defaults mirroring rtl_tcp behaviour. */
    public RtlSdrProperties {
        if (tcpPort            <= 0)  tcpPort            = 6218;
        if (initialFrequencyHz <= 0)  initialFrequencyHz = 100_000_000L; // 100 MHz
        if (initialSampleRateHz<= 0)  initialSampleRateHz= 2_048_000L;   // 2.048 Msps
    }
}
