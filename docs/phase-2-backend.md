# Kagua — Phase 2 backend, single-instance plan

Everything on one AWS instance: app, database, and photo storage. No RDS, no S3, no load
balancer. One instance is a single point of failure, which is accepted at pilot scale — an
hour of downtime means a buyer retries later.

## Why there is a backend at all

Phase 1 has none: the app calls `api.anthropic.com` directly with a key the user pastes in.
That key grants unmetered access to our Anthropic account to whoever holds the phone. Fine
for pilot testers we know by name, unacceptable for distribution. Phase 2 moves the key
server-side. Everything else — history sync, serving prompt files, per-user quotas — follows
from having a server at all.

## Stack

- **Kotlin + Ktor** — the Anthropic wire types and prompt assembly already exist as tested
  Kotlin in this repo, so the server reuses them instead of reimplementing the format.
- **PostgreSQL 17 + PostGIS 3.5**, on the same instance.
- **Photos on the EBS volume**, not object storage.
- **Nginx** in front for TLS.
- JWT auth, short-lived access token plus refresh.

## Constraints the server has to fit

Already shipped in the app; not negotiable server-side.

- Plain HTTPS JSON. No streaming, no websockets.
- **Requests take up to 120 seconds** — Claude vision on a slow connection.
- **Bodies up to ~15 MB** — nine base64 photos in one request.
- `cache_control` must pass through unchanged; it is a 1-hour prompt cache and it cuts the bill.
- Photos arrive already resized to 1568px / JPEG 80. Do not re-compress.

## Email sent to IT

See below — the operational ask in the form it was sent.

---

Subject: **Server request — Kagua backend (pilot)**

Hi,

We're adding a small backend to our Android app so the AI API key lives on a server instead
of on users' phones. Pilot scale: roughly 200 users and a few hundred requests a day.
Everything on one instance — app, database and file storage.

What we need:

- **1× EC2 instance**, Ubuntu 24.04, **t3.large** (2 vCPU / 8 GB). Burstable is fine — the
  service spends nearly all its time waiting on an upstream API.
- **100 GB gp3 EBS**, expandable, with an alert at 70%. Uploaded photos are what will fill it.
- **PostgreSQL 17 on the same box, with the PostGIS extension enabled.** PostGIS matters: we
  will add location-based search later, and enabling it now is far cheaper than migrating
  onto it once there is production data.
- **Nginx** in front, TLS via Let's Encrypt, one DNS record (`api.kagua.<domain>`) and an
  Elastic IP.
- **Two nginx settings that differ from the defaults** and will break us silently otherwise:
  - `proxy_read_timeout 180s` — our AI requests can take up to two minutes, and the default
    60s would 504 exactly the slowest and most important ones
  - `client_max_body_size 25M` — each request can carry up to nine photos
- **Security group:** inbound 443 only; outbound 443 to `api.anthropic.com`. Access via SSM
  Session Manager rather than an open port 22, if that suits you.
- **SSM Parameter Store** (SecureString) for three secrets — Anthropic API key, JWT signing
  key, database password — readable by the instance role. The API key needs to be rotatable
  without a redeploy.
- **Backups:** nightly `pg_dump` to the volume plus daily EBS snapshots, 30-day retention.
  One tested restore before we go live — assessments are the one thing a user cannot
  recreate, because the furniture is back in a shop somewhere.
- Please don't log request bodies. They contain photographs of people's homes and workshops.

A second, smaller instance for staging would be useful but is not a blocker.

We know one instance is a single point of failure and we're comfortable with that for now.

Happy to talk it through.

Thanks,
Grady

---

## The server as provisioned (surveyed 31 Aug 2026)

`i-06c19c68c05b8a0df`, us-east-1a. Access is via SSM Session Manager only — there is no SSH
key pair on the instance.

| | |
| --- | --- |
| Type | t3.medium (2 vCPU / 3.7 GB) — deliberately small for development cost |
| OS | Ubuntu 24.04.4 LTS, x86_64 |
| Disk | 100 GB gp3, 5% used |
| Elastic IP | 184.72.223.202 |
| PostgreSQL | 16.15, bound to 127.0.0.1:5432. Database `ubora_poc` exists |
| PostGIS | 3.4.2 packages present, **not yet enabled in any database** |
| Java | OpenJDK 21.0.12 |
| nginx | 1.24.0, only the stock `default` site |
| Instance role | `ubora-poc-ec2-role`: `AmazonSSMManagedInstanceCore` + inline `UboraBedrockModelInvoke` |
| Security group | inbound 80 and 443 from 0.0.0.0/0; outbound all |
| unattended-upgrades | enabled |

Bedrock is where IT expects this to go eventually, hence that inline policy, but Phase 2
targets the Anthropic API directly. Keep the server's Claude client behind an interface so
the swap stays contained — the same reason `AppContainer` exists on the app side.

## Done

- **TLS, 1 Sep 2026.** `https://kagua.gradykilleen.me` → 184.72.223.202. Let's Encrypt cert
  (issuer YE2) valid to 30 Nov 2026, `certbot.timer` active, `certbot renew --dry-run`
  passes. Domain registered on Namecheap under their academic offer, records on Namecheap
  BasicDNS, A record TTL 5 min.
- **nginx vhost** from `server/deploy/nginx/kagua.conf.template`, with
  `client_max_body_size 25m` and `proxy_read_timeout 180s` confirmed inside the 443 block.
  The proxy_pass location stays commented out until there is a service on 8080.
- **certbot 2.9.0** + nginx plugin installed. Only package added to the box so far.

- **Swap, 1 Sep 2026.** 2 GB `/swapfile`, in `/etc/fstab`, `vm.swappiness=10`. A backstop
  against the OOM killer, not a tier meant to be used.
- **Postgres tuned** via `server/deploy/postgres/kagua.conf`, a `conf.d` drop-in so the
  shipped config is untouched. Sized for sharing 3.8 GB with a JVM: `shared_buffers 512MB`,
  `effective_cache_size 1536MB`, `max_connections 40`, `random_page_cost 1.1`, `jit off`,
  slow-query logging at 1s.
- **Database, 1 Sep 2026.** Role and database `kagua`, PostGIS 3.4.2 enabled, schema
  `V1__init.sql` applied and recorded. `sessions.location` is a `geography(Point,4326)` with
  a GiST index, and `ST_DWithin` runs — the extension is proven end to end, not just
  installed. IT's `ubora_poc` database is untouched.
- **Secrets.** `/kagua/db/password` and `/kagua/jwt/signing-key`, both SecureString,
  generated *on the box* so they never passed through an SSM command log. Instance role
  policy `KaguaSsmParameters` was write-then-read during bootstrap and is now read-only.

## Outstanding before the first endpoint

1. ~~DNS and TLS~~ — done, above.
2. **`/kagua/anthropic/api-key`** — the rotated key, put in by hand from an admin machine
   (the instance role is read-only). Settled 1 Sep: staying on the Claude API key rather
   than Claude Platform on AWS or Bedrock, because Marketplace billing is a problem on our
   side. **The superseded Phase 1 key stays active until 9 Sep 2026** so collaborators'
   local testing is not disrupted; it must be revoked on that date. It was shared in plain
   text during development and has been in an emulator's encrypted prefs for weeks.

## Decided 1 Sep 2026

- **Claude access:** first-party API with a key in Parameter Store. Workload Identity
  Federation is GA but needs an OIDC token from a trusted issuer, which a plain EC2 box
  does not mint — it is built for EKS, GitHub Actions, GCP and Azure workloads. Claude
  Platform on AWS would have removed the key entirely via SigV4 and the instance role, with
  same-day API parity and prompt caching intact, but billing routes through AWS Marketplace.
  Rejected on billing, not on engineering.
- **The client sends only the new turn; the server appends the prior history.** Identical
  upstream requests, and the phone stops re-uploading the same photos every turn — roughly
  5–15 MB per assessment down to 2–3 MB once. This forces the photo-storage decision to
  "yes, stored, deduped by `sha256`". `ChatService` does not change: the implementation
  chooses what goes on the wire, so the Phase 2 seam still holds.
  - **The risk is prompt caching.** Cache hits are byte-exact prefix matches, so the server
    must serialise the rebuilt history deterministically — stable key order, identical
    `cache_control` placement. Getting it wrong raises no error; the cache just stops
    hitting and the bill grows. Assert `usage.cache_read_input_tokens > 0` in a test.
- **Deleted chats are retained 7 days** server-side, then really deleted. Disclosed in the
  app — draft wording in `docs/retention-and-profile-wording.md`.
- **Registration collects** name, individual-or-business, business name, and an optional GPS
  point captured only if they are at the premises. `V2__profiles_and_retention.sql`.
3. **No swap**, on 3.7 GB that will run a JVM and Postgres together. A 2 GB swapfile is cheap
   insurance against the OOM killer choosing for us.
4. Postgres tuned for 4 GB; app role and database with `CREATE EXTENSION postgis`.
5. Ktor skeleton with a health endpoint, then the prompt-file endpoint first — the smallest
   slice that proves the whole path.

## Admin portal (built 1 Sep 2026)

Same Ktor process, routes under `/admin`, `kotlinx.html` for markup. No second JVM, no Node,
no frontend build — on a 3.7 GB box that matters. Two new dependencies: an Argon2id hasher
and a TOTP library.

Server-rendered HTML with paginated queries is nearly free next to a single vision request.
Photos are already ≤1568px at q80, so they are served directly with `loading="lazy"` and
need no thumbnail pipeline.

**Scope**

- Invite codes: create, label, revoke.
- Curated read-only views of users, sessions and usage. Paginated and filterable.
- Chat view: one session's turns in order, **photos inline and visible without a
  click-to-reveal** — they are the point, since the job is judging whether the assessment
  was accurate, and they are overwhelmingly workshop photos read by academics in offices.
  Verdicts rendered with the same `AssistantBlocks` parser the app uses, so an admin sees
  what the customer saw from one implementation.
- Export for research analysis: CSV/JSON of a filtered set, streamed with a cursor.
  **Photos excluded by default, with an explicit option to include them.**

**Enrolment, resets and remembered browsers (2 Sep 2026)**

- The enrolment page renders a **scannable QR** (ZXing, drawn as inline SVG). Inline rather
  than an `<img>`: the CSP allows `img-src 'self'` and would block a `data:` URI, and
  serving it from a route would mean the TOTP secret existing as a URL, which is how a
  secret ends up in an access log. The typed secret stays on the page as a fallback.
- **A lost authenticator is reset by another admin**, who must re-enter their own password.
  Three rules, each covering a way the reset could be the attack rather than the remedy: never
  your own account (a borrowed session would otherwise move the second factor to the
  attacker's phone), the password again even though already signed in (this is the one action
  that hands out a working credential), and the target's remembered browsers are forgotten
  (otherwise they keep signing in on a remembered machine and never enrol the new secret,
  leaving an account whose second factor exists only in the database).
- **`ResetAdminTotp` on the box** is the break-glass path for the case the portal cannot
  cover: one admin, or every admin, having lost their authenticator, with nobody left who can
  sign in to do the reset. It needs server access, which is a higher bar than a portal
  password and could read the database anyway, so it grants nothing new. The password is
  untouched — a reset alone is not a way in.
- **"Remember this browser for 30 days"** skips the code, never the password and never
  enrolment. The cookie is 32 random bytes stored only as SHA-256, looked up by hash and then
  checked to belong to the account signing in — without that second check any issued cookie
  would satisfy anybody's second factor. Forgotten on a password change, on a 2FA reset, and
  from a button on the Admins page.

  The honest trade-off: somebody holding an unlocked laptop with that cookie needs only the
  password, for up to thirty days. That is a real reduction in what 2FA buys, taken because
  the alternative — a code every sitting, since the idle timeout is thirty minutes — is the
  friction that ends with the secret on a sticky note. It is bounded rather than removed.

  `Secure` on that cookie comes from an **explicit flag, not the request scheme**. nginx
  terminates TLS and proxies over plain http on loopback, and `XForwardedHeaders` is not
  installed, so `origin.scheme` reads "http" in production. Deriving it — which is what I
  wrote first — would have shipped a thirty-day second-factor bypass without the Secure flag.

**Deliberately not built: an arbitrary SQL box.** It is remote code execution and a bulk
exfiltration tool in one text field, and no amount of auth in front changes that. Ad-hoc
queries are what `psql` over SSM is for, run by somebody who already has server access.

**Security, given what is behind it** — 2–3 admins initially, possibly more:

- Argon2id password hashing.
- TOTP 2FA, agreed as required rather than optional.
- Session cookies `HttpOnly; Secure; SameSite=Strict`, short idle timeout.
- CSRF tokens on every mutating form.
- nginx **rate limiting** on `/admin/login` rather than an outer HTTP Basic gate. Basic was
  the first suggestion, but with a password and a TOTP code already in play a third
  credential buys little and invites password reuse; rate limiting keeps scanners out of
  app code at no cost to the admins.
- **Audit log — required.** Who viewed which session, who exported what, when. With a portal
  that can read every customer's conversation, the audit trail is what makes misuse
  detectable rather than theoretical.
- **No self-registration.** The first admin is created by a one-off script over SSM; admins
  create each other from inside.

The in-app wording gains a line about human review — see
`docs/retention-and-profile-wording.md` § 2b.

**As built**

- `AdminStore` is an interface with `PostgresAdminStore` behind it, for the same reason
  `AuthStore` and `ChatStore` are: a portal whose refusals can only be exercised against a
  live database is a portal whose refusals are not exercised. 18 route tests cover the
  gates — no session, password-only, wrong code, missing CSRF token, disabled and locked
  accounts, last-admin protection, and that customer chat text is escaped rather than
  rendered.
- **TOTP is written out rather than pulled in** (`admin/Totp.kt`, ~40 lines plus base32).
  It is checked against RFC 4226 Appendix D, RFC 6238 Appendix B and RFC 4648 §10, which is
  stronger evidence than a dependency's own suite and one fewer jar on the box. HMAC-SHA1
  because that is what authenticator apps assume; SHA-256 would be defensible and would
  silently produce codes no app can generate.
- Only two new dependencies, both Ktor modules: `ktor-server-html-builder` and
  `ktor-server-sessions`. Argon2id came free — Bouncy Castle was already in for customer
  passwords.
- Sessions are a signed cookie, not a table: `HttpOnly`, `Secure`, `SameSite=Strict`, path
  `/admin`, with a **30-minute idle timeout** and a **12-hour absolute cap**. The cookie is
  signed but not encrypted, so it carries nothing the holder does not already know.
- The CSRF check returns the parsed form to the route. Ktor throws
  `RequestAlreadyConsumedException` on a second `receiveParameters`, so a route that
  checked the token and then re-read the body would 500 on every submission — which is what
  the first version did, until a test caught it.
- An unknown email still costs an Argon2id verification against a dummy hash. Answering it
  measurably faster is how a login form leaks its user list.
- Bad TOTP codes count against the same lockout as bad passwords: 5 attempts, then 15
  minutes. Otherwise the second factor is six digits with unlimited guesses.
- Photos on the conversation page are checked against `attachments` before being read from
  disk. A path parameter that takes a hash plus content-addressed storage is exactly the
  shape where "any 64 hex characters" becomes "any file in the blob directory".
- `KAGUA_ADMIN_SESSION_KEY` is required; without it the portal is not mounted at all. A
  portal with a predictable signing key is worse than no portal.
- nginx rate limiting needs **two** files: `kagua-admin-ratelimit.conf` into
  `/etc/nginx/conf.d/` for the `limit_req_zone` (not valid inside a server block) and the
  `limit_req` in the vhost.

**The first admin**

No self-registration, so the first account comes from the box:

```
KAGUA_DB_PASSWORD=... java -cp /opt/kagua/kagua.jar \
  com.qualityverifier.server.admin.CreateAdminKt "someone@example.com" "Their Name"
```

The password is read from **stdin**, never an argument: an argument would land in the shell
history, in `ps`, and — because this is run over SSM — in an AWS command log that keeps its
parameters. It prints the TOTP secret once. The account cannot sign in until a code from
that secret has been accepted, so an abandoned enrolment leaves no usable account behind.

**Bringing the portal up, in order**

1. **Generate the session key on the box** and put it straight into Parameter Store from
   there. It must not travel as an SSM command parameter — those are kept in the AWS command
   log, which would leave the key guarding the portal sitting in an audit trail:

   ```
   openssl rand -base64 48 | tr -d '\n' > /tmp/k && \
     aws ssm put-parameter --region us-east-1 --name /kagua/admin/session-key \
       --type SecureString --value "file:///tmp/k" && shred -u /tmp/k
   ```

   The instance role is read-only on Parameter Store, so this is run from an admin machine
   or the role is widened for the one call. Either way the value is generated where it will
   be used and never printed.

2. **Apply `V8`** with `server/db/apply.sh`, as the `kagua` role. Applying it as `postgres`
   would leave the new tables owned by postgres and the app unable to read them — which is
   exactly the fault that produced `V4`'s ownership fix, and the script now asserts against
   it.

3. **Restart** so the launcher picks up `KAGUA_ADMIN_SESSION_KEY`. Until it does, the log
   says the portal is disabled and `/admin` 404s — which is the intended failure.

4. **nginx**, two files and one of them by hand:
   - copy `kagua-admin-ratelimit.conf` into `/etc/nginx/conf.d/`
   - add the `location ~ ^/admin/(login|2fa)$` block to the live vhost **by editing the file
     on the host**. Do not reinstall the template: certbot rewrote it in place, and copying
     the pre-TLS form back would drop the `listen 443` block and return the site to plain
     HTTP, which the app refuses to talk to.
   - `nginx -t`, then reload. Note that a reload returns before new workers are serving, so
     a check immediately afterwards can still hit the old config.

5. **Create the first admin** with the command above, add the secret to an authenticator,
   then sign in and confirm the code is accepted.

6. **Check the quota's SQL**, which has no unit test: start assessments past the limit on a
   throwaway account and confirm the 429 arrives with `daily_limit_reached` and that
   `usage_events` shows nothing was sent to Claude for the refused turn.

**Done 2 Sep 2026, and what it corrected**

- The launcher is **`/opt/kagua/bin/kagua-run`**, not `/usr/local/bin/kagua-run` — that is
  what the systemd unit's `ExecStart` names. Writing to the wrong path creates a file
  nothing runs, and `grep -c ... || echo 0` on a missing file reports `0` matches rather
  than "no such file", which is how the mistake survived a check. The diff against the
  installed launcher was exactly the three added lines, which is the check worth doing.
- **Migrations are not part of the deploy.** `/opt/kagua/db/migrations` is populated by
  hand, so a new migration has to be put there before `apply.sh` will see it.
- The session key was generated **locally and written with `--value file://…`**, then the
  local file was overwritten before being unlinked. The property that matters is that the
  value never appears in an SSM command document, since AWS keeps those; generating it on
  the box is one way to get that, not the only one.
- SSM's shell is **`sh`, not bash** — `<(...)` process substitution fails with
  `Syntax error: "(" unexpected`.
- An `nginx -t` before reloading is worth it: the `limit_req_zone` has to exist in
  `conf.d/` before the vhost's `limit_req` will validate.
- The vhost location was inserted **before `location /` inside the 443 block** with awk,
  against a `.before-admin` backup, and `listen 443` was confirmed still present afterwards.

**Verified live**

- 8 migrations applied as `kagua`, ownership assertion passed, and a **second** `apply.sh`
  run skipped all 8 and exited 0 — which is what V7 and V8 failing to record themselves
  would have broken.
- `/admin` redirects to `/admin/login`; the login page renders with `no-store`, the CSP,
  `nosniff`, `DENY` and `no-referrer` all present.
- Login rate limit: 12 rapid POSTs gave `401 401 401 401 401 401 429 429 429 429 429 429` —
  burst of 5 plus one, then nginx.
- **The quota, at the real limit of 20 and for no Claude spend.** Seeding session rows and
  attempting a 21st assessment returned `429 daily_limit_reached`, created no session row,
  and left `usage_events` untouched — nothing reached Claude.
- **The timezone conversion matters and works.** With one session at 22:00 UTC on 1 Sep
  (01:00 on 2 Sep in Kampala), the Kampala-day count was **20** and the UTC-day count
  **18**. On UTC the account would still have had two assessments in hand.
- An assessment **already under way** returned 200 with a real reply while the account was
  at its limit.

## Applying migrations

**Use `server/db/apply.sh`.** It runs every migration as the `kagua` role, keeps
`CREATE EXTENSION postgis` as the single superuser step, and then asserts that every
application table is owned by `kagua` before reporting success.

That assertion exists because of a real failure. V1 was applied as `kagua`, but V2-V5
were applied as `postgres`, since PostGIS needs a superuser. Adding *columns* that way
is harmless — ownership does not change. V4 created a **table**, so `refresh_tokens`
ended up owned by `postgres`, and the first real sign-in returned
`permission denied for table refresh_tokens`.

Two things about how it escaped:

- No unit test could catch it. The route logic is tested against a fake store, and the
  fake has no notion of a grant.
- The by-hand SQL check missed it because that check also ran **as `postgres`** — the one
  user that did have permission. Verifying as the wrong user is a verification that
  cannot fail.

Ownership alone is also not proof, so the script finishes by having the app role read
`refresh_tokens` for real.

## Auth: what the app must get right

The server side is built. These are requirements on the phone, and the first one is
load-bearing enough that getting it wrong looks like a security incident to the user.

1. **Single-flight refresh, mandatory.** Refresh tokens rotate and a replayed token
   revokes the whole chain. If two requests 401 at once and both refresh with the same
   token, the second presents a spent one, the server reads theft, and the user is signed
   out completely. A returning user — app reopened, several requests fired at once — is
   exactly the case that triggers it. One refresh at a time behind a mutex, others queue
   on its result.
2. **Refresh proactively** when the access token is within ~2 minutes of expiry, so a 3 MB
   photo upload does not begin on a token that dies mid-flight.
3. **Refresh-and-retry interceptor.** Until this exists, a 15-minute access token means a
   customer who puts the phone down and comes back sees an error. The token lifetime is
   only invisible because something refreshes it.

Two properties worth knowing rather than rediscovering:

- The 60-day refresh window is **sliding** — every refresh issues a fresh 60 days, so it
  bounds inactivity, not total account age.
- After 60 days of inactivity a user is locked out with **no route back in**: registration
  is one-time by invite code and there is no sign-in flow. Acceptable for a pilot where we
  can issue another code; it needs a real answer before anything wider.

## Decisions still ours

1. ~~**Do photos go to the server at all?**~~ Settled: yes. Rebuilding history server-side
   requires it, and the portal needs them for accuracy review. Stored once, deduped by
   `sha256`.
2. ~~**Photo retention**~~ — follows the session: 7 days after a customer deletes an
   assessment, 30 days after they delete the account. Settled 1 Sep 2026 for assessments
   nobody deletes: **retained permanently for now.** They are the research record, and the
   pilot's whole purpose is judging assessment accuracy against the photographs. Worth
   revisiting before any non-pilot use, since "permanent" is the one retention answer that
   cannot be walked back for data already collected.
2b. **Photo files on disk** — swept, added 2 Sep 2026. `purge_expired()` is SQL and cannot
   touch the filesystem, so until this the retention windows deleted the record of a
   photograph and kept the photograph: the delete dialog's "then it is deleted for good"
   was true of the conversation and false of the pictures, and the bytes stayed reachable
   by anything with disk access or an EBS snapshot.

   `BlobSweeper` runs in the server process — it needs the database *and* write access to
   the blob volume, and `kagua-purge.service` deliberately has neither, running as
   `postgres` over peer authentication so a daily timer holds no secret. It deletes files
   no live `attachments` row points at, **globally rather than per session**: blobs are
   deduplicated by hash, so two customers who photograph the same thing share one file and
   a per-session check would take a photo somebody still has.

   A **seven-day grace period** on file mtime protects photos uploaded before the turn that
   references them — legitimately unreferenced until the customer submits, and until the
   next day if that submission failed and they retry.

   Sweeping rather than tracking is deliberate. A `deleted_blobs` table would reproduce the
   original bug in a new form: one missed write and the file is invisible for ever. An
   orphan left by a crash is found on the next run.

   **Known tail:** the sweep is daily, so deletion completes within about 24 hours of the
   window expiring rather than on the hour. The wording says 7 days, which is when the
   window ends; worth tightening the interval if that ever needs to be exact.

2c. **Account deletion anonymises rather than erases** — changed 2 Sep 2026, and switched
   on. No IRB submission had been made, so the choice was between promising full deletion
   now and retracting it once the pilot data turned out to matter, or saying from the start
   that data may be kept and that deleting an account removes the identifiers. The second is
   the honest order; withdrawing a deletion right after people have relied on it is the
   version that is hard to explain afterwards.

   `anonymise_user(uuid)` nulls phone, password, name, business name, the location point and
   the invite label, sets `anonymised_at`, and drops every refresh token — in one
   transaction, immediately, with **no mapping table anywhere**. The `users` row survives so
   `sessions.user_id` resolves, and its random uuid is the anonymous identifier the research
   uses. `purge_deleted_accounts` is left defined but no longer called, which makes the
   revert a one-line change.

   **It does not make the record anonymous, and nothing user-facing claims it does.**
   Photographs show workshop signage and premises; free text may name a person. The wording
   at registration and at deletion says we remove what we hold about them, and asks them not
   to put personal details in the conversation. Keeping that distinction is the whole legal
   question — pseudonymised data is still personal data.

   Reverting: `docs/reverting-to-full-deletion.md`.

   **Open, and not decided by this:** deleting a single report still erases it for good
   after 7 days. So somebody who deletes every report individually gets a real erasure,
   while closing their account keeps the assessments anonymised. Defensible, but a gap
   somebody could walk through.

3. ~~**Region.**~~ Settled: us-east-1.
4. ~~**Auth identity.**~~ Settled: invite codes for the pilot. SMS later if needed.
5. ~~**Per-user quota.**~~ Built 1 Sep 2026. **20 assessments started per account per
   day**, configurable at runtime via `KAGUA_DAILY_ASSESSMENT_LIMIT`; zero or less disables
   it, and an unparseable value falls back to 20 rather than uncapping spend.

   - Counted per **calendar day in `Africa/Kampala`** (Uganda and Kenya are both UTC+3), so
     "resets at midnight" is true for the user rather than for UTC.
   - Enforced when an assessment **starts**, never mid-assessment. Earlier turns are paid
     for either way, and refusing somebody halfway is both the costliest moment to stop and
     the least useful answer to give somebody standing in front of a carpenter.
   - Refused **before** the request to Claude, which is the entire point — a 429 issued
     after the vision call would cost the same as allowing it.
   - Counted inside the same transaction that inserts the session, behind
     `pg_advisory_xact_lock` on the user id. A limit two concurrent requests can exceed by
     racing is not a limit.
   - Deleted assessments still count: the money was spent.
   - `V7__quota_index.sql` adds `(user_id, created_at DESC)` for the count.
   - The phone distinguishes this from a transient 429 and says the allowance resets
     tomorrow rather than "in a moment", taking the number from the server so the two
     cannot drift.

## Note on egress

History is re-sent on every turn, so the same photos travel upstream several times per
assessment. Outbound to Anthropic runs roughly 3–5× inbound from phones — on the order of
100–150 GB/month at 300 assessments/day. Worth a glance if the instance sits behind anything
metered.
