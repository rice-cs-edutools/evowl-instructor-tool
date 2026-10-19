package edu.rice.evowl.commands;

import edu.rice.evowl.coverage.GoCoverageParser;
import edu.rice.evowl.coverage.TsCoverageParser;
import edu.rice.evowl.FileUtils;

import java.io.File;

/**
 * Implements the parse command, which reads a Cobertura XML coverage file from
 * each submission subdirectory and creates a coverage-after directory containing
 * only the source files and lines executed during testing.
 *
 * Supported languages: Language.GOLANG, Language.TYPESCRIPT.
 */
public class Parse {

    /** Name of the output subdirectory written by this command. */
    public static final String COVERAGE_AFTER_DIR = "coverage-after";

    /**
     * Processes a single submission directory, creating a COVERAGE_AFTER_DIR
     * subdirectory that contains only the code covered during testing.
     *
     * If the submission directory does not exist, the call is skipped with a log message.
     * An existing COVERAGE_AFTER_DIR directory is replaced (logged explicitly).
     * A missing coverage.xml file causes the directory to be skipped (logged explicitly).
     *
     * @param language               target programming language
     * @param pathToSubmissionsDir   path to a single group's submission directory
     * @param coverageAfterDirName   name of the output subdirectory
     */
    public static void parseFiles(Language language, String pathToSubmissionsDir, String coverageAfterDirName) {
        File directory = new File(pathToSubmissionsDir);
        if (!directory.exists()) {
            System.out.println("Directory does not exist: " + pathToSubmissionsDir + ". Skipping.");
            return;
        }

        File coverageAfterDir = new File(pathToSubmissionsDir + File.separator + coverageAfterDirName);
        if (coverageAfterDir.exists()) {
            System.out.println("Replacing existing " + coverageAfterDirName + " at " + pathToSubmissionsDir);
            if (!FileUtils.deleteDirectory(coverageAfterDir)) {
                System.out.println("Failed to delete existing " + coverageAfterDirName + ". Skipping.");
                return;
            }
        }

        File coberturaFile = new File(pathToSubmissionsDir + File.separator + "coverage.xml");
        if (!coberturaFile.exists()) {
            System.out.println("Skipping " + pathToSubmissionsDir + ": coverage.xml not found.");
            return;
        }

        switch (language) {
            case GOLANG -> new GoCoverageParser(coberturaFile.getAbsolutePath(),
                    directory.getAbsolutePath(), coverageAfterDirName).createGoFilesAfterCoverage();
            case TYPESCRIPT -> new TsCoverageParser(coberturaFile.getAbsolutePath(),
                    directory.getAbsolutePath(), coverageAfterDirName).createTypeScriptFilesAfterCoverage();
        }
    }
}
