import ts from "typescript";
import fs from "node:fs/promises";
import path from "node:path";
import { FlatESLint } from "eslint/use-at-your-own-risk";

type CodeLocation = {
    File: string;
    Offset: number;
    StartLine: number;
    StartCol: number;
    EndLine: number;
    EndCol: number;
};

type NamedCodeLocation = { Name: string } & CodeLocation;

type ModuleMetrics = {
    Files: string[];

    // Modularity & Cohesion
    FileCount: number;
    LineCount: number;

    // Interfaces and encapsulation
    PublicFuncs: NamedCodeLocation[];
    PublicStructs: NamedCodeLocation[];
    PublicStructFields: NamedCodeLocation[];
    PrivateStructFields: NamedCodeLocation[];

    // Error checks (flattened)
    ErrorChecks: CodeLocation[];
    MisnamedErrorChecks: CodeLocation[];
    MissingErrorChecks: CodeLocation[];

    // Documentation
    DocFuncs: NamedCodeLocation[];
    DocStructs: NamedCodeLocation[];
    NodocFuncs: NamedCodeLocation[];
    NodocStructs: NamedCodeLocation[];

    // Imported packages
    ExternalImportUses: NamedCodeLocation[];
    ModuleImportUses: NamedCodeLocation[];
};

type ProjectMetrics = { [s: string]: ModuleMetrics };

const IGNORE_DIRECTORIES = ["src/provided"];

function getActualSymbol(symbol: ts.Symbol, checker: ts.TypeChecker): ts.Symbol {
    // Follow alias if this is an import/export alias
    if ((symbol.getFlags() & ts.SymbolFlags.Alias) !== 0) {
        const aliased = checker.getAliasedSymbol(symbol);
        if (aliased) return aliased;
    }
    return symbol;
}

async function analyzeFile(root: string, relative: string): Promise<ModuleMetrics> {
    console.log("Analyze", relative);

    const filePath = path.join(root, relative);
    const program = ts.createProgram([filePath], {});
    const checker = program.getTypeChecker();
    const source = program.getSourceFile(filePath)!;
    const fullText = source.getFullText();

    if (!source) throw new Error(`Failed to parse file: ${relative}`);

    const metrics: ModuleMetrics = {
        Files: [relative],

        // Modularity & Cohesion
        FileCount: 1,
        LineCount: (await fs.readFile(filePath, "utf8")).split("\n").length,

        // Interfaces and encapsulation
        PublicFuncs: [],
        PublicStructs: [],
        PublicStructFields: [],
        PrivateStructFields: [],

        // Error checks (flattened)
        ErrorChecks: [],
        MisnamedErrorChecks: [],
        MissingErrorChecks: [],

        // Documentation
        DocFuncs: [],
        DocStructs: [],
        NodocFuncs: [],
        NodocStructs: [],

        // Imported packages
        ExternalImportUses: [],
        ModuleImportUses: [],
    };

    function getNamedLocation(node: ts.Node, name: string): NamedCodeLocation {
        const start = source.getLineAndCharacterOfPosition(node.getStart());
        const end = source.getLineAndCharacterOfPosition(node.getEnd());
        return {
            File: relative,
            Name: name,
            Offset: node.getStart(),
            StartLine: start.line + 1,
            StartCol: start.character + 1,
            EndLine: end.line + 1,
            EndCol: end.character + 1,
        };
    }

    function visitNode(node: ts.Node) {
        const isFunc = ts.isFunctionDeclaration(node) || ts.isMethodDeclaration(node);
        const isType =
            ts.isClassDeclaration(node) ||
            ts.isInterfaceDeclaration(node) ||
            ts.isTypeAliasDeclaration(node);

        // If it is a function or type declaration
        if (isFunc || isType) {
            const name = node.name?.getText(source);
            if (node.name && name) {
                const modifiers = ts.getCombinedModifierFlags(node);
                const isPublic = ts.isMethodDeclaration(node)
                    ? !(modifiers & ts.ModifierFlags.Private)
                    : !!(modifiers & ts.ModifierFlags.Export);
                const location = getNamedLocation(node.name, name);

                const comments = ts.getLeadingCommentRanges(fullText, node.getFullStart());
                if (comments && comments.length > 0) {
                    (isFunc ? metrics.DocFuncs : metrics.DocStructs).push(location);
                } else {
                    (isFunc ? metrics.NodocFuncs : metrics.NodocStructs).push(location);
                }

                if (isPublic) {
                    (isFunc ? metrics.PublicFuncs : metrics.PublicStructs).push(location);
                }
            }
        }

        const symbol = checker.getSymbolAtLocation((node as any).name);
        if (symbol) {
            const actual = getActualSymbol(symbol, checker);
            const decl = actual.getDeclarations()?.[0];
            const file = decl?.getSourceFile().fileName;

            if (file && file.startsWith(`${root}/`) && file !== filePath) {
                const relative = file.substring(`${root}/`.length);
                if (IGNORE_DIRECTORIES.every((dir) => !relative.startsWith(dir))) {
                    metrics.ModuleImportUses.push(getNamedLocation(node, relative));
                }
            }
        }

        ts.forEachChild(node, visitNode);
    }
    visitNode(source);

    // Run eslint to get error instances
    const eslint = new FlatESLint({
        cwd: root,
        overrideConfigFile: path.join(process.cwd(), "eslint.config.js"),
    });

    // You can pass a fake filename for context (useful for parser matching)
    const results = await eslint.lintText(fullText, { filePath });
    // console.log(results.flatMap((r) => r.messages));
    for (const message of results.flatMap((r) => r.messages)) {
        const location: CodeLocation = {
            File: relative,
            Offset: 0,
            StartLine: message.line,
            StartCol: message.column,
            EndLine: message.endLine ?? message.line,
            EndCol: message.endColumn ?? message.column,
        };
        if (message.ruleId === "no-restricted-syntax" && message.nodeType === "CatchClause") {
            metrics.ErrorChecks.push(location);
        } else if (message.ruleId === "@typescript-eslint/no-floating-promises") {
            metrics.MissingErrorChecks.push(location);
        }
    }

    return metrics;
}

async function analyzeDir(root: string): Promise<ProjectMetrics> {
    const metrics: ProjectMetrics = {};
    const absRoot = await fs.realpath(root);

    const files = await fs.readdir(absRoot, {
        recursive: true,
        withFileTypes: true,
    });
    await Promise.all(
        files.map(async (file) => {
            if (!file.isFile() || !file.name.endsWith(".ts")) return;

            const filePath = path.join(file.parentPath, file.name);
            if (!filePath.startsWith(absRoot)) {
                throw new Error(`File not in root: ${filePath}`);
            }
            const relative = filePath.substring(absRoot.length + 1);
            if (!relative.startsWith("src/")) return;
            if (IGNORE_DIRECTORIES.find((dir) => relative.startsWith(dir))) {
                console.log("Ignoring ", relative);
                return;
            }

            metrics[relative] = await analyzeFile(absRoot, relative);
        }),
    );

    return metrics;
}

const args = process.argv.slice(2);
if (args.length === 2) {
    analyzeDir(args[0]).then((metrics) => {
        fs.writeFile(args[1], JSON.stringify(metrics, undefined, 2));
    });
} else {
    console.error("Expected `npx ts-node main.ts <dir> <outputFile>`");
}
