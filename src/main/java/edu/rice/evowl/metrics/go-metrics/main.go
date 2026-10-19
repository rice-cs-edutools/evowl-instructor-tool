package main

import (
	"bufio"
	"encoding/json"
	"errors"
	"fmt"
	"go/ast"
	"go/token"
	"go/types"
	"log"
	"os"
	"path/filepath"
	"strings"

	"golang.org/x/tools/go/packages"
)

type CodeLocation struct {
	File      string
	Offset    int
	StartLine int
	StartCol  int
	EndLine   int
	EndCol    int
}

type NamedCodeLocation struct {
	Name string
	CodeLocation
}

// Abbreviated data for each module
// This is what will actually be exported to the json file
type ModuleExportStats struct {
	Files []string

	// Modularity & Cohesion
	FileCount int
	LineCount int

	// Interfaces and encapsulation
	PublicFuncs         []NamedCodeLocation
	PublicStructs       []NamedCodeLocation
	PublicStructFields  []NamedCodeLocation
	PrivateStructFields []NamedCodeLocation

	// Error checks
	ErrorChecks         []CodeLocation
	MisnamedErrorChecks []CodeLocation
	MissingErrorChecks  []CodeLocation

	// Documentation
	DocFuncs     []NamedCodeLocation
	DocStructs   []NamedCodeLocation
	NodocFuncs   []NamedCodeLocation
	NodocStructs []NamedCodeLocation

	// Imported packages
	ExternalImportUses []NamedCodeLocation
	ModuleImportUses   []NamedCodeLocation
}

type ExportStats = map[string]ModuleExportStats

// ==================== Running the parser ====================

func main() {
	if len(os.Args) < 3 {
		println("Usage: go run main.go <project_directory> <output_file>")
		return
	}

	// Directory containing the go project
	rootDir := os.Args[1]
	// File to output to
	jsonFile := os.Args[2]

	// Parse the project
	stats, _, err := generateExportStats(rootDir)
	if err != nil {
		log.Fatal(err)
	}

	// Convert the struct to json
	jsonOutput, err := json.MarshalIndent(stats, "", "  ")
	if err != nil {
		log.Fatal(err)
	}

	// Output the JSON
	// err = os.WriteFile(jsonFile, []byte(jsonStringReplaced), 0644)
	err = os.WriteFile(jsonFile, []byte(jsonOutput), 0644)
	if err != nil {
		log.Fatal("Error writing JSON to file:", err)
	}
	fmt.Printf("Wrote to %s\n", jsonFile)
}

func generateExportStats(projectRoot string) (*ExportStats, string, error) {
	stats := make(ExportStats)

	// Parse the project

	absProjectRoot, err := filepath.Abs(projectRoot)
	if err != nil {
		return nil, "", errors.New(fmt.Sprintf("project root not found: `%s`", projectRoot))
	}

	cfg := &packages.Config{
		Mode: packages.NeedName |
			packages.NeedFiles |
			packages.NeedCompiledGoFiles |
			packages.NeedSyntax |
			packages.NeedTypes |
			packages.NeedTypesInfo |
			packages.NeedDeps,
		Fset: token.NewFileSet(),
		Dir:  absProjectRoot,
	}

	pkgs, err := packages.Load(cfg, "...")
	if err != nil {
		return nil, "", errors.New(fmt.Sprintf("unable to load project: `%s`", projectRoot))
	}

	// Map root directories to package names

	moduleDirectoryMap := make(map[string]string)
	for _, pkg := range pkgs {
		if absPkgRoot, err := filepath.Abs(pkg.Dir); err == nil {
			moduleDirectoryMap[absPkgRoot] = pkg.PkgPath
		}
	}

	// Determine the main module name
	var mainModuleId string
	for _, pkg := range pkgs {
		// Only parse files in the project directory
		absModuleRoot, err := filepath.Abs(pkg.Dir)
		if err != nil {
			continue
		}

		isMainModule := absModuleRoot == absProjectRoot
		if isMainModule {
			mainModuleId = pkg.ID
		}
	}

	// Traverse all nodes in the project

	for _, pkg := range pkgs {
		// Only parse files in the project directory
		absModuleRoot, err := filepath.Abs(pkg.Dir)
		if err != nil || !strings.HasPrefix(absModuleRoot, absProjectRoot) {
			continue
		}
		isMainModule := absModuleRoot == absProjectRoot

		moduleStats := ModuleExportStats{
			Files: make([]string, 0),

			FileCount: 0,
			LineCount: 0,

			PublicFuncs:         make([]NamedCodeLocation, 0),
			PublicStructs:       make([]NamedCodeLocation, 0),
			PublicStructFields:  make([]NamedCodeLocation, 0),
			PrivateStructFields: make([]NamedCodeLocation, 0),

			ErrorChecks:         make([]CodeLocation, 0),
			MisnamedErrorChecks: make([]CodeLocation, 0),
			MissingErrorChecks:  make([]CodeLocation, 0),

			DocFuncs:     make([]NamedCodeLocation, 0),
			DocStructs:   make([]NamedCodeLocation, 0),
			NodocFuncs:   make([]NamedCodeLocation, 0),
			NodocStructs: make([]NamedCodeLocation, 0),

			ExternalImportUses: make([]NamedCodeLocation, 0),
			ModuleImportUses:   make([]NamedCodeLocation, 0),
		}

		// Process either a function or struct definition. Add it to
		// ExternalImports or ModuleImports based on where it is
		// defined.
		checkIfImported := func(ident *ast.Ident) {
			definition := pkg.TypesInfo.ObjectOf(ident)
			if definition == nil || !definition.Pos().IsValid() {
				return
			}

			// Get the module the function is in
			definitionFile := cfg.Fset.Position(definition.Pos()).Filename
			definitionModule, err := findModule(&moduleDirectoryMap, definitionFile)
			if err != nil {
				fmt.Printf("Definition module not found: %s\n", definition.Name())
				return
			}

			// Assign the module name to the original location of the use
			useLocation := NamedCodeLocation{
				Name:         definitionModule,
				CodeLocation: identToLocation(absProjectRoot, cfg.Fset, ident).CodeLocation,
			}

			// If in the main module
			inCurrentModule := false
			if isMainModule {
				inCurrentModule = definitionFile[0:strings.LastIndex(definitionFile, "/")] == absProjectRoot
			} else {
				inCurrentModule = strings.HasPrefix(definitionFile, absModuleRoot)
			}

			// Check if it is a module of the current project
			if !inCurrentModule && strings.HasPrefix(definitionFile, absProjectRoot) {
				// Add the use to the list
				moduleStats.ModuleImportUses = append(moduleStats.ModuleImportUses, useLocation)

				// If it contains a dot, assume it is external
			} // else if strings.Contains(strings.Split(definitionModule, "/")[0], ".") {
			//	moduleStats.ExternalImportUses = append(moduleStats.ExternalImportUses, useLocation)
			//}
		}

		// Keep track of a list of calls that have been checked for errors
		handledCallExprs := make(map[token.Pos]bool)

		// In a list of statements, search for statements using incorrect
		// error checking
		searchForIncorrectErrorChecking := func(stmts []ast.Stmt) error {
			for i := 0; i < len(stmts); i++ {
				assignStmt, ok := stmts[i].(*ast.AssignStmt)
				if !ok || len(assignStmt.Rhs) == 0 || len(assignStmt.Lhs) == 0 {
					continue
				}

				callExpr, ok := assignStmt.Rhs[0].(*ast.CallExpr)
				if !ok {
					continue
				}
				handledCallExprs[callExpr.Pos()] = true
				location := posnsToLocation(absProjectRoot, cfg.Fset, callExpr.Pos(), callExpr.End())

				// Figure out the index of the error
				errPosition, err := errorIndex(pkg, callExpr)
				if err != nil {
					return err
				}
				if errPosition == -1 {
					continue
				}

				// If there are not enough variables, it is missing error checking
				if errPosition >= len(assignStmt.Lhs) {
					moduleStats.MissingErrorChecks = append(moduleStats.MissingErrorChecks, location)
					continue
				}

				// Check if the last assigned statement is a variable
				errVar, ok := assignStmt.Lhs[errPosition].(*ast.Ident)
				if !ok {
					continue
				}

				// Check if there is no next statement
				if i == len(stmts)-1 {
					moduleStats.MissingErrorChecks = append(moduleStats.MissingErrorChecks, location)
					continue
				}
				isCorrect := isErrorCheckStatement(stmts[i+1], errVar.Name)
				if !isCorrect {
					moduleStats.MissingErrorChecks = append(moduleStats.MissingErrorChecks, location)
				} else if strings.HasPrefix(errVar.Name, "err") || strings.HasSuffix(errVar.Name, "Err") {
					moduleStats.ErrorChecks = append(moduleStats.ErrorChecks, location)
				} else {
					moduleStats.MisnamedErrorChecks = append(moduleStats.MisnamedErrorChecks, location)
				}
			}
			return nil
		}

		// Parse each file
		for _, file := range pkg.Syntax {
			relPath, err := filepath.Rel(absProjectRoot, cfg.Fset.Position(file.Pos()).Filename)
			if err != nil {
				continue
			}
			moduleStats.Files = append(moduleStats.Files, relPath)

			// Count the lines in the file
			fileLines, err := linesInFile(cfg.Fset.File(file.Pos()).Name())
			if err != nil {
				return nil, "", err
			}
			moduleStats.FileCount += 1
			moduleStats.LineCount += fileLines

			// Traverse the ast of the file
			ast.Inspect(file, func(n ast.Node) bool {
				switch node := n.(type) {
				// Check for imported identifiers
				case *ast.Ident:
					checkIfImported(node)

				// Check block statements for error checks
				case *ast.BlockStmt:
					searchForIncorrectErrorChecking(node.List)

				case *ast.FuncDecl:
					location := identToLocation(absProjectRoot, cfg.Fset, node.Name)
					if ast.IsExported(node.Name.Name) {
						moduleStats.PublicFuncs = append(moduleStats.PublicFuncs, location)
					}
					// Check if the function is documented
					if node.Doc == nil {
						moduleStats.NodocFuncs = append(moduleStats.NodocFuncs, location)
					} else {
						moduleStats.DocFuncs = append(moduleStats.DocFuncs, location)
					}

				case *ast.CallExpr:
					// Blocks are visited before their contents, so if
					// this function call is properly error checked,
					// it will already be in handledCallExprs
					_, alreadyVisited := handledCallExprs[node.Pos()]
					if !alreadyVisited {
						errPosition, err := errorIndex(pkg, node)
						if err != nil {
							log.Println(err)
						} else if errPosition != -1 {
							location := posnsToLocation(absProjectRoot, cfg.Fset, node.Pos(), node.End())
							moduleStats.MissingErrorChecks = append(moduleStats.MissingErrorChecks, location)
						}
					}

				case *ast.GenDecl:
					// Check if it is a struct declaration
					typeDecl, isType := node.Specs[0].(*ast.TypeSpec)
					if !isType {
						return true
					}
					structType, isStruct := typeDecl.Type.(*ast.StructType)
					if !isStruct {
						return true
					}

					location := identToLocation(absProjectRoot, cfg.Fset, typeDecl.Name)
					// Check if it is public
					if ast.IsExported(typeDecl.Name.Name) {
						moduleStats.PublicStructs = append(moduleStats.PublicStructs, location)
					}
					// Check if it is documented
					if node.Doc == nil {
						moduleStats.NodocStructs = append(moduleStats.NodocStructs, location)
					} else {
						moduleStats.DocStructs = append(moduleStats.DocStructs, location)
					}

					// Determine which parameters are public
					if structType.Fields == nil {
						return true
					}
					for _, fieldNode := range structType.Fields.List {
						if fieldNode.Names == nil || len(fieldNode.Names) == 0 {
							continue
						}

						fieldLoc := identToLocation(absProjectRoot, cfg.Fset, fieldNode.Names[0])
						if ast.IsExported(fieldNode.Names[0].Name) {
							moduleStats.PublicStructFields = append(moduleStats.PublicStructFields, fieldLoc)
						} else {
							moduleStats.PrivateStructFields = append(moduleStats.PrivateStructFields, fieldLoc)
						}
					}
				}
				return true
			})
		}

		// Replcae the root package id with "go-compile" so that it can be understood by the frontend
		if strings.HasPrefix(pkg.ID, mainModuleId) {
			replacedId := "go-compile" + pkg.ID[len(mainModuleId):]
			stats[replacedId] = moduleStats
		} else {
			return nil, "", errors.New("Package id does not match main module id")
		}
	}

	return &stats, mainModuleId, nil
}

// ==================== Error checking ====================

// Check if a statement is a valid error checking statement for an
// error stored in a variable with name errVar
func isErrorCheckStatement(after ast.Stmt, errVar string) bool {
	// Check that the expression after is an if statement
	ifStmt, ok := after.(*ast.IfStmt)
	if !ok {
		return false
	}

	// Check that the if condition is a comparison (EQL or NEQ)
	binaryExpr, ok := ifStmt.Cond.(*ast.BinaryExpr)
	if !ok || (binaryExpr.Op != token.NEQ && binaryExpr.Op != token.EQL) {
		return false
	}

	// Check that both things being compared are idents
	leftIdent, ok1 := binaryExpr.X.(*ast.Ident)
	rightIdent, ok2 := binaryExpr.Y.(*ast.Ident)
	if !ok1 || !ok2 {
		return false
	}

	if leftIdent.Name == errVar && rightIdent.Name == "nil" {
		return true
	}
	if leftIdent.Name == "nil" && rightIdent.Name == errVar {
		return true
	}
	return false
}

// If the call returns only an error, return 0. If fn returns a tuple
// containing one or more error values, return the index (0-indexed)
// of the first slot with an error. Otherwise, return -1.
func errorIndex(pkg *packages.Package, callExpr *ast.CallExpr) (int, error) {
	typ := pkg.TypesInfo.TypeOf(callExpr.Fun)
	sig, ok := typ.(*types.Signature)
	if !ok {
		return 0, errors.New(fmt.Sprintf("no type signature found for call expr"))
	}

	// Check if the function is exempt from error checking
	selectorExpr, ok := callExpr.Fun.(*ast.SelectorExpr)
	if ok {
		methodTypeInfo := pkg.TypesInfo.ObjectOf(selectorExpr.Sel)
		// All functions in the fmt package are exempt
		if methodTypeInfo.Pkg().Path() == "fmt" {
			return -1, nil
		}
		if methodTypeInfo.Pkg().Path() == "errors" && methodTypeInfo.Name() == "New" {
			return -1, nil
		}

		objType := pkg.TypesInfo.TypeOf(selectorExpr.X).String()
		// The Write function defined in the http package is exempt
		if objType == "net/http.ResponseWriter" && methodTypeInfo.Name() == "Write" {
			return -1, nil
		}
	}

	for i := 0; i < sig.Results().Len(); i++ {
		if sig.Results().At(i).Type().String() == "error" {
			return i, nil
		}
	}
	return -1, nil
}

// ==================== Getting file locations ====================

// Convert a token position to a CodeLocation object
func posnsToLocation(projectRoot string, fset *token.FileSet, start token.Pos, end token.Pos) CodeLocation {
	startPosn := fset.Position(start)
	endPosn := fset.Position(end)
	relPath, _ := filepath.Rel(projectRoot, startPosn.Filename)
	return CodeLocation{
		File:      relPath,
		Offset:    startPosn.Offset,
		StartLine: startPosn.Line,
		StartCol:  startPosn.Column,
		EndLine:   endPosn.Line,
		EndCol:    endPosn.Column,
	}
}

// Convert a start location plus a length to a CodeLocation object
func startLengthToLocation(projectRoot string, fset *token.FileSet, start token.Pos, length int) CodeLocation {
	startPosn := fset.Position(start)
	relPath, _ := filepath.Rel(projectRoot, startPosn.Filename)
	return CodeLocation{
		File:      relPath,
		Offset:    startPosn.Offset,
		StartLine: startPosn.Line,
		StartCol:  startPosn.Column,
		EndLine:   startPosn.Line,
		EndCol:    startPosn.Column + length,
	}
}

// Convert an ast identifier to a CodeLocation object
func identToLocation(projectRoot string, fset *token.FileSet, ident *ast.Ident) NamedCodeLocation {
	startPosn := fset.Position(ident.Pos())
	relPath, _ := filepath.Rel(projectRoot, startPosn.Filename)
	return NamedCodeLocation{
		Name: ident.Name,
		CodeLocation: CodeLocation{
			File:      relPath,
			Offset:    startPosn.Offset,
			StartLine: startPosn.Line,
			StartCol:  startPosn.Column,
			EndLine:   startPosn.Line,
			EndCol:    startPosn.Column + len(ident.Name),
		},
	}
}

// ==================== Helper functions ====================

// Find the name of the module that filePath is a part of
func findModule(pkgMap *map[string]string, filePath string) (string, error) {
	// Normalize to forward slashes for consistency
	filePath = filepath.ToSlash(filePath)

	for {
		if pkgName, ok := (*pkgMap)[filePath]; ok {
			return pkgName, nil
		}

		// Find first slash
		idx := strings.LastIndex(filePath, "/")
		if idx == -1 {
			return "", errors.New("package not found")
		}

		// Remove first segment
		filePath = filePath[0:idx]
	}
}

// Count the lines in a file
func linesInFile(path string) (int, error) {
	file, err := os.Open(path)
	if err != nil {
		return 0, err
	}

	fileScanner := bufio.NewScanner(file)
	lineCount := 0
	for fileScanner.Scan() {
		lineCount++
	}
	file.Close()
	return lineCount, nil
}

// Convert the keys of a map to a list
func getKeys[K comparable, V any](m map[K]V) []K {
	keyList := make([]K, len(m))
	i := 0
	for k := range m {
		keyList[i] = k
		i++
	}
	return keyList
}

// Filter function
func filter[T any](list []T, f func(t T) bool) []T {
	newList := make([]T, 0)
	for _, v := range list {
		if f(v) {
			newList = append(newList, v)
		}
	}
	return newList
}

func filterMap[T any, S any](list []T, f func(t T) bool, g func(t T) S) []S {
	newList := make([]S, 0)
	for _, v := range list {
		if f(v) {
			newList = append(newList, g(v))
		}
	}
	return newList
}

func buildIndexMap[T any, K comparable](list []T, f func(t T) K) map[K]int {
	m := make(map[K]int)
	for idx, elem := range list {
		m[f(elem)] = idx
	}
	return m
}

func buildValueMap[T any, K comparable](list []T, f func(t T) K) map[K]T {
	m := make(map[K]T)
	for _, elem := range list {
		m[f(elem)] = elem
	}
	return m
}
