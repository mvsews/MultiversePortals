#!/bin/bash
# Refresh Geyser / Floodgate / Via* and MultiversePortals in /data/plugins.
# Keep old jars if a download fails. Sourced or executed; does not start Paper.
set -u

PLUGINS="${PLUGINS_DIR:-/data/plugins}"
mkdir -p "$PLUGINS"

log() { echo "[prestart-plugins] $*"; }

is_jar() {
  local f="$1"
  [[ -s "$f" ]] || return 1
  local magic
  magic="$(od -An -N4 -tx1 "$f" 2>/dev/null | tr -d ' \n')"
  [[ "$magic" == "504b0304" ]]
}

fetch() {
  local url="$1" dest="$2" label="$3"
  local tmp="${dest}.new"
  if ! curl -fsSL --retry 3 --retry-delay 2 --max-time 90 -o "$tmp" "$url"; then
    log "skip ${label}: download failed, keeping old jar"
    rm -f "$tmp"
    return 0
  fi
  if ! is_jar "$tmp"; then
    log "skip ${label}: not a jar, keeping old"
    rm -f "$tmp"
    return 0
  fi
  mv -f "$tmp" "$dest"
  log "updated ${label} ($(wc -c < "$dest" | tr -d ' ') bytes)"
}

hangar_paper_url() {
  local project="$1"
  curl -fsSL --max-time 30 \
    "https://hangar.papermc.io/api/v1/projects/ViaVersion/${project}/versions?limit=1" \
    | tr '"' '\n' \
    | grep -E "^https://hangarcdn.papermc.io/plugins/ViaVersion/${project}/.+/PAPER/.+\\.jar$" \
    | head -1
}

# True if $1 is a newer X.Y.Z than $2 (missing parts count as 0).
ver_gt() {
  awk -v a="${1:-0}" -v b="${2:-0}" 'BEGIN {
    gsub(/[^0-9.].*/, "", a)
    gsub(/[^0-9.].*/, "", b)
    n = split(a, A, ".")
    m = split(b, B, ".")
    max = n > m ? n : m
    for (i = 1; i <= max; i++) {
      ai = (i <= n ? A[i] : 0) + 0
      bi = (i <= m ? B[i] : 0) + 0
      if (ai > bi) exit 0
      if (ai < bi) exit 1
    }
    exit 1
  }'
}

jar_plugin_version() {
  local jar="$1"
  [[ -f "$jar" ]] || return 0
  if command -v unzip >/dev/null 2>&1; then
    unzip -p "$jar" plugin.yml 2>/dev/null \
      | grep -E '^version:' \
      | head -1 \
      | sed -E 's/^version:[[:space:]]*//; s/["'\'' ]//g'
  else
    python3 -c "
import zipfile,sys
z=zipfile.ZipFile(sys.argv[1])
for line in z.read('plugin.yml').decode().splitlines():
    if line.startswith('version:'):
        print(line.split(':',1)[1].strip().strip('\"').strip(\"'\"))
        break
" "$jar" 2>/dev/null || true
  fi
}

json_quoted() {
  local body="$1" key="$2" out
  out="$(printf '%s' "$body" \
    | tr '\n' ' ' \
    | grep -oE "\"${key}\"[[:space:]]*:[[:space:]]*\"[^\"]+\"" \
    | head -1 \
    | sed -E "s/.*:[[:space:]]*\"([^\"]+)\".*/\1/" || true)"
  printf '%s' "$out"
  return 0
}

update_mvp() {
  local dest="$PLUGINS/MultiversePortals.jar"
  local meta_url="${MVP_UPDATE_URL:-https://mp.mvse.ws/version.json}"
  local body remote local_ver dl
  log "checking MultiversePortals at ${meta_url}"
  if ! body="$(curl -fsSL --retry 2 --max-time 20 "$meta_url")"; then
    log "skip MVP: version.json unreachable"
    return 0
  fi
  remote="$(json_quoted "$body" version)"
  dl="$(json_quoted "$body" downloadUrl)"
  if [[ -z "$remote" ]]; then
    log "skip MVP: no version in version.json"
    return 0
  fi
  if [[ -z "$dl" ]]; then
    dl="https://mp.mvse.ws/download/MultiversePortals.jar"
  fi
  local_ver="$(jar_plugin_version "$dest")"
  if [[ -z "$local_ver" ]]; then
    local_ver="0.0.0"
  fi
  if ! ver_gt "$remote" "$local_ver"; then
    log "MVP ${local_ver} is current (catalog ${remote})"
    return 0
  fi
  log "MVP ${local_ver} → ${remote}"
  fetch "$dl" "$dest" "MultiversePortals ${remote}"
  chown 1000:1000 "$dest" 2>/dev/null || true
}

# itzg copies /plugins → /data/plugins on start. Stage fresh jars in /plugins,
# then drop dest + Paper update/ + remapper cache so an older plugins/update jar
# cannot replace what we just installed (that is how dest became 1.2.1 over 1.2.10).
sync_image_plugins() {
  [[ -d /plugins ]] || return 0
  local f
  for f in Geyser-Spigot.jar floodgate-spigot.jar ViaVersion.jar ViaBackwards.jar MultiversePortals.jar; do
    if [[ -f "$PLUGINS/$f" ]]; then
      cp -a "$PLUGINS/$f" "/plugins/$f" 2>/dev/null || true
    fi
    rm -f "$PLUGINS/update/$f" "$PLUGINS/.paper-remapped/$f"
    if [[ -f "/plugins/$f" ]]; then
      rm -f "$PLUGINS/$f"
    fi
  done
}

if [[ "${UPDATE_BEDROCK_BRIDGE:-true}" == "true" ]]; then
  log "fetching latest Geyser + Floodgate + Via*"
  fetch \
    "https://download.geysermc.org/v2/projects/geyser/versions/latest/builds/latest/downloads/spigot" \
    "$PLUGINS/Geyser-Spigot.jar" "Geyser-Spigot"
  fetch \
    "https://download.geysermc.org/v2/projects/floodgate/versions/latest/builds/latest/downloads/spigot" \
    "$PLUGINS/floodgate-spigot.jar" "floodgate"

  vv="$(hangar_paper_url ViaVersion || true)"
  vb="$(hangar_paper_url ViaBackwards || true)"
  if [[ -n "$vv" ]]; then
    fetch "$vv" "$PLUGINS/ViaVersion.jar" "ViaVersion"
  else
    log "skip ViaVersion: Hangar URL not resolved"
  fi
  if [[ -n "$vb" ]]; then
    fetch "$vb" "$PLUGINS/ViaBackwards.jar" "ViaBackwards"
  else
    log "skip ViaBackwards: Hangar URL not resolved"
  fi

  chown 1000:1000 \
    "$PLUGINS/Geyser-Spigot.jar" \
    "$PLUGINS/floodgate-spigot.jar" \
    "$PLUGINS/ViaVersion.jar" \
    "$PLUGINS/ViaBackwards.jar" 2>/dev/null || true
else
  log "UPDATE_BEDROCK_BRIDGE=false — keeping Geyser/Via jars"
fi

if [[ "${UPDATE_MVP:-true}" == "true" ]]; then
  update_mvp
else
  log "UPDATE_MVP=false — keeping MultiversePortals.jar"
fi

sync_image_plugins
