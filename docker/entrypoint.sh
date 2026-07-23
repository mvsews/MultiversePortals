#!/bin/bash
# Multiverse Portals one-shot image — wraps itzg/minecraft-server start.
set -euo pipefail

export EULA="${EULA:-TRUE}"
export TYPE="${TYPE:-PAPER}"
export VERSION="${VERSION:-1.21.10}"
export ONLINE_MODE="${ONLINE_MODE:-FALSE}"
export MEMORY="${MEMORY:-1G}"

# Standard Minecraft ports (host network: same inside and outside)
PUBLIC_PORT="${PUBLIC_PORT:-25565}"
BEDROCK_PORT="${BEDROCK_PORT:-19132}"

mkdir -p /data/plugins/MultiversePortals \
         /data/plugins/Geyser-Spigot \
         /data/plugins/floodgate

# --- Server display name (MOTD + MVP catalog label) ---
# SERVER_NAME or MOTD: custom. If unset: reuse persisted funny name, else generate.
NAME_FILE=/data/.mvp-server-name
if [[ -n "${SERVER_NAME:-}" ]]; then
  SERVER_NAME="$SERVER_NAME"
elif [[ -n "${MOTD:-}" && "${MOTD}" != "Multiverse Portals" && "${MOTD}" != "A Minecraft Server" ]]; then
  SERVER_NAME="$MOTD"
elif [[ -f "$NAME_FILE" ]]; then
  SERVER_NAME="$(tr -d '\r\n' < "$NAME_FILE")"
else
  # shellcheck source=/dev/null
  SERVER_NAME="$(bash /image/mvp/fun-server-name.sh)"
  echo "$SERVER_NAME" > "$NAME_FILE"
  echo "[mvp] generated server name: ${SERVER_NAME}"
fi
# Persist override so restarts keep the chosen/custom name
echo "$SERVER_NAME" > "$NAME_FILE"
export MOTD="$SERVER_NAME"
export MVP_DISPLAY_NAME="$SERVER_NAME"
echo "[mvp] server name: ${SERVER_NAME}"

# PUBLIC_HOST: optional domain/IP override. If empty — detect WAN via ipify.
if [[ -z "${PUBLIC_HOST:-}" ]]; then
  DETECTED="$(curl -fsS --max-time 5 https://api.ipify.org 2>/dev/null || true)"
  if [[ -n "$DETECTED" && "$DETECTED" =~ ^[0-9a-fA-F:.]+$ ]]; then
    PUBLIC_HOST="$DETECTED"
    echo "[mvp] auto PUBLIC_HOST=${PUBLIC_HOST} (ipify)"
  else
    echo "[mvp] PUBLIC_HOST unset and ipify failed — plugin will retry (discover-public-ip)"
  fi
else
  echo "[mvp] PUBLIC_HOST=${PUBLIC_HOST} (manual domain/IP)"
fi

export MVP_PUBLIC_HOST="${PUBLIC_HOST:-}"
export MVP_PUBLIC_PORT="${PUBLIC_PORT}"

# Escape for YAML double-quoted string
yaml_escape() {
  printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g'
}
MVP_DISPLAY_NAME_ESC="$(yaml_escape "$MVP_DISPLAY_NAME")"
export MVP_DISPLAY_NAME_ESC

# Seed or refresh advertise address for the public catalog (mp.mvse.ws)
if [[ ! -f /data/plugins/MultiversePortals/config.yml ]]; then
  envsubst '${MVP_PUBLIC_HOST} ${MVP_PUBLIC_PORT} ${MVP_DISPLAY_NAME_ESC}' \
    < /image/mvp/config.yml.tmpl \
    > /data/plugins/MultiversePortals/config.yml
else
  if [[ -n "${PUBLIC_HOST:-}" ]]; then
    sed -i -E "s|^(  public-host:).*|\1 \"${PUBLIC_HOST}\"|" \
      /data/plugins/MultiversePortals/config.yml || true
  fi
  sed -i -E "s|^(  public-port:).*|\1 ${PUBLIC_PORT}|" \
    /data/plugins/MultiversePortals/config.yml || true
  sed -i -E 's|^(  discover-public-ip:).*|\1 true|' \
    /data/plugins/MultiversePortals/config.yml || true
  sed -i -E "s|^(  display-name:).*|\1 \"${MVP_DISPLAY_NAME_ESC}\"|" \
    /data/plugins/MultiversePortals/config.yml || true
fi

if [[ -n "${PUBLIC_HOST:-}" ]]; then
  echo "[mvp] catalog advertise ${PUBLIC_HOST}:${PUBLIC_PORT} (Bedrock UDP ${BEDROCK_PORT})"
else
  echo "[mvp] catalog: waiting for discover-public-ip; port=${PUBLIC_PORT}"
fi

# Optional shared Floodgate key (base64 of key.pem) for cross-server Bedrock transfers
if [[ -n "${FLOODGATE_KEY_B64:-}" ]]; then
  echo "$FLOODGATE_KEY_B64" | base64 -d > /data/plugins/floodgate/key.pem
  chmod 600 /data/plugins/floodgate/key.pem
fi

# Seed Geyser for Floodgate auth on first boot
if [[ ! -f /data/plugins/Geyser-Spigot/config.yml ]]; then
  sed "s/port: 19132/port: ${BEDROCK_PORT}/" /image/mvp/geyser-config.yml \
    > /data/plugins/Geyser-Spigot/config.yml
else
  sed -i -E 's/^(  auth-type:).*/\1 floodgate/' /data/plugins/Geyser-Spigot/config.yml || true
  sed -i -E "s|^(  port:).*|\1 ${BEDROCK_PORT}|" /data/plugins/Geyser-Spigot/config.yml || true
fi

# Vanilla Transfer gate + Java listen port + MOTD
if [[ -f /data/server.properties ]]; then
  if grep -q '^accept-transfers=' /data/server.properties; then
    sed -i -E 's/^accept-transfers=.*/accept-transfers=true/' /data/server.properties
  else
    echo 'accept-transfers=true' >> /data/server.properties
  fi
  if grep -q '^server-port=' /data/server.properties; then
    sed -i -E "s/^server-port=.*/server-port=${PUBLIC_PORT}/" /data/server.properties
  else
    echo "server-port=${PUBLIC_PORT}" >> /data/server.properties
  fi
  # motd= may contain spaces — rewrite whole line
  MOTD_PROP="$(printf '%s' "$SERVER_NAME" | sed 's/\\/\\\\/g')"
  if grep -q '^motd=' /data/server.properties; then
    sed -i -E "s|^motd=.*|motd=${MOTD_PROP}|" /data/server.properties
  else
    echo "motd=${MOTD_PROP}" >> /data/server.properties
  fi
fi

# itzg also honors SERVER_PORT / MOTD when creating properties on first boot
export SERVER_PORT="${PUBLIC_PORT}"

# itzg runs as uid 1000 — anything we seeded as root must be owned by minecraft
find /data/plugins -maxdepth 1 -type f -name '*.jar' ! -user 1000 -delete 2>/dev/null || true
chown -R 1000:1000 /data 2>/dev/null || true

exec /image/scripts/start "$@"
