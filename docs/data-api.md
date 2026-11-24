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
| `GET /users` | accounts, with phone, `audience`, name, business, latitude/longitude and accuracy, and `shop_mode` (V39). From V38 one number may be on two accounts, one per app (see below) |
| `GET /assessments` | assessments, newest last, each with the `app_version_code` that started it, its `purpose`, `language`, `piece_id`, `prompt_sha`, `assessed_as` and `final` (V34), and `shop_tablet`, `tablet_ended` and `tablet_ended_at` (V39), and `report_links` and `report_downloads` (V40; see "Kagua on a shop's tablet" below for all five). Filters: `user`, `item`, `testers=1`, `updated_since` |
| `GET /assessments/{id}` | one assessment with its messages, photo hashes in order, each photo's camera metadata, its `problems`, the evaluator's critique if there is one, its `tools` (V35, see below), and its `report_link_times` (V40, see "Kagua on a shop's tablet") |
| `GET /tester-feedback` | every evaluator critique, keyed on `session_id` |
| `GET /review-helpful` | each Fundi Bora tester's answer to "Was this review helpful?" after a verdict (V36): `session_id`, `user_id` (null once the tester's account is deleted), `helpful`, `answered_at`. One per assessment, the first answer given |
| `GET /pieces` | every piece a Fundi Bora maker has assessed: `id`, `user_id`, `item_type_id`, `created_at`, and its studio label, `studio_entry_id` and `studio_chosen_at` (V34). No name (see below) |
| `GET /piece-studio-changes` | each change of a piece's studio label after it was made (V34): `piece_id`, `user_id`, `kind` (`first_choice` or `correction`), `old_studio_entry_id`, `new_studio_entry_id`, `at` |
| `GET /studio-days` | each answer to "Which studio are you at today?" (V34): `user_id`, `day` (Nairobi date), `studio_entry_id` (null for the fundi's own workshop), `answered_at` |
| `GET /detail-answers` | every Fundi Bora detail answer (V37): the answer to one detail button ("More on what we found", "More on what's causing this", "How to upgrade next time", and from v0.7.0's improvement list "A tool to buy or rent") of one improvement. `id`, `session_id`, `source_message_id` (the diagnosis reply), `finding_index` (from 0) and `finding_title`, `detail_kind` (`what_we_found`, `cause`, `next_time`, `tool`), `source` (`prefetch` or `tap`), `answer_text`, `model`, `effort` (the thinking effort asked for; null is the API's default, high), `prompt_sha`, `usage_event_id`, `created_at`, and `opened_at` and `opened_message_id` (null for an answer the fundi never opened). See "Detail answers" below |
| `GET /translations` | every translation of a Fundi Bora reply (V41), made when the fundi first switched the assessment between English and Kiswahili: `id`, `session_id`, `message_id` (the first stored reply with that text), `source_sha256` (SHA-256 of the original reply's text), `language` (`en` or `sw`, the language translated into), `translated_text`, `model`, `prompt_sha` (of `prompts/fundi-translate.txt`; part of the row's key, so a new prompt writes a new row), `usage_event_id`, `created_at`. See "Translations" below |
| `GET /bench-photos` | each bench photo taken at Fundi Bora setup (V35): `id`, `user_id`, `sha256`, `latitude`, `longitude` and `location_accuracy_m` (null unless the fundi allowed location), `taken_at`, the `draft` tool list Claude made from it, `prompt_sha`, `model` |
| `GET /reports` | every PDF report the server issued (V32): `piece_id` or `worker_id`, `kind`, `session_ids`, `parts`, `defect_refs`, `defect_snapshot`, `sha256`, `byte_size`, `issued_at` |
| `GET /tracker-transactions` | every PesaLog transaction (V33), oldest first: `user_id`, `network`, `code`, `direction`, `type`, amounts, `occurred_at`, `counterparty_code` and `counterparty_basis`, `counterparty_name_code` and `counterparty_masked_code` (V42; see "PesaLog" below for the matching rule), `category`, `comment` |
| `GET /tracker-unread-counts` | per PesaLog account, `network` and `day`, how many mobile money messages the phone could not read |
| `GET /tracker-deletions` | each deletion a person asked for from PesaLog: `user_id`, `deleted_at`, and how many rows went |
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

## Pieces and reports

`GET /pieces` is the grouping: a Fundi Bora assessment's `piece_id` points here, and a
re-assessment of the same stool shares its piece with the first. It carries no label. What
the maker calls a piece is often the customer's name, and whether it may leave the server is
a question for the PIs (plan v7, section 9); the research team does not need it to join.

Since v0.6.0 (V34) a piece carries the studio it is for. `studio_entry_id` is an entry of
the maker's own "Studios I work for" list (`worker_studios`, V31), whose number links it to
the studio's account through `/worker-links`; null is "My own job" when `studio_chosen_at` is
set, and a piece never asked (made before v0.6.0 or by an older app) when it is null. A wrong
label can be corrected once; `/piece-studio-changes` holds each change with its old and new
entry, and the first answer for a piece never asked is a `first_choice`, not the correction.

The account that runs the studio a piece is labelled for may add its own assessments to the
worker's piece while the two sides are linked. Such an assessment has the manager's
`user_id`, the worker's `piece_id`, and `assessed_as` = `manager`; the maker's own have
`maker`. Kagua's, and an assessment of no piece, have null. There is no undo for a manager
who assesses the wrong piece, so wrong picks are found among the `manager` rows. `final` marks
the assessment of the finished piece that a customer report needs first: by the studio's
manager on a labelled piece, or by the maker on one that is not labelled.

## Tools

Since v0.6.0 (V35) a tool on a fundi's list is owned by the `worker` (the account itself),
the `studio`, or is a `rental`; `none` and `unknown` are unchanged. Answers
given before V35 were rewritten: `owned` became `worker` and `borrowed` became `rental`.

An assessment of a piece made from v0.6.0 on has `tools`: one row per tool to hand when it
started, each with `kind`, `source` (`maker` for the maker's own list, `studio` for the
studio manager's list on a piece labelled for a studio) and `owner`. The server infers the
list from the maker's list and, on a labelled piece, the studio manager's list, as each stood
when the assessment started; the fundi never sees it and Claude is never sent it. A manager's
assessment of a worker's piece carries the worker's tools, since the worker makes the piece.
An assessment made before V35, Kagua's, or one of no piece has an empty list.

`GET /bench-photos` keeps the draft Claude made from each bench photo, before the fundi
confirmed or rejected each tool, for comparing the draft with the list the fundi kept. The
photo's bytes come from `/photos/{sha256}`.

`GET /reports` is one row per PDF report the server issued, written whether the file was
freshly rendered or served from the day-long cache, so the rows count downloads started and
a retry has the same `sha256` as the first attempt.

- `kind` is one of `customer`, `part_issues`, `part_fixes`, `full`, `to_manager`, or
  `worker_work`. A `customer` report covers every assessment of the piece the sender may
  read (on a worker's labelled piece, the worker's and the manager's) and prints no worker
  name and no part; the others cover one chosen assessment, except `worker_work`, which is
  one roster worker's work across pieces, their own assessments of pieces labelled for the
  studio included (v0.6.0), and has `worker_id` set and `piece_id` null.
- `defect_refs` names each problem printed as `<message_id>:<ordinal>` in the reply the
  problem list was read from (the newest reply of the session with a verdict or a
  diagnosis). A corrected verdict is a newer reply, so its problems have new references.
- `defect_snapshot` is what was printed, kept because a client-deleted assessment is purged
  after seven days and the parser can change: `assessments` (each `session_id`, `level`,
  `headline`), `problems` (each `session_id`, `message_id`, `ordinal`, `title`, `severity`,
  and the `part` it was printed under, null on a customer report), and `workers` (each
  `session_id`, `part`, `worker_id`). The row also holds the names the report printed, under
  each part and of the account that made it; those are not carried here, as the roster's
  names are not. Join `worker_id` to `/workers`.
- `sha256` is the hash of the bytes the phone received, which is what a check of a PDF
  somebody forwards would compare against.
- A deleted account's reports stay, with their hashes; their snapshot is emptied to `{}`.

## PesaLog

PesaLog is a separate app that reads mobile money notifications (M-Pesa and Airtel Money)
and signs in with a Fundi Bora account, so `user_id` joins its rows to that person's
assessments. `counterparty_code` is an HMAC of the other party's number or name under the
same key and rules as Fundi Bora's M-Pesa records, so the same person has the same code in
both apps and across accounts; the number or name itself was never stored.

- A `code` is unique per account and `network`; an M-Pesa code and an Airtel Money id can
  coincide.

### Matching a payment sent with a payment received (V42)

M-Pesa prints the other person's full number on money sent ("JANE DOE 0720456123") and a
masked one on money received from a person ("JANE DOE 0720***123"). `counterparty_code` is
made from the full number when there is one and from the name otherwise, so a send to Jane
and a receipt from Jane have two unrelated `counterparty_code`s. From V42 each row also
carries two more codes, under the same key, so the two can be matched:

| Field | An HMAC of | Null when |
|---|---|---|
| `counterparty_name_code` | the name, trimmed, lower-cased, and with each run of spaces made one ("name:jane doe") | the payment names nobody, or the phone did not send the name |
| `counterparty_masked_code` | the number cut to what money received shows: the first four and last three digits of the local form ("masked:0720***123"), from a full number or a masked one | the payment carries no Kenyan mobile number, full or masked |

**The matching rule.** Two payments are with the same person when

1. their `counterparty_code`s are equal and both have `counterparty_basis` = `phone`, or
2. their `counterparty_name_code`s are equal **and** their `counterparty_masked_code`s are equal.

Neither new code alone identifies a person: about a thousand real numbers share any one
masked number, and namesakes share a name. The server stores the codes and computes no
match; the rule is applied in the analysis. A `counterparty_name_code` equals the
`counterparty_code` of a row coded from the same name (basis `name`), since both are made
from the same input.

What each phone sends, and so which codes its rows have:

- **PesaLog 0.2.0 and later** sends the name and the number on money sent, and the name and
  the masked number on money received. A send and a receipt with the same person both get a
  name code and a masked-number code, so rule 2 matches them.
- **PesaLog 0.1.0** sends the number and not the name on money sent, and the name and not
  the masked number on money received. Its sends get a masked-number code and no name code;
  its receipts a name code and no masked-number code. Rule 2 never matches its rows.
- **Rows stored before V42** have neither new code. They cannot be back-filled: the server
  never kept the name or number to make them from.

Fundi Bora's own M-Pesa records (`mpesa_transactions`, V29) get the same two columns, by the
same rules; a v0.5.0 phone sends what PesaLog 0.1.0 sends. They have no data API route yet.
- A person can delete their PesaLog rows from the app's Settings. Their transactions and
  unread counts then disappear from these routes, and `/tracker-deletions` gains a row
  saying when and how many.
- Fundi Bora's own M-Pesa records (V29) have no data API route yet.

## Detail answers

From prompt E (#107, 18 November 2026) a Fundi Bora diagnosis no longer writes each finding's cause and habit; the fundi taps a button under an improvement and Claude answers that one detail. From v0.7.0 (V37) the server writes the answers to every button of the **first two improvements** of each diagnosis as soon as the diagnosis arrives (`source` = `prefetch`), and keeps every answer, prefetched or tapped, in `GET /detail-answers`. Improvements after the second are answered on tap (`source` = `tap`).

- **What the fundi saw.** An answer reaches the fundi, and the conversation, only when they open it: `opened_at` is the first opening and `opened_message_id` the reply that showed it, which is also in `GET /assessments/{id}`'s messages. An answer with no `opened_at` was written and never seen. A tapped answer is opened as it is written.
- **What Claude saw.** An unopened answer never enters the conversation, so no later turn's reply was written with it in view.
- **Cost.** Each prefetch is a `usage_events` row with `call_kind` = `detail_prefetch`, joined by `usage_event_id`. A tap's call is billed as a `turn`, as before. Neither counts against the daily allowance, which counts assessments started.
- **Thinking effort.** From v0.7.0, Fundi Bora's turns, notes and detail answers ask for medium thinking (`effort` = `medium`; the server setting `fundi_effort`), where before they used the API's default, high.
- **Deletion.** When an account is deleted its rows stay, with `finding_title` and `answer_text` set to null.

## Translations

From v0.7.0 (V41) each Fundi Bora assessment has a switch between English and Kiswahili. An assessment opens in the language chosen at its intake. The first time the fundi switches, the server translates every reply of the assessment with one Claude call per reply (`prompts/fundi-translate.txt`) and keeps each translation in `GET /translations`; every later switch, on any phone, reads the stored one. The server refuses a request for the assessment's own language.

- **Joining.** A translation names its reply by `source_sha256`, the SHA-256 of the reply's text as stored in `messages`; `message_id` is the first reply with that text.
- **Prompt versions.** `prompt_sha` is part of a row's key. The server serves only rows written by the current translation prompt, so a new prompt (for example row 16's glossary) writes a new row for each reply on the next switch; the old rows stay. Filter on `prompt_sha` to compare the two.
- **What the fundi saw.** A row means the fundi switched that assessment to `language` at least once while that prompt was current, at `created_at` or before. Later switches, and switching back, are not recorded.
- **What Claude saw.** Nothing: a translation never enters the conversation, and every later turn is written from the original replies.
- **What is translated.** Every reply, prose and blocks, including the plan's photo instructions, test questions and answer buttons and the reply chips. What the fundi taps on those is recorded and sent in Claude's original words, chosen by place, so the conversation is the same in either language.
- **What is not translated.** The fundi's own words, and a prefetched detail answer the fundi never opened.
- **Refused translations.** The server keeps a translation only if every block has the same shape, keys, numbers and fixed-list values as the original; the prose and every written value carry the same numbers; the prose has as many paragraphs; and the readable text is between 0.4 and 2.5 times the original's length (checked from 80 characters). A refused one leaves no row; its call is still in `usage_events`.
- **Cost.** Each call is a `usage_events` row with `call_kind` = `translation`, joined by `usage_event_id`. It does not count against the daily allowance.
- **Deletion.** When an account is deleted its rows stay, with `translated_text` set to null.

## Kagua on a shop's tablet

From v0.7.0 (V39) DIL may give a shop a tablet with Kagua on it. The shop owner registers it with a shop-tablet invite code, and walk-in customers use it to check a finished piece before buying. The tablet deletes its own copy of each customer's assessment; the server keeps every one.

- **Which accounts.** `shop_mode` on `GET /users` is true for an account registered with a shop-tablet code. It is set by the code at registration and never changes, and only Kagua accounts (`audience` = `buyer`) can have it. Its `account_type` is whatever the owner answered at sign-up; read `shop_mode`, not `account_type`, to find the tablets.
- **Which assessments.** `shop_tablet` on `GET /assessments` is true for an assessment made on a shop's tablet, copied from the account when the assessment started. These are customers' assessments, not the shop owner's, and every one records buying (the tablet does not ask "buying or already own it").
- **How the tablet's copy ended.** `tablet_ended` is `done` (the customer pressed "Done: delete this assessment", or went back to the start screen), `left_app` (the app left the screen with the screen on: Home, another app, a notification), `screen_lock` (the screen went off or locked), or `idle` (five minutes with no touch). `tablet_ended_at` is when the server heard it, not when it happened: a tablet that was offline reports when it is back. Both are null for an assessment not made on a tablet, and for one whose tablet never reported (switched off mid-way, or still offline). The first report stands.
- **Deletion.** A shop's assessments are never marked `deleted_by_user`: the tablet's own deletion is local only, and the server ignores a delete from an older app signed in to a shop account.
- **The customer's PDF by QR code** (v0.7.0, V40). After the verdict the tablet can show a QR code; the customer scans it with their own phone and downloads a PDF of the assessment, with no sign-in and no number collected. `report_links` on `GET /assessments` is how many codes the tablet showed for the assessment, and `report_downloads` how many times a customer downloaded the PDF through them (each link works for 30 minutes and three downloads). Both are 0 for every assessment not made on a tablet. A download does not change `updated_at`, so `updated_since` does not pick up a new one; a full pull does.
- **When each code was made and used.** `GET /assessments/{id}` returns `report_link_times` beside `assessment`: one object per code the tablet showed, oldest first, with `created_at`, `expires_at` (30 minutes after `created_at`), and `downloaded_at`, a list of each download's time, oldest first, at most three. A code whose `downloaded_at` has three entries stopped working at the third, before its `expires_at`. A tablet may show up to five codes for one assessment (the customer presses "Get a new code" when one stops working). Empty for every assessment not made on a tablet. The token and its hash are never returned.

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

From v0.7.0 (V38) **one phone number may appear on two users**: one Kagua account
(`audience` = `buyer`) and one Fundi Bora account (`fundi`), never two of the same app. A shop
owner who uses Fundi Bora registers Kagua on the shop's tablet with the same number. The two
are separate accounts with separate ids, passwords and histories, so key on `id`, never on
`phone`, and read `audience` beside the number. A roster's or a studio list's number (`GET
/workers`, `/worker-links`) links only to the Fundi Bora account on that number.

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
