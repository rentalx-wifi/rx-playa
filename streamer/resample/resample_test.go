package resample

import (
	"testing"
)

// BenchmarkQuality shows what each soxr recipe costs per second of audio, since
// on the full pipeline the resampler turned out to rival the FLAC decoder.
func BenchmarkQuality(b *testing.B) {
	const (
		inRate  = 44100
		outRate = 48000
		seconds = 30
		ch      = 2
	)
	frames := inRate * seconds / 64
	buf := make([]float32, frames*ch)
	for i := range buf {
		buf[i] = float32(i%97) / 97
	}

	cases := []struct {
		name string
		q    Quality
	}{
		{"QQ", Quick},
		{"LQ", Low},
		{"MQ", Medium},
		{"HQ", High},
		{"VHQ", VeryHigh},
	}
	for _, tc := range cases {
		b.Run(tc.name, func(b *testing.B) {
			s, err := NewQuality(inRate, outRate, ch, tc.q)
			if err != nil {
				b.Fatal(err)
			}
			defer s.Close()
			b.ResetTimer()
			var sink int
			for i := 0; i < b.N; i++ {
				out, err := s.Process(buf)
				if err != nil {
					b.Fatal(err)
				}
				sink += len(out)
			}
			b.StopTimer()
			_ = sink
			// Each call converts 1/64 of the 30 s of audio used to build buf.
			audioPerCall := float64(seconds) / 64
			if b.N > 0 {
				b.ReportMetric(audioPerCall/(b.Elapsed().Seconds()/float64(b.N)), "x-realtime")
			}
		})
	}
}
