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
| `GET /assessments` | assessments, newest last. Filters: `user`, `item`, `testers=1`, `updated_since` |
| `GET /assessments/{id}` | one assessment with its messages, photo hashes in order, and the evaluator's critique if there is one |
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

## Joining it together

- `assessments[].user_id` → `users[].id`
- `tester_feedback[].session_id` → `assessments[].id` (one critique per assessment, at most)
- `messages[].photos[]` → `/photos/{sha}`

## Two things that will bite an analysis if nobody mentions them

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
