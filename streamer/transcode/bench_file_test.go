package transcode

import (
	"io"
	"os"
	"testing"

	"github.com/rentalx-wifi/rx-playa/streamer/flacdec"
)

// BenchmarkDecodeFile measures decoding of whatever BENCH_FLAC points at, so a
// file with an embedded cover can be compared against a bare one. The fixtures
// benchmarkFile builds carry no artwork, and a real library does.
func BenchmarkDecodeFile(b *testing.B) {
	path := os.Getenv("BENCH_FLAC")
	if path == "" {
		b.Skip("BENCH_FLAC not set")
	}
	fi, err := os.Stat(path)
	if err != nil {
		b.Fatal(err)
	}
	b.SetBytes(fi.Size())
	b.ResetTimer()
	var samples int
	for i := 0; i < b.N; i++ {
		d, err := flacdec.Open(path)
		if err != nil {
			b.Fatal(err)
		}
		for {
			_, _, bs, err := d.Next()
			if err == io.EOF {
				break
			}
			if err != nil {
				b.Fatal(err)
			}
			samples += bs
		}
		d.Close()
	}
	b.StopTimer()
	_ = samples
}
