package transcode

import (
	"context"
	"io"
	"testing"

	"github.com/rentalx-wifi/rx-playa/streamer/resample"
)

// BenchmarkResampleVariants compares soxr recipes and quality flags through the
// whole pipeline, which is the only place the difference is worth knowing.
func BenchmarkResampleVariants(b *testing.B) {
	path := benchFile(b, 44100, 30)
	cases := []struct {
		name  string
		q     resample.Quality
		flags uint32
	}{
		{"QQ", resample.Quick, 0},
		{"LQ", resample.Low, 0},
		{"MQ", resample.Medium, 0},
		{"r16", resample.Quality(3), 0},
		{"HQ", resample.High, 0},
		{"VHQ", resample.VeryHigh, 0},
	}
	for _, tc := range cases {
		b.Run(tc.name, func(b *testing.B) {
			cfg := Default
			cfg.ResampleQuality = tc.q
			b.ResetTimer()
			for i := 0; i < b.N; i++ {
				if err := Stream(context.Background(), path, 0, cfg, io.Discard); err != nil {
					b.Fatal(err)
				}
			}
			b.StopTimer()
			if b.N > 0 {
				b.ReportMetric(30.0/(b.Elapsed().Seconds()/float64(b.N)), "x-realtime")
			}
		})
	}
}
