package api

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"os"
	"os/exec"
	"path/filepath"
	"time"
)

// errNoCover is the ordinary "this file has no embedded picture" answer.
var errNoCover = errors.New("api: no embedded cover")

// coverPath is where the extracted picture for a track lives: one file per
// master, named by the hash of its path so replacing the file yields a new one.
func (s *Server) coverPath(master string) string {
	sum := sha256.Sum256([]byte(master))
	return filepath.Join(s.ArtDir, hex.EncodeToString(sum[:8])+".jpg")
}

// cover returns the cached picture for master, extracting it on first use.
//
// ffmpeg writes the file already scaled: a phone draws a cover at a few hundred
// pixels, and a 3000px scan from a FLAC costs the listener bandwidth on every
// shelf they scroll past.
func (s *Server) cover(ctx context.Context, master string) (string, error) {
	if s.ArtDir == "" {
		return "", errNoCover
	}
	dst := s.coverPath(master)
	if fi, err := os.Stat(dst); err == nil && fi.Size() > 0 {
		return dst, nil
	}
	if err := os.MkdirAll(filepath.Dir(dst), 0o755); err != nil {
		return "", err
	}
	ctx, cancel := context.WithTimeout(ctx, 30*time.Second)
	defer cancel()
	tmp := dst + ".tmp"
	cfg := []string{"-v", "error", "-y", "-i", master,
		"-an", "-frames:v", "1",
		"-vf", "scale='min(640,iw)':-2",
		"-f", "image2", "-c:v", "mjpeg", "-q:v", "4", tmp}
	if out, err := exec.CommandContext(ctx, "ffmpeg", cfg...).CombinedOutput(); err != nil {
		_ = os.Remove(tmp)
		// A file with no picture stream is ffmpeg's most ordinary refusal here.
		if len(out) == 0 {
			return "", errNoCover
		}
		return "", errNoCover
	}
	if fi, err := os.Stat(tmp); err != nil || fi.Size() == 0 {
		_ = os.Remove(tmp)
		return "", errNoCover
	}
	if err := os.Rename(tmp, dst); err != nil {
		_ = os.Remove(tmp)
		return "", err
	}
	_ = os.Chmod(dst, 0o644)
	return dst, nil
}
