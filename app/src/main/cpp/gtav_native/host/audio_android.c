// Android AAudio consumer for the native GTA V mixer's shared-memory audio ring.
// Ring ABI from the browser/native reference: int32 header at byte 0 (write, read,
// capacity, callback heartbeat, peak); interleaved float stereo begins at byte 64.
#include <aaudio/AAudio.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>

#include "gpu_host.h"

static pthread_mutex_t g_audio_mu = PTHREAD_MUTEX_INITIALIZER;
static AAudioStream* g_audio_stream;
static int32_t* g_audio_header;
static float* g_audio_samples;
static uint32_t g_audio_mask;

static aaudio_data_callback_result_t audio_data_callback(
        AAudioStream* stream, void* user_data, void* audio_data, int32_t frames) {
  (void)stream;
  (void)user_data;
  float* out = (float*)audio_data;
  int32_t* hdr = g_audio_header;
  float* samples = g_audio_samples;
  if (!hdr || !samples || frames <= 0) {
    for (int64_t i = 0, n = (int64_t)frames * 2; i < n; ++i) out[i] = 0.0f;
    return AAUDIO_CALLBACK_RESULT_CONTINUE;
  }

  const uint32_t read = (uint32_t)__atomic_load_n(&hdr[1], __ATOMIC_ACQUIRE);
  const uint32_t write = (uint32_t)__atomic_load_n(&hdr[0], __ATOMIC_ACQUIRE);
  const uint32_t available = write - read;
  const uint32_t take = available < (uint32_t)frames ? available : (uint32_t)frames;
  for (uint32_t i = 0; i < take; ++i) {
    const uint32_t at = ((read + i) & g_audio_mask) * 2u;
    out[2u * i] = samples[at];
    out[2u * i + 1u] = samples[at + 1u];
  }
  for (uint32_t i = take; i < (uint32_t)frames; ++i) {
    out[2u * i] = 0.0f;
    out[2u * i + 1u] = 0.0f;
  }
  __atomic_store_n(&hdr[1], (int32_t)(read + take), __ATOMIC_RELEASE);
  __atomic_fetch_add(&hdr[3], 1, __ATOMIC_RELAXED);
  return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

static void audio_error_callback(AAudioStream* stream, void* user_data, aaudio_result_t error) {
  (void)stream;
  (void)user_data;
  host_log("audio: AAudio stream error %d (%s)", error, AAudio_convertResultToText(error));
}

void host_audio_start(uint8_t* ring, uint32_t capacity) {
  if (!ring || capacity < 2 || (capacity & (capacity - 1u)) != 0) {
    host_log("audio: rejected invalid ring/capacity %u", capacity);
    return;
  }
  pthread_mutex_lock(&g_audio_mu);
  if (g_audio_stream) {
    pthread_mutex_unlock(&g_audio_mu);
    return; // the engine publishes this ring once; never open duplicate output streams
  }
  g_audio_header = (int32_t*)ring;
  g_audio_samples = (float*)(ring + 64);
  g_audio_mask = capacity - 1u;

  AAudioStreamBuilder* builder = NULL;
  aaudio_result_t result = AAudio_createStreamBuilder(&builder);
  if (result != AAUDIO_OK || !builder) {
    host_log("audio: AAudio_createStreamBuilder failed: %s", AAudio_convertResultToText(result));
    pthread_mutex_unlock(&g_audio_mu);
    return;
  }
  AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
  AAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHARING_MODE_SHARED);
  AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
  AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_FLOAT);
  AAudioStreamBuilder_setChannelCount(builder, 2);
  AAudioStreamBuilder_setSampleRate(builder, 48000);
  AAudioStreamBuilder_setDataCallback(builder, audio_data_callback, NULL);
  AAudioStreamBuilder_setErrorCallback(builder, audio_error_callback, NULL);
  result = AAudioStreamBuilder_openStream(builder, &g_audio_stream);
  AAudioStreamBuilder_delete(builder);
  if (result != AAUDIO_OK || !g_audio_stream) {
    host_log("audio: openStream failed: %s", AAudio_convertResultToText(result));
    g_audio_stream = NULL;
    pthread_mutex_unlock(&g_audio_mu);
    return;
  }
  if (AAudioStream_getFormat(g_audio_stream) != AAUDIO_FORMAT_PCM_FLOAT ||
      AAudioStream_getChannelCount(g_audio_stream) != 2 ||
      AAudioStream_getSampleRate(g_audio_stream) != 48000) {
    host_log("audio: device format mismatch (format=%d channels=%d rate=%d); expected float stereo 48000 Hz",
        AAudioStream_getFormat(g_audio_stream), AAudioStream_getChannelCount(g_audio_stream), AAudioStream_getSampleRate(g_audio_stream));
    AAudioStream_close(g_audio_stream);
    g_audio_stream = NULL;
    pthread_mutex_unlock(&g_audio_mu);
    return;
  }
  result = AAudioStream_requestStart(g_audio_stream);
  if (result != AAUDIO_OK) {
    host_log("audio: requestStart failed: %s", AAudio_convertResultToText(result));
    AAudioStream_close(g_audio_stream);
    g_audio_stream = NULL;
  } else {
    host_log("audio: AAudio output started (float stereo, 48 kHz request, ring=%u frames)", capacity);
  }
  pthread_mutex_unlock(&g_audio_mu);
}
