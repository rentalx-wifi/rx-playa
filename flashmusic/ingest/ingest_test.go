package ingest

import (
	"context"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"testing"

	"github.com/rentalx-wifi/rx-playa/flashmusic/store"
)

func requireTools(t *testing.T) {
	t.Helper()
	for _, bin := range []string{"ffmpeg", "ffprobe", "fpcalc"} {
		if _, err := exec.LookPath(bin); err != nil {
			t.Skipf("%s not installed", bin)
		}
	}
}

func gen(t *testing.T, dir, name string, seed int, title string) string {
	t.Helper()
	path := filepath.Join(dir, name)
	cmd := exec.Command("ffmpeg", "-v", "error", "-y",
		"-f", "lavfi", "-i", fmt.Sprintf("anoisesrc=duration=15:color=pink:seed=%d", seed),
		"-metadata", "title="+title,
		"-metadata", "artist=Test Artist",
		"-metadata", "album=Test Album",
		"-metadata", "date=1999",
		path)
	if out, err := cmd.CombinedOutput(); err != nil {
		t.Fatalf("ffmpeg %s: %v: %s", name, err, out)
	}
	return path
}

func TestRunIngestsAndDeduplicates(t *testing.T) {
	requireTools(t)
	lib := t.TempDir()
	// Noise, not a sine: a pure tone has no spectral texture to fingerprint and
	// every tone ends up with the same near-empty print.
	gen(t, lib, "a.flac", 1, "First Song")
	gen(t, lib, "b.flac", 2, "Second Song")

	// The same recording, byte for byte: it must join the first track as a
	// second rendition instead of becoming a new one.
	data, err := os.ReadFile(filepath.Join(lib, "a.flac"))
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(lib, "a-copy.flac"), data, 0o644); err != nil {
		t.Fatal(err)
	}

	st, err := store.Open(filepath.Join(t.TempDir(), "cat.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer st.Close()

	res, err := Run(context.Background(), st, Options{Library: lib, Workers: 2})
	if err != nil {
		t.Fatal(err)
	}
	if len(res.Errors) > 0 {
		t.Fatalf("scan errors: %v", res.Errors)
	}
	if res.Files != 3 {
		t.Fatalf("files = %d, want 3", res.Files)
	}
	if res.NewTracks != 2 {
		t.Fatalf("new tracks = %d, want 2 (the copy must dedupe)", res.NewTracks)
	}

	stats, err := st.Stats(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if stats.Tracks != 2 || stats.Renditions != 3 {
		t.Fatalf("stats = %+v, want 2 tracks and 3 renditions", stats)
	}

	man, err := st.Manifest(context.Background(), 0)
	if err != nil {
		t.Fatal(err)
	}
	if len(man.Tracks) != 2 {
		t.Fatalf("manifest tracks = %d, want 2", len(man.Tracks))
	}
	for _, tr := range man.Tracks {
		if tr.Title == "" || tr.Artist != "Test Artist" || tr.Year != 1999 {
			t.Fatalf("tags not carried over: %+v", tr)
		}
		masters := 0
		for _, r := range tr.Renditions {
			if r.IsMaster {
				masters++
			}
		}
		if masters != 1 {
			t.Fatalf("track %s has %d masters, want 1", tr.Title, masters)
		}
		deltas, err := st.Manifest(context.Background(), man.Now)
		if err != nil {
			t.Fatal(err)
		}
		if len(deltas.Tracks) != 0 {
			t.Fatalf("second sync since Now returned %d tracks, want 0", len(deltas.Tracks))
		}
	}
}

func TestRunIsIdempotent(t *testing.T) {
	requireTools(t)
	lib := t.TempDir()
	gen(t, lib, "a.flac", 1, "First Song")

	st, err := store.Open(filepath.Join(t.TempDir(), "cat.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer st.Close()
	ctx := context.Background()

	first, err := Run(ctx, st, Options{Library: lib})
	if err != nil {
		t.Fatal(err)
	}
	if first.NewTracks != 1 {
		t.Fatalf("first scan new tracks = %d, want 1", first.NewTracks)
	}
	rev, err := st.Manifest(ctx, 0)
	if err != nil {
		t.Fatal(err)
	}

	second, err := Run(ctx, st, Options{Library: lib})
	if err != nil {
		t.Fatal(err)
	}
	if second.Updated != 0 {
		t.Fatalf("second scan updated %d tracks, want 0", second.Updated)
	}
	if second.Scanned != 0 || second.Cached != 1 {
		t.Fatalf("second scan scanned=%d cached=%d, want the probe to come from cache", second.Scanned, second.Cached)
	}
	again, err := st.Manifest(ctx, rev.Now)
	if err != nil {
		t.Fatal(err)
	}
	if len(again.Tracks) != 0 {
		t.Fatalf("unchanged re-scan bumped %d tracks, want 0", len(again.Tracks))
	}
}
