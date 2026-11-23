// Command rxstream serves a FLAC library as live Opus.
//
// It encodes on the request path instead of from a pre-built copy: a stream is
// one goroutine holding a decoder, a resampler and an encoder, roughly a
// megabyte, rather than a child process. That is what lets a single box hold
// far more listeners than a process-per-stream design would.
//
//	STREAM_LIBRARY=/srv/music/masters rxstream
package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
	"sync/atomic"
	"time"

	"github.com/rentalx-wifi/rx-playa/streamer/cache"
	"github.com/rentalx-wifi/rx-playa/streamer/resample"
	"github.com/rentalx-wifi/rx-playa/streamer/transcode"
)

func main() {
	addr := flag.String("addr", envOr("STREAM_ADDR", ":8080"), "listen address")
	lib := flag.String("library", envOr("STREAM_LIBRARY", ""), "directory of FLAC files")
	bitrate := flag.Int("bitrate", envInt("STREAM_BITRATE", 256000), "Opus bitrate, bits per second")
	complexity := flag.Int("complexity", envInt("STREAM_COMPLEXITY", transcode.Default.Complexity), "libopus complexity, 0-10")
	frameMs := flag.Int("frame-ms", envInt("STREAM_FRAMEMS", 20), "Opus packet length in ms: 2, 5, 10, 20, 40 or 60")
	resampleQ := flag.Int("resample", envInt("STREAM_RESAMPLE", int(transcode.Default.ResampleQuality)), "soxr recipe: 0 QQ, 1 LQ, 2 MQ, 4 HQ, 6 VHQ")
	opusDir := flag.String("opus", envOr("STREAM_OPUS", ""), "where encoded Opus is cached; empty transcodes on every request")
	opusMax := flag.Int64("opus-max", envInt64("STREAM_OPUS_MAX", 20<<30), "Opus cache size limit in bytes, 0 to keep everything")
	evictEvery := flag.Duration("evict", 10*time.Minute, "how often to trim the Opus cache")
	encoders := flag.Int("encoders", envInt("STREAM_ENCODERS", 0), "parallel cache encodes, 0 for 2 per core")
	maxStreams := flag.Int("max-streams", envInt("STREAM_MAX_CONCURRENT", 0), "concurrent encodes, 0 for 2 per core")
	token := flag.String("token", envOr("STREAM_TOKEN", ""), "bearer token required for every request, empty for none")
	acquire := flag.Duration("acquire", 2*time.Second, "how long a request waits for a free slot before 503")
	flag.Parse()

	if *lib == "" {
		fmt.Fprintln(os.Stderr, "rxstream: -library or STREAM_LIBRARY is required")
		os.Exit(2)
	}
	root, err := filepath.Abs(*lib)
	if err != nil {
		log.Fatalf("rxstream: %v", err)
	}
	if st, err := os.Stat(root); err != nil || !st.IsDir() {
		log.Fatalf("rxstream: %q is not a directory", root)
	}
	if *maxStreams <= 0 {
		*maxStreams = 2 * runtime.NumCPU()
	}

	s := &server{
		root:    root,
		cfg:     transcode.Config{Bitrate: *bitrate, Complexity: *complexity, FrameMs: *frameMs, ResampleQuality: resample.Quality(*resampleQ)},
		token:   *token,
		acquire: *acquire,
	}
	s.slots = make(chan struct{}, *maxStreams)

	if *opusDir != "" {
		if err := os.MkdirAll(*opusDir, 0o755); err != nil {
			log.Fatalf("rxstream: opus cache: %v", err)
		}
		if *encoders <= 0 {
			*encoders = 2 * runtime.NumCPU()
		}
		s.opus = cache.New(*opusDir, s.cfg, *encoders)
		log.Printf("rxstream: opus cache at %s, up to %d parallel encodes", *opusDir, *encoders)
		// The cache is derived data but it is not free: without this it grew
		// until the disk was full, and then both encoding and serving failed.
		go trimOpus(s.opus, *opusMax, *evictEvery)
	}

	mux := http.NewServeMux()
	mux.HandleFunc("GET /healthz", func(w http.ResponseWriter, r *http.Request) {
		io.WriteString(w, "ok\n")
	})
	mux.HandleFunc("GET /api/tracks", s.list)
	mux.HandleFunc("GET /api/tracks/{path...}", s.info)
	mux.HandleFunc("GET /stream/{path...}", s.stream)
	mux.HandleFunc("GET /metrics", s.metrics)

	srv := &http.Server{
		Addr:              *addr,
		Handler:           s.auth(mux),
		ReadHeaderTimeout: 10 * time.Second,
		// No WriteTimeout: a live stream is meant to stay open for an hour.
		IdleTimeout: 120 * time.Second,
	}
	log.Printf("rxstream: %s from %s, %d kbit/s, complexity %d, %d ms frames, soxr %d, up to %d concurrent streams",
		*addr, root, *bitrate/1000, *complexity, *frameMs, *resampleQ, *maxStreams)
	log.Fatal(srv.ListenAndServe())
}

// trimOpus keeps the Opus cache under limit, once at startup and then on a
// timer. The sweep at boot is what clears the partial encodes a killed process
// left behind; the timer is what keeps the directory from filling the disk
// between restarts, which is what used to happen.
func trimOpus(c *cache.Cache, limit int64, every time.Duration) {
	// A limit of zero means "keep everything" to the flag, but it means "evict
	// everything" to Evict — so it becomes a size nothing reaches.
	size := limit
	if size <= 0 {
		size = int64(1) << 62
	}
	sweep := func() {
		n, err := c.Evict(size, nil)
		if err != nil {
			log.Printf("rxstream: opus cache: %v", err)
		} else if n > 0 {
			log.Printf("rxstream: evicted %d cached tracks", n)
		}
	}
	sweep()
	if every <= 0 {
		return
	}
	t := time.NewTicker(every)
	defer t.Stop()
	for range t.C {
		sweep()
	}
}

type server struct {
	root    string
	cfg     transcode.Config
	token   string
	acquire time.Duration
	slots   chan struct{}
	opus    *cache.Cache

	active   atomic.Int64
	total    atomic.Int64
	rejected atomic.Int64
}

func (s *server) auth(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if s.token != "" && r.URL.Path != "/healthz" {
			got := strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer ")
			if got != s.token {
				http.Error(w, "unauthorized", http.StatusUnauthorized)
				return
			}
		}
		next.ServeHTTP(w, r)
	})
}

// resolve turns a URL path into a file inside the library, refusing anything
// that would escape it.
func (s *server) resolve(p string) (string, error) {
	full := filepath.Join(s.root, filepath.FromSlash(p))
	full = filepath.Clean(full)
	if full != s.root && !strings.HasPrefix(full, s.root+string(filepath.Separator)) {
		return "", errors.New("outside the library")
	}
	return full, nil
}

func (s *server) list(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/x-ndjson")
	var n int
	err := filepath.WalkDir(s.root, func(p string, d os.DirEntry, err error) error {
		if err != nil {
			return nil // an unreadable subtree is not worth failing the listing
		}
		if d.IsDir() || !strings.EqualFold(filepath.Ext(p), ".flac") {
			return nil
		}
		rel, err := filepath.Rel(s.root, p)
		if err != nil {
			return nil
		}
		if st, err := d.Info(); err == nil {
			fmt.Fprintf(w, "{\"path\":%q,\"bytes\":%d}\n", filepath.ToSlash(rel), st.Size())
			n++
		}
		return nil
	})
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	log.Printf("list: %d tracks in %s", n, time.Now().Format(time.RFC3339))
}

func (s *server) info(w http.ResponseWriter, r *http.Request) {
	full, err := s.resolve(r.PathValue("path"))
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}
	if st, err := os.Stat(full); err != nil || st.IsDir() {
		http.Error(w, "not found", http.StatusNotFound)
		return
	}
	in, err := transcode.Probe(full)
	if err != nil {
		http.Error(w, "not a FLAC stream", http.StatusUnsupportedMediaType)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	fmt.Fprintf(w, `{"sampleRate":%d,"channels":%d,"bits":%d,"duration":%.3f}`+"\n",
		in.SampleRate, in.Channels, in.BitsPerSample, in.Duration)
}

func (s *server) stream(w http.ResponseWriter, r *http.Request) {
	rel := r.PathValue("path")
	full, err := s.resolve(rel)
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}
	if st, err := os.Stat(full); err != nil || st.IsDir() {
		http.Error(w, "not found", http.StatusNotFound)
		return
	}

	// With a cache configured a track is encoded once and served as a file
	// from then on: Range works, seeking is free, and the encoder is touched
	// only for a track nobody has played yet.
	if s.opus != nil {
		dst, err := s.opus.Ensure(r.Context(), rel, full)
		if err != nil {
			if errors.Is(err, context.Canceled) {
				return
			}
			log.Printf("cache %s: %v", rel, err)
			http.Error(w, "could not prepare track", http.StatusInternalServerError)
			return
		}
		s.total.Add(1)
		cache.ServeFile(w, r, dst)
		return
	}

	in, err := transcode.Probe(full)
	if err != nil {
		http.Error(w, "not a FLAC stream", http.StatusUnsupportedMediaType)
		return
	}

	var start uint64
	if v := r.URL.Query().Get("t"); v != "" {
		sec, err := strconv.ParseFloat(v, 64)
		if err != nil || sec < 0 {
			http.Error(w, "bad t", http.StatusBadRequest)
			return
		}
		start = uint64(sec * float64(in.SampleRate))
	}

	// Take a slot or give up quickly: a hundred queued listeners waiting on
	// encoders is worse for everyone than a prompt 503 and a retry.
	timer := time.NewTimer(s.acquire)
	defer timer.Stop()
	select {
	case s.slots <- struct{}{}:
		defer func() { <-s.slots }()
	case <-timer.C:
		s.rejected.Add(1)
		w.Header().Set("Retry-After", "2")
		http.Error(w, "all encoders busy", http.StatusServiceUnavailable)
		return
	case <-r.Context().Done():
		return
	}

	s.active.Add(1)
	s.total.Add(1)
	defer s.active.Add(-1)

	h := w.Header()
	h.Set("Content-Type", "audio/ogg")
	h.Set("Cache-Control", "no-store")
	h.Set("X-Accel-Buffering", "no") // nginx must not sit on a live stream
	w.WriteHeader(http.StatusOK)

	fl, _ := w.(http.Flusher)
	fw := &flushWriter{w: w, f: fl}
	err = transcode.Stream(r.Context(), full, start, s.cfg, fw)
	fw.Close()
	if err != nil && !errors.Is(err, context.Canceled) {
		log.Printf("stream %s at %.2fs: %v", r.PathValue("path"), float64(start)/float64(in.SampleRate), err)
	}
}

func (s *server) metrics(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "text/plain; version=0.0.4")
	fmt.Fprintf(w, "rxstream_active_streams %d\n", s.active.Load())
	fmt.Fprintf(w, "rxstream_total_streams %d\n", s.total.Load())
	fmt.Fprintf(w, "rxstream_rejected_streams %d\n", s.rejected.Load())
	fmt.Fprintf(w, "rxstream_slot_capacity %d\n", cap(s.slots))
}

// flushWriter hands whole Ogg pages to the client, but only actually flushes
// the response when enough has accumulated. A page is 20 ms of audio and the
// client buffers seconds, so flushing on every page is thousands of syscalls a
// second for nothing.
type flushWriter struct {
	w io.Writer
	f http.Flusher
	n int
}

func (fw *flushWriter) Write(p []byte) (int, error) {
	n, err := fw.w.Write(p)
	fw.n += n
	if err == nil && fw.n >= 8192 && fw.f != nil {
		fw.f.Flush()
		fw.n = 0
	}
	return n, err
}

// Close flushes whatever is left.
func (fw *flushWriter) Close() error {
	if fw.f != nil && fw.n > 0 {
		fw.f.Flush()
		fw.n = 0
	}
	return nil
}

func envOr(k, def string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return def
}

func envInt(k string, def int) int {
	if v := os.Getenv(k); v != "" {
		if n, err := strconv.Atoi(v); err == nil {
			return n
		}
	}
	return def
}

func envInt64(k string, def int64) int64 {
	if v := os.Getenv(k); v != "" {
		if n, err := strconv.ParseInt(v, 10, 64); err == nil {
			return n
		}
	}
	return def
}
