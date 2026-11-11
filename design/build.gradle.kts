plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// The look: colour scheme, type scale, and the verdict badge palette.
//
// Its own module because both apps need it and one of them does not exist yet. Fundi Bora
// reuses `qv-verdict` on a re-assessment, so it needs the same three badge colours as
// Kagua — and a second copy of a palette is how two apps end up disagreeing about what
// "serious concerns" looks like while both being sure they are right.
//
// The hex itself lives further down, in `:shared`, because the admin portal renders the
// same verdicts as CSS and cannot depend on an Android library. This module is the Compose
// binding for those values, not their home.
//
// No screens and no strings. Wording is fetched with the prompts and is not a
// compile-time constant, so it arrives through ReportLabels rather than from resources.
// Three exceptions, all things the two apps must draw identically. ui/update holds the
// update banner and the update-required screen, worded by UpdateLabels; ui/phone holds
// the +254 phone field, worded by AuthLabels, with its rule in KenyanPhone in :shared;
// ui/settings and ui/location hold the account settings screen and the location switch,
// worded by AuthLabels. Each takes its wording and state from the caller, so this module
// still knows nothing about which app it is in.
//
// The settings screen is why this module depends on :core: it calls the account routes
// through AccountActions and writes LocationPreference, both of which live there, and
// both apps' containers are the same AppContainer class. :core draws nothing and does
// not depend on this module, so the edge has one direction.
android {
    namespace = "com.qualityverifier.design"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
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
    }

    // SettingsViewModelTest runs a view model on the plain JVM, as :fundi's tests do.
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // api: VerdictLevel appears in verdictColors' signature, and every consumer of the
    // theme is already a consumer of :shared.
    api(project(":shared"))

    // implementation: AppContainer appears in SettingsScreen's signature, but both apps
    // already depend on :core directly.
    implementation(project(":core"))

    api(platform(libs.androidx.compose.bom))
    api(libs.androidx.ui)
    api(libs.androidx.material3)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.core.ktx)
    // The settings screen's back arrow, its back-press guard, its view model, and the
    // location switch's permission request.
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
