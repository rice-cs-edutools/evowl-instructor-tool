package edu.rice.evowl.coverage;

import edu.rice.evowl.ast.GoAST;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

public class GoCoverageParser extends CoverageParser {
    /** The file paths of all Go files in the project path */
    private final List<String> goFilePaths = new ArrayList<>();
    /** A Map where the key is a Go file path and value is the GoAST object for that file */
    private final Map<String, GoAST> goASTs = new HashMap<>();
    /** The base directory path of the project */
    private final String baseDirectoryPath;
    /** The name of the new directory where the coverage output will be located */
    private final String coverageAfterDirName;

    /**
     * Constructs a GoCoverageParser object by parsing the coverage output from the given file.
     * @param coberturaFile The path to the coverage output file to be parsed.
     * @param baseDirectoryPath The base directory path of the project.
     * @param coverageAfterDirName The name of the new directory where the coverage output will be located.
     */
    public GoCoverageParser(String coberturaFile, String baseDirectoryPath, String coverageAfterDirName) {
        super(coberturaFile);
        this.baseDirectoryPath = baseDirectoryPath;
        this.coverageAfterDirName = coverageAfterDirName;
        this.collectGoFiles(new File(baseDirectoryPath));

        for (String filePath : this.goFilePaths) {
            this.goASTs.put(filePath, new GoAST(filePath));
        }
    }

    /**
     * Creates a Go file only including content marked as covered for each Go file in the project.
     */
    public void createGoFilesAfterCoverage() {
        for (String goFilePath : this.goFilePaths) {
            this.createGoFileAfterCoverage(goFilePath);
        }

        // Clean up
        runGoFmt();
        runGoImports();
    }

    /**
     * Creates a Go file only including content marked as covered for the given Go file path.
     * @param goFilePath The path to the Go file to be processed.
     */
    private void createGoFileAfterCoverage(String goFilePath) {
        GoAST goAST = this.goASTs.get(goFilePath);
        Map<Integer, String> coveredLines;

        // Get the content of the file
        String packageName = goAST.getPackageName();
        Map<Integer, String> imports = goAST.getImports();
        Map<Integer, String> types = goAST.getTypes();
        Map<Integer, String> variables = goAST.getVariables();
        Map<Integer, String> functions = goAST.getFunctions();
        Map<Integer, Integer> lineMap = goAST.getLineMap();

        // Filters lineMap for all "global" code scopes, i.e. removes nested code scopes
        lineMap = getFilteredStartToEndLines(lineMap);

        // Filters all extracted variables and type definitions for only global scoped definitions
        getGlobalDefinitions(variables, lineMap);
        getGlobalDefinitions(types, lineMap);

        /* Checks if the go file was included in the coverage output,
        and if not only consider the file if it has global variables or type definitions */
        if (super.getCoveredLines().containsKey(goFilePath)) {
            coveredLines = new LinkedHashMap<>(super.getCoveredLines().get(goFilePath));
        } else if (!goAST.getVariables().isEmpty() || !goAST.getTypes().isEmpty()) {
            coveredLines = new HashMap<>();
        } else {
            System.out.println("Skipping " + goFilePath +
                    " : Not in the coverage output nor has global variables/type definitions");
            return;
        }

        // Types and Variable definitions are not marked covered by "go test", manually adding here
        coveredLines.putAll(types);
        coveredLines.putAll(variables);

        LinkedHashMap<Integer, String> sortedCoveredLines = coveredLines.entrySet()
                .parallelStream()
                .sorted(Map.Entry.comparingByKey())
                .collect(LinkedHashMap::new, (map, entry) -> map.put(entry.getKey(), entry.getValue()), Map::putAll);

        StringBuilder sb = new StringBuilder();
        sb.append(packageName).append("\n");

        // Sort imports by their line number
        List<Integer> sortedImports = imports.keySet()
                .parallelStream()
                .sorted(Integer::compareTo)
                .collect(Collectors.toList());

        // Writes the imports
        for (Integer key : sortedImports) {
            sb.append(imports.get(key)).append("\n");
        }
        sb.append("\n");

        // Writes the covered lines (i.e. functions and the previously added variables and types)
        for (Map.Entry<Integer, String> entry : sortedCoveredLines.entrySet()) {
            if(entry.getValue() == null) {
                // sortedCoveredLines could include line numbers within function scopes
                // i.e. 25 when function scope is 20-30; only write 20-30
                if (functions.containsKey(entry.getKey())) {
                    sb.append(functions.get(entry.getKey())).append("\n\n");
                }
            } else {
                sb.append(entry.getValue()).append("\n\n");
            }
        }

        if (sortedCoveredLines.isEmpty() && variables.isEmpty() && types.isEmpty()) {
            System.out.println("Skipping " + goFilePath +
                    " : Not in the coverage output nor has global variables/type definitions");
            return;
        }

        // Writes to the coverage-after file
        String coverageText = sb.toString();
        File baseDirectory = new File(this.baseDirectoryPath);
        String relativeExportFilePath = goFilePath.substring(baseDirectory.getPath().length() + 1);
        String exportFilePath = this.baseDirectoryPath + File.separator +
                this.coverageAfterDirName + File.separator + relativeExportFilePath;
        this.writeFile(exportFilePath, coverageText);
    }

    /**
     * Populates the GoFilePaths with all Go files found in the given directory.
     *
     * @param directory The directory to be searched for Go files.
     */
    private void collectGoFiles(File directory) {
        File[] files = directory.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    collectGoFiles(file);
                } else if (file.isFile() && file.getName().endsWith(".go") && !file.getName().endsWith("_test.go")) {
                    this.goFilePaths.add(file.getPath());
                }
            }
        }
    }

    /**
     * Filters the definitionMap for all "global" definitions, i.e. removes local definitions
     * @param definitionMap the map of definitions to be filtered where keys are line numbers (Integers)
     * @param lineMap the map of start line numbers (Integers) to end line numbers (Integers) of global code scopes
     */
    private void getGlobalDefinitions(Map<Integer, String> definitionMap, Map<Integer, Integer> lineMap) {
        Set<Integer> localDefinitions = new HashSet<>();

        // Iterate over the lineMap to find the global scopes
        for (Map.Entry<Integer, Integer> entry : lineMap.entrySet()) {
            Integer startLine = entry.getKey();
            Integer endLine = entry.getValue();

            // Iterate over the definitionsMap to find the local definitions
            for (Map.Entry<Integer, String> definitionEntry : definitionMap.entrySet()) {
                Integer midLine = definitionEntry.getKey();
                if ((midLine >= startLine && midLine <= endLine) && !definitionMap.containsKey(startLine)) {
                    localDefinitions.add(definitionEntry.getKey());
                }
            }
        }

        definitionMap.keySet().removeAll(localDefinitions);
    }

    /**
     * Formats the Go files in the coverage-after directory.
     */
    private void runGoFmt () {
        try {
            ProcessBuilder processBuilder = new ProcessBuilder("go", "fmt");
            processBuilder.directory(new File
                    (this.baseDirectoryPath + File.separator + this.coverageAfterDirName));
            Process process = processBuilder.start();
            process.waitFor();
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Clean up unused imports for the Go files in the coverage-after directory.
     */
    private void runGoImports() {
        try {
            ProcessBuilder processBuilder = new ProcessBuilder("goimports", "-w", ".");
            processBuilder.directory(new File
                    (this.baseDirectoryPath + File.separator + this.coverageAfterDirName));
            Process process = processBuilder.start();
            process.waitFor();
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

     public static void main(String[] args) {
         // Check if the directory exists
         File directory = new File("Test/GoCover/assignment-group05");

         // Check if the cobertura file exists
         File coberturaFile = new File("Test/GoCover/assignment-group05" + File.separator + "coverage.xml");

         GoCoverageParser goCoverageParser = new GoCoverageParser(coberturaFile.getAbsolutePath(),
                 directory.getAbsolutePath(), "coverage-after");
         goCoverageParser.createGoFilesAfterCoverage();
    }
}

