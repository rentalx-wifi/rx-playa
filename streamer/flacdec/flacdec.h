#ifndef FLACDEC_H
#define FLACDEC_H

#include <stdint.h>

/*
 * A minimal libFLAC stream decoder for Go.
 *
 * The callback libFLAC offers is C-to-Go, which costs a cgo transition per
 * frame. Instead the write callback copies into a buffer owned here, and Go
 * reads that buffer after each process_single call -- one C call per frame
 * rather than two.
 */

typedef struct flacdec flacdec;

flacdec *flacdec_open(const char *path, char **err);
int flacdec_next(flacdec *d); /* 1 frame, 0 end of stream, -1 error */

/* Planar samples: channels consecutive blocks of blocksize int32s. The pointer
 * is valid until the next flacdec_next or flacdec_seek call. */
int32_t *flacdec_samples(flacdec *d);

unsigned flacdec_channels(flacdec *d);
unsigned flacdec_blocksize(flacdec *d);
unsigned flacdec_rate(flacdec *d);
unsigned flacdec_bits(flacdec *d);
uint64_t flacdec_total(flacdec *d);

int flacdec_seek(flacdec *d, uint64_t sample);
void flacdec_close(flacdec *d);

#endif
