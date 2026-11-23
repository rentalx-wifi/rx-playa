// Package flacdec decodes FLAC through libFLAC instead of in Go.
//
// The pure-Go decoder this replaces runs at about 176x real time; libFLAC
// manages 406x on the same file. On a cold transcode that is a sixth of the
// work, and on a warm one it does not matter at all — which is why this exists
// as an option and not as the only path.
//
// The callback libFLAC provides is C-to-Go and would cost a cgo transition per
// frame. The write callback here copies into a buffer owned by the C side
// instead, so Go pays one call to flacdec_next per frame and no more.
package flacdec

/*
#cgo pkg-config: flac
#include <stdlib.h>
#include "flacdec.h"
*/
import "C"

import (
	"errors"
	"fmt"
	"io"
	"runtime"
	"unsafe"
)

// Info describes the stream without decoding audio.
type Info struct {
	SampleRate    int
	Channels      int
	BitsPerSample int
	TotalSamples  uint64
}

// Duration is the length in seconds, or 0 when the file does not declare it.
func (i Info) Duration() float64 {
	if i.SampleRate <= 0 || i.TotalSamples == 0 {
		return 0
	}
	return float64(i.TotalSamples) / float64(i.SampleRate)
}

// Decoder is one file being decoded.
type Decoder struct {
	d    *C.flacdec
	info Info
}

// Open starts decoding path and reads the stream header.
func Open(path string) (*Decoder, error) {
	cpath := C.CString(path)
	defer C.free(unsafe.Pointer(cpath))

	var cerr *C.char
	d := C.flacdec_open(cpath, &cerr)
	if d == nil {
		if cerr != nil {
			return nil, fmt.Errorf("flacdec: %s", C.GoString(cerr))
		}
		return nil, errors.New("flacdec: open failed")
	}
	dec := &Decoder{d: d}
	// The metadata callback fires on the first process_single, so pull one
	// frame here to populate the header fields before reporting Info.
	if _, _, _, err := dec.Next(); err != nil && !errors.Is(err, io.EOF) {
		dec.Close()
		return nil, err
	}
	runtime.SetFinalizer(dec, (*Decoder).Close)
	return dec, nil
}

// Info returns the stream parameters read from the file header.
func (d *Decoder) Info() Info { return d.info }

// Next decodes one frame. The returned slice is planar — channels consecutive
// blocks of blocksize samples — and aliases C memory that is only valid until
// the next call to Next or Seek.
func (d *Decoder) Next() (samples []int32, channels, blocksize int, err error) {
	rc := C.flacdec_next(d.d)
	switch rc {
	case 0:
		return nil, 0, 0, io.EOF
	case -1:
		return nil, 0, 0, errors.New("flacdec: decode error")
	}
	ch := int(C.flacdec_channels(d.d))
	bs := int(C.flacdec_blocksize(d.d))
	if ch == 0 || bs == 0 {
		return nil, 0, 0, errors.New("flacdec: empty frame")
	}
	d.info = Info{
		SampleRate:    int(C.flacdec_rate(d.d)),
		Channels:      ch,
		BitsPerSample: int(C.flacdec_bits(d.d)),
		TotalSamples:  uint64(C.flacdec_total(d.d)),
	}
	ptr := C.flacdec_samples(d.d)
	if ptr == nil {
		return nil, 0, 0, errors.New("flacdec: no samples")
	}
	flat := unsafe.Slice((*int32)(unsafe.Pointer(ptr)), ch*bs)
	return flat, ch, bs, nil
}

// Seek positions the decoder so that the next frame contains sample, counted
// in the file's own sample rate.
func (d *Decoder) Seek(sample uint64) error {
	if C.flacdec_seek(d.d, C.uint64_t(sample)) != 0 {
		return fmt.Errorf("flacdec: seek to %d failed", sample)
	}
	return nil
}

// Close releases the decoder. It is safe to call more than once.
func (d *Decoder) Close() {
	if d.d != nil {
		C.flacdec_close(d.d)
		d.d = nil
	}
	runtime.SetFinalizer(d, nil)
}
