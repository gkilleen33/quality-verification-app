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
}

dependencies {
    // api: VerdictLevel appears in verdictColors' signature, and every consumer of the
    // theme is already a consumer of :shared.
    api(project(":shared"))

    api(platform(libs.androidx.compose.bom))
    api(libs.androidx.ui)
    api(libs.androidx.material3)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.core.ktx)

    testImplementation(libs.junit)
}
