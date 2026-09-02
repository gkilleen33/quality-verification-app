# Reverting to full deletion

Kagua currently **anonymises** an account when its owner deletes it, and keeps the
assessments. This document is how to go back to erasing them, and what it costs at each
point. Written on 2 Sep 2026, the day the change was made, while the reasons were still
in front of me.

## Why it is this way

No IRB submission had been made when this was decided. The choice was between two orders:

1. Promise full deletion now, then retract it once the pilot data turns out to matter.
2. Say from the start that data may be kept indefinitely and that deleting an account
   removes the identifiers from it.

The second is the honest one. Withdrawing a deletion right after people have relied on it
is worse than never having offered it, and it is the version that is hard to explain to an
IRB afterwards. So the app says plainly, at registration and again at deletion, that the
assessments are kept.

**This is not the same as anonymous data.** The photographs show workshop signage and
premises; free text may name a shop or a person. Clearing profile columns removes what we
hold *about* the account and honestly nothing more. Everything user-facing says so and asks
people not to put personal details in the conversation. Do not let that wording drift into
claiming the record is anonymous — under GDPR-style regimes that distinction is the whole
question, and pseudonymised data is still personal data with every right attached.

## What happens today

`DELETE /v1/account` calls `AuthStore.markAccountDeleted`, which runs `anonymise_user(uuid)`
from `V11__anonymise_on_delete.sql`. In one transaction it:

- nulls `phone`, `password_hash`, `password_set_at` — the account can no longer be signed
  into or found by number
- nulls `display_name`, `business_name`, `label`
- nulls `business_location`, `business_location_accuracy_m`, `business_location_at`
- nulls `invite_code`, because the code's label often names the person it was handed to
- sets `deleted_at` and `anonymised_at`
- deletes every row in `refresh_tokens` for that user

The `users` row survives so `sessions.user_id` still resolves. Its `id` — a random uuid
never derived from anything about the person — is the anonymous identifier the research
uses. Nothing is re-keyed, and there is **no mapping table anywhere**: with one, this would
be pseudonymisation dressed up as anonymisation.

It is immediate and irreversible. There is no grace period, which is the price of the
promise being unambiguous, and the confirmation dialog says as much.

`purge_deleted_accounts(interval)` is **still defined** and no longer called. That is
deliberate — it makes the revert a one-line change rather than a rewrite.

## Reverting

### Step 1 — decide what to do about accounts already anonymised

This is the part that cannot be undone, so decide it first.

An account anonymised under the current policy has already lost its phone number and name.
Reverting does not bring those back, and it cannot: there is no mapping. So a revert leaves
you with two populations — accounts closed before the revert, which are anonymised and
retained, and accounts closed after it, which are erased. Anybody analysing the data needs
to know that, and it should be recorded wherever the dataset is described.

If the revert is happening because an IRB or a regulator required it, they will most likely
want the already-anonymised rows **erased too**. Doing that is a one-off:

```sql
-- Everything belonging to an account that was closed. Cascades to messages and
-- attachments; usage_events keep their rows with user_id set null.
DELETE FROM users WHERE anonymised_at IS NOT NULL;
```

Then run the blob sweep so the photographs actually go — deleting rows does not touch the
files, which is the whole point of `BlobSweeper`. It runs daily in the server process; to
force it, restart the service and wait five minutes, or call it from a one-off main.

### Step 2 — put erasure back in the nightly job

In a new migration:

```sql
CREATE OR REPLACE FUNCTION purge_expired()
RETURNS text
LANGUAGE sql
AS $$
    SELECT 'sessions=' || purge_deleted_sessions('7 days'::interval)
        || ' accounts=' || purge_deleted_accounts('30 days'::interval)
        || ' tokens='   || purge_expired_tokens('30 days'::interval)
        || ' devices='  || purge_expired_trusted_devices();
$$;
```

That is the only change needed to the job. `purge_deleted_accounts` was left in place for
exactly this.

### Step 3 — stop anonymising on delete

In `AuthStore.markAccountDeleted`, replace the `anonymise_user` call with what it was
before: set `deleted_at`, then `revoke_refresh_chain`. The commit that introduced this
change has the original in its diff.

The two must not both run. Anonymising *and* purging would work, but it would destroy the
identifiers thirty days before the row goes, so anybody who asked "what do you still hold
about me" during that window would get a misleading answer.

### Step 4 — the wording, which is the part most likely to be forgotten

`shared/src/main/kotlin/com/qualityverifier/text/AuthLabels.kt`, in **both** languages:

- `deleteAccountBlurb` and `deleteAccountConfirmBody` — currently say the identifiers go
  and the assessments stay under a random number. They would need to state the deletion
  window again.
- `dataRetentionNotice` — shown at registration. Currently says data may be kept
  indefinitely. It would need to say the opposite, or be removed.

`AuthLabelsTest` will fail until these are changed, deliberately: `deleting an account does
not promise the assessments are erased` asserts the wording does **not** mention a 30-day
window, and `the retention notice admits what anonymising cannot reach` asserts the notice
says "indefinitely". Those tests are the tripwire that stops the code and the promise
drifting apart, so update them rather than deleting them.

Anything reverted here needs a fresh Swahili review — see issue #20. The Swahili in these
strings is unreviewed as it is.

### Step 5 — tell the people who were told otherwise

Anybody who registered under the current wording agreed to indefinite retention. Reverting
to erasure is a change in their favour, so it needs no consent, but it does need saying:
the app told them their assessments would be kept, and after a revert that is no longer
true.

## What is not affected

Deleting a **single report** has never been changed by any of this. `client_deleted_at` is
set, and `purge_deleted_sessions('7 days')` erases it for good seven days later, photographs
included via the blob sweep. The app still promises exactly that and it is still true.

Worth noticing, and deciding on deliberately rather than by accident: a customer who wants
everything gone can delete each report individually and get a real erasure, while deleting
their whole account keeps the assessments anonymised. That asymmetry is defensible —
deleting one report is a targeted "not this", closing an account is "I am leaving" — but it
is also a gap somebody could walk through, and it was not part of the decision that
produced this document.
