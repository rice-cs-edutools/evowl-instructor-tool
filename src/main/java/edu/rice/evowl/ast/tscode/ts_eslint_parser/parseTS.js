const { parse } = require('@typescript-eslint/typescript-estree');
const fs = require('fs');

async function parseTypeScriptFile(filePath) {
    const code = fs.readFileSync(filePath, 'utf8');
    return parse(code, {
        jsx: false,
        useJSXTextNode: true,
        ecmaVersion: 2020,
        sourceType: 'module',
        loc: true,
        comment: true,
    });
}

const filePath = process.argv[2];
parseTypeScriptFile(filePath).then((ast) => {
    console.log(JSON.stringify(ast, null, 2));
}).catch((error) => {
    console.error(error);
    process.exit(1);
});