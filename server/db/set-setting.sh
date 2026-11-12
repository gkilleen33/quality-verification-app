#!/bin/bash
# Changes one app setting (V27's app_settings) and records who changed it and why.
#
#   bash /opt/kagua/db/set-setting.sh fundi terms_gate on "every active fundi has agreed"
#   bash /opt/kagua/db/set-setting.sh fundi terms_gate off "rollback: phones refused"
#
# The way to change a setting until the portal's settings page exists (sign-up plan v3,
# row 4a). Run on the server over SSM, each command approved by Nathan.
#
# One transaction: it sets kagua.changed_by and kagua.reason for itself, then writes the
# value. The table's trigger stamps the row and writes the app_settings_history row from
# those two values, and the table's CHECKs refuse a key or value outside the vocabulary,
# so this script repeats neither rule. A setting that has no row yet (Kagua's
# terms_version, say) is inserted.
#
# It connects as postgres over peer authentication and then acts as the kagua role, as the
# deploy rehearsal does, so it reads no password and holds no secret. Installed by hand
# into /opt/kagua/db/ beside apply.sh (docs/phase-2-backend.md: that folder is populated
# by hand), with one SSM command, after the deploy that applies V27.
set -euo pipefail

if [ "$#" -ne 4 ]; then
    echo "usage: set-setting.sh <buyer|fundi> <key> <value> \"<reason>\"" >&2
    exit 2
fi

AUDIENCE=$1
KEY=$2
VALUE=$3
REASON=$4
# Who, as the history will show it. SSM runs this as root, so the login name says nothing.
CHANGED_BY="${KAGUA_CHANGED_BY:-nathan (ssm)}"

case "$AUDIENCE" in
    buyer|fundi) ;;
    *) echo "set-setting: the app is buyer (Kagua) or fundi (Fundi Bora), not '$AUDIENCE'" >&2; exit 2 ;;
esac
[ -n "${REASON// /}" ] || { echo "set-setting: give a reason; it goes in the history" >&2; exit 2; }

# The values travel as psql variables, quoted by psql itself (:'name'), never pasted into
# the SQL text.
sudo -u postgres psql -d kagua -v ON_ERROR_STOP=1 -q -At \
    -v audience="$AUDIENCE" -v key="$KEY" -v value="$VALUE" \
    -v reason="$REASON" -v changed_by="$CHANGED_BY" <<'SQL'
SET ROLE kagua;
BEGIN;
SELECT set_config('kagua.changed_by', :'changed_by', true) \g /dev/null
SELECT set_config('kagua.reason', :'reason', true) \g /dev/null
INSERT INTO app_settings (audience, key, value)
    VALUES (:'audience', :'key', :'value')
    ON CONFLICT (audience, key) DO UPDATE SET value = EXCLUDED.value;
SELECT 'now: ' || audience || ' ' || key || ' = ' || value
       || ' (by ' || updated_by || ', ' || updated_at || ')'
  FROM app_settings WHERE audience = :'audience' AND key = :'key';
COMMIT;
SQL

echo "The server reads the new value within 10 seconds."
