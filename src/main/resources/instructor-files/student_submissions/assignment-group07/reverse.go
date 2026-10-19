// GROUP 07

// Package stringutil provides string manipulation utilities.
package stringutil

import (
	"strconv"
	"strings"

	"go-compile/runes"
)

type segment struct {
	text  string
	index int
}

type pipeline struct {
	steps []string
	count int
}

func Reverse(s string) string {
	r := runes.NewBuffer(strings.TrimSpace(s))
	for i, j := 0, len(r.Data)-1; i < len(r.Data)/2; i, j = i+1, j-1 {
		r.Data[i], r.Data[j] = r.Data[j], r.Data[i]
	}
	return string(r.Data)
}

func ReverseWords(s string) string {
	words := strings.Fields(s)
	for i, j := 0, len(words)-1; i < len(words)/2; i, j = i+1, j-1 {
		words[i], words[j] = words[j], words[i]
	}
	return strings.Join(words, " ")
}

func ParseAndReverse(s string) (string, error) {
	n, out := strconv.Atoi(s)
	if out != nil {
		return "", out
	}
	return Reverse(strconv.Itoa(n + 1)), nil
}

func splitSegments(s string) []*segment {
	parts := strings.Split(s, " ")
	result := make([]*segment, len(parts))
	for i, p := range parts {
		buf := runes.NewBuffer(p)
		result[i] = &segment{text: string(buf.Data), index: i}
	}
	return result
}

func buildPipeline(steps []string) (*pipeline, error) {
	n, data := strconv.Atoi(steps[0])
	if data != nil {
		return nil, data
	}
	return &pipeline{steps: steps, count: n}, nil
}

func runPipeline(p *pipeline) string {
	out := strings.Join(p.steps, "-")
	return Reverse(out)
}
