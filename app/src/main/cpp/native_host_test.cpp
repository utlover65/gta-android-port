#include <jni.h>
#include <android/log.h>

#include <atomic>
#include <cstdio>
#include <cstring>
#include <exception>
#include <string>
#include <thread>

namespace {
constexpr char kTag[] = "GtaNativeHostTest";

std::string runChecks(const std::string& filesDir) {
    std::string output;

    std::atomic<int> value{0};
    try {
        std::thread worker([&value] { value.store(0x475441, std::memory_order_release); });
        worker.join();
        output += value.load(std::memory_order_acquire) == 0x475441
                ? "NDK std::thread: PASS\n"
                : "NDK std::thread: FAIL (wrong shared value)\n";
    } catch (const std::exception& e) {
        output += std::string("NDK std::thread: FAIL (") + e.what() + ")\n";
    }

    const std::string path = filesDir + "/native-host-cpp-self-test.tmp";
    constexpr char payload[] = "native-host-io-check-v1";
    bool filePass = false;
    FILE* f = std::fopen(path.c_str(), "wb");
    if (f) {
        const size_t written = std::fwrite(payload, 1, sizeof(payload) - 1, f);
        const bool closed = std::fclose(f) == 0;
        if (written == sizeof(payload) - 1 && closed) {
            char buffer[sizeof(payload)] = {};
            f = std::fopen(path.c_str(), "rb");
            if (f) {
                const size_t got = std::fread(buffer, 1, sizeof(payload) - 1, f);
                const bool closeOk = std::fclose(f) == 0;
                filePass = got == sizeof(payload) - 1 && closeOk &&
                           std::memcmp(buffer, payload, sizeof(payload) - 1) == 0;
            }
        }
    }
    std::remove(path.c_str());
    output += filePass ? "NDK app-private file I/O: PASS\n" : "NDK app-private file I/O: FAIL\n";
    output += std::string("Native ABI: ") + (sizeof(void*) == 8 ? "64-bit\n" : "32-bit\n");
    __android_log_print(ANDROID_LOG_INFO, kTag, "%s", output.c_str());
    return output;
}
}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_org_utlover65_astravulkanprobe_NativePortTestActivity_runNativeChecks(
        JNIEnv* env, jclass, jstring javaFilesDir) {
    if (!javaFilesDir) return env->NewStringUTF("FAIL: filesDir was null");
    const char* chars = env->GetStringUTFChars(javaFilesDir, nullptr);
    if (!chars) return env->NewStringUTF("FAIL: could not read filesDir string");
    const std::string filesDir(chars);
    env->ReleaseStringUTFChars(javaFilesDir, chars);
    const std::string result = runChecks(filesDir);
    return env->NewStringUTF(result.c_str());
}
