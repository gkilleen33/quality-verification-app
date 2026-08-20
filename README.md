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
- `prompts/items/<slug>.txt` — appended for the chosen item type. All six are
  populated with a guided photo walkthrough the assistant runs at the start of a
  conversation


Each item prompt follows the same shape: an opening explanation, numbered steps taken
one at a time, photo requests described in words, look-for-it questions that ask for a
photo only when the answer is yes, then an evaluation against the master criteria.

The two upholstered prompts assess the **frame as well as the covering**. The frame is
hidden under the padding and cannot be photographed, so they reach it indirectly —
exposed legs and trim, the underside, and physical tests the user performs and describes
(lift by one corner and watch for twist, press the arms and back, feel for a rail
through the padding). Those prompts are told to report frame and upholstery separately
and to say plainly that the frame verdict is less certain, because a good frame with
poor padding can be reupholstered while a weak frame cannot be fixed.

Resolution order per file is: fresh cache → network → stale cache → compiled-in
default. The app therefore works offline, and works before this repo is even pushed.

The compiled-in copies live in `DefaultPrompts.kt`, which is **generated** — after
editing anything under `prompts/`, regenerate it:

```bash
python3 tools/generate_default_prompts.py
```

Remote stays the source of truth: a value fetched from GitHub always wins over the
compiled-in copy, **including an empty one**. So emptying a prompt file upstream really
does clear it on devices.

Note the assistant cannot send images — the app renders only text from Claude — so
prompts must describe what a photo should show rather than offer to display an example.

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

## Prompt caching

Every request sets two `cache_control` breakpoints — one on the system block, one on the
last content block of the conversation — with a **1-hour TTL** rather than the 5-minute
default. Caching is purely a request-level feature: there is nothing to enable in the
Anthropic Console, and no beta header.

The 1-hour TTL is deliberate. The walkthroughs send the user away to take a photo — tip
the table over, find someone to help lift the sofa — so gaps between turns routinely
exceed five minutes, and a default entry would expire mid-checklist. The doubled write
premium (2x instead of 1.25x) needs three requests to pay off; a walkthrough is a dozen.

Caching is a **prefix match**, so anything that varies per request must stay after the
last breakpoint. Do not interpolate a timestamp, session id, or device id into the system
prompt — it sits at the front of the prefix and would silently make every request a cache
miss. There are currently no such values, and a test asserts that two identical sends
serialize byte-identically.

To confirm it is working, watch the log line the chat service emits per response:

```bash
adb logcat -s ChatService
```

`cacheRead` should be large from the second turn of a conversation onward. If it stays at
zero, something is invalidating the prefix. Note the minimum cacheable prefix for
`claude-sonnet-5` is **1024 tokens**; the shortest system prompt here is roughly 1,300, so
all six clear it, but not by a wide margin — shortening `master.txt` substantially could
silently disable caching for the smaller item types.

## Images

Photos are stored as files under `filesDir/images/<sessionId>/`, not as database blobs.
On the way in, each image has its EXIF rotation applied, its long edge capped at 1568px,
and is re-encoded as JPEG q80 — roughly a 10x smaller upload than a raw camera frame,
which matters on metered mobile data. Rotation is applied for accuracy too: Claude
cannot judge whether a table is level from a sideways photo.

Deleting a session deletes its photos. Photos attached to a conversation that was never
sent are cleaned up on the next visit to the home screen.
