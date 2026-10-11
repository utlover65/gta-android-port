#include <jni.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <webgpu/webgpu.h>

#include <chrono>
#include <mutex>
#include <string>
#include <thread>

namespace {
struct AdapterResult {
    bool done = false;
    WGPURequestAdapterStatus status = WGPURequestAdapterStatus_Error;
    WGPUAdapter adapter = nullptr;
};
struct DeviceResult {
    bool done = false;
    WGPURequestDeviceStatus status = WGPURequestDeviceStatus_Error;
    WGPUDevice device = nullptr;
};
struct QueueResult { bool done = false; };

void onAdapter(WGPURequestAdapterStatus status, WGPUAdapter adapter,
               WGPUStringView, void* userdata1, void*) {
    auto* r = static_cast<AdapterResult*>(userdata1);
    r->status = status;
    r->adapter = adapter;
    r->done = true;
}
void onDevice(WGPURequestDeviceStatus status, WGPUDevice device,
              WGPUStringView, void* userdata1, void*) {
    auto* r = static_cast<DeviceResult*>(userdata1);
    r->status = status;
    r->device = device;
    r->done = true;
}
void onQueueDone(WGPUQueueWorkDoneStatus, WGPUStringView,
                 void* userdata1, void*) {
    static_cast<QueueResult*>(userdata1)->done = true;
}

class DawnAndroidRenderer {
public:
    ~DawnAndroidRenderer() { cleanup(); }
    std::string error;

    bool run(ANativeWindow* window) {
        cleanup();
        error.clear();
        if (!window) {
            error = "Android returned a null native window";
            return false;
        }
        // Own the ANativeWindow reference for the lifetime of the Dawn surface.
        window_ = window;

        if (!createInstance() || !createSurface() || !requestAdapter() ||
            !requestDevice() || !configureSurface() || !clearAndPresent()) {
            cleanup();
            return false;
        }
        return true;
    }

    void cleanup() {
        if (queue_ && instance_) {
            QueueResult result;
            WGPUQueueWorkDoneCallbackInfo info =
                WGPU_QUEUE_WORK_DONE_CALLBACK_INFO_INIT;
            info.mode = WGPUCallbackMode_AllowProcessEvents;
            info.callback = onQueueDone;
            info.userdata1 = &result;
            wgpuQueueOnSubmittedWorkDone(queue_, info);
            const auto deadline =
                std::chrono::steady_clock::now() + std::chrono::seconds(3);
            while (!result.done && std::chrono::steady_clock::now() < deadline) {
                wgpuInstanceProcessEvents(instance_);
                std::this_thread::sleep_for(std::chrono::milliseconds(1));
            }
        }
        if (surface_) {
            wgpuSurfaceUnconfigure(surface_);
            wgpuSurfaceRelease(surface_);
            surface_ = nullptr;
        }
        if (queue_) {
            wgpuQueueRelease(queue_);
            queue_ = nullptr;
        }
        if (device_) {
            wgpuDeviceDestroy(device_);
            wgpuDeviceRelease(device_);
            device_ = nullptr;
        }
        if (adapter_) {
            wgpuAdapterRelease(adapter_);
            adapter_ = nullptr;
        }
        if (instance_) {
            wgpuInstanceRelease(instance_);
            instance_ = nullptr;
        }
        if (window_) {
            ANativeWindow_release(window_);
            window_ = nullptr;
        }
    }

private:
    ANativeWindow* window_ = nullptr;
    WGPUInstance instance_ = nullptr;
    WGPUSurface surface_ = nullptr;
    WGPUAdapter adapter_ = nullptr;
    WGPUDevice device_ = nullptr;
    WGPUQueue queue_ = nullptr;
    WGPUTextureFormat format_ = WGPUTextureFormat_Undefined;

    template <typename Predicate>
    bool waitForCallback(Predicate completed, const char* operation) {
        const auto deadline =
            std::chrono::steady_clock::now() + std::chrono::seconds(15);
        while (!completed() && std::chrono::steady_clock::now() < deadline) {
            wgpuInstanceProcessEvents(instance_);
            std::this_thread::sleep_for(std::chrono::milliseconds(1));
        }
        if (!completed()) {
            error = std::string("Timed out waiting for Dawn ") + operation;
            return false;
        }
        return true;
    }

    bool createInstance() {
        WGPUInstanceDescriptor descriptor = WGPU_INSTANCE_DESCRIPTOR_INIT;
        instance_ = wgpuCreateInstance(&descriptor);
        if (!instance_) {
            error = "wgpuCreateInstance failed";
            return false;
        }
        return true;
    }

    bool createSurface() {
        WGPUSurfaceSourceAndroidNativeWindow source =
            WGPU_SURFACE_SOURCE_ANDROID_NATIVE_WINDOW_INIT;
        source.window = window_;
        WGPUSurfaceDescriptor descriptor = WGPU_SURFACE_DESCRIPTOR_INIT;
        descriptor.nextInChain = &source.chain;
        surface_ = wgpuInstanceCreateSurface(instance_, &descriptor);
        if (!surface_) {
            error = "Dawn could not create an Android native-window surface";
            return false;
        }
        return true;
    }

    bool requestAdapter() {
        WGPURequestAdapterOptions options =
            WGPU_REQUEST_ADAPTER_OPTIONS_INIT;
        options.backendType = WGPUBackendType_Vulkan;
        options.compatibleSurface = surface_;

        AdapterResult result;
        WGPURequestAdapterCallbackInfo info =
            WGPU_REQUEST_ADAPTER_CALLBACK_INFO_INIT;
        info.mode = WGPUCallbackMode_AllowProcessEvents;
        info.callback = onAdapter;
        info.userdata1 = &result;
        wgpuInstanceRequestAdapter(instance_, &options, info);

        if (!waitForCallback([&result] { return result.done; }, "Vulkan adapter"))
            return false;
        if (result.status != WGPURequestAdapterStatus_Success ||
            !result.adapter) {
            if (result.adapter) wgpuAdapterRelease(result.adapter);
            error = "Dawn could not acquire a Vulkan adapter compatible with the surface";
            return false;
        }
        adapter_ = result.adapter;
        return true;
    }

    bool requestDevice() {
        WGPUDeviceDescriptor descriptor = WGPU_DEVICE_DESCRIPTOR_INIT;
        DeviceResult result;
        WGPURequestDeviceCallbackInfo info =
            WGPU_REQUEST_DEVICE_CALLBACK_INFO_INIT;
        info.mode = WGPUCallbackMode_AllowProcessEvents;
        info.callback = onDevice;
        info.userdata1 = &result;
        wgpuAdapterRequestDevice(adapter_, &descriptor, info);

        if (!waitForCallback([&result] { return result.done; }, "device"))
            return false;
        if (result.status != WGPURequestDeviceStatus_Success || !result.device) {
            if (result.device) wgpuDeviceRelease(result.device);
            error = "Dawn could not create a WebGPU device";
            return false;
        }
        device_ = result.device;
        queue_ = wgpuDeviceGetQueue(device_);
        if (!queue_) {
            error = "Dawn returned a null WebGPU queue";
            return false;
        }
        return true;
    }

    bool configureSurface() {
        WGPUSurfaceCapabilities caps = WGPU_SURFACE_CAPABILITIES_INIT;
        const WGPUStatus status =
            wgpuSurfaceGetCapabilities(surface_, adapter_, &caps);
        if (status != WGPUStatus_Success ||
            caps.formatCount == 0 || !caps.formats) {
            wgpuSurfaceCapabilitiesFreeMembers(caps);
            error = "Dawn reported no compatible surface formats";
            return false;
        }

        format_ = caps.formats[0];
        const WGPUCompositeAlphaMode alpha =
            caps.alphaModeCount && caps.alphaModes
                ? caps.alphaModes[0] : WGPUCompositeAlphaMode_Auto;
        wgpuSurfaceCapabilitiesFreeMembers(caps);

        const int32_t width = ANativeWindow_getWidth(window_);
        const int32_t height = ANativeWindow_getHeight(window_);
        if (width <= 0 || height <= 0) {
            error = "Android surface has invalid dimensions";
            return false;
        }

        WGPUSurfaceConfiguration config = WGPU_SURFACE_CONFIGURATION_INIT;
        config.device = device_;
        config.format = format_;
        config.usage = WGPUTextureUsage_RenderAttachment;
        config.width = static_cast<uint32_t>(width);
        config.height = static_cast<uint32_t>(height);
        config.alphaMode = alpha;
        config.presentMode = WGPUPresentMode_Fifo;
        wgpuSurfaceConfigure(surface_, &config);
        return true;
    }

    bool clearAndPresent() {
        WGPUSurfaceTexture frame = WGPU_SURFACE_TEXTURE_INIT;
        wgpuSurfaceGetCurrentTexture(surface_, &frame);
        if (!frame.texture ||
            (frame.status != WGPUSurfaceGetCurrentTextureStatus_SuccessOptimal &&
             frame.status != WGPUSurfaceGetCurrentTextureStatus_SuccessSuboptimal)) {
            if (frame.texture) wgpuTextureRelease(frame.texture);
            error = "Dawn could not acquire the Android surface frame texture";
            return false;
        }

        WGPUTextureView view = wgpuTextureCreateView(frame.texture, nullptr);
        if (!view) {
            wgpuTextureRelease(frame.texture);
            error = "Dawn could not create a surface texture view";
            return false;
        }
        WGPUCommandEncoder encoder = wgpuDeviceCreateCommandEncoder(device_, nullptr);
        if (!encoder) {
            wgpuTextureViewRelease(view);
            wgpuTextureRelease(frame.texture);
            error = "Dawn could not create a command encoder";
            return false;
        }

        WGPURenderPassColorAttachment attachment =
            WGPU_RENDER_PASS_COLOR_ATTACHMENT_INIT;
        attachment.view = view;
        attachment.loadOp = WGPULoadOp_Clear;
        attachment.storeOp = WGPUStoreOp_Store;
        attachment.clearValue = WGPUColor{0.12, 0.62, 0.28, 1.0};

        WGPURenderPassDescriptor passDesc = WGPU_RENDER_PASS_DESCRIPTOR_INIT;
        passDesc.colorAttachmentCount = 1;
        passDesc.colorAttachments = &attachment;
        WGPURenderPassEncoder pass =
            wgpuCommandEncoderBeginRenderPass(encoder, &passDesc);
        if (!pass) {
            wgpuCommandEncoderRelease(encoder);
            wgpuTextureViewRelease(view);
            wgpuTextureRelease(frame.texture);
            error = "Dawn could not begin the render pass";
            return false;
        }
        wgpuRenderPassEncoderEnd(pass);
        wgpuRenderPassEncoderRelease(pass);

        WGPUCommandBuffer commandBuffer = wgpuCommandEncoderFinish(encoder, nullptr);
        wgpuCommandEncoderRelease(encoder);
        if (!commandBuffer) {
            wgpuTextureViewRelease(view);
            wgpuTextureRelease(frame.texture);
            error = "Dawn could not finish the command buffer";
            return false;
        }

        wgpuQueueSubmit(queue_, 1, &commandBuffer);
        wgpuCommandBufferRelease(commandBuffer);
        const WGPUStatus presentStatus = wgpuSurfacePresent(surface_);
        wgpuTextureViewRelease(view);
        wgpuTextureRelease(frame.texture);

        if (presentStatus != WGPUStatus_Success) {
            error = "Dawn submitted a frame, but surface presentation failed";
            return false;
        }
        return true;
    }
};

DawnAndroidRenderer g_renderer;
std::mutex g_rendererMutex;
}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_org_utlover65_astravulkanprobe_MainActivity_presentWebGPUFrame(
    JNIEnv* env, jclass, jobject surface) {
    std::lock_guard<std::mutex> lock(g_rendererMutex);
    ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
    if (!window)
        return env->NewStringUTF("FAIL: Android returned a null native window");
    if (!g_renderer.run(window)) {
        const std::string message = "FAIL: " + g_renderer.error;
        return env->NewStringUTF(message.c_str());
    }
    return env->NewStringUTF(
        "PASS: Dawn WebGPU rendered and presented a frame through Android/Vulkan.");
}

extern "C" JNIEXPORT void JNICALL
Java_org_utlover65_astravulkanprobe_MainActivity_releaseWebGPU(JNIEnv*, jclass) {
    std::lock_guard<std::mutex> lock(g_rendererMutex);
    g_renderer.cleanup();
}
