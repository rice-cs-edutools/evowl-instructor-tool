package edu.rice.evowl.commands;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;

/**
 * Implements the metrics command, which runs language-specific metric scripts on
 * each submission's coverage-after/ directory and writes a metrics.json
 * file alongside the covered source.
 *
 * The parse command must be run before metrics so that the
 * coverage-after/ directories exist.
 *
 * Supported languages: Language.GOLANG, Language.TYPESCRIPT.
 */
public class Metrics {

    /** Path to the Go metrics-analyzer source, relative to the project root. */
    static final String METRICS_GO_DIRECTORY =
            "src/main/java/edu/rice/evowl/metrics/go-metrics";

    /** Path to the compiled TypeScript metrics bundle, relative to the TS metrics directory. */
    static final String METRICS_TS_FILE = "build/main.js";

    /** Path to the TypeScript metrics source, relative to the project root. */
    static final String METRICS_TS_DIRECTORY =
            "src/main/java/edu/rice/evowl/metrics/typescript-metrics";

    /**
     * Computes metrics for all submission subdirectories found under
     * pathToSubmissionsDirectory.
     *
     * @param language                 target programming language
     * @param pathToSubmissionsDirectory  path to the directory containing one subdirectory per submission
     * @param coverageAfterDirName     name of the subdirectory written by the parse command
     */
    public static void computeMetrics(Language language,
                                      String pathToSubmissionsDirectory,
                                      String coverageAfterDirName) {
        File submissionsDirectory = new File(pathToSubmissionsDirectory);
        if (!submissionsDirectory.exists()) {
            System.out.println("Submissions directory does not exist: " + pathToSubmissionsDirectory);
            return;
        }

        String[] students = submissionsDirectory.list();
        if (students == null) return;

        for (String studentName : students) {
            File studentDir = submissionsDirectory.toPath().resolve(studentName).toFile();
            if (!studentDir.isDirectory()) continue;

            File coverageDirectory = studentDir.toPath().resolve(coverageAfterDirName).toFile();
            if (!coverageDirectory.exists()) {
                System.out.println("Run 'parse' before 'metrics'. Missing " + coverageAfterDirName
                        + " for: " + studentName);
                return;
            }

            System.out.println("Computing metrics for: " + studentName);
            try {
                switch (language) {
                    case GOLANG -> runGoStudentMetrics(coverageDirectory);
                    case TYPESCRIPT -> runTypescriptStudentMetrics(coverageDirectory);
                }
            } catch (RuntimeException e) {
                System.out.println("Error computing metrics for " + studentName + ": " + e.getMessage());
            }
        }
    }

    /**
     * Runs the Go metrics analyzer on a single coverage-after/ directory,
     * producing metrics.json in that directory.
     *
     * @param coverageDirectory the coverage-after/ directory for one submission
     * @throws RuntimeException if the metrics script fails or the output file is not produced
     */
    public static void runGoStudentMetrics(File coverageDirectory) {
        String metricsJsonFile = coverageDirectory.toPath().resolve("metrics.json")
                .toAbsolutePath().toString();
        String coverageDirPath = coverageDirectory.toPath().toAbsolutePath().toString();

        try {
            // Copy go.mod into the coverage directory so the analyzer can resolve imports
            Path src = coverageDirectory.getParentFile().toPath().resolve("go.mod");
            Path dest = coverageDirectory.toPath().resolve("go.mod");
            Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING);

            ProcessBuilder processBuilder = new ProcessBuilder(
                    "go", "run", ".", coverageDirPath, metricsJsonFile);
            processBuilder.directory(new File(METRICS_GO_DIRECTORY));
            processBuilder.redirectOutput(ProcessBuilder.Redirect.INHERIT);
            processBuilder.redirectError(ProcessBuilder.Redirect.INHERIT);
            processBuilder.start().waitFor();

            if (!new File(metricsJsonFile).exists()) {
                throw new RuntimeException("metrics.json was not produced");
            }
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Runs the TypeScript metrics script on a single coverage-after/ directory,
     * producing metrics.json in that directory.
     *
     * @param coverageDirectory the coverage-after/ directory for one submission
     * @throws RuntimeException if the metrics script fails or the output file is not produced
     */
    public static void runTypescriptStudentMetrics(File coverageDirectory) {
        String metricsJsonFile = coverageDirectory.toPath().resolve("metrics.json")
                .toAbsolutePath().toString();
        String coverageDirPath = coverageDirectory.toPath().toAbsolutePath().toString();

        try {
            // Copy tsconfig.json into the coverage directory so the analyzer can compile
            Path src = coverageDirectory.getParentFile().toPath().resolve("tsconfig.json");
            Path dest = coverageDirectory.toPath().resolve("tsconfig.json");
            Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING);

            ProcessBuilder processBuilder = new ProcessBuilder(
                    "node", METRICS_TS_FILE, coverageDirPath, metricsJsonFile);
            processBuilder.directory(new File(METRICS_TS_DIRECTORY));
            processBuilder.redirectOutput(ProcessBuilder.Redirect.INHERIT);
            processBuilder.redirectError(ProcessBuilder.Redirect.INHERIT);
            processBuilder.start().waitFor();

            if (!new File(metricsJsonFile).exists()) {
                throw new RuntimeException("metrics.json was not produced");
            }
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }
}
