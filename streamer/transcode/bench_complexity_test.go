package transcode

import (
	"context"
	"io"
	"testing"
)

// BenchmarkComplexity is the number the hardware question actually needs: how
// many streams one core carries at each libopus effort setting.
//
// Run it pinned to one core to read per-core figures:
//
//	GOMAXPROCS=1 go test ./transcode -run XXX -bench Complexity -benchtime 2x
func BenchmarkComplexity(b *testing.B) {
	path := benchFile(b, 44100, 30)
	const audioSeconds = 30.0

	for _, c := range []int{1, 3, 5, 7, 10} {
		b.Run(complexityName(c), func(b *testing.B) {
			cfg := Default
			cfg.Complexity = c
			b.ResetTimer()
			for i := 0; i < b.N; i++ {
				if err := Stream(context.Background(), path, 0, cfg, io.Discard); err != nil {
					b.Fatal(err)
				}
			}
			b.StopTimer()
			if b.N > 0 {
				per := b.Elapsed().Seconds() / float64(b.N)
				b.ReportMetric(audioSeconds/per, "x-realtime")
				b.ReportMetric(per*1000, "ms/track")
			}
		})
	}
}

func complexityName(c int) string {
	switch c {
	case 1:
		return "c1"
	case 3:
		return "c3"
	case 5:
		return "c5"
	case 7:
		return "c7"
	case 10:
		return "c10"
	}
	return "c?"
}
