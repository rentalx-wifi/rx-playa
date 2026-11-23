package prefetch

import (
	"context"
	"sync"
	"testing"
	"time"
)

// fakeEncoder records what was encoded and can block so cancellation is
// observable.
type fakeEncoder struct {
	mu      sync.Mutex
	done    []string
	started chan string
	block   bool
}

func (f *fakeEncoder) setBlock(b bool) {
	f.mu.Lock()
	f.block = b
	f.mu.Unlock()
}

func (f *fakeEncoder) blocking() bool {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.block
}

func (f *fakeEncoder) Ensure(ctx context.Context, key, master string) (string, error) {
	if f.started != nil {
		select {
		case f.started <- key:
		default:
		}
	}
	if f.blocking() {
		<-ctx.Done()
		return "", ctx.Err()
	}
	f.mu.Lock()
	f.done = append(f.done, key)
	f.mu.Unlock()
	return key, nil
}

func (f *fakeEncoder) encoded() []string {
	f.mu.Lock()
	defer f.mu.Unlock()
	return append([]string(nil), f.done...)
}

func waitFor(t *testing.T, what string, cond func() bool) {
	t.Helper()
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		if cond() {
			return
		}
		time.Sleep(5 * time.Millisecond)
	}
	t.Fatalf("timed out waiting for %s", what)
}

func TestQueueEncodesThePlanInOrder(t *testing.T) {
	enc := &fakeEncoder{}
	q := New(map[int]Encoder{192000: enc}, nil)

	q.Set([]Job{
		{Key: "a", Master: "/m/a.flac", Bitrate: 192000},
		{Key: "b", Master: "/m/b.flac", Bitrate: 192000},
		{Key: "c", Master: "/m/c.flac", Bitrate: 192000},
	})

	waitFor(t, "the whole plan", func() bool { return q.Pending() == 0 && len(enc.encoded()) == 3 })
	got := enc.encoded()
	want := []string{"a", "b", "c"}
	for i := range want {
		if got[i] != want[i] {
			t.Fatalf("encoded %v, want %v", got, want)
		}
	}
}

func TestNewPlanCancelsTheOldOne(t *testing.T) {
	enc := &fakeEncoder{block: true, started: make(chan string, 4)}
	q := New(map[int]Encoder{192000: enc}, nil)

	q.Set([]Job{{Key: "first", Master: "/m/a.flac", Bitrate: 192000}})
	select {
	case key := <-enc.started:
		if key != "first" {
			t.Fatalf("started %q, want first", key)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("the first job never started")
	}

	// Replacing the plan must cancel the encode that is in flight; the queue
	// then goes on to the new one.
	enc.setBlock(false)
	q.Set([]Job{{Key: "second", Master: "/m/b.flac", Bitrate: 192000}})

	waitFor(t, "the new job", func() bool {
		for _, k := range enc.encoded() {
			if k == "second" {
				return true
			}
		}
		return false
	})
	for _, k := range enc.encoded() {
		if k == "first" {
			t.Fatal("the cancelled encode was recorded as done")
		}
	}
}

func TestEmptyPlanStopsTheQueue(t *testing.T) {
	enc := &fakeEncoder{}
	q := New(map[int]Encoder{192000: enc}, nil)

	q.Set([]Job{{Key: "a", Master: "/m/a.flac", Bitrate: 192000}})
	waitFor(t, "a", func() bool { return len(enc.encoded()) == 1 })

	q.Set(nil)
	if q.Pending() != 0 {
		t.Fatalf("pending = %d, want 0", q.Pending())
	}
}

func TestTierWithoutCacheIsSkipped(t *testing.T) {
	enc := &fakeEncoder{}
	q := New(map[int]Encoder{192000: enc}, nil)
	q.Set([]Job{{Key: "a", Master: "/m/a.flac", Bitrate: 44100}})
	waitFor(t, "the plan to drain", func() bool { return q.Pending() == 0 })
	if got := enc.encoded(); len(got) != 0 {
		t.Fatalf("encoded %v for a tier with no cache", got)
	}
}
