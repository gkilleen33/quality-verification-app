# Kagua

*Jua kabla ya kununua — know before you buy.*

Android app for furniture quality verification, aimed at buyers in East Africa
(initially Uganda and Kenya). The app walks a buyer through photographing and
physically testing a piece of furniture, then Claude gives a verdict they can act on
before money changes hands.

The Kotlin package stays `com.qualityverifier`, so the repository, the prompt URLs and
the CI secrets are unaffected by the name.

This is **Phase 1: serverless**. The app calls the Anthropic API directly with a key
the user enters once, fetches prompts from this repo over raw GitHub URLs, and keeps
history in a local Room database. There is no backend and no login.

## Install it on a phone

**→ [Download the newest build](https://github.com/gkilleen33/quality-verification-app/releases/tag/nightly)**

Open that link *on the phone*, tap the `.apk` under **Assets**, and confirm the install.
Android asks permission to install from your browser the first time — allow it for
whichever browser you used, then tap the downloaded file again.

On first launch the app asks for an Anthropic API key. It is stored encrypted on the
device and is never sent anywhere but `api.anthropic.com`.

Every merge to `main` refreshes that `nightly` build, so the link above always points at
the newest code. Tagged versions are on the
[releases page](https://github.com/gkilleen33/quality-verification-app/releases).

Do not use `/releases/latest` — GitHub never counts a prerelease as "latest", so it skips
`nightly` and lands on whatever was tagged last, or on the release list if nothing has
been. The `tag/nightly` link above is the one that always resolves to the newest build.

Builds published this way are signed with the persistent upload key, so a new one
installs over an older one without uninstalling first.

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

Gradle must run on JDK 17; AGP 8.13 rejects newer launcher JVMs. Android Studio's bundled
JDK is fine.

If `java -version` or `keytool` reports **"unable to locate a Java Runtime"**, a JDK is
installed but not registered with macOS — Homebrew's `openjdk@17` is keg-only, so nothing
is linked into the system location and the `/usr/bin` wrappers have nothing to find.
Register it once:

```bash
sudo ln -sfn /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk /Library/Java/JavaVirtualMachines/openjdk-17.jdk
```

After that `java`, `keytool` and `/usr/libexec/java_home` all work, and Gradle picks the
JDK up on its own. If you would rather not use `sudo`, point `JAVA_HOME` at it per shell
instead:

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
```

On Linux, or with a JDK from elsewhere, point `JAVA_HOME` at that install.

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

| Item type           | Drawable name              |
| ------------------- | -------------------------- |
| Table               | `item_wooden_table`        |
| Wooden chair        | `item_wooden_chair`        |
| Stool or bench      | `item_wooden_stool`        |
| Bed                 | `item_wooden_bed`          |
| Cabinet or wardrobe | `item_wooden_cabinet`      |
| Sofa                | `item_upholstered_sofa`    |
| Padded chair        | `item_upholstered_chair`   |
| Something else      | `item_other`               |

Any drawable extension works (`.jpg`, `.png`, `.webp`). Cards crop to 4:3.

## The assessment

Every category has its own protocol under `prompts/items/`, and `prompts/master.txt`
drives the sequence they all share:

1. **Context, collected on the phone** — language, buying or already own it, the price
   quoted, intended use, and how thorough to be. **Nothing is sent while this happens.**
   The same loose joint matters far more on a stool used daily in a kitchen than on a chair
   guests sit in twice a year, and the assessment knows which it is before it starts.
   A full assessment then takes **one photo of the whole piece** and sends it with the
   context, so the assistant's *first* reply is a plan that already fits the actual piece.
2. **A plan** — the assistant issues the whole shot list and test list in one message,
   as a `qv-plan` block.
3. **Collection** — the app walks the buyer through every shot and every test *locally*,
   without going back to the model in between, then sends the whole set as one turn.
4. **Inspection** — the assistant examines everything at once and either asks for what is
   still missing, as another short plan, or gives the verdict.
5. **Verdict** — sound, fair, or serious concerns, as cards.
6. **Follow-up** — questions grounded in what was actually seen.

Steps 2 and 3 are why the app, not the conversation, holds the state of a collection run.
Asking shot by shot cost a network round trip per photo — a dozen waits for a full
assessment — and because every turn re-sends all the earlier images, the token cost grew
with the *square* of the shot count rather than linearly. Collection is now one request.

The hands-on tests are racking, bottle-top roll, sighting along a surface, fingernail
press, drawer pull, foam press and the one-leg lift. Three of them — racking, sighting
along, and the one-leg lift — get a schematic diagram, drawn in
[`TestDiagrams.kt`](app/src/main/java/com/qualityverifier/ui/plan/TestDiagrams.kt) rather
than shipped as vector XML, because each needs the furniture in one colour and the motion
in another and a tinted drawable cannot do that. The rest get none: "press your thumbnail
into the underside" does not need a picture, and a drawing on every test teaches the
reader to skip all of them.

`TestDiagram` is a closed set, and a plan naming a diagram this build has never heard of
draws nothing rather than a placeholder. Prompts are data and can change without a
release; drawings cannot. A test asserts every diagram named in `prompts/items/` is one
the app can actually draw.

Two rules the prompts hold to, both covered by tests in
[`DefaultPromptsInSyncTest`](app/src/test/java/com/qualityverifier/prompts/DefaultPromptsInSyncTest.kt):

- **No money.** No shilling figures, no typical price ranges, no repair-cost estimates.
  There is no price data behind them, and a wrong number quoted back to a seller in a
  negotiation is worse than no number.
- **No negotiating, and no coaching one.** The app assesses the piece; what the buyer pays
  for it is theirs to decide. No suggesting a discount, no holding out for a better price, no
  proposing a line to bargain with.

  Two things it *does* do, and the distinction is the whole rule:

  - **Describing a repair.** *"The joint needs opening out, re-gluing and clamping"* is an
    objective fact about the furniture. That is what the verdict's `what_to_do` field carries
    — written as the work, not as a move in a negotiation.
  - **Suggesting questions for the seller.** *"Ask how long the timber has been drying"*,
    *"ask whether the top has been sealed"*. Often the only way to settle something a
    photograph cannot show, so it is encouraged, and unresolved points go in `unverified` as
    what would settle them. This is finishing the assessment, not haggling.

  The first pass at this ban caught the second category too, which was wrong; a test now
  asserts clarifying questions stay permitted, so tightening the ban again cannot quietly
  re-break it.

  There used to be an `ask_seller` field holding a scripted line for the customer to say out
  loud, and a "SAY THIS TO THE SELLER" card. Those are gone — a script to recite in a shop is
  a negotiation aid, not an assessment — and a test asserts the field does not come back.
- **Mirror the language.** Swahili in, Swahili out; mixed in, mixed out.

Swahili category labels are shown only where a term could be sourced. `ItemType`
deliberately leaves the two upholstered ones English-only rather than guessing — a wrong
word in the user's own language costs more trust than a missing one.

### One chair on the grid, two protocols behind it

The home grid offers **Chair**, not "Wooden chair" and "Padded chair" side by side. The
second was easy to miss, and it asked a buyer to classify their own chair before the app had
asked them anything.

So `ItemType.homeChoices` omits `UPHOLSTERED_CHAIR`, and the intake asks whether the chair
has cushioning. `withUpholstery()` turns that answer into the protocol that actually applies,
and the session stores the resolved type — so a reports row still reads "Padded chair" once it
is known. While the question is on screen the heading uses `neutralItemName()`, because
"Wooden chair" above "does this chair have cushioning?" answers its own question.

Only the chairs work this way. A sofa is always upholstered and a table never is, so asking
would be noise; `needsUpholsteryQuestion` is asserted to be true for exactly those two.

### Before anything is sent

Opening an assessment used to fire a request immediately, so the first thing a customer
saw was a spinner — and then three more round trips went on a language, an ownership and a
usage question that needed no model at all.

[`IntakeScreen`](app/src/main/java/com/qualityverifier/ui/intake/IntakeScreen.kt) asks
those on the device. The chat opens instantly, and the customer's answers become their own
first turn, written in the language they chose by
[`buildIntakeMessage`](app/src/main/java/com/qualityverifier/text/IntakeMessage.kt).

That first turn is also how the model learns the language. Left to inference it picked one
from the item name and then would not switch when written to in the other — a Swahili-only
assessment, with no way back. Now the choice is stated, and `master.txt` is told the
context is already collected and must not be asked for again. Both are asserted by tests,
because either regressing costs three round trips and asks the customer things they have
already answered.

Sending it as a message rather than as a field on `ChatService` is deliberate: it is
something the customer is telling the assistant, it belongs in their conversation where
they can see it, and keeping it out of the system prompt means every language shares one
cached prefix instead of splitting it.

### When the buttons do not fit

Every intake question after the language offers **"Something else — let me explain"**. A
stepped form with no way out is exactly where somebody whose answer is not on a button gets
stuck, and the assistant is better at an awkward question than a fixed list is.

Taking it ends the local questioning for good and hands over whatever was already chosen,
with a line asking the assistant to ask the rest itself. Partial context is worth keeping:
a customer who gave up at the usage question has still said they are buying, and making
them repeat that would be the second time the app failed them. `master.txt` is told this
can happen and to ask only for what is missing.

It is worth more than a fallback. Handed a context with only the usage question missing,
the assistant asked it and offered *"Outdoor use"* among the choices — an answer the app's
own list does not have. The fixed questions are the fast path, not the whole truth.

The price step only appears for a buyer, so how many steps there are is unknowable until
the ownership question is answered — which is why the step counter appears from the second
screen rather than showing a total it may have to change.

### Language of the report

The verdict cards, the reports badges and the share message take their wording from
[`ReportLabels`](app/src/main/java/com/qualityverifier/text/ReportLabels.kt), keyed on
**the language of the assessment rather than the language of the phone**. The assistant
mirrors whatever the customer writes, so a Swahili conversation on a handset set to
English was putting Swahili findings under English headings, which reads as a
half-finished app. The verdict therefore declares its own `language`, and the reports
row stores it alongside the verdict level.

**The Swahili in that file is unreviewed.** It needs a native speaker before any pilot,
and the three verdict levels need it most: a verdict is a judgement somebody acts on in
front of the person who built the furniture, and the tone of the word carries as much as
its meaning. The design brief lists this as an open question, and this is the file to fix
when it is answered. Treat it as better than English, not as finished copy.

The rest of the app's chrome — home, reports and profile — is still English only, driven
by nothing. Full localisation of those is a separate piece of work.

## Blocks the app parses out of a reply

Three fenced blocks in an assistant message are addressed to the app rather than the
reader, and are stripped from the bubble by
[`AssistantBlocks.kt`](app/src/main/java/com/qualityverifier/text/AssistantBlocks.kt):

````
```qv-options
Solid, no movement
A little give, corner to corner
Rocks clearly at the joints
```
````

becomes tappable reply chips, and

````
```qv-verdict
{ "verdict": "fair", "headline": "...", "defects": [ ... ] }
```
````

becomes the verdict cards. A third, `qv-plan`, carries the shot list and the tests, and
becomes the plan card plus the capture and test screens.

They travel inside the message text rather than through a tool call or a second request.
That keeps `ChatService` returning plain text, so the Phase 2 swap stays a one-file
change, and keeps the whole assessment in a single cached prefix instead of paying for a
second system prompt.

A plan is treated the same way but not identically: its prose opening is the assistant
acknowledging what it was told, which is worth keeping, so only the **first paragraph**
survives when the block parses. Left whole, the assistant listed all seven shots in prose
as well, the card drew the same list directly underneath, and the button that starts the
camera ended up below the fold on the screen whose entire purpose is to start the camera.
The prompt now asks for one short paragraph; the parser guarantees it.

For the same reason the plan's next action is pinned above the composer rather than sitting
at the foot of the card, which a seven-shot plan pushes off-screen.

The prompt writes the verdict twice — prose, then the block — and the app shows whichever
survived. It costs a few hundred output tokens and buys a readable answer for somebody
standing in a shop when a parse fails, which is the worst possible moment to show
nothing. A `qv-verdict` block that will not parse is dropped rather than printed, so raw
JSON never reaches the bubble.

Renaming a tag in the prompt without renaming it in `AssistantBlocks` would silently stop
the cards and chips from ever appearing, with no error anywhere. A test asserts both tags
are still documented in the master prompt.

## Planning for the piece in front of you

The item protocols under `prompts/items/` describe a *typical* piece of that kind. The one
being bought may be on welded steel legs, or have a drawer nobody mentioned, or turn out not
to be the category the buyer picked at all.

So a full assessment sends one photo of the whole piece along with the context, and the
prompt is told to check its protocol against that photo before planning: use the shots that
still apply, change the ones that do not, and say in a sentence what changed.

**The app takes that photo rather than asking the assistant to request it.** Asked for in
the prompt it was simply ignored — twice, even after the contradicting rule was removed —
because the pull towards issuing the whole plan at once was stronger. Doing it on the device
is also one round trip cheaper, since the photo rides along with the context that was going
to be sent anyway.

It works: handed the emulator's virtual scene, the assistant identified the piece as a TV
stand rather than the table the category claimed, and planned accordingly.

One known imperfection: the prompt says never to re-ask for the photo it already has, and
the model sometimes asks for a second wide shot anyway. Costs one photo out of seven, and a
second angle is not useless, so it is left as an instruction rather than enforced.

## Tests that did not happen

Every hands-on test carries two answers the app adds itself, so they are present whatever
the plan contained: **"I'm not sure"** and **"I can't do this one"**. They mean different
things — having tried and learned nothing is not the same as never having tried, usually
because the piece was too heavy to tip alone — and both are reported distinctly.

Neither is a failure. The prompt is told explicitly never to read a missing result as
evidence of a defect and to put it in the verdict's unverified list instead: a wobble test
nobody could perform is not a wobbly stool. That is the worst direction for an error in this
app to go, so it is asserted by a test.

## Waiting

The Inspecting screen ticks off the steps the app genuinely completed — the photos it
prepared, the payload it sent — and spins on the one that is actually outstanding. It
also lists what is in the inspection, taken from the plan.

What it deliberately does **not** do is animate its way through content areas the way the
mockup's checklist does. This is one request with no streaming, so the app has no idea
whether the model has looked at the joints yet. Ticking those off on a timer would be
theatre, and inventing certainty is the thing this product cannot do anywhere — including
in a progress indicator.

## Capture

Photos are taken in-app with CameraX rather than by handing off to the system camera,
because the shot instruction has to sit on top of the live preview. In a shop the user is
holding the phone in one hand, is being watched by the person who built the furniture, and
has been told to frame something specific; a camera app that has forgotten the
instruction means they come back with the wrong photo.

The instruction shown is the assistant's last message, because the protocol asks for
exactly one photo at a time — no extra prompt machinery, nothing to drift out of sync.

Each capture is measured on-device for blur (variance of the Laplacian) and darkness
(mean luma) before being attached. The check is advisory: a photo it dislikes is held up
with an explanation and a "use it anyway", and a photo it cannot measure is attached
without comment. Both thresholds in
[`ImageQuality.kt`](app/src/main/java/com/qualityverifier/images/ImageQuality.kt) are
engineering judgement, not measurement — they have not been calibrated against real
photos from real workshops, which is why they sit low enough to fire only on obvious
cases.

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

To confirm it is working, look at the usage breakdown in the Anthropic Console: cached
input tokens should dominate from the second turn of a conversation onward. If they stay at
zero, something is invalidating the prefix. The app also logs
`in/cacheWrite/cacheRead/out` per response for anyone debugging with a device attached.

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
sent are cleaned up on the next launch, from the home screen, which is the one
destination guaranteed to be reached.

## Getting a build onto a phone

### From GitHub (no toolchain needed)

Push a version tag to publish a permanent release:

```bash
git tag v0.1.0 && git push origin v0.1.0
```

The tag must match `versionName` in `app/build.gradle.kts` or the run fails: a release
called `v0.2.0` containing an app that reports `0.1.0` is confusing for anyone reporting a
bug. Bump `versionName` **and** `versionCode` before tagging.

Or rebuild the rolling `nightly` on demand from **Actions → Build APK → Run workflow**,
without waiting for a merge. Tagging leaves `nightly` untouched, so a permanent release and
the rolling build never overwrite each other.

Then on the phone, open the release page and tap the `.apk` — see
[Install it on a phone](#install-it-on-a-phone) above for the link and the
first-run permission prompt.

**Merging a PR into `main` refreshes the `nightly` build automatically**, so the latest
merged state is always downloadable without doing anything.

Pull requests themselves do not build an APK — they run tests and lint only. Building an
APK on every PR added about two minutes per run for an artifact nobody installs.

A warm publish run takes a couple of minutes; the ten-minute figure was a cold Gradle
cache on a branch that had never built before. Since the build now happens on merge, it
runs without you waiting on it — the APK is on the releases page by the time you look.

Pull request builds stay on debug — a PR from a fork cannot read secrets, and the
signature is irrelevant for review.

### Signing setup (once)

Publishing needs an upload key. Generate one, keeping it **outside the repo**:

```bash
mkdir -p ~/keys && keytool -genkeypair -v -keystore ~/keys/quality-verifier-upload.jks -alias upload -keyalg RSA -keysize 4096 -validity 10000
```

If that fails with "unable to locate a Java Runtime", see the JDK note under
[Build](#build) — either register the JDK once with `sudo ln -sfn`, or run the JDK's own
copy directly:

```bash
mkdir -p ~/keys && /opt/homebrew/opt/openjdk@17/bin/keytool -genkeypair -v -keystore ~/keys/quality-verifier-upload.jks -alias upload -keyalg RSA -keysize 4096 -validity 10000
```

keytool asks for a keystore password, then certificate details (any sensible values —
they only appear in the certificate), then a key password. **Press Return at the key
password prompt.** Modern keytool writes PKCS12, which does not support a key password
that differs from the store password and silently ignores one if you give it. That is why
`KEY_PASSWORD` and `KEYSTORE_PASSWORD` below hold the same value.

Then load the three secrets. Run from the repo root so `gh` picks the right repository:

The alias is not one of them: `upload` is the default in `app/build.gradle.kts`, and it
lives inside the keystore anyway. Holding it as a secret was actively harmful — GitHub
redacts every occurrence of a secret's value in logs, so the word "upload" came back as
`***` in unrelated places. Override it with `QV_KEY_ALIAS` or a `keyAlias` line in
`keystore.properties` if your keystore uses something else.

```bash
base64 -i ~/keys/quality-verifier-upload.jks | gh secret set KEYSTORE_BASE64
```

```bash
printf 'Keystore password: ' && read -rs KS && echo && printf '%s' "$KS" | gh secret set KEYSTORE_PASSWORD && printf '%s' "$KS" | gh secret set KEY_PASSWORD && unset KS && echo done
```

That prompts once with the password hidden and sets both secrets from it, so it never
reaches your shell history. It uses `printf` for the prompt rather than `read -p`, which
is a bashism — in zsh `-p` means "read from a coprocess" and the command fails with
`read: -p: no coprocess`. This form works in both shells.

Check all four landed:

```bash
gh secret list
```

**Back the keystore up somewhere durable, and keep the passwords in a password manager.**
GitHub secrets are write-only: once loaded they cannot be read back, so the `.jks` file is
the only retrievable copy. Losing it means never being able to ship an update that
installs over an existing one.

CI fails loudly rather than silently falling back: a published build with no keystore
stops the run, and the signature is checked afterwards to confirm it is not a debug key.

To build a signed release locally, create `keystore.properties` in the repo root
(gitignored):

```properties
storeFile=/Users/you/keys/quality-verifier-upload.jks
storePassword=...
keyPassword=...
```

Then `./gradlew assembleRelease` produces an APK with the same signature as a CI build.
Without it, `assembleRelease` still works but signs with the debug key and says so — fine
for a local smoke test, not for anything you hand out.

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
