#!/bin/bash
set -u
# shellcheck disable=SC1091
. /image/update-bedrock-bridge.sh
exec /image/scripts/start "$@"
