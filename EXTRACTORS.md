# Adding a New Language Extractor

The `parse` command reads a Cobertura XML coverage report per submission and writes filtered source into `coverage-after/`. The `metrics` command then runs a language-specific script on that directory to produce `metrics.json`.

To support a new language, these are the following steps:

---

## 1. Add to the `Language` enum

`src/main/edu/rice/evowl/commands/Language.java`

```java
PYTHON("python"),
```

---

## 2. Implement a coverage parser

Create `src/main/edu/rice/evowl/coverage/MyLanguageCoverageParser.java` extending `CoverageParser`:

```java
public class MyLanguageCoverageParser extends CoverageParser {

    public MyLanguageCoverageParser(String coberturaFile, String baseDir, String coverageAfterDirName) {
        super(coberturaFile); // parses XML, populates getCoveredLines()
        // collect source files here
    }

    public void createMyLanguageFilesAfterCoverage() {
        // For each source file:
        //   1. Look up covered lines via getCoveredLines().get(filePath)
        //   2. Extract content with getTextInScope(filePath, startLine, endLine)
        //   3. Write filtered output to baseDir/coverageAfterDirName/
    }
}
```

**Key `CoverageParser` methods:**

| Method | Description |
|--------|-------------|
| `getCoveredLines()` | `Map<String, Map<Integer, String>>` — covered lines per file |
| `getUncoveredFunctions()` | `Map<String, List<Integer>>` — uncovered function start lines per file |
| `getTextInScope(file, start, end)` | Read a line range from a source file |
| `getTextInScope(file, start, startCol, end, endCol)` | Column-precise variant |
| `executeCommand(workingDir, commands)` | Run shell commands in a directory |

---

## 3. Wire the parser into `Parse.java`

`src/main/edu/rice/evowl/commands/Parse.java`, inside `parseFiles`:

```java
case MY_LANGUAGE -> new MyLanguageCoverageParser(
        coberturaFile.getAbsolutePath(),
        directory.getAbsolutePath(),
        coverageAfterDirName
).createMyLanguageFilesAfterCoverage();
```

---

## 4. Write the metrics script

Create your program under `src/main/edu/rice/evowl/ast/mylanguage/`. It must:

- Accept `<coverageAfterDir>` and `<outputJsonPath>` as arguments
- Walk `coverageAfterDir` and compute relevant metrics (LOC, cyclomatic complexity, etc.)
- Write results as JSON to `<outputJsonPath>`

See the existing implementations for reference:
- **Go**: `src/main/java/edu/rice/evowl/ast/gocode/metrics-analyzer/main.go`
- **TypeScript**: `src/main/java/edu/rice/evowl/ast/tscode/typescript-metrics/main.ts`

---

## 5. Wire the metrics script into `Metrics.java`

`src/main/java/edu/rice/evowl/commands/Metrics.java`:

```java
public static void runMyLanguageStudentMetrics(File coverageDirectory) {
    // build and invoke your metrics program, write metrics.json
}
```

Then add a case in `computeMetrics`:

```java
case MY_LANGUAGE -> runMyLanguageStudentMetrics(coverageDirectory);
```

---

## Testing

- Add sample Cobertura XML, source files, and expected `coverage-after/` output under `src/test/resources/`.
- Write `src/test/java/edu/rice/evowl/coverage/MyLanguageCoverageParserTest.java`, modelled on `TypeScriptCoverageParserTest.java`.
