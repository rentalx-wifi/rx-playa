package store

import (
	"context"
	"fmt"
	"path/filepath"
	"testing"
)

func openTestStore(t *testing.T) *Store {
	t.Helper()
	st, err := Open(filepath.Join(t.TempDir(), "fm.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = st.Close() })
	return st
}

func ingestTestTrack(t *testing.T, st *Store, title, fingerprint string) {
	t.Helper()
	_, _, err := st.Ingest(context.Background(),
		Track{Title: title, Artist: "Test Artist", DurationMs: 120_000, Fingerprint: fingerprint},
		[]Rendition{{
			Codec: "flac", SampleRate: 44100, Bits: 16, Bitrate: 900_000,
			Bytes: 1000, Path: "/music/" + fingerprint + ".flac", Source: "test",
		}})
	if err != nil {
		t.Fatal(err)
	}
}

// A listener types "%" or "a_b" into search. Those are LIKE's own wildcards, and
// passing them through turned one keystroke into the whole library.
func TestSearchTreatsWildcardsLiterally(t *testing.T) {
	st := openTestStore(t)
	ingestTestTrack(t, st, "100% Pure", "fp-1")
	ingestTestTrack(t, st, "a_b", "fp-2")
	ingestTestTrack(t, st, "Ordinary Song", "fp-3")

	ctx := context.Background()
	// The wildcards are literal: each finds only the track carrying that
	// character in its title, rather than the whole library at once.
	for q, want := range map[string]string{"%": "100% Pure", "_": "a_b"} {
		got, err := st.Search(ctx, q, 50)
		if err != nil {
			t.Fatal(err)
		}
		if len(got) != 1 || got[0].Title != want {
			t.Fatalf("query %q returned %d tracks, want just %q", q, len(got), want)
		}
	}
	// Nothing in the library carries two of them in a row.
	if got, err := st.Search(ctx, "%%", 50); err != nil || len(got) != 0 {
		t.Fatalf("query %q returned %d tracks (err %v), want none", "%%", len(got), err)
	}

	got, err := st.Search(ctx, "100%", 50)
	if err != nil {
		t.Fatal(err)
	}
	if len(got) != 1 || got[0].Title != "100% Pure" {
		t.Fatalf("query %q returned %+v, want the one track titled 100%% Pure", "100%", got)
	}

	got, err = st.Search(ctx, "a_b", 50)
	if err != nil {
		t.Fatal(err)
	}
	if len(got) != 1 || got[0].Title != "a_b" {
		t.Fatalf("query %q returned %+v, want exactly a_b", "a_b", got)
	}
}

// The first sync of a real library is one manifest call, and every track in it
// has its renditions looked up by `IN (?,?,…)`. Past SQLite's ceiling on query
// variables that failed outright, so the lookup walks the page in chunks.
func TestManifestAttachesRenditionsAcrossChunks(t *testing.T) {
	st := openTestStore(t)
	const tracks = attachBatch*2 + 7
	for i := 0; i < tracks; i++ {
		ingestTestTrack(t, st, fmt.Sprintf("Track %03d", i), fmt.Sprintf("fp-%03d", i))
	}

	page, err := st.ManifestPage(context.Background(), 0, tracks)
	if err != nil {
		t.Fatal(err)
	}
	if len(page.Tracks) != tracks {
		t.Fatalf("manifest returned %d tracks, want %d", len(page.Tracks), tracks)
	}
	for _, tr := range page.Tracks {
		if len(tr.Renditions) != 1 {
			t.Fatalf("%s has %d renditions, want 1 — the chunk that held it was not attached",
				tr.Title, len(tr.Renditions))
		}
	}
}
