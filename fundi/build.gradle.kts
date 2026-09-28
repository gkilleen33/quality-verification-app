plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Fundi Bora: the producer-facing app. See docs/fundi-bora.md.
//
// A second application module rather than a flavour of Kagua. The two share a backend and
// four libraries, but nothing else: separate accounts, separate invite codes, separate
// entries in the launcher, and — since the chat endpoints were split — no way for either
// to reach the other's prompt. A flavour would have made "which app am I" a runtime
// question in code that both ship.
//
// It owns almost nothing. The camera and plan runner are :capture, the data layer is
// :core, the look is :design, and the vocabularies and parsing are :shared. What lives
// here is the producer's own screens and the navigation between them.
apply(from = rootProject.file("gradle/release-signing.gradle.kts"))

val releaseKeystore = extra["releaseKeystore"] as File?
val releaseStorePassword = extra["releaseStorePassword"] as String?
val releaseKeyAlias = extra["releaseKeyAlias"] as String
val releaseKeyPassword = extra["releaseKeyPassword"] as String?
val hasReleaseSigning = extra["hasReleaseSigning"] as Boolean

android {
    namespace = "com.qualityverifier.fundi"
    compileSdk = 36

    defaultConfig {
        // Its own id, so both apps can sit on one handset. Under the project's namespace
        // rather than a new top-level one: same repo, same signing key, same store entry
        // when there is one.
        applicationId = "com.qualityverifier.fundibora"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        // The same backend as Kagua, which is the whole architecture — one server, one
        // database, two apps. Compiled in, as it is there.
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
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                logger.lifecycle(
                    "No release keystore configured; signing Fundi Bora with the debug " +
                        "key. It will not install over a differently signed build."
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
    implementation(project(":core"))
    implementation(project(":capture"))
    implementation(project(":design"))

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
    implementation(libs.coil.compose)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
