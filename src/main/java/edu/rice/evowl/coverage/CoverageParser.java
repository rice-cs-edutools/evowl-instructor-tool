package edu.rice.evowl.coverage;

import org.w3c.dom.*;
import org.xml.sax.SAXException;

import javax.xml.parsers.*;
import java.io.*;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

public class CoverageParser {

    /** The file path of the coverage output being parsed */
    private final String coberturaFile;

    /** A Map where the key is a typescript file and value is a
     *  list of all covered lines in that file */
    private final Map<String, Map<Integer, String>> coveredLines;

    /** A Map where the key is a typescript file and value is a
     *  list of all uncovered functions in the XML file */
    private final Map<String, List<Integer>> uncoveredFunctions;

    /**
     * Constructs a CoverageParser object by parsing the coverage output from the given file.
     * @param coberturaFile The path to the coverage output file to be parsed.
     */
    public CoverageParser(String coberturaFile) {
        this.coberturaFile = coberturaFile;
        this.coveredLines = extractCoveredLines();
        this.uncoveredFunctions = extractUncoveredFuncs();
    }

    /**
     * Gets the text in the scope of the given lines for the given file.
     *
     * @param filePath path to the TypeScript file being parsed
     * @param startLine line where the scope starts
     * @param startColumn column where the scope starts
     * @param endLine line where the scope ends
     * @param endColumn column where the scope ends
     * @return the text in the scope
     * @throws IOException if the file cannot be read
     */
    public static String getTextInScope(String filePath, int startLine, int startColumn, int endLine, int endColumn) throws IOException {
        List<String> lines = Files.readAllLines(Paths.get(filePath));
        StringBuilder sb = new StringBuilder();

        for (int i = startLine - 1; i < endLine; i++) {
            String line = lines.get(i);

            if (i == startLine - 1 && i == endLine - 1) {
                // Reading one line
                sb.append(line, startColumn, endColumn);
            } else if (i == startLine - 1) {
                // Reading first line
                sb.append(line.substring(startColumn)).append("\n");
            } else if (i == endLine - 1) {
                // Reading last line
                sb.append(line, 0, endColumn);
            } else {
                sb.append(line).append("\n");
            }
        }
        return sb.toString();
    }

    /**
     * Gets the text in the scope of the given lines for the given file.
     * @param filePath path to the TypeScript file being parsed
     * @param startLine line where the scope starts
     * @param endLine line where the scope ends
     * @return the text in the scope
     * @throws IOException if the file cannot be read
     */
    public static String getTextInScope(String filePath, int startLine, int endLine) throws IOException {
        List<String> lines = Files.readAllLines(Paths.get(filePath));
        StringBuilder sb = new StringBuilder();

        for (int i = startLine - 1; i < endLine; i++) {
            String line = lines.get(i);
            sb.append(line).append("\n");
        }

        return sb.toString();
    }

    /**
     * Executes the given commands in the given directory.
     *
     * @param pathToDirectory the path to the directory to execute the commands in
     * @param commands the commands to execute
     */
    public static void executeCommand(String pathToDirectory, List<String[]> commands) {
        try {
            // Set the working directory
            File directory = new File(pathToDirectory);

            // Execute the commands
            for (String[] command : commands) {
                System.out.println("Starting command: " + String.join(" ", command));

                ProcessBuilder processBuilder = new ProcessBuilder();
                processBuilder.command(command);
                processBuilder.directory(directory);

                Process process = processBuilder.start();
                BufferedReader bf = new BufferedReader(new InputStreamReader(process.getErrorStream()));

                if (command[0] == "gocover-cobertura") {
                    // Feed coverage.out into stdin of the process
                    try (OutputStream os = process.getOutputStream();
                         FileInputStream fis = new FileInputStream(directory.toPath().resolve("coverage.txt").toString())) {
                        fis.transferTo(os);
                    }

                    // Capture stdout (the XML output)
                    try (InputStream is = process.getInputStream();
                         FileOutputStream fos = new FileOutputStream(directory.toPath().resolve("coverage.xml").toString())) {
                        is.transferTo(fos);
                    }
                }

                int exitCode = process.waitFor();

                String line;
                StringBuilder sb = new StringBuilder();
                while((line = bf.readLine()) != null) {
                    sb.append(line).append("\n");
                }

                if (exitCode != 0) {
                    System.err.println("Command failed: " + Arrays.toString(command));
                    System.err.println("Error message: " + sb);
                } else {
                    System.out.println("Command succeeded");
                }
            }

        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
        }
    }

    /**
     * Returns a list of all covered lines in the XML file
     * @return Map of all covered lines
     */
    protected Map<String, Map<Integer, String>> getCoveredLines() {
        return this.coveredLines;
    }

    /**
     * Returns a list of the start lines of all uncovered functions in the XML file
     * @return map of the start lines of all uncovered functions in the XML file
     */
    protected Map<String, List<Integer>> getUncoveredFuncs() {
        return this.uncoveredFunctions;
    }

    /**
     * Cleans up the mapping of start lines to end lines by only keeping the biggest scopes.
     * For example, if 50 -> 70 exists, and 60 -> 69 exists, then 60 -> 69 will be deleted in favor of 50 -> 70.
     *
     * @param startToEndLines Map of start lines to end lines
     * @return Map of start lines to the biggest end line
     */
    protected Map<Integer, Integer> getFilteredStartToEndLines(Map<Integer, Integer> startToEndLines) {
        Map<Integer, Integer> filtered = new HashMap<>(startToEndLines);
        for (Map.Entry<Integer, Integer> outer : startToEndLines.entrySet()) {
            int outerStart = outer.getKey();
            int outerEnd = outer.getValue();

            for (Map.Entry<Integer, Integer> inner : startToEndLines.entrySet()) {
                if (outer.getKey().equals(inner.getKey())) continue;

                int innerStart = inner.getKey();
                int innerEnd = inner.getValue();

                if (outerStart <= innerStart && outerEnd >= innerEnd) {
                    filtered.remove(inner.getKey());
                }
            }
        }
        return filtered;
    }

    /**
     * Writes the given content to the given file.
     *
     * @param exportFilePath path to the file to be written to
     * @param content content to be written to the file
     */
    protected void writeFile(String exportFilePath, String content) {
        File file = new File(exportFilePath);
        try {
            // Delete the file if it already exists
            if (file.exists() && !file.delete()) {
                System.err.println("Failed to delete the file.");
            }

            // Create the parent directories if they don't exist
            if (!file.getParentFile().mkdirs() && !file.getParentFile().exists()) {
                System.err.println("Failed to create directories.");
            }

            // Create the file
            if (!file.createNewFile()) {
                System.err.println("File failed to create.");
                throw new IOException("Unable to create file: " + file.getAbsolutePath());
            }

            try (FileWriter fileWriter = new FileWriter(file)) {
                fileWriter.write(content);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Parses the XML file and returns a list of all covered lines, ordered by their line number
     * @return Map of all covered lines
     */
    private Map<String, Map<Integer, String>> extractCoveredLines() {
        File coberturaFile = new File(this.coberturaFile);
        Map<String, Map<Integer, String>> coveredLinesByClass = new LinkedHashMap<>();

        try {
            DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
            dbFactory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);

            DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
            Document doc = dBuilder.parse(coberturaFile);
            doc.getDocumentElement().normalize();

            // Go specific, fixes the path of the files
            File cobertura = new File(this.coberturaFile);
            Map<String, String> moduleNames = this.getModuleNames(cobertura.getParentFile().getPath());

            // Each <package> element contains a list of <class> elements
            for (Element packageElement : getChildrenElements(doc.getElementsByTagName("package"))) {
                // Iterates over <class> elements in the current <package> element
                for (Element classElement : getChildrenElements(packageElement.getElementsByTagName("class"))) {
                    String className = classElement.getAttribute("filename");

                    // Go specific, fixes the path of the files
                    String matchedModule = moduleNames.keySet().parallelStream()
                        .filter(className::contains)
                        .findFirst()
                        .orElse(null);
                    if (matchedModule != null) {
                        className = (new File(this.coberturaFile)).getParent() + File.separator +
                            (new File(className)).getPath().substring(matchedModule.length() + 1);

                    } else if (className.contains(".go")) {
                        String packageName = packageElement.getAttribute("name");
                        if (moduleNames.containsKey(packageName)) {
                            className = moduleNames.get(packageName) + File.separator + className;
                        } else {
                            className = (new File(this.coberturaFile)).getParent() + File.separator + className;
                        }
                    }

                    Map<Integer, String> coveredLines = new LinkedHashMap<>();

                    // Iterates over <lines> elements in the current <class> element
                    for (Element lineElement : getChildrenElements(classElement.getElementsByTagName("line"))) {
                        if (Integer.parseInt(lineElement.getAttribute("hits")) > 0) {
                            coveredLines.put(Integer.valueOf(lineElement.getAttribute("number")), null);
                        }
                    }

                    // Sorts the covered lines by their line number
                    Map<Integer, String> sortedCoveredLines = coveredLines.entrySet()
                        .stream()
                        .sorted(Map.Entry.comparingByKey())
                        .collect(LinkedHashMap::new, (map, entry) -> map.put(entry.getKey(), entry.getValue()), Map::putAll);

                    System.out.println("filename: " + className);
                    if (coveredLinesByClass.containsKey(className)) {
                        coveredLinesByClass.get(className).putAll(sortedCoveredLines);
                    } else {
                        coveredLinesByClass.put(className, sortedCoveredLines);
                    }
                }
            }
        } catch (ParserConfigurationException | SAXException | IOException e) {
            throw new RuntimeException(e);
        }

        return coveredLinesByClass;
    }

    /**
     * Parses the coverage XML file and returns a list of the
     * start lines of all uncovered functions in the XML file
     * @return map of the start lines of all uncovered functions in the XML file
     */
    private Map<String, List<Integer>> extractUncoveredFuncs() {
        File coberturaFile = new File(this.coberturaFile);
        Map<String, List<Integer>> uncoveredFuncByClass = new HashMap<>();

        try {
            // Builds an iterable DOM tree from the XML file
            DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();

            // Disallows loading the Document Type Definition (DTD) associated with the XML file (i.e. coverage-04.dtd)
            dbFactory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);

            DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
            Document doc = dBuilder.parse(coberturaFile);
            doc.getDocumentElement().normalize();

            // Each <package> element contains a list of <class> elements
            for (Element packageElement : getChildrenElements(doc.getElementsByTagName("package"))) {
                // Iterates over <class> elements in the current <package> element
                for (Element classElement : getChildrenElements(packageElement.getElementsByTagName("class"))) {
                    String className = classElement.getAttribute("filename");
                    List<Integer> uncoveredLines = new ArrayList<>();

                    // Iterates over <lines> elements in the current <class> element
                    for (Element lineElement : getChildrenElements(classElement.getElementsByTagName("line"))) {
                        if (Integer.parseInt(lineElement.getAttribute("hits")) == 0) {
                            uncoveredLines.add(Integer.valueOf(lineElement.getAttribute("number")));
                        }
                    }

                    uncoveredFuncByClass.put(className, uncoveredLines);
                }
            }
        } catch (ParserConfigurationException | SAXException | IOException e) {
            throw new RuntimeException(e);
        }
        return uncoveredFuncByClass;
    }

    /**
     * Returns a list of <element> elements contained in the given <nodeList>.
     * @param nodeList The list of nodes to be filtered
     * @return A list of <element> elements contained in the given <nodeList>
     */
    private List<Element> getChildrenElements(NodeList nodeList) {
        List<Element> children = new ArrayList<>();
        for (int i = 0; i < nodeList.getLength(); i++) {
            Node node = nodeList.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE) {
                Element element = (Element) node;
                children.add(element);
            }
        }
        return children;
    }

    /**
     * Gets the module names (from go.mod files) mapped to their system path within the go project
     *
     * @param projectPath The path to the go project
     * @return A Map of module names (from go.mod files) within the go project to
     * their system path within the go project
     */
    private Map<String, String> getModuleNames(String projectPath) {
        Map<String, String> moduleToPath = new HashMap<>();
        List<String> goModFiles = this.getAllGoModFiles(projectPath);

        for (String goModFile : goModFiles) {
            try {
                Path path = Paths.get(goModFile);
                List<String> goModLines = Files.readAllLines(path);
                String moduleLine = goModLines.stream()
                    .filter(line -> line.startsWith("module"))
                    .findFirst()
                    .orElseThrow(() -> new RuntimeException("Module declaration not found in go.mod"));

                String moduleName = moduleLine.substring("module ".length()).trim();
                moduleToPath.put(moduleName, path.getParent().toString());
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        return moduleToPath;
    }

    /**
     * Gets all go.mod files in the given project path
     *
     * @param projectPath The path to the go project
     * @return A list of all go.mod files in the given project path
     */
    private List<String> getAllGoModFiles(String projectPath) {
        List<String> goModFiles = new ArrayList<>();
        File[] files = new File(projectPath).listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    goModFiles.addAll(getAllGoModFiles(file.getPath()));
                } else if (file.isFile() && file.getName().equals("go.mod")) {
                    goModFiles.add(file.getPath());
                }
            }
        }
        return goModFiles;
    }
}
