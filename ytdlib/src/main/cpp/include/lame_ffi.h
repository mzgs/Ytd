typedef struct lame_global_struct lame_global_flags;
typedef lame_global_flags* lame_t;

lame_t lame_init(void);
int lame_close(lame_t);

int lame_set_in_samplerate(lame_t, int);
int lame_set_num_channels(lame_t, int);
int lame_set_brate(lame_t, int);
int lame_set_quality(lame_t, int);
int lame_init_params(lame_t);

int lame_encode_buffer(
    lame_t,
    const short int[],
    const short int[],
    int,
    unsigned char*,
    int
);

int lame_encode_buffer_interleaved(
    lame_t,
    short int[],
    int,
    unsigned char*,
    int
);

int lame_encode_flush(
    lame_t,
    unsigned char*,
    int
);
