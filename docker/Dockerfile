# Multiverse Portals — Paper + MVP + Geyser/Floodgate + Via*
# Image: mvsews/mvp
#
#   docker run -d --name minecraft_mvp --network host \
#     -e EULA=TRUE \
#     -e PUBLIC_HOST=YOUR.PUBLIC.IP \
#     -e PUBLIC_PORT=25565 \
#     -p 25565:25565 -p 19132:19132/udp \
#     -v mvp-data:/data \
#     mvsews/mvp
#
FROM itzg/minecraft-server:java21

ARG MVP_JAR=MultiversePortals.jar
ARG PAPER_VERSION=1.21.10

ENV EULA=TRUE \
    TYPE=PAPER \
    VERSION=${PAPER_VERSION} \
    ONLINE_MODE=FALSE \
    MEMORY=1G \
    USE_AIKAR_FLAGS=true \
    ENABLE_ROLLING_LOGS=true \
    MOTD="" \
    CREATE_CONSOLE_IN_PIPE=true

USER root
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl ca-certificates gettext-base jq \
 && rm -rf /var/lib/apt/lists/*

WORKDIR /plugins
# Companion plugins (Bedrock + protocol bridge) — latest Hangar / GeyserMC at build time
RUN set -euo pipefail \
 && curl -fsSL -o Geyser-Spigot.jar \
      "https://download.geysermc.org/v2/projects/geyser/versions/latest/builds/latest/downloads/spigot" \
 && curl -fsSL -o floodgate-spigot.jar \
      "https://download.geysermc.org/v2/projects/floodgate/versions/latest/builds/latest/downloads/spigot" \
 && VV_URL="$(curl -fsSL 'https://hangar.papermc.io/api/v1/projects/ViaVersion/versions?limit=1&platform=PAPER' \
      | jq -r '.result[0].downloads.PAPER.downloadUrl')" \
 && VB_URL="$(curl -fsSL 'https://hangar.papermc.io/api/v1/projects/ViaBackwards/versions?limit=1&platform=PAPER' \
      | jq -r '.result[0].downloads.PAPER.downloadUrl')" \
 && curl -fsSL -o ViaVersion.jar "$VV_URL" \
 && curl -fsSL -o ViaBackwards.jar "$VB_URL" \
 && ls -la /plugins

COPY ${MVP_JAR} /plugins/MultiversePortals.jar
COPY docker/config/server.properties /config/server.properties
COPY docker/mvp/ /image/mvp/
COPY docker/entrypoint.sh /mvp-entrypoint.sh
RUN sed -i 's/\r$//' /mvp-entrypoint.sh /image/mvp/fun-server-name.sh \
 && chmod +x /mvp-entrypoint.sh /image/mvp/fun-server-name.sh \
 && chown -R minecraft:minecraft /plugins /config /image/mvp \
 && ls -la /plugins/MultiversePortals.jar

EXPOSE 25565/tcp 19132/udp 25575/tcp

ENTRYPOINT ["/mvp-entrypoint.sh"]
