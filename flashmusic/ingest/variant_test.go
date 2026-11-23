package ingest

import (
	"context"
	"os/exec"
	"path/filepath"
	"strconv"
	"testing"

	"github.com/rentalx-wifi/rx-playa/flashmusic/store"
)

func TestDetectVariant(t *testing.T) {
	cases := []struct {
		title  string
		kind   string
		base   string
		ok     bool
		reverb bool
	}{
		{"First Song (Slowed + Reverb)", store.VariantSlowed, "First Song", true, true},
		{"First Song - Sped Up", store.VariantSpedUp, "First Song", true, false},
		{"First Song slowed", store.VariantSlowed, "First Song", true, false},
		{"First Song (Super Sped Up)", store.VariantSuperSpedUp, "First Song", true, false},
		{"First Song (Super Slowed)", store.VariantSuperSlowed, "First Song", true, false},
		{"First Song [Ultra Slowed]", store.VariantUltraSlowed, "First Song", true, false},
		{"First Song (Nightcore)", store.VariantSpedUp, "First Song", true, false},
		{"First Song", "", "", false, false},
		{"Slowdive - Alison", "", "", false, false},
		// A song title that merely carries the word: reading it as an edit of
		// "Down" kept the file out of the catalogue entirely.
		{"Slow Down", "", "", false, false},
		{"Slow Hands", "", "", false, false},
		{"First Song (Live)", "", "", false, false},
		// Prefix and separator forms are real edit titles.
		{"Nightcore - First Song", store.VariantSpedUp, "First Song", true, false},
		{"Slowed: First Song", store.VariantSlowed, "First Song", true, false},
	}
	for _, c := range cases {
		m, ok := DetectVariant(c.title)
		if ok != c.ok {
			t.Fatalf("%q: ok = %v, want %v", c.title, ok, c.ok)
		}
		if !ok {
			continue
		}
		if m.Kind != c.kind || m.Base != c.base || m.Reverb != c.reverb {
			t.Fatalf("%q: got %+v, want kind=%s base=%q reverb=%v", c.title, m, c.kind, c.base, c.reverb)
		}
	}
}

// A search query is held to a looser rule than a file title: "song slowed"
// with nothing else around it is how people ask for the edit.
//
// The looseness costs a second query and nothing else — asking for "Slow Down"
// searches for "Down" as well and merges what comes back — which is why it is
// allowed here and not on a file's title, where being wrong loses the track.
func TestDetectVariantQuery(t *testing.T) {
	m, ok := DetectVariantQuery("First Song slowed")
	if !ok || m.Kind != store.VariantSlowed || m.Base != "First Song" {
		t.Fatalf("got %+v ok=%v, want a slowed edit of First Song", m, ok)
	}
	if _, ok := DetectVariantQuery("First Song"); ok {
		t.Fatal("an ordinary query was read as an edit")
	}
}

// slowedCopy makes a real slowed edit: the base played slower, pitch and all.
func slowedCopy(t *testing.T, base, out string, rate float64) {
	t.Helper()
	filter := "asetrate=" + strconv.Itoa(int(48000*rate+0.5)) + ",aresample=48000"
	cmd := exec.Command("ffmpeg", "-v", "error", "-y", "-i", base, "-af", filter,
		"-metadata", "title=Second Song (Slowed)", "-metadata", "artist=Test Artist", out)
	if b, err := cmd.CombinedOutput(); err != nil {
		t.Fatalf("ffmpeg slowed copy: %v: %s", err, b)
	}
}

func TestVariantCollapsesIntoBaseTrack(t *testing.T) {
	requireTools(t)
	lib := t.TempDir()
	base := gen(t, lib, "base.flac", 1, "Second Song")
	slowedCopy(t, base, filepath.Join(lib, "base-slowed.flac"), 0.85)

	st, err := store.Open(filepath.Join(t.TempDir(), "cat.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer st.Close()
	ctx := context.Background()

	res, err := Run(ctx, st, Options{Library: lib})
	if err != nil {
		t.Fatal(err)
	}
	if len(res.Errors) > 0 {
		t.Fatalf("scan errors: %v", res.Errors)
	}
	if res.NewTracks != 1 {
		t.Fatalf("new tracks = %d, want 1: the slowed edit is not a track of its own", res.NewTracks)
	}
	if res.VariantsLinked != 1 {
		t.Fatalf("linked variants = %d, want 1", res.VariantsLinked)
	}
	if len(res.Redundant) != 1 {
		t.Fatalf("redundant files = %v, want the slowed copy", res.Redundant)
	}

	man, err := st.Manifest(ctx, 0)
	if err != nil {
		t.Fatal(err)
	}
	if len(man.Tracks) != 1 {
		t.Fatalf("manifest tracks = %d, want 1", len(man.Tracks))
	}
	vs := man.Tracks[0].Variants
	if len(vs) != 1 {
		t.Fatalf("variants = %d, want 1", len(vs))
	}
	v := vs[0]
	if v.Kind != store.VariantSlowed || !v.Verified || v.Path != "" {
		t.Fatalf("variant = %+v, want a verified slowed edit with no file of its own", v)
	}
	if v.Rate < 0.82 || v.Rate > 0.88 {
		t.Fatalf("rate = %v, want about 0.85 as measured from the durations", v.Rate)
	}
}

func TestVariantWithDifferentMixIsKept(t *testing.T) {
	requireTools(t)
	lib := t.TempDir()
	gen(t, lib, "base.flac", 1, "Third Song")
	// A different recording wearing the same title: it cannot be derived.
	gen(t, lib, "fake.flac", 7, "Third Song (Sped Up)")

	st, err := store.Open(filepath.Join(t.TempDir(), "cat.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer st.Close()
	ctx := context.Background()

	res, err := Run(ctx, st, Options{Library: lib})
	if err != nil {
		t.Fatal(err)
	}
	if res.VariantsKept != 1 {
		t.Fatalf("kept variants = %d, want 1", res.VariantsKept)
	}
	if len(res.Redundant) != 0 {
		t.Fatalf("redundant = %v, want nothing deleted", res.Redundant)
	}

	man, err := st.Manifest(ctx, 0)
	if err != nil {
		t.Fatal(err)
	}
	var found bool
	for _, tr := range man.Tracks {
		for _, v := range tr.Variants {
			if v.Kind == store.VariantSpedUp {
				found = true
				if v.Verified || v.Path == "" {
					t.Fatalf("variant = %+v, want it kept as its own file", v)
				}
				if v.Rate != 1 {
					t.Fatalf("rate = %v, want 1: the speed is baked into the kept file", v.Rate)
				}
			}
		}
	}
	if !found {
		t.Fatal("no sped-up variant recorded")
	}
}
