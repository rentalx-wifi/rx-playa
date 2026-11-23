// Command flashmusic is the FlashMusic server: it scans a library of audio
// files into a catalog and serves it to the RX Playa app.
package main

import (
	"context"
	"errors"
	"flag"
	"log"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"strconv"
	"strings"
	"syscall"
	"time"

	"github.com/rentalx-wifi/rx-playa/flashmusic/api"
	"github.com/rentalx-wifi/rx-playa/flashmusic/ingest"
	"github.com/rentalx-wifi/rx-playa/flashmusic/prefetch"
	"github.com/rentalx-wifi/rx-playa/flashmusic/store"
	"github.com/rentalx-wifi/rx-playa/streamer/cache"
	"github.com/rentalx-wifi/rx-playa/streamer/transcode"
)

func main() {
	addr := flag.String("addr", ":8099", "listen address")
	dbPath := flag.String("db", "flashmusic.db", "catalog database file")
	library := flag.String("library", "", "library directory to scan")
	source := flag.String("source", "upload", "source label recorded on renditions")
	scanOnly := flag.Bool("scan", false, "scan the library, report, and exit")
	workers := flag.Int("workers", 0, "scan workers (default: one per CPU, at most 8)")
	opusDir := flag.String("opus", "opus", "Opus cache directory")
	encoders := flag.Int("encoders", 2, "parallel Opus encodes")
	opusMax := flag.Int64("opus-max", 20<<30, "Opus cache size limit in bytes")
	evictEvery := flag.Duration("evict", 10*time.Minute, "how often to trim the Opus cache")
	flag.Parse()

	logger := log.New(os.Stderr, "flashmusic: ", log.LstdFlags)

	st, err := store.Open(*dbPath)
	if err != nil {
		logger.Fatal(err)
	}
	defer st.Close()

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	if *library != "" {
		if err := scan(ctx, st, *library, *source, *workers, logger); err != nil {
			logger.Printf("scan: %v", err)
			if *scanOnly {
				os.Exit(1)
			}
		}
	}
	if *scanOnly {
		return
	}

	caches := opusCaches(*opusDir, *encoders, logger)
	var queue *prefetch.Queue
	if len(caches) > 0 {
		encodersByTier := make(map[int]prefetch.Encoder, len(caches))
		for bitrate, c := range caches {
			encodersByTier[bitrate] = c
		}
		queue = prefetch.New(encodersByTier, logger)
	}
	go evictLoop(ctx, st, caches, *opusMax, *evictEvery, logger)

	artDir := filepath.Join(filepath.Dir(*dbPath), "art")
	srv := &api.Server{Store: st, Opus: caches, Prefetch: queue, Log: logger, ArtDir: artDir}
	httpSrv := &http.Server{
		Addr:              *addr,
		Handler:           srv.Handler(),
		ReadHeaderTimeout: 10 * time.Second,
		// A request body is two JSON fields, so a slow one is a client that
		// never meant to finish. Headers alone were covered before this, which
		// left the body free to hold a connection open one byte at a time.
		ReadTimeout: 30 * time.Second,
		IdleTimeout: 120 * time.Second,
		// Streaming a long track must not be cut off by a write deadline.
		WriteTimeout: 0,
	}

	go func() {
		logger.Printf("слушаю %s", *addr)
		if err := httpSrv.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			logger.Fatal(err)
		}
	}()

	<-ctx.Done()
	shutdown, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	_ = httpSrv.Shutdown(shutdown)
}

// opusCaches builds one disk cache per lossy tier. The directory is derived
// data, so an empty or missing one just costs a re-encode.
func opusCaches(root string, encoders int, logger *log.Logger) map[int]*cache.Cache {
	if root == "" {
		return nil
	}
	out := make(map[int]*cache.Cache, 2)
	for _, bitrate := range []int{192000, 320000} {
		cfg := transcode.Default
		cfg.Bitrate = bitrate
		c := cache.New(filepath.Join(root, strconv.Itoa(bitrate/1000)), cfg, encoders)
		c.Log = logger
		out[bitrate] = c
	}
	return out
}

// evictLoop keeps the Opus cache under its size limit. The most played tracks
// are pinned: a track that made the top hundred is never evicted, because that
// is exactly the track everyone is about to ask for.
func evictLoop(ctx context.Context, st *store.Store, caches map[int]*cache.Cache,
	limit int64, every time.Duration, logger *log.Logger) {
	if len(caches) == 0 || limit <= 0 {
		return
	}
	t := time.NewTicker(every)
	defer t.Stop()
	// The flag is the size of the Opus cache, and there is one directory per
	// tier under it. Handing each the whole budget made the cache twice what
	// the flag promised, which is a disk filling up months early.
	share := limit / int64(len(caches))
	if share <= 0 {
		share = limit
	}
	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
			pinned := pinnedKeys(ctx, st)
			for bitrate, c := range caches {
				n, err := c.Evict(share, pinned)
				if err != nil {
					logger.Printf("кеш %d: %v", bitrate, err)
					continue
				}
				if n > 0 {
					logger.Printf("кеш %d: вытеснено файлов %d", bitrate, n)
				}
			}
		}
	}
}

// pinnedKeys returns a predicate for the hundred most played tracks. Cache
// keys are the track id, or the track id plus a variant suffix.
func pinnedKeys(ctx context.Context, st *store.Store) func(string) bool {
	top, err := st.TopPlayed(ctx, 100)
	if err != nil || len(top) == 0 {
		return nil
	}
	set := make(map[string]bool, len(top))
	for _, p := range top {
		set[p.TrackID] = true
	}
	return func(key string) bool {
		if set[key] {
			return true
		}
		if i := strings.IndexByte(key, '-'); i > 0 {
			return set[key[:i]]
		}
		return false
	}
}

func scan(ctx context.Context, st *store.Store, library, source string, workers int, logger *log.Logger) error {
	start := time.Now()
	res, err := ingest.Run(ctx, st, ingest.Options{
		Library: library,
		Source:  source,
		Workers: workers,
		Log:     logger.Printf,
	})
	if err != nil {
		return err
	}
	logger.Printf("файлов %d, просканировано %d, из кеша %d, новых треков %d, обновлено %d, рендиций +%d, за %s",
		res.Files, res.Scanned, res.Cached, res.NewTracks, res.Updated, res.Renditions,
		time.Since(start).Round(time.Millisecond))
	if res.VariantsLinked > 0 || res.VariantsKept > 0 {
		logger.Printf("вариантов связано %d, оставлено файлами %d", res.VariantsLinked, res.VariantsKept)
	}
	for _, path := range res.Redundant {
		logger.Printf("лишний файл, можно удалить: %s", path)
	}
	for _, e := range res.Errors {
		logger.Printf("ошибка: %s", e)
	}
	return nil
}
