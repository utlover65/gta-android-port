#define VK_USE_PLATFORM_ANDROID_KHR
#include <jni.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <vulkan/vulkan.h>
#include <vulkan/vulkan_android.h>

#include <algorithm>
#include <string>
#include <vector>

#define VK_CHECK(expr) do { \
    VkResult result_ = (expr); \
    if (result_ != VK_SUCCESS) { \
        error = #expr " failed: " + std::to_string((int)result_); \
        return false; \
    } \
} while (0)

class Renderer {
public:
    std::string error;

    explicit Renderer(ANativeWindow* window) : window_(window) {}
    ~Renderer() { cleanup(); }

    bool run() {
        return createInstance() &&
               createSurface() &&
               chooseDevice() &&
               createSwapchain() &&
               createRenderTargets() &&
               createCommands() &&
               drawFrame();
    }

private:
    ANativeWindow* window_;
    VkInstance instance_ = VK_NULL_HANDLE;
    VkSurfaceKHR surface_ = VK_NULL_HANDLE;
    VkPhysicalDevice physical_ = VK_NULL_HANDLE;
    VkDevice device_ = VK_NULL_HANDLE;
    uint32_t queueFamily_ = 0;
    VkQueue queue_ = VK_NULL_HANDLE;
    VkSwapchainKHR swapchain_ = VK_NULL_HANDLE;
    VkFormat format_ = VK_FORMAT_UNDEFINED;
    VkExtent2D extent_{};

    std::vector<VkImage> images_;
    std::vector<VkImageView> views_;
    VkRenderPass renderPass_ = VK_NULL_HANDLE;
    std::vector<VkFramebuffer> framebuffers_;

    VkCommandPool commandPool_ = VK_NULL_HANDLE;
    VkCommandBuffer command_ = VK_NULL_HANDLE;
    VkSemaphore acquired_ = VK_NULL_HANDLE;
    VkSemaphore rendered_ = VK_NULL_HANDLE;
    VkFence fence_ = VK_NULL_HANDLE;

    bool createInstance() {
        const char* extensions[] = {
            VK_KHR_SURFACE_EXTENSION_NAME,
            VK_KHR_ANDROID_SURFACE_EXTENSION_NAME
        };

        VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO};
        app.pApplicationName = "Astra Vulkan Presentation Test";
        app.apiVersion = VK_API_VERSION_1_0;

        VkInstanceCreateInfo ci{
            VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO
        };
        ci.pApplicationInfo = &app;
        ci.enabledExtensionCount = 2;
        ci.ppEnabledExtensionNames = extensions;

        VK_CHECK(vkCreateInstance(&ci, nullptr, &instance_));
        return true;
    }

    bool createSurface() {
        VkAndroidSurfaceCreateInfoKHR ci{
            VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR
        };
        ci.window = window_;

        VK_CHECK(vkCreateAndroidSurfaceKHR(
            instance_, &ci, nullptr, &surface_
        ));
        return true;
    }

    bool chooseDevice() {
        uint32_t count = 0;
        VK_CHECK(vkEnumeratePhysicalDevices(
            instance_, &count, nullptr
        ));

        if (count == 0) {
            error = "No Vulkan physical devices found";
            return false;
        }

        std::vector<VkPhysicalDevice> devices(count);
        VK_CHECK(vkEnumeratePhysicalDevices(
            instance_, &count, devices.data()
        ));

        for (VkPhysicalDevice candidate : devices) {
            uint32_t extCount = 0;

            if (vkEnumerateDeviceExtensionProperties(
                    candidate, nullptr, &extCount, nullptr
                ) != VK_SUCCESS) {
                continue;
            }

            std::vector<VkExtensionProperties> extensions(extCount);

            if (vkEnumerateDeviceExtensionProperties(
                    candidate, nullptr, &extCount,
                    extensions.data()
                ) != VK_SUCCESS) {
                continue;
            }

            bool hasSwapchain = false;

            for (const auto& ext : extensions) {
                if (std::string(ext.extensionName) ==
                    VK_KHR_SWAPCHAIN_EXTENSION_NAME) {
                    hasSwapchain = true;
                    break;
                }
            }

            if (!hasSwapchain) continue;

            uint32_t queueCount = 0;
            vkGetPhysicalDeviceQueueFamilyProperties(
                candidate, &queueCount, nullptr
            );

            std::vector<VkQueueFamilyProperties> queues(queueCount);
            vkGetPhysicalDeviceQueueFamilyProperties(
                candidate, &queueCount, queues.data()
            );

            for (uint32_t q = 0; q < queueCount; ++q) {
                VkBool32 canPresent = VK_FALSE;

                VkResult result = vkGetPhysicalDeviceSurfaceSupportKHR(
                    candidate, q, surface_, &canPresent
                );

                if (result == VK_SUCCESS &&
                    (queues[q].queueFlags & VK_QUEUE_GRAPHICS_BIT) &&
                    canPresent) {
                    physical_ = candidate;
                    queueFamily_ = q;
                    break;
                }
            }

            if (physical_ != VK_NULL_HANDLE) break;
        }

        if (physical_ == VK_NULL_HANDLE) {
            error = "No graphics queue supports Android-surface presentation";
            return false;
        }

        float priority = 1.0f;

        VkDeviceQueueCreateInfo queueInfo{
            VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO
        };
        queueInfo.queueFamilyIndex = queueFamily_;
        queueInfo.queueCount = 1;
        queueInfo.pQueuePriorities = &priority;

        const char* deviceExtensions[] = {
            VK_KHR_SWAPCHAIN_EXTENSION_NAME
        };

        VkDeviceCreateInfo deviceInfo{
            VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO
        };
        deviceInfo.queueCreateInfoCount = 1;
        deviceInfo.pQueueCreateInfos = &queueInfo;
        deviceInfo.enabledExtensionCount = 1;
        deviceInfo.ppEnabledExtensionNames = deviceExtensions;

        VK_CHECK(vkCreateDevice(
            physical_, &deviceInfo, nullptr, &device_
        ));

        vkGetDeviceQueue(device_, queueFamily_, 0, &queue_);
        return true;
    }

    bool createSwapchain() {
        VkSurfaceCapabilitiesKHR caps{};

        VK_CHECK(vkGetPhysicalDeviceSurfaceCapabilitiesKHR(
            physical_, surface_, &caps
        ));

        if (!(caps.supportedUsageFlags &
              VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT)) {
            error = "Surface images do not support color attachments";
            return false;
        }

        uint32_t count = 0;
        VK_CHECK(vkGetPhysicalDeviceSurfaceFormatsKHR(
            physical_, surface_, &count, nullptr
        ));

        if (count == 0) {
            error = "Android surface reports no formats";
            return false;
        }

        std::vector<VkSurfaceFormatKHR> formats(count);

        VK_CHECK(vkGetPhysicalDeviceSurfaceFormatsKHR(
            physical_, surface_, &count, formats.data()
        ));

        VkSurfaceFormatKHR chosen = formats[0];

        if (count == 1 && formats[0].format == VK_FORMAT_UNDEFINED) {
            chosen.format = VK_FORMAT_R8G8B8A8_UNORM;
        } else {
            for (const auto& f : formats) {
                if (f.format == VK_FORMAT_R8G8B8A8_UNORM ||
                    f.format == VK_FORMAT_B8G8R8A8_UNORM) {
                    chosen = f;
                    break;
                }
            }
        }

        format_ = chosen.format;

        if (caps.currentExtent.width != UINT32_MAX) {
            extent_ = caps.currentExtent;
        } else {
            extent_.width = std::clamp(
                static_cast<uint32_t>(ANativeWindow_getWidth(window_)),
                caps.minImageExtent.width,
                caps.maxImageExtent.width
            );
            extent_.height = std::clamp(
                static_cast<uint32_t>(ANativeWindow_getHeight(window_)),
                caps.minImageExtent.height,
                caps.maxImageExtent.height
            );
        }

        uint32_t imageCount = caps.minImageCount + 1;

        if (caps.maxImageCount &&
            imageCount > caps.maxImageCount) {
            imageCount = caps.maxImageCount;
        }

        VkCompositeAlphaFlagBitsKHR alpha =
            VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR;

        const VkCompositeAlphaFlagBitsKHR alphaOptions[] = {
            VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR,
            VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR,
            VK_COMPOSITE_ALPHA_PRE_MULTIPLIED_BIT_KHR,
            VK_COMPOSITE_ALPHA_POST_MULTIPLIED_BIT_KHR
        };

        bool foundAlpha = false;

        for (auto option : alphaOptions) {
            if (caps.supportedCompositeAlpha & option) {
                alpha = option;
                foundAlpha = true;
                break;
            }
        }

        if (!foundAlpha) {
            error = "Surface has no supported composite-alpha mode";
            return false;
        }

        VkSwapchainCreateInfoKHR ci{
            VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR
        };
        ci.surface = surface_;
        ci.minImageCount = imageCount;
        ci.imageFormat = chosen.format;
        ci.imageColorSpace = chosen.colorSpace;
        ci.imageExtent = extent_;
        ci.imageArrayLayers = 1;
        ci.imageUsage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
        ci.imageSharingMode = VK_SHARING_MODE_EXCLUSIVE;
        ci.preTransform = caps.currentTransform;
        ci.compositeAlpha = alpha;
        ci.presentMode = VK_PRESENT_MODE_FIFO_KHR;
        ci.clipped = VK_TRUE;

        VK_CHECK(vkCreateSwapchainKHR(
            device_, &ci, nullptr, &swapchain_
        ));

        VK_CHECK(vkGetSwapchainImagesKHR(
            device_, swapchain_, &imageCount, nullptr
        ));

        images_.resize(imageCount);

        VK_CHECK(vkGetSwapchainImagesKHR(
            device_, swapchain_, &imageCount, images_.data()
        ));

        images_.resize(imageCount);
        return true;
    }

    bool createRenderTargets() {
        for (VkImage image : images_) {
            VkImageViewCreateInfo vi{
                VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO
            };
            vi.image = image;
            vi.viewType = VK_IMAGE_VIEW_TYPE_2D;
            vi.format = format_;
            vi.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
            vi.subresourceRange.levelCount = 1;
            vi.subresourceRange.layerCount = 1;

            VkImageView view = VK_NULL_HANDLE;

            VK_CHECK(vkCreateImageView(
                device_, &vi, nullptr, &view
            ));

            views_.push_back(view);
        }

        VkAttachmentDescription attachment{};
        attachment.format = format_;
        attachment.samples = VK_SAMPLE_COUNT_1_BIT;
        attachment.loadOp = VK_ATTACHMENT_LOAD_OP_CLEAR;
        attachment.storeOp = VK_ATTACHMENT_STORE_OP_STORE;
        attachment.stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
        attachment.stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
        attachment.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        attachment.finalLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;

        VkAttachmentReference ref{
            0, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL
        };

        VkSubpassDescription sub{};
        sub.pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS;
        sub.colorAttachmentCount = 1;
        sub.pColorAttachments = &ref;

        VkRenderPassCreateInfo rp{
            VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO
        };
        rp.attachmentCount = 1;
        rp.pAttachments = &attachment;
        rp.subpassCount = 1;
        rp.pSubpasses = &sub;

        VK_CHECK(vkCreateRenderPass(
            device_, &rp, nullptr, &renderPass_
        ));

        for (VkImageView view : views_) {
            VkFramebufferCreateInfo fi{
                VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO
            };
            fi.renderPass = renderPass_;
            fi.attachmentCount = 1;
            fi.pAttachments = &view;
            fi.width = extent_.width;
            fi.height = extent_.height;
            fi.layers = 1;

            VkFramebuffer framebuffer = VK_NULL_HANDLE;

            VK_CHECK(vkCreateFramebuffer(
                device_, &fi, nullptr, &framebuffer
            ));

            framebuffers_.push_back(framebuffer);
        }

        return true;
    }

    bool createCommands() {
        VkCommandPoolCreateInfo pi{
            VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO
        };
        pi.queueFamilyIndex = queueFamily_;
        pi.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;

        VK_CHECK(vkCreateCommandPool(
            device_, &pi, nullptr, &commandPool_
        ));

        VkCommandBufferAllocateInfo ai{
            VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO
        };
        ai.commandPool = commandPool_;
        ai.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
        ai.commandBufferCount = 1;

        VK_CHECK(vkAllocateCommandBuffers(
            device_, &ai, &command_
        ));

        VkSemaphoreCreateInfo si{
            VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO
        };

        VK_CHECK(vkCreateSemaphore(
            device_, &si, nullptr, &acquired_
        ));
        VK_CHECK(vkCreateSemaphore(
            device_, &si, nullptr, &rendered_
        ));

        VkFenceCreateInfo fi{
            VK_STRUCTURE_TYPE_FENCE_CREATE_INFO
        };
        fi.flags = VK_FENCE_CREATE_SIGNALED_BIT;

        VK_CHECK(vkCreateFence(
            device_, &fi, nullptr, &fence_
        ));

        return true;
    }

    bool drawFrame() {
        VK_CHECK(vkWaitForFences(
            device_, 1, &fence_, VK_TRUE, UINT64_MAX
        ));

        uint32_t index = 0;

        VkResult acquire = vkAcquireNextImageKHR(
            device_, swapchain_, UINT64_MAX,
            acquired_, VK_NULL_HANDLE, &index
        );

        if (acquire != VK_SUCCESS &&
            acquire != VK_SUBOPTIMAL_KHR) {
            error = "vkAcquireNextImageKHR failed: " +
                    std::to_string(static_cast<int>(acquire));
            return false;
        }

        VK_CHECK(vkResetFences(device_, 1, &fence_));
        VK_CHECK(vkResetCommandBuffer(command_, 0));

        VkCommandBufferBeginInfo begin{
            VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO
        };
        VK_CHECK(vkBeginCommandBuffer(command_, &begin));

        VkClearValue clear{};
        clear.color.float32[0] = 0.04f;
        clear.color.float32[1] = 0.22f;
        clear.color.float32[2] = 0.62f;
        clear.color.float32[3] = 1.0f;

        VkRenderPassBeginInfo render{
            VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO
        };
        render.renderPass = renderPass_;
        render.framebuffer = framebuffers_[index];
        render.renderArea.extent = extent_;
        render.clearValueCount = 1;
        render.pClearValues = &clear;

        vkCmdBeginRenderPass(
            command_, &render, VK_SUBPASS_CONTENTS_INLINE
        );
        vkCmdEndRenderPass(command_);

        VK_CHECK(vkEndCommandBuffer(command_));

        VkPipelineStageFlags waitStage =
            VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;

        VkSubmitInfo submit{VK_STRUCTURE_TYPE_SUBMIT_INFO};
        submit.waitSemaphoreCount = 1;
        submit.pWaitSemaphores = &acquired_;
        submit.pWaitDstStageMask = &waitStage;
        submit.commandBufferCount = 1;
        submit.pCommandBuffers = &command_;
        submit.signalSemaphoreCount = 1;
        submit.pSignalSemaphores = &rendered_;

        VK_CHECK(vkQueueSubmit(queue_, 1, &submit, fence_));

        VkPresentInfoKHR present{
            VK_STRUCTURE_TYPE_PRESENT_INFO_KHR
        };
        present.waitSemaphoreCount = 1;
        present.pWaitSemaphores = &rendered_;
        present.swapchainCount = 1;
        present.pSwapchains = &swapchain_;
        present.pImageIndices = &index;

        VkResult presented = vkQueuePresentKHR(queue_, &present);

        VkResult idle = vkQueueWaitIdle(queue_);

        if (presented != VK_SUCCESS &&
            presented != VK_SUBOPTIMAL_KHR) {
            error = "vkQueuePresentKHR failed: " +
                    std::to_string(static_cast<int>(presented));
            return false;
        }

        if (idle != VK_SUCCESS) {
            error = "vkQueueWaitIdle failed: " +
                    std::to_string(static_cast<int>(idle));
            return false;
        }

        return true;
    }

    void cleanup() {
        if (device_) vkDeviceWaitIdle(device_);

        if (device_ && fence_)
            vkDestroyFence(device_, fence_, nullptr);
        if (device_ && acquired_)
            vkDestroySemaphore(device_, acquired_, nullptr);
        if (device_ && rendered_)
            vkDestroySemaphore(device_, rendered_, nullptr);

        if (device_) {
            for (auto fb : framebuffers_)
                vkDestroyFramebuffer(device_, fb, nullptr);
        }

        if (device_ && renderPass_)
            vkDestroyRenderPass(device_, renderPass_, nullptr);

        if (device_) {
            for (auto view : views_)
                vkDestroyImageView(device_, view, nullptr);
        }

        if (device_ && commandPool_)
            vkDestroyCommandPool(device_, commandPool_, nullptr);
        if (device_ && swapchain_)
            vkDestroySwapchainKHR(device_, swapchain_, nullptr);
        if (device_)
            vkDestroyDevice(device_, nullptr);
        if (instance_ && surface_)
            vkDestroySurfaceKHR(instance_, surface_, nullptr);
        if (instance_)
            vkDestroyInstance(instance_, nullptr);
    }
};

extern "C" JNIEXPORT jstring JNICALL
Java_org_utlover65_astravulkanprobe_MainActivity_presentVulkanFrame(
    JNIEnv* env, jclass, jobject javaSurface
) {
    if (!javaSurface)
        return env->NewStringUTF("FAIL: Android Surface is null");

    ANativeWindow* window = ANativeWindow_fromSurface(env, javaSurface);

    if (!window)
        return env->NewStringUTF("FAIL: ANativeWindow_fromSurface returned null");

    Renderer renderer(window);
    bool ok = renderer.run();

    std::string message = ok
        ? "PASS: Vulkan frame submitted and presented.\n\n"
          "A blue frame should have appeared. Android surface, "
          "swapchain, render pass, and presentation calls completed.\n"
          "Dawn and GTA are not tested."
        : "FAIL: " + renderer.error;

    ANativeWindow_release(window);
    return env->NewStringUTF(message.c_str());
}
