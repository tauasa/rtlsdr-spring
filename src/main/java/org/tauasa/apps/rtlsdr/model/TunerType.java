package org.tauasa.apps.rtlsdr.model;

/**
 * Maps the integer tuner-type codes returned by {@code rtlsdr_get_tuner_type()}
 * to human-readable names and the rtl_tcp wire representation.
 */
public enum TunerType {

    UNKNOWN(0, "Unknown"),
    E4000  (1, "Elonics E4000"),
    FC0012 (2, "Fitipower FC0012"),
    FC0013 (3, "Fitipower FC0013"),
    FC2580 (4, "FCI FC2580"),
    R820T  (5, "Rafael Micro R820T"),
    R828D  (6, "Rafael Micro R828D");

    private final int    code;
    private final String displayName;

    TunerType(int code, String displayName) {
        this.code        = code;
        this.displayName = displayName;
    }

    /** The integer code used in the rtl_tcp protocol dongle-info header. */
    public int getCode() { return code; }

    public String getDisplayName() { return displayName; }

    public static TunerType fromCode(int code) {
        for (TunerType t : values()) {
            if (t.code == code) return t;
        }
        return UNKNOWN;
    }
}
