package transcode

import (
	"math"
	"os/exec"
	"strconv"
	"strings"
	"testing"
)

// rmsDB asks ffmpeg for the overall RMS level of a file in dBFS.
func rmsDB(t *testing.T, path string) float64 {
	t.Helper()
	ffmpeg := needTool(t, "ffmpeg")
	out, err := exec.Command(ffmpeg, "-hide_banner", "-nostats",
		"-i", path, "-af", "astats=metadata=1:reset=0",
		"-f", "null", "-").CombinedOutput()
	_ = err // astats exits non-zero when it writes warnings; the text is what matters
	s := string(out)
	idx := strings.LastIndex(s, "RMS level dB:")
	if idx < 0 {
		t.Fatalf("no RMS in ffmpeg output:\n%s", s)
	}
	rest := s[idx+len("RMS level dB:"):]
	rest = strings.TrimSpace(rest)
	end := strings.IndexAny(rest, " \n\r")
	if end >= 0 {
		rest = rest[:end]
	}
	v, err := strconv.ParseFloat(rest, 64)
	if err != nil {
		t.Fatalf("parse RMS %q: %v", rest, err)
	}
	return v
}

// TestAudioSurvivesTheTrip catches the failure that matters most and that a
// duration check would happily pass: an output that is silent, clipped, or
// several dB off because the samples were scaled wrong on the way through.
func TestAudioSurvivesTheTrip(t *testing.T) {
	dir := t.TempDir()
	in := makeFLAC(t, dir, 44100, 10, "s32")

	before := rmsDB(t, in)
	if math.IsInf(before, -1) || before < -40 {
		t.Fatalf("source RMS is %.1f dB, the fixture is wrong", before)
	}

	out := writeTemp(t, run(t, in, 0, Default))
	after := rmsDB(t, out)

	// Lossy coding at 256 kbit/s moves RMS by a hair at most; a wrong scale
	// factor moves it by 6 dB or more, and silence moves it to -inf.
	if diff := math.Abs(after - before); diff > 1.0 {
		t.Errorf("RMS moved from %.2f dB to %.2f dB (%.2f dB)", before, after, diff)
	}
	t.Logf("RMS: source %.2f dB, opus %.2f dB", before, after)
}
