package transcode

import (
	"context"
	"io"
	"testing"
)

// BenchmarkPipelineRate shows what the resampler costs by comparing a 44.1 kHz
// source, which must be converted to Opus's 48 kHz, against a 48 kHz one that
// needs no conversion at all.
func BenchmarkPipelineRate(b *testing.B) {
	for _, rate := range []int{44100, 48000} {
		name := "44k"
		if rate == 48000 {
			name = "48k"
		}
		b.Run(name, func(b *testing.B) {
			path := benchFile(b, rate, 30)
			b.ResetTimer()
			for i := 0; i < b.N; i++ {
				if err := Stream(context.Background(), path, 0, Default, io.Discard); err != nil {
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
