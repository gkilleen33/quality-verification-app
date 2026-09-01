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

## Admin portal (scoped 1 Sep 2026, built after the proxy)

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

## Decisions still ours

1. ~~**Do photos go to the server at all?**~~ Settled: yes. Rebuilding history server-side
   requires it, and the portal needs them for accuracy review. Stored once, deduped by
   `sha256`.
2. ~~**Photo retention**~~ — follows the session: 7 days after a customer deletes an
   assessment, 30 days after they delete the account. **Not yet settled** for assessments
   nobody deletes, which currently means indefinitely.
3. ~~**Region.**~~ Settled: us-east-1.
4. ~~**Auth identity.**~~ Settled: invite codes for the pilot. SMS later if needed.
5. **Per-user quota.** Needed before launch: after Phase 2 every request is billed to us
   rather than to the tester.

## Note on egress

History is re-sent on every turn, so the same photos travel upstream several times per
assessment. Outbound to Anthropic runs roughly 3–5× inbound from phones — on the order of
100–150 GB/month at 300 assessments/day. Worth a glance if the instance sits behind anything
metered.
