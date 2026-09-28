// Resolves release signing material for an app module, and refuses to guess.
//
// A script plugin rather than a copy in each app's build file: Kagua and Fundi Bora both
// publish signed release APKs from the same CI job, and the fiddly part — the resolution
// order, and the failure message that says which piece is missing — is exactly the sort
// of thing that gets fixed in one copy and not the other.
//
// It publishes values through `extra` rather than configuring `android { }` itself. The
// android extension's concrete type differs between an application and a library module
// and is not public API, so a script that reached into it would be one AGP upgrade from
// breaking. Each app spends ten obvious lines on its own signingConfigs block instead.
//
// Material comes from `keystore.properties` (local, gitignored) or from environment
// variables (CI). Absent both, the release build falls back to the debug key so a fork
// can still produce an installable APK without holding the real one.
//
// Pass -PrequireReleaseSigning to turn a missing key into a build failure. CI does that
// when publishing, because a silently debug-signed "release" would refuse to install over
// a previous build and the reason would be invisible.

val keystoreProperties = java.util.Properties().apply {
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
val releaseKeyAlias = signingValue("QV_KEY_ALIAS", "keyAlias") ?: "upload"
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
        "Release signing was required but is not configured for ${project.path}. Missing: " +
            missing.joinToString(", ") +
            ". Set them as environment variables or in keystore.properties."
    )
}

// Read by each app module's signingConfigs block. Named rather than a single object so a
// build file reads as a list of values, not as an unpacking exercise.
extra["releaseKeystore"] = releaseKeystore
extra["releaseStorePassword"] = releaseStorePassword
extra["releaseKeyAlias"] = releaseKeyAlias
extra["releaseKeyPassword"] = releaseKeyPassword
extra["hasReleaseSigning"] = hasReleaseSigning
