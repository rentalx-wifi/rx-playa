// Package resample wraps libsoxr for streaming sample-rate conversion.
//
// One Stream is one stream's worth of state: soxr keeps a small filter delay
// per instance and nothing else, which is what makes it usable per-connection
// at a rate of a thousand connections.
package resample

/*
#cgo LDFLAGS: -lsoxr -lm
#include <soxr.h>
#include <stdlib.h>

static soxr_t mk(double inRate, double outRate, unsigned channels, unsigned long q) {
	soxr_error_t err = NULL;
	soxr_io_spec_t io = soxr_io_spec(SOXR_FLOAT32_I, SOXR_FLOAT32_I);
	soxr_quality_spec_t qs = soxr_quality_spec(q, 0);
	soxr_runtime_spec_t rt = soxr_runtime_spec(1);
	soxr_t s = soxr_create(inRate, outRate, channels, &err, &io, &qs, &rt);
	if (err != NULL) {
		return NULL;
	}
	return s;
}

// proc returns the number of frames written, or -1 on error.
static long proc(soxr_t s, float *in, size_t iFrames, float *out, size_t oFrames) {
	size_t odone = 0;
	soxr_error_t err = soxr_process(s, in, iFrames, NULL, out, oFrames, &odone);
	if (err != NULL) {
		return -1;
	}
	return (long)odone;
}
*/
import "C"

import (
	"errors"
	"fmt"
	"math"
	"runtime"
	"unsafe"
)

// Stream converts interleaved float32 audio at one rate into another.
// It is not safe for concurrent use; one Stream belongs to one goroutine.
type Stream struct {
	h       C.soxr_t
	ch      int
	inRate  float64
	outRate float64
	tmp     []float32
}

// Quality selects soxr's filter recipe. HQ is the default and is heavier than
// the conversion deserves: 44.1 to 48 kHz into a 256 kbit/s Opus stream cannot
// tell HQ from MQ, and MQ costs a fraction of it.
type Quality int

// The values are soxr's own recipes: QQ 0, LQ 1, MQ 2, HQ 4, VHQ 6.
// Unset is distinct from QQ so that a zero Config is not silently read as the
// cheapest recipe.
const (
	Unset    Quality = -1
	Quick    Quality = 0 // soxr QQ: cubic interpolation
	Low      Quality = 1 // soxr LQ
	Medium   Quality = 2 // soxr MQ
	High     Quality = 4 // soxr HQ, 20-bit quality
	VeryHigh Quality = 6 // soxr VHQ, 28-bit quality
)

// New creates a converter at High quality. channels must match the audio being
// fed in.
func New(inRate, outRate float64, channels int) (*Stream, error) {
	return NewQuality(inRate, outRate, channels, High)
}

// NewQuality creates a converter with an explicit filter recipe.
//
// The recipes are not a smooth dial. Measured through the whole pipeline on
// prod, twice: QQ 113x real time, LQ 101x, and then a cliff -- MQ, the 16-bit
// recipe and HQ all land within noise of 87x, and no soxr quality flag lowers
// that (the coefficient-interpolation flag changes nothing, and disabling the
// anti-imaging rolloff makes it 60% slower, not faster). So there is nothing
// between LQ and HQ: the 14% is the price of a real anti-imaging filter, and it
// cannot be bought in part.
func NewQuality(inRate, outRate float64, channels int, q Quality) (*Stream, error) {
	if q == Unset {
		q = High
	}
	if q < 0 {
		return nil, fmt.Errorf("resample: bad quality %d", int(q))
	}
	if channels < 1 {
		return nil, fmt.Errorf("resample: %d channels", channels)
	}
	if inRate <= 0 || outRate <= 0 {
		return nil, fmt.Errorf("resample: rates %v -> %v", inRate, outRate)
	}
	h := C.mk(C.double(inRate), C.double(outRate), C.uint(channels), C.ulong(q))
	if h == nil {
		return nil, errors.New("resample: soxr_create failed")
	}
	s := &Stream{h: h, ch: channels, inRate: inRate, outRate: outRate}
	runtime.SetFinalizer(s, (*Stream).Close)
	return s, nil
}

// Ratio is the output frames per input frame.
func (s *Stream) Ratio() float64 { return s.outRate / s.inRate }

// Process converts in, an interleaved buffer of whole frames, and returns the
// converted frames. The result aliases the Stream's internal buffer and is only
// valid until the next call: copy it if it must outlive that.
func (s *Stream) Process(in []float32) ([]float32, error) {
	if len(in)%s.ch != 0 {
		return nil, fmt.Errorf("resample: %d samples is not a whole number of %d-channel frames", len(in), s.ch)
	}
	return s.run(in)
}

// Flush drains the filter delay with silence and returns the remaining frames.
func (s *Stream) Flush() ([]float32, error) { return s.run(nil) }

func (s *Stream) run(in []float32) ([]float32, error) {
	iFrames := len(in) / s.ch
	// soxr may emit one extra frame of delay per call; a fixed pad keeps the
	// output buffer honest without querying soxr_delay every time.
	oFrames := int(math.Ceil(float64(iFrames)*s.Ratio())) + 512
	if oFrames < 512 {
		oFrames = 512
	}
	if cap(s.tmp) < oFrames*s.ch {
		s.tmp = make([]float32, oFrames*s.ch)
	}
	out := s.tmp[:oFrames*s.ch]

	var inp *C.float
	if iFrames > 0 {
		inp = (*C.float)(unsafe.Pointer(&in[0]))
	}
	n := C.proc(s.h, inp, C.size_t(iFrames), (*C.float)(unsafe.Pointer(&out[0])), C.size_t(oFrames))
	// Both buffers are live as far as libsoxr is concerned for the whole call,
	// but neither is referenced in Go afterwards, so say so explicitly.
	runtime.KeepAlive(in)
	runtime.KeepAlive(out)
	if n < 0 {
		return nil, errors.New("resample: soxr_process failed")
	}
	// Return a view rather than a copy: at a thousand streams a fresh buffer
	// per frame is tens of megabytes a second of garbage.
	return out[:int(n)*s.ch], nil
}

// Close releases the converter. It is safe to call more than once.
func (s *Stream) Close() {
	if s.h != nil {
		C.soxr_delete(s.h)
		s.h = nil
	}
	runtime.SetFinalizer(s, nil)
}
