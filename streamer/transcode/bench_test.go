package transcode

import (
	"context"
	"io"
	"os/exec"
	"path/filepath"
	"testing"
	"time"

	"github.com/rentalx-wifi/rx-playa/streamer/flacdec"
)

// benchFile builds a long FLAC once per run and hands back its path.
func benchFile(b *testing.B, rate int, seconds int) string {
	b.Helper()
	dir := b.TempDir()
	in := filepath.Join(dir, "bench.flac")
	ffmpeg, err := exec.LookPath("ffmpeg")
	if err != nil {
		b.Skip("ffmpeg not installed")
	}
	rateS := itoa(rate)
	durS := itoa(seconds)
	args := []string{
		"-hide_banner", "-loglevel", "error", "-y",
		"-f", "lavfi", "-i", "sine=frequency=440:sample_rate=" + rateS + ":duration=" + durS,
		"-f", "lavfi", "-i", "anoisesrc=d=" + durS + ":c=pink:r=" + rateS + ":a=0.08",
		"-filter_complex", "[0:a][1:a]amix=inputs=2:duration=shortest,volume=0.6",
		"-c:a", "flac", "-sample_fmt", "s32", "-bits_per_raw_sample", "24",
		"-ar", rateS, "-ac", "2", in,
	}
	if out, err := exec.Command(ffmpeg, args...).CombinedOutput(); err != nil {
		b.Fatalf("ffmpeg: %v: %s", err, out)
	}
	return in
}

func BenchmarkPipeline44k(b *testing.B) {
	path := benchFile(b, 44100, 60)

	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		if err := Stream(context.Background(), path, 0, Default, io.Discard); err != nil {
			b.Fatal(err)
		}
	}
	b.StopTimer()
	per := time.Duration(0)
	if b.N > 0 {
		per = time.Duration(b.Elapsed().Nanoseconds() / int64(b.N))
	}
	if per > 0 {
		b.ReportMetric(60.0/per.Seconds(), "x-realtime")
		b.ReportMetric(per.Seconds()*1000, "ms/track")
	}
}

func BenchmarkDecodeOnly(b *testing.B) {
	path := benchFile(b, 44100, 60)

	b.ResetTimer()
	var sinks int
	for i := 0; i < b.N; i++ {
		d, err := flacdec.Open(path)
		if err != nil {
			b.Fatal(err)
		}
		for {
			flat, _, bs, err := d.Next()
			if err == io.EOF {
				break
			}
			if err != nil {
				b.Fatal(err)
			}
			sinks += bs
			_ = flat
		}
		d.Close()
	}
	b.StopTimer()
	_ = sinks
	per := time.Duration(b.Elapsed().Nanoseconds() / int64(b.N))
	if per > 0 {
		b.ReportMetric(60.0/per.Seconds(), "x-realtime")
	}
}
