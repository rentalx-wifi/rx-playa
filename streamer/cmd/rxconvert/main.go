// Command rxconvert turns a FLAC library into 48 kHz FLAC.
//
// Opus only speaks 48 kHz, so a 44.1 kHz master pays for a resample on every
// single listen. Paying it once, at ingest, and with the best filter available,
// removes that from the streaming path entirely -- measured at 87x real time
// before and 127x after on the transcode benchmark.
//
// The conversion goes through ffmpeg's soxr resampler at maximum precision
// rather than through this project's own binding, because a FLAC *encoder* is
// one thing the streamer never needed. ffmpeg already links libsoxr, so the
// filter is the same one, just driven from the other side.
package main

import (
	"flag"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/rentalx-wifi/rx-playa/streamer/flacdec"
)

func main() {
	inDir := flag.String("in", "", "source directory of FLAC files")
	outDir := flag.String("out", "", "destination directory for 48 kHz FLAC")
	jobs := flag.Int("jobs", runtime.NumCPU(), "files converted in parallel")
	precision := flag.Int("precision", 28, "soxr precision, 20 standard, 28 highest")
	dryRun := flag.Bool("n", false, "list what would be converted")
	flag.Parse()

	if *inDir == "" || *outDir == "" {
		fmt.Fprintln(os.Stderr, "rxconvert: -in and -out are required")
		os.Exit(2)
	}
	ffmpeg, err := exec.LookPath("ffmpeg")
	if err != nil {
		fmt.Fprintln(os.Stderr, "rxconvert: ffmpeg not found")
		os.Exit(2)
	}

	var files []string
	err = filepath.WalkDir(*inDir, func(p string, d os.DirEntry, err error) error {
		if err != nil || d.IsDir() {
			return nil
		}
		if strings.EqualFold(filepath.Ext(p), ".flac") {
			files = append(files, p)
		}
		return nil
	})
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
	fmt.Printf("rxconvert: %d files, %d jobs, soxr precision %d\n", len(files), *jobs, *precision)

	var done, skipped, failed atomic.Int64
	start := time.Now()
	work := make(chan string)
	var wg sync.WaitGroup
	for i := 0; i < *jobs; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for src := range work {
				rel, err := filepath.Rel(*inDir, src)
				if err != nil {
					failed.Add(1)
					continue
				}
				dst := filepath.Join(*outDir, rel)
				if *dryRun {
					fmt.Println(src, "->", dst)
					continue
				}
				if err := os.MkdirAll(filepath.Dir(dst), 0o755); err != nil {
					failed.Add(1)
					continue
				}
				rate, err := probeRate(src)
				if err != nil {
					failed.Add(1)
					continue
				}
				if rate == 48000 {
					// Already 48 kHz: copy the bytes, do not re-encode them.
					if err := copyFile(src, dst); err != nil {
						failed.Add(1)
					} else {
						skipped.Add(1)
					}
					continue
				}
				if err := convert(ffmpeg, src, dst, *precision); err != nil {
					fmt.Fprintf(os.Stderr, "rxconvert: %s: %v\n", src, err)
					failed.Add(1)
					continue
				}
				done.Add(1)
			}
		}()
	}
	for _, f := range files {
		work <- f
	}
	close(work)
	wg.Wait()

	el := time.Since(start)
	fmt.Printf("converted %d, already 48k %d, failed %d in %.1fs (%.1f files/s)\n",
		done.Load(), skipped.Load(), failed.Load(), el.Seconds(), float64(len(files))/el.Seconds())
	if failed.Load() > 0 {
		os.Exit(1)
	}
}

func probeRate(path string) (int, error) {
	d, err := flacdec.Open(path)
	if err != nil {
		return 0, err
	}
	defer d.Close()
	return d.Info().SampleRate, nil
}

func convert(ffmpeg, src, dst string, precision int) error {
	args := []string{
		"-hide_banner", "-loglevel", "error", "-y",
		"-i", src,
		// soxr at the requested precision; cheby=1 gives the steeper filter.
		// osr is the output rate and is the part that actually does the work:
		// without it aresample keeps the input rate and only reformats.
		"-af", fmt.Sprintf("aresample=resampler=soxr:osr=48000:precision=%d:cheby=1", precision),
		"-c:a", "flac", "-compression_level", "8",
		"-map_metadata", "0",
		dst,
	}
	out, err := exec.Command(ffmpeg, args...).CombinedOutput()
	if err != nil {
		return fmt.Errorf("%v: %s", err, strings.TrimSpace(string(out)))
	}
	return nil
}

func copyFile(src, dst string) error {
	in, err := os.Open(src)
	if err != nil {
		return err
	}
	defer in.Close()
	out, err := os.Create(dst)
	if err != nil {
		return err
	}
	if _, err := out.ReadFrom(in); err != nil {
		out.Close()
		return err
	}
	return out.Close()
}
