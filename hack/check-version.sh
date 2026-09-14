#!/usr/bin/env bash
#
# The published tag is built from VERSION, so two releases shipping different code under the same
# version would be told apart only by a timestamp -- which says nothing about whether upgrading
# between them is safe.
#
# With no argument this only checks the shape. With a base ref it also insists that the version
# moved if anything that ships did.
set -euo pipefail

VERSION_FILE=VERSION
CHART=charts/mc-agents-mcp-server/Chart.yaml
# The catalogue ships: a bot checks its argument hashes against the copy this server holds. The
# chart ships too, since installing it is how the server gets run.
RELEASE_PATHS=(src/main/ catalog/ charts/ build.gradle.kts VERSION)

declared=$(tr -d '[:space:]' < "$VERSION_FILE")

if ! printf '%s' "$declared" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?$'; then
  echo "\"$declared\" is not a semantic version" >&2
  exit 1
fi

# The chart's image tag falls back to its appVersion, so a chart that fell behind VERSION installs an
# older server than the one it ships beside. It did: 0.8.0 against 0.43.0.
chart_version=$(sed -n 's/^version: *//p' "$CHART" | tr -d '"[:space:]')
chart_app_version=$(sed -n 's/^appVersion: *//p' "$CHART" | tr -d '"[:space:]')
if [ "$chart_version" != "$declared" ] || [ "$chart_app_version" != "$declared" ]; then
  echo "$CHART says version $chart_version and appVersion $chart_app_version; VERSION is $declared" >&2
  exit 1
fi

base=${1:-}

if [ -z "$base" ]; then
  echo "version $declared is a semantic version"
  exit 0
fi

if ! changed=$(git diff --name-only "$base" HEAD); then
  echo "cannot diff against $base. Check out with fetch-depth: 0 so there is history to compare." >&2
  exit 1
fi

shipped=$(printf '%s\n' "$changed" | grep -E "^($(IFS='|'; echo "${RELEASE_PATHS[*]}"))" || true)

if [ -z "$shipped" ]; then
  echo "version $declared is consistent; nothing that ships changed"
  exit 0
fi

if ! earlier=$(git show "$base:$VERSION_FILE" 2>/dev/null | tr -d '[:space:]'); then
  echo "cannot read $VERSION_FILE at $base" >&2
  exit 1
fi

# sort -V puts the lower version first, so the declared one has to be the last line and not equal.
if [ "$declared" = "$earlier" ] || [ "$(printf '%s\n%s\n' "$declared" "$earlier" | sort -V | tail -1)" != "$declared" ]; then
  count=$(printf '%s\n' "$shipped" | wc -l | tr -d ' ')
  {
    echo "$count file(s) that ship changed, but the version stayed at $declared (was $earlier)."
    echo "Raise it: major when an existing tool's arguments change, minor when a tool is added or"
    echo "answers differently, patch for a fix. Changed:"
    printf '%s\n' "$shipped" | head -8
  } >&2
  exit 1
fi

echo "version $declared was raised from $earlier"
