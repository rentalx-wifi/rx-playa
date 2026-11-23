package ingest

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"runtime"
	"sort"
	"sync"

	"github.com/rentalx-wifi/rx-playa/flashmusic/store"
)

// Options configures one scan of the library directory.
type Options struct {
	Library string
	Source  string
	Workers int
	Log     func(format string, args ...any)
}

// Result is what one scan did.
type Result struct {
	Files      int
	Scanned    int
	Cached     int
	NewTracks  int
	Updated    int
	Renditions int
	// VariantsLinked are slowed/sped-up edits derivable from their base track,
	// so their file does not need to be kept.
	VariantsLinked int
	// VariantsKept are edits that did not match the base recording and stay on
	// disk as separate files.
	VariantsKept int
	// Redundant lists files that can be deleted now that the catalog holds only
	// the base recording.
	Redundant []string
	Errors    []string
}

// Run walks the library, fingerprints every new or changed file and writes the
// result to the catalog. Files that already have the same song in the catalog
// become renditions of that track, not new tracks.
func Run(ctx context.Context, st *store.Store, opt Options) (*Result, error) {
	if opt.Library == "" {
		return nil, errors.New("ingest: library path is empty")
	}
	if opt.Source == "" {
		opt.Source = "upload"
	}
	if opt.Workers <= 0 {
		opt.Workers = runtime.NumCPU()
		if opt.Workers > 8 {
			opt.Workers = 8
		}
	}
	if opt.Log == nil {
		opt.Log = func(string, ...any) {}
	}

	paths, err := Walk(opt.Library)
	if err != nil {
		return nil, err
	}

	before, err := st.Stats(ctx)
	if err != nil {
		return nil, err
	}

	res := &Result{Files: len(paths)}
	probes := make([]*Probe, len(paths))

	var mu sync.Mutex
	var wg sync.WaitGroup
	sem := make(chan struct{}, opt.Workers)
	processed := 0

	for i, path := range paths {
		wg.Add(1)
		sem <- struct{}{}
		go func(i int, path string) {
			defer wg.Done()
			defer func() { <-sem }()

			p, cached, err := probeCached(ctx, st, path)

			mu.Lock()
			defer mu.Unlock()
			if err != nil {
				if len(res.Errors) < 20 {
					res.Errors = append(res.Errors, err.Error())
				}
				return
			}
			probes[i] = p
			if cached {
				res.Cached++
			} else {
				res.Scanned++
			}
			processed++
			if processed%200 == 0 {
				opt.Log("просканировано %d/%d", processed, len(paths))
			}
		}(i, path)
	}
	wg.Wait()

	var variantItems []pendingVariant
	var normalProbes []*Probe
	for _, p := range probes {
		if p == nil {
			continue
		}
		if m, ok := DetectVariant(p.Title); ok {
			variantItems = append(variantItems, pendingVariant{probe: p, match: m})
			continue
		}
		normalProbes = append(normalProbes, p)
	}

	groups, order := groupByFingerprint(normalProbes, opt.Source)
	res.count(ctx, st, groups, order)

	if len(variantItems) > 0 {
		linked, kept, redundant, leftover := linkVariants(ctx, st, variantItems, opt.Log)
		res.VariantsLinked, res.VariantsKept = linked, kept
		res.Redundant = redundant
		if len(leftover) > 0 {
			lg, lo := groupByFingerprint(leftover, opt.Source)
			res.count(ctx, st, lg, lo)
		}
	}

	after, err := st.Stats(ctx)
	if err != nil {
		return nil, err
	}
	res.Renditions = after.Renditions - before.Renditions
	return res, nil
}

// count writes a batch of track groups to the catalog and tallies the result.
func (r *Result) count(ctx context.Context, st *store.Store, groups map[string]*group, order []string) {
	for _, fp := range order {
		g := groups[fp]
		created, changed, err := st.Ingest(ctx, g.track, g.rends)
		if err != nil {
			if len(r.Errors) < 20 {
				r.Errors = append(r.Errors, fmt.Sprintf("%s: %v", g.track.Title, err))
			}
			continue
		}
		switch {
		case created:
			r.NewTracks++
		case changed:
			r.Updated++
		}
	}
}

// Walk returns every audio file under root, sorted so scanning is repeatable.
func Walk(root string) ([]string, error) {
	var out []string
	err := filepath.WalkDir(root, func(path string, d os.DirEntry, err error) error {
		if err != nil {
			return err
		}
		if d.IsDir() {
			if name := d.Name(); name == ".git" || name == "variants" {
				return filepath.SkipDir
			}
			return nil
		}
		if audioExt[filepath.Ext(path)] {
			out = append(out, path)
		}
		return nil
	})
	if err != nil {
		return nil, err
	}
	sort.Strings(out)
	return out, nil
}

// probeCached reuses the stored probe when the file has not changed since the
// last scan, which is what keeps a second scan of a large library fast.
func probeCached(ctx context.Context, st *store.Store, path string) (*Probe, bool, error) {
	st1, err := os.Stat(path)
	if err != nil {
		return nil, false, err
	}
	size, mtime := st1.Size(), st1.ModTime().Unix()
	if buf, ok := st.ScanCache(ctx, path, size, mtime); ok {
		var p Probe
		if err := json.Unmarshal(buf, &p); err == nil {
			return &p, true, nil
		}
	}
	p, err := ScanFile(ctx, path)
	if err != nil {
		return nil, false, err
	}
	if buf, err := json.Marshal(p); err == nil {
		_ = st.PutScan(ctx, path, p.Size, p.Mtime, buf)
	}
	return p, false, nil
}

type group struct {
	track store.Track
	rends []store.Rendition
}

func groupByFingerprint(probes []*Probe, source string) (map[string]*group, []string) {
	groups := make(map[string]*group)
	var order []string
	for _, p := range probes {
		if p == nil {
			continue
		}
		g, ok := groups[p.Fingerprint]
		if !ok {
			g = &group{track: store.Track{
				Title:       p.Title,
				Artist:      p.Artist,
				Album:       p.Album,
				DurationMs:  p.DurationMs,
				Year:        p.Year,
				TrackNo:     p.TrackNo,
				DiscNo:      p.DiscNo,
				Fingerprint: p.Fingerprint,
			}}
			groups[p.Fingerprint] = g
			order = append(order, p.Fingerprint)
		}
		g.rends = append(g.rends, store.Rendition{
			Codec:      p.Codec,
			SampleRate: p.SampleRate,
			Bits:       p.Bits,
			Bitrate:    p.Bitrate,
			Bytes:      p.Size,
			Path:       p.Path,
			Source:     source,
		})
	}
	sort.Strings(order)
	return groups, order
}
