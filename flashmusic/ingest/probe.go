// Package ingest turns audio files on disk into catalog entries.
package ingest

import (
	"context"
	"encoding/json"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

// audioExt are the containers we accept. Ogg covers both Vorbis and Opus.
var audioExt = map[string]bool{
	".flac": true, ".opus": true, ".ogg": true, ".mp3": true,
	".m4a": true, ".aac": true, ".wav": true, ".aiff": true, ".aif": true,
}

// Probe is everything the catalog needs to know about one file.
type Probe struct {
	Path        string `json:"path"`
	Size        int64  `json:"size"`
	Mtime       int64  `json:"mtime"`
	Title       string `json:"title"`
	Artist      string `json:"artist"`
	Album       string `json:"album"`
	Year        int    `json:"year"`
	TrackNo     int    `json:"trackNo"`
	DiscNo      int    `json:"discNo"`
	DurationMs  int64  `json:"durationMs"`
	Codec       string `json:"codec"`
	SampleRate  int    `json:"sampleRate"`
	Bits        int    `json:"bits"`
	Bitrate     int    `json:"bitrate"`
	Fingerprint string `json:"fingerprint"`
}

type ffprobeOut struct {
	Streams []struct {
		CodecName        string `json:"codec_name"`
		SampleRate       string `json:"sample_rate"`
		BitsPerRawSample string `json:"bits_per_raw_sample"`
		BitsPerSample    int    `json:"bits_per_sample"`
		BitRate          string `json:"bit_rate"`
	} `json:"streams"`
	Format struct {
		Duration string            `json:"duration"`
		BitRate  string            `json:"bit_rate"`
		Tags     map[string]string `json:"tags"`
	} `json:"format"`
}

type fpcalcOut struct {
	Duration    float64 `json:"duration"`
	Fingerprint string  `json:"fingerprint"`
}

// probeTimeout bounds one external tool per file. A network share that stops
// answering, or a truncated FLAC that ffprobe sits on, used to hold a scan
// worker for good — and eight of those end the scan entirely.
const probeTimeout = 60 * time.Second

// withinTimeout runs [f] on a context that is cancelled after [probeTimeout] and
// still honours the caller's own cancellation.
func withinTimeout(ctx context.Context, f func(context.Context) error) error {
	ctx, cancel := context.WithTimeout(ctx, probeTimeout)
	defer cancel()
	return f(ctx)
}

// ScanFile probes one file: tags and stream parameters from ffprobe, the
// acoustic fingerprint from fpcalc.
func ScanFile(ctx context.Context, path string) (*Probe, error) {
	st, err := os.Stat(path)
	if err != nil {
		return nil, err
	}
	p := &Probe{Path: path, Size: st.Size(), Mtime: st.ModTime().Unix()}

	if err := withinTimeout(ctx, func(ctx context.Context) error { return p.readMeta(ctx, path) }); err != nil {
		return nil, err
	}
	if err := withinTimeout(ctx, func(ctx context.Context) error { return p.readFingerprint(ctx, path) }); err != nil {
		return nil, err
	}
	return p, nil
}

func (p *Probe) readMeta(ctx context.Context, path string) error {
	out, err := exec.CommandContext(ctx, "ffprobe", "-v", "error",
		"-print_format", "json", "-show_format", "-show_streams", path).Output()
	if err != nil {
		return fmt.Errorf("ffprobe %s: %w", path, err)
	}
	var v ffprobeOut
	if err := json.Unmarshal(out, &v); err != nil {
		return fmt.Errorf("ffprobe %s: %w", path, err)
	}

	tags := map[string]string{}
	for k, val := range v.Format.Tags {
		tags[strings.ToLower(k)] = strings.TrimSpace(val)
	}
	p.Title = tags["title"]
	p.Artist = firstNonEmpty(tags["artist"], tags["album_artist"], tags["albumartist"])
	p.Album = tags["album"]
	p.Year = parseYear(firstNonEmpty(tags["date"], tags["year"], tags["originaldate"]))
	p.TrackNo = parseIndex(firstNonEmpty(tags["tracknumber"], tags["track"]))
	p.DiscNo = parseIndex(firstNonEmpty(tags["discnumber"], tags["disc"]))

	if d, err := strconv.ParseFloat(v.Format.Duration, 64); err == nil {
		p.DurationMs = int64(d * 1000)
	}
	if br, err := strconv.Atoi(v.Format.BitRate); err == nil {
		p.Bitrate = br
	}
	for _, s := range v.Streams {
		if s.CodecName == "" || p.Codec != "" {
			continue
		}
		p.Codec = s.CodecName
		if sr, err := strconv.Atoi(s.SampleRate); err == nil {
			p.SampleRate = sr
		}
		p.Bits = s.BitsPerSample
		if s.BitsPerRawSample != "" {
			if b, err := strconv.Atoi(s.BitsPerRawSample); err == nil {
				p.Bits = b
			}
		}
		if p.Bitrate == 0 {
			if br, err := strconv.Atoi(s.BitRate); err == nil {
				p.Bitrate = br
			}
		}
	}

	// Files without tags still need a name: "Artist - Title.flac" is the
	// convention uploads actually arrive in.
	if p.Title == "" {
		base := strings.TrimSuffix(filepath.Base(path), filepath.Ext(path))
		if a, t, ok := strings.Cut(base, " - "); ok {
			p.Artist = firstNonEmpty(p.Artist, strings.TrimSpace(a))
			base = strings.TrimSpace(t)
		}
		p.Title = base
	}
	return nil
}

func (p *Probe) readFingerprint(ctx context.Context, path string) error {
	// 120 seconds is the Chromaprint default and is what stays stable across
	// re-encodes; a longer window starts reacting to trailing silence.
	out, err := exec.CommandContext(ctx, "fpcalc", "-json", "-length", "120", path).Output()
	if err != nil {
		return fmt.Errorf("fpcalc %s: %w", path, err)
	}
	var v fpcalcOut
	if err := json.Unmarshal(out, &v); err != nil {
		return fmt.Errorf("fpcalc %s: %w", path, err)
	}
	if v.Fingerprint == "" {
		return fmt.Errorf("fpcalc %s: empty fingerprint", path)
	}
	p.Fingerprint = v.Fingerprint
	if p.DurationMs == 0 {
		p.DurationMs = int64(v.Duration * 1000)
	}
	return nil
}

func firstNonEmpty(vals ...string) string {
	for _, v := range vals {
		if v != "" {
			return v
		}
	}
	return ""
}

func parseYear(s string) int {
	if len(s) >= 4 {
		if y, err := strconv.Atoi(s[:4]); err == nil {
			return y
		}
	}
	return 0
}

func parseIndex(s string) int {
	if i := strings.IndexAny(s, "/"); i >= 0 {
		s = s[:i]
	}
	n, _ := strconv.Atoi(strings.TrimSpace(s))
	return n
}
