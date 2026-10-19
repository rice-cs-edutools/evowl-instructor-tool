package edu.rice.evowl.ast;

import edu.rice.evowl.coverage.CoverageParser;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

/**
 * This class is responsible for parsing the Go AST JSON file and
 * extracting the necessary information to generate the coverage report.
 */
public class GoAST {
    /** The file path of the JSON file being parsed */
    private final String outputPath;
    
    /** The file path of the Go file being parsed */
    private final String filePath;
    
    /** The Go AST JSON object */
    private final JSONObject ast;
    
    /** The path to the Go AST parser */
    static final String GO_AST_PARSER_PATH = "src/main/java/edu/rice/evowl/ast/gocode/go_ast_parser/main.go";

    /**
     * Constructs a GoAST object by parsing the Go AST from the given file.
     * @param filePath The path to the Go file to be parsed.
     */
    public GoAST(String filePath) {
        this.filePath = filePath;
        this.outputPath = runGoAst();
        this.ast = parseAST();
    }

    /**
     * Returns the package name of this Go file.
     * @return The package name of this Go file.
     */
    public String getPackageName() {
        String packageComment = this.ast.get("PackageComment").toString();
        String text = "package " + this.ast.get("Package").toString();
        if (!packageComment.isEmpty()) {
            // check if comment is multi-line or one line by checking there is more than one line break
            if (packageComment.split("\n").length > 1) {
                text = "/*\n" + packageComment.substring(0, packageComment.lastIndexOf("\n")) + "\n*/\n" + text;
            } else {
                text = "// " + packageComment + text;
            }
            return text;
        } else {
            return text;
        }
    }

    /**
     * Returns the import section of the Go AST JSON file.
     * @return The import section of the Go AST JSON file.
     */
    public Map<Integer, String> getImports() {
        return this.getSection("Imports");
    }

    /**
     * Returns the functions section of the Go AST JSON file.
     * @return The functions section of the Go AST JSON file.
     */
    public Map<Integer, String> getFunctions() {
        return getSection("Functions");
    }

    /**
     * Returns the variables section of the Go AST JSON file.
     * @return The variables section of the Go AST JSON file.
     */
    public Map<Integer, String> getTypes() {
        return getSection("Types");
    }

    /**
     * Returns the variables section of the Go AST JSON file.
     * @return The variables section of the Go AST JSON file.
     */
    public Map<Integer, String> getVariables() {
        return getSection("Variables");
    }

    /**
     * Returns the line map section of the Go AST JSON file.
     * @return The line map section of the Go AST JSON file.
     */
    public Map<Integer, Integer> getLineMap() {
        Map<Integer, Integer> sectionMap = new HashMap<>();
        for (Map.Entry<String, Map<String, Object>> entry : this.getRawSection("LineMap").entrySet()) {
            sectionMap.put(((Long)entry.getValue().get("startLine")).intValue(),
                    ((Long) entry.getValue().get("endLine")).intValue());
        }
        return sectionMap;
    }

    /**
     * Parses the Go AST JSON file and returns the JSON object.
     * @return The Go AST JSON object.
     */
    private JSONObject parseAST() {
        try {
            String jsonContent = new String(Files.readAllBytes(Paths.get(this.outputPath)));
            return (JSONObject) (new JSONParser()).parse(jsonContent);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read or parse AST file", e);
        } catch (ParseException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Returns the section of the Go AST JSON file with the given name.
     * @param sectionName The name of the section to be returned.
     * @return The section of the Go AST JSON file with the given name.
     */
    private Map<Integer, String> getSection(String sectionName) {
        Map<Integer, String> sectionMap = new HashMap<>();
        try {
            for (Map.Entry<String, Map<String, Object>> entry : this.getRawSection(sectionName).entrySet()) {
                String inScopeText = CoverageParser.getTextInScope(this.filePath,
                        ((Long) entry.getValue().get("startLine")).intValue(),
                        ((Long) entry.getValue().get("startColumn")).intValue()-1,
                        ((Long) entry.getValue().get("endLine")).intValue(),
                        ((Long) entry.getValue().get("endColumn")).intValue()-1);

                if (!((String) entry.getValue().get("associatedComment")).isEmpty()) {
                    String comment = entry.getValue().get("associatedComment").toString();

                    // check if comment is multi-line or one line by checking there is more than one line break
                    if (comment.split("\n").length > 1) {
                        inScopeText = "/*\n" + comment.substring(0, comment.lastIndexOf("\n")) + " \n*/\n" + inScopeText;
                    } else {
                        inScopeText = "// " + comment + inScopeText;
                    }
                }

                sectionMap.put(Integer.valueOf(entry.getKey()), inScopeText);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return sectionMap;
    }

    /**
     * Returns the raw section of the Go AST JSON file with the given name
     * (i.e. the section startLine, ..., endLine information without the text)
     * @param sectionName The name of the section to be returned.
     * @return The raw section of the Go AST JSON file with the given name.
     */
    private Map<String, Map<String, Object>> getRawSection(String sectionName) {
        JSONObject section = (JSONObject) ast.get(sectionName);
        Map<String, Map<String, Object>> sectionResult = new HashMap<>();
        for (Object key : section.keySet()) {
            sectionResult.put((String) key, new HashMap<>((Map<String, Object>) section.get(key)));
        }
        return sectionResult;
    }

    /**
     * Runs the Go AST parser on the given Go file and returns the output path.
     * @return The output path of the Go AST parser.
     */
    private String runGoAst () {
        String fileName = new File(this.filePath).getName();
        String fileDir = new File(this.filePath).getParent();

        try {
            ProcessBuilder processBuilder = new ProcessBuilder("go",
                    "run", GO_AST_PARSER_PATH, fileDir, fileName);

            processBuilder.redirectOutput(ProcessBuilder.Redirect.INHERIT);
            processBuilder.redirectError(ProcessBuilder.Redirect.INHERIT);

            Process process = processBuilder.start();
            process.waitFor();

            // check if the output file exists
            File outputFile = new File(fileDir + File.separator + fileName + "_output.json");
            if (!outputFile.exists()) {
                throw new RuntimeException("Failed to run Go AST parser and create .go_output.json file for " + this.outputPath);
            }

            return fileDir + File.separator + fileName + "_output.json";
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }
}