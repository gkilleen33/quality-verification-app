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
| Country | **Kenya.** No multi-market abstraction. Kagua's `+254` prefill and `Africa/Nairobi` day boundary were moved separately — see the Kenya change. |
| Deletion | **Hides from public, retains the record.** See below — this is not what `anonymise_user` does. |
| Voice | **Out.** Mockup scene 10 is voice-first Swahili; typed for now. |
| Extended (marketplace, tools, jobs) | **Later.** Sketch-to-workplan is written up in issue #33. |

## Module shape

```
shared/     Fundi.kt (vocabularies), Diagnosis/FixPlan, fb-* parsing, FUNDI_MASTER
capture/    NOT YET — see "deferred" below
app/        Kagua, buyer-facing
fundi/      NOT YET — Fundi Bora, producer-facing
server/     grows — audience parameter, fundi_* routes
```

`fundi/` and `capture/` are still absent. An empty Android module builds a blank APK on
every CI run and proves nothing, and the extraction is better done alongside the first
screen than before it — the seam is easier to get right with something on the other side
of it.

**The groundwork is done, though.** `PlanRun` moved into `:shared`, which was the only
app-internal type the capture screens depended on. The five files that would move —
`CaptureScreen`, `PlanCard`, `PhysicalTestsScreen`, `InspectingScreen`, `TestDiagrams` —
now import nothing of ours outside `:shared`:

| File | Imports from our code |
|---|---|
| `CaptureScreen` | `text.markdownToPlainText` |
| `PlanCard` | `text.ReportLabels`, `domain.PlanRun` |
| `InspectingScreen` | `text.ReportLabels`, `domain.PlanRun` |
| `PhysicalTestsScreen` | `text.ReportLabels`, `domain.PlannedTest` |
| `TestDiagrams` | `domain.TestDiagram` |

So the extraction is now a file move plus a build file, with no import rewrites outside
the moved files. That is the whole reason to have done the `PlanRun` move first.

**When they do, the extraction is the important half.** The capture pipeline — CameraX,
the shot instruction over the viewfinder, normalisation on capture, the plan runner, the
test screens and their diagrams — is substantial, identical for both audiences, and the
thing that will silently diverge if it is copied. Mockup scene 6 is the existing shot
runner verbatim, down to "Shot 5 of 7" and "Same eyes as the buyer's app".

## The audience dimension

Not a fork. One parameter, threaded through:

- `sessions.audience` and `usage_events.audience` (`V14`). Both default to `buyer`; every
  row that existed before Fundi Bora was one.
- `PromptRepository.systemPromptFor(itemType, audience)` — **done**. `prompts/fundi-master.txt`
  is the inward-pointing master; `Audience.BUYER` is the default so existing callers are
  unchanged, and each audience caches its own master under its own path. The item
  protocols are shared, because what to photograph on a table does not depend on who is
  asking.
- **Wired, and derived.** `ChatStore.audienceFor(userId, sessionId)` answers in one query:
  an existing session reports the audience it was created with, and a new one is answered
  by whether the account has a `fundi_workshops` row. The route resolves it before
  assembling the prompt, because it chooses which prompt, and passes it to `ensureSession`
  to be written once at creation.

  **Never from the request.** There is no `audience` field on `ChatRequest`, and the
  server's JSON is strict, so a body carrying one is refused outright. The two prompts are
  not interchangeable, so letting a client choose would be letting it choose what the
  assistant is for.

  **A session keeps the audience it was created with.** A buyer who later registers a
  workshop does not retrospectively turn their old assessments into coaching sessions —
  they were conducted as a buyer, and the record should say so.
- **The daily limit is still not per-audience**, and this is the next thing that will bite.
  A fundi assessing their own work all morning is a completely different shape of use from
  a buyer checking one table, and today they share the customer allowance. The code path
  already distinguishes evaluator from customer, so adding a third is small — but it needs
  a number, and that is a research decision rather than a guess.

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

## Open before the first screen

- The two Fundi Bora consent strings do not exist yet, and Kagua's Swahili is still
  unreviewed placeholder copy (issue #20). A producer-facing app in Kenya needs that pass
  more than the buyer's app does, not less.
- Certification is "3 of 10" in the mockup, with no definition of what qualifies. That is a
  research decision and it determines what `fundi_observations` has to record.
