#!/usr/bin/env bash
#
# The notes for a GitHub Release: every commit since the previous release, then the catalogue
# version this build ships and the bot tags it was verified with.
#
# GitHub's generated notes list pull requests, and this repository is pushed to directly, so they
# were empty. The commit subjects are the changelog here -- the version check already insists that
# a version moves with the code, so what lies between two tags is what that version changed.
#
# The bot tags are the ones dev/cluster/mcpserver.yaml pins, which is the set the release was run
# against; the operator's docs/compatibility.md is where the whole matrix lives, one row per
# operator release, and this line is what that row is written from.
#
#     hack/release-notes.sh <version> [notes-file]
#
# The previous release is the newest v* tag reachable from HEAD other than this version's own,
# which a re-run of the publish job may already have placed on an earlier commit. With no earlier
# tag at all -- the first release -- every commit reachable from HEAD is listed.
set -euo pipefail

version=${1:?the version being released}
out=${2:-/dev/stdout}

previous=$(git describe --tags --abbrev=0 --match 'v*' --exclude "v$version" HEAD 2>/dev/null \
  || git tag --list 'v*' --sort=-v:refname | grep -vx "v$version" | head -1 || true)

if [ -n "$previous" ]; then
  range="$previous..HEAD"
  heading="Changes since $previous"
else
  range=HEAD
  heading="Changes"
fi

catalog=$(sed -n 's/^ *"catalogVersion": *"\([^"]*\)".*/\1/p' catalog/catalog.json | head -1)
fabric=$(awk '/^  fabric:/ { found = 1 } found && /tag:/ { print $2; exit }' dev/cluster/mcpserver.yaml)
azalea=$(awk '/^  azalea:/ { found = 1 } found && /tag:/ { print $2; exit }' dev/cluster/mcpserver.yaml)

test -n "$catalog" || { echo "catalog/catalog.json has no catalogVersion" >&2; exit 1; }
test -n "$fabric" && test -n "$azalea" || { echo "dev/cluster/mcpserver.yaml pins no bot tags" >&2; exit 1; }

{
  echo "## $heading"
  echo
  git log "$range" --format='- %s'
  echo
  echo "Catalogue $catalog. Verified with bot-fabric $fabric and bot-azalea $azalea; the full table is" \
    "[operator/docs/compatibility.md](https://github.com/mc-agents/operator/blob/main/docs/compatibility.md)."
} > "$out"
