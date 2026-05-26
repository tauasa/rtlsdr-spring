package org.tauasa.apps.rtlsdr.model;

/**
 * Immutable snapshot of a detected RTL-SDR device.
 */
public record DeviceInfo(
        int    index,
        String name,
        String manufacturer,
        String product,
        String serial
) {}
