#!/bin/bash
# Deploys a release to the server in one command: finds the migrations the server has not
# applied, uploads them, rehearses them against the live database in a transaction that
# rolls back, asks, snapshots the database, applies them, installs the release's jar, and
# checks the result.
#
#   tools/deploy.sh            # the nightly release (main at its last merge)
#   tools/deploy.sh v0.2.0     # a tagged release
#
# Needs the AWS CLI signed in (aws sso login --profile uchicago), the Session Manager
# plugin, and gh. Every remote step is one SSM command run as root on the box, whose
# shell is sh, not bash. Nothing changes on the server before the yes, and the script stops
# at the first failure after it.
#
# Why one script: the order matters and is easy to get wrong by hand. Migrations go before
# the jar, because new code expects new tables; the jar goes straight after, because some
# migrations (V21) leave the old code answering wrongly until it is replaced; and the
# rehearsal has to see every pending migration in one transaction, since each builds on the
# last.
set -euo pipefail

RELEASE="${1:-nightly}"
PROFILE="${AWS_PROFILE:-uchicago}"
REGION=us-east-1
INSTANCE=i-06c19c68c05b8a0df
REPO=gkilleen33/quality-verification-app
RUN_ID=$(date +%Y%m%d-%H%M%S)
STAGE=/tmp/kagua-deploy-$RUN_ID
ROOT=$(git -C "$(dirname "$0")" rev-parse --show-toplevel)

# Runs stdin as a shell script on the box and prints its output. $1 names the step in the
# SSM history. Exits the whole deploy if the step fails.
remote() {
    local step=$1 params id status out
    params=$(jq -n --arg script "$(cat)" '{commands: [$script]}')
    id=$(aws ssm send-command --profile "$PROFILE" --region "$REGION" \
        --instance-ids "$INSTANCE" --document-name AWS-RunShellScript \
        --comment "deploy $RUN_ID: $step" --parameters "$params" \
        --query Command.CommandId --output text)
    # A waiter would give up after 100 seconds; kagua-deploy alone can take a minute.
    for _ in $(seq 1 200); do
        status=$(aws ssm get-command-invocation --profile "$PROFILE" --region "$REGION" \
            --command-id "$id" --instance-id "$INSTANCE" --query Status --output text 2>/dev/null || echo Pending)
        case "$status" in Pending|InProgress|Delayed) sleep 3 ;; *) break ;; esac
    done
    # Output to stdout so callers can capture it; the box's stderr to ours.
    out=$(aws ssm get-command-invocation --profile "$PROFILE" --region "$REGION" \
        --command-id "$id" --instance-id "$INSTANCE" \
        --query '{o: StandardOutputContent, e: StandardErrorContent}' --output json)
    jq -r '.o | rtrimstr("\n")' <<<"$out"
    jq -r '.e' <<<"$out" | sed '/^$/d' >&2
    if [ "$status" != Success ]; then
        echo "deploy: '$step' ended $status on the server; stopping (command $id)" >&2
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
gh release view "$RELEASE" -R "$REPO" --json assets --jq '.assets[].name' | grep -qx kagua-server.jar || {
    echo "deploy: release $RELEASE has no kagua-server.jar" >&2
    exit 1
}
# nightly is rebuilt by CI a few minutes after each merge. Deploying it before then would
# install the previous merge's jar beside this merge's migrations.
if [ "$RELEASE" = nightly ] && [ "$SHA" != "$MAIN" ]; then
    echo "deploy: nightly is at ${SHA:0:7} but main is at ${MAIN:0:7}; wait for CI to publish" >&2
    exit 1
fi
echo "release $RELEASE = ${SHA:0:7}"

LOCAL=$(git -C "$ROOT" ls-tree --name-only "$SHA" server/db/migrations/ |
    sed 's|.*/||' | grep -E '^V[0-9]+__.*\.sql$' | sort -V)
APPLIED=$(remote "list applied migrations" <<'SH'
sudo -u postgres psql -d kagua -Atc 'select version from schema_migrations'
SH
)
PENDING=""
for f in $LOCAL; do
    grep -qx "${f%.sql}" <<<"$APPLIED" || PENDING="$PENDING $f"
done
for v in $APPLIED; do
    grep -qx "$v.sql" <<<"$LOCAL" || {
        echo "deploy: the server has applied $v, which $RELEASE does not contain; stopping" >&2
        exit 1
    }
done
echo "pending migrations:${PENDING:- none}"

if [ -n "$PENDING" ]; then
    echo "== upload"
    {
        echo "set -e; mkdir -p $STAGE"
        for f in $PENDING; do
            echo "echo '$(git -C "$ROOT" show "$SHA:server/db/migrations/$f" | base64 | tr -d '\n')' | base64 -d > $STAGE/$f"
        done
        echo "cd $STAGE && sha256sum$PENDING"
    } | remote "upload migrations" > /tmp/kagua-deploy-$RUN_ID.sums
    for f in $PENDING; do
        want=$(git -C "$ROOT" show "$SHA:server/db/migrations/$f" | shasum -a 256 | cut -d' ' -f1)
        grep -q "^$want  $f$" /tmp/kagua-deploy-$RUN_ID.sums || {
            echo "deploy: $f arrived with a different checksum; stopping" >&2
            exit 1
        }
    done
    rm -f /tmp/kagua-deploy-$RUN_ID.sums
    echo "checksums match"

    echo "== rehearsal (as kagua, rolled back)"
    remote "rehearse migrations" <<SH
set -e
{ echo 'SET ROLE kagua;'; echo 'BEGIN;'
  for f in $PENDING; do grep -v -x -e 'BEGIN;' -e 'COMMIT;' "$STAGE/\$f"; done
  echo "SELECT 'rehearsal passed' AS result;"; echo 'ROLLBACK;'
} | sudo -u postgres psql -d kagua -v ON_ERROR_STOP=1 -q
SH
fi

echo
echo "Ready to change the server:"
echo "  - snapshot the database to /var/backups/kagua/pre-deploy-$RUN_ID.dump"
[ -n "$PENDING" ] && echo "  - apply:$PENDING"
echo "  - install $RELEASE (${SHA:0:7}) and restart the service"
read -r -p "Go ahead? [y/N] " answer </dev/tty
[ "$answer" = y ] || { echo "Nothing changed on the server."; exit 0; }

echo "== snapshot"
remote "snapshot database" <<SH
set -e
install -d -m 0700 -o postgres -g postgres /var/backups/kagua
sudo -u postgres pg_dump -Fc -f /var/backups/kagua/pre-deploy-$RUN_ID.dump kagua
ls -lh /var/backups/kagua/pre-deploy-$RUN_ID.dump
SH

if [ -n "$PENDING" ]; then
    echo "== apply"
    remote "apply migrations" <<SH
set -e
cd $STAGE && install -m 0644$PENDING /opt/kagua/db/migrations/
bash /opt/kagua/db/apply.sh
SH
fi

echo "== install $RELEASE"
remote "install release" <<SH
kagua-deploy $RELEASE
SH

echo "== check"
remote "check" <<SH
set -e
cat /opt/kagua/VERSION
deep=\$(curl -fsS http://127.0.0.1:8080/healthz/deep)
echo "\$deep"
for f in $PENDING; do
    echo "\$deep" | grep -q "\${f%.sql}" || { echo "healthz/deep does not list \$f"; exit 1; }
done
echo "errors logged since the restart:"
journalctl -u kagua --since '3 min ago' -p err --no-pager | tail -20
rm -rf $STAGE
SH
echo "Deployed $RELEASE (${SHA:0:7}). Snapshot: /var/backups/kagua/pre-deploy-$RUN_ID.dump"
