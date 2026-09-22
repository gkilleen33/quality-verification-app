plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// Everything a phone needs to hold an assessment and talk to our server: the token store
// and its refresh, the chat client, the Room database, the image store, the sync queue and
// the location fix.
//
// Its own module for the same reason :capture is, and with more at stake. Both apps sign
// in against the same server, store the same assessments and upload the same photographs,
// and the parts of this that are easy to get subtly wrong are all in here — one refresh in
// flight at a time, a turn that is replayed rather than paid for twice, a blob uploaded
// only when the server says it does not have it. Copied into a second app, a fix to any of
// those would land in one copy and nothing would fail in the other. It would just cost
// money, or lose somebody's assessment.
//
// WHAT STAYS WITH THE APP
//
// The screens, the view models, and the container lookup — `ui.appContainer` reads the
// Application subclass, which is per-app by definition. AppContainer itself is here
// because the wiring is identical; an app that needs more composes it rather than
// reimplementing it.
//
// No Compose and no resources: nothing in here draws anything.
android {
    namespace = "com.qualityverifier.core"
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

    // Room, the token store and the image store all touch android.* from plain JVM tests.
    // Same setting :app had when these tests lived there.
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

// Moved with AppDatabase. The exported schemas are how a migration is reviewed, and a
// stale path writes version 10 into whichever module still claims to own them.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    // api: SessionStart, ChatMessage, Verdict and the rest appear throughout the public
    // signatures here, and :shared carries okhttp, coroutines and the JSON codec.
    api(project(":shared"))

    implementation(libs.androidx.core.ktx)

    api(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.exifinterface)

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
}
