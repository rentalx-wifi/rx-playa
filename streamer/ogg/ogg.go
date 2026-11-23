// Package ogg writes Opus-in-Ogg streams (RFC 7845) page by page.
//
// It exists because the encoders available to Go either mux to a file or not at
// all, and a live stream needs a page on the wire every 20 ms with the right
// granule position. The whole muxer is a lacing table and a CRC.
package ogg

import (
	"encoding/binary"
	"fmt"
)

// Ogg's CRC-32: polynomial 0x04c11db7, no input or output reflection, zero
// initial value and no final xor. Go's hash/crc32 only implements reflected
// CRCs, so the table and the update are here rather than imported.
var crcTable [256]uint32

func init() {
	for i := range crcTable {
		c := uint32(i) << 24
		for range 8 {
			if c&0x80000000 != 0 {
				c = c<<1 ^ 0x04c11db7
			} else {
				c <<= 1
			}
		}
		crcTable[i] = c
	}
}

func crc(b []byte) uint32 {
	var c uint32
	for _, v := range b {
		c = c<<8 ^ crcTable[byte(c>>24)^v]
	}
	return c
}

// Header flags.
const (
	FlagContinuation = 0x01
	FlagBOS          = 0x02
	FlagEOS          = 0x04
)

// Writer serialises packets into an Ogg stream on a single logical bitstream.
type Writer struct {
	w        interface{ Write([]byte) (int, error) }
	serial   uint32
	seq      uint32
	granule  uint64
	segments []byte
	body     []byte
	buf      []byte
}

// NewWriter returns a writer for one logical stream identified by serial.
func NewWriter(w interface{ Write([]byte) (int, error) }, serial uint32) *Writer {
	return &Writer{w: w, serial: serial}
}

// SetGranule records the granule position to stamp on the next page.
func (o *Writer) SetGranule(g uint64) { o.granule = g }

// Granule is the position most recently stamped.
func (o *Writer) Granule() uint64 { return o.granule }

// WritePacket appends a packet to the page being built. Packets are flushed to
// the writer when the page fills (255 lacing values) or when Flush is called.
func (o *Writer) WritePacket(p []byte) error {
	// A packet is split into 255-byte lacing values; a value below 255 ends it.
	// A zero-length packet is a single zero lace.
	n := len(p)
	if n == 0 {
		o.segments = append(o.segments, 0)
		return nil
	}
	for n >= 255 {
		if len(o.segments) == 255 {
			if err := o.flush(0); err != nil {
				return err
			}
		}
		o.segments = append(o.segments, 255)
		o.body = append(o.body, p[:255]...)
		p = p[255:]
		n -= 255
	}
	// A page holds at most 255 lacing values, and the header only has one byte
	// for the count. If the loop above filled the table, flush before the
	// terminator rather than writing a 256th value the header cannot describe.
	if len(o.segments) >= 255 {
		if err := o.flush(0); err != nil {
			return err
		}
	}
	o.segments = append(o.segments, byte(n))
	o.body = append(o.body, p...)
	if len(o.segments) >= 255 {
		return o.flush(0)
	}
	return nil
}

// Flush writes the pending page with the writer's current granule position.
func (o *Writer) Flush() error { return o.flush(0) }

// FlushEOS writes the pending page and closes the logical stream.
func (o *Writer) FlushEOS() error {
	if len(o.segments) == 0 {
		// An empty EOS page is legal and keeps a zero-length stream closable.
		o.segments = append(o.segments, 0)
	}
	flags := byte(FlagEOS)
	if o.seq == 0 {
		flags |= FlagBOS
	}
	if err := o.page(flags, o.granule, o.segments, o.body); err != nil {
		return err
	}
	o.seq++
	o.segments = o.segments[:0]
	o.body = o.body[:0]
	return nil
}

func (o *Writer) flush(extraFlags byte) error {
	if len(o.segments) == 0 {
		return nil
	}
	flags := extraFlags
	if o.seq == 0 {
		flags |= FlagBOS
	}
	if err := o.page(flags, o.granule, o.segments, o.body); err != nil {
		return err
	}
	o.seq++
	o.segments = o.segments[:0]
	o.body = o.body[:0]
	return nil
}

func (o *Writer) page(flags byte, granule uint64, segments, body []byte) error {
	need := 27 + len(segments) + len(body)
	if cap(o.buf) < need {
		o.buf = make([]byte, need)
	}
	b := o.buf[:need]

	copy(b[0:4], "OggS")
	b[4] = 0 // stream structure version
	b[5] = flags
	binary.LittleEndian.PutUint64(b[6:14], granule)
	binary.LittleEndian.PutUint32(b[14:18], o.serial)
	binary.LittleEndian.PutUint32(b[18:22], o.seq)
	binary.LittleEndian.PutUint32(b[22:26], 0) // CRC placeholder
	b[26] = byte(len(segments))
	copy(b[27:], segments)
	copy(b[27+len(segments):], body)

	crcv := crc(b)
	binary.LittleEndian.PutUint32(b[22:26], crcv)

	_, err := o.w.Write(b)
	return err
}

// OpusHead builds the identification packet for a stream of ch channels.
// preSkip is the encoder's lookahead in 48 kHz samples, which a gapless player
// subtracts from the start.
func OpusHead(ch int, preSkip uint16, inputRate uint32, mappingFamily byte) []byte {
	b := make([]byte, 19)
	copy(b[0:8], "OpusHead")
	b[8] = 1 // version
	b[9] = byte(ch)
	binary.LittleEndian.PutUint16(b[10:12], preSkip)
	binary.LittleEndian.PutUint32(b[12:16], inputRate)
	binary.LittleEndian.PutUint16(b[16:18], 0) // output gain
	b[18] = mappingFamily
	return b
}

// OpusTags builds the comment packet with the given vendor string.
func OpusTags(vendor string, comments []string) []byte {
	if len(vendor) > 255 {
		vendor = vendor[:255]
	}
	b := make([]byte, 0, 8+4+len(vendor)+4+len(comments)*64)
	b = append(b, "OpusTags"...)
	b = binary.LittleEndian.AppendUint32(b, uint32(len(vendor)))
	b = append(b, vendor...)
	b = binary.LittleEndian.AppendUint32(b, uint32(len(comments)))
	for _, c := range comments {
		b = binary.LittleEndian.AppendUint32(b, uint32(len(c)))
		b = append(b, c...)
	}
	return b
}

// ErrChannels is returned for a channel count family 0 cannot describe.
var ErrChannels = fmt.Errorf("ogg: channel mapping family 0 supports 1 or 2 channels")
