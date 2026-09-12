#!/usr/bin/env bash
# Put the fixture datapack into a Paper server and load it.
#
#     ./dev/fixture.sh <container>            install and load
#     ./dev/fixture.sh <container> hud        send the action bar, title, sound and particle
#     ./dev/fixture.sh <container> dialog     open the dialog for everyone
#
# What it installs is dev/fixture: a scoreboard, a boss bar, a two-sided sign, a hologram, a named
# cow, a chest with custom names and lore, a furnace, and a patch of diamond blocks. The reading
# tools have nothing to read in an empty flat world, so the comparison suite agrees perfectly
# about a world neither kind of bot can see.
set -euo pipefail

container=${1:?usage: fixture.sh <paper container> [hud|dialog]}
action=${2:-install}
here=$(cd "$(dirname "$0")" && pwd)

rcon() {
    docker exec "$container" rcon-cli "$@"
}

case "$action" in
install)
    # Removed first: docker cp into a directory that exists nests the copy one level deeper, and
    # the server then loads the old pack while the new files sit unreferenced beside it.
    docker exec "$container" rm -rf /data/world/datapacks/mcagents
    docker cp "$here/fixture" "$container:/data/world/datapacks/mcagents"

    # A datapack list is read when the world loads, so a reload is what makes a new one exist.
    rcon reload > /dev/null
    sleep 5
    rcon "function mcagents:setup"
    rcon "datapack list enabled"
    ;;
hud | dialog)
    rcon "function mcagents:$action"
    ;;
*)
    echo "unknown action $action" >&2
    exit 2
    ;;
esac
