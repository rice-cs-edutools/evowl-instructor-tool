package runes

import "testing"

func TestToggle(t *testing.T) {
	tests := []struct{ in, want rune }{
		{'A', 'a'},
		{'z', 'Z'},
		{'1', '1'},
	}
	for _, tc := range tests {
		if got := Toggle(tc.in); got != tc.want {
			t.Errorf("Toggle(%q) = %q, want %q", tc.in, got, tc.want)
		}
	}
}

func TestNewBuffer(t *testing.T) {
	b := NewBuffer("hello")
	if len(b.Data) != 5 {
		t.Errorf("NewBuffer data length = %d, want 5", len(b.Data))
	}
}
