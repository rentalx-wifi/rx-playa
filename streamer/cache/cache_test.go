package cache

import (
	"context"
	"os"
	"os/exec"
	"path/filepath"
	"testing"
	"time"

	"github.com/rentalx-wifi/rx-playa/streamer/transcode"
)

func genFLAC(t *testing.T, dir, name string, seed int) string {
	t.Helper()
	path := filepath.Join(dir, name)
	cmd := exec.Command("ffmpeg", "-v", "error", "-y",
		"-f", "lavfi", "-i", "anoisesrc=duration=8:color=pink:seed="+string(rune('0'+seed)),
		path)
	if out, err := cmd.CombinedOutput(); err != nil {
		t.Skipf("ffmpeg unavailable: %v: %s", err, out)
	}
	return path
}

func TestEnsureEncodesOnceThenServes(t *testing.T) {
	lib := t.TempDir()
	master := genFLAC(t, lib, "a.flac", 1)
	dir := filepath.Join(t.TempDir(), "opus")

	cfg := transcode.Default
	cfg.Bitrate = 192000
	c := New(dir, cfg, 2)
	ctx := context.Background()

	first, err := c.Ensure(ctx, "track-1", master)
	if err != nil {
		t.Fatal(err)
	}
	fi, err := os.Stat(first)
	if err != nil {
		t.Fatal(err)
	}
	if fi.Size() == 0 {
		t.Fatal("encoded file is empty")
	}
	if fi.Mode().Perm() != 0o644 {
		t.Fatalf("mode = %v, want 0644 so a proxy can read it", fi.Mode().Perm())
	}
	stamp := fi.ModTime()

	// A second request must return the same file without touching it.
	second, err := c.Ensure(ctx, "track-1", master)
	if err != nil {
		t.Fatal(err)
	}
	if second != first {
		t.Fatalf("second ensure returned %q, want %q", second, first)
	}
	fi, err = os.Stat(second)
	if err != nil {
		t.Fatal(err)
	}
	if !fi.ModTime().Equal(stamp) {
		t.Fatal("second ensure re-encoded a file that was already fresh")
	}

	// Replacing the source invalidates the cache entry.
	time.Sleep(10 * time.Millisecond)
	replacement := genFLAC(t, lib, "b.flac", 2)
	if err := os.Rename(replacement, master); err != nil {
		t.Fatal(err)
	}
	if _, err := c.Ensure(ctx, "track-1", master); err != nil {
		t.Fatal(err)
	}
	fi2, err := os.Stat(first)
	if err != nil {
		t.Fatal(err)
	}
	if !fi2.ModTime().After(stamp) {
		t.Fatal("a newer master did not invalidate the cached encode")
	}
}

func TestEnsureRejectsEscapingKeys(t *testing.T) {
	c := New(t.TempDir(), transcode.Default, 1)
	for _, key := range []string{"../evil", "/etc/passwd"} {
		if _, err := c.Ensure(context.Background(), key, "/nonexistent"); err == nil {
			t.Fatalf("key %q was accepted", key)
		}
	}
}

func TestEnsureSurvivesAnotherLeadersFailure(t *testing.T) {
	lib := t.TempDir()
	master := genFLAC(t, lib, "a.flac", 2)
	c := New(filepath.Join(t.TempDir(), "opus"), transcode.Default, 1)

	// Someone else led the encode for this key and gave up without a file:
	// the listener skipped the track, or a new prefetch plan cancelled theirs.
	// The claim closes with nothing on disk, which used to make every waiter
	// report the leader's cancellation as its own failure — a 500 on playback,
	// over a track one more encode would have delivered.
	done, lead := c.claim("track-1")
	if !lead {
		t.Fatal("claim did not take the lead on an idle key")
	}
	close(done)
	c.mu.Lock()
	delete(c.busy, "track-1")
	c.mu.Unlock()

	got, err := c.Ensure(context.Background(), "track-1", master)
	if err != nil {
		t.Fatalf("waiter inherited another leader's failure: %v", err)
	}
	if fi, err := os.Stat(got); err != nil || fi.Size() == 0 {
		t.Fatalf("no usable file after retry: %v", err)
	}
}

func TestEnsureWaiterHonoursItsOwnCancellation(t *testing.T) {
	c := New(filepath.Join(t.TempDir(), "opus"), transcode.Default, 1)
	// A leader that never finishes, standing in for a hung ffmpeg.
	done, lead := c.claim("track-1")
	if !lead {
		t.Fatal("claim did not take the lead on an idle key")
	}
	defer close(done)

	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	waited := make(chan struct{})
	go func() {
		defer close(waited)
		if _, err := c.Ensure(ctx, "track-1", "/nonexistent"); err == nil {
			t.Error("expected an error from a cancelled waiter")
		}
	}()
	select {
	case <-waited:
	case <-time.After(2 * time.Second):
		t.Fatal("a waiter outlived its own request: waiting on someone else's encode ignored the context")
	}
}

func TestEvictDropsLeastRecentlyUsedAndKeepsPinned(t *testing.T) {
	dir := t.TempDir()
	write := func(key string, age time.Duration) string {
		t.Helper()
		p := filepath.Join(dir, key+".opus")
		if err := os.WriteFile(p, make([]byte, 1000), 0o644); err != nil {
			t.Fatal(err)
		}
		stamp := time.Now().Add(-age)
		if err := os.Chtimes(p, stamp, stamp); err != nil {
			t.Fatal(err)
		}
		return p
	}

	pinned := write("pinned", 4*time.Hour) // oldest, but never evicted
	old := write("old", 3*time.Hour)
	mid := write("mid", 2*time.Hour)
	fresh := write("fresh", time.Hour)

	c := New(dir, transcode.Default, 1)
	// 4000 bytes on disk, limit 2500: the two oldest unpinned files have to go.
	removed, err := c.Evict(2500, func(key string) bool { return key == "pinned" })
	if err != nil {
		t.Fatal(err)
	}
	if removed != 2 {
		t.Fatalf("removed %d files, want 2", removed)
	}
	for _, p := range []string{pinned, fresh} {
		if _, err := os.Stat(p); err != nil {
			t.Fatalf("%s should have survived: %v", filepath.Base(p), err)
		}
	}
	for _, p := range []string{old, mid} {
		if _, err := os.Stat(p); !os.IsNotExist(err) {
			t.Fatalf("%s should have been evicted", filepath.Base(p))
		}
	}
}

func TestEvictDoesNothingUnderTheLimit(t *testing.T) {
	dir := t.TempDir()
	if err := os.WriteFile(filepath.Join(dir, "a.opus"), make([]byte, 100), 0o644); err != nil {
		t.Fatal(err)
	}
	c := New(dir, transcode.Default, 1)
	removed, err := c.Evict(1000, nil)
	if err != nil {
		t.Fatal(err)
	}
	if removed != 0 {
		t.Fatalf("removed %d files under the limit", removed)
	}
}
