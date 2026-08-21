import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// Release signing material comes from `keystore.properties` (local, gitignored) or from
// environment variables (CI). Absent both, the release build falls back to the debug key
// so a fork can still produce an installable APK without holding the real one.
//
// Pass -PrequireReleaseSigning to turn a missing key into a build failure. CI does that
// when publishing, because a silently debug-signed "release" would refuse to install over
// a previous build and the reason would be invisible.
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun signingValue(environmentVariable: String, property: String): String? =
    (System.getenv(environmentVariable) ?: keystoreProperties.getProperty(property))
        ?.takeIf { it.isNotBlank() }

val releaseStorePath = signingValue("QV_KEYSTORE_FILE", "storeFile")
val releaseStorePassword = signingValue("QV_KEYSTORE_PASSWORD", "storePassword")
// The alias lives inside the keystore and is not sensitive, so it is a project
// convention rather than a secret. Held as a secret it was worse than useless: GitHub
// redacts every occurrence of a secret's value in logs, so "upload" became "***"
// everywhere, including in unrelated step names.
val defaultKeyAlias = "upload"
val releaseKeyAlias = signingValue("QV_KEY_ALIAS", "keyAlias") ?: defaultKeyAlias
val releaseKeyPassword = signingValue("QV_KEY_PASSWORD", "keyPassword")

val releaseKeystore = releaseStorePath?.let(::File)?.takeIf { it.isFile }
val hasReleaseSigning = releaseKeystore != null &&
    releaseStorePassword != null && releaseKeyPassword != null

if (providers.gradleProperty("requireReleaseSigning").isPresent && !hasReleaseSigning) {
    val missing = buildList {
        if (releaseStorePath == null) add("QV_KEYSTORE_FILE")
        else if (releaseKeystore == null) add("QV_KEYSTORE_FILE (no file at $releaseStorePath)")
        if (releaseStorePassword == null) add("QV_KEYSTORE_PASSWORD")
        if (releaseKeyPassword == null) add("QV_KEY_PASSWORD")
    }
    throw GradleException(
        "Release signing was required but is not configured. Missing: " +
            missing.joinToString(", ") +
            ". Set them as environment variables or in keystore.properties."
    )
}

android {
    namespace = "com.qualityverifier"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.qualityverifier"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        // Phase 1: prompts are fetched from raw GitHub. Change this one value to
        // repoint at a different repo or branch. Phase 2 replaces the whole
        // GitHubPromptRepository with a server-backed one.
        buildConfigField(
            "String",
            "PROMPT_BASE_URL",
            "\"https://raw.githubusercontent.com/gkilleen33/quality-verification-app/main/prompts/\"",
        )
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            // A stable signing key is what lets a new build install over an older one.
            // Debug keys are generated per machine and per CI run, so builds signed with
            // them are rejected as a signature mismatch on upgrade.
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                logger.lifecycle(
                    "No release keystore configured; signing the release build with the " +
                        "debug key. It will not install over a differently signed build."
                )
                signingConfigs.getByName("debug")
            }
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    debugImplementation(libs.androidx.ui.tooling)

    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.security.crypto)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.coil.compose)
    implementation(libs.androidx.exifinterface)
    // In-app capture rather than the system camera intent: the shot instruction has to
    // sit on top of the live preview, which an intent cannot do.
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
}
