import java.io.File
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
        debug {
            buildConfigField("boolean", "DEVELOPER_DIAGNOSTICS_ENABLED", "true")
        }
        release {
            buildConfigField("boolean", "DEVELOPER_DIAGNOSTICS_ENABLED", "false")
            optimization {
                enable = true
            }
        }
        create("profile") {
            initWith(getByName("release"))
            buildConfigField("boolean", "DEVELOPER_DIAGNOSTICS_ENABLED", "false")
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
            isDebuggable = false
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

val checkNoUncontrolledProductionLogs by tasks.registering {
    group = "verification"
    description = "Reject uncontrolled logcat and console writes from production sources."
    val productionSources = fileTree("src/main") {
        include("**/*.kt", "**/*.java")
    }
    inputs.files(productionSources)
    val sourceRootPath = layout.projectDirectory.dir("src/main").asFile.absolutePath
    doLast {
        val forbiddenPatterns = listOf(
            Regex("\\bandroid\\.util\\.Log\\b"),
            Regex("\\bLog\\.(?:v|d|i|w|e|wtf|println)\\s*\\("),
            Regex("\\b(?:print|println)\\s*\\("),
            Regex("\\b(?:java\\.lang\\.)?System\\.(?:out|err)\\b"),
            Regex("\\.printStackTrace\\s*\\("),
            Regex("\\bjava\\.util\\.logging\\b"),
            Regex("\\bTimber\\."),
        )
        val sourceRoot = File(sourceRootPath)
        val violations = inputs.files.files.filter { source ->
            val text = source.readText()
            forbiddenPatterns.any { pattern -> pattern.containsMatchIn(text) }
        }
        check(violations.isEmpty()) {
            "Uncontrolled production logging is forbidden; route structured diagnostics through the diagnostics module: " +
                violations.joinToString { it.relativeTo(sourceRoot).invariantSeparatorsPath }
        }
    }
}

tasks.named("preBuild") {
    dependsOn(checkNoUncontrolledProductionLogs)
}

/** Copies the published privacy policy into the app, so the in-app text is the web text. */
abstract class PackagePrivacyPolicyTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val englishPolicy: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val chinesePolicy: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun packagePolicies() {
        val privacyDirectory = outputDirectory.get().dir("privacy").asFile
        privacyDirectory.mkdirs()
        englishPolicy.get().asFile.copyTo(privacyDirectory.resolve("en.md"), overwrite = true)
        chinesePolicy.get().asFile.copyTo(privacyDirectory.resolve("zh-CN.md"), overwrite = true)
    }
}

/** Blocks store bundles while the policy is a draft or the app has no web address to link to. */
abstract class CheckPrivacyPolicyFinalTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val policies: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val stringResources: ConfigurableFileCollection

    @TaskAction
    fun verifyPolicy() {
        val draftMarkers = listOf("TBD", "待定", "Draft", "草案")
        val urlPattern = Regex("""<string name="privacy_policy_url">([^<]*)</string>""")
        val problems = mutableListOf<String>()
        policies.files.forEach { policy ->
            val text = policy.readText()
            draftMarkers.filter(text::contains).forEach { marker ->
                problems += "${policy.name} still contains \"$marker\""
            }
        }
        stringResources.files.forEach { strings ->
            val url = urlPattern.find(strings.readText())?.groupValues?.get(1)?.trim().orEmpty()
            if (!url.startsWith("https://")) {
                problems += "${strings.parentFile.name}/${strings.name} has no https privacy_policy_url"
            }
        }
        check(problems.isEmpty()) {
            "The privacy policy is not ready for a release bundle:\n" + problems.joinToString("\n") { "- $it" }
        }
    }
}

val privacyPolicyFiles = listOf(
    rootProject.layout.projectDirectory.file("docs/PRIVACY_POLICY.md"),
    rootProject.layout.projectDirectory.file("docs/PRIVACY_POLICY.zh-CN.md"),
)

val checkPrivacyPolicyFinal by tasks.registering(CheckPrivacyPolicyFinalTask::class) {
    group = "verification"
    description = "Reject release bundles whose privacy policy is a draft or has no web address."
    policies.from(privacyPolicyFiles)
    stringResources.from(
        layout.projectDirectory.file("src/main/res/values/strings.xml"),
        layout.projectDirectory.file("src/main/res/values-zh-rCN/strings.xml"),
    )
}

// Only the Play bundle is gated: CI assembles unsigned APKs from the draft policy, and the GitHub
// APK release step runs checkPrivacyPolicyFinal explicitly (docs/M4_BETA_RELEASE.md).
tasks.matching { it.name == "bundleRelease" }.configureEach {
    dependsOn(checkPrivacyPolicyFinal)
}

androidComponents {
    onVariants { variant ->
        val packagePrivacyPolicy = tasks.register<PackagePrivacyPolicyTask>(
            "package${variant.name.replaceFirstChar(Char::uppercaseChar)}PrivacyPolicy",
        ) {
            englishPolicy.set(privacyPolicyFiles[0])
            chinesePolicy.set(privacyPolicyFiles[1])
        }
        variant.sources.assets?.addGeneratedSourceDirectory(
            packagePrivacyPolicy,
            PackagePrivacyPolicyTask::outputDirectory,
        )
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
