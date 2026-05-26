package org.tauasa.apps.rtlsdr.model;

/**
 * A point-in-time snapshot of all configurable RTL-SDR parameters.
 * Serialised to JSON and returned by {@code GET /api/device/state}.
 */
public record SdrState(
        boolean   open,
        int       deviceIndex,
        long      centerFreqHz,
        long      sampleRateHz,
        int       gainTenthsDb,
        boolean   autoGain,
        boolean   agcEnabled,
        int       freqCorrectionPpm,
        int       directSampling,
        boolean   offsetTuning,
        boolean   biasTee,
        boolean   streaming,
        TunerType tunerType,
        int[]     availableGains
) {}
