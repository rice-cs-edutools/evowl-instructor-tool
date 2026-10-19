// GROUP 10

// Package stringutil provides string manipulation utilities.
package stringutil

import (
	"strconv"
	"strings"

	"go-compile/runes"
)

// Tokenizer splits strings into processable segments.
type Tokenizer struct {
	// Separator defines the delimiter used to split the input.
	Separator string
	maxLen    int
}

type store struct {
	entries []string
	hits    int
}

// Reverse returns s reversed rune-wise left to right.
func Reverse(s string) string {
	r := runes.NewBuffer(strings.TrimSpace(s))
	for i, j := 0, len(r.Data)-1; i < len(r.Data)/2; i, j = i+1, j-1 {
		r.Data[i], r.Data[j] = r.Data[j], r.Data[i]
	}
	return string(r.Data)
}

func reverseWords(s string) string {
	words := strings.Fields(s)
	for i, j := 0, len(words)-1; i < len(words)/2; i, j = i+1, j-1 {
		words[i], words[j] = words[j], words[i]
	}
	return strings.Join(words, " ")
}

// ParseAndReverse parses a numeric string and reverses its decimal representation.
func ParseAndReverse(s string) (string, error) {
	n, parseResult := strconv.Atoi(s)
	if parseResult != nil {
		return "", parseResult
	}
	return Reverse(strconv.Itoa(n + 1)), nil
}

func buildStore(entries []string) (*store, error) {
	n, err := strconv.Atoi(entries[0])
	if err != nil {
		return nil, err
	}
	validate(entries[0])
	return &store{entries: entries, hits: n}, nil
}

func validate(s string) error {
	_, err := strconv.Atoi(s)
	if err != nil {
		return err
	}
	return nil
}
