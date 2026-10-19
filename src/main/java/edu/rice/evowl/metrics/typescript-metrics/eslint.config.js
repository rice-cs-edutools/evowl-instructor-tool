import tseslint from "@typescript-eslint/eslint-plugin";
import parser from "@typescript-eslint/parser";

export default [
    {
        files: ["src/**/*.ts", "src/**/*.tsx"],
        languageOptions: {
            parser,
            parserOptions: {
                ecmaVersion: "latest",
                sourceType: "module",
                projectService: true,         // enables typed rules
                tsconfigRootDir: import.meta.dirname, // base path for tsconfig lookup
            }
        },
        plugins: {
            "@typescript-eslint": tseslint
        },
        rules: {
            "@typescript-eslint/no-floating-promises": "error",
            "no-restricted-syntax": [
                "error",
                {
                    selector: "CatchClause",
                    message: "All catch blocks are disallowed."
                }
            ],
            // "promise/catch-or-return": "error",

            // Warn if .then() chains don't return properly
            // "promise/always-return": "warn"
        }
    }
];
