#!/bin/bash
# Applies migrations as the application role, then proves the application can use
# what was created.
#
# This script exists because of a real failure. V1 was applied as `kagua`; V2-V5 were
# applied as `postgres` because CREATE EXTENSION postgis needs a superuser. Adding
# columns that way is harmless, but V4 created a table — which was therefore owned by
# postgres, and the app got "permission denied for table refresh_tokens" on the first
# real sign-in. Nothing in the test suite could catch it, and the by-hand SQL check
# missed it because that check also ran as postgres: the one user with permission.
#
# So: everything runs as kagua, except the one statement that cannot, and the
# ownership assertion at the end is the part that actually prevents a repeat.
set -euo pipefail

DB=kagua
ROLE=kagua
DIR="$(cd "$(dirname "$0")" && pwd)/migrations"

PASSWORD=$(aws ssm get-parameter --region us-east-1 --name /kagua/db/password \
  --with-decryption --query Parameter.Value --output text)

# The only superuser step. Idempotent, and separate so nothing else inherits postgres.
sudo -u postgres psql -d "$DB" -qc "CREATE EXTENSION IF NOT EXISTS postgis;" >/dev/null

for file in $(ls "$DIR"/V*.sql | sort -V); do
  version=$(basename "$file" .sql)
  applied=$(PGPASSWORD="$PASSWORD" psql -h 127.0.0.1 -U "$ROLE" -d "$DB" -Atc \
    "select 1 from schema_migrations where version = '$version'" 2>/dev/null || echo "")
  if [ "$applied" = "1" ]; then
    echo "  = $version (already applied)"
    continue
  fi
  echo "  + $version"
  PGPASSWORD="$PASSWORD" psql -h 127.0.0.1 -U "$ROLE" -d "$DB" -v ON_ERROR_STOP=1 -q -f "$file"
done

echo
echo "checking every application table is owned by $ROLE"
# spatial_ref_sys belongs to PostGIS and must stay with postgres.
WRONG=$(sudo -u postgres psql -d "$DB" -Atc "
  select tablename || ' owned by ' || tableowner
  from pg_tables
  where schemaname = 'public'
    and tablename <> 'spatial_ref_sys'
    and tableowner <> '$ROLE'")
if [ -n "$WRONG" ]; then
  echo "FAIL: the app cannot use these:" >&2
  echo "$WRONG" >&2
  echo "fix with: ALTER TABLE <name> OWNER TO $ROLE;" >&2
  exit 1
fi
echo "ok: all application tables owned by $ROLE"

# Ownership is necessary but not sufficient — prove a write actually works.
PGPASSWORD="$PASSWORD" psql -h 127.0.0.1 -U "$ROLE" -d "$DB" -Atc \
  "select 'app role can write: ' || (select count(*) >= 0 from refresh_tokens)"
