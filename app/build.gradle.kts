plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// No kotlin-serialization and no KSP any more. Both were here for the data layer — the
// wire types and Room's generated code — and both went to :core with it. Nothing left in
// this module is annotated.

// Release signing, resolved by the script both apps share. It publishes these through
// `extra` and leaves the signingConfigs block to each app — see the script for why.
apply(from = rootProject.file("gradle/release-signing.gradle.kts"))

val releaseKeystore = extra["releaseKeystore"] as File?
val releaseStorePassword = extra["releaseStorePassword"] as String?
val releaseKeyAlias = extra["releaseKeyAlias"] as String
val releaseKeyPassword = extra["releaseKeyPassword"] as String?
val hasReleaseSigning = extra["hasReleaseSigning"] as Boolean

android {
    namespace = "com.qualityverifier"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.qualityverifier"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        // The one value that points the app at its backend. Compiled in, so changing it
        // means a release — which is why it is worth picking a hostname you will keep.
        //
        // Prompts are no longer fetched here: the server assembles the system prompt from
        // the protocols on GitHub, so a client cannot substitute one. PROMPT_BASE_URL and
        // the app's copy of GitHubPromptRepository went with that change.
        buildConfigField(
            "String",
            "SERVER_BASE_URL",
            "\"https://kagua.gradykilleen.me/\"",
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

dependencies {
    implementation(project(":shared"))
    // The colour scheme, type scale and verdict palette. Shared with Fundi Bora.
    implementation(project(":design"))
    // Tokens, the chat client, the database, the image store, the sync queue and the
    // location fix. Shared with Fundi Bora, which is why they are no longer in here.
    implementation(project(":core"))
    // The camera, the plan runner and the physical tests. Likewise.
    implementation(project(":capture"))
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

    // Room, the encrypted prefs, EXIF and okhttp all went to :core; CameraX went to
    // :capture. What is left here is what this module's own code names directly.
    implementation(libs.coil.compose)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
