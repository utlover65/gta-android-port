#include <wasmtime.h>
#include <wasmtime/config.h>
#include <wasmtime/error.h>
#include <wasmtime/extern.h>
#include <wasmtime/func.h>
#include <wasmtime/instance.h>
#include <wasmtime/linker.h>
#include <wasmtime/memory.h>
#include <wasmtime/module.h>
#include <wasmtime/sharedmemory.h>
#include <wasmtime/store.h>
#include <wasmtime/trap.h>
#include <wasmtime/val.h>

#include <android/log.h>
#include <jni.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <climits>
#include <cstdio>
#include <cstdint>
#include <cstring>
#include <fstream>
#include <mutex>
#include <limits>
#include <string>
#include <time.h>
#include <unistd.h>
#include <vector>

namespace {
constexpr char kTag[] = "GtaNativeEngine";
constexpr uint64_t kMaxModuleBytes = 256ULL * 1024ULL * 1024ULL;
constexpr uint64_t kBootstrapFuel = 150'000'000ULL;
constexpr uint64_t kMainFuel = 20'000'000ULL;

struct Runtime {
    wasm_engine_t* engine = nullptr;
    wasmtime_store_t* store = nullptr;
    wasmtime_module_t* module = nullptr;
    wasmtime_linker_t* linker = nullptr;
    wasmtime_sharedmemory_t* sharedMemory = nullptr;
    wasmtime_instance_t instance{};
    bool instanceReady = false;
    uint64_t memoryMinPages = 0;
    uint64_t memoryMaxPages = 0;
    bool memoryHasMax = false;
};
Runtime g_runtime;
std::mutex g_mutex;
std::atomic<unsigned> g_hostWarningCount{0};
JavaVM* g_javaVm = nullptr;
jclass g_activityClass = nullptr;
jmethodID g_fetchManifestMethod = nullptr;

struct HostImportContext {
    std::string module;
    std::string name;
    std::vector<uint8_t> resultKinds;
};

void hostNote(const std::string& name) {
    // Prevent a broken game bootstrap from flooding logcat indefinitely.
    if (g_hostWarningCount.fetch_add(1, std::memory_order_relaxed) < 32)
        __android_log_print(ANDROID_LOG_WARN, kTag, "Native host bridge missing: %s", name.c_str());
}

double monotonicMilliseconds() {
    const auto now = std::chrono::steady_clock::now().time_since_epoch();
    return std::chrono::duration<double, std::milli>(now).count();
}

double wallMilliseconds() {
    const auto now = std::chrono::system_clock::now().time_since_epoch();
    return std::chrono::duration<double, std::milli>(now).count();
}

std::string wasmString(uint64_t offset) {
    if (!g_runtime.sharedMemory) return "<WASM memory unavailable>";
    const uint64_t size = wasmtime_sharedmemory_data_size(g_runtime.sharedMemory);
    const uint8_t* bytes = wasmtime_sharedmemory_data(g_runtime.sharedMemory);
    if (!bytes || offset >= size) return "<invalid WASM string pointer>";
    const size_t limit = static_cast<size_t>(std::min<uint64_t>(4096, size - offset));
    const char* text = reinterpret_cast<const char*>(bytes + offset);
    const void* end = std::memchr(text, 0, limit);
    const size_t count = end ? static_cast<size_t>(static_cast<const char*>(end) - text) : limit;
    return std::string(text, count);
}


bool writeWasmBytes(uint64_t offset, const void* source, size_t count) {
    if (!g_runtime.sharedMemory) return false;
    const uint64_t size = wasmtime_sharedmemory_data_size(g_runtime.sharedMemory);
    if (offset > size || count > size - offset) return false;
    uint8_t* data = wasmtime_sharedmemory_data(g_runtime.sharedMemory);
    if (!data) return false;
    std::memcpy(data + offset, source, count);
    return true;
}

bool fillResultKinds(const wasm_functype_t* functionType, std::vector<uint8_t>* resultKinds) {
    const wasm_valtype_vec_t* values = wasm_functype_results(functionType);
    for (size_t i = 0; i < values->size; ++i) {
        switch (wasm_valtype_kind(values->data[i])) {
            case WASM_I32: resultKinds->push_back(WASMTIME_I32); break;
            case WASM_I64: resultKinds->push_back(WASMTIME_I64); break;
            case WASM_F32: resultKinds->push_back(WASMTIME_F32); break;
            case WASM_F64: resultKinds->push_back(WASMTIME_F64); break;
            default: return false; // This module's imports use numeric results only.
        }
    }
    return true;
}

void setDefaultResults(const HostImportContext* context, wasmtime_val_t* results, size_t count) {
    for (size_t i = 0; i < count; ++i) {
        // The callback ABI should guarantee count == the function's declared
        // result count. Keep the host boundary defensive if that ever changes.
        results[i].kind = i < context->resultKinds.size() ? context->resultKinds[i] : WASMTIME_I32;
        switch (results[i].kind) {
            case WASMTIME_I32: results[i].of.i32 = 0; break;
            case WASMTIME_I64: results[i].of.i64 = 0; break;
            case WASMTIME_F32: results[i].of.f32 = 0.0f; break;
            case WASMTIME_F64: results[i].of.f64 = 0.0; break;
            default: break;
        }
    }
}

std::vector<uint8_t> fetchRemoteManifest() {
    std::vector<uint8_t> bytes;
    if (!g_javaVm || !g_activityClass || !g_fetchManifestMethod) return bytes;
    JNIEnv* env = nullptr;
    bool attached = false;
    const jint state = g_javaVm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    if (state == JNI_EDETACHED) {
        if (g_javaVm->AttachCurrentThread(&env, nullptr) != JNI_OK) return bytes;
        attached = true;
    } else if (state != JNI_OK) {
        return bytes;
    }

    jbyteArray array = static_cast<jbyteArray>(env->CallStaticObjectMethod(g_activityClass, g_fetchManifestMethod));
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
    } else if (array) {
        const jsize length = env->GetArrayLength(array);
        // Keep the metadata bridge bounded. The manifest is small relative to the game assets.
        if (length > 0 && length <= 32 * 1024 * 1024) {
            bytes.resize(static_cast<size_t>(length));
            env->GetByteArrayRegion(array, 0, length, reinterpret_cast<jbyte*>(bytes.data()));
            if (env->ExceptionCheck()) { env->ExceptionClear(); bytes.clear(); }
        }
        env->DeleteLocalRef(array);
    }
    if (attached) g_javaVm->DetachCurrentThread();
    return bytes;
}

wasm_trap_t* hostTrap(const char* message) {
    return wasmtime_trap_new(message, std::strlen(message));
}

wasm_trap_t* nativeHostImport(void* opaque, wasmtime_caller_t*, const wasmtime_val_t* args,
                              size_t nargs, wasmtime_val_t* results, size_t nresults) {
    const auto* context = static_cast<const HostImportContext*>(opaque);
    setDefaultResults(context, results, nresults);
    const std::string& name = context->name;
    const std::string& module = context->module;

    if (module == "env" && name == "wasm_now_ms") {
        if (nresults) { results[0].kind = WASMTIME_F64; results[0].of.f64 = wallMilliseconds(); }
        return nullptr;
    }
    if (module == "env" && name == "emscripten_get_now") {
        if (nresults) { results[0].kind = WASMTIME_F64; results[0].of.f64 = monotonicMilliseconds(); }
        return nullptr;
    }
    if (module == "env" && name == "emscripten_date_now") {
        if (nresults) { results[0].kind = WASMTIME_F64; results[0].of.f64 = wallMilliseconds(); }
        return nullptr;
    }
    if (module == "env" && (name == "wasm_print_line_js" || name == "wasm_hang_line_js")) {
        if (nargs && args[0].kind == WASMTIME_I64) {
            std::string line = wasmString(static_cast<uint64_t>(args[0].of.i64));
            __android_log_print(ANDROID_LOG_INFO, kTag, "%s", line.c_str());
        }
        return nullptr;
    }
    if (module == "env" && name == "wasm_module_int_js") {
        if (nresults) {
            results[0].kind = WASMTIME_I32;
            results[0].of.i32 = (nargs >= 2 && args[1].kind == WASMTIME_I32) ? args[1].of.i32 : 0;
        }
        return nullptr;
    }
    if (module == "env" && name == "emscripten_num_logical_cores") {
        if (nresults) {
            long cores = sysconf(_SC_NPROCESSORS_ONLN);
            results[0].kind = WASMTIME_I32;
            results[0].of.i32 = cores > 0 ? static_cast<int32_t>(cores) : 1;
        }
        return nullptr;
    }
    if (module == "env" && name == "emscripten_get_heap_max") {
        if (nresults) {
            results[0].kind = WASMTIME_I64;
            results[0].of.i64 = g_runtime.sharedMemory
                ? static_cast<int64_t>(wasmtime_sharedmemory_data_size(g_runtime.sharedMemory)) : 0;
        }
        return nullptr;
    }
    if (module == "wasi_snapshot_preview1" && name == "clock_time_get") {
        if (nargs >= 3 && args[0].kind == WASMTIME_I32 && args[2].kind == WASMTIME_I64 && nresults) {
            struct timespec ts{};
            clockid_t clockId = args[0].of.i32 == 0 ? CLOCK_REALTIME : CLOCK_MONOTONIC;
            results[0].kind = WASMTIME_I32;
            if (clock_gettime(clockId, &ts) != 0) { results[0].of.i32 = 29; return nullptr; }
            const uint64_t nanos = static_cast<uint64_t>(ts.tv_sec) * 1000000000ULL + static_cast<uint64_t>(ts.tv_nsec);
            results[0].of.i32 = writeWasmBytes(static_cast<uint64_t>(args[2].of.i64), &nanos, sizeof(nanos)) ? 0 : 21;
        }
        return nullptr;
    }
    if (module == "wasi_snapshot_preview1" && name == "environ_sizes_get") {
        if (nargs >= 2 && nresults && args[0].kind == WASMTIME_I64 && args[1].kind == WASMTIME_I64) {
            const uint32_t zero = 0;
            bool okay = writeWasmBytes(static_cast<uint64_t>(args[0].of.i64), &zero, sizeof(zero)) &&
                        writeWasmBytes(static_cast<uint64_t>(args[1].of.i64), &zero, sizeof(zero));
            results[0].kind = WASMTIME_I32; results[0].of.i32 = okay ? 0 : 21;
        }
        return nullptr;
    }
    if (module == "wasi_snapshot_preview1" && name == "environ_get") {
        if (nresults) { results[0].kind = WASMTIME_I32; results[0].of.i32 = 0; }
        return nullptr;
    }
    if (module == "env" && name == "wasm_httpfs_manifest_js") {
        if (nargs < 2 || args[0].kind != WASMTIME_I64 || args[1].kind != WASMTIME_I32 || !nresults) {
            hostNote("env.wasm_httpfs_manifest_js received an unexpected ABI signature");
            if (nresults) { results[0].kind = WASMTIME_I32; results[0].of.i32 = -1; }
            return nullptr;
        }
        const uint64_t destination = static_cast<uint64_t>(args[0].of.i64);
        const int32_t capacity = args[1].of.i32;
        std::vector<uint8_t> manifest = fetchRemoteManifest();
        int32_t answer = -1;
        if (!manifest.empty() && manifest.size() <= static_cast<size_t>(INT32_MAX)) {
            answer = static_cast<int32_t>(manifest.size());
            // Match the browser shim: report required size; copy + NUL only when
            // the supplied capacity is strictly larger than the manifest size.
            if (capacity > answer) {
                const uint8_t nul = 0;
                if (destination > std::numeric_limits<uint64_t>::max() - manifest.size() ||
                    !writeWasmBytes(destination, manifest.data(), manifest.size()) ||
                    !writeWasmBytes(destination + manifest.size(), &nul, 1)) {
                    answer = -1;
                }
            }
        } else {
            hostNote("env.wasm_httpfs_manifest_js could not fetch the remote data manifest");
        }
        results[0].kind = WASMTIME_I32;
        results[0].of.i32 = answer;
        return nullptr;
    }
    if (module == "env" && name == "__pthread_create_js") {
        hostNote("env.__pthread_create_js (native pthread adapter not implemented)");
        if (nresults) { results[0].kind = WASMTIME_I32; results[0].of.i32 = 6; }
        return nullptr;
    }
    if ((module == "wasi_snapshot_preview1" && name == "proc_exit") ||
        (module == "env" && (name == "exit" || name == "_abort_js" || name == "emscripten_exit_with_live_runtime"))) {
        char message[160];
        std::snprintf(message, sizeof(message), "WASM requested process exit via %s.%s", module.c_str(), name.c_str());
        hostNote(message);
        return hostTrap(message);
    }
    if (module == "env" && name.rfind("__syscall_", 0) == 0) {
        hostNote("env." + name + " (native syscall mapping not implemented)");
        if (nresults) { results[0].kind = WASMTIME_I32; results[0].of.i32 = -38; }
        return nullptr;
    }
    if (module == "wasi_snapshot_preview1" && nresults) {
        // WASI errno 52 is ENOSYS; do not falsely report missing file/socket APIs as success.
        results[0].kind = WASMTIME_I32;
        results[0].of.i32 = 52;
        hostNote(module + "." + name + " (WASI host service not implemented)");
        return nullptr;
    }
    if (module == "env" && (name == "wgpu_start_worker" || name == "wasm_httpfs_io_start_js" ||
            name == "wasm_audio_publish_js" || name == "wasm_input_publish_js")) {
        hostNote(module + "." + name + " (native bridge not implemented)");
        return nullptr;
    }
    if (module == "env" && (name.find("wasm_") != std::string::npos || name.find("wgpu_") != std::string::npos)) {
        hostNote(module + "." + name + " (custom import default stub)");
    }
    return nullptr;
}

void deleteHostImportContext(void* opaque) {
    delete static_cast<HostImportContext*>(opaque);
}

std::string wasmMessage(const wasm_name_t& message) {
    if (!message.data || message.size == 0) return {};
    return std::string(reinterpret_cast<const char*>(message.data), message.size);
}

std::string takeError(wasmtime_error_t* error) {
    if (!error) return {};
    wasm_name_t message;
    wasmtime_error_message(error, &message);
    std::string text = wasmMessage(message);
    wasm_name_delete(&message);
    wasmtime_error_delete(error);
    return text;
}

std::string takeTrap(wasm_trap_t* trap) {
    if (!trap) return {};
    wasm_message_t message;
    wasm_trap_message(trap, &message);
    std::string text = wasmMessage(message);
    wasm_name_delete(&message);
    wasm_trap_delete(trap);
    return text;
}

void closeRuntimeLocked() {
    if (g_runtime.linker) wasmtime_linker_delete(g_runtime.linker);
    g_runtime.linker = nullptr;
    if (g_runtime.store) wasmtime_store_delete(g_runtime.store);
    g_runtime.store = nullptr;
    if (g_runtime.sharedMemory) wasmtime_sharedmemory_delete(g_runtime.sharedMemory);
    g_runtime.sharedMemory = nullptr;
    if (g_runtime.module) wasmtime_module_delete(g_runtime.module);
    g_runtime.module = nullptr;
    if (g_runtime.engine) wasm_engine_delete(g_runtime.engine);
    g_runtime.engine = nullptr;
    g_runtime.instanceReady = false;
    g_runtime.instance = {};
    g_runtime.memoryMinPages = 0;
    g_runtime.memoryMaxPages = 0;
    g_runtime.memoryHasMax = false;
}

std::string readModule(const std::string& path, std::vector<uint8_t>* bytes) {
    std::ifstream file(path, std::ios::binary | std::ios::ate);
    if (!file) return "FAIL — cannot open the selected WASM file";
    const std::streamoff end = file.tellg();
    if (end < 8) return "FAIL — file is too small to be a WASM module";
    if (static_cast<uint64_t>(end) > kMaxModuleBytes)
        return "FAIL — WASM exceeds the 256 MiB safety limit";
    bytes->resize(static_cast<size_t>(end));
    file.seekg(0, std::ios::beg);
    if (!file.read(reinterpret_cast<char*>(bytes->data()), static_cast<std::streamsize>(bytes->size())))
        return "FAIL — could not read the complete WASM module";
    const uint8_t* p = bytes->data();
    if (!(p[0] == 0x00 && p[1] == 'a' && p[2] == 's' && p[3] == 'm' &&
          p[4] == 1 && p[5] == 0 && p[6] == 0 && p[7] == 0))
        return "FAIL — invalid or unsupported WASM v1 header";
    return {};
}

std::string nativeLoadAndInitialize(const std::string& path) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_runtime.instanceReady)
        return "NATIVE ENGINE ALREADY INITIALIZED\nClose/reopen this screen to retry with another module.";

    std::vector<uint8_t> bytes;
    std::string readError = readModule(path, &bytes);
    if (!readError.empty()) return readError;

    const auto start = std::chrono::steady_clock::now();
    wasm_config_t* config = wasm_config_new();
    if (!config) return "FAIL — Wasmtime could not allocate a configuration";
    wasmtime_config_wasm_memory64_set(config, true);
    wasmtime_config_wasm_threads_set(config, true);
    wasmtime_config_shared_memory_set(config, true);
    wasmtime_config_wasm_simd_set(config, true);
    wasmtime_config_consume_fuel_set(config, true);
    wasmtime_config_cranelift_opt_level_set(config, WASMTIME_OPT_LEVEL_NONE);

    // wasm_engine_new_with_config takes ownership of config.
    g_runtime.engine = wasm_engine_new_with_config(config);
    if (!g_runtime.engine) {
        return "NATIVE WASM ENGINE: FAIL\nWasmtime could not initialize memory64 + threads + shared memory.";
    }
    g_runtime.store = wasmtime_store_new(g_runtime.engine, nullptr, nullptr);
    if (!g_runtime.store) {
        closeRuntimeLocked();
        return "NATIVE WASM ENGINE: FAIL\nCould not create a Wasmtime store.";
    }
    wasmtime_context_t* context = wasmtime_store_context(g_runtime.store);
    wasmtime_error_t* error = wasmtime_context_set_fuel(context, kBootstrapFuel);
    if (error) {
        std::string detail = takeError(error);
        closeRuntimeLocked();
        return "NATIVE WASM ENGINE: FAIL\nFuel configuration failed: " + detail;
    }

    error = wasmtime_module_new(g_runtime.engine, bytes.data(), bytes.size(), &g_runtime.module);
    if (error || !g_runtime.module) {
        std::string detail = error ? takeError(error) : "compiler returned no module";
        closeRuntimeLocked();
        return "NATIVE MODULE COMPILE: FAIL\n" + detail;
    }
    g_runtime.linker = wasmtime_linker_new(g_runtime.engine);
    if (!g_runtime.linker) {
        closeRuntimeLocked();
        return "NATIVE MODULE LINK: FAIL\nCould not create linker.";
    }

    // Supply imported shared env.memory using the exact type declared by the module.
    wasm_importtype_vec_t imports;
    wasmtime_module_imports(g_runtime.module, &imports);
    size_t functionImports = 0;
    size_t memoryImports = 0;
    std::string importFailure;
    for (size_t i = 0; i < imports.size; ++i) {
        const wasm_importtype_t* item = imports.data[i];
        const wasm_name_t* moduleName = wasm_importtype_module(item);
        const wasm_name_t* fieldName = wasm_importtype_name(item);
        const wasm_externtype_t* type = wasm_importtype_type(item);
        const wasm_externkind_t kind = wasm_externtype_kind(type);
        if (kind == WASM_EXTERN_FUNC) {
            ++functionImports;
            const wasm_functype_t* functionType = wasm_externtype_as_functype_const(type);
            auto* hostContext = new HostImportContext();
            hostContext->module.assign(reinterpret_cast<const char*>(moduleName->data), moduleName->size);
            hostContext->name.assign(reinterpret_cast<const char*>(fieldName->data), fieldName->size);
            if (!fillResultKinds(functionType, &hostContext->resultKinds)) {
                importFailure = "unsupported host import return value type for " + hostContext->module + "." + hostContext->name;
                delete hostContext;
                break;
            }
            error = wasmtime_linker_define_func(g_runtime.linker,
                reinterpret_cast<const char*>(moduleName->data), moduleName->size,
                reinterpret_cast<const char*>(fieldName->data), fieldName->size,
                functionType, nativeHostImport, hostContext, deleteHostImportContext);
            if (error) {
                const std::string importName = hostContext->module + "." + hostContext->name;
                const std::string detail = takeError(error);
                delete hostContext;
                importFailure = "could not bind function import " + importName + ": " + detail;
                break;
            }
            continue;
        }
        if (kind == WASM_EXTERN_MEMORY) {
            ++memoryImports;
            if (g_runtime.sharedMemory) {
                importFailure = "more than one imported memory is not handled in this stage";
                break;
            }
            const wasm_memorytype_t* memType = wasm_externtype_as_memorytype_const(type);
            g_runtime.memoryMinPages = wasmtime_memorytype_minimum(memType);
            g_runtime.memoryHasMax = wasmtime_memorytype_maximum(memType, &g_runtime.memoryMaxPages);
            if (!wasmtime_memorytype_is64(memType) || !wasmtime_memorytype_isshared(memType)) {
                importFailure = "expected an imported memory64 shared memory; module import type differs";
                break;
            }
            error = wasmtime_sharedmemory_new(g_runtime.engine, memType, &g_runtime.sharedMemory);
            if (error || !g_runtime.sharedMemory) {
                importFailure = "could not reserve the module's declared shared linear memory: " +
                    (error ? takeError(error) : std::string("runtime returned no memory"));
                break;
            }
            wasmtime_extern_t ext{};
            ext.kind = WASMTIME_EXTERN_SHAREDMEMORY;
            ext.of.sharedmemory = g_runtime.sharedMemory;
            error = wasmtime_linker_define(g_runtime.linker, context,
                reinterpret_cast<const char*>(moduleName->data), moduleName->size,
                reinterpret_cast<const char*>(fieldName->data), fieldName->size, &ext);
            if (error) {
                importFailure = "could not bind imported memory: " + takeError(error);
                break;
            }
            continue;
        }
        importFailure = "unsupported non-function import kind " + std::to_string(static_cast<int>(kind)) +
            " for " + std::string(reinterpret_cast<const char*>(moduleName->data), moduleName->size) + "." +
            std::string(reinterpret_cast<const char*>(fieldName->data), fieldName->size);
        break;
    }
    wasm_importtype_vec_delete(&imports);
    if (importFailure.empty() && memoryImports != 1)
        importFailure = "expected exactly one imported shared memory, found " + std::to_string(memoryImports);
    if (!importFailure.empty()) {
        closeRuntimeLocked();
        return "NATIVE MODULE LINK: FAIL\n" + importFailure;
    }

    // Every imported function is explicitly bound above. Unknown-import
    // fallback definitions are intentionally avoided so a missed bridge cannot
    // be silently mistaken for a valid implementation.

    wasmtime_instance_t instance{};
    wasm_trap_t* trap = nullptr;
    error = wasmtime_linker_instantiate(g_runtime.linker, context, g_runtime.module, &instance, &trap);
    if (error || trap) {
        std::string detail = error ? takeError(error) : takeTrap(trap);
        if (error && trap) { takeTrap(trap); }
        closeRuntimeLocked();
        return "NATIVE MODULE START: FAIL\n" + detail +
            "\nThe import stubs are placeholders; this is not a game boot.";
    }
    g_runtime.instance = instance;
    g_runtime.instanceReady = true;

    // Execute the Emscripten C/C++ constructor export after the module start section.
    // The actual gameplay main is a separate bounded test button.
    wasmtime_extern_t ctor{};
    bool ctorFound = wasmtime_instance_export_get(context, &g_runtime.instance,
        "__wasm_call_ctors", std::strlen("__wasm_call_ctors"), &ctor);
    if (!ctorFound || ctor.kind != WASMTIME_EXTERN_FUNC) {
        if (ctorFound) wasmtime_extern_delete(&ctor);
        closeRuntimeLocked();
        return "NATIVE MODULE START: PARTIAL\nThe WASM start section ran, but __wasm_call_ctors export was not found.";
    }
    error = wasmtime_context_set_fuel(context, kBootstrapFuel);
    if (error) {
        const std::string detail = takeError(error);
        wasmtime_extern_delete(&ctor);
        closeRuntimeLocked();
        return "NATIVE MODULE START: FAIL\nCould not set constructor fuel budget: " + detail;
    }
    trap = nullptr;
    error = wasmtime_func_call(context, &ctor.of.func, nullptr, 0, nullptr, 0, &trap);
    wasmtime_extern_delete(&ctor);
    if (error || trap) {
        std::string detail = error ? takeError(error) : takeTrap(trap);
        if (error && trap) { takeTrap(trap); }
        closeRuntimeLocked();
        return "NATIVE MODULE START: FAIL\n__wasm_call_ctors trapped: " + detail;
    }

    const auto end = std::chrono::steady_clock::now();
    const auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(end - start).count();
    std::string output =
        "NATIVE WASM MODULE START: PASS\n"
        "Runtime: Wasmtime C API 49.0.2 (Android ARM64)\n"
        "No WebView or browser runtime used for this execution.\n"
        "WASM start section: PASS\n"
        "__wasm_call_ctors: PASS\n"
        "Function imports bound to native callbacks/default stubs: " + std::to_string(functionImports) + "\n" +
        "Imported shared memory: memory64, min " + std::to_string(g_runtime.memoryMinPages * 65536ULL / (1024ULL * 1024ULL * 1024ULL)) + " GiB, max " +
        (g_runtime.memoryHasMax ? std::to_string(g_runtime.memoryMaxPages * 65536ULL / (1024ULL * 1024ULL * 1024ULL)) + " GiB\n" : std::string("unspecified\n")) +
        "Elapsed: " + std::to_string(elapsed) + " ms\n\n" +
        "GAMEPLAY STATUS: NOT PLAYABLE YET\n"
        "GPU command worker, HTTP/batch file reads, native pthread startup, audio mixer and input callbacks are not implemented. The remote manifest fetch adapter is implemented.\n"
        "The next button calls the game entry point with a 20-million-instruction fuel cap; the remaining host stubs may cause an early trap or meaningless return.\n";
    __android_log_print(ANDROID_LOG_INFO, kTag, "%s", output.c_str());
    return output;
}

std::string tryMain() {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_runtime.instanceReady || !g_runtime.store)
        return "NATIVE GAME MAIN: BLOCKED\nLoad and initialize game.wasm first.";
    wasmtime_context_t* context = wasmtime_store_context(g_runtime.store);
    wasmtime_error_t* error = wasmtime_context_set_fuel(context, kMainFuel);
    if (error) return "NATIVE GAME MAIN: FAIL\n" + takeError(error);

    wasmtime_extern_t mainExport{};
    if (!wasmtime_instance_export_get(context, &g_runtime.instance,
            "__main_argc_argv", std::strlen("__main_argc_argv"), &mainExport)) {
        return "NATIVE GAME MAIN: BLOCKED\n__main_argc_argv export not found.";
    }
    if (mainExport.kind != WASMTIME_EXTERN_FUNC) {
        wasmtime_extern_delete(&mainExport);
        return "NATIVE GAME MAIN: BLOCKED\n__main_argc_argv is not a function export.";
    }
    wasmtime_val_t args[2]{};
    args[0].kind = WASMTIME_I32; args[0].of.i32 = 0;
    args[1].kind = WASMTIME_I64; args[1].of.i64 = 0;
    wasmtime_val_t result{};
    wasm_trap_t* trap = nullptr;
    error = wasmtime_func_call(context, &mainExport.of.func, args, 2, &result, 1, &trap);
    wasmtime_extern_delete(&mainExport);
    if (error || trap) {
        std::string detail = error ? takeError(error) : takeTrap(trap);
        if (error && trap) { takeTrap(trap); }
        return "NATIVE GAME MAIN: STOPPED/TRAPPED\n" + detail +
            "\nThis was a bounded native execution attempt, not playable gameplay. Host APIs remain stubs.";
    }
    uint64_t remaining = 0;
    wasmtime_context_get_fuel(context, &remaining);
    return "NATIVE GAME MAIN: RETURNED\nReturn code: " + std::to_string(result.of.i32) +
        "\nFuel remaining: " + std::to_string(remaining) + " / " + std::to_string(kMainFuel) +
        "\nThis does not confirm gameplay: the GPU, asset, thread, audio and input imports still return default values.";
}
} // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    g_javaVm = vm;
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_utlover65_astravulkanprobe_NativeGameEngineActivity_nativeLoadAndInitialize(
        JNIEnv* env, jclass clazz, jstring javaPath) {
    if (!g_activityClass) g_activityClass = static_cast<jclass>(env->NewGlobalRef(clazz));
    if (g_activityClass && !g_fetchManifestMethod)
        g_fetchManifestMethod = env->GetStaticMethodID(g_activityClass, "fetchManifestForNative", "()[B");
    if (env->ExceptionCheck()) env->ExceptionClear();
    if (!javaPath) return env->NewStringUTF("FAIL — module path was null");
    const char* chars = env->GetStringUTFChars(javaPath, nullptr);
    if (!chars) return env->NewStringUTF("FAIL — could not read module path");
    std::string path(chars);
    env->ReleaseStringUTFChars(javaPath, chars);
    std::string result;
    try { result = nativeLoadAndInitialize(path); }
    catch (const std::exception& e) { result = std::string("NATIVE INITIALIZATION EXCEPTION\n") + e.what(); }
    catch (...) { result = "NATIVE INITIALIZATION EXCEPTION\nUnknown native exception."; }
    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_utlover65_astravulkanprobe_NativeGameEngineActivity_nativeTryMain(JNIEnv* env, jclass) {
    std::string result;
    try { result = tryMain(); }
    catch (const std::exception& e) { result = std::string("NATIVE MAIN EXCEPTION\n") + e.what(); }
    catch (...) { result = "NATIVE MAIN EXCEPTION\nUnknown native exception."; }
    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_org_utlover65_astravulkanprobe_NativeGameEngineActivity_nativeClose(JNIEnv* env, jclass) {
    std::lock_guard<std::mutex> lock(g_mutex);
    closeRuntimeLocked();
    if (g_activityClass) env->DeleteGlobalRef(g_activityClass);
    g_activityClass = nullptr;
    g_fetchManifestMethod = nullptr;
}
