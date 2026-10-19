package main

import (
	"encoding/json"
	"fmt"
	"go/ast"
	"go/parser"
	"go/token"
	"log"
	"os"
)

type GoFile struct {
	Package        string                   `json:"Package"`
	PackageComment string                   `json:"PackageComment"`
	Imports        map[string]PositionRange `json:"Imports"`
	Functions      map[string]PositionRange `json:"Functions"`
	TypeSpecs      map[string]PositionRange `json:"Types"`
	Variables      map[string]PositionRange `json:"Variables"`
	LineMap        map[string]PositionRange `json:"LineMap"`
}

type PositionRange struct {
	Name        string `json:"name"`
	StartLine   int    `json:"startLine"`
	StartColumn int    `json:"startColumn"`
	EndLine     int    `json:"endLine"`
	EndColumn   int    `json:"endColumn"`
	Associated  string `json:"associatedComment"`
}

func main() {
	if len(os.Args) < 3 {
		fmt.Println("Usage: go run main.go <directory_for_go_file> <go_file_name>")
		return
	}

	filePath := os.Args[1]
	fileName := os.Args[2]

	fset := token.NewFileSet()
	if err := os.Chdir(filePath); err != nil {
		log.Fatal("Error changing directory:", err)
	}

	// Parse the Go file
	node, err := parser.ParseFile(fset, fileName, nil, parser.AllErrors|parser.ParseComments)
	if err != nil {
		fmt.Println("Error: ", err)
		return
	}

	/* Create an ast.CommentMap from the ast.File's comments.
	This helps in keeping the association between comments and AST nodes. */
	cmap := ast.NewCommentMap(fset, node, node.Comments)

	output := &GoFile{
		Imports:   make(map[string]PositionRange),
		Functions: make(map[string]PositionRange),
		TypeSpecs: make(map[string]PositionRange),
		Variables: make(map[string]PositionRange),
		LineMap:   make(map[string]PositionRange),
	}

	// Traverse the AST
	ast.Inspect(node, func(n ast.Node) bool {
		switch x := n.(type) {

		// Extract out the package
		case *ast.File:
			output.Package = x.Name.Name
			// Extract out the package's associated comment
			if cmap[x] != nil {
				for _, comment := range cmap[x] {
					output.PackageComment = comment.Text()
				}
			}

		// Extract out all Functions
		case *ast.FuncDecl:
			start := fset.Position(x.Pos())
			end := fset.Position(x.End())

			/* Checks if this function has a comment associated with it */
			var associatedComment string
			if cmap[x] != nil {
				for _, comment := range cmap[x] {
					associatedComment = comment.Text()
				}
			}

			line := fmt.Sprintf("%d", start.Line)
			output.Functions[line] = PositionRange{
				Name:        x.Name.Name,
				StartLine:   start.Line,
				StartColumn: start.Column,
				EndLine:     end.Line,
				EndColumn:   end.Column,
				Associated:  associatedComment,
			}

			output.LineMap[line] = PositionRange{
				Name:        "",
				StartLine:   start.Line,
				StartColumn: start.Column,
				EndLine:     end.Line,
				EndColumn:   end.Column,
				Associated:  associatedComment,
			}

		case *ast.GenDecl:
			// Extract out all Variable and Constant Declarations
			if x.Tok == token.VAR || x.Tok == token.CONST {
				start := fset.Position(x.Pos())
				end := fset.Position(x.End())

				/* Checks if this function has a comment associated with it */
				var associatedComment string
				if cmap[x] != nil {
					for _, comment := range cmap[x] {
						associatedComment = comment.Text()
					}
				}

				line := fmt.Sprintf("%d", start.Line)

				var name string
				if x.Lparen != token.NoPos && x.Rparen != token.NoPos {
					name = fmt.Sprintf("%s decl_block", x.Tok.String())
				} else {
					name = fmt.Sprintf("%s", x.Specs[0].(*ast.ValueSpec).Names[0].Name)
				}
				output.Variables[line] = PositionRange{
					Name:        name,
					StartLine:   start.Line,
					StartColumn: start.Column,
					EndLine:     end.Line,
					EndColumn:   end.Column,
					Associated:  associatedComment,
				}

				// Extract out the TypeDefs (Structs & Interfaces)
			} else if x.Tok == token.TYPE {
				start := fset.Position(x.Pos())
				end := fset.Position(x.End())

				/* Checks if this function has a comment associated with it */
				var associatedComment string
				if cmap[x] != nil {
					for _, comment := range cmap[x] {
						associatedComment = comment.Text()
					}
				}

				line := fmt.Sprintf("%d", start.Line)
				output.TypeSpecs[line] = PositionRange{
					Name:        x.Tok.String(),
					StartLine:   start.Line,
					StartColumn: start.Column,
					EndLine:     end.Line,
					EndColumn:   end.Column,
					Associated:  associatedComment,
				}

				// Extract out the Imports
			} else if x.Tok == token.IMPORT {
				start := fset.Position(x.Pos())
				end := fset.Position(x.End())

				/* Checks if this function has a comment associated with it */
				var associatedComment string
				if cmap[x] != nil {
					for _, comment := range cmap[x] {
						associatedComment = comment.Text()
					}
				}

				line := fmt.Sprintf("%d", start.Line)
				output.Imports[line] = PositionRange{
					Name:        x.Tok.String(),
					StartLine:   start.Line,
					StartColumn: start.Column,
					EndLine:     end.Line,
					EndColumn:   end.Column,
					Associated:  associatedComment,
				}
			}

			start := fset.Position(x.Pos())
			end := fset.Position(x.End())

			/* Checks if this function has a comment associated with it */
			var associatedComment string
			if cmap[x] != nil {
				for _, comment := range cmap[x] {
					associatedComment = comment.Text()
				}
			}

			line := fmt.Sprintf("%d", start.Line)
			output.LineMap[line] = PositionRange{
				Name:        "",
				StartLine:   start.Line,
				StartColumn: start.Column,
				EndLine:     end.Line,
				EndColumn:   end.Column,
				Associated:  associatedComment,
			}
		}
		return true
	})

	// Convert the struct to JSON
	jsonOutput, err := json.MarshalIndent(output, "", "  ")
	if err != nil {
		log.Fatal(err)
	}

	// Output the JSON
	o := fmt.Sprintf("%s_output.json", fileName)
	err = os.WriteFile(o, jsonOutput, 0644)
	if err != nil {
		log.Fatal("Error writing JSON to file:", err)
	}
}
