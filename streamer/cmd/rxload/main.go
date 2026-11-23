// Command rxload streams from rxstream over HTTP with a chosen number of
// concurrent listeners and reports what the server actually spent.
//
// The interesting number is cores divided by streams: it says how many
// listeners one box holds before the CPU is gone. Wall-clock throughput alone
// hides that, because a stream that is behind real time still looks fast.
package main

import (
	"flag"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"runtime"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

func main() {
	url := flag.String("url", "", "stream URL to pull")
	pid := flag.Int("pid", 0, "server pid to read CPU and memory from")
	conns := flag.Int("conns", 100, "concurrent listeners")
	seconds := flag.Int("seconds", 20, "how long to run")
	trackSeconds := flag.Float64("track-seconds", 0, "length of the track; enables streams-per-core")
	pattern := flag.String("pattern", "", "printf pattern using the client index, e.g. 'http://host/stream/f%04d.flac'; overrides -url")
	once := flag.Bool("once", false, "each listener fetches one file once instead of looping")
	disk := flag.String("disk", "", "block device in /proc/diskstats to report reads for, e.g. md0")
	flag.Parse()

	if *pattern != "" {
		*url = ""
	}
	if *url == "" && *pattern == "" {
		fmt.Fprintln(os.Stderr, "rxload: -url or -pattern is required")
		os.Exit(2)
	}

	before, err := sample(*pid)
	if err != nil {
		log.Fatalf("rxload: %v", err)
	}
	diskBefore := readDisk(*disk)

	var bytes, streams, fails atomic.Int64
	stop := make(chan struct{})
	var wg sync.WaitGroup
	client := &http.Client{Transport: &http.Transport{
		MaxIdleConnsPerHost: *conns,
		DisableCompression:  true,
	}}

	start := time.Now()
	for i := 0; i < *conns; i++ {
		wg.Add(1)
		go func(idx int) {
			defer wg.Done()
			target := *url
			if *pattern != "" {
				target = fmt.Sprintf(*pattern, idx)
			}
			buf := make([]byte, 32<<10)
			for {
				select {
				case <-stop:
					return
				default:
				}
				resp, err := client.Get(target)
				if err != nil {
					fails.Add(1)
					if *once {
						return
					}
					time.Sleep(50 * time.Millisecond)
					continue
				}
				if resp.StatusCode != http.StatusOK {
					io.Copy(io.Discard, resp.Body)
					resp.Body.Close()
					fails.Add(1)
					if *once {
						return
					}
					time.Sleep(50 * time.Millisecond)
					continue
				}
				for {
					n, err := resp.Body.Read(buf)
					if n > 0 {
						bytes.Add(int64(n))
					}
					if err != nil {
						break
					}
				}
				resp.Body.Close()
				streams.Add(1)
				if *once {
					return
				}
			}
		}(i)
	}

	if *once {
		wg.Wait()
	} else {
		time.Sleep(time.Duration(*seconds) * time.Second)
		close(stop)
		wg.Wait()
	}
	wall := time.Since(start)

	after, err := sample(*pid)
	if err != nil {
		log.Fatalf("rxload: %v", err)
	}

	cpu := after.cpuSeconds() - before.cpuSeconds()
	cores := cpu / wall.Seconds()

	fmt.Printf("listeners        %d\n", *conns)
	fmt.Printf("wall             %.1fs\n", wall.Seconds())
	fmt.Printf("streams finished %d\n", streams.Load())
	fmt.Printf("failed requests  %d\n", fails.Load())
	fmt.Printf("bytes pulled     %.1f MiB\n", float64(bytes.Load())/(1<<20))
	fmt.Printf("server CPU       %.2f core-seconds (%.2f cores of %d)\n",
		cpu, cores, numCPU())
	fmt.Printf("RSS before/after %d -> %d MiB\n", before.rssMiB(), after.rssMiB())
	fmt.Printf("RSS per listener %.2f MiB\n", float64(after.rss-before.rss)/float64(*conns)/1024)

	// Streams per core is the number that sizes a box: audio delivered per
	// CPU-second does not depend on how long the window happened to be.
	if *trackSeconds > 0 && cpu > 0 {
		audio := float64(streams.Load()) * *trackSeconds
		perCore := audio / cpu
		fmt.Printf("streams per core %.1f (real time)\n", perCore)
		fmt.Printf("capacity at 100%% %.0f concurrent streams\n", perCore*float64(numCPU()))
	}

	if d := readDisk(*disk); d != (diskStats{}) || diskBefore != (diskStats{}) {
		sectors := d.sectorsRead - diskBefore.sectorsRead
		ioMs := d.ioMs - diskBefore.ioMs
		mib := float64(sectors) * 512 / (1 << 20)
		fmt.Printf("disk %s reads %.1f MiB in %.1f s (%.1f MiB/s, %.0f%% busy)\n",
			*disk, mib, wall.Seconds(), mib/wall.Seconds(),
			float64(ioMs)/wall.Seconds()/10)
	}
}

type stats struct {
	utime, stime uint64 // clock ticks
	rss          uint64 // bytes
	hz           float64
}

// diskStats is the two fields of /proc/diskstats worth having: sectors read and
// the milliseconds the device spent servicing I/O.
type diskStats struct {
	sectorsRead uint64
	ioMs        uint64
}

func readDisk(name string) diskStats {
	if name == "" {
		return diskStats{}
	}
	raw, err := os.ReadFile("/proc/diskstats")
	if err != nil {
		return diskStats{}
	}
	for _, line := range strings.Split(string(raw), "\n") {
		f := strings.Fields(line)
		if len(f) < 13 || f[2] != name {
			continue
		}
		sect, _ := strconv.ParseUint(f[5], 10, 64)
		ms, _ := strconv.ParseUint(f[12], 10, 64)
		return diskStats{sectorsRead: sect, ioMs: ms}
	}
	return diskStats{}
}

func (s stats) cpuSeconds() float64 {
	return float64(s.utime+s.stime) / s.hz
}

func (s stats) rssMiB() uint64 { return s.rss / (1 << 20) }

func sample(pid int) (stats, error) {
	if pid == 0 {
		return stats{hz: 100}, nil
	}
	st := stats{hz: 100}
	raw, err := os.ReadFile(fmt.Sprintf("/proc/%d/stat", pid))
	if err != nil {
		return st, err
	}
	// Fields after the command, which is parenthesised and may contain spaces.
	s := string(raw)
	i := strings.LastIndex(s, ")")
	if i < 0 {
		return st, fmt.Errorf("unparsable stat")
	}
	f := strings.Fields(s[i+1:])
	if len(f) < 15 {
		return st, fmt.Errorf("short stat")
	}
	st.utime, _ = strconv.ParseUint(f[11], 10, 64)
	st.stime, _ = strconv.ParseUint(f[12], 10, 64)

	status, err := os.ReadFile(fmt.Sprintf("/proc/%d/status", pid))
	if err == nil {
		for _, line := range strings.Split(string(status), "\n") {
			if strings.HasPrefix(line, "VmRSS:") {
				v := strings.TrimSpace(strings.TrimPrefix(line, "VmRSS:"))
				v = strings.TrimSuffix(v, " kB")
				if kb, err := strconv.ParseUint(v, 10, 64); err == nil {
					st.rss = kb * 1024
				}
			}
		}
	}
	return st, nil
}

func numCPU() int { return runtime.NumCPU() }
