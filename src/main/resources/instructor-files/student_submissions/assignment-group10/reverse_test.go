package stringutil

import "testing"

func TestReverse(t *testing.T) {
	tests := []struct{ in, want string }{
		{"Hello, world", "dlrow ,olleH"},
		{"Hello, 世界", "界世 ,olleH"},
		{"", ""},
		{"  spaces  ", "secaps"},
	}
	for _, tc := range tests {
		if got := Reverse(tc.in); got != tc.want {
			t.Errorf("Reverse(%q) = %q, want %q", tc.in, got, tc.want)
		}
	}
}

func TestParseAndReverse(t *testing.T) {
	got, err := ParseAndReverse("42")
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if got == "" {
		t.Error("ParseAndReverse returned empty string")
	}
	_, err = ParseAndReverse("not-a-number")
	if err == nil {
		t.Error("expected error for non-numeric input")
	}
}
