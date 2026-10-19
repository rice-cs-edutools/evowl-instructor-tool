package edu.rice.evowl.coverage;

import edu.rice.evowl.ast.TsAST;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;
import java.util.logging.Logger;
import java.util.logging.Level;
import java.util.stream.Collectors;

/**
 * Parser for TypeScript coverage files that processes coverage data and generates filtered TypeScript files
 * containing only covered code.
 */
public class TsCoverageParser extends CoverageParser {

    private static final Logger logger = Logger.getLogger(TsCoverageParser.class.getName());

    /** The AST of the TypeScript files being parsed */
    private final Map<String, TsAST> typeScriptASTs;

    /** The file paths of the TypeScript files being parsed */
    private final List<String> typeScriptFilePaths;

    /** The base path to where the TypeScript files for parsing will be found */
    private final String baseDirectoryPath;

    /** The name of the new directory where the coverage output will be located */
    private final String coverageAfterDirName;

    /**
     * Creates a new TypeScriptCoverageParser object.
     *
     * @param coberturaFile      The path to the coverage file to be parsed
     * @param baseDirectoryPath  The base path to where the TypeScript files for parsing will be found
     * @param coverageAfterDirName The name of the directory where processed files will be written
     */
    public TsCoverageParser(String coberturaFile, String baseDirectoryPath, String coverageAfterDirName) {
        super(coberturaFile);
        this.coverageAfterDirName = coverageAfterDirName;
        this.baseDirectoryPath = baseDirectoryPath;
        this.typeScriptASTs = new HashMap<>();
        this.typeScriptFilePaths = new ArrayList<>();

        this.collectTypeScriptFiles(new File(baseDirectoryPath, "src"));
        for (String filePath : this.typeScriptFilePaths) {
            this.typeScriptASTs.put(filePath, new TsAST(filePath));
        }
    }

    /**
     * Processes all discovered TypeScript files to create coverage-filtered versions.
     */
    public void createTypeScriptFilesAfterCoverage() {
        for (String typeScriptFilePath : this.typeScriptFilePaths) {
            try {
                createTypeScriptFileAfterCoverage(typeScriptFilePath);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    /**
     * Creates a filtered version of a TypeScript file containing only covered code.
     *
     * @param typeScriptFilePath The path to the TypeScript file to be processed
     */
    private void createTypeScriptFileAfterCoverage(String typeScriptFilePath) throws IOException {
        if (typeScriptFilePath == null || typeScriptFilePath.isEmpty()) {
            throw new IllegalArgumentException("TypeScript file path cannot be null or empty");
        }

        logger.info(() -> String.format("Creating coverage-after file for %s", typeScriptFilePath));

        TsAST currentAST = typeScriptASTs.get(typeScriptFilePath);
        if (currentAST == null) {
            throw new IllegalStateException("No AST found for file: " + typeScriptFilePath);
        }

        String fileName = new File(typeScriptFilePath).getPath();
        if (!fileName.contains("src/")) {
            throw new IllegalArgumentException("Invalid file path structure - must contain 'src/' directory");
        }
        fileName = fileName.substring(fileName.indexOf("src/"));

        // Create the directory if it doesn't exist
        File baseDirectory = new File(this.baseDirectoryPath);
        if (!baseDirectory.exists()) {
            throw new IllegalStateException("Base directory does not exist: " + this.baseDirectoryPath);
        }
        baseDirectory = baseDirectory.getCanonicalFile();

        if (this.coverageAfterDirName == null || this.coverageAfterDirName.isEmpty()) {
            throw new IllegalStateException("Coverage after directory name is not set");
        }

        String relativeExportFilePath = typeScriptFilePath.substring(baseDirectory.getPath().length() + 1);
        String exportFilePath = this.baseDirectoryPath + File.separator + this.coverageAfterDirName +
                File.separator + relativeExportFilePath;

        File outputFile = new File(exportFilePath).getCanonicalFile();
        try {
            prepareOutputFile(outputFile);
            boolean passed = writeFilteredContent(outputFile, fileName, currentAST);
            if (passed) {
                cleanupTypeScriptFile(exportFilePath);
            } else {
                // Delete the prepared output file
                outputFile.delete();
            }
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Failed to process file: " + typeScriptFilePath, e);
            throw new RuntimeException("Failed to process TypeScript file", e);
        }
    }

    /**
     * Prepares the output file by ensuring directories exist and file is ready for writing.
     * @param file The file to be prepared
     */
    private void prepareOutputFile(File file) throws IOException {
        if (file.exists() && !file.delete()) {
            logger.warning("Failed to delete existing file: " + file.getPath());
        }

        if (!file.getParentFile().exists() && !file.getParentFile().mkdirs()) {
            logger.severe("Failed to create directory structure for: " + file.getPath());
            throw new IOException("Unable to create directory structure");
        }

        if (!file.createNewFile()) {
            logger.severe("Failed to create new file: " + file.getPath());
            throw new IOException("Unable to create file: " + file.getAbsolutePath());
        }
    }

    /**
     * Writes the filtered content to the output file.
     * @param file The output file to write to
     * @param fileName The name of the file being processed
     * @param currentAST The AST of the TypeScript file being processed
     */
    private boolean writeFilteredContent(File file, String fileName, TsAST currentAST) throws IOException {
        try (FileWriter fileWriter = new FileWriter(file)) {
            Map<Integer, String> coveredLines = super.getCoveredLines().get(fileName);
            if (coveredLines == null) {
                logger.warning("No covered lines found for file: " + fileName);

                // Delete this file and return
                if (!file.delete()) {
                    logger.warning("Failed to delete file: " + file.getPath());
                }
                return false;
            }

            // Get the start line to end line mapping for the current AST
            Map<Integer, Integer> startToEndLines = currentAST.getStartLineToEndLineMap();
            Map<Integer, Integer> filteredStartToEndLines = getFilteredStartToEndLines(startToEndLines);

            // Add missing elements to the coveredLines map
            addMissingElements(coveredLines, currentAST);

            // Sort the coveredLines map by key
            LinkedHashMap<String, String> updatedCoveredLines = coveredLines.entrySet()
                    .parallelStream()
                    .sorted(Map.Entry.comparingByKey(Comparator.comparing(Integer::valueOf)))
                    .collect(LinkedHashMap::new, 
                            (map, entry) -> map.put(String.valueOf(entry.getKey()), entry.getValue()), 
                            Map::putAll);

            // Build the filtered content
            String content = buildFilteredContent(fileName, currentAST, updatedCoveredLines, filteredStartToEndLines);

            // Write the filtered content to the output file
            fileWriter.write(content);
        }
        return true;
    }

    /**
     * Builds the filtered content by processing covered lines and removing uncovered functions.
     * @param fileName The name of the TypeScript file
     * @param currentAST The AST for the TypeScript file
     * @param updatedCoveredLines The updated covered lines (i.e., the covered lines with their corresponding content)
     * @param filteredStartToEndLines The filtered start-to-end line mappings (i.e., largest scope start-to-end line mappings)
     * @return The filtered content as a string to be written to the output file
     */
    private String buildFilteredContent(String fileName, TsAST currentAST,
                                      LinkedHashMap<String, String> updatedCoveredLines,
                                      Map<Integer, Integer> filteredStartToEndLines) throws IOException {
        StringBuilder sb = new StringBuilder();

        // Remove uncovered functions that somehow ended up in the covered lines
        List<Integer> uncoveredLines = super.getUncoveredFuncs().get(fileName);
        if (uncoveredLines != null) {
             updatedCoveredLines.keySet().removeAll(uncoveredLines.stream()
                    .map(String::valueOf)
                    .collect(Collectors.toSet()));
        }

        // Process each covered line
        for (Map.Entry<String, String> entry : updatedCoveredLines.entrySet()) {
            if (filteredStartToEndLines.containsKey(Integer.parseInt(entry.getKey()))) {
                processContentEntry(sb, entry, filteredStartToEndLines, currentAST, fileName);
            }
        }

        return sb.toString();
    }

    /**
     * Processes a single content entry, handling associated comments if present.
     * @param sb The StringBuilder to append content to.
     * @param entry The entry to process.
     * @param filteredStartToEndLines The filtered start to end lines map.
     * @param currentAST The current TypeScriptAST.
     */
    private void processContentEntry(StringBuilder sb, Map.Entry<String, String> entry,
                                   Map<Integer, Integer> filteredStartToEndLines,
                                   TsAST currentAST, String fileName) throws IOException {

        // Extract the start and end line numbers from the entry key
        int startLineNum = Integer.parseInt(entry.getKey());
        int endLineNum = filteredStartToEndLines.get(startLineNum);

        // Check if there's an associated comment for the start line
        int effectiveStartLine = startLineNum;
        Map<Integer, Integer> commentMap = currentAST.getCommentMap();
        Optional<Map.Entry<Integer, Integer>> associatedComment = commentMap.entrySet().stream()
                .filter(comment -> comment.getValue() == startLineNum - 1)
                .findFirst();

        if (associatedComment.isPresent() && !Objects.equals(associatedComment.get().getKey(), associatedComment.get().getValue())) {
            // If there's an associated comment, use its line number as the start line
            effectiveStartLine = associatedComment.get().getKey();
        } else {
            // Check for sequential single-line comments
            int currentLine = startLineNum - 1;
            while (currentLine > 0) {
                // Check if this line is a comment that points to the next line
                if (commentMap.containsKey(currentLine)) {
                    effectiveStartLine = currentLine;
                    currentLine--;
                } else {
                    break;
                }
            }
        }

        String text = getTextInScope(currentAST.getFilePath(), effectiveStartLine, endLineNum);

        // If a class is being added to the content, remove any uncovered functions within the class
        List<Integer> uncoveredFunc = super.getUncoveredFuncs().get(fileName);
        if (currentAST.getClassesMap().containsKey(startLineNum) && uncoveredFunc != null &&
                uncoveredFunc.stream().anyMatch(line -> line >= startLineNum && line <= endLineNum)) {

            List<Integer> uncoveredLines = new ArrayList<>();
            for (int line : uncoveredFunc) {
                if (line >= startLineNum && line <= endLineNum) {
                        associatedComment = commentMap.entrySet().stream()
                                .filter(comment -> comment.getValue() == line - 1)
                                .findFirst();

                        int effectiveUncoveredLine = line;
                        if (associatedComment.isPresent() && !Objects.equals(associatedComment.get().getKey(), associatedComment.get().getValue())) {
                            effectiveUncoveredLine = associatedComment.get().getKey();
                        } else {
                            int currentLine = line - 1;
                            while (currentLine > 0) {
                                if (commentMap.containsKey(currentLine)) {
                                    effectiveUncoveredLine = currentLine;
                                    currentLine--;
                                } else {
                                    break;
                                }
                            }
                        }
                        uncoveredLines.add(effectiveUncoveredLine);
                }
            }
            text = removeUncoveredFunctions(text, uncoveredLines, currentAST);
        }
        sb.append(text);
    }

    /**
     * Removes uncovered functions from the content.
     * @param content The content to remove uncovered functions from.
     * @param uncoveredFuncs A list of line numbers for uncovered functions.
     * @param currentAST The current ast being used to create this coverage after file
     * @return The content with uncovered functions removed.
     */
    private String removeUncoveredFunctions(String content, List<Integer> uncoveredFuncs, TsAST currentAST) {
        if (uncoveredFuncs == null) {
            return content;
        }

        for (Integer line : uncoveredFuncs) {
            String functionText = currentAST.getFunctionMap().get(line);
            if (functionText == null) {
                continue;
            }

            // Handles the case where we're considering an export statement with the unused function
            if (content.contains("export " + functionText)) {
                content = content.replace("export " + functionText, "");
            } else {
                content = content.replace(functionText, "");
            }
        }
        return content;
    }

    /**
     * Cleans up a TypeScript file by formatting it with prettier.
     * @param filePath The path to the TypeScript file to be cleaned up.
     */
    private void cleanupTypeScriptFile(String filePath) {
        try {
            ProcessBuilder prettierProcess = new ProcessBuilder(
                "npx",
                "prettier",
                "--write",
                filePath
            );

            prettierProcess.inheritIO();
            prettierProcess.directory(new File("src/main/resources/ts_eslint_parser"));
            Process prettierResult = prettierProcess.start();
            
            if (prettierResult.waitFor() != 0) {
                logger.severe("Failed to format file with prettier: " + filePath);
            }

        } catch (IOException | InterruptedException e) {
            logger.log(Level.SEVERE, "Error cleaning up TypeScript file: " + filePath, e);
        }
    }

    /**
     * Adds missing elements from the given map to the covered lines map.
     * @param coveredLines The map containing covered lines.
     * @param ast The AST object to get the missing elements.
     */
    private void addMissingElements(Map<Integer, String> coveredLines, TsAST ast) {
        addMissingElementsFromMap(coveredLines, ast.getVariableMap());
        addMissingElementsFromMap(coveredLines, ast.getClassesMap());
        addMissingElementsFromMap(coveredLines, ast.getTypeDeclaration());
        addMissingElementsFromMap(coveredLines, ast.getInterfaceMap());
        addMissingElementsFromMap(coveredLines, ast.getModuleMap());
        addMissingElementsFromMap(coveredLines, ast.getImportMap());
    }

    /**
     * Adds missing elements from the given map to the covered lines map.
     * @param coveredLines The map containing covered lines.
     * @param elementMap The map containing elements to be added.
     */
    private void addMissingElementsFromMap(Map<Integer, String> coveredLines, Map<Integer, ?> elementMap) {
        elementMap.keySet().stream()
                .filter(line -> !coveredLines.containsKey(line))
                .forEach(line -> coveredLines.put(line, null));
    }

    /**
     * Recursively collects TypeScript files from the given directory, excluding test files and node_modules.
     * @param directory The directory to start collecting TypeScript files from.
     */
    private void collectTypeScriptFiles(File directory) {
        File[] files = directory.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory() && !file.getName().equals("node_modules")) {
                    collectTypeScriptFiles(file);
                } else if (file.isFile() && file.getName().endsWith(".ts") && !file.getName().endsWith(".test.ts")) {
                    logger.info("Found TypeScript file: " + file.getPath());
                    this.typeScriptFilePaths.add(file.getPath());
                }
            }
        }
    }
}
