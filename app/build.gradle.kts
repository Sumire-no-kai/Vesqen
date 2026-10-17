import com.android.build.api.artifact.SingleArtifact
import io.github.sumirenokai.vesqen.build.GenerateThirdPartyLicenses
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import java.io.File
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.baselineprofile)
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

// Only the GitHub flavor has an updater that reads this.
val debugUpdateEndpoint = providers.gradleProperty("vesqen.updateManifestBaseUrl")
    .orElse("https://vesqen.sumirenokai.com/updates/").get()
require(debugUpdateEndpoint.matches(Regex("https://[A-Za-z0-9._~:/%-]+/"))) {
    "The Debug update manifest base URL must be HTTPS and end with /"
}

// #119: whether this build charges for paid features. Off for the betas and 1.x, which stay free and
// count no trial; it turns on with 2.0, once Vesqen's own USB output works on phones without the
// official path (owner decision 2026-10-13). `-Pvesqen.premiumEnforced=true` builds a charging debug APK for device tests.
val premiumEnforced = providers.gradleProperty("vesqen.premiumEnforced").orElse("false").get()
require(premiumEnforced == "true" || premiumEnforced == "false") {
    "vesqen.premiumEnforced must be true or false"
}

// Empty until the owner deploys #70. Shared across build types, with no production host in source.
val usageEndpoint = providers.gradleProperty("vesqen.usageEndpoint").orElse("").get()
require(usageEndpoint.isEmpty() || usageEndpoint.matches(Regex("https://[A-Za-z0-9.-]+(:[0-9]+)?/v1/usage"))) {
    "Usage endpoint must be empty or an HTTPS /v1/usage URL"
}

android {
    namespace = "io.github.sumirenokai.vesqen"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        buildConfigField("String", "USAGE_ENDPOINT", "\"$usageEndpoint\"")
        buildConfigField("String", "UPDATE_MANIFEST_BASE_URL", "\"https://vesqen.sumirenokai.com/updates/\"")
        // #96: the owner's remote switch, read only right before a usage ping or report upload.
        buildConfigField("String", "SERVICE_SWITCH_URL", "\"https://vesqen.sumirenokai.com/service/status.json\"")
        buildConfigField("boolean", "PREMIUM_ENFORCED", premiumEnforced)
        applicationId = "io.github.sumirenokai.vesqen"
        minSdk = 26
        targetSdk = 36
        versionCode = vesqenVersionCode
        versionName = vesqenVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        manifestPlaceholders["appLabel"] = "@string/app_name"
    }

    // #47: one app in two channels, both signed with the application signing key so either can
    // replace the other. Only the GitHub flavor carries the self-updater and its install permission.
    flavorDimensions += "distribution"
    productFlavors {
        create("github") {
            dimension = "distribution"
            isDefault = true
            buildConfigField("String", "DISTRIBUTION", "\"github\"")
        }
        create("play") {
            dimension = "distribution"
            buildConfigField("String", "DISTRIBUTION", "\"play\"")
        }
    }

    buildTypes {
        debug {
            buildConfigField("boolean", "DEVELOPER_DIAGNOSTICS_ENABLED", "true")
            buildConfigField("String", "UPDATE_MANIFEST_BASE_URL", "\"$debugUpdateEndpoint\"")
        }
        create("deviceTest") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".devicetest"
            manifestPlaceholders["appLabel"] = "Vesqen Test"
            matchingFallbacks += listOf("debug")
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
    // Instrumentation runs under a separate Android UID and private data directory.
    testBuildType = "deviceTest"
    sourceSets.getByName("deviceTest").kotlin.directories.add("src/debug/java")

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        buildConfig = true
        compose = true
    }
}

// #50: one Baseline Profile and startup profile for both channels and the profile build type, kept in
// src/main/generated/baselineProfiles. :baselineprofile records them on the GitHub release build;
// regenerate with `./gradlew :app:generateBaselineProfile` (baselineprofile/README.md).
baselineProfile {
    mergeIntoMain = true
    dexLayoutOptimization = true
    variants {
        create("githubRelease") { from(project(":baselineprofile")) }
    }
}
androidComponents {
    // The plugin copies `optimization.enable` from release into its nonMinified build types, and its
    // `isMinifyEnabled = false` cannot turn R8 off, which would record obfuscated names.
    finalizeDsl { android ->
        android.buildTypes.matching { it.name.startsWith("nonMinified") }.configureEach {
            optimization { enable = false }
        }
    }
}

val checkNoUncontrolledProductionLogs by tasks.registering {
    group = "verification"
    description = "Reject uncontrolled logcat and console writes from production sources."
    val productionSources = files(fileTree("src/main"), fileTree("src/github"), fileTree("src/play")).asFileTree.matching {
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
            val url = urlPattern.find(strings.readText())?.groupValues?.get(1)?.trim()
            // A translation without its own address uses the default one, which must have it.
            if (url == null && strings.parentFile.name != "values") return@forEach
            if (url == null || !url.startsWith("https://")) {
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
    // Every language: Japanese and Traditional Chinese override the address too.
    stringResources.from(fileTree("src/main/res") { include("values*/strings.xml") })
}

// Every Release artifact of either flavor requires the same finalized privacy policy.
val releasePackagingTask = Regex("(bundle|assemble|package)(Github|Play)?Release")
tasks.matching { releasePackagingTask.matches(it.name) }.configureEach {
    dependsOn(checkPrivacyPolicyFinal)
}

/** Fails a Play Release whose merged manifest requests a permission outside the Play allowlist. */
abstract class CheckPlayPermissionsTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mergedManifest: RegularFileProperty

    @get:Input
    abstract val allowedPermissions: SetProperty<String>

    @TaskAction
    fun verifyPermissions() {
        val requested = Regex("""<uses-permission(?:-sdk-23)?\s[^>]*android:name="([^"]+)"""")
            .findAll(mergedManifest.get().asFile.readText()).map { it.groupValues[1] }.toSet()
        val unexpected = requested - allowedPermissions.get()
        check(unexpected.isEmpty()) {
            "The Play build requests permissions outside its allowlist: ${unexpected.sorted().joinToString()}"
        }
    }
}

// Google Play rejects REQUEST_INSTALL_PACKAGES for a music player, and its policy forbids self-updates.
val playPermissions = setOf(
    "android.permission.READ_MEDIA_AUDIO",
    "android.permission.READ_EXTERNAL_STORAGE",
    "android.permission.POST_NOTIFICATIONS",
    "android.permission.FOREGROUND_SERVICE",
    "android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK",
    "android.permission.WAKE_LOCK",
    "android.permission.MODIFY_AUDIO_SETTINGS",
    "android.permission.BLUETOOTH_CONNECT",
    "android.permission.INTERNET",
    "android.permission.ACCESS_NETWORK_STATE",
)

val checkThirdPartyLicenses by tasks.registering {
    group = "verification"
    description = "Validate and generate third-party license catalogs for every enabled variant."
}
tasks.named("check") { dependsOn(checkThirdPartyLicenses) }

androidComponents {
    beforeVariants(selector().withBuildType("debug")) {
        it.hostTests[com.android.build.api.variant.HostTestBuilder.UNIT_TEST_TYPE]?.enable = true
    }
    onVariants(selector().withFlavor("distribution" to "play").withBuildType("release")) { variant ->
        val variantName = variant.name.replaceFirstChar(Char::uppercaseChar)
        val checkPermissions = tasks.register<CheckPlayPermissionsTask>("check${variantName}Permissions") {
            mergedManifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
            allowedPermissions.set(variant.applicationId.map { id ->
                playPermissions + "$id.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
            })
        }
        tasks.named("check") { dependsOn(checkPermissions) }
        tasks.matching { it.name == "bundle$variantName" || it.name == "assemble$variantName" }.configureEach {
            dependsOn(checkPermissions)
        }
    }
    onVariants { variant ->
        val runtimeArtifacts = variant.runtimeConfiguration.incoming.artifacts
        val generateLicenses = tasks.register<GenerateThirdPartyLicenses>(
            "generate${variant.name.replaceFirstChar(Char::uppercaseChar)}ThirdPartyLicenses",
        ) {
            outputDirectory.set(layout.buildDirectory.dir("generated/thirdPartyLicenses/${variant.name}"))
            this.runtimeArtifacts.from(runtimeArtifacts.artifactFiles)
            artifactNamesByCoordinate.set(runtimeArtifacts.resolvedArtifacts.map { artifacts ->
                artifacts.associate { artifact ->
                    val id = artifact.id.componentIdentifier
                    require(id is ModuleComponentIdentifier) { "Review non-Maven runtime dependency: $id" }
                    "${id.group}:${id.module}:${id.version}" to artifact.file.name
                }
            })
            catalogDirectory.set(rootProject.layout.projectDirectory.dir("third-party-licenses"))
            resourceDirectory.set(layout.projectDirectory.dir("src/main/res"))
            fontLicenseDirectory.set(layout.projectDirectory.dir("src/main/assets/licenses/fonts"))
        }
        variant.sources.assets?.addGeneratedSourceDirectory(
            generateLicenses,
            GenerateThirdPartyLicenses::getOutputDirectory,
        )
        checkThirdPartyLicenses.configure { dependsOn(generateLicenses) }
        variant.hostTests.values.forEach { hostTest ->
            hostTest.configureTestTask { test ->
                test.dependsOn(generateLicenses)
                test.inputs.file(generateLicenses.flatMap { it.outputDirectory.file("licenses/third-party.xml") })
                test.systemProperty(
                    "licenses.generatedFile",
                    generateLicenses.get().outputDirectory.file("licenses/third-party.xml").get().asFile.absolutePath,
                )
            }
        }
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

// The documented build-type commands keep covering both flavors (#47).
mapOf(
    "testDebugUnitTest" to listOf("testGithubDebugUnitTest", "testPlayDebugUnitTest"),
    "lintDebug" to listOf("lintGithubDebug", "lintPlayDebug"),
    "assembleDeviceTestAndroidTest" to listOf("assembleGithubDeviceTestAndroidTest", "assemblePlayDeviceTestAndroidTest"),
).forEach { (name, flavorTasks) ->
    tasks.register(name) {
        description = "Runs ${flavorTasks.joinToString(" and ")}."
        dependsOn(flavorTasks)
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
    implementation(libs.androidx.profileinstaller)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
    // androidx.test resolves its Bootstrap/Empty helper activities from the app under test first.
    // Merging them into the isolated host keeps every test transition inside one foreground app;
    // from the test APK they become cross-app launches that vivo prompts for and then freezes.
    "deviceTestImplementation"(libs.androidx.test.core)
    "deviceTestImplementation"(libs.androidx.compose.ui.test.manifest)
    "deviceTestImplementation"(libs.androidx.compose.ui.tooling)
}
