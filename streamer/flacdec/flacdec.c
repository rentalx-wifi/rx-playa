#include <FLAC/stream_decoder.h>
#include <stdlib.h>
#include <string.h>

#include "flacdec.h"

struct flacdec {
	FLAC__StreamDecoder *dec;
	int32_t *buf;
	size_t cap; /* samples, not bytes */
	unsigned channels;
	unsigned blocksize;
	unsigned rate;
	unsigned bits;
	uint64_t total;
	int got_frame;
	int failed;
	int eof;
};

static FLAC__StreamDecoderWriteStatus on_write(const FLAC__StreamDecoder *decoder,
		const FLAC__Frame *frame, const FLAC__int32 *const buffer[], void *data)
{
	struct flacdec *d = data;
	unsigned ch = frame->header.channels;
	unsigned bs = frame->header.blocksize;
	if (ch == 0 || bs == 0)
		return FLAC__STREAM_DECODER_WRITE_STATUS_ABORT;

	size_t need = (size_t)ch * bs;
	if (d->cap < need) {
		int32_t *nb = realloc(d->buf, need * sizeof(int32_t));
		if (!nb)
			return FLAC__STREAM_DECODER_WRITE_STATUS_ABORT;
		d->buf = nb;
		d->cap = need;
	}
	for (unsigned c = 0; c < ch; c++)
		memcpy(d->buf + (size_t)c * bs, buffer[c], bs * sizeof(int32_t));

	d->channels = ch;
	d->blocksize = bs;
	d->got_frame = 1;
	return FLAC__STREAM_DECODER_WRITE_STATUS_CONTINUE;
}

static void on_meta(const FLAC__StreamDecoder *decoder,
		const FLAC__StreamMetadata *meta, void *data)
{
	struct flacdec *d = data;
	if (meta->type != FLAC__METADATA_TYPE_STREAMINFO)
		return;
	d->rate = meta->data.stream_info.sample_rate;
	d->channels = meta->data.stream_info.channels;
	d->bits = meta->data.stream_info.bits_per_sample;
	d->total = meta->data.stream_info.total_samples;
}

static void on_error(const FLAC__StreamDecoder *decoder,
		FLAC__StreamDecoderErrorStatus status, void *data)
{
	struct flacdec *d = data;
	d->failed = (int)status;
}

flacdec *flacdec_open(const char *path, char **err)
{
	struct flacdec *d = calloc(1, sizeof(*d));
	if (!d) {
		*err = (char *)"out of memory";
		return NULL;
	}
	d->dec = FLAC__stream_decoder_new();
	if (!d->dec) {
		free(d);
		*err = (char *)"FLAC__stream_decoder_new failed";
		return NULL;
	}
	/*
	 * Two defaults are wrong for a streaming server:
	 *
	 * md5_checking hashes every decoded sample so the decoder can verify the
	 * stream against its STREAMINFO digest at the end. Nothing here ever looks
	 * at that digest, and the hash runs over the whole PCM stream.
	 *
	 * Metadata is skipped either way, but responding only to STREAMINFO keeps
	 * the decoder from building objects for tags and embedded cover art that
	 * this server has no use for.
	 */
	FLAC__stream_decoder_set_md5_checking(d->dec, false);
	FLAC__stream_decoder_set_metadata_respond(d->dec, FLAC__METADATA_TYPE_STREAMINFO);
	FLAC__StreamDecoderInitStatus st = FLAC__stream_decoder_init_file(
			d->dec, path, on_write, on_meta, on_error, d);
	if (st != FLAC__STREAM_DECODER_INIT_STATUS_OK) {
		*err = (char *)FLAC__StreamDecoderInitStatusString[st];
		FLAC__stream_decoder_delete(d->dec);
		free(d);
		return NULL;
	}
	return d;
}

int flacdec_next(flacdec *d)
{
	if (d->eof)
		return 0;
	for (;;) {
		d->got_frame = 0;
		FLAC__bool ok = FLAC__stream_decoder_process_single(d->dec);
		if (d->got_frame && !d->failed)
			return 1;
		if (d->failed) {
			d->eof = 1;
			return -1;
		}
		if (!ok) {
			d->eof = 1;
			return 0;
		}
		/* A metadata block was processed; ask for the next unit. */
		FLAC__StreamDecoderState s = FLAC__stream_decoder_get_state(d->dec);
		if (s == FLAC__STREAM_DECODER_END_OF_STREAM) {
			d->eof = 1;
			return 0;
		}
		if (s == FLAC__STREAM_DECODER_ABORTED) {
			d->eof = 1;
			return -1;
		}
	}
}

int32_t *flacdec_samples(flacdec *d) { return d->buf; }
unsigned flacdec_channels(flacdec *d) { return d->channels; }
unsigned flacdec_blocksize(flacdec *d) { return d->blocksize; }
unsigned flacdec_rate(flacdec *d) { return d->rate; }
unsigned flacdec_bits(flacdec *d) { return d->bits; }
uint64_t flacdec_total(flacdec *d) { return d->total; }

int flacdec_seek(flacdec *d, uint64_t sample)
{
	d->eof = 0;
	d->failed = 0;
	if (!FLAC__stream_decoder_seek_absolute(d->dec, (FLAC__uint64)sample))
		return -1;
	return 0;
}

void flacdec_close(flacdec *d)
{
	if (!d)
		return;
	if (d->dec)
		FLAC__stream_decoder_delete(d->dec);
	free(d->buf);
	free(d);
}
