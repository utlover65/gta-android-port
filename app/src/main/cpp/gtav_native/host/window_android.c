// Android window adapter. Java owns the SurfaceView/event loop; Dawn receives its ANativeWindow.
#include <pthread.h>

#include "gpu_host.h"

static pthread_mutex_t g_window_mu = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t g_window_cv = PTHREAD_COND_INITIALIZER;
static void* g_window;

void host_window_set_layer(void* layer) {
  pthread_mutex_lock(&g_window_mu);
  g_window = layer;
  pthread_cond_broadcast(&g_window_cv);
  pthread_mutex_unlock(&g_window_mu);
}

void* host_window_layer(void) {
  pthread_mutex_lock(&g_window_mu);
  while (!g_window) pthread_cond_wait(&g_window_cv, &g_window_mu);
  void* result = g_window;
  pthread_mutex_unlock(&g_window_mu);
  return result;
}

void host_window_want(int wanted) { (void)wanted; }
void host_window_run(int width, int height) { (void)width; (void)height; }
