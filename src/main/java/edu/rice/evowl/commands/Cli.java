package edu.rice.evowl.commands;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Flag parser. Converts --key=value or --key value
 * pairs from the raw argument array into a Map<String,String>.
 * Boolean flags such as --overwrite are stored as the string "true".
 *
 * Typical usage:
 *   Cli cli = Cli.parse(args, 1);          // skip args[0] (subcommand name)
 *   String db   = cli.require("db");
 *   int    n    = cli.requireInt("tiers");
 *   String loc  = cli.optional("location", "strict-midpoint");
 *   boolean ow  = cli.isOverwrite();
 *
 * Help text for each subcommand is printed by printHelp (see below).
 */
public class Cli {

    /** Help text shown the program is invoked with no arguments or --help. */
    public static final String GLOBAL_HELP = """
        Usage:  evowl <subcommand> [--flag=value ...]

        Subcommands:
          roster             Load student roster from a CSV file
          groups             Load group membership from a JSON file
          assignment         Create an assignment (criteria + submissions)
          list-assignments   Print all assignments in a database
          grades             Load a grade CSV into the database
          parse              Extract covered source from Cobertura XML
          metrics            Compute code metrics for parsed submissions
          analyze            Rank groups with ELO and select grading targets
          grade              Interpolate final scores from analyze output
          grade-wo-tas       Interpolate without TA anchor grades
          extension          Give a student a deadline extension
          export             Export raw eval data as CSV
          grade-instr        Grade using instructor grades as anchors
          assign-tas         Create round-2 grading assignments
          default-config     Print the bundled default criteria config for a language

        Run  evowl <subcommand> --help  for flag details.
        """;

    /** Per-subcommand help text, shown when a subcommand is run with --help. */
    private static final java.util.Map<String, String> SUBCOMMAND_HELP = new java.util.LinkedHashMap<>();
    static {
        SUBCOMMAND_HELP.put("roster",
            """
            Usage:  evowl roster --db=NAME --file=PATH

            Flags:
              --db=NAME    Database name (required)
              --file=PATH  Path to roster CSV (last_name, first_name, user_id, role columns) (required)
            """);
        SUBCOMMAND_HELP.put("groups",
            """
            Usage:  evowl groups --db=NAME --file=PATH

            Flags:
              --db=NAME    Database name (required)
              --file=PATH  Path to groups JSON file (required)
            """);
        SUBCOMMAND_HELP.put("assignment",
            """
            Usage:  evowl assignment --db=NAME --name=STR --deadline=ISO --criteria=PATH --submissions=DIR [--overwrite]

            Flags:
              --db=NAME           Database name (required)
              --name=STR          Assignment name (required)
              --deadline=ISO      Student submission deadline, e.g. 2025-12-01 23:59:59 (required)
              --criteria=PATH     Path to criteria JSON file (required)
              --submissions=DIR   Path to submissions directory (required)
              --overwrite         Replace existing assignment of the same name
            """);
        SUBCOMMAND_HELP.put("list-assignments",
            """
            Usage:  evowl list-assignments --db=NAME

            Flags:
              --db=NAME    Database name (required)
            """);
        SUBCOMMAND_HELP.put("grades",
            """
            Usage:  evowl grades --db=NAME --assign=ID --kind=KIND --out=PATH

            Flags:
              --db=NAME      Database name (required)
              --assign=ID    Assignment ID (required)
              --kind=KIND    Grade type: mc, fr, pe, semc, sefr, inmc (required)
              --out=PATH     Path to the grade CSV file to load (required)
            """);
        SUBCOMMAND_HELP.put("parse",
            """
            Usage:  evowl parse --lang=LANG --submissions=DIR

            Flags:
              --lang=LANG        Language: golang or typescript (required)
              --submissions=DIR  Path to submissions directory (required)
            """);
        SUBCOMMAND_HELP.put("metrics",
            """
            Usage:  evowl metrics --lang=LANG --submissions=DIR

            Flags:
              --lang=LANG        Language: golang or typescript (required)
              --submissions=DIR  Path to submissions directory (required)
            """);
        SUBCOMMAND_HELP.put("analyze",
            """
            Usage:  evowl analyze --db=NAME --assign=ID --tiers=N --location=LOC --out=DIR [--overwrite]

            Flags:
              --db=NAME        Database name (required)
              --assign=ID      Assignment ID (required)
              --tiers=N        Number of grading tiers (required)
              --location=LOC   Tier selection strategy (required):
                                 strict-midpoint        — exact median group only
                                 strict-endpoints       — exact top and bottom groups only
                                 midpoint-tol           — groups within tolerance of median
                                 endpoints-tol          — groups within tolerance of endpoints
                                 any                    — all groups
                                 endpoints-and-midpoint — top, bottom, and median groups
              --out=DIR        Output directory for analyze files (required)
              --overwrite      Replace existing output directory
            """);
        SUBCOMMAND_HELP.put("grade",
            """
            Usage:  evowl grade --db=NAME --assign=ID --in=DIR --out=DIR [--overwrite]

            Flags:
              --db=NAME      Database name (required)
              --assign=ID    Assignment ID (required)
              --in=DIR       Directory of analyze output files (required)
              --out=DIR      Directory to write grade files to (required)
              --overwrite    Replace existing output directory
            """);
        SUBCOMMAND_HELP.put("grade-wo-tas",
            """
            Usage:  evowl grade-wo-tas --lower=F --upper=F --in=DIR --out=DIR [--overwrite]

            Flags:
              --lower=F    Lower grade bound (required)
              --upper=F    Upper grade bound (required)
              --in=DIR     Directory of analyze output files (required)
              --out=DIR    Directory to write grade files to (required)
              --overwrite  Replace existing output directory
            """);
        SUBCOMMAND_HELP.put("extension",
            """
            Usage:  evowl extension --db=NAME --assign=ID --userId=STR --deadline=ISO

            Flags:
              --db=NAME        Database name (required)
              --assign=ID      Assignment ID (required)
              --userId=STR     User ID (required)
              --deadline=ISO   New deadline, e.g. 2025-12-08 23:59:59 (required)
            """);
        SUBCOMMAND_HELP.put("export",
            """
            Usage:  evowl export --db=NAME --assign=ID --kind=KIND

            Flags:
              --db=NAME      Database name (required)
              --assign=ID    Assignment ID (required)
              --kind=KIND    Export type: peer, self, training, instructor (required)
            """);
        SUBCOMMAND_HELP.put("grade-instr",
            """
            Usage:  evowl grade-instr --db=NAME --assign=ID --in=DIR --out=DIR --instr-db=NAME --instr-assign=ID [--overwrite]

            Flags:
              --db=NAME          Peer database name (required)
              --assign=ID        Peer assignment ID (required)
              --in=DIR           Directory of analyze output files (required)
              --out=DIR          Directory to write grade files to (required)
              --instr-db=NAME    Instructor database name (required)
              --instr-assign=ID  Instructor assignment ID (required)
              --overwrite        Replace existing output directory
            """);
        SUBCOMMAND_HELP.put("assign-tas",
            """
            Usage:  evowl assign-tas --db=NAME --assign=ID --in=FILE

            Flags:
              --db=NAME      Database name (required)
              --assign=ID    Assignment ID (required)
              --in=FILE      Path to the grading input file (required)
            """);
        SUBCOMMAND_HELP.put("default-config",
            """
            Usage:  evowl default-config --lang=LANG

            Flags:
              --lang=LANG    Language whose default criteria to print: golang or typescript (required)
            """);
    }

    private final String subcommand;
    private final Map<String, String> flags;

    private Cli(String subcommand, Map<String, String> flags) {
        this.subcommand = subcommand;
        this.flags = flags;
    }

    /**
     * Parses the argument array starting at an index.
     *
     * @param args   raw command-line arguments (args[0] is the subcommand)
     * @param offset index of the first flag
     */
    public static Cli parse(String[] args, int offset) {
        String sub = (args.length > 0) ? args[0] : "";
        Map<String, String> flags = new LinkedHashMap<>();  // ordered map
        for (int i = offset; i < args.length; i++) {
            String arg = args[i];
            if (!arg.startsWith("--")) continue;
            int eq = arg.indexOf('=');
            String key;
            String value;
            if (eq != -1) {
                key = arg.substring(2, eq);
                value = arg.substring(eq + 1);
            } else {
                key = arg.substring(2);
                // next token (if it doesn't start with '--') is assumed to be the value
                if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                    value = args[++i];
                } else {
                    value = "true";   // bare flag → boolean true
                }
            }
            flags.put(key, value);
        }
        return new Cli(sub, flags);
    }

    /** Returns the subcommand (the first argument, args[0]). */
    public String getSubcommand() { return subcommand; }

    /**
     * Returns the value of a required flag.
     *
     * @throws IllegalArgumentException if the flag is absent
     */
    public String require(String key) {
        String v = flags.get(key);
        if (v == null) {
            throw new IllegalArgumentException("Missing required flag: --" + key + "; run with --help to see available flags.");
        }
        return v;
    }

    /**
     * Returns the integer value of a required flag.
     *
     * @throws IllegalArgumentException if the flag is absent or not a valid integer
     */
    public int requireInt(String key) {
        String v = require(key);
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("--" + key + " must be an integer, got: " + v);
        }
    }

    /**
     * Returns the double value of a required flag.
     *
     * @throws IllegalArgumentException if the flag is absent or not a valid number
     */
    public double requireDouble(String key) {
        String v = require(key);
        try {
            return Double.parseDouble(v);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("--" + key + " must be a number, got: " + v);
        }
    }

    /** Returns the value of an optional flag, or defaultValue if absent. */
    public String optional(String key, String defaultValue) {
        return flags.getOrDefault(key, defaultValue);
    }

    /** Returns the integer value of an optional flag, or defaultValue if absent. */
    public int optionalInt(String key, int defaultValue) {
        String v = flags.get(key);
        if (v == null) return defaultValue;
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("--" + key + " must be an integer, got: " + v);
        }
    }

    /**
     * Returns true if the flag was provided (bare --key or --key=true).
     */
    public boolean flag(String key) {
        return "true".equalsIgnoreCase(flags.getOrDefault(key, "false"));
    }

    /** Returns true if --help was provided. */
    public boolean isHelp() { return flag("help"); }

    /** Returns true if --overwrite was provided. */
    public boolean isOverwrite() { return flag("overwrite"); }

    /**
     * Parses --location=<value> into a LocationToGrade enum constant.
     *
     * @throws IllegalArgumentException if the flag is missing or its value is not recognized
     */
    public LocationToGrade requireLocation() {
        return LocationToGrade.fromString(require("location"));
    }

    /**
     * Parses --lang=<value> into a Language enum constant.
     *
     * @throws IllegalArgumentException if the flag is missing or its value is not recognized
     */
    public Language requireLanguage() {
        return Language.fromString(require("lang"));
    }

    /** Prints the global help text to stdout. */
    public static void printHelp() {
        System.out.print(GLOBAL_HELP);
    }

    /**
     * Prints help for a specific subcommand, or the global help if the subcommand is unknown.
     *
     * @param subcommand the name of the subcommand (e.g. "analyze")
     */
    public static void printHelp(String subcommand) {
        String text = SUBCOMMAND_HELP.get(subcommand);
        if (text != null) {
            System.out.print(text);
        } else {
            System.out.println("Unknown subcommand: " + subcommand);
            System.out.println();
            System.out.print(GLOBAL_HELP);
        }
    }
}
