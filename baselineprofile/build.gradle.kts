plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.baselineprofile)
}

// #50: records the Baseline Profile on a connected API 33+ (or rooted) device. The app keeps the
// result in its own sources; nothing in this module ships.
android {
    namespace = "io.github.sumirenokai.vesqen.baselineprofile"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        minSdk = 28
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    targetProjectPath = ":app"

    // The test module must declare the app's flavors to select one of its APKs.
    flavorDimensions += "distribution"
    productFlavors {
        create("github") { dimension = "distribution" }
        create("play") { dimension = "distribution" }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

baselineProfile {
    useConnectedDevices = true
}

androidComponents {
    // Profiles come from the plugin's nonMinifiedRelease target; a debuggable app records nothing useful.
    // This also keeps the module out of CI's unprefixed lintDebug and assembleDebug.
    beforeVariants(selector().withBuildType("debug")) { it.enable = false }
}

dependencies {
    implementation(libs.androidx.junit)
    implementation(libs.androidx.benchmark.macro.junit4)
}
