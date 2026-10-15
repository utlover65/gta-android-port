// JNI bridge for the AOT host. The engine, renderer, I/O worker, and audio run on native threads.
#include <jni.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <android/log.h>

#include <atomic>
#include <cerrno>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <string>
#include <sys/stat.h>
#include <unistd.h>

#include "gpu_host.h"

namespace {
constexpr const char* kTag = "GtaNativeAOT";
std::mutex g_window_mutex;
ANativeWindow* g_window = nullptr; // intentionally held for the lifetime of this process; Dawn may still reference it
std::atomic<bool> g_engine_started{false};

std::string fromJava(JNIEnv* env, jstring value) {
  if (!value) return {};
  const char* p = env->GetStringUTFChars(value, nullptr);
  if (!p) return {};
  std::string out(p);
  env->ReleaseStringUTFChars(value, p);
  return out;
}

bool isRegularReadable(const std::string& path) {
  struct stat st{};
  return !path.empty() && stat(path.c_str(), &st) == 0 && S_ISREG(st.st_mode) && access(path.c_str(), R_OK) == 0;
}

jstring javaString(JNIEnv* env, const std::string& s) { return env->NewStringUTF(s.c_str()); }
} // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_org_utlover65_astravulkanprobe_NativePortActivity_nativeSetWindowSurface(
        JNIEnv* env, jclass, jobject surface) {
  if (!surface) return javaString(env, "BLOCKED: Android Surface was null.");
  ANativeWindow* candidate = ANativeWindow_fromSurface(env, surface);
  if (!candidate) return javaString(env, "BLOCKED: ANativeWindow_fromSurface returned null.");
  {
    std::lock_guard<std::mutex> lock(g_window_mutex);
    if (!g_window) {
      g_window = candidate;
      host_window_set_layer(g_window);
      __android_log_print(ANDROID_LOG_INFO, kTag, "attached ANativeWindow %p (%d x %d)",
                          (void*)g_window, ANativeWindow_getWidth(g_window), ANativeWindow_getHeight(g_window));
    } else {
      // This host currently creates a Dawn Surface once. Replacing its native window needs a surface/device rebuild.
      ANativeWindow_release(candidate);
      return javaString(env, "Native window already bound; restart the native port screen before attempting to rebind a destroyed surface.");
    }
  }
  return javaString(env, "NATIVE WINDOW: ATTACHED");
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_utlover65_astravulkanprobe_NativePortActivity_nativeStart(
        JNIEnv* env, jclass, jstring javaRoot, jstring javaUserDir, jstring javaMode,
        jint width, jint height) {
  bool expected = false;
  if (!g_engine_started.compare_exchange_strong(expected, true, std::memory_order_acq_rel))
    return javaString(env, "BLOCKED: Native GTA host has already been started in this process.");
  ANativeWindow* bound_window = nullptr;
  {
    std::lock_guard<std::mutex> lock(g_window_mutex);
    bound_window = g_window;
  }
  if (!bound_window) {
    g_engine_started.store(false, std::memory_order_release);
    return javaString(env, "BLOCKED: attach the Android surface before starting the engine.");
  }
  const std::string root = fromJava(env, javaRoot);
  const std::string userDir = fromJava(env, javaUserDir);
  const std::string mode = fromJava(env, javaMode);
  const std::string dataDir = root + "/data";
  const std::string manifest = dataDir + "/manifest.json";
  if (!isRegularReadable(manifest)) {
    g_engine_started.store(false, std::memory_order_release);
    return javaString(env, "BLOCKED: expected readable " + manifest + ". Copy your own full data/ folder first.");
  }
  if (userDir.empty()) {
    g_engine_started.store(false, std::memory_order_release);
    return javaString(env, "BLOCKED: writable user-data directory is empty.");
  }
  (void)mkdir(userDir.c_str(), 0700);
  setenv("GTA_MODE", mode == "story" ? "story" : "sandbox", 1);
  setenv("GTA_CORES", "4", 1);
  setenv("GTA_PAGE", "1", 1);
  setenv("GTA_AUDIO", "1", 1);

  // A conservative mobile profile: 30 fps and 1 GiB reported VRAM to prevent runaway streaming.
  char* extraArgs[] = { const_cast<char*>("-frameLimit=2"), const_cast<char*>("-availablevidmem=1024"),
                        const_cast<char*>("-disableExtraMem") };
  host_config config{};
  config.data_dir = dataDir.c_str();
  config.user_dir = userDir.c_str();
  config.shader_dir = nullptr; // reference host locates root/b/*/shaders from data_dir
  config.gpu_mode = "dawn";
  config.window = 1;
  config.width = width > 0 ? width : ANativeWindow_getWidth(bound_window);
  config.height = height > 0 ? height : ANativeWindow_getHeight(bound_window);
  config.argc = 3;
  config.argv = extraArgs;

  __android_log_print(ANDROID_LOG_INFO, kTag,
      "starting AOT native host; data=%s mode=%s render=%dx%d; game data is external",
      dataDir.c_str(), mode.c_str(), config.width, config.height);
  host_start(&config); // starts the guest asynchronously; it does not mean the world is loaded
  return javaString(env, "NATIVE HOST: STARTED\nThe game engine is running asynchronously. Check logcat for engine, renderer, and asset-loading results.");
}

extern "C" JNIEXPORT void JNICALL
Java_org_utlover65_astravulkanprobe_NativePortActivity_nativeKey(JNIEnv*, jclass, jint vk, jint down) {
  host_input_key(vk, down != 0);
}
extern "C" JNIEXPORT void JNICALL
Java_org_utlover65_astravulkanprobe_NativePortActivity_nativeButton(JNIEnv*, jclass, jint bit, jint down) {
  host_input_button(bit, down != 0);
}
extern "C" JNIEXPORT void JNICALL
Java_org_utlover65_astravulkanprobe_NativePortActivity_nativeMouseDelta(JNIEnv*, jclass, jint dx, jint dy) {
  host_input_mouse_delta(dx, dy);
}
extern "C" JNIEXPORT void JNICALL
Java_org_utlover65_astravulkanprobe_NativePortActivity_nativeWheel(JNIEnv*, jclass, jint notches) {
  host_input_wheel(notches);
}
extern "C" JNIEXPORT void JNICALL
Java_org_utlover65_astravulkanprobe_NativePortActivity_nativeFocus(JNIEnv*, jclass, jint focused) {
  host_input_focus(focused != 0);
}
extern "C" JNIEXPORT void JNICALL
Java_org_utlover65_astravulkanprobe_NativePortActivity_nativeSuspend(JNIEnv*, jclass, jint suspended) {
  gpu_suspend(suspended != 0);
  if (suspended) host_input_focus(0); else host_input_focus(1);
}
