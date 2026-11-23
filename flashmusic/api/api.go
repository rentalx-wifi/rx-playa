// Package api exposes the catalog and the audio itself over HTTP.
package api

import (
	"encoding/json"
	"errors"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"

	"github.com/rentalx-wifi/rx-playa/flashmusic/ingest"
	"github.com/rentalx-wifi/rx-playa/flashmusic/prefetch"
	"github.com/rentalx-wifi/rx-playa/flashmusic/store"
	"github.com/rentalx-wifi/rx-playa/streamer/cache"
	"github.com/rentalx-wifi/rx-playa/streamer/transcode"
)

// Server serves the catalog. Audio is served in three tiers: the master file
// as-is for lossless, and Opus for the two lossy tiers. Opus tiers go through
// a disk cache, so a track is encoded once and then read as a file.
type Server struct {
	Store *store.Store
	// Opus maps a bitrate to its cache directory.
	Opus map[int]*cache.Cache
	// Prefetch encodes what the listener is about to play.
	Prefetch *prefetch.Queue
	Log      *log.Logger
	// ArtDir is where extracted covers are cached. Empty disables the endpoint.
	ArtDir string
}

// Handler returns the router.
func (s *Server) Handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /healthz", s.healthz)
	mux.HandleFunc("GET /api/v1/stats", s.stats)
	mux.HandleFunc("GET /api/v1/library/manifest", s.manifest)
	mux.HandleFunc("GET /api/v1/search", s.search)
	mux.HandleFunc("POST /api/v1/match", s.match)
	mux.HandleFunc("GET /api/v1/tracks/{id}", s.track)
	mux.HandleFunc("GET /api/v1/tracks/{id}/stream", s.stream)
	mux.HandleFunc("GET /api/v1/tracks/{id}/art", s.art)
	mux.HandleFunc("POST /api/v1/tracks/{id}/play", s.play)
	mux.HandleFunc("POST /api/v1/prefetch", s.prefetch)
	return mux
}

// art serves the cover embedded in the master file, extracting it once into
// the art cache.
//
// The catalogue carries no pictures of its own: what a listener sees on the
// phone is whatever the FLAC had inside it, which is exactly the cover the rest
// of their library shows. A track with none answers 404 and the app keeps its
// placeholder — a missing cover is not an error.
func (s *Server) art(w http.ResponseWriter, r *http.Request) {
	t, err := s.Store.Track(r.Context(), r.PathValue("id"))
	if errors.Is(err, store.ErrNotFound) {
		writeErr(w, http.StatusNotFound, err)
		return
	}
	if err != nil {
		writeErr(w, http.StatusInternalServerError, err)
		return
	}
	master := ""
	for i := range t.Renditions {
		if t.Renditions[i].IsMaster && fileExists(t.Renditions[i].Path) {
			master = t.Renditions[i].Path
			break
		}
	}
	if master == "" {
		http.Error(w, "no audio", http.StatusNotFound)
		return
	}

	cached, err := s.cover(r.Context(), master)
	if err != nil {
		if errors.Is(err, errNoCover) {
			http.Error(w, "no embedded cover", http.StatusNotFound)
			return
		}
		s.logf("cover %s: %v", master, err)
		http.Error(w, "cover unavailable", http.StatusInternalServerError)
		return
	}
	// Served as a file of its own rather than through the Opus cache's helper:
	// that one answers `audio/ogg`, which an image loader has every right to
	// refuse.
	f, err := os.Open(cached)
	if err != nil {
		http.Error(w, "cover unavailable", http.StatusInternalServerError)
		return
	}
	defer f.Close()
	w.Header().Set("Cache-Control", "public, max-age=86400")
	http.ServeContent(w, r, "cover.jpg", time.Time{}, f)
}

func (s *Server) healthz(w http.ResponseWriter, r *http.Request) {
	st, err := s.Store.Stats(r.Context())
	if err != nil {
		writeErr(w, http.StatusInternalServerError, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"ok": true, "catalog": st})
}

func (s *Server) stats(w http.ResponseWriter, r *http.Request) {
	st, err := s.Store.Stats(r.Context())
	if err != nil {
		writeErr(w, http.StatusInternalServerError, err)
		return
	}
	writeJSON(w, http.StatusOK, st)
}

// manifest returns the delta the app needs to sync its catalog. The app sends
// the "now" of its previous sync and gets back only what changed since.
func (s *Server) manifest(w http.ResponseWriter, r *http.Request) {
	since, _ := strconv.ParseInt(r.URL.Query().Get("since"), 10, 64)
	limit, _ := strconv.Atoi(r.URL.Query().Get("limit"))
	// A page, not the library. Without a limit this handed the client every
	// track in one answer, and the `IN (?,?,…)` that fills each track's
	// renditions hit SQLite's variable ceiling at about 32.7k tracks — the
	// whole sync failed with a 500 for anyone with a real collection. A caller
	// that wants more walks the cursor.
	if limit <= 0 {
		limit = manifestDefaultPage
	}
	if limit > manifestMaxPage {
		limit = manifestMaxPage
	}
	m, err := s.Store.ManifestPage(r.Context(), since, limit)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, err)
		return
	}
	writeJSON(w, http.StatusOK, m)
}

// search answers plain queries and variant queries alike: "song sped up" finds
// the normal track, which carries the sped-up variant inside it.
func (s *Server) search(w http.ResponseWriter, r *http.Request) {
	q := strings.TrimSpace(r.URL.Query().Get("q"))
	if q == "" {
		http.Error(w, "q is required", http.StatusBadRequest)
		return
	}
	limit, _ := strconv.Atoi(r.URL.Query().Get("limit"))

	queries := []string{q}
	if m, ok := ingest.DetectVariantQuery(q); ok {
		queries = append(queries, m.Base)
	}

	seen := make(map[string]bool)
	out := []store.Track{}
	for _, query := range queries {
		tracks, err := s.Store.Search(r.Context(), query, limit)
		if err != nil {
			writeErr(w, http.StatusInternalServerError, err)
			return
		}
		for _, t := range tracks {
			if seen[t.ID] {
				continue
			}
			seen[t.ID] = true
			out = append(out, t)
		}
	}
	writeJSON(w, http.StatusOK, map[string]any{"query": q, "tracks": out})
}

// prefetchRequest is the listener saying what comes next in its queue.
type prefetchRequest struct {
	TrackIDs []string `json:"trackIds"`
	Tier     string   `json:"tier"`
}

// prefetch replaces the encode plan. The listener sends its next few tracks;
// sending a new list cancels whatever the old one was still encoding.
func (s *Server) prefetch(w http.ResponseWriter, r *http.Request) {
	var req prefetchRequest
	body := http.MaxBytesReader(w, r.Body, 1<<20)
	if err := json.NewDecoder(body).Decode(&req); err != nil {
		http.Error(w, "bad request body", http.StatusBadRequest)
		return
	}
	tier := req.Tier
	if tier == "" {
		tier = r.URL.Query().Get("tier")
	}
	bitrate, opus, ok := tierBitrate(tier)
	if !ok {
		http.Error(w, "unknown tier", http.StatusBadRequest)
		return
	}
	if !opus || s.Prefetch == nil {
		// Lossless is the master file itself: there is nothing to encode.
		writeJSON(w, http.StatusOK, map[string]any{"queued": 0})
		return
	}
	if len(req.TrackIDs) > 50 {
		req.TrackIDs = req.TrackIDs[:50]
	}

	jobs := make([]prefetch.Job, 0, len(req.TrackIDs))
	for _, id := range req.TrackIDs {
		t, err := s.Store.Track(r.Context(), id)
		if err != nil {
			continue
		}
		master := ""
		for _, rd := range t.Renditions {
			if rd.IsMaster {
				master = rd.Path
				break
			}
		}
		if master == "" {
			continue
		}
		jobs = append(jobs, prefetch.Job{Key: id, Master: master, Bitrate: bitrate})
	}
	s.Prefetch.Set(jobs)
	writeJSON(w, http.StatusAccepted, map[string]any{"queued": len(jobs)})
}

// play records one listen, which is what ranks a track for pinning.
func (s *Server) play(w http.ResponseWriter, r *http.Request) {
	if err := s.Store.Play(r.Context(), r.PathValue("id")); err != nil {
		if errors.Is(err, store.ErrNotFound) {
			writeErr(w, http.StatusNotFound, err)
			return
		}
		writeErr(w, http.StatusInternalServerError, err)
		return
	}
	writeJSON(w, http.StatusAccepted, map[string]bool{"ok": true})
}

// match answers which of the given recordings the catalogue already holds.
//
// The caller is a search screen on another catalogue: it has rows of its own
// and wants to mark the ones that are also here, so a single request carries
// the whole result list rather than one question per row.
func (s *Server) match(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Items []store.MatchItem `json:"items"`
	}
	body := http.MaxBytesReader(w, r.Body, 1<<20)
	if err := json.NewDecoder(body).Decode(&req); err != nil {
		http.Error(w, "bad request body", http.StatusBadRequest)
		return
	}
	if len(req.Items) > 200 {
		req.Items = req.Items[:200]
	}
	matches, err := s.Store.Match(r.Context(), req.Items)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, err)
		return
	}
	if matches == nil {
		matches = []store.MatchResult{}
	}
	writeJSON(w, http.StatusOK, map[string]any{"matches": matches})
}

func (s *Server) track(w http.ResponseWriter, r *http.Request) {
	t, err := s.Store.Track(r.Context(), r.PathValue("id"))
	if errors.Is(err, store.ErrNotFound) {
		writeErr(w, http.StatusNotFound, err)
		return
	}
	if err != nil {
		writeErr(w, http.StatusInternalServerError, err)
		return
	}
	writeJSON(w, http.StatusOK, t)
}

// tierBitrate maps the three quality tiers to an Opus bitrate. The lossless
// tier has no bitrate: it serves the master file untouched.
func tierBitrate(tier string) (bitrate int, opus, ok bool) {
	switch tier {
	case "", "default", "opus192":
		return 192000, true, true
	case "high", "opus320":
		return 320000, true, true
	case "lossless", "flac":
		return 0, false, true
	}
	return 0, false, false
}

func (s *Server) stream(w http.ResponseWriter, r *http.Request) {
	bitrate, opus, ok := tierBitrate(r.URL.Query().Get("tier"))
	if !ok {
		http.Error(w, "unknown tier", http.StatusBadRequest)
		return
	}

	t, err := s.Store.Track(r.Context(), r.PathValue("id"))
	if errors.Is(err, store.ErrNotFound) {
		writeErr(w, http.StatusNotFound, err)
		return
	}
	if err != nil {
		writeErr(w, http.StatusInternalServerError, err)
		return
	}

	var master *store.Rendition
	for i := range t.Renditions {
		if t.Renditions[i].IsMaster {
			master = &t.Renditions[i]
			break
		}
	}
	if master == nil {
		http.Error(w, "track has no audio", http.StatusNotFound)
		return
	}

	audioPath, key := master.Path, t.ID
	// A variant normally has no file: the player applies the rate itself. Only
	// an edit that could not be derived from the base is stored and served.
	if kind := r.URL.Query().Get("variant"); kind != "" {
		var found *store.Variant
		for i := range t.Variants {
			if t.Variants[i].Kind == kind && t.Variants[i].Path != "" {
				found = &t.Variants[i]
				break
			}
		}
		if found == nil {
			// No stored file: the client plays the base recording at the
			// variant's own rate. It asked for a file, so say there is none
			// rather than quietly serving the un-slowed track.
			http.Error(w, "variant has no stored file", http.StatusNotFound)
			return
		}
		audioPath, key = found.Path, t.ID+"-"+kind
	} else {
		// The catalogue's master is what the last scan measured, not what is on
		// disk now. A library that moved, a file renamed by hand, or a drive
		// that was unplugged leaves renditions behind that the scan has not
		// revisited — and the answer to that was a 500 instead of the same
		// recording at the next quality down, which the cache then failed to
		// encode too. See [store.Store.MissingRenditions] for the sweep.
		if !fileExists(master.Path) {
			if fallback := firstPresent(t.Renditions); fallback != "" {
				s.logf("master %s is gone, serving %s", master.Path, fallback)
				audioPath = fallback
				// Encoded from a different source file, so it cannot share the
				// master's cache entry: that one was made from bytes that are no
				// longer here.
				key = t.ID + "-" + filepath.Base(fallback)
			} else {
				http.Error(w, "audio file unavailable", http.StatusNotFound)
				return
			}
		}
	}

	if !opus {
		s.serveLossless(w, r, audioPath)
		return
	}
	s.serveOpus(w, r, bitrate, key, audioPath)
}

func (s *Server) serveLossless(w http.ResponseWriter, r *http.Request, path string) {
	f, err := os.Open(path)
	if err != nil {
		s.logf("open %s: %v", path, err)
		http.Error(w, "audio file unavailable", http.StatusInternalServerError)
		return
	}
	defer f.Close()
	// Ranges come free because this is the file itself.
	http.ServeContent(w, r, path, time.Time{}, f)
}

func (s *Server) serveOpus(w http.ResponseWriter, r *http.Request, bitrate int, key, path string) {
	if c := s.Opus[bitrate]; c != nil {
		dst, err := c.Ensure(r.Context(), key, path)
		if err != nil {
			if errors.Is(err, r.Context().Err()) {
				return
			}
			s.logf("encode %s: %v", key, err)
			http.Error(w, "could not prepare track", http.StatusInternalServerError)
			return
		}
		cache.ServeFile(w, r, dst)
		return
	}

	cfg := transcode.Default
	cfg.Bitrate = bitrate
	w.Header().Set("Content-Type", "audio/ogg")
	w.Header().Set("Cache-Control", "no-store")
	if err := transcode.Stream(r.Context(), path, 0, cfg, w); err != nil {
		// Headers are already out; all that is left is a log line.
		s.logf("transcode %s: %v", key, err)
	}
}

func (s *Server) logf(format string, args ...any) {
	if s.Log != nil {
		s.Log.Printf(format, args...)
	}
}

// firstPresent returns the path of the first rendition still on disk, or ""
// when every one of them is gone. Renditions arrive best-first, so this is the
// same recording at the next quality down rather than a different track.
func firstPresent(renditions []store.Rendition) string {
	for i := range renditions {
		if fileExists(renditions[i].Path) {
			return renditions[i].Path
		}
	}
	return ""
}

func fileExists(path string) bool {
	fi, err := os.Stat(path)
	return err == nil && fi.Mode().IsRegular()
}

func writeJSON(w http.ResponseWriter, code int, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(code)
	enc := json.NewEncoder(w)
	enc.SetEscapeHTML(false)
	_ = enc.Encode(v)
}

func writeErr(w http.ResponseWriter, code int, err error) {
	writeJSON(w, code, map[string]string{"error": err.Error()})
}

const (
	// A page of the library delta. The client walks the cursor until it runs out.
	manifestDefaultPage = 2000
	// Far below SQLite's ceiling on query variables, which the per-track
	// `IN (?,?,…)` would otherwise reach — and past which every sync failed.
	manifestMaxPage = 5000
)
