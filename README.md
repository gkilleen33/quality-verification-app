# Quality Verifier

Android app for furniture quality verification, aimed at buyers in East Africa
(initially Uganda and Kenya). A user photographs a piece of furniture, and Claude
assesses joinery, finishing, symmetry, and defects.

This is **Phase 1: serverless**. The app calls the Anthropic API directly with a key
the user enters once, fetches prompts from this repo over raw GitHub URLs, and keeps
history in a local Room database. There is no backend and no login.

## Build

Requires JDK 17 and the Android SDK (platform 36, build-tools 36).

```bash
./gradlew assembleDebug
```

```bash
./gradlew testDebugUnitTest
```

`local.properties` points at the SDK and is machine-specific (gitignored) — recreate it
with `sdk.dir=<your-android-sdk>` when cloning.

Gradle must run on JDK 17; AGP 8.13 rejects newer launcher JVMs. Android Studio's
bundled JDK is fine. From the command line, set it explicitly:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ./gradlew assembleDebug
```

## Updating prompts without shipping an app update

Prompts live in `prompts/` and are fetched at runtime from:

```
https://raw.githubusercontent.com/gkilleen/quality-verification-app/main/prompts/
```

Edit the files, push to `main`, and devices pick up the change within 24 hours
(the cache TTL). Settings → **Refresh prompts** forces it immediately.

- `prompts/master.txt` — the system prompt, sent on every request
- `prompts/items/<slug>.txt` — appended for the chosen item type; **all currently
  empty placeholders**, intentionally

Resolution order per file is: fresh cache → network → stale cache → compiled-in
default. The app therefore works offline, and works before this repo is even pushed.
`DefaultPrompts.MASTER` is a byte-identical copy of `master.txt` — **edit both
together**.

The base URL is one `buildConfigField` in [app/build.gradle.kts](app/build.gradle.kts).

## Item photos

The item selection grid looks up a drawable by name at runtime and falls back to a
neutral placeholder when none exists. To add real photos, drop files into
`app/src/main/res/drawable/` using these exact names — no code change needed:

| Item type          | Drawable name              |
| ------------------ | -------------------------- |
| Wooden Table       | `item_wooden_table`        |
| Wooden Chair       | `item_wooden_chair`        |
| Wooden Bed         | `item_wooden_bed`          |
| Upholstered Chair  | `item_upholstered_chair`   |
| Upholstered Sofa   | `item_upholstered_sofa`    |
| Other              | `item_other`               |

Any drawable extension works (`.jpg`, `.png`, `.webp`). Cards crop to 4:3.

## Architecture

The data layer is deliberately separated from the UI so Phase 2 (server-backed) is a
swap of implementations rather than a rewrite. Four interfaces are the whole contract:

| Interface           | Phase 1                       | Phase 2                          |
| ------------------- | ----------------------------- | -------------------------------- |
| `ChatService`       | `AnthropicDirectChatService`  | server proxy + JWT               |
| `PromptRepository`  | `GitHubPromptRepository`      | server endpoints                 |
| `SessionRepository` | `RoomSessionRepository`       | Room + server sync               |
| `ApiKeyStore`       | `EncryptedPrefsApiKeyStore`   | deleted; key lives on the server |

They are wired in [AppContainer.kt](app/src/main/java/com/qualityverifier/di/AppContainer.kt),
which carries the Phase 2 migration checklist. No ViewModel or screen touches a key, a
URL, or a Room type, so none of them need to change.

`serverId` and `updatedAt` columns already exist in the schema so Phase 2 sync needs no
migration.

## Security notes

- The API key is stored in `EncryptedSharedPreferences`, encrypted with an
  Android-Keystore-backed master key. It is never written in plaintext and never logged.
- Backup and device transfer are disabled: Keystore keys are device-bound, so a
  restored copy of the encrypted prefs would be undecryptable.
- Cleartext HTTP is disabled app-wide.
- **The Phase 1 key model suits pilot testing, not public distribution.** A key on the
  device grants unmetered access to its owner's Anthropic account to whoever holds the
  phone. Phase 2 moves the key server-side, which is the actual fix.

## Images

Photos are stored as files under `filesDir/images/<sessionId>/`, not as database blobs.
On the way in, each image has its EXIF rotation applied, its long edge capped at 1568px,
and is re-encoded as JPEG q80 — roughly a 10x smaller upload than a raw camera frame,
which matters on metered mobile data. Rotation is applied for accuracy too: Claude
cannot judge whether a table is level from a sideways photo.

Deleting a session deletes its photos. Photos attached to a conversation that was never
sent are cleaned up on the next visit to the home screen.
