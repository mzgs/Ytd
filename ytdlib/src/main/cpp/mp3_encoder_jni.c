#include <jni.h>
#include "lame_ffi.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

typedef struct Mp3EncoderHandle {
    lame_t lame;
    FILE *file;
    unsigned char *buffer;
    int buffer_size;
    int channel_count;
    int finished;
} Mp3EncoderHandle;

static void throw_exception(JNIEnv *env, const char *class_name, const char *message) {
    jclass exception_class = (*env)->FindClass(env, class_name);
    if (exception_class != NULL) {
        (*env)->ThrowNew(env, exception_class, message);
    }
}

static void throw_illegal_argument(JNIEnv *env, const char *message) {
    throw_exception(env, "java/lang/IllegalArgumentException", message);
}

static void throw_illegal_state(JNIEnv *env, const char *message) {
    throw_exception(env, "java/lang/IllegalStateException", message);
}

static void throw_io_exception(JNIEnv *env, const char *message) {
    throw_exception(env, "java/io/IOException", message);
}

static void destroy_handle(Mp3EncoderHandle *handle) {
    if (handle == NULL) {
        return;
    }

    if (handle->file != NULL) {
        fclose(handle->file);
    }
    if (handle->lame != NULL) {
        lame_close(handle->lame);
    }
    free(handle->buffer);
    free(handle);
}

static int ensure_buffer_capacity(Mp3EncoderHandle *handle, int pcm_byte_count) {
    const int samples_per_channel = pcm_byte_count / (2 * handle->channel_count);
    const int needed_size = (int) (1.25 * samples_per_channel + 7200);
    if (needed_size <= handle->buffer_size) {
        return 1;
    }

    unsigned char *new_buffer = (unsigned char *) realloc(handle->buffer, (size_t) needed_size);
    if (new_buffer == NULL) {
        return 0;
    }

    handle->buffer = new_buffer;
    handle->buffer_size = needed_size;
    return 1;
}

JNIEXPORT jlong JNICALL
Java_com_mzgs_ytdlib_NativeMp3Encoder_nativeCreate(
    JNIEnv *env,
    jclass clazz,
    jstring output_path,
    jint sample_rate,
    jint channel_count,
    jint bitrate_kbps
) {
    (void) clazz;

    if (output_path == NULL) {
        throw_illegal_argument(env, "outputPath must not be null");
        return 0;
    }
    if (sample_rate <= 0) {
        throw_illegal_argument(env, "sampleRate must be positive");
        return 0;
    }
    if (channel_count < 1 || channel_count > 2) {
        throw_illegal_argument(env, "channelCount must be 1 or 2");
        return 0;
    }
    if (bitrate_kbps <= 0) {
        throw_illegal_argument(env, "bitrateKbps must be positive");
        return 0;
    }

    const char *output_path_chars = (*env)->GetStringUTFChars(env, output_path, NULL);
    if (output_path_chars == NULL) {
        return 0;
    }

    Mp3EncoderHandle *handle = (Mp3EncoderHandle *) calloc(1, sizeof(Mp3EncoderHandle));
    if (handle == NULL) {
        (*env)->ReleaseStringUTFChars(env, output_path, output_path_chars);
        throw_io_exception(env, "Failed to allocate MP3 encoder state");
        return 0;
    }

    handle->file = fopen(output_path_chars, "wb");
    (*env)->ReleaseStringUTFChars(env, output_path, output_path_chars);
    if (handle->file == NULL) {
        destroy_handle(handle);
        throw_io_exception(env, "Failed to open MP3 output file");
        return 0;
    }

    handle->lame = lame_init();
    if (handle->lame == NULL) {
        destroy_handle(handle);
        throw_illegal_state(env, "Failed to initialize LAME encoder");
        return 0;
    }

    handle->channel_count = channel_count;
    lame_set_in_samplerate(handle->lame, sample_rate);
    lame_set_num_channels(handle->lame, channel_count);
    lame_set_brate(handle->lame, bitrate_kbps);
    lame_set_quality(handle->lame, 2);

    if (lame_init_params(handle->lame) < 0) {
        destroy_handle(handle);
        throw_illegal_state(env, "Failed to configure LAME encoder");
        return 0;
    }

    return (jlong) (intptr_t) handle;
}

JNIEXPORT void JNICALL
Java_com_mzgs_ytdlib_NativeMp3Encoder_nativeEncode(
    JNIEnv *env,
    jclass clazz,
    jlong handle_ptr,
    jbyteArray pcm_data,
    jint length
) {
    (void) clazz;

    Mp3EncoderHandle *handle = (Mp3EncoderHandle *) (intptr_t) handle_ptr;
    if (handle == NULL || handle->lame == NULL || handle->file == NULL) {
        throw_illegal_state(env, "MP3 encoder has not been initialized");
        return;
    }
    if (handle->finished) {
        throw_illegal_state(env, "MP3 encoder has already been finished");
        return;
    }
    if (pcm_data == NULL) {
        throw_illegal_argument(env, "pcmData must not be null");
        return;
    }
    if (length < 0) {
        throw_illegal_argument(env, "length must be non-negative");
        return;
    }
    if (length == 0) {
        return;
    }
    if ((length % (handle->channel_count * 2)) != 0) {
        throw_illegal_argument(env, "PCM data length must align to complete samples");
        return;
    }

    if (!ensure_buffer_capacity(handle, length)) {
        throw_io_exception(env, "Failed to allocate MP3 output buffer");
        return;
    }

    jbyte *pcm_bytes = (*env)->GetByteArrayElements(env, pcm_data, NULL);
    if (pcm_bytes == NULL) {
        return;
    }

    const int samples_per_channel = length / (2 * handle->channel_count);
    int encoded_size;
    if (handle->channel_count == 1) {
        const short int *mono = (const short int *) pcm_bytes;
        encoded_size = lame_encode_buffer(
            handle->lame,
            mono,
            mono,
            samples_per_channel,
            handle->buffer,
            handle->buffer_size
        );
    } else {
        encoded_size = lame_encode_buffer_interleaved(
            handle->lame,
            (short int *) pcm_bytes,
            samples_per_channel,
            handle->buffer,
            handle->buffer_size
        );
    }

    (*env)->ReleaseByteArrayElements(env, pcm_data, pcm_bytes, JNI_ABORT);

    if (encoded_size < 0) {
        throw_illegal_state(env, "LAME failed to encode PCM audio");
        return;
    }

    if (encoded_size > 0 && fwrite(handle->buffer, 1U, (size_t) encoded_size, handle->file) != (size_t) encoded_size) {
        throw_io_exception(env, "Failed to write encoded MP3 data");
    }
}

JNIEXPORT void JNICALL
Java_com_mzgs_ytdlib_NativeMp3Encoder_nativeFinish(
    JNIEnv *env,
    jclass clazz,
    jlong handle_ptr
) {
    (void) clazz;

    Mp3EncoderHandle *handle = (Mp3EncoderHandle *) (intptr_t) handle_ptr;
    if (handle == NULL || handle->lame == NULL || handle->file == NULL) {
        throw_illegal_state(env, "MP3 encoder has not been initialized");
        return;
    }
    if (handle->finished) {
        return;
    }

    const int flush_buffer_size = 7200;
    unsigned char flush_buffer[7200];
    const int flush_size = lame_encode_flush(handle->lame, flush_buffer, flush_buffer_size);
    if (flush_size < 0) {
        throw_illegal_state(env, "LAME failed to flush remaining MP3 data");
        return;
    }

    if (flush_size > 0 && fwrite(flush_buffer, 1U, (size_t) flush_size, handle->file) != (size_t) flush_size) {
        throw_io_exception(env, "Failed to write final MP3 data");
        return;
    }

    fflush(handle->file);
    handle->finished = 1;
}

JNIEXPORT void JNICALL
Java_com_mzgs_ytdlib_NativeMp3Encoder_nativeClose(
    JNIEnv *env,
    jclass clazz,
    jlong handle_ptr
) {
    (void) env;
    (void) clazz;
    destroy_handle((Mp3EncoderHandle *) (intptr_t) handle_ptr);
}
