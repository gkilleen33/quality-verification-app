#!/bin/bash
# Deploys a release to the server in one command: finds the migrations the server has not
# applied, uploads them and the release's jar, rehearses the migrations against the live
# database in a transaction that rolls back, asks, snapshots the database and the running
# jar, applies the migrations, installs the jar it uploaded, and checks the result.
#
#   tools/deploy.sh            # the nightly release (main at its last merge)
#   tools/deploy.sh v0.2.0     # a tagged release; prefer these once they exist
#
# Needs the AWS CLI signed in (aws sso login --profile uchicago), the Session Manager
# plugin, gh, jq, and curl. Every remote step is one SSM command run as root on the box,
# whose shell is sh, not bash. Nothing changes on the server before the yes, except a
# staging folder in /tmp; after it, the script stops at the first failure and says what
# was done and how to recover.
#
# Why one script: the order matters and is easy to get wrong by hand. Migrations go before
# the jar, because new code expects new tables; the jar goes straight after, because some
# migrations (V21) leave the old code answering wrongly until it is replaced; the rehearsal
# has to see every pending migration in one transaction, since each builds on the last;
# and the jar is uploaded before the yes and checked against the release, so the one
# installed is the one rehearsed beside, not whatever the tag points at by then.
set -euo pipefail

RELEASE="${1:-nightly}"
PROFILE="${AWS_PROFILE:-uchicago}"
REGION=us-east-1
INSTANCE=i-06c19c68c05b8a0df
REPO=gkilleen33/quality-verification-app
RUN_ID=$(date +%Y%m%d-%H%M%S)
STAGE=/tmp/kagua-deploy-$RUN_ID
BACKUPS=/var/backups/kagua
ROOT=$(git -C "$(dirname "$0")" rev-parse --show-toplevel)
LOCAL_TMP=$(mktemp -d)
DONE="nothing on the server"

# The release name is interpolated into scripts that run as root on the box.
[[ "$RELEASE" =~ ^[A-Za-z0-9._-]+$ ]] || { echo "deploy: '$RELEASE' is not a release name" >&2; exit 1; }

# On any exit before the end, say how far it got and what to do next.
finish() {
    local code=$?
    rm -rf "$LOCAL_TMP"
    [ "$code" -eq 0 ] && return
    echo >&2
    echo "deploy: stopped. Completed: $DONE." >&2
    case "$DONE" in
        *"migrations applied"*)
            echo "  The new schema is live and the old jar may still be running." >&2
            echo "  Re-run the install:   tools/deploy.sh $RELEASE   (nothing pending, so it goes straight to the jar)" >&2
            echo "  Or go back:           restore $BACKUPS/pre-deploy-$RUN_ID.dump and $BACKUPS/kagua-pre-deploy-$RUN_ID.jar" >&2 ;;
        *"snapshot taken"*)
            echo "  Nothing was applied. The snapshot is $BACKUPS/pre-deploy-$RUN_ID.dump." >&2 ;;
    esac
}
trap finish EXIT

# Runs stdin as a shell script on the box and prints its output. $1 names the step in the
# SSM history. Exits the whole deploy if the step fails.
remote() {
    local step=$1 params id status out err
    params=$(jq -n --arg script "$(cat)" '{commands: [$script]}')
    id=$(aws ssm send-command --profile "$PROFILE" --region "$REGION" \
        --instance-ids "$INSTANCE" --document-name AWS-RunShellScript \
        --comment "deploy $RUN_ID: $step" --parameters "$params" \
        --query Command.CommandId --output text) || exit 1
    # Inside $(...) bash 3.2 does not stop on errors, so a failed send must stop here.
    [ -n "$id" ] || { echo "deploy: could not send '$step' to the server" >&2; exit 1; }
    status=Pending
    # Up to 15 minutes; a waiter would give up after 100 seconds.
    for _ in $(seq 1 300); do
        if out=$(aws ssm get-command-invocation --profile "$PROFILE" --region "$REGION" \
            --command-id "$id" --instance-id "$INSTANCE" --query Status --output text 2>&1); then
            status=$out
        else
            # Only "not registered yet" is worth waiting through.
            case "$out" in *InvocationDoesNotExist*) ;; *) echo "deploy: $out" >&2; exit 1 ;; esac
        fi
        case "$status" in Pending|InProgress|Delayed) sleep 3 ;; *) break ;; esac
    done
    out=$(aws ssm get-command-invocation --profile "$PROFILE" --region "$REGION" \
        --command-id "$id" --instance-id "$INSTANCE" \
        --query '{o: StandardOutputContent, e: StandardErrorContent}' --output json) || exit 1
    jq -r '.o | rtrimstr("\n")' <<<"$out"
    err=$(jq -r '.e' <<<"$out")
    [ -z "$err" ] || echo "$err" >&2
    if [ "$status" != Success ]; then
        echo "deploy: '$step' ended $status on the server (command $id)" >&2
        case "$status" in Pending|InProgress|Delayed)
            echo "  It may still be running there; check before retrying." >&2 ;;
        esac
        exit 1
    fi
}

echo "== preflight"
aws sts get-caller-identity --profile "$PROFILE" --query Arn --output text >/dev/null 2>&1 || {
    echo "deploy: not signed in to AWS; run: aws sso login --profile $PROFILE" >&2
    exit 1
}
git -C "$ROOT" fetch -q --force origin main "+refs/tags/*:refs/tags/*"
SHA=$(git -C "$ROOT" rev-parse "refs/tags/$RELEASE^{commit}")
MAIN=$(git -C "$ROOT" rev-parse origin/main)
# nightly is rebuilt by CI a few minutes after each merge. Deploying it before then would
# install the previous merge's jar beside this merge's migrations.
if [ "$RELEASE" = nightly ] && [ "$SHA" != "$MAIN" ]; then
    echo "deploy: nightly is at ${SHA:0:7} but main is at ${MAIN:0:7}; wait for CI to publish" >&2
    exit 1
fi
JAR_URL="https://github.com/$REPO/releases/download/$RELEASE/kagua-server.jar"
curl -fsSL --retry 3 -o "$LOCAL_TMP/kagua.jar" "$JAR_URL" || {
    echo "deploy: release $RELEASE has no kagua-server.jar" >&2
    exit 1
}
JAR_SUM=$(shasum -a 256 "$LOCAL_TMP/kagua.jar" | cut -d' ' -f1)
echo "release $RELEASE = ${SHA:0:7}, jar ${JAR_SUM:0:12}"

LOCAL=$(git -C "$ROOT" ls-tree --name-only "$SHA" server/db/migrations/ |
    sed 's|.*/||' | grep -E '^V[0-9]+__.*\.sql$' | sort -V)
for f in $LOCAL; do
    [[ "$f" =~ ^V[0-9]+__[a-z0-9_]+\.sql$ ]] || { echo "deploy: unexpected migration name $f" >&2; exit 1; }
done
APPLIED=$(remote "list applied migrations" <<'SH'
sudo -u postgres psql -d kagua -Atc 'select version from schema_migrations'
SH
)
[ -n "$APPLIED" ] || { echo "deploy: the server reported no applied migrations; stopping" >&2; exit 1; }
PENDING=""
for f in $LOCAL; do
    grep -qxF "${f%.sql}" <<<"$APPLIED" || PENDING="$PENDING $f"
done
for v in $APPLIED; do
    grep -qxF "$v.sql" <<<"$LOCAL" || {
        echo "deploy: the server has applied $v, which $RELEASE does not contain; stopping" >&2
        exit 1
    }
done
echo "pending migrations:${PENDING:- none}"

# The rehearsal strips each file's own BEGIN; and COMMIT; to run them all in one
# transaction. A transaction line written any other way would survive the strip and
# commit for real before the prompt, so each file must have exactly one of each, and no
# other line that ends a transaction.
for f in $PENDING; do
    body=$(git -C "$ROOT" show "$SHA:server/db/migrations/$f")
    case "$body" in *$'\r'*) echo "deploy: $f has Windows line endings" >&2; exit 1 ;; esac
    [ "$(grep -cx 'BEGIN;' <<<"$body")" = 1 ] && [ "$(grep -cx 'COMMIT;' <<<"$body")" = 1 ] || {
        echo "deploy: $f must have exactly one 'BEGIN;' line and one 'COMMIT;' line" >&2; exit 1
    }
    if grep -v -x -e 'BEGIN;' -e 'COMMIT;' <<<"$body" | grep -qiE '^[[:space:]]*(commit|rollback|end)[[:space:]]*(transaction|work)?[[:space:]]*;[[:space:]]*(--.*)?$'; then
        # PL/pgSQL bodies end blocks with END; on its own line, which is not a transaction
        # end; allow those only inside $$ ... $$.
        if awk '/\$\$/{n++} n%2==0 && tolower($0) ~ /^[[:space:]]*(commit|rollback|end)[[:space:]]*(transaction|work)?[[:space:]]*;/{bad=1} END{exit !bad}' <<<"$(grep -v -x -e 'BEGIN;' -e 'COMMIT;' <<<"$body")"; then
            echo "deploy: $f ends a transaction outside its final COMMIT;" >&2; exit 1
        fi
    fi
done

echo "== upload"
SIZE=0
for f in $PENDING; do
    SIZE=$((SIZE + $(git -C "$ROOT" show "$SHA:server/db/migrations/$f" | base64 | wc -c)))
done
# SSM refuses a command document much over 64 KB. A long backlog needs several deploys.
[ "$SIZE" -lt 50000 ] || { echo "deploy: pending migrations are too large to send at once ($SIZE bytes in base64)" >&2; exit 1; }
{
    echo "set -e; mkdir -p $STAGE"
    for f in $PENDING; do
        echo "echo '$(git -C "$ROOT" show "$SHA:server/db/migrations/$f" | base64 | tr -d '\n')' | base64 -d > $STAGE/$f"
    done
    echo "curl -fsSL --retry 3 -o $STAGE/kagua.jar '$JAR_URL'"
    echo "cd $STAGE && sha256sum kagua.jar$PENDING"
    # Anything in the migrations folder that is neither applied nor pending would be
    # applied by apply.sh too.
    echo "ls /opt/kagua/db/migrations | sed 's/^/present: /'"
} | remote "upload" > "$LOCAL_TMP/upload.txt"
grep -q "^$JAR_SUM  kagua.jar$" "$LOCAL_TMP/upload.txt" || {
    echo "deploy: the jar the server downloaded differs from the release's (was it rebuilt?); stopping" >&2
    exit 1
}
for f in $PENDING; do
    want=$(git -C "$ROOT" show "$SHA:server/db/migrations/$f" | shasum -a 256 | cut -d' ' -f1)
    grep -q "^$want  $f$" "$LOCAL_TMP/upload.txt" || {
        echo "deploy: $f arrived with a different checksum; stopping" >&2
        exit 1
    }
done
for p in $(sed -n 's/^present: //p' "$LOCAL_TMP/upload.txt"); do
    grep -qxF "${p%.sql}" <<<"$APPLIED" || {
        echo "deploy: /opt/kagua/db/migrations holds $p, which the server has not applied; remove it first" >&2
        exit 1
    }
done
echo "checksums match"

if [ -n "$PENDING" ]; then
    echo "== rehearsal (as kagua, rolled back)"
    REHEARSAL=$(remote "rehearse migrations" <<SH
set -e
{ echo 'SET ROLE kagua;'
  echo "SET lock_timeout = '5s';"
  echo 'BEGIN;'
  for f in $PENDING; do grep -v -x -e 'BEGIN;' -e 'COMMIT;' "$STAGE/\$f"; done
  echo "SELECT 'rehearsal passed' AS result;"; echo 'ROLLBACK;'
} > $STAGE/rehearsal.sql
sudo -u postgres psql -d kagua -v ON_ERROR_STOP=1 -q -f $STAGE/rehearsal.sql
SH
)
    echo "$REHEARSAL"
    # sh has no pipefail, so require the row the last statement prints.
    grep -q 'rehearsal passed' <<<"$REHEARSAL" || { echo "deploy: the rehearsal did not finish; stopping" >&2; exit 1; }
fi

echo
echo "Ready to change the server:"
echo "  - snapshot the database and the running jar to $BACKUPS/"
[ -n "$PENDING" ] && echo "  - apply:$PENDING"
echo "  - install $RELEASE (${SHA:0:7}) and restart the service"
read -r -p "Go ahead? [y/N] " answer </dev/tty
[ "$answer" = y ] || { echo "Nothing changed on the server."; exit 0; }

echo "== snapshot"
remote "snapshot" <<SH
set -e
install -d -m 0700 -o postgres -g postgres $BACKUPS
df -h $BACKUPS | tail -1
sudo -u postgres pg_dump -Fc -f $BACKUPS/pre-deploy-$RUN_ID.dump kagua
cp /opt/kagua/kagua.jar $BACKUPS/kagua-pre-deploy-$RUN_ID.jar
ls -lh $BACKUPS/pre-deploy-$RUN_ID.dump $BACKUPS/kagua-pre-deploy-$RUN_ID.jar
SH
DONE="snapshot taken"

if [ -n "$PENDING" ]; then
    echo "== apply"
    remote "apply migrations" <<SH
set -e
cd $STAGE && install -m 0644$PENDING /opt/kagua/db/migrations/
bash /opt/kagua/db/apply.sh
SH
    DONE="snapshot taken, migrations applied"
fi

echo "== install $RELEASE"
# What kagua-deploy does, but from the jar uploaded and checked above rather than a fresh
# download, and with the commit in VERSION so the health check reports it.
remote "install jar" <<SH
set -e
install -o kagua -g kagua -m 0640 $STAGE/kagua.jar /opt/kagua/kagua.jar
echo "$RELEASE@${SHA:0:7}" > /opt/kagua/VERSION
chown kagua:kagua /opt/kagua/VERSION
systemctl restart kagua
i=0
until curl -fsS --max-time 5 http://127.0.0.1:8080/healthz >/dev/null 2>&1; do
    if ! systemctl is-active --quiet kagua; then
        echo "service died during startup:"; journalctl -u kagua -n 30 --no-pager; exit 1
    fi
    i=\$((i + 1))
    if [ \$i -ge 30 ]; then
        echo "not answering /healthz after 60s:"; journalctl -u kagua -n 30 --no-pager; exit 1
    fi
    sleep 2
done
echo "kagua is up"
SH
DONE="$DONE, jar installed"

echo "== check"
remote "check" <<SH
set -e
deep=\$(curl -fsS http://127.0.0.1:8080/healthz/deep)
echo "\$deep"
echo "\$deep" | grep -q "$RELEASE@${SHA:0:7}" || { echo "healthz/deep does not report $RELEASE@${SHA:0:7}"; exit 1; }
for f in $PENDING; do
    echo "\$deep" | grep -q "\${f%.sql}" || { echo "healthz/deep does not list \$f"; exit 1; }
done
echo "errors logged since the restart:"
journalctl -u kagua --since '3 min ago' -p err --no-pager | tail -20
rm -rf $STAGE
SH
DONE="$DONE, checked"
echo "Deployed $RELEASE (${SHA:0:7})."
echo "Backups: $BACKUPS/pre-deploy-$RUN_ID.dump and $BACKUPS/kagua-pre-deploy-$RUN_ID.jar"
