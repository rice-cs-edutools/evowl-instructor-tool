package edu.rice.evowl.commands;

/**
 * Strategy for selecting which group within each ELO tier should be sent for grading.
 * Passed to the analyze command via --location=<value>.
 *
 * 0 → STRICT_MIDPOINT
 * 1 → STRICT_ENDPOINTS
 * 2 → MIDPOINT_WITH_TOLERANCE
 * 3 → ENDPOINTS_WITH_TOLERANCE
 * 4 → ANY
 * 5 → ENDPOINTS_AND_MIDPOINT
 */
public enum LocationToGrade {
    STRICT_MIDPOINT("strict-midpoint"),
    STRICT_ENDPOINTS("strict-endpoints"),
    MIDPOINT_WITH_TOLERANCE("midpoint-tol"),
    ENDPOINTS_WITH_TOLERANCE("endpoints-tol"),
    ANY("any"),
    ENDPOINTS_AND_MIDPOINT("endpoints-and-midpoint");

    private final String cliValue;

    LocationToGrade(String cliValue) {
        this.cliValue = cliValue;
    }

    /**
     * Parses a --location=<value> flag into the matching enum constant.
     *
     * @throws IllegalArgumentException if no match is found
     */
    public static LocationToGrade fromString(String value) {
        for (LocationToGrade loc : values()) {
            if (loc.cliValue.equalsIgnoreCase(value)) {
                return loc;
            }
        }
        throw new IllegalArgumentException(
            "Unknown --location value " + value + ". Valid values: " +
            "strict-midpoint, strict-endpoints, midpoint-tol, endpoints-tol, any, endpoints-and-midpoint"
        );
    }
}
