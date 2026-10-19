// Package runes provides low-level rune manipulation utilities.
package runes

import "unicode"

// Buffer holds a sequence of runes for in-place modification.
type Buffer struct {
	Data    []rune
	maxSize int
}

// NewBuffer allocates a Buffer from the given string.
func NewBuffer(s string) *Buffer {
	return &Buffer{Data: []rune(s), maxSize: len(s)}
}

// Toggle flips the Unicode case of a single rune.
func Toggle(r rune) rune {
	if unicode.IsUpper(r) {
		return unicode.ToLower(r)
	}
	return unicode.ToUpper(r)
}
