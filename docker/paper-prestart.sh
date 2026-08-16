#!/bin/bash
# itzg start hook — refresh plugins, then hand off to Paper.
set -u
export UPDATE_BEDROCK_BRIDGE="${UPDATE_BEDROCK_BRIDGE:-true}"
export UPDATE_MVP="${UPDATE_MVP:-true}"
if [[ -f /image/update-bedrock-bridge.sh ]]; then
  set +e
  # shellcheck disable=SC1091
  . /image/update-bedrock-bridge.sh
  set -e
else
  echo "[prestart-plugins] missing /image/update-bedrock-bridge.sh — Paper starts with existing jars"
fi
exec /image/scripts/start "$@"
