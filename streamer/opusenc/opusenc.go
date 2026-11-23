// Package opusenc is a thin binding to libopus's encoder only.
//
// The pooling wrappers available for Go bind the decoder too, which drags in
// libopusfile as a pkg-config dependency for a code path this server never
// calls. Encoders are all a stream needs, and binding them directly also makes
// the real lookahead available instead of a guessed constant.
package opusenc

/*
#cgo pkg-config: opus
#include <opus.h>
#include <stdlib.h>

// The OPUS_SET_* and OPUS_GET_* names are function-like macros, not constants,
// so cgo cannot call them directly. Each control gets a one-line wrapper here.
static int set_bitrate(OpusEncoder *e, int v)   { return opus_encoder_ctl(e, OPUS_SET_BITRATE(v)); }
static int set_complexity(OpusEncoder *e, int v){ return opus_encoder_ctl(e, OPUS_SET_COMPLEXITY(v)); }
static int set_vbr(OpusEncoder *e, int v)       { return opus_encoder_ctl(e, OPUS_SET_VBR(v)); }
static int set_signal(OpusEncoder *e, int v)    { return opus_encoder_ctl(e, OPUS_SET_SIGNAL(v)); }
static int get_lookahead(OpusEncoder *e, int *o){ return opus_encoder_ctl(e, OPUS_GET_LOOKAHEAD(o)); }
*/
import "C"

import (
	"errors"
	"fmt"
	"runtime"
	"unsafe"
)

// Application is the encoder's tuning: speech, music, or low delay.
type Application int

const (
	// VoIP tunes for speech.
	VoIP Application = C.OPUS_APPLICATION_VOIP
	// Audio tunes for music, which is what a music server wants.
	Audio Application = C.OPUS_APPLICATION_AUDIO
	// RestrictedLowdelay is for interactive use.
	RestrictedLowdelay Application = C.OPUS_APPLICATION_RESTRICTED_LOWDELAY
)

// Encoder is one stream's libopus state.
type Encoder struct {
	e    *C.OpusEncoder
	ch   int
	rate int
}

// New creates an encoder. Opus accepts only 8, 12, 16, 24 and 48 kHz.
func New(sampleRate, channels int, app Application) (*Encoder, error) {
	var errno C.int
	e := C.opus_encoder_create(C.opus_int32(sampleRate), C.int(channels),
		C.int(app), &errno)
	if errno != C.OPUS_OK || e == nil {
		return nil, fmt.Errorf("opusenc: create: %s", strerror(errno))
	}
	enc := &Encoder{e: e, ch: channels, rate: sampleRate}
	runtime.SetFinalizer(enc, (*Encoder).Close)
	return enc, nil
}

// SetBitrate sets the target bitrate in bits per second.
func (e *Encoder) SetBitrate(bps int) error {
	return e.check(C.set_bitrate(e.e, C.int(bps)), "bitrate")
}

// SetComplexity sets the effort from 0 to 10.
func (e *Encoder) SetComplexity(v int) error {
	return e.check(C.set_complexity(e.e, C.int(v)), "complexity")
}

// SetVBR turns variable bitrate on or off; libopus defaults to on.
func (e *Encoder) SetVBR(on bool) error {
	v := C.int(0)
	if on {
		v = 1
	}
	return e.check(C.set_vbr(e.e, v), "vbr")
}

// SetSignalMusic tells the encoder the content is music.
func (e *Encoder) SetSignalMusic() error {
	return e.check(C.set_signal(e.e, C.OPUS_SIGNAL_MUSIC), "signal")
}

func (e *Encoder) check(rc C.int, what string) error {
	if rc != C.OPUS_OK {
		return fmt.Errorf("opusenc: set %s: %s", what, strerror(rc))
	}
	return nil
}

// Lookahead is the encoder delay in samples, which a gapless player subtracts
// from the stream's start.
func (e *Encoder) Lookahead() int {
	var v C.int
	if rc := C.get_lookahead(e.e, &v); rc != C.OPUS_OK {
		return 0
	}
	return int(v)
}

// EncodeFloat32 encodes interleaved float32 samples in [-1, 1] and returns the
// number of bytes written to out. The sample count must be a valid Opus
// duration at the encoder's rate.
func (e *Encoder) EncodeFloat32(pcm []float32, out []byte) (int, error) {
	if len(pcm) == 0 {
		return 0, errors.New("opusenc: no samples")
	}
	if len(pcm)%e.ch != 0 {
		return 0, fmt.Errorf("opusenc: %d samples is not a whole number of %d-channel frames", len(pcm), e.ch)
	}
	frames := len(pcm) / e.ch
	var outp *C.uchar
	if len(out) > 0 {
		outp = (*C.uchar)(unsafe.Pointer(&out[0]))
	}
	n := C.opus_encode_float(e.e, (*C.float)(unsafe.Pointer(&pcm[0])),
		C.int(frames), outp, C.opus_int32(len(out)))
	// libopus reads pcm and writes out for the duration of the call; without
	// these the compiler is free to consider both dead once the arguments are
	// passed, and the collector may reclaim them mid-encode.
	runtime.KeepAlive(pcm)
	runtime.KeepAlive(out)
	if n < 0 {
		return 0, fmt.Errorf("opusenc: encode: %s", strerror(n))
	}
	return int(n), nil
}

// Close releases the encoder. It is safe to call more than once.
func (e *Encoder) Close() {
	if e.e != nil {
		C.opus_encoder_destroy(e.e)
		e.e = nil
	}
	runtime.SetFinalizer(e, nil)
}

func strerror(code C.int) string {
	s := C.GoString(C.opus_strerror(code))
	if s == "" {
		return fmt.Sprintf("error %d", int(code))
	}
	return s
}
