plugins { id("com.android.application") }
val nativeEngineBuild = providers.gradleProperty("nativeEngine").orNull == "true"
android {
    namespace = "org.utlover65.astravulkanprobe"
    compileSdk = 35
    ndkVersion = "27.2.12479018"
    defaultConfig {
        applicationId = "org.utlover65.astravulkanprobe"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                if (nativeEngineBuild) arguments += "-DBUILD_NATIVE_GTA_ENGINE=ON"
            }
        }
    }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    buildTypes { release { isMinifyEnabled = false } }
}
