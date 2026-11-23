// Package store holds the FlashMusic catalog: tracks, their renditions and
// the slowed/sped-up variants derived from them.
package store

import (
	"context"
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
	"errors"
	"fmt"
	"strings"
	"time"

	_ "modernc.org/sqlite"
)

// ErrNotFound is returned when a track id is not in the catalog.
var ErrNotFound = errors.New("store: track not found")

// TrackID derives the stable catalog id from a Chromaprint fingerprint. The
// fingerprint, not the title or the file, is what makes a track the same track.
func TrackID(fingerprint string) string {
	sum := sha256.Sum256([]byte(fingerprint))
	return hex.EncodeToString(sum[:])[:16]
}

// Variant kinds. Rate is the playback multiplier against the normal mix: 1.0
// is the record as released, 0.85 is "slowed", 1.25 is "sped up".
const (
	VariantUltraSlowed = "ultra_slowed"
	VariantSuperSlowed = "super_slowed"
	VariantSlowed      = "slowed"
	VariantNormal      = "normal"
	VariantSpedUp      = "sped_up"
	VariantSuperSpedUp = "super_sped_up"
)

// Track is one piece of music, identified by its acoustic fingerprint rather
// than by a file: two uploads of the same song collapse into one track.
type Track struct {
	ID          string `json:"id"`
	Title       string `json:"title"`
	Artist      string `json:"artist"`
	Album       string `json:"album"`
	DurationMs  int64  `json:"durationMs"`
	Year        int    `json:"year"`
	TrackNo     int    `json:"trackNo"`
	DiscNo      int    `json:"discNo"`
	Fingerprint string `json:"-"`
	Rev         int64  `json:"rev"`
	AddedAt     int64  `json:"addedAt"`
	UpdatedAt   int64  `json:"updatedAt"`

	Renditions []Rendition `json:"renditions,omitempty"`
	Variants   []Variant   `json:"variants,omitempty"`
}

// Rendition is one stored encoding of a track. Exactly one per track is the
// master: the best-quality file the server transcodes the Opus tiers from.
type Rendition struct {
	ID         int64  `json:"id,omitempty"`
	TrackID    string `json:"trackId"`
	Codec      string `json:"codec"`
	SampleRate int    `json:"sampleRate"`
	Bits       int    `json:"bits"`
	Bitrate    int    `json:"bitrate"`
	Bytes      int64  `json:"bytes"`
	Path       string `json:"path"`
	Source     string `json:"source"`
	IsMaster   bool   `json:"isMaster"`
}

// Variant is a slowed or sped-up rendering of a track. It normally carries no
// file of its own: the player applies Rate itself. Path is set only when the
// variant turned out not to be derivable (a fan edit with its own mix).
type Variant struct {
	TrackID          string  `json:"trackId"`
	Kind             string  `json:"kind"`
	Rate             float64 `json:"rate"`
	PitchFollowsRate bool    `json:"pitchFollowsRate"`
	Reverb           bool    `json:"reverb"`
	Verified         bool    `json:"verified"`
	Path             string  `json:"path,omitempty"`
}

// Manifest is the delta the app syncs against: every track whose Rev is above
// the one the client already has, plus the ids deleted since. Now is the value
// to pass as since on the next sync.
type Manifest struct {
	Now     int64    `json:"now"`
	Tracks  []Track  `json:"tracks"`
	Deleted []string `json:"deleted,omitempty"`
	// More says the page was cut short and the client should ask again with the
	// Now it just received. Absent on the last page, so an older client that
	// ignores it still syncs correctly in one go.
	More bool `json:"more,omitempty"`
}

// Store is the catalog database.
type Store struct {
	db *sql.DB
}

const schema = `
CREATE TABLE IF NOT EXISTS meta (
	key   TEXT PRIMARY KEY,
	value INTEGER NOT NULL
);
INSERT OR IGNORE INTO meta (key, value) VALUES ('next_rev', 0);

CREATE TABLE IF NOT EXISTS tracks (
	id          TEXT PRIMARY KEY,
	title       TEXT NOT NULL,
	artist      TEXT NOT NULL DEFAULT '',
	album       TEXT NOT NULL DEFAULT '',
	duration_ms INTEGER NOT NULL DEFAULT 0,
	year        INTEGER NOT NULL DEFAULT 0,
	track_no    INTEGER NOT NULL DEFAULT 0,
	disc_no     INTEGER NOT NULL DEFAULT 0,
	fingerprint TEXT NOT NULL UNIQUE,
	rev         INTEGER NOT NULL,
	added_at    INTEGER NOT NULL,
	updated_at  INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS tracks_rev ON tracks(rev);
CREATE INDEX IF NOT EXISTS tracks_title_lower ON tracks(lower(title));

CREATE TABLE IF NOT EXISTS renditions (
	id          INTEGER PRIMARY KEY AUTOINCREMENT,
	track_id    TEXT NOT NULL REFERENCES tracks(id) ON DELETE CASCADE,
	codec       TEXT NOT NULL,
	sample_rate INTEGER NOT NULL DEFAULT 0,
	bits        INTEGER NOT NULL DEFAULT 0,
	bitrate     INTEGER NOT NULL DEFAULT 0,
	bytes       INTEGER NOT NULL DEFAULT 0,
	path        TEXT NOT NULL,
	source      TEXT NOT NULL DEFAULT 'upload',
	is_master   INTEGER NOT NULL DEFAULT 0,
	UNIQUE(track_id, path)
);

CREATE TABLE IF NOT EXISTS variants (
	track_id          TEXT NOT NULL REFERENCES tracks(id) ON DELETE CASCADE,
	kind              TEXT NOT NULL,
	rate              REAL NOT NULL,
	pitch_follows_rate INTEGER NOT NULL DEFAULT 1,
	reverb            INTEGER NOT NULL DEFAULT 0,
	verified          INTEGER NOT NULL DEFAULT 0,
	path              TEXT NOT NULL DEFAULT '',
	PRIMARY KEY (track_id, kind)
);

CREATE TABLE IF NOT EXISTS tombstones (
	track_id TEXT PRIMARY KEY,
	rev      INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS scans (
	path  TEXT PRIMARY KEY,
	size  INTEGER NOT NULL,
	mtime INTEGER NOT NULL,
	probe TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS plays (
	track_id    TEXT PRIMARY KEY REFERENCES tracks(id) ON DELETE CASCADE,
	count       INTEGER NOT NULL DEFAULT 0,
	last_played INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS plays_count ON plays(count DESC);
`

// Open opens (and creates when missing) the catalog at path. An empty path
// gives an in-memory database, which is what the tests use.
func Open(path string) (*Store, error) {
	dsn := "file::memory:"
	if path != "" {
		dsn = "file:" + path
	}
	dsn += "?_pragma=journal_mode(WAL)&_pragma=busy_timeout(5000)&_pragma=foreign_keys(1)&_pragma=synchronous(NORMAL)"

	db, err := sql.Open("sqlite", dsn)
	if err != nil {
		return nil, fmt.Errorf("store: open %s: %w", path, err)
	}
	// One connection: SQLite writes serialize anyway and this keeps the
	// in-memory case from losing the schema between pooled connections.
	db.SetMaxOpenConns(1)
	if _, err := db.Exec(schema); err != nil {
		db.Close()
		return nil, fmt.Errorf("store: schema: %w", err)
	}
	return &Store{db: db}, nil
}

// Close closes the database.
func (s *Store) Close() error { return s.db.Close() }

func nextRev(ctx context.Context, tx *sql.Tx) (int64, error) {
	var rev int64
	err := tx.QueryRowContext(ctx,
		`UPDATE meta SET value = value + 1 WHERE key = 'next_rev' RETURNING value`).Scan(&rev)
	return rev, err
}

// Ingest writes one track and its renditions. The track is matched by
// fingerprint, so re-ingesting the same song from a second file adds a
// rendition instead of a second track. It reports whether anything actually
// changed; an unchanged re-ingest costs nothing in the manifest.
func (s *Store) Ingest(ctx context.Context, t Track, r []Rendition) (created, changed bool, err error) {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return false, false, err
	}
	defer tx.Rollback()

	now := time.Now().Unix()

	var existingRev int64
	err = tx.QueryRowContext(ctx, `SELECT rev FROM tracks WHERE fingerprint = ?`, t.Fingerprint).Scan(&existingRev)
	switch {
	case errors.Is(err, sql.ErrNoRows):
		created = true
		rev, err := nextRev(ctx, tx)
		if err != nil {
			return false, false, err
		}
		t.ID = TrackID(t.Fingerprint)
		t.Rev = rev
		t.AddedAt = now
		t.UpdatedAt = now
		if _, err := tx.ExecContext(ctx, `
			INSERT INTO tracks (id, title, artist, album, duration_ms, year, track_no, disc_no,
			                    fingerprint, rev, added_at, updated_at)
			VALUES (?,?,?,?,?,?,?,?,?,?,?,?)`,
			t.ID, t.Title, t.Artist, t.Album, t.DurationMs, t.Year, t.TrackNo, t.DiscNo,
			t.Fingerprint, rev, now, now); err != nil {
			return false, false, err
		}
	case err != nil:
		return false, false, err
	default:
		if err := tx.QueryRowContext(ctx, `SELECT id FROM tracks WHERE fingerprint = ?`, t.Fingerprint).Scan(&t.ID); err != nil {
			return false, false, err
		}
	}

	changed = false
	var priorMaster int64
	// ErrNoRows is the ordinary "this track has no master yet" case, not a
	// failure. Ignoring the error outright made any real one look like that
	// case, which set changed=true and bumped the revision on every scan.
	err = tx.QueryRowContext(ctx, `SELECT id FROM renditions WHERE track_id = ? AND is_master = 1`, t.ID).Scan(&priorMaster)
	if err != nil && !errors.Is(err, sql.ErrNoRows) {
		return false, false, err
	}

	for i := range r {
		r[i].TrackID = t.ID
		res, err := tx.ExecContext(ctx, `
			INSERT INTO renditions (track_id, codec, sample_rate, bits, bitrate, bytes, path, source, is_master)
			VALUES (?,?,?,?,?,?,?,?,0)
			ON CONFLICT(track_id, path) DO UPDATE SET
				codec = excluded.codec, sample_rate = excluded.sample_rate, bits = excluded.bits,
				bitrate = excluded.bitrate, bytes = excluded.bytes, source = excluded.source
			WHERE renditions.bytes <> excluded.bytes OR renditions.bitrate <> excluded.bitrate
			   OR renditions.codec <> excluded.codec`,
			r[i].TrackID, r[i].Codec, r[i].SampleRate, r[i].Bits, r[i].Bitrate,
			r[i].Bytes, r[i].Path, r[i].Source)
		if err != nil {
			return false, false, err
		}
		if n, _ := res.RowsAffected(); n > 0 {
			changed = true
		}
	}

	// The master is always the best-quality file on hand, so a better upload
	// demotes the old one instead of the track ending up with two.
	master, err := normalizeMaster(ctx, tx, t.ID)
	if err != nil {
		return false, false, err
	}
	if master != priorMaster {
		changed = true
	}

	if existingRev != 0 {
		rev, err := nextRev(ctx, tx)
		if err != nil {
			return false, false, err
		}
		res, err := tx.ExecContext(ctx, `
			UPDATE tracks SET title=?, artist=?, album=?, duration_ms=?, year=?, track_no=?,
			                  disc_no=?, rev=?, updated_at=?
			WHERE id=? AND (title<>? OR artist<>? OR album<>? OR duration_ms<>? OR year<>?
			                OR track_no<>? OR disc_no<>?)`,
			t.Title, t.Artist, t.Album, t.DurationMs, t.Year, t.TrackNo, t.DiscNo, rev, now,
			t.ID, t.Title, t.Artist, t.Album, t.DurationMs, t.Year, t.TrackNo, t.DiscNo)
		if err != nil {
			return false, false, err
		}
		meta, _ := res.RowsAffected()
		if meta == 0 && changed {
			// Only the files moved, not the tags: still bump the revision.
			if _, err := tx.ExecContext(ctx, `UPDATE tracks SET rev=?, updated_at=? WHERE id=?`, rev, now, t.ID); err != nil {
				return false, false, err
			}
		}
		changed = changed || meta > 0
	} else {
		changed = true
	}

	if err := tx.Commit(); err != nil {
		return false, false, err
	}
	return created, changed, nil
}

// PutVariant attaches a slowed or sped-up rendition to a track. It bumps the
// track revision only when the variant actually changed, so a re-scan that
// finds the same edit does not chase every client into a resync.
func (s *Store) PutVariant(ctx context.Context, v Variant) error {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()

	res, err := tx.ExecContext(ctx, `
		INSERT INTO variants (track_id, kind, rate, pitch_follows_rate, reverb, verified, path)
		VALUES (?,?,?,?,?,?,?)
		ON CONFLICT(track_id, kind) DO UPDATE SET
			rate = excluded.rate, pitch_follows_rate = excluded.pitch_follows_rate,
			reverb = excluded.reverb, verified = excluded.verified, path = excluded.path
		WHERE variants.rate <> excluded.rate OR variants.verified <> excluded.verified
		   OR variants.path <> excluded.path OR variants.reverb <> excluded.reverb`,
		v.TrackID, v.Kind, v.Rate, boolInt(v.PitchFollowsRate), boolInt(v.Reverb), boolInt(v.Verified), v.Path)
	if err != nil {
		return err
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return nil
	}

	rev, err := nextRev(ctx, tx)
	if err != nil {
		return err
	}
	if _, err := tx.ExecContext(ctx, `UPDATE tracks SET rev=?, updated_at=? WHERE id=?`,
		rev, time.Now().Unix(), v.TrackID); err != nil {
		return err
	}
	return tx.Commit()
}

// Track returns one track with its renditions and variants.
func (s *Store) Track(ctx context.Context, id string) (*Track, error) {
	t := &Track{}
	err := s.db.QueryRowContext(ctx, `
		SELECT id, title, artist, album, duration_ms, year, track_no, disc_no,
		       fingerprint, rev, added_at, updated_at
		FROM tracks WHERE id = ?`, id).Scan(
		&t.ID, &t.Title, &t.Artist, &t.Album, &t.DurationMs, &t.Year, &t.TrackNo, &t.DiscNo,
		&t.Fingerprint, &t.Rev, &t.AddedAt, &t.UpdatedAt)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, err
	}
	if err := s.attach(ctx, s.db, []*Track{t}); err != nil {
		return nil, err
	}
	return t, nil
}

// Manifest returns everything that changed after the given revision.
func (s *Store) Manifest(ctx context.Context, since int64) (*Manifest, error) {
	return s.ManifestPage(ctx, since, 0)
}

// ManifestPage returns the changes after since, at most limit tracks.
//
// The cursor is the page's own highest revision, so a client walks a large
// library in as many calls as it takes without ever seeing a track twice or
// skipping one: revisions are assigned monotonically at write time. Tombstones
// are cut at the same revision, which is what keeps a deletion from arriving
// before the page that still lists the track.
//
// A limit of zero returns everything in one answer, which is what a small
// library and every existing test want.
func (s *Store) ManifestPage(ctx context.Context, since int64, limit int) (*Manifest, error) {
	// One snapshot for the whole page. The cursor, the tracks and the
	// tombstones have to agree: with the revision read first and the rows after
	// it, a track committed in between landed on both sides of the cursor and
	// arrived twice on the next sync.
	tx, err := s.db.BeginTx(ctx, &sql.TxOptions{ReadOnly: true})
	if err != nil {
		return nil, err
	}
	defer tx.Rollback()

	var maxRev int64
	if err := tx.QueryRowContext(ctx, `SELECT value FROM meta WHERE key = 'next_rev'`).Scan(&maxRev); err != nil {
		return nil, err
	}

	query := `
		SELECT id, title, artist, album, duration_ms, year, track_no, disc_no,
		       fingerprint, rev, added_at, updated_at
		FROM tracks WHERE rev > ? ORDER BY rev`
	args := []any{since}
	if limit > 0 {
		query += ` LIMIT ?`
		args = append(args, limit)
	}

	rows, err := tx.QueryContext(ctx, query, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var ptrs []*Track
	for rows.Next() {
		var t Track
		if err := rows.Scan(&t.ID, &t.Title, &t.Artist, &t.Album, &t.DurationMs, &t.Year,
			&t.TrackNo, &t.DiscNo, &t.Fingerprint, &t.Rev, &t.AddedAt, &t.UpdatedAt); err != nil {
			return nil, err
		}
		ptrs = append(ptrs, &t)
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}
	rows.Close()
	if err := s.attach(ctx, tx, ptrs); err != nil {
		return nil, err
	}

	// The cursor covers what this page actually delivered.
	cursor := maxRev
	more := false
	if limit > 0 && len(ptrs) == limit {
		cursor = ptrs[len(ptrs)-1].Rev
		more = true
	}

	m := &Manifest{Now: cursor, Tracks: []Track{}, More: more}
	for _, p := range ptrs {
		m.Tracks = append(m.Tracks, *p)
	}

	trows, err := tx.QueryContext(ctx,
		`SELECT track_id FROM tombstones WHERE rev > ? AND rev <= ? ORDER BY rev`, since, cursor)
	if err != nil {
		return nil, err
	}
	defer trows.Close()
	for trows.Next() {
		var id string
		if err := trows.Scan(&id); err != nil {
			return nil, err
		}
		m.Deleted = append(m.Deleted, id)
	}
	return m, trows.Err()
}

// DeleteTrack removes a track and leaves a tombstone so clients that synced it
// know to drop it.
func (s *Store) DeleteTrack(ctx context.Context, id string) error {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	rev, err := nextRev(ctx, tx)
	if err != nil {
		return err
	}
	res, err := tx.ExecContext(ctx, `DELETE FROM tracks WHERE id = ?`, id)
	if err != nil {
		return err
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return ErrNotFound
	}
	if _, err := tx.ExecContext(ctx, `INSERT OR REPLACE INTO tombstones (track_id, rev) VALUES (?,?)`, id, rev); err != nil {
		return err
	}
	return tx.Commit()
}

// Stats reports catalog size, used by the scanner to report what changed.
func (s *Store) Stats(ctx context.Context) (TrackStats, error) {
	var st TrackStats
	if err := s.db.QueryRowContext(ctx, `SELECT COUNT(*) FROM tracks`).Scan(&st.Tracks); err != nil {
		return st, err
	}
	if err := s.db.QueryRowContext(ctx, `SELECT COUNT(*) FROM renditions`).Scan(&st.Renditions); err != nil {
		return st, err
	}
	err := s.db.QueryRowContext(ctx, `SELECT COUNT(*) FROM variants`).Scan(&st.Variants)
	return st, err
}

// TrackStats is a catalog census.
type TrackStats struct {
	Tracks     int `json:"tracks"`
	Renditions int `json:"renditions"`
	Variants   int `json:"variants"`
}

// Search returns tracks whose title or artist contains q.
func (s *Store) Search(ctx context.Context, q string, limit int) ([]Track, error) {
	if limit <= 0 || limit > 200 {
		limit = 50
	}
	like := "%" + escapeLike(strings.ToLower(q)) + "%"
	rows, err := s.db.QueryContext(ctx, `
		SELECT id, title, artist, album, duration_ms, year, track_no, disc_no,
		       fingerprint, rev, added_at, updated_at
		FROM tracks
		WHERE lower(title) LIKE ? ESCAPE '\' OR lower(artist) LIKE ? ESCAPE '\'
		   OR lower(album) LIKE ? ESCAPE '\'
		ORDER BY lower(title) LIMIT ?`, like, like, like, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var ptrs []*Track
	for rows.Next() {
		var t Track
		if err := rows.Scan(&t.ID, &t.Title, &t.Artist, &t.Album, &t.DurationMs, &t.Year,
			&t.TrackNo, &t.DiscNo, &t.Fingerprint, &t.Rev, &t.AddedAt, &t.UpdatedAt); err != nil {
			return nil, err
		}
		ptrs = append(ptrs, &t)
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}
	rows.Close()
	if err := s.attach(ctx, s.db, ptrs); err != nil {
		return nil, err
	}
	out := make([]Track, len(ptrs))
	for i, p := range ptrs {
		out[i] = *p
	}
	return out, nil
}

// FindByTitle returns every track whose title matches case-insensitively.
// Used to attach a slowed variant to the track it was made from.
func (s *Store) FindByTitle(ctx context.Context, title string) ([]Track, error) {
	rows, err := s.db.QueryContext(ctx, `
		SELECT id, title, artist, album, duration_ms, year, track_no, disc_no,
		       fingerprint, rev, added_at, updated_at
		FROM tracks WHERE lower(title) = lower(?)`, title)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var ptrs []*Track
	for rows.Next() {
		var t Track
		if err := rows.Scan(&t.ID, &t.Title, &t.Artist, &t.Album, &t.DurationMs, &t.Year,
			&t.TrackNo, &t.DiscNo, &t.Fingerprint, &t.Rev, &t.AddedAt, &t.UpdatedAt); err != nil {
			return nil, err
		}
		ptrs = append(ptrs, &t)
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}
	rows.Close()
	if err := s.attach(ctx, s.db, ptrs); err != nil {
		return nil, err
	}
	out := make([]Track, len(ptrs))
	for i, p := range ptrs {
		out[i] = *p
	}
	return out, nil
}

// PlayCount is one row of the listening leaderboard.
type PlayCount struct {
	TrackID string `json:"trackId"`
	Count   int64  `json:"count"`
}

// Play records one listen. It deliberately does not bump the track revision:
// how often something is played is not a change to the catalog.
func (s *Store) Play(ctx context.Context, id string) error {
	var exists int
	err := s.db.QueryRowContext(ctx, `SELECT 1 FROM tracks WHERE id = ?`, id).Scan(&exists)
	if errors.Is(err, sql.ErrNoRows) {
		return ErrNotFound
	} else if err != nil {
		return err
	}
	_, err = s.db.ExecContext(ctx, `
		INSERT INTO plays (track_id, count, last_played) VALUES (?, 1, ?)
		ON CONFLICT(track_id) DO UPDATE SET count = count + 1, last_played = excluded.last_played`,
		id, time.Now().Unix())
	return err
}

// TopPlayed returns the most played tracks. The first entries are what gets
// pinned in the Opus cache and never evicted.
func (s *Store) TopPlayed(ctx context.Context, limit int) ([]PlayCount, error) {
	if limit <= 0 {
		limit = 100
	}
	rows, err := s.db.QueryContext(ctx,
		`SELECT track_id, count FROM plays ORDER BY count DESC, last_played DESC LIMIT ?`, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []PlayCount{}
	for rows.Next() {
		var p PlayCount
		if err := rows.Scan(&p.TrackID, &p.Count); err != nil {
			return nil, err
		}
		out = append(out, p)
	}
	return out, rows.Err()
}

// MatchItem is one row a client is asking the catalogue to identify.
type MatchItem struct {
	Title  string `json:"title"`
	Artist string `json:"artist"`
}

// MatchResult names the track holding the recording an item describes. Only
// answers that matched are returned.
type MatchResult struct {
	Index      int    `json:"index"`
	TrackID    string `json:"trackId"`
	DurationMs int64  `json:"durationMs"`
}

// Match answers "do you have this recording?" for a batch of rows another
// catalogue returned.
//
// One request for a whole search result, not one per row: the caller is a
// search screen that draws a badge on each row it recognises, and a round trip
// per row would cost more than the search it decorates.
//
// Titles are compared case-insensitively and the artist is required only when
// the caller states one, because a row from another catalogue may name the
// artist differently ("feat.", a different spelling) while naming the song
// exactly. A match on title alone is the deliberate trade: a wrong badge is a
// hint that the row can be played from here, and playback still asks the real
// question by track id.
func (s *Store) Match(ctx context.Context, items []MatchItem) ([]MatchResult, error) {
	if len(items) == 0 {
		return nil, nil
	}
	stmt, err := s.db.PrepareContext(ctx, `
		SELECT id, duration_ms FROM tracks
		WHERE lower(title) = lower(?)
		  AND (? = '' OR artist = '' OR lower(artist) = lower(?))
		ORDER BY (lower(artist) = lower(?)) DESC
		LIMIT 1`)
	if err != nil {
		return nil, err
	}
	defer stmt.Close()

	out := make([]MatchResult, 0, len(items))
	for i, item := range items {
		if item.Title == "" {
			continue
		}
		var res MatchResult
		err := stmt.QueryRowContext(ctx, item.Title, item.Artist, item.Artist, item.Artist).
			Scan(&res.TrackID, &res.DurationMs)
		if errors.Is(err, sql.ErrNoRows) {
			continue
		}
		if err != nil {
			return nil, err
		}
		res.Index = i
		out = append(out, res)
	}
	return out, nil
}

// ScanCache returns the cached probe for a file, valid only while its size and
// mtime are unchanged. Fingerprinting a large library costs minutes, so this is
// what makes a second scan cheap.
func (s *Store) ScanCache(ctx context.Context, path string, size, mtime int64) ([]byte, bool) {
	var probe string
	var gotSize, gotMtime int64
	err := s.db.QueryRowContext(ctx, `SELECT size, mtime, probe FROM scans WHERE path = ?`, path).
		Scan(&gotSize, &gotMtime, &probe)
	if err != nil || gotSize != size || gotMtime != mtime {
		return nil, false
	}
	return []byte(probe), true
}

// PutScan records a probe so the next scan can skip the file.
func (s *Store) PutScan(ctx context.Context, path string, size, mtime int64, probe []byte) error {
	_, err := s.db.ExecContext(ctx, `
		INSERT INTO scans (path, size, mtime, probe) VALUES (?,?,?,?)
		ON CONFLICT(path) DO UPDATE SET size = excluded.size, mtime = excluded.mtime, probe = excluded.probe`,
		path, size, mtime, string(probe))
	return err
}

// normalizeMaster flags the best rendition of a track as the master and
// returns its id.
func normalizeMaster(ctx context.Context, tx *sql.Tx, trackID string) (int64, error) {
	var master int64
	err := tx.QueryRowContext(ctx, `
		SELECT id FROM renditions WHERE track_id = ?
		ORDER BY (codec = 'flac') DESC, sample_rate DESC, bits DESC, bitrate DESC, bytes DESC
		LIMIT 1`, trackID).Scan(&master)
	if errors.Is(err, sql.ErrNoRows) {
		return 0, nil
	}
	if err != nil {
		return 0, err
	}
	if _, err := tx.ExecContext(ctx,
		`UPDATE renditions SET is_master = 0 WHERE track_id = ? AND id <> ? AND is_master <> 0`,
		trackID, master); err != nil {
		return 0, err
	}
	if _, err := tx.ExecContext(ctx,
		`UPDATE renditions SET is_master = 1 WHERE id = ? AND is_master = 0`, master); err != nil {
		return 0, err
	}
	return master, nil
}

func (s *Store) maxRev(ctx context.Context) (int64, error) {
	var rev int64
	err := s.db.QueryRowContext(ctx, `SELECT value FROM meta WHERE key = 'next_rev'`).Scan(&rev)
	return rev, err
}

// attach loads renditions and variants for a batch of tracks in two queries
// per chunk.
//
// Chunked rather than one `IN (?,?,…)` for the whole set: SQLite refuses a
// statement with more variables than its ceiling (32 766), and the manifest's
// first sync is the one caller that can arrive with a whole library in hand.
// querier is what [attach] needs: a pool or a transaction.
//
// The manifest reads through a transaction so its page, its cursor and its
// tombstones are one snapshot. Reading the revision before the rows let a track
// committed between the two queries fall below the cursor and arrive twice.
type querier interface {
	QueryContext(ctx context.Context, query string, args ...any) (*sql.Rows, error)
}

func (s *Store) attach(ctx context.Context, q querier, tracks []*Track) error {
	if len(tracks) == 0 {
		return nil
	}
	byID := make(map[string]*Track, len(tracks))
	for _, t := range tracks {
		byID[t.ID] = t
	}
	for start := 0; start < len(tracks); start += attachBatch {
		end := start + attachBatch
		if end > len(tracks) {
			end = len(tracks)
		}
		ids := make([]any, 0, end-start)
		for _, t := range tracks[start:end] {
			ids = append(ids, t.ID)
		}
		if err := s.attachChunk(ctx, q, byID, ids); err != nil {
			return err
		}
	}
	return nil
}

// attachChunk fills in one chunk of tracks. [ids] must not be empty.
func (s *Store) attachChunk(ctx context.Context, q querier, byID map[string]*Track, ids []any) error {
	ph := placeholders(len(ids))

	rows, err := q.QueryContext(ctx, `
		SELECT id, track_id, codec, sample_rate, bits, bitrate, bytes, path, source, is_master
		FROM renditions WHERE track_id IN (`+ph+`) ORDER BY is_master DESC, bytes DESC`, ids...)
	if err != nil {
		return err
	}
	defer rows.Close()
	for rows.Next() {
		var r Rendition
		if err := rows.Scan(&r.ID, &r.TrackID, &r.Codec, &r.SampleRate, &r.Bits,
			&r.Bitrate, &r.Bytes, &r.Path, &r.Source, &r.IsMaster); err != nil {
			return err
		}
		if t := byID[r.TrackID]; t != nil {
			t.Renditions = append(t.Renditions, r)
		}
	}
	if err := rows.Err(); err != nil {
		return err
	}
	rows.Close()

	vrows, err := q.QueryContext(ctx, `
		SELECT track_id, kind, rate, pitch_follows_rate, reverb, verified, path
		FROM variants WHERE track_id IN (`+ph+`)`, ids...)
	if err != nil {
		return err
	}
	defer vrows.Close()
	for vrows.Next() {
		var v Variant
		if err := vrows.Scan(&v.TrackID, &v.Kind, &v.Rate, &v.PitchFollowsRate,
			&v.Reverb, &v.Verified, &v.Path); err != nil {
			return err
		}
		if t := byID[v.TrackID]; t != nil {
			t.Variants = append(t.Variants, v)
		}
	}
	return vrows.Err()
}

func placeholders(n int) string {
	return strings.TrimSuffix(strings.Repeat("?,", n), ",")
}

// escapeLike makes a listener's text literal inside a LIKE pattern. '%' and '_'
// are wildcards of their own, so a query of "%" matched the whole library — and
// a search is a thing the app sends on every keystroke.
func escapeLike(s string) string {
	return strings.NewReplacer(`\`, `\\`, `%`, `\%`, `_`, `\_`).Replace(s)
}

// attchBatch is how many ids one `IN (?,?,…)` carries. Kept well under
// SQLite's 32 766-variable ceiling.
const attachBatch = 500

func boolInt(b bool) int {
	if b {
		return 1
	}
	return 0
}
