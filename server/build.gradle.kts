import org.gradle.api.tasks.PathSensitivity

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktor)
}

group = "com.qualityverifier.server"
version = "0.1.0"

application {
    mainClass.set("com.qualityverifier.server.ApplicationKt")
}

kotlin {
    compilerOptions {
        // 17, not 21, even though the box runs 21. Gradle itself has to run on JDK 17
        // here because AGP 8.13 rejects newer launcher JVMs, and you cannot target a
        // release newer than the JDK compiling it. A 17-target jar on a 21 runtime is
        // fine; the reverse is not.
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation(project(":shared"))

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.json)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.auth.jwt)
    implementation(libs.ktor.server.html.builder)
    implementation(libs.ktor.server.sessions)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.logback.classic)
    implementation(libs.postgresql)
    implementation(libs.hikaricp)
    implementation(libs.bouncycastle)

    testImplementation(libs.junit)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.content.negotiation)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.withType<Test>().configureEach {
    useJUnit()
    // MigrationsTest reads server/db/migrations, which is not a source directory. Without
    // declaring it, Gradle sees no input change when a migration is edited and reports the
    // task up to date — so the test that guards the migrations would pass by not running.
    // Found exactly that way: deleting V8's version row locally left the suite green.
    inputs.dir(layout.projectDirectory.dir("db/migrations"))
        .withPropertyName("migrations")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
