#include <jni.h>
#include <vulkan/vulkan.h>
#include <sstream>
#include <string>
#include <vector>

static std::string ver(uint32_t v) {
    std::ostringstream s; s << VK_VERSION_MAJOR(v) << "." << VK_VERSION_MINOR(v) << "." << VK_VERSION_PATCH(v);
    return s.str();
}
static bool ext(const std::vector<VkExtensionProperties>& xs, const char* name) {
    for (const auto& x : xs) if (std::string(x.extensionName) == name) return true;
    return false;
}
extern "C" JNIEXPORT jstring JNICALL
Java_org_utlover65_astravulkanprobe_MainActivity_getVulkanReport(JNIEnv* env, jclass) {
    std::ostringstream o;
    o << "ASTRA VULKAN PROBE\n=================\n";
    uint32_t loader = VK_API_VERSION_1_0;
    auto getVer = reinterpret_cast<PFN_vkEnumerateInstanceVersion>(
        vkGetInstanceProcAddr(VK_NULL_HANDLE, "vkEnumerateInstanceVersion"));
    if (getVer && getVer(&loader) != VK_SUCCESS) loader = VK_API_VERSION_1_0;
    o << "Loader API: " << ver(loader) << "\n";
        // VK_KHR_android_surface is an instance extension, not a device extension.
    uint32_t instanceExtCount = 0;
    VkResult instanceExtResult =
        vkEnumerateInstanceExtensionProperties(nullptr, &instanceExtCount, nullptr);

    std::vector<VkExtensionProperties> instanceExts;
    if (instanceExtResult == VK_SUCCESS && instanceExtCount > 0) {
        instanceExts.resize(instanceExtCount);
        instanceExtResult = vkEnumerateInstanceExtensionProperties(
            nullptr, &instanceExtCount, instanceExts.data());
        if (instanceExtResult != VK_SUCCESS) instanceExts.clear();
    }

    o << "Instance extensions: android_surface="
      << ext(instanceExts, "VK_KHR_android_surface") << "\n";
    VkApplicationInfo ai{VK_STRUCTURE_TYPE_APPLICATION_INFO};
    ai.pApplicationName = "Astra Vulkan Probe"; ai.apiVersion = loader < VK_API_VERSION_1_1 ? loader : VK_API_VERSION_1_1;
    VkInstanceCreateInfo ci{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO}; ci.pApplicationInfo = &ai;
    VkInstance instance = VK_NULL_HANDLE;
    VkResult r = vkCreateInstance(&ci, nullptr, &instance);
    if (r != VK_SUCCESS) {
        o << "vkCreateInstance FAILED: " << static_cast<int>(r) << "\n";
        return env->NewStringUTF(o.str().c_str());

#include <jni.h>
#include <vulkan/vulkan.h>

#include <sstream>
#include <string>
#include <vector>

static std::string ver(uint32_t v) {
    std::ostringstream s;
    s << VK_VERSION_MAJOR(v) << "."
      << VK_VERSION_MINOR(v) << "."
      << VK_VERSION_PATCH(v);
    return s.str();
}

static bool ext(
    const std::vector<VkExtensionProperties>& xs,
    const char* name
) {
    for (const auto& x : xs) {
        if (std::string(x.extensionName) == name) {
            return true;
        }
    }
    return false;
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_utlover65_astravulkanprobe_MainActivity_getVulkanReport(
    JNIEnv* env,
    jclass
) {
    std::ostringstream o;
    o << "ASTRA VULKAN PROBE\n=================\n";

    // Query the Vulkan loader version.
    uint32_t loader = VK_API_VERSION_1_0;

    auto getVer = reinterpret_cast<PFN_vkEnumerateInstanceVersion>(
        vkGetInstanceProcAddr(
            VK_NULL_HANDLE,
            "vkEnumerateInstanceVersion"
        )
    );

    if (getVer && getVer(&loader) != VK_SUCCESS) {
        loader = VK_API_VERSION_1_0;
    }

    o << "Loader API: " << ver(loader) << "\n";

    // VK_KHR_android_surface is an INSTANCE extension,
    // not a physical-device extension.
    uint32_t instanceExtCount = 0;

    VkResult instanceExtResult =
        vkEnumerateInstanceExtensionProperties(
            nullptr,
            &instanceExtCount,
            nullptr
        );

    std::vector<VkExtensionProperties> instanceExts;

    if (instanceExtResult == VK_SUCCESS && instanceExtCount > 0) {
        instanceExts.resize(instanceExtCount);

        instanceExtResult =
            vkEnumerateInstanceExtensionProperties(
                nullptr,
                &instanceExtCount,
                instanceExts.data()
            );

        if (instanceExtResult != VK_SUCCESS) {
            instanceExts.clear();
        } else {
            instanceExts.resize(instanceExtCount);
        }
    }

    o << "Instance extensions: android_surface="
      << ext(instanceExts, "VK_KHR_android_surface")
      << "\n";

    // Create a Vulkan instance.
    VkApplicationInfo ai{VK_STRUCTURE_TYPE_APPLICATION_INFO};
    ai.pApplicationName = "Astra Vulkan Probe";
    ai.apiVersion =
        loader < VK_API_VERSION_1_1
            ? loader
            : VK_API_VERSION_1_1;

    VkInstanceCreateInfo ci{
        VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO
    };
    ci.pApplicationInfo = &ai;

    VkInstance instance = VK_NULL_HANDLE;

    VkResult r = vkCreateInstance(
        &ci,
        nullptr,
        &instance
    );

    if (r != VK_SUCCESS) {
        o << "vkCreateInstance FAILED: "
          << static_cast<int>(r) << "\n";

        return env->NewStringUTF(o.str().c_str());
    }

    // Enumerate physical devices.
    uint32_t n = 0;
    r = vkEnumeratePhysicalDevices(instance, &n, nullptr);

    if (r != VK_SUCCESS || n == 0) {
        o << "No physical devices. VkResult="
          << static_cast<int>(r) << "\n";

        vkDestroyInstance(instance, nullptr);
        return env->NewStringUTF(o.str().c_str());
    }

    std::vector<VkPhysicalDevice> ds(n);

    r = vkEnumeratePhysicalDevices(
        instance,
        &n,
        ds.data()
    );

    if (r != VK_SUCCESS) {
        o << "Physical-device enumeration failed. VkResult="
          << static_cast<int>(r) << "\n";

        vkDestroyInstance(instance, nullptr);
        return env->NewStringUTF(o.str().c_str());
    }

    ds.resize(n);

    o << "Physical devices: " << n << "\n\n";

    for (uint32_t i = 0; i < n; ++i) {
        VkPhysicalDeviceProperties p{};
        vkGetPhysicalDeviceProperties(ds[i], &p);

        VkPhysicalDeviceFeatures f{};
        vkGetPhysicalDeviceFeatures(ds[i], &f);

        // Enumerate DEVICE extensions.
        uint32_t ec = 0;

        VkResult extensionResult =
            vkEnumerateDeviceExtensionProperties(
                ds[i],
                nullptr,
                &ec,
                nullptr
            );

        std::vector<VkExtensionProperties> es;

        if (extensionResult == VK_SUCCESS && ec > 0) {
            es.resize(ec);

            extensionResult =
                vkEnumerateDeviceExtensionProperties(
                    ds[i],
                    nullptr,
                    &ec,
                    es.data()
                );

            if (extensionResult != VK_SUCCESS) {
                es.clear();
            } else {
                es.resize(ec);
            }
        }

        // Query sampled-image format support.
        VkFormatProperties bc{};
        VkFormatProperties astc{};
        VkFormatProperties etc{};

        vkGetPhysicalDeviceFormatProperties(
            ds[i],
            VK_FORMAT_BC1_RGBA_UNORM_BLOCK,
            &bc
        );

        vkGetPhysicalDeviceFormatProperties(
            ds[i],
            VK_FORMAT_ASTC_4x4_UNORM_BLOCK,
            &astc
        );

        vkGetPhysicalDeviceFormatProperties(
            ds[i],
            VK_FORMAT_ETC2_R8G8B8_UNORM_BLOCK,
            &etc
        );

        o << "[" << i << "] " << p.deviceName << "\n";

        o << "  Vendor/device: 0x"
          << std::hex << p.vendorID
          << "/0x" << p.deviceID
          << std::dec << "\n";

        o << "  Device API: "
          << ver(p.apiVersion) << "\n";

        o << "  Feature flags:"
          << " depthBiasClamp=" << f.depthBiasClamp
          << " fragmentStoresAndAtomics="
          << f.fragmentStoresAndAtomics
          << " fullDrawIndexUint32="
          << f.fullDrawIndexUint32
          << " imageCubeArray=" << f.imageCubeArray
          << " independentBlend=" << f.independentBlend
          << " sampleRateShading=" << f.sampleRateShading
          << "\n";

        o << "  Sampled formats: BC1="
          << !!(bc.optimalTilingFeatures
                & VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT)
          << " ASTC4x4="
          << !!(astc.optimalTilingFeatures
                & VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT)
          << " ETC2="
          << !!(etc.optimalTilingFeatures
                & VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT)
          << "\n";

        // Report extensions in their correct categories.
        o << "  Device extensions: swapchain="
          << ext(es, "VK_KHR_swapchain")
          << "\n";

        o << "  Limits: maxImageDimension2D="
          << p.limits.maxImageDimension2D
          << " maxStorageBufferRange="
          << p.limits.maxStorageBufferRange
          << " maxComputeWorkGroupInvocations="
          << p.limits.maxComputeWorkGroupInvocations
          << "\n\n";
    }

    o << "This is a driver probe only; "
      << "Android surface presentation, Dawn, and GTA "
      << "have not been tested by this app.\n";

    vkDestroyInstance(instance, nullptr);

    return env->NewStringUTF(o.str().c_str());
}

