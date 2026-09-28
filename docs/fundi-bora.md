# Fundi Bora: the shape

The producer-facing half of the project. Brief and mockup: <https://ubora-e1l.pages.dev/>
(`03-fundi-bora-design-doc.html`, `04-fundi-bora-mockup.html`).

The architectural decision is the brief's own sentence: Fundi Bora **"runs the same
assessment engine as Kagua, but points it inward"**. Everything below follows from taking
that literally.

## Decided

| Question | Decision |
|---|---|
| One server or two? | **One.** Same jar, same systemd unit, same nginx. |
| One database or two? | **One.** The flywheel — "certified fundis win Kagua buyers" — is a join. Two databases would make the central mechanism of the intervention a cross-database query. |
| One repo? | **Yes**, and one Gradle build. `shared/` already exists because prompt caching is a byte-exact prefix match; both halves need it. |
| Shared accounts? | **No.** Two apps, one backend, **entirely independent accounts.** Nobody holds one account that is both. |
| Country | **Kenya.** No multi-market abstraction. Kagua's `+254` prefill and `Africa/Nairobi` day boundary were moved separately — see the Kenya change. |
| Deletion | **Hides from public, retains the record.** See below — this is not what `anonymise_user` does. |
| Voice | **Out.** Mockup scene 10 is voice-first Swahili; typed for now. |
| Extended (marketplace, tools, jobs) | **Later.** Sketch-to-workplan is written up in issue #33. |

## Module shape

```
shared/     Fundi.kt (vocabularies), Diagnosis/FixPlan, fb-* parsing, FUNDI_MASTER,
            VerdictSwatches (read by the phone's theme AND the portal's CSS)
core/       tokens, chat client, database, images, sync, location — used by both apps
capture/    the camera, the plan runner, the physical tests — used by both apps
design/     colour scheme, type scale, verdict palette — used by both apps
app/        Kagua, buyer-facing
fundi/      Fundi Bora, producer-facing — scaffolding only
server/     grows — audience parameter, fundi_* routes
```

`core/` **exists** — the whole data layer, 23 files, moved out of `:app` unchanged. More
was at stake here than in `:capture`: both apps sign in against the same server, store the
same assessments and upload the same photographs, and the things that are easy to get
subtly wrong are all in here — one refresh in flight at a time, a turn replayed rather
than paid for twice, a blob uploaded only when the server says it lacks it. A fix to any
of those, applied to one copy, would fail in the other by costing money or losing an
assessment rather than by crashing.

`AppContainer` came too, with the server's base URL as a constructor parameter. That was
the only thing in the entire layer that knew which app it had been compiled into. What
stayed behind is `ui.appContainer`, which reads the `Application` subclass and is per-app
by definition.

`capture/` **exists** — an Android library holding the five screens both apps run
unchanged: `CaptureScreen`, `PlanCard`, `PhysicalTestsScreen`, `InspectingScreen`,
`TestDiagrams`. Extracting it was the important half. The capture pipeline — CameraX, the
shot instruction over the viewfinder, normalisation on capture, the plan runner, the test
screens and their diagrams — is substantial, identical for both audiences, and the thing
that would silently diverge if it were copied: one app would gain a fix to the rotation
handling or the skip flow and the other would not, and nothing would fail. Mockup scene 6
is this shot runner verbatim, down to "Shot 5 of 7" and "Same eyes as the buyer's app".

It was a file move plus a build file, with no import rewrites at all — the packages
(`ui.capture`, `ui.plan`) went across unchanged, and only `ChatScreen` imported them. That
is what the earlier `PlanRun` move bought: it was the only app-internal type those screens
depended on, so after it they imported nothing of ours outside `:shared`.

**What may not come into `:capture`.** Nothing that knows who is asking. The screens take a
plan, some labels and callbacks, and hand back photographs and answers; the audience, the
conversation, the database and the server stay in the app that owns them. `:shared` is its
only project dependency and holds no Android types. No resources either — every string
arrives through `ReportLabels`, because the wording is fetched with the prompts and is not
a compile-time constant.

`design/` **exists** — the colour scheme, the type scale, and the verdict badge palette.
Fundi Bora needs the last of those the moment it issues a re-assessment verdict, and a
second copy of a palette is how two apps end up disagreeing about what "serious concerns"
looks like while both being sure they are right.

The hex itself went one level further down, into `:shared` as `VerdictSwatches`, because
there is a **third** renderer: the admin portal draws the same verdicts as CSS so a
reviewer can hold the page beside a handset, and it cannot depend on an Android library.
The portal used to carry its own copy of the four hex pairs under a comment asking
somebody to keep them in step by hand; it now generates its rules from the shared values,
using the same `levelClass` function the markup applies, so a level cannot get a rule
nothing selects or a class nothing styles. `VerdictPaletteTest` pins that.

`fundi/` **exists, as scaffolding.** A second application module rather than a flavour of
Kagua: the two share a backend and four libraries and nothing else — separate accounts,
separate invite codes, separate launcher entries, and since the chat endpoints were split,
no way for either to reach the other's prompt. A flavour would have made "which app am I"
a runtime question in code that both ship.

What it draws is a placeholder that says so. The point of the slice was not the screen but
the chain underneath it: `:core`'s container constructing against Fundi Bora's own
`BuildConfig`, `:design`'s theme applying, the manifest merging the permissions its
libraries declare (it declares none and gets all four), and CI signing and publishing a
second APK. Those are what break when a module is added, and they break at assembly time
rather than in a screen.

Release signing moved to `gradle/release-signing.gradle.kts` first, so the resolution
order and the which-piece-is-missing failure message exist once rather than per app.

**Auth and workshop setup exist.** Fundi-specific sign-in and registration screens rather
than extracted Kagua ones: registration genuinely differs, because the business-or-personal
question is about a shop lending its handset to walk-in customers and asking a fundi it
would be asking something nothing reads. What is *not* duplicated is anything that
matters — `AuthClient` and the token store are `:core`'s, so rotation, single-flight
refresh and the theft rule have one implementation. What is duplicated is a busy flag.

The three setup screens share one view model, because the profile is **sent whole**: the
server derives what changed by comparing against what it holds, so three per-screen saves
would write three partial profiles and record a tool history of a maker acquiring their
own tools one screen at a time. Re-entering setup loads what the server already has, so
correcting one tool does not wipe the rest — which would otherwise be silent, since an
absent tool is deliberately not a disposal.

**Nothing on the tools screen is pre-selected.** Absent means nobody asked; `NONE` means
they said they have none; only the second lets the coaching work around it. A default
would have told the model every maker owns nothing.

`FundiProfileClient` lives in `:core` despite that module's only-what-both-apps-need rule,
because the rule it loses to is stronger: every authenticated client has to refresh
through the single-flight provider or it can sign somebody out. That retry is now
`AuthenticatedHttp`, shared with `SyncClient` and tested for the first time.

**The assessment loop and the cards exist.** Home offers the item types and nothing else
— a maker opening the app is standing over a piece they have just finished, and anything
between that and the camera is a tap they did not need. Then the opening photograph plus
the maker's context, the plan, `:capture`'s shot runner and physical tests unchanged, and
the diagnosis and fix-plan cards.

`AssessViewModel` is a leaner cousin of Kagua's `ChatViewModel` rather than a reuse of it.
The buyer's version also carries an intake questionnaire, comparisons between two pieces,
the evaluator questionnaire and sharing, none of which a maker has any use for. What the
two genuinely share is already shared: the camera and plan runner are `:capture`, storage
and upload dedup and the retry are `:core`.

`ServerChatService` takes the endpoint as a parameter now, defaulted to Kagua's — the safe
direction to be wrong in, since the server refuses a fundi account on the buying endpoint
rather than quietly coaching a buyer. A 403 from an assessment endpoint used to be folded
in with 401 and said "please sign in again", which under the split endpoints is a loop:
the credentials are right and the account is simply for the other app. It now says so.

**The cards are the part a carpenter will judge**, and the part issue #41 is waiting on.
One card per fix horizon rather than three sections in one, because fixing this piece,
changing the habit and practising are three kinds of advice and run together they read as
nine things to do now. Findings past the first are listed without causes: a fundi handed
six habits to change changes none of them.

Still to come: reports, the skill file, certification — and running any of it against a
real piece.

## The audience dimension

Not a fork. One parameter, threaded through:

- `sessions.audience` and `usage_events.audience` (`V14`). Both default to `buyer`; every
  row that existed before Fundi Bora was one.
- `PromptRepository.systemPromptFor(itemType, audience)` — **done**. `prompts/fundi-master.txt`
  is the inward-pointing master; `Audience.BUYER` is the default so existing callers are
  unchanged, and each audience caches its own master under its own path. The item
  protocols are shared, because what to photograph on a table does not depend on who is
  asking.
- **The route is the audience.** Two endpoints, because there are two apps, and each
  hard-codes the prompt it serves: Kagua's `/v1/chat` passes `Audience.BUYER` and Fundi
  Bora's `/v1/fundi/chat` passes `Audience.FUNDI`. Neither reads anything to decide, and
  the shared body below that choice is one function so the money-spending path cannot
  drift between them.

  **This replaced a single endpoint that resolved the audience from the account.** That
  worked and was the wrong shape: it left a live path to the coaching prompt inside
  Kagua's own endpoint, reachable by a data change with no code change and no client
  involved. A buyer standing in a furniture shop being told how to re-glue the joint they
  are inspecting is not a failure any test catches, because nothing about it is an error.
  Kagua now has no code path there at all.

- **A property of the account** (`V16`). `users.audience`, established at registration and
  never changed. It is now a **guard, not a selector**: each endpoint refuses an account
  belonging to the other app with `403 wrong_app`, before the prompt is assembled and
  before anything is spent upstream. So a fundi account cannot get coaching out of Kagua
  either, and the answer does not depend on which app somebody typed their password into.
  The route passes its own audience to `ensureSession`, to be written once at creation.

  **It comes from the invite code**, exactly as `is_tester` does — `invite_codes.audience`,
  read in the same transaction that redeems it. Registration is invite-gated for the pilot
  and every tester is somebody we hand a code to by name, so the code is already how we
  decide who may spend the budget; it can decide which app they spend it in. The portal's
  invite form is therefore **the only way a Fundi Bora account can come into existence**,
  and a test pins that.

  **Never from the request.** There is no `audience` field on `ChatRequest` and the
  server's JSON is strict, so a body carrying one is refused outright — belt and braces,
  since the URL already decided. An app identifier on the register request was the
  alternative there and was rejected for the same reason.

  **`V14` inferred it instead**, from the presence of a `fundi_workshops` row. That is
  wrong once accounts are independent, and wrong in a way that bites on day one: every
  fundi between signing up and finishing the workshop screens has no row, so they would
  read as a buyer and get the buying prompt for their first assessments. The inference is
  gone.

  **A session keeps the audience it was created with.** That cannot differ from the
  account's today. It is kept because the session is the research record, and what an
  assessment was conducted as is a property of the assessment.

  **Cross-app sign-in still succeeds**, because `/v1/auth/sign-in` does not ask which app
  is asking. It no longer matters for the prompt: whichever app you sign in to, the only
  assessment endpoint your account may use is its own. What it still costs is a confusing
  session — a fundi signed into Kagua can read their reports and start nothing. Refusing
  it outright belongs with the producer app's auth screen.
- **The daily limit is per-audience** — **five customer allowances**, so 100 against the
  current 20. A fundi works through everything that came off the bench that morning and
  re-assesses yesterday's pieces after repairing them; the assess / repair / re-assess
  loop *is* the product, and each turn of it is another assessment. The customer
  allowance would have stopped them before lunch.

  A multiple rather than a second constant, so the ratio survives the customer number
  being retuned once there is spend data — and so disabling the quota disables it for
  both audiences, since zero times five is zero. `KAGUA_FUNDI_DAILY_ASSESSMENT_LIMIT`
  overrides it outright.

  The endpoint chooses the allowance, exactly as it chooses the prompt. An evaluator gets
  the *larger* of their audience's allowance and the tester allowance, not the tester one
  outright — that number exists to raise a customer's, and applied to a fundi it would
  have halved an evaluator's day.

### Where the maker's context goes, and why it is not the system prompt

The coaching is explicitly "with your tools" — the fix plan proposes a rope tourniquet
because no clamps are owned, and prices a marking gauge because its absence caused the
defect. So the tool inventory and goals have to reach the model.

**Put them in the opening user turn, not the system prompt.** The cache breakpoint sits on
the system prompt precisely because it is identical for every conversation about an item
type. Per-maker system prompts would mean a per-maker 8.3k-token cache write instead of
one shared entry. The opening user turn is already inside the rolling message-prefix
breakpoint, which is where per-conversation context belongs.

## What is genuinely new

Everything else is additive. These are not:

**Piece identity.** `pieces`, and `sessions.piece_id`. Kagua never needed it — one
customer, one piece, once — but Fundi Bora's loop is assess, repair, re-assess the next
morning, and the skill file aggregates over pieces. Deliberately *not*
`previous_session_id`, which already means "the customer tapped check-another-item".

**The skill file.** `fundi_observations` stores *measurements* keyed to the session they
came from — the mockup shows a joint gap going 5mm → 3mm → 2mm across assessments #12, #14
and #15. Current level and certification progress ("3 of 10") are **derived**, not stored:
the aggregation is a rubric decision that will change, and a stored score freezes it.

**Maker annotations.** The skill file is "yours to edit". An edit goes in
`fundi_skill_notes` *alongside* the observation and never overwrites it. That is a research
decision: a subject who can overwrite a measurement turns the series into a mixture of what
was measured and what they preferred to record, with nothing able to tell which is which.

**Output blocks — done.** `qv-plan` and `qv-options` are reused as-is, and so is
`qv-verdict`: the re-assessment issues one, which is what fills
`sessions.verdict_defect_count` and therefore the two rates. A re-assessment that stopped
emitting a verdict would leave every repair unrecorded with nothing failing, so the prompt
says why and a test pins it.

New: `fb-diagnosis` ("Not a grade — a cause": what-happened / manufacturing-cause /
root-habit, plus one clarifying question) and `fb-fixplan` (fix now / prevent / drill, each
with a time and a cost, plus an optional tool with a price *range*).
`parseAssistantContent` leaves unrecognised tags in prose, so adding them was additive and
older clients degrade rather than break — asserted against a tag nothing implements yet.

Only the worst finding carries a cause; the rest are recorded so the piece's defect count
is right. A fundi handed six habits to change changes none of them.

## Deletion, and the wording it needs

Deleting a fundi's account **hides their record from public view; it does not erase it.**

This is a different axis from `anonymise_user`, which clears identifying columns. Nothing
in `V14` is public — there is no badge table yet — so nothing implements it. When badges
arrive they need a visibility flag from their first day rather than retrofitted.

What already applies through foreign keys: the profile tables cascade from `users`, and
observations cascade from `sessions`, which V11 keeps on purpose.

**Outstanding:** the app's deletion wording promises to remove what we hold about somebody.
Hidden-but-retained is not that. See `retention-and-profile-wording.md` §5.

## One deliberate departure from the mockup

The mockup quotes a price uplift — "KSh 3,800–4,000 work now, not 3,000 work". The prompt
declines to. Costs a maker can check are allowed (materials, tool prices as a range, rough
build times); what the finished piece is *worth* is not, for the same reason Kagua's master
forbids naming figures: we have no price data for their market, and a number invented here
becomes a number they quote a customer. It says that better work commands a better price,
which is true, without saying what the price is.

Worth revisiting — it is a product decision, not a technical one, and the mockup disagrees.

## Open

- **The coaching prompt has never been run against a real photograph** — issue #41.
  `prompts/fundi-master.txt` is marked `DRAFT, v0.1` and its coaching content was written
  by somebody with no workshop experience: the diagnosis chain, the fix/prevent/drill
  times and costs, the tool substitutions and the price ranges are all guesses. Reviewed
  *after* the app can run an assessment end to end, deliberately — a diagnosis is far
  easier to judge beside the photographs that produced it than as the instruction that
  asked for it, and prompts are data, so the review is an edit to a text file.
- The two Fundi Bora consent strings do not exist yet, and Kagua's Swahili is still
  unreviewed placeholder copy (issue #20). A producer-facing app in Kenya needs that pass
  more than the buyer's app does, not less.
- Certification is "3 of 10" in the mockup, with no definition of what qualifies. That is a
  research decision and it determines what `fundi_observations` has to record.
- Cross-app sign-in still succeeds. It no longer affects which prompt anybody gets — see
  the audience section — but it leaves a fundi able to sign into Kagua and find nothing
  they can do.
- ~~The daily limit is not per-audience.~~ Settled 28 September 2026: five customer
  allowances. See the audience section.
