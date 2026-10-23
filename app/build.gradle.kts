plugins { id("com.android.application") }

val webOnlyBuild = providers.gradleProperty("webOnly").orNull == "true"
val nativeEngineBuild = providers.gradleProperty("nativeEngine").orNull == "true"
val nativeAotBuild = providers.gradleProperty("nativeAot").orNull == "true"
require(!(nativeEngineBuild && nativeAotBuild)) { "nativeEngine and nativeAot are mutually exclusive" }
require(!(webOnlyBuild && (nativeEngineBuild || nativeAotBuild))) {
    "webOnly cannot be combined with nativeEngine or nativeAot"
}

android {
    namespace = "org.utlover65.astravulkanprobe"
    compileSdk = 35
    if (!webOnlyBuild) ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "org.utlover65.astravulkanprobe"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.2-webview"
        if (!webOnlyBuild) {
            ndk { abiFilters += "arm64-v8a" }
            externalNativeBuild {
                cmake {
                    cppFlags += "-std=c++17"
                    if (nativeEngineBuild) arguments += "-DBUILD_NATIVE_GTA_ENGINE=ON"
                    if (nativeAotBuild) arguments += "-DBUILD_NATIVE_GTAV_AOT=ON"
                }
            }
        }
    }

    if (!webOnlyBuild) {
        externalNativeBuild {
            cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" }
        }
    }

    buildTypes { release { isMinifyEnabled = false } }
}

dependencies {
    implementation("androidx.webkit:webkit:1.17.1")
}
