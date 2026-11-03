plugins {
    alias(libs.plugins.android.library)
}

// M7: Vesqen's own USB output. Nothing here runs unless the app asks for it, and no release
// build depends on this module until M7-3.
android {
    namespace = "io.github.sumirenokai.vesqen.usb"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        minSdk = 28
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    testImplementation(libs.junit)
}
