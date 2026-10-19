# Metrics Parsers

A metrics parser is a standalone program that analyzes a student's project directory and outputs a `metrics.json` file summarizing particular code quality indicators.

## How to Set Up a New Parser

### 1. Create the parser directory

Place your parser under `src/main/java/edu/rice/evowl/metrics/<language>-metrics/`.

### 2. Register the parser in `Metrics.java`

Add an entry to `METRICS_BY_LANGUAGE` in `Metrics.java`:

```java
final static Map<String, Consumer<File>> METRICS_BY_LANGUAGE =
    Map.of(
        "golang",     Metrics::runGoStudentMetrics,
        "typescript", Metrics::runTypescriptStudentMetrics,
    );
```

Then add a corresponding runner method that:
1. Copies any config files (e.g., `go.mod`, `tsconfig.json`) which are required for running the parser from the student's project root into the coverage directory.
2. Builds a `ProcessBuilder` to invoke the parser with two positional arguments: `<project_directory>` and `<output_file>`.
3. Calls `runMetricsProcess(processBuilder, metricsJsonFile)`.

See the existing metrics parsers for examples of this.

### 3. Implement the parser

The parser must:
- Accept two command-line arguments: `<project_directory>` and `<output_file>`.
- Write a JSON file to `<output_file>` (the file must exist after the process exits, or `Metrics.java` will throw).
- Produce output matching the schema described below.

## Output JSON Schema

The output file must be a JSON object keyed by a module identifier, (e.g., package path or file path).
The exact specification for the JSON output is as follows:

```json
CodeLocation := {
  "File":      "relative/path/from/project/root.ext",
  "Offset":    123,
  "StartLine": 10,
  "StartCol":  5,
  "EndLine":   10,
  "EndCol":    15
}

NamedCodeLocation := {
  "Name": "FunctionOrTypeName",
  ...CodeLocation
}

MetricsOutput := {
  "<module-id>": {
    "Files": ["relative/path/to/file.ext"],
    "FileCount": number,
    "LineCount": number,

    "PublicFuncs": NamedCodeLocation[],
    "PublicStructs": NamedCodeLocation[],
    "PublicStructFields": NamedCodeLocation[],
    "PrivateStructFields": NamedCodeLocation[],

    "ErrorChecks": CodeLocation[],
    "MisnamedErrorChecks": CodeLocation[],
    "MissingErrorChecks": CodeLocation[],

    "DocFuncs": NamedCodeLocation[],
    "DocStructs": NamedCodeLocation[],
    "NodocFuncs": NamedCodeLocation[],
    "NodocStructs": NamedCodeLocation[],

    "ExternalImportUses": NamedCodeLocation[],
    "ModuleImportUses": NamedCodeLocation[]
  }
}
```

## What to Include in Each Field

| Field | What to collect |
|---|---|
| `Files` | List of source files in this module (relative to project root) |
| `FileCount` / `LineCount` | Number of files and total lines across all files in the module |
| `PublicFuncs` | Exported/public function and method declarations |
| `PublicStructs` | Exported/public type declarations (classes, interfaces, structs) |
| `PublicStructFields` | Fields of structs/classes that are publicly accessible |
| `PrivateStructFields` | Fields of structs/classes that are private/unexported |
| `ErrorChecks` | Call sites where errors/exceptions are properly caught and handled |
| `MisnamedErrorChecks` | Call sites where errors are checked but the error variable is poorly named |
| `MissingErrorChecks` | Call sites that return an error/promise but where the error is not handled |
| `DocFuncs` | Function/method declarations that have a leading doc comment |
| `NodocFuncs` | Function/method declarations with no doc comment |
| `DocStructs` | Type declarations that have a leading doc comment |
| `NodocStructs` | Type declarations with no doc comment |
| `ModuleImportUses` | Uses of identifiers defined in a different file within the same project |
| `ExternalImportUses` | Uses of identifiers from external/third-party packages (currently unused) |
