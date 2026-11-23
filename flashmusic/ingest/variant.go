package ingest

import (
	"context"
	"encoding/binary"
	"math"
	"os/exec"
	"regexp"
	"strconv"
	"strings"

	"github.com/rentalx-wifi/rx-playa/flashmusic/store"
)

// VariantMatch is what a title says about a slowed or sped-up edit.
type VariantMatch struct {
	Kind   string
	Reverb bool
	Base   string
}

var (
	bracketRE = regexp.MustCompile(`[\(\[]([^\)\]]*)[\)\]]`)
	// The marker has to be a word of its own: "Slowdive" is a band, not an edit.
	markerRE = regexp.MustCompile(`(?i)\b(ultra|super)?[\s_-]*(slow(?:ed)?|sped[\s_-]?up|speed[\s_-]?up|nightcore)\b`)

	// Where a marker has to stand to be naming an edit rather than a song.
	//
	// Inside brackets — "Song (Slowed + Reverb)" — or on its own at one end:
	// "Song - Slowed", "Song slowed", "Nightcore - Song", "Slowed: Song".
	// Anywhere else it is just a word in a title, and "Slow Down" read as a
	// slowed edit of "Down" is how a file stops being a track at all.
	bracketMarkerRE = regexp.MustCompile(`(?i)[\(\[]\s*(?:ultra|super)?[\s_-]*(?:slow(?:ed)?|sped[\s_-]?up|speed[\s_-]?up|nightcore)[^\)\]]*[\)\]]`)
	tailMarkerRE    = regexp.MustCompile(`(?i)[\s_]*(?:[-–—|:]\s*)?((?:ultra|super)?[\s_-]*(?:slow(?:ed)?|sped[\s_-]?up|speed[\s_-]?up|nightcore))[\s_-]*(?:\+?\s*reverb|version|edit|remix|mix)?$`)
	headMarkerRE    = regexp.MustCompile(`(?i)^\s*((?:ultra|super)?[\s_-]*(?:slow(?:ed)?|sped[\s_-]?up|speed[\s_-]?up|nightcore))\s*[-–—|:]\s*`)
)

func hasMarker(s string) bool { return markerRE.MatchString(s) }

// DetectVariant reads a file's title like "Song (Slowed + Reverb)" and returns
// the kind plus what is left of the title once the marker is gone.
//
// Strict about where the marker stands, because this decides whether a file
// enters the catalogue as a track at all. A search query is a different
// question — see [DetectVariantQuery].
func DetectVariant(title string) (VariantMatch, bool) {
	var marker, base string
	switch {
	case bracketMarkerRE.MatchString(title):
		bracket := bracketMarkerRE.FindString(title)
		marker = markerRE.FindString(bracket)
		base = stripBrackets(title)
	case tailMarkerRE.MatchString(title):
		m := tailMarkerRE.FindStringSubmatch(title)
		marker = m[1]
		base = title[:len(title)-len(m[0])]
	case headMarkerRE.MatchString(title):
		m := headMarkerRE.FindStringSubmatch(title)
		marker = m[1]
		base = title[len(m[0]):]
	default:
		return VariantMatch{}, false
	}
	return variantOf(title, marker, base)
}

// DetectVariantQuery is the loose read, for a search query: "song slowed" with
// no brackets and no separator is exactly how people ask for the edit.
func DetectVariantQuery(q string) (VariantMatch, bool) {
	m := markerRE.FindStringSubmatch(q)
	if m == nil {
		return VariantMatch{}, false
	}
	return variantOf(q, m[0], markerRE.ReplaceAllString(stripBrackets(q), " "))
}

// variantOf builds the match from the raw title, the marker that was found and
// what is left of the title once it and its brackets are gone.
func variantOf(raw, marker, base string) (VariantMatch, bool) {
	word := strings.NewReplacer(" ", "", "-", "", "_", "").Replace(strings.ToLower(marker))
	mod := ""
	for _, prefix := range []string{"ultra", "super"} {
		if strings.HasPrefix(word, prefix) {
			mod = prefix
		}
	}
	isSped := strings.Contains(word, "sped") || strings.Contains(word, "speed") ||
		strings.Contains(word, "nightcore")

	var kind string
	switch {
	case isSped && mod == "super":
		kind = store.VariantSuperSpedUp
	case isSped:
		kind = store.VariantSpedUp
	case mod == "ultra":
		kind = store.VariantUltraSlowed
	case mod == "super":
		kind = store.VariantSuperSlowed
	default:
		kind = store.VariantSlowed
	}

	base = strings.TrimSpace(strings.Trim(base, "-–—: [](),"))
	if base == "" {
		return VariantMatch{}, false
	}
	return VariantMatch{Kind: kind, Reverb: strings.Contains(strings.ToLower(raw), "reverb"), Base: base}, true
}

func stripBrackets(s string) string {
	return strings.TrimSpace(bracketRE.ReplaceAllStringFunc(s, func(m string) string {
		if hasMarker(m) {
			return ""
		}
		return m
	}))
}

// pendingVariant is a probed file whose title announced a slowed or sped-up
// edit, waiting to be attached to the track it was made from.
type pendingVariant struct {
	probe *Probe
	match VariantMatch
}

// verifyThreshold is the correlation a variant must reach to be treated as
// derivable from the base recording. A fan edit with its own mix and reverb
// lands well below it and is kept as a separate file.
const verifyThreshold = 0.7

// linkVariants attaches each variant to its base track. It returns the files it
// could not link, which the caller ingests as ordinary tracks instead.
func linkVariants(ctx context.Context, st *store.Store, items []pendingVariant,
	log func(string, ...any)) (linked, kept int, redundant []string, leftover []*Probe) {

	for _, it := range items {
		p := it.probe
		cands, err := st.FindByTitle(ctx, it.match.Base)
		if err != nil || len(cands) == 0 {
			leftover = append(leftover, p)
			continue
		}

		var best *store.Track
		bestScore := 0.0
		for i := range cands {
			c := &cands[i]
			if c.DurationMs <= 0 || p.DurationMs <= 0 {
				continue
			}
			rate := float64(c.DurationMs) / float64(p.DurationMs)
			if rate < 0.6 || rate > 1.6 {
				continue
			}
			// A matching artist is a much stronger hint than a matching title.
			score := 1.0
			if c.Artist != "" && strings.EqualFold(c.Artist, p.Artist) {
				score = 2.0
			}
			if score > bestScore {
				bestScore, best = score, c
			}
		}
		if best == nil {
			leftover = append(leftover, p)
			continue
		}

		masterPath, masterRate := "", 0
		for _, r := range best.Renditions {
			if r.IsMaster {
				masterPath, masterRate = r.Path, r.SampleRate
				break
			}
		}
		if masterPath == "" {
			leftover = append(leftover, p)
			continue
		}

		rate := float64(best.DurationMs) / float64(p.DurationMs)
		corr, verr := Verify(ctx, masterPath, best.DurationMs, masterRate, p.Path, p.DurationMs, p.SampleRate, rate)
		if verr != nil {
			log("проверка %s: %v", p.Path, verr)
		}
		verified := corr >= verifyThreshold

		v := store.Variant{
			TrackID:          best.ID,
			Kind:             it.match.Kind,
			Rate:             math.Round(rate*1000) / 1000,
			PitchFollowsRate: true,
			Reverb:           it.match.Reverb,
			Verified:         verified,
		}
		if verified {
			redundant = append(redundant, p.Path)
		} else {
			// Not derivable: the file stays and is referenced by the variant.
			// Its speed is already baked into the audio, so it plays at 1x;
			// recording the measured rate here would slow it down twice.
			v.Path = p.Path
			v.Rate = 1
			kept++
		}
		if err := st.PutVariant(ctx, v); err != nil {
			log("вариант %s: %v", p.Path, err)
			continue
		}
		if verified {
			linked++
		}
	}
	return linked, kept, redundant, leftover
}

// its tempo is restored. A genuine slowed edit correlates near 1; a fan edit
// with its own mix and reverb does not, and must be kept as its own file.
func Verify(ctx context.Context, basePath string, baseDurMs int64, baseRate int, varPath string, varDurMs int64, varRate int, rate float64) (float64, error) {
	if rate <= 0 || baseDurMs <= 0 || varDurMs <= 0 {
		return 0, nil
	}
	const (
		sampleRate = 8000
		windowSec  = 4.0
	)
	baseSec := float64(baseDurMs) / 1000
	varSec := float64(varDurMs) / 1000

	base, err := pcmWindow(ctx, basePath, baseRate, baseSec/2-windowSec/2, windowSec, sampleRate, 1)
	if err != nil {
		return 0, err
	}
	// In the variant's own timeline the same musical passage is 1/rate longer,
	// and playing it back at 1/rate restores the base tempo.
	variant, err := pcmWindow(ctx, varPath, varRate, varSec/2-windowSec/rate/2, windowSec/rate, sampleRate, 1/rate)
	if err != nil {
		return 0, err
	}
	if len(base) == 0 || len(variant) == 0 {
		return 0, nil
	}
	return bestCorrelation(base, variant, sampleRate/20), nil
}

// pcmWindow decodes a mono window via ffmpeg. startSec and durSec are in the
// file's own timeline; speed > 1 plays the result faster, which is how a
// slowed variant is brought back to base tempo. nativeRate is the file's own
// sample rate: asetrate works on that, not on the rate we want out.
func pcmWindow(ctx context.Context, path string, nativeRate int, startSec, durSec float64, sampleRate int, speed float64) ([]float64, error) {
	if startSec < 0 {
		startSec = 0
	}
	if nativeRate <= 0 {
		nativeRate = sampleRate
	}
	args := []string{"-v", "error", "-ss", strconv.FormatFloat(startSec, 'f', 3, 64),
		"-i", path, "-t", strconv.FormatFloat(durSec, 'f', 3, 64)}
	if speed != 1 {
		args = append(args, "-af", "asetrate="+strconv.Itoa(nativeRate)+"*"+
			strconv.FormatFloat(speed, 'f', 6, 64)+",aresample="+strconv.Itoa(sampleRate))
	}
	args = append(args, "-ac", "1", "-ar", strconv.Itoa(sampleRate), "-f", "s16le", "-")

	ctx, cancel := context.WithTimeout(ctx, probeTimeout)
	defer cancel()
	out, err := exec.CommandContext(ctx, "ffmpeg", args...).Output()
	if err != nil {
		return nil, err
	}
	samples := make([]float64, len(out)/2)
	for i := range samples {
		samples[i] = float64(int16(binary.LittleEndian.Uint16(out[i*2:]))) / 32768
	}
	return samples, nil
}

// bestCorrelation returns the peak normalized cross-correlation over a range
// of lags, coarse first so the search stays cheap.
func bestCorrelation(a, b []float64, maxLag int) float64 {
	normalize(a)
	normalize(b)

	lag := 0
	best := 0.0
	for l := -maxLag; l <= maxLag; l += 8 {
		if v := corrAt(a, b, l); v > best {
			best, lag = v, l
		}
	}
	for l := lag - 8; l <= lag+8; l++ {
		if v := corrAt(a, b, l); v > best {
			best = v
		}
	}
	return best
}

func corrAt(a, b []float64, lag int) float64 {
	i, j := 0, lag
	if lag < 0 {
		i, j = -lag, 0
	}
	n := len(a) - i
	if m := len(b) - j; m < n {
		n = m
	}
	if n <= 0 {
		return 0
	}
	sum := 0.0
	for k := 0; k < n; k++ {
		sum += a[i+k] * b[j+k]
	}
	return sum / float64(n)
}

func normalize(s []float64) {
	if len(s) == 0 {
		return
	}
	mean := 0.0
	for _, v := range s {
		mean += v
	}
	mean /= float64(len(s))
	energy := 0.0
	for i := range s {
		s[i] -= mean
		energy += s[i] * s[i]
	}
	if energy == 0 {
		return
	}
	scale := 1 / math.Sqrt(energy/float64(len(s)))
	for i := range s {
		s[i] *= scale
	}
}
