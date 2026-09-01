import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val vesqenVersion = Properties().apply {
    rootProject.file("version.properties").inputStream().use(::load)
}
val vesqenVersionName = requireNotNull(vesqenVersion.getProperty("versionName")) {
    "versionName is required in version.properties"
}
val vesqenVersionCode = requireNotNull(vesqenVersion.getProperty("versionCode")) {
    "versionCode is required in version.properties"
}.toInt()

require(vesqenVersionName.matches(Regex("(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)(-[0-9A-Za-z.-]+)?"))) {
    "versionName must use semantic versioning"
}
require(vesqenVersionCode > 0) { "versionCode must be positive" }

android {
    namespace = "io.github.sumirenokai.vesqen"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "io.github.sumirenokai.vesqen"
        minSdk = 26
        targetSdk = 36
        versionCode = vesqenVersionCode
        versionName = vesqenVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        buildConfig = true
        compose = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.runtime.saveable)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
