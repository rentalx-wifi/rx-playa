package api

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/rentalx-wifi/rx-playa/flashmusic/ingest"
	"github.com/rentalx-wifi/rx-playa/flashmusic/prefetch"
	"github.com/rentalx-wifi/rx-playa/flashmusic/store"
	"github.com/rentalx-wifi/rx-playa/streamer/cache"
	"github.com/rentalx-wifi/rx-playa/streamer/transcode"
)

func requireTools(t *testing.T) {
	t.Helper()
	for _, bin := range []string{"ffmpeg", "ffprobe", "fpcalc"} {
		if _, err := exec.LookPath(bin); err != nil {
			t.Skipf("%s not installed", bin)
		}
	}
}

func gen(t *testing.T, dir, name string, seed int, title, artist string) string {
	t.Helper()
	path := filepath.Join(dir, name)
	cmd := exec.Command("ffmpeg", "-v", "error", "-y",
		"-f", "lavfi", "-i", "anoisesrc=duration=12:color=pink:seed="+itoa(seed),
		"-metadata", "title="+title, "-metadata", "artist="+artist, path)
	if out, err := cmd.CombinedOutput(); err != nil {
		t.Fatalf("ffmpeg %s: %v: %s", name, err, out)
	}
	return path
}

func itoa(i int) string {
	if i == 0 {
		return "0"
	}
	var b []byte
	for i > 0 {
		b = append([]byte{byte('0' + i%10)}, b...)
		i /= 10
	}
	return string(b)
}

func newTestServer(t *testing.T) *Server {
	t.Helper()
	requireTools(t)
	lib := t.TempDir()
	gen(t, lib, "a.flac", 21, "Quiet Harbour", "Test Artist")
	gen(t, lib, "b.flac", 22, "Loud Meadow", "Other Artist")

	st, err := store.Open(filepath.Join(t.TempDir(), "cat.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { st.Close() })
	if _, err := ingest.Run(context.Background(), st, ingest.Options{Library: lib}); err != nil {
		t.Fatal(err)
	}
	return &Server{Store: st}
}

func get(t *testing.T, h http.Handler, url string) *httptest.ResponseRecorder {
	t.Helper()
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, url, nil))
	return rec
}

func TestManifestServesCatalog(t *testing.T) {
	srv := newTestServer(t)
	rec := get(t, srv.Handler(), "/api/v1/library/manifest?since=0")
	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d, body %s", rec.Code, rec.Body)
	}
	var m store.Manifest
	if err := json.Unmarshal(rec.Body.Bytes(), &m); err != nil {
		t.Fatal(err)
	}
	if len(m.Tracks) != 2 || m.Now == 0 {
		t.Fatalf("manifest = %d tracks, now %d, want 2 and a revision", len(m.Tracks), m.Now)
	}

	// Only an unchanged library comes back empty.
	rec = get(t, srv.Handler(), "/api/v1/library/manifest?since="+itoa(int(m.Now)))
	if len(rec.Body.Bytes()) == 0 {
		t.Fatal("empty body")
	}
	var delta store.Manifest
	if err := json.Unmarshal(rec.Body.Bytes(), &delta); err != nil {
		t.Fatal(err)
	}
	if len(delta.Tracks) != 0 {
		t.Fatalf("delta since now = %d tracks, want 0", len(delta.Tracks))
	}
}

func TestSearchFindsByTitleArtistAndAlbum(t *testing.T) {
	srv := newTestServer(t)
	for _, q := range []string{"quiet", "harbour", "test artist"} {
		rec := get(t, srv.Handler(), "/api/v1/search?q="+strings.ReplaceAll(q, " ", "+"))
		var out struct {
			Tracks []store.Track `json:"tracks"`
		}
		if err := json.Unmarshal(rec.Body.Bytes(), &out); err != nil {
			t.Fatal(err)
		}
		if len(out.Tracks) != 1 || out.Tracks[0].Title != "Quiet Harbour" {
			t.Fatalf("search %q returned %d tracks, want Quiet Harbour", q, len(out.Tracks))
		}
	}
}

func TestSearchByVariantPhrase(t *testing.T) {
	requireTools(t)
	lib := t.TempDir()
	base := gen(t, lib, "base.flac", 31, "Autumn Lights", "Test Artist")
	// A real slowed edit, so it becomes a variant of the base.
	out := filepath.Join(lib, "base-slowed.flac")
	cmd := exec.Command("ffmpeg", "-v", "error", "-y", "-i", base,
		"-af", "asetrate=48000*0.85,aresample=48000",
		"-metadata", "title=Autumn Lights (Slowed)", "-metadata", "artist=Test Artist", out)
	if b, err := cmd.CombinedOutput(); err != nil {
		t.Fatalf("ffmpeg slowed: %v: %s", err, b)
	}

	st, err := store.Open(filepath.Join(t.TempDir(), "cat.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer st.Close()
	res, err := ingest.Run(context.Background(), st, ingest.Options{Library: lib})
	if err != nil {
		t.Fatal(err)
	}
	if res.VariantsLinked != 1 {
		t.Fatalf("linked = %d, want 1", res.VariantsLinked)
	}

	srv := &Server{Store: st}
	rec := get(t, srv.Handler(), "/api/v1/search?q=autumn+lights+slowed")
	var res2 struct {
		Tracks []store.Track `json:"tracks"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &res2); err != nil {
		t.Fatal(err)
	}
	if len(res2.Tracks) != 1 || res2.Tracks[0].Title != "Autumn Lights" {
		t.Fatalf("search returned %d tracks, want the base track", len(res2.Tracks))
	}
	if len(res2.Tracks[0].Variants) != 1 {
		t.Fatalf("base track carries %d variants, want 1", len(res2.Tracks[0].Variants))
	}
}

func post(t *testing.T, h http.Handler, url, body string) *httptest.ResponseRecorder {
	t.Helper()
	rec := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodPost, url, strings.NewReader(body))
	h.ServeHTTP(rec, req)
	return rec
}

func TestPlayCountsUpwards(t *testing.T) {
	srv := newTestServer(t)
	h := srv.Handler()

	rec := get(t, h, "/api/v1/library/manifest?since=0")
	var m store.Manifest
	if err := json.Unmarshal(rec.Body.Bytes(), &m); err != nil {
		t.Fatal(err)
	}
	id := m.Tracks[0].ID

	for i := 0; i < 3; i++ {
		rec = post(t, h, "/api/v1/tracks/"+id+"/play", "")
		if rec.Code != http.StatusAccepted {
			t.Fatalf("play status = %d, body %s", rec.Code, rec.Body)
		}
	}
	top, err := srv.Store.TopPlayed(context.Background(), 10)
	if err != nil {
		t.Fatal(err)
	}
	if len(top) != 1 || top[0].TrackID != id || top[0].Count != 3 {
		t.Fatalf("top played = %+v, want one track with 3 plays", top)
	}

	// An unknown track is a 404, not a silent insert.
	if rec := post(t, h, "/api/v1/tracks/deadbeefdeadbeef/play", ""); rec.Code != http.StatusNotFound {
		t.Fatalf("unknown track status = %d, want 404", rec.Code)
	}
}

func TestPrefetchEncodesAhead(t *testing.T) {
	requireTools(t)
	lib := t.TempDir()
	gen(t, lib, "a.flac", 41, "Early Bird", "Test Artist")

	st, err := store.Open(filepath.Join(t.TempDir(), "cat.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer st.Close()
	if _, err := ingest.Run(context.Background(), st, ingest.Options{Library: lib}); err != nil {
		t.Fatal(err)
	}

	opusDir := filepath.Join(t.TempDir(), "opus")
	cfg := transcode.Default
	cfg.Bitrate = 192000
	c := cache.New(filepath.Join(opusDir, "192"), cfg, 1)
	queue := prefetch.New(map[int]prefetch.Encoder{192000: c}, nil)

	srv := &Server{Store: st, Opus: map[int]*cache.Cache{192000: c}, Prefetch: queue}
	h := srv.Handler()

	rec := get(t, h, "/api/v1/library/manifest?since=0")
	var m store.Manifest
	if err := json.Unmarshal(rec.Body.Bytes(), &m); err != nil {
		t.Fatal(err)
	}
	id := m.Tracks[0].ID

	rec = post(t, h, "/api/v1/prefetch", `{"trackIds":["`+id+`"],"tier":"default"}`)
	if rec.Code != http.StatusAccepted {
		t.Fatalf("prefetch status = %d, body %s", rec.Code, rec.Body)
	}

	// Nobody asked to stream it: the encode has to appear on its own.
	want := filepath.Join(opusDir, "192", id+".opus")
	deadline := time.Now().Add(10 * time.Second)
	for time.Now().Before(deadline) {
		if fi, err := os.Stat(want); err == nil && fi.Size() > 0 {
			return
		}
		time.Sleep(20 * time.Millisecond)
	}
	t.Fatalf("prefetch never produced %s", want)
}

func TestManifestPagesWithoutGapsOrRepeats(t *testing.T) {
	srv := newTestServer(t)
	h := srv.Handler()

	seen := map[string]int{}
	since, pages := int64(0), 0
	for {
		rec := get(t, h, "/api/v1/library/manifest?since="+strconv.FormatInt(since, 10)+"&limit=1")
		var m store.Manifest
		if err := json.Unmarshal(rec.Body.Bytes(), &m); err != nil {
			t.Fatal(err)
		}
		pages++
		if pages > 10 {
			t.Fatal("paging did not finish")
		}
		for _, tr := range m.Tracks {
			seen[tr.ID]++
		}
		since = m.Now
		if !m.More {
			break
		}
	}

	if len(seen) != 2 {
		t.Fatalf("saw %d tracks across pages, want 2", len(seen))
	}
	for id, n := range seen {
		if n != 1 {
			t.Fatalf("track %s arrived %d times, want exactly once", id, n)
		}
	}
	if pages != 3 {
		t.Fatalf("took %d pages, want 2 pages of one track plus a final empty one", pages)
	}
}

func TestMatchAnswersWhichRowsTheCatalogueHolds(t *testing.T) {
	srv := newTestServer(t)
	h := srv.Handler()

	body := `{"items":[` +
		`{"title":"quiet harbour","artist":"test artist"},` +
		`{"title":"Loud Meadow","artist":"Someone Else"},` +
		`{"title":"Loud Meadow","artist":""},` +
		`{"title":"Nothing Here","artist":"Test Artist"}]}`

	rec := post(t, h, "/api/v1/match", body)
	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d, body %s", rec.Code, rec.Body)
	}
	var out struct {
		Matches []store.MatchResult `json:"matches"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &out); err != nil {
		t.Fatal(err)
	}

	// Case does not matter, a wrong artist does not match, and a row with no
	// artist matches on the title alone.
	if len(out.Matches) != 2 {
		t.Fatalf("matches = %+v, want 2", out.Matches)
	}
	if out.Matches[0].Index != 0 || out.Matches[0].TrackID == "" {
		t.Fatalf("first match = %+v, want index 0 with a track id", out.Matches[0])
	}
	if out.Matches[1].Index != 2 {
		t.Fatalf("second match index = %d, want 2", out.Matches[1].Index)
	}
}

func TestStreamLosslessHonoursRange(t *testing.T) {
	srv := newTestServer(t)
	h := srv.Handler()

	rec := get(t, h, "/api/v1/library/manifest?since=0")
	var m store.Manifest
	if err := json.Unmarshal(rec.Body.Bytes(), &m); err != nil {
		t.Fatal(err)
	}
	id := m.Tracks[0].ID

	rec = get(t, h, "/api/v1/tracks/"+id+"/stream?tier=lossless")
	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d", rec.Code)
	}
	if rec.Header().Get("Accept-Ranges") != "bytes" {
		t.Fatalf("Accept-Ranges = %q, want bytes", rec.Header().Get("Accept-Ranges"))
	}

	req := httptest.NewRequest(http.MethodGet, "/api/v1/tracks/"+id+"/stream?tier=lossless", nil)
	req.Header.Set("Range", "bytes=0-1023")
	rec = httptest.NewRecorder()
	h.ServeHTTP(rec, req)
	if rec.Code != http.StatusPartialContent || rec.Body.Len() != 1024 {
		t.Fatalf("range: status %d, %d bytes, want 206 and 1024", rec.Code, rec.Body.Len())
	}

	rec = get(t, h, "/api/v1/tracks/"+id+"/stream?tier=nonsense")
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("unknown tier status = %d, want 400", rec.Code)
	}
}
