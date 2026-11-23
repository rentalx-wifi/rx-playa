package transcode

import (
	"bytes"
	"context"
	"encoding/json"
	"os"
	"os/exec"
	"path/filepath"
	"testing"
)

func needTool(t *testing.T, name string) string {
	t.Helper()
	p, err := exec.LookPath(name)
	if err != nil {
		t.Skipf("%s not installed", name)
	}
	return p
}

// makeFLAC renders a stereo FLAC at the given rate with a tone and noise, so
// the encoder sees something with real high-frequency content to spend bits on.
func makeFLAC(t *testing.T, dir string, rate, seconds int, bits string) string {
	t.Helper()
	ffmpeg := needTool(t, "ffmpeg")
	out := filepath.Join(dir, "in.flac")
	args := []string{
		"-hide_banner", "-loglevel", "error", "-y",
		"-f", "lavfi", "-i", "sine=frequency=440:sample_rate=" + itoa(rate) + ":duration=" + itoa(seconds),
		"-f", "lavfi", "-i", "anoisesrc=d=" + itoa(seconds) + ":c=pink:r=" + itoa(rate) + ":a=0.05",
		"-filter_complex", "[0:a][1:a]amix=inputs=2:duration=shortest,volume=0.5",
		"-c:a", "flac", "-sample_fmt", bits,
	}
	if bits == "s32" {
		args = append(args, "-bits_per_raw_sample", "24")
	} else {
		args = append(args, "-bits_per_raw_sample", "16")
	}
	args = append(args, "-ar", itoa(rate), "-ac", "2", out)
	if b, err := exec.Command(ffmpeg, args...).CombinedOutput(); err != nil {
		t.Fatalf("ffmpeg: %v: %s", err, b)
	}
	return out
}

func itoa(v int) string {
	var b [12]byte
	i := len(b)
	if v == 0 {
		return "0"
	}
	for v > 0 {
		i--
		b[i] = byte('0' + v%10)
		v /= 10
	}
	return string(b[i:])
}

type probeResult struct {
	Streams []struct {
		CodecName  string `json:"codec_name"`
		SampleRate string `json:"sample_rate"`
		Channels   int    `json:"channels"`
		Duration   string `json:"duration"`
	} `json:"streams"`
	Format struct {
		Duration string `json:"duration"`
	} `json:"format"`
}

func probe(t *testing.T, path string) probeResult {
	t.Helper()
	ffprobe := needTool(t, "ffprobe")
	b, err := exec.Command(ffprobe, "-v", "error",
		"-show_entries", "stream=codec_name,sample_rate,channels,duration",
		"-show_entries", "format=duration",
		"-of", "json", path).CombinedOutput()
	if err != nil {
		t.Fatalf("ffprobe: %v: %s", err, b)
	}
	var r probeResult
	if err := json.Unmarshal(b, &r); err != nil {
		t.Fatalf("ffprobe json: %v", err)
	}
	return r
}

func run(t *testing.T, in string, startSample uint64, cfg Config) []byte {
	t.Helper()
	var buf bytes.Buffer
	if err := Stream(context.Background(), in, startSample, cfg, &buf); err != nil {
		t.Fatalf("stream: %v", err)
	}
	return buf.Bytes()
}

func writeTemp(t *testing.T, data []byte) string {
	t.Helper()
	p := filepath.Join(t.TempDir(), "out.opus")
	if err := os.WriteFile(p, data, 0o600); err != nil {
		t.Fatal(err)
	}
	return p
}

func TestStreamIsPlayableOpus(t *testing.T) {
	dir := t.TempDir()
	in := makeFLAC(t, dir, 44100, 10, "s32")

	out := run(t, in, 0, Default)
	if len(out) < 1000 {
		t.Fatalf("output is %d bytes", len(out))
	}
	p := writeTemp(t, out)
	r := probe(t, p)

	if len(r.Streams) != 1 {
		t.Fatalf("got %d streams", len(r.Streams))
	}
	s := r.Streams[0]
	if s.CodecName != "opus" {
		t.Errorf("codec = %q, want opus", s.CodecName)
	}
	if s.SampleRate != "48000" {
		t.Errorf("sample rate = %s, want 48000", s.SampleRate)
	}
	if s.Channels != 2 {
		t.Errorf("channels = %d, want 2", s.Channels)
	}
	// Ten seconds in, out, allowing for the encoder's pre-skip trimming.
	if got := atof(t, r.Format.Duration); got < 9.9 || got > 10.1 {
		t.Errorf("duration = %.3fs, want ~10s", got)
	}
	// 256 kbit/s over 10 s is ~2.56 Mbit; a quarter of that means the encoder
	// ran at some much lower setting.
	if mb := float64(len(out)) * 8 / 1e6; mb < 1.9 || mb > 3.2 {
		t.Errorf("payload = %.2f Mbit for 10s, want roughly 2.56", mb)
	}
}

func TestStreamAt48kSkipsResampler(t *testing.T) {
	dir := t.TempDir()
	in := makeFLAC(t, dir, 48000, 5, "s16")

	out := run(t, in, 0, Default)
	r := probe(t, writeTemp(t, out))
	if len(r.Streams) != 1 || r.Streams[0].CodecName != "opus" {
		t.Fatalf("not an opus stream")
	}
	if got := atof(t, r.Format.Duration); got < 4.9 || got > 5.1 {
		t.Errorf("duration = %.3fs, want ~5s", got)
	}
}

func TestSeekStartsWhereAsked(t *testing.T) {
	dir := t.TempDir()
	in := makeFLAC(t, dir, 44100, 10, "s16")

	// Ask for the second half.
	out := run(t, in, 44100*5, Default)
	r := probe(t, writeTemp(t, out))
	got := atof(t, r.Format.Duration)
	if got < 4.5 || got > 5.2 {
		t.Errorf("duration after seeking to 5s = %.3fs, want ~5s", got)
	}
}

func TestProbeMatchesFile(t *testing.T) {
	dir := t.TempDir()
	in := makeFLAC(t, dir, 44100, 3, "s16")

	info, err := Probe(in)
	if err != nil {
		t.Fatal(err)
	}
	if info.SampleRate != 44100 {
		t.Errorf("rate = %d", info.SampleRate)
	}
	if info.Channels != 2 {
		t.Errorf("channels = %d", info.Channels)
	}
	if info.Duration < 2.9 || info.Duration > 3.1 {
		t.Errorf("duration = %.3f", info.Duration)
	}
}

func atof(t *testing.T, s string) float64 {
	t.Helper()
	var v float64
	var frac, div float64
	var neg bool
	i := 0
	if i < len(s) && s[i] == '-' {
		neg = true
		i++
	}
	for ; i < len(s) && s[i] >= '0' && s[i] <= '9'; i++ {
		v = v*10 + float64(s[i]-'0')
	}
	if i < len(s) && s[i] == '.' {
		i++
		div = 1
		for ; i < len(s) && s[i] >= '0' && s[i] <= '9'; i++ {
			frac = frac*10 + float64(s[i]-'0')
			div *= 10
		}
	}
	v += frac / div
	if neg {
		v = -v
	}
	return v
}
