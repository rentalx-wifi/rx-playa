// Command rxresample resamples raw interleaved float32 audio between rates
// with a chosen soxr recipe, so the recipe can be judged on its own rather
// than through an Opus encode that hides most of the difference.
package main

import (
	"encoding/binary"
	"flag"
	"fmt"
	"io"
	"math"
	"os"
	"strconv"

	"github.com/rentalx-wifi/rx-playa/streamer/resample"
)

func main() {
	inRate := flag.Float64("in", 44100, "input sample rate")
	outRate := flag.Float64("out", 48000, "output sample rate")
	ch := flag.Int("ch", 2, "channels")
	q := flag.Int("q", 4, "soxr recipe: 0 QQ, 1 LQ, 2 MQ, 4 HQ, 6 VHQ")
	flag.Parse()

	rs, err := resample.NewQuality(*inRate, *outRate, *ch, resample.Quality(*q))
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
	defer rs.Close()

	raw, err := io.ReadAll(os.Stdin)
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
	frames := len(raw) / 4
	pcm := make([]float32, frames)
	for i := 0; i < frames; i++ {
		pcm[i] = math.Float32frombits(binary.LittleEndian.Uint32(raw[i*4:]))
	}

	out, err := rs.Process(pcm)
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
	tail, err := rs.Flush()
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
	out = append(out, tail...)

	buf := make([]byte, 4)
	w := os.Stdout
	for _, v := range out {
		binary.LittleEndian.PutUint32(buf, math.Float32bits(v))
		if _, err := w.Write(buf); err != nil {
			fmt.Fprintln(os.Stderr, err)
			os.Exit(1)
		}
	}
	_ = strconv.Itoa
}
