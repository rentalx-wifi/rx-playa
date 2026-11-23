// Package cache stores one encoded Opus per key and serves it as a plain file
// from then on.
//
// The first listener of a track pays for the encode and waits a second or two;
// everyone after that costs a sendfile. That is what turns the transcode
// benchmark from "how many can we encode at once" into "how fast can nginx
// push a file", which on this hardware is a different order of magnitude.
//
// The cache is derived data: deleting the directory costs a re-encode and
// nothing else.
package cache

import (
	"context"
	"errors"
	"io/fs"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"time"

	"github.com/rentalx-wifi/rx-playa/streamer/transcode"
)

// Cache is one directory of encoded files, all at the same transcode config.
type Cache struct {
	dir string
	cfg transcode.Config
	sem chan struct{}
	mu  sync.Mutex
	// busy holds the encode in flight per key. A channel rather than a
	// WaitGroup so that a listener waiting on someone else's encode can give up
	// when its own request goes away.
	busy map[string]chan struct{}
	Log  *log.Logger
}

// New returns a cache rooted at dir that encodes at most maxEncode tracks at
// a time.
func New(dir string, cfg transcode.Config, maxEncode int) *Cache {
	if maxEncode <= 0 {
		maxEncode = 1
	}
	return &Cache{
		dir:  dir,
		cfg:  cfg,
		sem:  make(chan struct{}, maxEncode),
		busy: make(map[string]chan struct{}),
	}
}

// Dir is where the encoded files live.
func (c *Cache) Dir() string { return c.dir }

// Path returns where the encoded form of key lives.
func (c *Cache) Path(key string) string {
	return filepath.Join(c.dir, key+".opus")
}

const (
	// What a partial encode is named while it is being written.
	tempPrefix = ".enc-"
	// How long a partial encode has to sit there before it is treated as the
	// remains of a process that died rather than one still working.
	staleTempAge = time.Hour
)

// Ensure makes sure the encoded file for key exists, encoding it at most once
// even if several listeners ask for the same uncached track at once. The
// result is reused only while master is older than it, so replacing the
// source file re-encodes instead of serving a stale copy.
func (c *Cache) Ensure(ctx context.Context, key, master string) (string, error) {
	if strings.Contains(key, "..") || filepath.IsAbs(key) {
		return "", errors.New("cache: bad key")
	}
	dst := c.Path(key)
	if c.fresh(dst, master) {
		return dst, nil
	}

	// Two passes at most: the first may wait on an encode someone else started,
	// and that encode can end without a file — the listener who started it
	// skipped the track, or a new prefetch plan cancelled the old one. Neither
	// is this listener's failure, so the second pass leads an encode of its own
	// instead of turning someone else's cancellation into a playback error.
	for attempt := 0; attempt < 2; attempt++ {
		done, lead := c.claim(key)
		if !lead {
			select {
			case <-done:
			case <-ctx.Done():
				return "", ctx.Err()
			}
			if c.fresh(dst, master) {
				return dst, nil
			}
			continue
		}
		return c.encode(ctx, key, dst, master, done)
	}
	return "", errors.New("cache: encode failed")
}

// claim either joins the encode already running for key — false, with the
// channel that closes when it ends — or takes the lead for this caller.
func (c *Cache) claim(key string) (done chan struct{}, lead bool) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if ch, ok := c.busy[key]; ok {
		return ch, false
	}
	ch := make(chan struct{})
	c.busy[key] = ch
	return ch, true
}

// encode fills the cache as the key's leader and closes the claim when the
// result — file or error — is on disk either way.
func (c *Cache) encode(ctx context.Context, key, dst, master string, done chan struct{}) (string, error) {
	defer func() {
		close(done)
		c.mu.Lock()
		delete(c.busy, key)
		c.mu.Unlock()
	}()

	select {
	case c.sem <- struct{}{}:
		defer func() { <-c.sem }()
	case <-ctx.Done():
		return "", ctx.Err()
	}

	if err := os.MkdirAll(filepath.Dir(dst), 0o755); err != nil {
		return "", err
	}
	tmp, err := os.CreateTemp(filepath.Dir(dst), tempPrefix+"*")
	if err != nil {
		return "", err
	}
	tmpName := tmp.Name()
	start := time.Now()
	err = transcode.Stream(ctx, master, 0, c.cfg, tmp)
	if cerr := tmp.Close(); err == nil {
		err = cerr
	}
	if err != nil {
		os.Remove(tmpName)
		return "", err
	}
	if err := os.Rename(tmpName, dst); err != nil {
		os.Remove(tmpName)
		return "", err
	}
	// CreateTemp is 0600; a reverse proxy running as another user has to be
	// able to read what it serves.
	_ = os.Chmod(dst, 0o644)
	if c.Log != nil {
		if fi, err := os.Stat(dst); err == nil {
			c.Log.Printf("encoded %s in %.2fs (%d KiB)", key, time.Since(start).Seconds(), fi.Size()/1024)
		}
	}
	return dst, nil
}

func (c *Cache) fresh(dst, master string) bool {
	fi, err := os.Stat(dst)
	if err != nil || fi.Size() == 0 {
		return false
	}
	mi, err := os.Stat(master)
	if err != nil {
		return true
	}
	return fi.ModTime().After(mi.ModTime())
}

// Evict deletes the least recently used files until the directory holds no
// more than limit bytes. A key for which keep returns true is never removed:
// that is how the most played tracks stay cached for good. It returns how many
// files went away.
func (c *Cache) Evict(limit int64, keep func(key string) bool) (int, error) {
	type entry struct {
		key  string
		path string
		size int64
		mod  time.Time
	}
	var entries []entry
	var total int64

	err := filepath.WalkDir(c.dir, func(p string, d fs.DirEntry, err error) error {
		if err != nil || d.IsDir() {
			return nil
		}
		fi, err := d.Info()
		if err != nil {
			return nil
		}
		// A half-written encode left by a process that died. It is never served
		// and never counted as cache, so nothing else would ever remove it.
		if strings.HasPrefix(filepath.Base(p), tempPrefix) {
			if time.Since(fi.ModTime()) > staleTempAge {
				_ = os.Remove(p)
			}
			return nil
		}
		if !strings.HasSuffix(p, ".opus") {
			return nil
		}
		rel, err := filepath.Rel(c.dir, p)
		if err != nil {
			return nil
		}
		entries = append(entries, entry{
			key:  strings.TrimSuffix(rel, ".opus"),
			path: p, size: fi.Size(), mod: fi.ModTime(),
		})
		total += fi.Size()
		return nil
	})
	if err != nil {
		return 0, err
	}
	if total <= limit {
		return 0, nil
	}

	sort.Slice(entries, func(i, j int) bool { return entries[i].mod.Before(entries[j].mod) })
	removed := 0
	for _, e := range entries {
		if total <= limit {
			break
		}
		if keep != nil && keep(e.key) {
			continue
		}
		if err := os.Remove(e.path); err != nil {
			continue
		}
		total -= e.size
		removed++
	}
	return removed, nil
}

// ServeFile answers with the cached file, honouring Range so the player can
// seek without a re-encode.
func ServeFile(w http.ResponseWriter, r *http.Request, path string) {
	f, err := os.Open(path)
	if err != nil {
		http.Error(w, "not found", http.StatusNotFound)
		return
	}
	defer f.Close()
	fi, err := f.Stat()
	if err != nil {
		http.Error(w, "not found", http.StatusNotFound)
		return
	}
	// The mtime doubles as the last-use time for LRU eviction. Touching on
	// every seek would be a write per range request, so an hour is close
	// enough for a cache whose entries live for weeks.
	if fi.ModTime().Before(time.Now().Add(-time.Hour)) {
		now := time.Now()
		_ = os.Chtimes(path, now, now)
	}
	w.Header().Set("Content-Type", "audio/ogg")
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("X-Accel-Buffering", "no")
	http.ServeContent(w, r, filepath.Base(path), fi.ModTime(), f)
}
