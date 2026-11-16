import com.android.build.api.artifact.SingleArtifact

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// PesaLog: the mobile money log (build prompt, 13 November 2026; Grady agreed to a separate
// app on 12 November 2026). It reads mobile money confirmations from the SMS notifications
// the phone shows, never from the SMS store, keeps a log on the phone, and sends it to the
// research server when the person agrees.
//
// A third application module rather than a part of Fundi Bora: Google Play Protect blocked
// Fundi Bora's install for asking to read SMS, and any objection to an app that reads
// notifications should land on this one, not on Fundi Bora. It signs in with a Fundi Bora
// account, so the two datasets join on the account.
//
// What it reuses: :shared for the parser, the intake rule, the consent wording and the
// labels; :core for signing in, the token store and its refresh, the app identity headers,
// the version check and the account guard; :design for the theme, the phone field and the
// update screens. What it does not: :core's AppContainer, which would open Kagua's and
// Fundi Bora's database and declare their M-Pesa reader's repository, and :capture.
apply(from = rootProject.file("gradle/release-signing.gradle.kts"))

val releaseKeystore = extra["releaseKeystore"] as File?
val releaseStorePassword = extra["releaseStorePassword"] as String?
val releaseKeyAlias = extra["releaseKeyAlias"] as String
val releaseKeyPassword = extra["releaseKeyPassword"] as String?
val hasReleaseSigning = extra["hasReleaseSigning"] as Boolean

/**
 * The app's name as people see it, in one place: the launcher label and every screen read
 * it from here. The terms and the two questions name it too, but those are published texts
 * with hashes, and a new name there is a new version of each.
 */
val displayName = "PesaLog"

android {
    namespace = "com.qualityverifier.tracker"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.qualityverifier.tracker"
        minSdk = 24
        targetSdk = 36
        // Its own numbers, from 1: the server keeps a latest and a minimum per app id
        // (X-App-Id: tracker), so they never need to match Kagua's or Fundi Bora's.
        versionCode = 1
        versionName = "0.1.0"

        buildConfigField("String", "SERVER_BASE_URL", "\"https://kagua.gradykilleen.me/\"")
        buildConfigField("String", "DISPLAY_NAME", "\"$displayName\"")
        resValue("string", "app_name", displayName)
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
            // The same upload key as Kagua and Fundi Bora: a later version may share data with
            // Fundi Bora on the phone, which Android allows only between apps of one key.
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                logger.lifecycle(
                    "No release keystore configured; signing PesaLog with the debug key. It " +
                        "will not install over a differently signed build."
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

    // PesaLog's own terms, from the repository's one terms/ folder, as Fundi Bora bundles its
    // own. Only tracker/: the other apps' texts are not this app's to show.
    sourceSets {
        getByName("main") {
            assets.srcDir(layout.buildDirectory.dir("generated/termsAssets"))
        }
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }
}

// The exported Room schemas, reviewed with any migration, as :core's are.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

val copyTermsAssets by tasks.registering(Sync::class) {
    from(rootProject.layout.projectDirectory.dir("terms/tracker")) { into("tracker") }
    into(layout.buildDirectory.dir("generated/termsAssets"))
}
tasks.named("preBuild") { dependsOn(copyTermsAssets) }

/**
 * Fails the build if the merged manifest asks for any SMS permission (build prompt: no
 * READ_SMS, no RECEIVE_SMS, no contacts, no accessibility). Notification access is the only
 * way PesaLog reads anything, and a dependency that added an SMS permission through the
 * manifest merger would make it the app Play Protect blocked. Read from the merged manifest,
 * after every library's has been folded in, for each variant; the unit tests and every
 * assemble depend on it, so CI runs it.
 */
abstract class CheckNoSmsPermission : DefaultTask() {
    @get:InputFile
    abstract val mergedManifest: RegularFileProperty

    @TaskAction
    fun check() {
        val text = mergedManifest.get().asFile.readText()
        val asked = Regex("""<uses-permission(?:-sdk-23)?\s[^>]*android:name="([^"]+)"""")
            .findAll(text).map { it.groupValues[1] }.toList()
        val refused = asked.filter { name -> FORBIDDEN.any { name.endsWith(it) } }
        if (refused.isNotEmpty()) {
            throw GradleException("PesaLog's merged manifest asks for $refused. It must read notifications only.")
        }
        if ("android.permission.BIND_ACCESSIBILITY_SERVICE" in text) {
            throw GradleException("PesaLog's merged manifest declares an accessibility service.")
        }
    }

    private companion object {
        val FORBIDDEN = listOf(
            ".READ_SMS", ".RECEIVE_SMS", ".SEND_SMS", ".RECEIVE_MMS", ".RECEIVE_WAP_PUSH",
            ".READ_CONTACTS", ".READ_CALL_LOG", ".READ_PHONE_STATE", ".READ_PHONE_NUMBERS",
            ".ACCESS_FINE_LOCATION", ".ACCESS_COARSE_LOCATION",
        )
    }
}

androidComponents {
    onVariants { variant ->
        val name = variant.name.replaceFirstChar { it.uppercase() }
        val checkTask = tasks.register<CheckNoSmsPermission>("check${name}NoSmsPermission") {
            mergedManifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
        }
        tasks.matching { it.name == "test${name}UnitTest" || it.name == "assemble$name" }
            .configureEach { dependsOn(checkTask) }
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(project(":core"))
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

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
