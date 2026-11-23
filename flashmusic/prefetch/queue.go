// Package prefetch encodes the tracks a listener is about to play, before the
// request for them arrives.
//
// One plan at a time: setting a new plan cancels the old one, so skipping
// through a queue costs a stopped encode rather than a wasted one.
package prefetch

import (
	"context"
	"errors"
	"log"
	"sync"
)

// Job is one track to encode.
type Job struct {
	Key     string // cache key, usually the track id
	Master  string // source file the encode reads
	Bitrate int
}

// Encoder turns a master file into a cached encode. A *cache.Cache is one.
type Encoder interface {
	Ensure(ctx context.Context, key, master string) (string, error)
}

// Queue runs the plan of upcoming encodes in the background.
type Queue struct {
	caches map[int]Encoder
	Log    *log.Logger

	mu      sync.Mutex
	plan    []Job
	running bool
	cancel  context.CancelFunc
}

// New returns a queue that encodes into the given caches, one tier per
// bitrate.
func New(caches map[int]Encoder, log *log.Logger) *Queue {
	return &Queue{caches: caches, Log: log}
}

// Set replaces the pending plan. An empty plan stops whatever is encoding.
func (q *Queue) Set(jobs []Job) {
	q.mu.Lock()
	defer q.mu.Unlock()

	if q.cancel != nil {
		q.cancel()
		q.cancel = nil
	}
	q.plan = jobs
	if q.running || len(jobs) == 0 {
		return
	}
	q.running = true
	go q.run()
}

// Pending reports how many tracks are still waiting.
func (q *Queue) Pending() int {
	q.mu.Lock()
	defer q.mu.Unlock()
	return len(q.plan)
}

func (q *Queue) run() {
	for {
		q.mu.Lock()
		if len(q.plan) == 0 {
			q.running = false
			q.cancel = nil
			q.mu.Unlock()
			return
		}
		job := q.plan[0]
		q.plan = q.plan[1:]
		ctx, cancel := context.WithCancel(context.Background())
		q.cancel = cancel
		q.mu.Unlock()

		var err error
		if c := q.caches[job.Bitrate]; c != nil {
			_, err = c.Ensure(ctx, job.Key, job.Master)
		}
		cancel()

		if err != nil && !errors.Is(err, context.Canceled) && q.Log != nil {
			q.Log.Printf("prefetch %s: %v", job.Key, err)
		}
	}
}
