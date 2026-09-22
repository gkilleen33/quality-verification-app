plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// The assessment capture pipeline: the camera with the shot instruction over the
// viewfinder, the plan runner, the physical tests and their diagrams.
//
// Its own module because both apps use it unchanged. The brief's sentence for Fundi Bora
// is that it "runs the same assessment engine as Kagua, but points it inward", and the
// mockup's capture scene is this screen verbatim, down to "Shot 5 of 7" and "Same eyes as
// the buyer's app". Copied into a second app it would diverge silently — one of them would
// gain a fix to the rotation handling or the skip flow and the other would not, and nothing
// would fail.
//
// WHAT MAY NOT COME IN HERE
//
// Nothing that knows who is asking. These screens take a plan, some labels and callbacks,
// and hand back photographs and answers; the audience, the conversation, the database and
// the server all live in the app that owns them. `:shared` is the only project dependency
// and it holds no Android types.
//
// No resources either, deliberately. Every string arrives through ReportLabels, because
// the wording is fetched with the prompts and is not a compile-time constant.
android {
    namespace = "com.qualityverifier.capture"
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
    // api, not implementation: PlanRun, PlannedTest, TestDiagram and ReportLabels all
    // appear in the public signatures here, so a consumer cannot call these screens
    // without them.
    api(project(":shared"))

    api(platform(libs.androidx.compose.bom))
    api(libs.androidx.ui)
    api(libs.androidx.material3)
    implementation(libs.androidx.ui.graphics)
    // Foundation arrives with material3, as it does in :app. Not declared twice.
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.ui.tooling.preview)
    debugImplementation(libs.androidx.ui.tooling)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)

    // In-app capture rather than the system camera intent: the shot instruction has to sit
    // on top of the live preview, which an intent cannot do. camera2 is the backend and is
    // needed at runtime even though nothing here names it — declared so a second app
    // cannot forget it and find an empty viewfinder.
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    implementation(libs.coil.compose)

    testImplementation(libs.junit)
}
