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
JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew assembleDebug
```

(That is the macOS incantation; on Linux point `JAVA_HOME` at your JDK 17 install.)

## Updating prompts without shipping an app update

Prompts live in `prompts/` and are fetched at runtime from:

```
https://raw.githubusercontent.com/gkilleen33/quality-verification-app/main/prompts/
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

## Assistant formatting

Claude formats its advice with bold, headings and lists. The chat bubble renders that
Markdown rather than showing the markers as literal characters, which for readers with
varying literacy is pure noise.

`text/Markdown.kt` is a small hand-rolled parser — no dependency, and being Compose-free
it is fully unit-tested on the JVM. It covers what actually appears in furniture advice:
headings, bullet and numbered lists, bold, italic, inline code, links and rules. Tables,
block quotes and fenced code fall through as plain text rather than being mishandled.
`ui/chat/MarkdownText.kt` renders it; `markdownToPlainText` flattens it for the history
list, which cannot show styling.

Two deliberate deviations from CommonMark, both chosen to surprise less in a chat bubble:

- A single newline inside a paragraph stays a line break rather than collapsing to a
  space, so a break Claude intended is honoured.
- A single underscore never starts emphasis, so `item_wooden_table` survives intact.
  Doubled `__bold__` is still recognised.

Only assistant replies are parsed. The user's own text is rendered literally, so an
asterisk they typed is never reinterpreted as formatting.

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
zero, something is invalidating the prefix.

Measured on a live three-turn conversation (`input` is the uncached remainder only):

| Turn | input | cacheWrite | cacheRead |
|---|---:|---:|---:|
| 1 — text only | 2 | 0 | 1,639 |
| 2 — adds a photo | 2 | 2,604 | 1,639 |
| 3 — text follow-up | 2 | 148 | 4,243 |

A photo is about 2,450 tokens and is resent on every later turn, so on a full 12-turn
walkthrough with six photos this is roughly a **70% cut in input cost** (~$0.55 → ~$0.16
per session at standard Sonnet 5 rates).

**On the TTL choice:** the 5-minute default is strictly cheaper when turns arrive quickly
(1.25× writes vs 2×, same 0.1× reads) — over the three turns above it would have cost
$0.013 against $0.019. The 1-hour TTL wins by avoiding *misses*: one avoided miss on a
4,000-token prefix saves ~3,600 tokens, which more than pays the extra write premium on a
2,500-token write (~1,875). Since the checklists deliberately send users away to
photograph — tipping a table, finding help with a sofa — at least one gap per session
exceeding five minutes is near-certain. If real usage turns out to be fast-paced, drop the
`ttl` field in `CacheControl.ONE_HOUR` and the default applies.

Minimum cacheable prefix on `claude-sonnet-5` is **1024 tokens**. Measured system prompts
(`count_tokens`), smallest first:

| Item | Tokens |
|---|---:|
| wooden-table | 1,639 |
| wooden-chair | 1,854 |
| wooden-bed | 2,012 |
| other | 2,379 |
| upholstered-chair | 2,467 |
| upholstered-sofa | 2,812 |

All six clear the minimum with room to spare, the table prompt by 1.6×. Shortening
`master.txt` by more than about a third would start to put the smaller item types at risk
of silently not caching.

## Images

Photos are stored as files under `filesDir/images/<sessionId>/`, not as database blobs.
On the way in, each image has its EXIF rotation applied, its long edge capped at 1568px,
and is re-encoded as JPEG q80 — roughly a 10x smaller upload than a raw camera frame,
which matters on metered mobile data. Rotation is applied for accuracy too: Claude
cannot judge whether a table is level from a sideways photo.

Deleting a session deletes its photos. Photos attached to a conversation that was never
sent are cleaned up on the next visit to the home screen.

## Contributing

`main` is protected: changes go through a pull request, and review from the code owner
(`.github/CODEOWNERS`) is required. Fork, branch, and open a PR.

Before opening one, please make sure this passes:

```bash
./gradlew assembleDebug testDebugUnitTest lintDebug
```

If you changed anything under `prompts/`, regenerate the compiled-in copies first or
`DefaultPromptsInSyncTest` will fail:

```bash
python3 tools/generate_default_prompts.py
```

## License

MIT — see [LICENSE](LICENSE).
