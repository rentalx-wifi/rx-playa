// Command rxstatic serves a directory over HTTP with no logic at all.
//
// It exists to measure one thing: what a FLAC or Opus stream costs when nothing
// has to be encoded. Go's net/http hands a *os.File to the socket with
// sendfile, so this is the cheapest path the machine has, and the difference
// against rxstream is the price of transcoding.
package main

import (
	"flag"
	"log"
	"net/http"
	"time"
)

func main() {
	addr := flag.String("addr", ":8080", "listen address")
	dir := flag.String("dir", ".", "directory to serve")
	flag.Parse()

	srv := &http.Server{
		Addr:              *addr,
		Handler:           http.FileServer(http.Dir(*dir)),
		ReadHeaderTimeout: 10 * time.Second,
		IdleTimeout:       120 * time.Second,
	}
	log.Printf("rxstatic: %s from %s", *addr, *dir)
	log.Fatal(srv.ListenAndServe())
}
