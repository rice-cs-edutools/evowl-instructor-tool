package edu.rice.evowl.ast;

import edu.rice.evowl.coverage.CoverageParser;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import java.io.*;
import java.util.*;
import java.util.logging.Logger;

/**
 * This class represents a TypeScript Abstract Syntax Tree (AST) parser.
 * It parses a TypeScript file and provides methods to access various elements of the AST.
 */
public class TsAST {

    /** Logger for logging messages */
    private static final Logger logger = Logger.getLogger(TsAST.class.getName());

    /** Map of class start lines to their corresponding declarations */
    private final Map<Integer, String> classesMap;

    /** The file path of the TypeScript file being parsed */
    private final String filePath;

    /** Map of function start lines to their corresponding declarations */
    private final Map<Integer, String> functionMap;

    /** Map of start line to end line mapping for scoped code blocks */
    private final Map<Integer, Integer> startLineToEndLineMap;

    /** Map of variable names to their corresponding declarations */
    private final Map<Integer, String> variableMap;

    /** Map of type alias start line to their corresponding declarations */
    private final Map<Integer, String> typeDeclaration;

    /** Map of interface start line to their corresponding declarations */
    private final Map<Integer, String> interfaceMap;

    /** Map of module start line to their corresponding declarations */
    private final Map<Integer, String> moduleMap;

    /** Map of import start line to their corresponding declarations */
    private final Map<Integer, String> importMap;

    /** Map of comment start lines to their end lines */
    private final Map<Integer, Integer> commentMap;

    /** The path to the TypeScript parser */
    static final String TS_ESLINT_PARSER_PATH = "src/main/java/edu/rice/evowl/ast/tscode/ts_eslint_parser/parseTS.js";

    /**
     * Constructs a TypeScriptParser object by parsing the TypeScript AST from the given file.
     *
     * @param filePath The path to the TypeScript file to be parsed.
     */
    public TsAST(String filePath) {
        logger.info(() -> String.format("Building AST for %s", filePath));
        JSONObject ast = this.parseAndCreateTypeScriptAST(filePath);

        this.filePath = filePath;
        this.classesMap = this.parseAST("ClassDeclaration", ast);
        this.commentMap = this.parseComments(ast);
        this.functionMap = this.parseFunctionDeclarations(ast);
        this.startLineToEndLineMap = this.parseASTLines(ast);
        this.variableMap = this.parseAST("VariableDeclaration", ast);
        this.typeDeclaration = parseAST("TSTypeAliasDeclaration", ast);
        this.interfaceMap = parseAST("TSInterfaceDeclaration", ast);
        this.moduleMap = parseAST("TSModuleDeclaration", ast);
        this.importMap = parseAST("ImportDeclaration", ast);
    }

    /**
     * Gets the map of modules.
     * @return A map where keys are module start lines and values are module declarations.
     */
    public Map<Integer, String> getModuleMap() {
        return this.moduleMap;
    }

    /**
     * Gets the map of imports.
     * @return A map where keys are import start lines and values are import declarations.
     */
    public Map<Integer, String> getImportMap() {
        return this.importMap;
    }

    /**
     * Gets the map of interfaces.
     * @return A map where keys are interface names and values are interface declarations.
     */
    public Map<Integer, String> getInterfaceMap() {
        return this.interfaceMap;
    }

    /**
     * Gets the map of type declarations.
     * @return A map where keys are start lines and values are type declaration declarations.
     */
    public Map<Integer, String> getTypeDeclaration() {
        return this.typeDeclaration;
    }

    /**
     * Gets the map of functions.
     *
     * @return A map where keys are function start lines and values are function declarations.
     */
    public Map<Integer, String> getFunctionMap() {
        return this.functionMap;
    }

    /**
     * Gets the map of start lines to end lines.
     *
     * @return A map where keys are start lines and values are end lines for scoped code blocks.
     */
    public Map<Integer, Integer> getStartLineToEndLineMap() {
        return this.startLineToEndLineMap;
    }

    /**
     * Gets the map of classes.
     *
     * @return A map where keys are class names and values are class declarations.
     */
    public Map<Integer, String> getClassesMap() {
        return this.classesMap;
    }

    /**
     * Gets the map of variables.
     *
     * @return A map where keys are variable names and values are variable declarations.
     */
    public Map<Integer, String> getVariableMap() {
        return this.variableMap;
    }

    /**
     * Parses the TypeScript file and creates the AST.
     *
     * @param filePath path to the TypeScript file to be parsed.
     * @return the AST as a JSONObject.
     */
    private JSONObject parseAndCreateTypeScriptAST(String filePath) {
        try {
            // Create the process
            Process process = this.getProcess(filePath);

            // Read the output
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            // Wait for the process to finish
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                System.out.println("Node.js error-stream: \n" + output);
                throw new RuntimeException("Node.js script failed with exit code: " + exitCode);
            }

            JSONParser parser = new JSONParser();
            return (JSONObject) parser.parse(output.toString());

        } catch (IOException | ParseException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Runs the TypeScript parser (AST builder)
     *
     * @param filePath path to the TypeScript file to be parsed.
     * @return the npm process that was executed.
     * @throws IOException if the file cannot be read.
     * @throws InterruptedException if the process cannot be executed.
     */
    private Process getProcess(String filePath) throws IOException, InterruptedException {
        logger.info(() -> String.format("Running TypeScript parser (AST builder) for %s", filePath));

        // Run the TypeScript parser (AST builder)
        ProcessBuilder processBuilder = new ProcessBuilder("node",
                TS_ESLINT_PARSER_PATH, filePath);

        processBuilder.redirectErrorStream(true);
        processBuilder.redirectError(ProcessBuilder.Redirect.INHERIT);

        return processBuilder.start();
    }

/**
 * Parses comments from the AST.
 *
 * @param ast the AST to be parsed.
 * @return a map of comment start lines to end lines.
 */
private Map<Integer, Integer> parseComments(JSONObject ast) {
    Map<Integer, Integer> comments = new HashMap<>();

    ((JSONArray) ast.get("comments")).forEach(bodyElement -> {
        JSONObject bodyElementObject = (JSONObject) bodyElement;

        long startLine = (Long) ((JSONObject) ((JSONObject) bodyElementObject.get("loc")).get("start")).get("line");
        long endLine = (Long) ((JSONObject) ((JSONObject) bodyElementObject.get("loc")).get("end")).get("line");

        comments.put((int) startLine, (int) endLine);
    });
    return comments;
}

    /**
     * Parses the AST and returns a map of every start line to end line.
     *
     * @param ast the AST to be parsed.
     * @return the map of start line to end line.
     */
    private Map<Integer, Integer> parseASTLines(JSONObject ast) {
        Map<Integer, Integer> startLineToEndLineMap = new HashMap<>();

        this.parseASTLinesRecursive(ast, startLineToEndLineMap);
        return startLineToEndLineMap;
    }

    /**
     * Recursively parses the AST and adds the start line to end line mapping to the map.
     *
     * @param node the node to be parsed.
     * @param startLineToEndLineMap the map to be updated.
     */
    private void parseASTLinesRecursive(JSONObject node, Map<Integer, Integer> startLineToEndLineMap) {
        if (node.containsKey("loc") && node.containsKey("type") && !("comments".equals(node.get("type")))) {
            JSONObject loc = (JSONObject) node.get("loc");
            JSONObject start = (JSONObject) loc.get("start");
            JSONObject end = (JSONObject) loc.get("end");

            startLineToEndLineMap.put(Integer.parseInt(start.get("line").toString()),
                    Integer.parseInt(end.get("line").toString()));
        }

        if (node.containsKey("body")) {
            Object body = node.get("body");
            if (body instanceof JSONArray) {
                ((JSONArray) body).forEach(childElement -> {
                    if (childElement instanceof JSONObject) {
                        this.parseASTLinesRecursive((JSONObject) childElement, startLineToEndLineMap);
                    }
                });
            } else if (body instanceof JSONObject) {
                this.parseASTLinesRecursive((JSONObject) body, startLineToEndLineMap);
            }
        }

        String[] nestedFields = {"declaration", "expression", "consequent", "alternate"};
        for (String field : nestedFields) {
            if (node.containsKey(field) && node.get(field) instanceof JSONObject) {
                this.parseASTLinesRecursive((JSONObject) node.get(field), startLineToEndLineMap);
            }
        }
    }
    /**
     * Parses the AST and returns a map of all FunctionDeclaration where key is its start line and
     * value the text of the function declaration.
     *
     * @param ast the AST to be parsed.
     * @return the Map of FunctionDeclaration nodes.
     */
    private Map<Integer, String> parseFunctionDeclarations(JSONObject ast) {
        List<JSONObject> functionDeclarations = new ArrayList<>();
        Map<Integer, String> functionDeclarationMap = new HashMap<>();
    
        this.parseFunctionDeclarationsHelper(ast, functionDeclarations);
        for (JSONObject functionNode : functionDeclarations) {
            try {
                JSONObject loc = (JSONObject) functionNode.get("loc");
                JSONObject start = (JSONObject) loc.get("start");
                JSONObject end = (JSONObject) loc.get("end");
                
                int startLine = Math.toIntExact((Long) start.get("line"));
                int startColumn = Math.toIntExact((Long) start.get("column"));
                int endLine = Math.toIntExact((Long) end.get("line"));
                int endColumn = Math.toIntExact((Long) end.get("column"));

                int finalStartLine = startLine;
                Optional<Map.Entry<Integer, Integer>> associatedComment = commentMap.entrySet().stream()
                    .filter(entry -> entry.getValue() == finalStartLine - 1)
                    .findFirst();
    
                if (associatedComment.isPresent() && !Objects.equals(associatedComment.get().getKey(), associatedComment.get().getValue())) {
                    // Include the comment in the function text
                    startLine = associatedComment.get().getKey();
                    startColumn = 0;
                } else {
                    int effectiveStartLine = startLine;

                    // Check for sequential single-line comments
                    int currentLine = startLine - 1;
                    while (currentLine > 0) {
                        // Check if this line is a comment that points to the next line
                        if (commentMap.containsKey(currentLine)) {
                            effectiveStartLine = currentLine;
                            currentLine--;
                        } else {
                            break;
                        }
                    }

                    startLine = effectiveStartLine;
                    startColumn = 0;
                }
    
                String functionBody = CoverageParser.getTextInScope(this.filePath, startLine, startColumn, endLine, endColumn);
                functionDeclarationMap.put(startLine, functionBody);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        return functionDeclarationMap;
    }

    /**
     * Recursively parses the AST and adds FunctionDeclaration nodes to the list.
     *
     * @param node the node to be parsed.
     * @param functionDeclarations the list to be updated.
     */
    private void parseFunctionDeclarationsHelper(JSONObject node, List<JSONObject> functionDeclarations) {
        if (node.containsKey("type") && ("FunctionDeclaration".equals(node.get("type")) || "MethodDefinition".equals(node.get("type")))) {
            functionDeclarations.add(node);
        }

        if (node.containsKey("body")) {
            Object body = node.get("body");
            if (body instanceof JSONArray) {
                ((JSONArray) body).forEach(childElement -> {
                    if (childElement instanceof JSONObject) {
                        this.parseFunctionDeclarationsHelper((JSONObject) childElement, functionDeclarations);
                    }
                });
            } else if (body instanceof JSONObject) {
                this.parseFunctionDeclarationsHelper((JSONObject) body, functionDeclarations);
            }
        }

        String[] nestedFields = {"declaration", "expression", "consequent", "alternate"};
        for (String field : nestedFields) {
            if (node.containsKey(field) && node.get(field) instanceof JSONObject) {
                this.parseFunctionDeclarationsHelper((JSONObject) node.get(field), functionDeclarations);
            }
        }
    }

    /**
     * "Abstract" method to parse the AST and returns a map of "type" names and their text.
     *
     * @param type the type of the AST to parse (i.e. "ClassDeclaration", "FunctionDeclaration", etc.)
     * @param ast the AST as a JSONObject
     * @return a map of "type" names and their text.
     */
    private Map<Integer, String> parseAST(String type, JSONObject ast) {
        Map<Integer, String> astMap = new LinkedHashMap<>();

        ((JSONArray) ast.get("body")).forEach(bodyElement -> {
            JSONObject bodyElementObject = (JSONObject) bodyElement;
            if ((bodyElementObject.containsKey("type") && bodyElementObject.get("type").equals(type)) ||
                    (bodyElementObject.containsKey("type")
                            && bodyElementObject.get("type").equals("ExportNamedDeclaration"))
                            && bodyElementObject.containsKey("declaration")
                            && bodyElementObject.get("declaration") != null
                            && ((JSONObject) bodyElementObject.get("declaration")).containsKey("type")
                            && ((JSONObject) bodyElementObject.get("declaration")).get("type").equals(type)) {
                try {
                    long startLine = (Long) ((JSONObject) ((JSONObject) bodyElementObject.get("loc")).get("start")).get("line");
                    long startColumn = (Long) ((JSONObject) ((JSONObject) bodyElementObject.get("loc")).get("start")).get("column");
                    long endLine = (Long) ((JSONObject) ((JSONObject) bodyElementObject.get("loc")).get("end")).get("line");
                    long endColumn = (Long) ((JSONObject) ((JSONObject) bodyElementObject.get("loc")).get("end")).get("column");

                    String functionBody = CoverageParser.getTextInScope(this.filePath, (int) startLine, (int) startColumn,
                            (int) endLine, (int) endColumn);
                    astMap.put((int) startLine, functionBody);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        });
        return astMap;
    }

    /**
     * Gets the map of comments.
     * @return A map where keys are comment start lines and values are end lines.
     */
    public Map<Integer, Integer> getCommentMap() {  // Updated getter
        return this.commentMap;
    }

    public String getFilePath() {
        return this.filePath;
    }
}
