// Package transcode turns a FLAC file into an Opus-in-Ogg stream as it goes.
//
// The point is that no process is spawned and no file is written: decoding,
// resampling and encoding run inside the server, so a stream costs a little
// state rather than an ffmpeg child. That is what makes the per-connection
// memory arithmetic work out at a thousand listeners.
package transcode

import (
	"context"
	"fmt"
	"io"
	"math"

	"github.com/rentalx-wifi/rx-playa/streamer/flacdec"
	"github.com/rentalx-wifi/rx-playa/streamer/ogg"
	"github.com/rentalx-wifi/rx-playa/streamer/opusenc"
	"github.com/rentalx-wifi/rx-playa/streamer/resample"
)

// Opus always runs at 48 kHz, whatever the file was.
const opusRate = 48000

// Config is the encoder setting a stream runs with.
type Config struct {
	// Bitrate in bits per second, e.g. 256000.
	Bitrate int
	// Complexity is libopus's 0..10 effort knob. Live streams trade it for
	// cores: 10 buys a fraction of a percent over 5 at this bitrate for
	// roughly three times the CPU.
	Complexity int
	// FrameMs must be one of 2, 5, 10, 20, 40, 60. 20 is the default Opus
	// packet length and what every decoder handles well.
	FrameMs int
	// ResampleQuality selects the soxr filter recipe.
	//
	// Measured on flat white noise, which shows a rolloff as a level drop: the
	// 20-bit recipe is flat to 20 kHz, the low recipe is 1 dB down at 15-18 kHz,
	// 3.5 dB at 18-20 and 9 dB at 20-22. Opus at 256 kbit/s attenuates the same
	// 18-22 band by 6.5 dB on its own, and music carries almost nothing up
	// there, so the low recipe buys about 17% more streams per core for a loss
	// confined to the top octave. Cubic interpolation is the faster recipe still
	// and is not worth it: it does not merely roll off, it adds imaging.
	ResampleQuality resample.Quality
}

// Default is the setting this server runs: 256 kbit/s VBR, music mode, and
// complexity 4.
//
// Complexity was measured under a thousand concurrent streams: 1 gives 61.4
// streams per core, 2 gives 59.4, 3 gives 56.9, 4 gives 56.6 and 5 gives 49.5.
// The curve has a knee at 4 -- 3 and 4 are the same speed, so there is no
// reason to buy the extra effort with quality. Going below 4 buys 2% per step
// and starts to ask for a listening test that has not been done.
var Default = Config{Bitrate: 256000, Complexity: 4, FrameMs: 20, ResampleQuality: resample.High}

func (c Config) frameSize() (int, error) {
	switch c.FrameMs {
	case 2, 5, 10, 20, 40, 60:
	default:
		return 0, fmt.Errorf("transcode: frame length %d ms is not an Opus duration", c.FrameMs)
	}
	return opusRate * c.FrameMs / 1000, nil
}

// Info is what a caller needs to make decisions before streaming.
type Info struct {
	SampleRate    int
	Channels      int
	BitsPerSample int
	Duration      float64 // seconds, 0 when the file does not declare it
}

// Probe reads the FLAC header without decoding audio.
func Probe(path string) (Info, error) {
	d, err := flacdec.Open(path)
	if err != nil {
		return Info{}, err
	}
	defer d.Close()
	in := d.Info()
	return Info{
		SampleRate:    in.SampleRate,
		Channels:      in.Channels,
		BitsPerSample: in.BitsPerSample,
		Duration:      in.Duration(),
	}, nil
}

// Stream decodes path from startSample (in the file's own sample rate) and
// writes an Ogg Opus stream to w, returning when the track ends or ctx is
// cancelled.
//
// w is written one page at a time, so wrapping it in a flushing writer streams
// live instead of buffering the whole track.
func Stream(ctx context.Context, path string, startSample uint64, cfg Config, w io.Writer) error {
	frameSize, err := cfg.frameSize()
	if err != nil {
		return err
	}

	src, err := flacdec.Open(path)
	if err != nil {
		return fmt.Errorf("transcode: %w", err)
	}
	defer src.Close()

	info := src.Info()
	ch := info.Channels
	if ch != 1 && ch != 2 {
		return ogg.ErrChannels
	}
	inRate := float64(info.SampleRate)
	bits := info.BitsPerSample
	if bits <= 0 || bits > 32 {
		return fmt.Errorf("transcode: %d bits per sample", bits)
	}
	scale := float32(math.Ldexp(1, bits-1))

	if startSample > 0 {
		if err := src.Seek(startSample); err != nil {
			return err
		}
	}

	enc, err := opusenc.New(opusRate, ch, opusenc.Audio)
	if err != nil {
		return fmt.Errorf("transcode: opus encoder: %w", err)
	}
	defer enc.Close()
	if err := enc.SetBitrate(cfg.Bitrate); err != nil {
		return err
	}
	if err := enc.SetComplexity(cfg.Complexity); err != nil {
		return err
	}
	if err := enc.SetVBR(true); err != nil {
		return err
	}
	if err := enc.SetSignalMusic(); err != nil {
		return err
	}

	var rs *resample.Stream
	if info.SampleRate != opusRate {
		rs, err = resample.NewQuality(inRate, opusRate, ch, cfg.ResampleQuality)
		if err != nil {
			return err
		}
		defer rs.Close()
	}

	ow := ogg.NewWriter(w, 0x5258504c) // "RXPL"
	if err := ow.WritePacket(ogg.OpusHead(ch, uint16(enc.Lookahead()), uint32(math.Round(inRate)), 0)); err != nil {
		return err
	}
	ow.SetGranule(0)
	if err := ow.Flush(); err != nil {
		return err
	}
	if err := ow.WritePacket(ogg.OpusTags("rx-playa streamer", []string{
		"ENCODER=rxplaya-streamer",
		fmt.Sprintf("OPUS_BITRATE=%d", cfg.Bitrate),
	})); err != nil {
		return err
	}
	if err := ow.Flush(); err != nil {
		return err
	}

	st := &state{
		ch:        ch,
		scale:     scale,
		frameSize: frameSize,
		enc:       enc,
		ow:        ow,
		rs:        rs,
		pcm:       make([]float32, 0, frameSize*ch*4),
		packet:    make([]byte, 4000*8),
	}

	for {
		if err := ctx.Err(); err != nil {
			return err
		}
		flat, _, bs, err := src.Next()
		if err == io.EOF {
			break
		}
		if err != nil {
			return fmt.Errorf("transcode: decode: %w", err)
		}
		if err := st.feed(flat, bs); err != nil {
			return err
		}
	}

	// Drain the resampler's delay, then close with whatever the last, short
	// frame is padded to. The granule position carries the real length, so the
	// padding is trimmed by any player that reads it.
	if rs != nil {
		tail, err := rs.Flush()
		if err != nil {
			return err
		}
		st.pcm = append(st.pcm, tail...)
	}
	if err := st.drain(); err != nil {
		return err
	}
	if len(st.pcm) > 0 {
		pad := make([]float32, frameSize*ch)
		copy(pad, st.pcm)
		if err := st.encodeFrame(pad); err != nil {
			return err
		}
	}
	ow.SetGranule(st.samples)
	return ow.FlushEOS()
}

type state struct {
	ch        int
	scale     float32
	frameSize int
	enc       *opusenc.Encoder
	ow        *ogg.Writer
	rs        *resample.Stream
	pcm       []float32
	in        []float32
	packet    []byte
	samples   uint64 // 48 kHz frames actually encoded, padding excluded
}

// feed takes one decoded FLAC frame — planar, channels consecutive blocks of
// blocksize samples — interleaves it, converts to float, resamples if needed
// and encodes whatever whole Opus frames result.
func (s *state) feed(flat []int32, blocksize int) error {
	if blocksize == 0 {
		return nil
	}
	need := blocksize * s.ch
	if cap(s.in) < need {
		s.in = make([]float32, need)
	}
	in := s.in[:need]

	// A reciprocal beats a division per sample, and the stereo case is the one
	// that runs a thousand times over.
	inv := 1 / s.scale
	for c := 0; c < s.ch; c++ {
		base := c * blocksize
		for i := 0; i < blocksize; i++ {
			in[i*s.ch+c] = float32(flat[base+i]) * inv
		}
	}

	if s.rs != nil {
		out, err := s.rs.Process(in)
		if err != nil {
			return err
		}
		s.pcm = append(s.pcm, out...)
	} else {
		s.pcm = append(s.pcm, in...)
	}
	return s.drain()
}

// drain encodes every whole frame in the buffer and keeps the remainder at the
// front, so the backing array is reused instead of regrown per frame.
func (s *state) drain() error {
	n := s.frameSize * s.ch
	i := 0
	for len(s.pcm)-i >= n {
		if err := s.encodeFrame(s.pcm[i : i+n]); err != nil {
			return err
		}
		i += n
	}
	if i > 0 {
		rest := copy(s.pcm, s.pcm[i:])
		s.pcm = s.pcm[:rest]
	}
	return nil
}

func (s *state) encodeFrame(frame []float32) error {
	n, err := s.enc.EncodeFloat32(frame, s.packet)
	if err != nil {
		return fmt.Errorf("transcode: encode: %w", err)
	}
	if err := s.ow.WritePacket(s.packet[:n]); err != nil {
		return err
	}
	s.samples += uint64(s.frameSize)
	s.ow.SetGranule(s.samples)
	return s.ow.Flush()
}
