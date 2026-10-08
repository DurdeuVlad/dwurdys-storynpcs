#!/usr/bin/env bash
# Capture one screenshot per player panel: open via command, verify screen type, screenshot, close.
# Usage: ./panel_capture.sh <client> <outdir>
# Requires the mct CLI on PATH; if it isn't installed globally, point MCT at a
# local mc-pilot build instead: MCT="node <mc-pilot>/cli/bin/mct" ./panel_capture.sh ...
set -u
MCT="${MCT:-mct}"
CLIENT="${1:-beta-01}"
OUT="${2:-docs/beta/screens/raw}"

declare -A PANELS=(
  [05-panel-quests]="storynpcs panel quests"
  [06-panel-factions]="storynpcs panel factions"
  [07-panel-mail]="storynpcs panel mail"
  [08-panel-transport]="storynpcs panel transport"
  [09-panel-companions]="storynpcs panel companions"
  [10-panel-hire]="storynpcs panel hire"
  [11-panel-achievements]="storynpcs panel achievements"
  [12-panel-carpentry]="storynpcs panel carpentry"
)

for name in 05-panel-quests 06-panel-factions 07-panel-mail 08-panel-transport 09-panel-companions 10-panel-hire 11-panel-achievements 12-panel-carpentry; do
  cmd="${PANELS[$name]}"
  $MCT chat command "$cmd" --client "$CLIENT" >/dev/null 2>&1
  sleep 2
  info=$($MCT gui info --client "$CLIENT" 2>/dev/null | python -c "import json,sys;d=json.loads(sys.stdin.read())['data']['data'];print(d.get('type','?') if d.get('open') else 'CLOSED')" 2>/dev/null)
  $MCT screenshot --client "$CLIENT" --output "$OUT/$name.png" >/dev/null 2>&1
  $MCT gui close --client "$CLIENT" >/dev/null 2>&1
  echo "$name -> $info"
  sleep 1
done
