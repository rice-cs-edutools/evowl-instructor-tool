package edu.rice.evowl.commands;

/**
 * Supported submission programming languages understood by the parse and metrics commands.
 */
public enum Language {
    GOLANG("golang"),
    TYPESCRIPT("typescript");

    private final String cliValue;

    Language(String cliValue) {
        this.cliValue = cliValue;
    }

    /**
     * Returns the Language whose CLI identifier matches value (case-insensitive).
     *
     * @throws IllegalArgumentException if no match is found
     */
    public static Language fromString(String value) {
        for (Language lang : values()) {
            if (lang.cliValue.equalsIgnoreCase(value)) {
                return lang;
            }
        }
        throw new IllegalArgumentException(
            "Unsupported language " + value + ". Valid values: golang, typescript"
        );
    }

}
