package transcode

import (
	"testing"

	"github.com/rentalx-wifi/rx-playa/streamer/opusenc"
)

// BenchmarkEncodeOnly isolates libopus, which the full-pipeline number cannot:
// there, decoding and resampling move with it and hide the slope. The signal
// here is deliberately dull, so read the ratios between complexities rather
// than the absolute rate — real music makes Opus work harder than a ramp does.
func BenchmarkEncodeOnly(b *testing.B) {
	for _, c := range []int{1, 3, 5, 7, 10} {
		b.Run(complexityName(c), func(b *testing.B) {
			const frames = 48000 * 30
			pcm := make([]float32, frames*2)
			for i := range pcm {
				pcm[i] = float32(i%1000)/500 - 1 // a ramp, cheap to encode
			}
			enc, err := opusenc.New(opusRate, 2, opusenc.Audio)
			if err != nil {
				b.Fatal(err)
			}
			if err := enc.SetBitrate(Default.Bitrate); err != nil {
				b.Fatal(err)
			}
			if err := enc.SetComplexity(c); err != nil {
				b.Fatal(err)
			}
			const frame = 960
			buf := make([]byte, 8000)
			out := make([]byte, 0, frames)

			b.ResetTimer()
			for i := 0; i < b.N; i++ {
				out = out[:0]
				for off := 0; off+frame*2 <= len(pcm); off += frame * 2 {
					n, err := enc.EncodeFloat32(pcm[off:off+frame*2], buf)
					if err != nil {
						b.Fatal(err)
					}
					out = append(out, buf[:n]...)
				}
			}
			b.StopTimer()
			if b.N > 0 {
				b.ReportMetric(30.0/(b.Elapsed().Seconds()/float64(b.N)), "x-realtime")
			}
		})
	}
}
