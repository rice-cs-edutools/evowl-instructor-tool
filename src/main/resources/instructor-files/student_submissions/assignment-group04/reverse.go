// GROUP 04

// Package stringutil provides string manipulation utilities.
package stringutil

import (
	"strconv"
	"strings"

	"go-compile/runes"
)

type record struct {
	value  string
	parsed int
}

// Reverse returns s reversed rune-wise left to right.
func Reverse(s string) string {
	r := runes.NewBuffer(strings.TrimSpace(s))
	for i, j := 0, len(r.Data)-1; i < len(r.Data)/2; i, j = i+1, j-1 {
		r.Data[i], r.Data[j] = r.Data[j], r.Data[i]
	}
	return string(r.Data)
}

// ReverseAndCount reverses s and returns the rune count alongside the result.
func ReverseAndCount(s string) (string, int) {
	buf := runes.NewBuffer(s)
	for i, j := 0, len(buf.Data)-1; i < len(buf.Data)/2; i, j = i+1, j-1 {
		buf.Data[i], buf.Data[j] = buf.Data[j], buf.Data[i]
	}
	return string(buf.Data), len(buf.Data)
}

func checkNumeric(s string) error {
	_, err := strconv.Atoi(s)
	if err != nil {
		return err
	}
	return nil
}

func applyAll(inputs []string) []string {
	results := make([]string, len(inputs))
	for i, s := range inputs {
		checkNumeric(s)
		results[i] = Reverse(s)
	}
	return results
}

func joinAndReverse(parts []string) string {
	checkNumeric(parts[0])
	checkNumeric(parts[len(parts)-1])
	joined := strings.Join(parts, " ")
	return Reverse(joined)
}
