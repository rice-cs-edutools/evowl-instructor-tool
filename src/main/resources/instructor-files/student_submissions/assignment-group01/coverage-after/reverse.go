// GROUP 01

// Package stringutil provides string manipulation utilities.
package stringutil

import (
	"strconv"
	"strings"

	"go-compile/runes"
)

// Result holds the output of a Reverse operation.
type Result struct {
	// Value is the reversed string.
	Value  string
	length int
}

// Reverser performs string reversal with optional case toggling.
type Reverser struct {
	// Input is the original string to reverse.
	Input      string
	// ToggleCase enables per-rune case toggling during reversal.
	ToggleCase bool
	buf        *runes.Buffer
}

// Reverse returns s reversed rune-wise left to right.
func Reverse(s string) string {
	r := runes.NewBuffer(strings.TrimSpace(s))
	for i, j := 0, len(r.Data)-1; i < len(r.Data)/2; i, j = i+1, j-1 {
		r.Data[i], r.Data[j] = r.Data[j], r.Data[i]
	}
	return string(r.Data)
}

// ReverseWithToggle reverses s and flips the Unicode case of each rune.
func ReverseWithToggle(s string) string {
	buf := runes.NewBuffer(s)
	for i, r := range buf.Data {
		buf.Data[i] = runes.Toggle(r)
	}
	return Reverse(string(buf.Data))
}

// ParseAndReverse parses a numeric string, increments the value, then
// reverses its decimal representation.
func ParseAndReverse(s string) (string, error) {
	n, err := strconv.Atoi(s)
	if err != nil {
		return "", err
	}
	return Reverse(strconv.Itoa(n + 1)), nil
}

func buildResult(s string) (*Result, error) {
	reversed, err := ParseAndReverse(s)
	if err != nil {
		return nil, err
	}
	return &Result{Value: reversed, length: len(reversed)}, nil
}
