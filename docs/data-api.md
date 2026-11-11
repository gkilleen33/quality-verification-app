# The read-only data API

For pulling the pilot dataset out for analysis. Read-only, key-authenticated, and it returns
**everything** — phone numbers, names, business locations, every conversation and every
photograph.

## Before anything else: what a key is

A key reads the whole corpus with one string, no second factor, from anywhere. An admin
password does less: it needs a TOTP code, the session idles out after thirty minutes, and
every page view is audited against a named person.

So treat a key like the database password. Not like a URL, not like something to paste into
a shared notebook, and not like something to leave in a repository. If you are wondering
whether a particular place is safe to put it, it is not.

- Keys are shown **once**, at creation. Only a SHA-256 is stored, so a lost key is replaced
  rather than recovered.
- Every request is written to the audit log with the key's id, the path and the query.
- Revocation is immediate — checked per request, since there is no session to expire.
- Prefixed `kagua_`, so one found in a config file or a log is obviously a credential.

Create and revoke them at **`/admin/api-keys`**.

## Authenticating

Either header works:

```
Authorization: Bearer kagua_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
X-API-Key: kagua_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
```

Both, because a research consumer is as likely to be a curl one-liner or an R script as a
library.

## Endpoints

All under `https://kagua.gradykilleen.me/api/v1`. Every list takes `limit` (default 50, max
**200**) and `offset`, and returns `{items, has_more, limit, offset}`. Timestamps are epoch
milliseconds throughout.

| Endpoint | Returns |
|---|---|
| `GET /users` | accounts, with phone, name, business, latitude/longitude and accuracy |
| `GET /assessments` | assessments, newest last, each with the `app_version_code` that started it, its `purpose`, `language`, `piece_id`, and `prompt_sha`. Filters: `user`, `item`, `testers=1`, `updated_since` |
| `GET /assessments/{id}` | one assessment with its messages, photo hashes in order, each photo's camera metadata, its `problems`, and the evaluator's critique if there is one |
| `GET /tester-feedback` | every evaluator critique, keyed on `session_id` |
| `GET /photos/{sha256}` | the JPEG bytes |

### Pulling the whole thing

```bash
KEY=kagua_...
BASE=https://kagua.gradykilleen.me/api/v1
offset=0
while :; do
  page=$(curl -sS -H "X-API-Key: $KEY" "$BASE/assessments?limit=200&offset=$offset")
  echo "$page" | jq -c '.items[]' >> assessments.ndjson
  [ "$(echo "$page" | jq -r .has_more)" = "true" ] || break
  offset=$((offset + 200))
done
```

`updated_since` makes that incremental: pass the highest `updated_at` you already have and
you get only what has changed. Assessments are ordered by `updated_at` ascending for exactly
that reason.

### Photos

`assessments/{id}` gives hashes, not bytes. Fetch each one from `/photos/{sha}`. They are
content-addressed, so the same photograph appears under one hash however many assessments
refer to it — deduplicate on the hash and you will not download it twice.

A hash that no assessment refers to returns 404 even if the file is on disk. That is
deliberate: the path parameter becomes a filename, so "any 64 hex characters" would
otherwise be a request for any file in the blob directory.

### Camera metadata

From v0.4.0 the phone reads chosen EXIF fields from each photo before it shrinks and
re-encodes it, and the uploaded JPEG carries none. The fields are stored beside the photo
(V25) and returned only by `GET /assessments/{id}`, as `messages[].photo_meta`: a list in
the same order as `messages[].photos`, so entry n describes photo n. An entry is `null` for
a photo sent without metadata, which is every photo from v0.3.0 or older and every photo of
a deleted account. Within an entry, a key the camera did not fill is absent.

| Key | Meaning |
|---|---|
| `taken_at`, `taken_at_offset` | when the photo was taken, by the camera's clock (`2026-11-10T14:03:22`), and its offset from UTC (`+03:00`) if recorded |
| `make`, `model` | the phone |
| `exposure_time_s`, `f_number`, `iso`, `flash`, `focal_length_mm`, `focal_length_35mm`, `white_balance` | camera settings. `flash` is the EXIF bit field (bit 0: fired); `white_balance` is 0 automatic, 1 manual |
| `lens_make`, `lens_model`, `lens_specification` | the lens; the specification is up to four numbers: shortest and longest focal length, then the smallest F-number at each |
| `body_serial`, `lens_serial`, `image_unique_id` | identifiers |
| `owner_name`, `artist` | names the phone's owner set in the camera |
| `original_width`, `original_height` | the photo's size before shrinking, upright |
| `gps_latitude`, `gps_longitude`, `gps_altitude_m`, `gps_time` | the camera's GPS (`gps_time` is UTC), only while the app's location switch was on |

Serial numbers, the unique ID, owner and artist names, and GPS identify a person or a
handset: store them with the restricted identifiers. They are not the assessment's location
(that is the session's own point), and many phones fill few of these fields.

## Joining it together

- `assessments[].user_id` → `users[].id`
- `tester_feedback[].session_id` → `assessments[].id` (one critique per assessment, at most)
- `messages[].photos[]` → `/photos/{sha}`
- `assessments[].piece_id` → the other assessments with the same `piece_id` (a piece and
  its re-assessments)

## Which build made an assessment

`assessments[].app_version_code` is the `versionCode` of the app that started the
assessment, sent by the phone in its `X-App-Version` header and written once when the
session is created (V24). Read it with `audience`: both apps share version numbers, so 4 in
Kagua and 4 in Fundi Bora are different builds. It is `null` for every assessment made by
v0.3.0 or older, which did not send the header; null means "not known" and stands for no
particular version. A change to photo processing or to the intake ships in a build, so this is
the column that separates assessments made before such a change from those made after.

## Purpose, language, piece, and prompt

Four more fields on every assessment, on both the list and the single route (v0.4.0):

| Field | Type | Example | Meaning |
|---|---|---|---|
| `purpose` | string or null | `"evaluate"` | why the maker opened the assessment: `evaluate` and `reevaluate` are full assessments with a verdict; `diagnose`, `work_in_progress`, `verify_fix`, `new_issue`, `customer_return`, `learn`, and `other` are partial checks |
| `language` | string or null | `"sw"` | the language the maker chose, `en` or `sw` |
| `piece_id` | string or null | `"5b0c…"` | the piece the assessment is of; a re-assessment carries its piece's id, so the two join |
| `prompt_sha` | string or null | `"9f2e…"` (64 hex characters) | SHA-256 of the full system prompt the server assembled when the assessment started |

`purpose` and `language` are decoded from the intake code Fundi Bora stores with the
session (`fundi-<language>-<purpose>`). Both are `null` for every Kagua assessment, and for
a Fundi Bora code this server cannot read; they are never one set and the other null.
Keep partial checks apart from full assessments in any rate: a clean check of one joint
says nothing about the rest of the piece.

`piece_id` is `null` for Kagua and for Fundi Bora assessments made before pieces existed.
`prompt_sha` changes whenever any prompt file that builds the system prompt changes, which
happens without an app release; the research team's translation table maps each value to
the prompt-file versions that built it.

## Problems

`GET /assessments/{id}` returns `problems`, beside `assessment` and `messages`: the
problems the assessment found, worst first, read from the stored replies on each request.
The list route carries none, since that would mean reading every reply on a page of 200.

```json
"problems": [
  {"title": "Gap at the back left joint", "area": "structural", "severity": "moderate",
   "dimension": "joint_fit", "default_part": "joinery"},
  {"title": "Fabric pulled unevenly over the arm", "area": "upholstery", "severity": "minor",
   "dimension": "finishing", "default_part": "upholstery"}
]
```

- Every purpose, not only full assessments: a diagnosis or a work-in-progress check lists
  its findings. Read `problems` with the assessment's `purpose`.
- The newest verdict and the newest diagnosis in the conversation decide, so a follow-up
  that corrects either replaces the earlier list. For an assessment with a verdict, the
  number of problems is the defect count the admin portal's quality rates use.
- Replies written before the v0.4.0 prompt change list each problem twice: as a diagnosis
  finding (the cause) and as a verdict defect (the consequence), linked by title. There the
  verdict's defects are the problems, and each takes its `dimension` from the finding with
  the same title, or `null` when no finding has that title. Replies written after list
  each problem once, as a finding with its own `dimension`.
- `default_part` is the part of the piece the server puts the problem down to, for linking
  it to the workers named in "Who worked on this?". The rules, first match wins: area
  `upholstery` → `upholstery`; then by dimension: `frame_squareness` → `frame`,
  `joint_fit` → `joinery`, `finishing` and `surface` → `finishing`, `hardware` →
  `assembly`, `material` → none; then by area: `surface` → `finishing`, `hardware` →
  `assembly`, and `structural`, `level`, `material`, `other` → none. `null` means the
  labels do not say whose work it was.
- `area`, `severity`, and `dimension` are lowercased and `null` when the reply left them
  out. The problems' wording (what I see, what it means, what to do, the cause) stays in
  `messages[].text`.
- `problems` is `[]` when the assessment found nothing or never reached a verdict or a
  diagnosis, and `null` when the problems cannot be known: the newest diagnosis block would
  not decode and no verdict defects stand in for it.

## Three things that will bite an analysis if nobody mentions them

**There are two apps in here.** `audience` is `buyer` for Kagua and `fundi` for Fundi
Bora, and it is on both users and assessments. Splitting on it is the first thing to do:
a fundi works through everything that came off the bench that morning and re-assesses
yesterday's pieces after repairing them, while a buyer checks one table in a shop. A rate
averaged over both describes neither population. Do not mistake it for `account_type`,
which says whether a business lets walk-in customers use its handset and is a different
question entirely.

The assessment's `audience` is the session's own rather than its account's. They cannot
differ today — an account keeps one audience for life — but the assessment is the record,
and reading it from the record beats joining to a profile to find out.

**Evaluator runs are in here.** `by_tester` marks an assessment done by one of our own
evaluators rather than a customer. Including them in a pilot finding overstates how well the
thing works, because staff know how to photograph a joint. Filter with `testers=1` to look
at only those, or exclude them on `by_tester`.

**Closed accounts are anonymised, not removed.** A user with `deleted: true` has had its
phone, name, business and location nulled, permanently and with no mapping kept. Its
assessments are still there under the same `id`. Those nulls mean "will never be filled in",
not "not captured" — which is what a null means on a live account. Treating the two the same
will quietly bias anything conditioned on having a business name.

See `docs/reverting-to-full-deletion.md` for why deletion works that way.

## Rate limit

120 requests/minute with a burst of 30, keyed on the API key rather than the address — a
university network shares one address between everybody on it. Over that, nginx answers 429.
The pull loop above stays well inside it; a loop with no `has_more` check does not.

## Not built

No writes, no aggregate or summary endpoints, and no CSV. Writes are absent structurally
rather than by check — only `GET` is mounted — so a leaked key cannot alter anything.
Aggregates were left out because every one of them is a research decision better made in the
analysis than fixed in an endpoint.
