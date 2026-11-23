package ogg

import (
	"bytes"
	"testing"
)

// A packet big enough to fill the lacing table exactly must still produce a
// page whose header count matches the table.
func TestFullLacingTable(t *testing.T) {
	for _, size := range []int{255, 255 * 254, 255 * 255, 255*255 + 1, 200000} {
		var buf bytes.Buffer
		w := NewWriter(&buf, 1)
		if err := w.WritePacket(make([]byte, size)); err != nil {
			t.Fatal(err)
		}
		if err := w.FlushEOS(); err != nil {
			t.Fatal(err)
		}
		b := buf.Bytes()
		for i := 0; i < len(b); {
			if string(b[i:i+4]) != "OggS" {
				t.Fatalf("size %d: no page header at %d", size, i)
			}
			nseg := int(b[i+26])
			if nseg > 255 || nseg == 0 {
				t.Fatalf("size %d: segment count %d", size, nseg)
			}
			body := 0
			for k := 0; k < nseg; k++ {
				body += int(b[i+27+k])
			}
			i += 27 + nseg + body
		}
	}
}
