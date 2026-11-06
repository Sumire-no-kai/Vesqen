plugins {
    alias(libs.plugins.android.library)
}

// M7: Vesqen's own USB output. Every build of the app contains it (M7-3), but nothing here runs
// unless the app asks for it, and the native library loads only when the own output starts (D5).
android {
    namespace = "io.github.sumirenokai.vesqen.usb"
    compileSdk {
        version = release(36)
    }

    // CI installs the same versions (.github/workflows/android.yml).
    ndkVersion = "30.0.16248370"

    defaultConfig {
        minSdk = 28
        consumerProguardFiles("consumer-rules.pro")
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "4.1.2"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    testImplementation(libs.junit)
}
