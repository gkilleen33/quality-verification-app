#!/bin/bash
# Prints the app-versions.conf that a deploy of <rev> installs on the server.
#
#   tools/app-versions.sh <repo-root> <rev> [installed-conf]
#
# Each app's LATEST is the versionCode in its build.gradle.kts at <rev>, so a phone older than
# the release is told "A new version is available" as soon as the new jar starts. Every
# MINIMUM, and any DOWNLOAD_URL, is kept from [installed-conf], the file the server has now:
# a minimum blocks phones, and raising one is Nathan's decision, made by editing the
# installed file, never by a deploy. A value the installed file lacks comes from the
# repository's copy at <rev>, and failing that is 1, the server's own default.
#
# tools/deploy.sh runs this before it asks, shows the values, and installs the result after
# the migrations. Run it by hand to see what a deploy of a tag would write; without an
# installed file it uses the repository's minimums:
#
#   tools/app-versions.sh "$(git rev-parse --show-toplevel)" v0.7.0
#
# Reads only. If any app's versionCode cannot be read it prints nothing on stdout and exits
# 1: a guessed number would tell every phone the wrong thing.
set -euo pipefail

[ $# -ge 2 ] || { echo "usage: tools/app-versions.sh <repo-root> <rev> [installed-conf]" >&2; exit 2; }
ROOT=$1
REV=$2
INSTALLED=${3:-}
CONF=server/deploy/systemd/kagua.service.d/app-versions.conf

REPO_COPY=$(mktemp)
trap 'rm -f "$REPO_COPY"' EXIT
# Absent before v0.5.0; an empty copy just means every minimum falls back to 1.
git -C "$ROOT" show "$REV:$CONF" > "$REPO_COPY" 2>/dev/null || : > "$REPO_COPY"

# The one "versionCode = N" line in a build file at <rev>. Anything else is a build file this
# script does not understand, so it stops rather than guess.
version_code() {
    local codes
    codes=$(git -C "$ROOT" show "$REV:$1" |
        sed -n 's/^[[:space:]]*versionCode[[:space:]]*=[[:space:]]*\([0-9][0-9]*\)[[:space:]]*$/\1/p')
    if [ -z "$codes" ] || [ "$(printf '%s\n' "$codes" | wc -l | tr -d ' ')" != 1 ]; then
        echo "app-versions: $1 at $REV does not have exactly one 'versionCode = N' line" >&2
        return 1
    fi
    echo "$codes"
}

# The value of Environment="<name>=<value>" in file $1, or nothing. The last one wins, as it
# does for systemd.
setting() {
    [ -n "$1" ] && [ -f "$1" ] || return 0
    sed -n "s/^Environment=\"$2=\\(.*\\)\"[[:space:]]*\$/\\1/p" "$1" | tail -n 1
}

# A positive whole number, or nothing.
number() {
    case "$1" in ''|*[!0-9]*) ;; *) [ "$1" -gt 0 ] && echo "$1" ;; esac
    return 0
}

# Server name, build file. PesaLog numbers its own versions from 1, separately from the
# other two, so it gets its own line like the others rather than sharing theirs.
APPS="KAGUA:app/build.gradle.kts FUNDI:fundi/build.gradle.kts TRACKER:tracker/build.gradle.kts"

OUT="# Installed by tools/deploy.sh for $REV. LATEST is each app's versionCode at that commit;
# MINIMUM and DOWNLOAD_URL were kept from the file this one replaced. What each value does:
# $CONF in the repository.

[Service]"
for entry in $APPS; do
    app=${entry%%:*}
    latest=$(version_code "${entry#*:}")
    was=$(number "$(setting "$INSTALLED" "KAGUA_APP_LATEST_$app")")
    if [ -n "$was" ] && [ "$latest" -lt "$was" ]; then
        echo "app-versions: warning: $app latest goes down from $was to $latest; is $REV an older release?" >&2
    fi
    minimum=$(number "$(setting "$INSTALLED" "KAGUA_APP_MINIMUM_$app")")
    [ -n "$minimum" ] || minimum=$(number "$(setting "$REPO_COPY" "KAGUA_APP_MINIMUM_$app")")
    OUT="$OUT
Environment=\"KAGUA_APP_LATEST_$app=$latest\"
Environment=\"KAGUA_APP_MINIMUM_$app=${minimum:-1}\""
    url=$(setting "$INSTALLED" "KAGUA_APP_DOWNLOAD_URL_$app")
    [ -n "$url" ] || url=$(setting "$REPO_COPY" "KAGUA_APP_DOWNLOAD_URL_$app")
    if [ -n "$url" ]; then
        OUT="$OUT
Environment=\"KAGUA_APP_DOWNLOAD_URL_$app=$url\""
    fi
done
printf '%s\n' "$OUT"
