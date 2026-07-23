package io.multiverseportals.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.multiverseportals.compat.ServerCaps;
import io.multiverseportals.config.PluginConfig;
import io.multiverseportals.model.Portal;
import io.multiverseportals.model.PortalType;
import io.multiverseportals.model.RegistryPortal;
import io.multiverseportals.model.RegistryServer;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;

/**
 * Shared MySQL/MariaDB registry — directory of servers for MULTI random portals.
 * Local SQLite still holds pair portals, sessions, inventory snapshots.
 */
public final class RegistryDatabase {

    private final JavaPlugin plugin;
    private final PluginConfig config;
    private HikariDataSource ds;

    public RegistryDatabase(JavaPlugin plugin, PluginConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    public boolean enabled() {
        return config.registryEnabled();
    }

    public void init() {
        if (!enabled()) {
            plugin.getLogger().info("Shared registry disabled (multi pool will use local peers only)");
            return;
        }
        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl(config.registryJdbcUrl());
        hc.setUsername(config.registryUser());
        hc.setPassword(config.registryPassword());
        hc.setMaximumPoolSize(3);
        hc.setPoolName("MVP-Registry");
        hc.setConnectionTimeout(5000);
        this.ds = new HikariDataSource(hc);
        migrate();
        plugin.getLogger().info("Shared registry connected");
    }

    private void migrate() {
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("""
                CREATE TABLE IF NOT EXISTS registry_servers (
                  server_id          VARCHAR(64)  NOT NULL PRIMARY KEY,
                  display_name       VARCHAR(128) NOT NULL,
                  public_host        VARCHAR(255) NOT NULL,
                  public_port        INT          NOT NULL DEFAULT 25565,
                  federation_url     VARCHAR(512) NULL,
                  has_plugin         TINYINT(1)   NOT NULL DEFAULT 1,
                  multi_opt_in       TINYINT(1)   NOT NULL DEFAULT 1,
                  accept_transfers   TINYINT(1)   NOT NULL DEFAULT 1,
                  tags               VARCHAR(255) NULL,
                  motd               VARCHAR(255) NULL,
                  max_players        INT          NULL,
                  online_players     INT          NULL,
                  protocol_version   VARCHAR(32)  NULL,
                  last_heartbeat     BIGINT       NOT NULL,
                  last_online_at     BIGINT       NULL,
                  last_offline_at    BIGINT       NULL,
                  created_at         BIGINT       NOT NULL
                )
                """);
            try {
                st.execute("CREATE INDEX idx_multi_alive ON registry_servers (multi_opt_in, last_heartbeat)");
            } catch (SQLException ignored) {
            }
            // Presence: created_at = first registration; last_online_at / last_offline_at transitions
            alterIgnore(st, "ALTER TABLE registry_servers ADD COLUMN last_online_at BIGINT NULL");
            alterIgnore(st, "ALTER TABLE registry_servers ADD COLUMN last_offline_at BIGINT NULL");
            try {
                st.execute("""
                    UPDATE registry_servers
                    SET last_online_at = last_heartbeat
                    WHERE last_online_at IS NULL AND last_heartbeat > 0
                    """);
            } catch (SQLException ignored) {
            }
            // List branding: MOTD line2 + server-icon.png
            alterIgnore(st, "ALTER TABLE registry_servers ADD COLUMN description VARCHAR(512) NULL");
            alterIgnore(st, "ALTER TABLE registry_servers ADD COLUMN icon_png MEDIUMBLOB NULL");
            try {
                st.execute("ALTER TABLE registry_servers MODIFY COLUMN motd VARCHAR(512) NULL");
            } catch (SQLException ignored) {
            }
            // Version / Via compatibility columns (safe to re-run)
            alterIgnore(st, "ALTER TABLE registry_servers ADD COLUMN mc_version VARCHAR(32) NULL");
            alterIgnore(st, "ALTER TABLE registry_servers ADD COLUMN protocol_id INT NULL");
            alterIgnore(st, "ALTER TABLE registry_servers ADD COLUMN has_via_version TINYINT(1) NOT NULL DEFAULT 0");
            alterIgnore(st, "ALTER TABLE registry_servers ADD COLUMN has_via_backwards TINYINT(1) NOT NULL DEFAULT 0");
            alterIgnore(st, "ALTER TABLE registry_servers ADD COLUMN has_via_rewind TINYINT(1) NOT NULL DEFAULT 0");
            alterIgnore(st, "ALTER TABLE registry_servers ADD COLUMN via_min INT NULL");
            alterIgnore(st, "ALTER TABLE registry_servers ADD COLUMN via_max INT NULL");
            // Capability / ingress snapshot (not full plugin list)
            alterIgnore(st, "ALTER TABLE registry_servers ADD COLUMN has_geyser TINYINT(1) NOT NULL DEFAULT 0");
            alterIgnore(st, "ALTER TABLE registry_servers ADD COLUMN has_floodgate TINYINT(1) NOT NULL DEFAULT 0");
            alterIgnore(st, "ALTER TABLE registry_servers ADD COLUMN geyser_version VARCHAR(64) NULL");
            alterIgnore(st, "ALTER TABLE registry_servers ADD COLUMN bedrock_port INT NULL");
            alterIgnore(st, "ALTER TABLE registry_servers ADD COLUMN bedrock_protocol INT NULL");
            alterIgnore(st, "ALTER TABLE registry_servers ADD COLUMN bedrock_version VARCHAR(32) NULL");
            alterIgnore(st, "ALTER TABLE registry_servers ADD COLUMN accept_bedrock TINYINT(1) NOT NULL DEFAULT 0");
            alterIgnore(st, "ALTER TABLE registry_servers ADD COLUMN ingress_enabled TINYINT(1) NOT NULL DEFAULT 1");
            alterIgnore(st, "ALTER TABLE registry_servers ADD COLUMN ingress_max_online INT NULL");
            alterIgnore(st, "ALTER TABLE registry_servers ADD COLUMN ingress_reserve INT NULL");
            alterIgnore(st, "ALTER TABLE registry_servers ADD COLUMN ingress_min_score DOUBLE NULL");
            alterIgnore(st, "ALTER TABLE registry_servers ADD COLUMN ingress_deny_unknown TINYINT(1) NOT NULL DEFAULT 0");
            alterIgnore(st, "ALTER TABLE registry_servers ADD COLUMN ingress_max_per_hour INT NULL");
            alterIgnore(st, "ALTER TABLE registry_servers ADD COLUMN export_inventory TINYINT(1) NOT NULL DEFAULT 0");
            alterIgnore(st, "ALTER TABLE registry_servers ADD COLUMN import_inventory TINYINT(1) NOT NULL DEFAULT 0");
            alterIgnore(st, "ALTER TABLE registry_servers ADD COLUMN mvp_version VARCHAR(64) NULL");
            st.execute("""
                CREATE TABLE IF NOT EXISTS registry_portals (
                  server_id         VARCHAR(64)  NOT NULL,
                  portal_id         VARCHAR(64)  NOT NULL,
                  portal_type       VARCHAR(16)  NOT NULL,
                  status            VARCHAR(16)  NOT NULL,
                  world             VARCHAR(128) NOT NULL,
                  x                 INT NOT NULL,
                  y                 INT NOT NULL,
                  z                 INT NOT NULL,
                  yaw               FLOAT NOT NULL DEFAULT 0,
                  name              VARCHAR(128) NULL,
                  dest_kind         VARCHAR(16)  NOT NULL DEFAULT 'none',
                  dest_server_id    VARCHAR(64)  NULL,
                  dest_host         VARCHAR(255) NULL,
                  dest_port         INT NULL,
                  dest_java_port    INT NULL,
                  dest_portal_id    VARCHAR(64)  NULL,
                  dest_label        VARCHAR(255) NULL,
                  return_capable    TINYINT(1)   NOT NULL DEFAULT 0,
                  signs_json        TEXT NULL,
                  updated_at        BIGINT       NOT NULL,
                  PRIMARY KEY (server_id, portal_id),
                  INDEX idx_reg_portals_dest (dest_server_id),
                  INDEX idx_reg_portals_host (dest_host, dest_java_port),
                  INDEX idx_reg_portals_updated (updated_at)
                )
                """);
            alterIgnore(st, "ALTER TABLE registry_portals ADD COLUMN signs_json TEXT NULL");
            st.execute("""
                CREATE TABLE IF NOT EXISTS registry_pair_invites (
                  invite_code       VARCHAR(16)  NOT NULL PRIMARY KEY,
                  host_server_id    VARCHAR(64)  NOT NULL,
                  host_portal_id    VARCHAR(64)  NOT NULL,
                  host_world        VARCHAR(128) NOT NULL,
                  host_x            INT NOT NULL,
                  host_y            INT NOT NULL,
                  host_z            INT NOT NULL,
                  created_at        BIGINT NOT NULL,
                  expires_at        BIGINT NOT NULL,
                  claimed_by_server VARCHAR(64) NULL,
                  claimed_portal_id VARCHAR(64) NULL
                )
                """);
            st.execute("""
                CREATE TABLE IF NOT EXISTS registry_travel (
                  session_id        VARCHAR(64)  NOT NULL PRIMARY KEY,
                  player_uuid       VARCHAR(64)  NOT NULL,
                  from_server       VARCHAR(64)  NOT NULL,
                  to_server         VARCHAR(64)  NOT NULL,
                  to_portal_id      VARCHAR(64)  NULL,
                  portal_type       VARCHAR(16)  NOT NULL,
                  carry_inventory   TINYINT(1)   NOT NULL DEFAULT 0,
                  inventory_b64     MEDIUMTEXT   NULL,
                  score             DOUBLE       NULL,
                  status            VARCHAR(16)  NOT NULL,
                  created_at        BIGINT NOT NULL,
                  expires_at        BIGINT NOT NULL,
                  landing_return    TINYINT(1)   NOT NULL DEFAULT 0,
                  INDEX idx_travel_player (player_uuid, status)
                )
                """);
            // landing_return: 1 = to_portal_id leads home; 0 = fallback placement at any portal
            alterIgnore(st, "ALTER TABLE registry_travel ADD COLUMN landing_return TINYINT(1) NOT NULL DEFAULT 0");
            alterIgnore(st, "ALTER TABLE registry_travel ADD COLUMN dest_host VARCHAR(255) NULL");
            alterIgnore(st, "ALTER TABLE registry_travel ADD COLUMN dest_java_port INT NULL");
            st.execute("""
                CREATE TABLE IF NOT EXISTS registry_scanner_hosts (
                  host               VARCHAR(255) NOT NULL,
                  java_port          INT          NOT NULL,
                  mc_version         VARCHAR(32)  NULL,
                  version_branch     VARCHAR(16)  NULL,
                  score              DOUBLE       NOT NULL DEFAULT 0,
                  status             VARCHAR(16)  NOT NULL DEFAULT 'SEEN',
                  bedrock_port       INT          NULL,
                  bedrock_protocol   INT          NULL,
                  bedrock_version    VARCHAR(32)  NULL,
                  online_players     INT          NULL,
                  max_players        INT          NULL,
                  display_name       VARCHAR(255) NULL,
                  motd               VARCHAR(512) NULL,
                  source             VARCHAR(32)  NULL,
                  success_count      INT          NOT NULL DEFAULT 0,
                  fail_count         INT          NOT NULL DEFAULT 0,
                  last_ok_at         BIGINT       NULL,
                  last_fail_at       BIGINT       NULL,
                  last_seen_at       BIGINT       NULL,
                  updated_at         BIGINT       NOT NULL,
                  PRIMARY KEY (host, java_port),
                  INDEX idx_scan_branch_score (version_branch, score),
                  INDEX idx_scan_bedrock (bedrock_port, score),
                  INDEX idx_scan_seen (last_seen_at)
                )
                """);
        } catch (SQLException e) {
            throw new IllegalStateException("Registry migrate failed", e);
        }
    }

    private static void alterIgnore(Statement st, String sql) {
        try {
            st.execute(sql);
        } catch (SQLException ignored) {
        }
    }

    public void close() {
        if (ds != null) {
            ds.close();
        }
    }

    public void announceSelf(
            int onlinePlayers,
            int maxPlayers,
            String motd,
            String mcVersion,
            int protocolId,
            boolean hasViaVersion,
            boolean hasViaBackwards,
            boolean hasViaRewind,
            int viaMin,
            int viaMax
    ) {
        announceSelf(onlinePlayers, maxPlayers, motd, mcVersion, protocolId,
                hasViaVersion, hasViaBackwards, hasViaRewind, viaMin, viaMax, ServerCaps.empty());
    }

    public void announceSelf(
            int onlinePlayers,
            int maxPlayers,
            String motd,
            String mcVersion,
            int protocolId,
            boolean hasViaVersion,
            boolean hasViaBackwards,
            boolean hasViaRewind,
            int viaMin,
            int viaMax,
            ServerCaps caps
    ) {
        if (!enabled()) {
            return;
        }
        if (caps == null) {
            caps = ServerCaps.empty();
        }
        long now = System.currentTimeMillis();
        String sql = """
            INSERT INTO registry_servers(
              server_id, display_name, public_host, public_port, federation_url,
              has_plugin, multi_opt_in, accept_transfers, tags, motd,
              max_players, online_players, protocol_version, mc_version, protocol_id,
              has_via_version, has_via_backwards, has_via_rewind, via_min, via_max,
              has_geyser, has_floodgate, geyser_version, bedrock_port, bedrock_protocol, bedrock_version,
              accept_bedrock, ingress_enabled, ingress_max_online, ingress_reserve, ingress_min_score,
              ingress_deny_unknown, ingress_max_per_hour, export_inventory, import_inventory, mvp_version,
              last_heartbeat, last_online_at, created_at)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            ON DUPLICATE KEY UPDATE
              display_name=VALUES(display_name),
              public_host=VALUES(public_host),
              public_port=VALUES(public_port),
              federation_url=VALUES(federation_url),
              has_plugin=VALUES(has_plugin),
              multi_opt_in=VALUES(multi_opt_in),
              accept_transfers=VALUES(accept_transfers),
              tags=VALUES(tags),
              motd=VALUES(motd),
              max_players=VALUES(max_players),
              online_players=VALUES(online_players),
              protocol_version=VALUES(protocol_version),
              mc_version=VALUES(mc_version),
              protocol_id=VALUES(protocol_id),
              has_via_version=VALUES(has_via_version),
              has_via_backwards=VALUES(has_via_backwards),
              has_via_rewind=VALUES(has_via_rewind),
              via_min=VALUES(via_min),
              via_max=VALUES(via_max),
              has_geyser=VALUES(has_geyser),
              has_floodgate=VALUES(has_floodgate),
              geyser_version=VALUES(geyser_version),
              bedrock_port=IF(VALUES(bedrock_port)>0, VALUES(bedrock_port), bedrock_port),
              bedrock_protocol=IF(VALUES(bedrock_protocol)>0, VALUES(bedrock_protocol), bedrock_protocol),
              bedrock_version=IF(VALUES(bedrock_version) IS NOT NULL AND VALUES(bedrock_version)<>'', VALUES(bedrock_version), bedrock_version),
              accept_bedrock=VALUES(accept_bedrock),
              ingress_enabled=VALUES(ingress_enabled),
              ingress_max_online=VALUES(ingress_max_online),
              ingress_reserve=VALUES(ingress_reserve),
              ingress_min_score=VALUES(ingress_min_score),
              ingress_deny_unknown=VALUES(ingress_deny_unknown),
              ingress_max_per_hour=VALUES(ingress_max_per_hour),
              export_inventory=VALUES(export_inventory),
              import_inventory=VALUES(import_inventory),
              mvp_version=VALUES(mvp_version),
              last_heartbeat=VALUES(last_heartbeat),
              last_online_at=VALUES(last_online_at),
              last_offline_at=NULL
            """;
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            String fedUrl = "http://" + config.publicHost() + ":" + config.federationPort() + config.federationPath();
            if (config.federationBind().equals("0.0.0.0") || config.federationBind().equals("127.0.0.1")) {
                fedUrl = config.registryFederationUrlOverride().orElse(fedUrl);
            }
            int i = 1;
            ps.setString(i++, config.serverId());
            ps.setString(i++, config.displayName());
            ps.setString(i++, config.publicHost());
            ps.setInt(i++, config.publicPort());
            ps.setString(i++, config.registryFederationUrlOverride().orElse(fedUrl));
            ps.setInt(i++, 1);
            ps.setInt(i++, config.registryMultiOptIn() ? 1 : 0);
            ps.setInt(i++, config.acceptTransfersEnabled() ? 1 : 0);
            ps.setString(i++, config.registryTags());
            ps.setString(i++, motd);
            ps.setInt(i++, maxPlayers);
            ps.setInt(i++, onlinePlayers);
            ps.setString(i++, mcVersion);
            ps.setString(i++, mcVersion);
            ps.setInt(i++, protocolId);
            ps.setInt(i++, hasViaVersion ? 1 : 0);
            ps.setInt(i++, hasViaBackwards ? 1 : 0);
            ps.setInt(i++, hasViaRewind ? 1 : 0);
            ps.setInt(i++, viaMin);
            ps.setInt(i++, viaMax);
            ps.setInt(i++, caps.hasGeyser() ? 1 : 0);
            ps.setInt(i++, caps.hasFloodgate() ? 1 : 0);
            ps.setString(i++, caps.geyserVersion());
            ps.setInt(i++, caps.bedrockPort());
            ps.setInt(i++, caps.bedrockProtocol());
            ps.setString(i++, caps.bedrockVersion());
            ps.setInt(i++, caps.acceptBedrock() ? 1 : 0);
            ps.setInt(i++, caps.ingressEnabled() ? 1 : 0);
            ps.setInt(i++, caps.ingressMaxOnline());
            ps.setInt(i++, caps.ingressReserveSlots());
            ps.setDouble(i++, caps.ingressMinScore());
            ps.setInt(i++, caps.ingressDenyUnknownScore() ? 1 : 0);
            ps.setInt(i++, caps.ingressMaxArrivalsPerHour());
            ps.setInt(i++, caps.exportInventory() ? 1 : 0);
            ps.setInt(i++, caps.importInventory() ? 1 : 0);
            ps.setString(i++, caps.mvpVersion());
            ps.setLong(i++, now);
            ps.setLong(i++, now);
            ps.setLong(i, now);
            ps.executeUpdate();
            try {
                var brand = io.multiverseportals.util.ServerBranding.local();
                updateBranding(
                        config.serverId(),
                        brand.name(),
                        brand.description(),
                        brand.motd(),
                        brand.hasIcon() ? brand.iconPng() : null
                );
            } catch (Throwable ignored) {
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Registry announce failed: " + e.getMessage());
        }
    }

    /**
     * Hub: upsert a peer that announced via HTTP catalog (no JDBC on the peer).
     */
    public void upsertPeerAnnounce(
            String serverId,
            String displayName,
            String publicHost,
            int publicPort,
            String federationUrl,
            String mcVersion,
            long lastHeartbeat,
            ServerCaps caps
    ) {
        upsertPeerAnnounce(serverId, displayName, publicHost, publicPort, federationUrl,
                mcVersion, lastHeartbeat, caps, null, null, null);
    }

    public void upsertPeerAnnounce(
            String serverId,
            String displayName,
            String publicHost,
            int publicPort,
            String federationUrl,
            String mcVersion,
            long lastHeartbeat,
            ServerCaps caps,
            String description,
            String motd,
            byte[] iconPng
    ) {
        upsertPeerAnnounce(serverId, displayName, publicHost, publicPort, federationUrl,
                mcVersion, lastHeartbeat, caps, description, motd, iconPng, 0, 0);
    }

    public void upsertPeerAnnounce(
            String serverId,
            String displayName,
            String publicHost,
            int publicPort,
            String federationUrl,
            String mcVersion,
            long lastHeartbeat,
            ServerCaps caps,
            String description,
            String motd,
            byte[] iconPng,
            int onlinePlayers,
            int maxPlayers
    ) {
        if (!enabled() || serverId == null || serverId.isBlank()
                || publicHost == null || publicHost.isBlank() || publicPort <= 0) {
            return;
        }
        if (caps == null) {
            caps = ServerCaps.empty();
        }
        long now = lastHeartbeat > 0 ? lastHeartbeat : System.currentTimeMillis();
        String sql = """
            INSERT INTO registry_servers(
              server_id, display_name, public_host, public_port, federation_url,
              has_plugin, multi_opt_in, accept_transfers, tags, motd,
              max_players, online_players, protocol_version, mc_version, protocol_id,
              has_via_version, has_via_backwards, has_via_rewind, via_min, via_max,
              has_geyser, has_floodgate, geyser_version, bedrock_port, bedrock_protocol, bedrock_version,
              accept_bedrock, ingress_enabled, ingress_max_online, ingress_reserve, ingress_min_score,
              ingress_deny_unknown, ingress_max_per_hour, export_inventory, import_inventory, mvp_version,
              last_heartbeat, last_online_at, created_at)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            ON DUPLICATE KEY UPDATE
              display_name=VALUES(display_name),
              public_host=VALUES(public_host),
              public_port=VALUES(public_port),
              federation_url=VALUES(federation_url),
              has_plugin=1,
              multi_opt_in=VALUES(multi_opt_in),
              accept_transfers=1,
              mc_version=VALUES(mc_version),
              protocol_version=VALUES(protocol_version),
              max_players=VALUES(max_players),
              online_players=VALUES(online_players),
              has_geyser=VALUES(has_geyser),
              has_floodgate=VALUES(has_floodgate),
              geyser_version=VALUES(geyser_version),
              bedrock_port=IF(VALUES(bedrock_port)>0, VALUES(bedrock_port), bedrock_port),
              bedrock_protocol=IF(VALUES(bedrock_protocol)>0, VALUES(bedrock_protocol), bedrock_protocol),
              bedrock_version=IF(VALUES(bedrock_version) IS NOT NULL AND VALUES(bedrock_version)<>'', VALUES(bedrock_version), bedrock_version),
              accept_bedrock=VALUES(accept_bedrock),
              mvp_version=VALUES(mvp_version),
              last_heartbeat=VALUES(last_heartbeat),
              last_online_at=VALUES(last_online_at),
              last_offline_at=NULL
            """;
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            int i = 1;
            ps.setString(i++, serverId.trim());
            ps.setString(i++, displayName == null || displayName.isBlank() ? serverId : displayName);
            ps.setString(i++, publicHost.trim());
            ps.setInt(i++, publicPort);
            ps.setString(i++, federationUrl == null ? "" : federationUrl);
            ps.setInt(i++, 1);
            ps.setInt(i++, 1);
            ps.setInt(i++, 1);
            ps.setString(i++, "");
            ps.setString(i++, motd == null ? "" : motd);
            ps.setInt(i++, Math.max(0, maxPlayers));
            ps.setInt(i++, Math.max(0, onlinePlayers));
            String mc = mcVersion == null ? "" : mcVersion;
            ps.setString(i++, mc);
            ps.setString(i++, mc);
            ps.setInt(i++, 0);
            ps.setInt(i++, 0);
            ps.setInt(i++, 0);
            ps.setInt(i++, 0);
            ps.setInt(i++, 0);
            ps.setInt(i++, 0);
            ps.setInt(i++, caps.hasGeyser() ? 1 : 0);
            ps.setInt(i++, caps.hasFloodgate() ? 1 : 0);
            ps.setString(i++, caps.geyserVersion());
            ps.setInt(i++, caps.bedrockPort());
            ps.setInt(i++, caps.bedrockProtocol());
            ps.setString(i++, caps.bedrockVersion());
            ps.setInt(i++, caps.acceptBedrock() ? 1 : 0);
            ps.setInt(i++, caps.ingressEnabled() ? 1 : 0);
            ps.setInt(i++, caps.ingressMaxOnline());
            ps.setInt(i++, caps.ingressReserveSlots());
            ps.setDouble(i++, caps.ingressMinScore());
            ps.setInt(i++, caps.ingressDenyUnknownScore() ? 1 : 0);
            ps.setInt(i++, caps.ingressMaxArrivalsPerHour());
            ps.setInt(i++, caps.exportInventory() ? 1 : 0);
            ps.setInt(i++, caps.importInventory() ? 1 : 0);
            ps.setString(i++, caps.mvpVersion());
            ps.setLong(i++, now);
            ps.setLong(i++, now);
            ps.setLong(i, now);
            ps.executeUpdate();
            updateBranding(serverId.trim(), displayName, description, motd, iconPng);
            // Same public host:port = same physical server (name / serverId may change).
            retireDuplicateEndpoints(serverId.trim(), publicHost.trim(), publicPort);
        } catch (SQLException e) {
            plugin.getLogger().warning("Registry peer upsert failed for " + serverId + ": " + e.getMessage());
        }
    }

    /**
     * Remove other registry rows that advertise the same join endpoint.
     * Display-name or serverId churn must not create duplicate map markers.
     */
    public int retireDuplicateEndpoints(String keepServerId, String publicHost, int publicPort) {
        if (!enabled() || keepServerId == null || keepServerId.isBlank()
                || publicHost == null || publicHost.isBlank() || publicPort <= 0) {
            return 0;
        }
        String keep = keepServerId.trim();
        String host = publicHost.trim();
        List<String> victims = new ArrayList<>();
        String select = """
            SELECT server_id FROM registry_servers
            WHERE LOWER(public_host)=LOWER(?) AND public_port=? AND server_id<>?
            """;
        try (Connection c = ds.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement(select)) {
                ps.setString(1, host);
                ps.setInt(2, publicPort);
                ps.setString(3, keep);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String id = rs.getString(1);
                        if (id != null && !id.isBlank()) {
                            victims.add(id.trim());
                        }
                    }
                }
            }
            if (victims.isEmpty()) {
                return 0;
            }
            try (PreparedStatement delP = c.prepareStatement(
                    "DELETE FROM registry_portals WHERE server_id=?");
                 PreparedStatement delS = c.prepareStatement(
                         "DELETE FROM registry_servers WHERE server_id=?")) {
                for (String id : victims) {
                    delP.setString(1, id);
                    delP.executeUpdate();
                    delS.setString(1, id);
                    delS.executeUpdate();
                }
            }
            plugin.getLogger().info("Registry: merged " + victims.size()
                    + " duplicate endpoint(s) " + host + ":" + publicPort
                    + " into " + keep + " (" + String.join(", ", victims) + ")");
            return victims.size();
        } catch (SQLException e) {
            plugin.getLogger().warning("Registry endpoint dedupe failed for "
                    + host + ":" + publicPort + ": " + e.getMessage());
            return 0;
        }
    }

    /**
     * Hub sweeper: when heartbeat is older than stale window, record {@code last_offline_at}
     * once per online→offline transition (does not rewrite {@code created_at}).
     *
     * @return rows marked offline this pass
     */
    public int markStaleOffline() {
        if (!enabled()) {
            return 0;
        }
        long staleMs = Math.max(1_000L, config.registryStaleMs());
        long cutoff = System.currentTimeMillis() - staleMs;
        String sql = """
            UPDATE registry_servers
            SET last_offline_at = last_heartbeat + ?
            WHERE last_heartbeat > 0
              AND last_heartbeat < ?
              AND last_online_at IS NOT NULL
              AND (last_offline_at IS NULL OR last_offline_at < last_online_at)
            """;
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, staleMs);
            ps.setLong(2, cutoff);
            return ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().warning("Registry mark-stale-offline failed: " + e.getMessage());
            return 0;
        }
    }

    /** Clean shutdown: stamp offline for this server id immediately. */
    public void markSelfOffline() {
        markPeerOffline(config.serverId());
    }

    /** Hub: peer announced graceful shutdown over HTTPS catalog. */
    public void markPeerOffline(String serverId) {
        if (!enabled() || serverId == null || serverId.isBlank()) {
            return;
        }
        long now = System.currentTimeMillis();
        String sql = """
            UPDATE registry_servers
            SET last_offline_at = ?
            WHERE server_id = ?
              AND (last_offline_at IS NULL OR last_online_at IS NULL OR last_offline_at < last_online_at)
            """;
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, now);
            ps.setString(2, serverId.trim());
            int n = ps.executeUpdate();
            if (n > 0) {
                plugin.getLogger().info("Registry: marked offline " + serverId.trim());
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Registry mark-offline failed for " + serverId + ": " + e.getMessage());
        }
    }

    /**
     * Update list branding (name / description / motd / optional PNG icon).
     * Null {@code iconPng} keeps the previous icon; empty array clears it.
     */
    public void updateBranding(
            String serverId,
            String displayName,
            String description,
            String motd,
            byte[] iconPng
    ) {
        if (!enabled() || serverId == null || serverId.isBlank()) {
            return;
        }
        String sqlWithIcon = """
            UPDATE registry_servers
            SET display_name = COALESCE(NULLIF(?, ''), display_name),
                description = ?,
                motd = COALESCE(NULLIF(?, ''), motd),
                icon_png = ?
            WHERE server_id = ?
            """;
        String sqlNoIcon = """
            UPDATE registry_servers
            SET display_name = COALESCE(NULLIF(?, ''), display_name),
                description = COALESCE(?, description),
                motd = COALESCE(NULLIF(?, ''), motd)
            WHERE server_id = ?
            """;
        try (Connection c = ds.getConnection()) {
            if (iconPng != null) {
                try (PreparedStatement ps = c.prepareStatement(sqlWithIcon)) {
                    ps.setString(1, displayName == null ? "" : displayName);
                    ps.setString(2, description == null ? "" : description);
                    ps.setString(3, motd == null ? "" : motd);
                    if (iconPng.length == 0) {
                        ps.setBytes(4, null);
                    } else {
                        ps.setBytes(4, iconPng);
                    }
                    ps.setString(5, serverId.trim());
                    ps.executeUpdate();
                }
            } else {
                try (PreparedStatement ps = c.prepareStatement(sqlNoIcon)) {
                    ps.setString(1, displayName == null ? "" : displayName);
                    ps.setString(2, description);
                    ps.setString(3, motd == null ? "" : motd);
                    ps.setString(4, serverId.trim());
                    ps.executeUpdate();
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Registry branding update failed for " + serverId + ": " + e.getMessage());
        }
    }

    public byte[] iconPng(String serverId) {
        if (!enabled() || serverId == null || serverId.isBlank()) {
            return null;
        }
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(
                "SELECT icon_png FROM registry_servers WHERE server_id=?")) {
            ps.setString(1, serverId.trim());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return rs.getBytes(1);
            }
        } catch (SQLException e) {
            return null;
        }
    }

    private static final Gson GSON = new Gson();

    /**
     * Replace this server's portal rows in the shared graph.
     * Public fields only — coordinates + destination edge + optional comment signs (no secrets).
     */
    public int syncPortals(List<Portal> portals) {
        return syncPortals(portals, Map.of());
    }

    public int syncPortals(List<Portal> portals, Map<String, List<String>> commentSigns) {
        if (!enabled() || !config.registryPublishPortals()) {
            return 0;
        }
        if (commentSigns == null) {
            commentSigns = Map.of();
        }
        String self = config.serverId();
        long now = System.currentTimeMillis();
        Set<String> keep = new HashSet<>();
        int n = 0;
        String upsert = """
            INSERT INTO registry_portals(
              server_id, portal_id, portal_type, status, world, x, y, z, yaw, name,
              dest_kind, dest_server_id, dest_host, dest_port, dest_java_port, dest_portal_id,
              dest_label, return_capable, signs_json, updated_at)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            ON DUPLICATE KEY UPDATE
              portal_type=VALUES(portal_type),
              status=VALUES(status),
              world=VALUES(world),
              x=VALUES(x), y=VALUES(y), z=VALUES(z), yaw=VALUES(yaw),
              name=VALUES(name),
              dest_kind=VALUES(dest_kind),
              dest_server_id=VALUES(dest_server_id),
              dest_host=VALUES(dest_host),
              dest_port=VALUES(dest_port),
              dest_java_port=VALUES(dest_java_port),
              dest_portal_id=VALUES(dest_portal_id),
              dest_label=VALUES(dest_label),
              return_capable=VALUES(return_capable),
              signs_json=VALUES(signs_json),
              updated_at=VALUES(updated_at)
            """;
        try (Connection c = ds.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement(upsert)) {
                for (Portal p : portals) {
                    if (p == null) {
                        continue;
                    }
                    keep.add(p.id());
                    String destKind = "none";
                    String destServer = null;
                    String destHost = null;
                    int destPort = 0;
                    int destJava = 0;
                    String destPortal = null;
                    String destLabel = null;
                    boolean ret = false;

                    if (p.type() == PortalType.PAIR && p.pairServerId() != null && !p.pairServerId().isBlank()) {
                        destKind = "pair";
                        destServer = p.pairServerId();
                        destPortal = p.pairPortalId();
                        ret = p.pairPortalId() != null && !p.pairPortalId().isBlank();
                        Optional<RegistryServer> peer = find(p.pairServerId());
                        if (peer.isPresent()) {
                            destHost = peer.get().publicHost();
                            destPort = peer.get().publicPort();
                            destJava = peer.get().publicPort();
                            destLabel = peer.get().displayName();
                        }
                    } else if (p.hasBoundDestination()) {
                        destKind = "bound";
                        destHost = p.boundHost();
                        destPort = p.boundPort();
                        destJava = p.boundJavaPort() > 0 ? p.boundJavaPort() : p.boundPort();
                        destLabel = p.boundVersion();
                        destServer = resolveServerIdByHost(destHost, destJava).orElse(null);
                        destPortal = p.boundDestPortalId();
                        // Return if dest is an MVP peer that has a portal edge back to us
                        ret = destServer != null && (destPortal != null || hasPortalEdge(destServer, self));
                    }

                    List<String> signs = commentSigns.getOrDefault(p.id(), List.of());
                    String signsJson = signs.isEmpty() ? null : GSON.toJson(signs);

                    int i = 1;
                    ps.setString(i++, self);
                    ps.setString(i++, p.id());
                    ps.setString(i++, p.type().name());
                    ps.setString(i++, p.status().name());
                    ps.setString(i++, p.frame().world());
                    ps.setInt(i++, p.frame().x());
                    ps.setInt(i++, p.frame().y());
                    ps.setInt(i++, p.frame().z());
                    ps.setFloat(i++, p.frame().yaw());
                    ps.setString(i++, p.name());
                    ps.setString(i++, destKind);
                    ps.setString(i++, destServer);
                    ps.setString(i++, destHost);
                    if (destPort > 0) {
                        ps.setInt(i++, destPort);
                    } else {
                        ps.setNull(i++, Types.INTEGER);
                    }
                    if (destJava > 0) {
                        ps.setInt(i++, destJava);
                    } else {
                        ps.setNull(i++, Types.INTEGER);
                    }
                    ps.setString(i++, destPortal);
                    ps.setString(i++, destLabel);
                    ps.setInt(i++, ret ? 1 : 0);
                    ps.setString(i++, signsJson);
                    ps.setLong(i, now);
                    ps.addBatch();
                    n++;
                }
                if (n > 0) {
                    ps.executeBatch();
                }
            }
            try (PreparedStatement list = c.prepareStatement(
                    "SELECT portal_id FROM registry_portals WHERE server_id=?");
                 PreparedStatement del = c.prepareStatement(
                         "DELETE FROM registry_portals WHERE server_id=? AND portal_id=?")) {
                list.setString(1, self);
                try (ResultSet rs = list.executeQuery()) {
                    while (rs.next()) {
                        String id = rs.getString(1);
                        if (!keep.contains(id)) {
                            del.setString(1, self);
                            del.setString(2, id);
                            del.addBatch();
                        }
                    }
                }
                del.executeBatch();
            }
            // If local list empty — clear all for this server
            if (keep.isEmpty()) {
                try (PreparedStatement wipe = c.prepareStatement(
                        "DELETE FROM registry_portals WHERE server_id=?")) {
                    wipe.setString(1, self);
                    wipe.executeUpdate();
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Registry portal sync failed: " + e.getMessage());
            return 0;
        }
        return n;
    }

    /**
     * Hub ingest: replace {@code ownerServerId}'s portal graph from a peer catalog announce/export.
     * Only rows for that server id are touched; other servers' portals stay intact.
     */
    public int ingestPeerPortals(String ownerServerId, com.google.gson.JsonArray portals) {
        if (!enabled() || !config.registryPublishPortals()
                || ownerServerId == null || ownerServerId.isBlank()) {
            return 0;
        }
        String owner = ownerServerId.trim();
        long now = System.currentTimeMillis();
        Set<String> keep = new HashSet<>();
        int n = 0;
        String upsert = """
            INSERT INTO registry_portals(
              server_id, portal_id, portal_type, status, world, x, y, z, yaw, name,
              dest_kind, dest_server_id, dest_host, dest_port, dest_java_port, dest_portal_id,
              dest_label, return_capable, signs_json, updated_at)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            ON DUPLICATE KEY UPDATE
              portal_type=VALUES(portal_type),
              status=VALUES(status),
              world=VALUES(world),
              x=VALUES(x), y=VALUES(y), z=VALUES(z), yaw=VALUES(yaw),
              name=VALUES(name),
              dest_kind=VALUES(dest_kind),
              dest_server_id=VALUES(dest_server_id),
              dest_host=VALUES(dest_host),
              dest_port=VALUES(dest_port),
              dest_java_port=VALUES(dest_java_port),
              dest_portal_id=VALUES(dest_portal_id),
              dest_label=VALUES(dest_label),
              return_capable=VALUES(return_capable),
              signs_json=VALUES(signs_json),
              updated_at=VALUES(updated_at)
            """;
        try (Connection c = ds.getConnection()) {
            if (portals != null && portals.size() > 0) {
                try (PreparedStatement ps = c.prepareStatement(upsert)) {
                    for (com.google.gson.JsonElement el : portals) {
                        if (el == null || !el.isJsonObject()) {
                            continue;
                        }
                        com.google.gson.JsonObject o = el.getAsJsonObject();
                        String claimedOwner = o.has("serverId") && !o.get("serverId").isJsonNull()
                                ? o.get("serverId").getAsString() : owner;
                        if (!owner.equalsIgnoreCase(claimedOwner)) {
                            continue; // peers may only publish their own graph
                        }
                        String portalId = o.has("portalId") && !o.get("portalId").isJsonNull()
                                ? o.get("portalId").getAsString() : null;
                        if (portalId == null || portalId.isBlank()) {
                            continue;
                        }
                        keep.add(portalId);
                        String destKind = o.has("destKind") && !o.get("destKind").isJsonNull()
                                ? o.get("destKind").getAsString() : "none";
                        if (destKind == null || destKind.isBlank()) {
                            destKind = "none";
                        }
                        String destServer = o.has("destServerId") && !o.get("destServerId").isJsonNull()
                                ? o.get("destServerId").getAsString() : null;
                        String destHost = o.has("destHost") && !o.get("destHost").isJsonNull()
                                ? o.get("destHost").getAsString() : null;
                        int destPort = o.has("destPort") && !o.get("destPort").isJsonNull()
                                ? o.get("destPort").getAsInt() : 0;
                        int destJava = o.has("destJavaPort") && !o.get("destJavaPort").isJsonNull()
                                ? o.get("destJavaPort").getAsInt() : 0;
                        String destPortal = o.has("destPortalId") && !o.get("destPortalId").isJsonNull()
                                ? o.get("destPortalId").getAsString() : null;
                        String destLabel = o.has("destLabel") && !o.get("destLabel").isJsonNull()
                                ? o.get("destLabel").getAsString() : null;
                        if (("bound".equalsIgnoreCase(destKind) || "pair".equalsIgnoreCase(destKind))
                                && (destServer == null || destServer.isBlank())
                                && destHost != null && !destHost.isBlank()) {
                            destServer = resolveServerIdByHost(destHost, destJava > 0 ? destJava : destPort)
                                    .orElse(null);
                        }
                        boolean ret = o.has("returnCapable") && !o.get("returnCapable").isJsonNull()
                                && o.get("returnCapable").getAsBoolean();
                        String signsJson = null;
                        if (o.has("signs") && o.get("signs").isJsonArray() && o.getAsJsonArray("signs").size() > 0) {
                            signsJson = GSON.toJson(o.getAsJsonArray("signs"));
                        }
                        String world = o.has("world") && !o.get("world").isJsonNull()
                                ? o.get("world").getAsString() : "world";
                        int x = o.has("x") ? o.get("x").getAsInt() : 0;
                        int y = o.has("y") ? o.get("y").getAsInt() : 64;
                        int z = o.has("z") ? o.get("z").getAsInt() : 0;
                        float yaw = o.has("yaw") ? o.get("yaw").getAsFloat() : 0f;
                        String name = o.has("name") && !o.get("name").isJsonNull()
                                ? o.get("name").getAsString() : null;
                        String type = o.has("type") && !o.get("type").isJsonNull()
                                ? o.get("type").getAsString() : "MULTI";
                        String status = o.has("status") && !o.get("status").isJsonNull()
                                ? o.get("status").getAsString() : "ACTIVE";

                        int i = 1;
                        ps.setString(i++, owner);
                        ps.setString(i++, portalId);
                        ps.setString(i++, type);
                        ps.setString(i++, status);
                        ps.setString(i++, world);
                        ps.setInt(i++, x);
                        ps.setInt(i++, y);
                        ps.setInt(i++, z);
                        ps.setFloat(i++, yaw);
                        ps.setString(i++, name);
                        ps.setString(i++, destKind);
                        ps.setString(i++, destServer);
                        ps.setString(i++, destHost);
                        if (destPort > 0) {
                            ps.setInt(i++, destPort);
                        } else {
                            ps.setNull(i++, Types.INTEGER);
                        }
                        if (destJava > 0) {
                            ps.setInt(i++, destJava);
                        } else {
                            ps.setNull(i++, Types.INTEGER);
                        }
                        ps.setString(i++, destPortal);
                        ps.setString(i++, destLabel);
                        ps.setInt(i++, ret ? 1 : 0);
                        ps.setString(i++, signsJson);
                        ps.setLong(i, now);
                        ps.addBatch();
                        n++;
                    }
                    if (n > 0) {
                        ps.executeBatch();
                    }
                }
            }
            try (PreparedStatement list = c.prepareStatement(
                    "SELECT portal_id FROM registry_portals WHERE server_id=?");
                 PreparedStatement del = c.prepareStatement(
                         "DELETE FROM registry_portals WHERE server_id=? AND portal_id=?")) {
                list.setString(1, owner);
                try (ResultSet rs = list.executeQuery()) {
                    while (rs.next()) {
                        String id = rs.getString(1);
                        if (!keep.contains(id)) {
                            del.setString(1, owner);
                            del.setString(2, id);
                            del.addBatch();
                        }
                    }
                }
                del.executeBatch();
            }
            if (keep.isEmpty()) {
                try (PreparedStatement wipe = c.prepareStatement(
                        "DELETE FROM registry_portals WHERE server_id=?")) {
                    wipe.setString(1, owner);
                    wipe.executeUpdate();
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Registry peer portal ingest failed: " + e.getMessage());
            return 0;
        }
        return n;
    }

    public Optional<String> resolveServerIdByHost(String host, int javaPort) {
        if (!enabled() || host == null || host.isBlank()) {
            return Optional.empty();
        }
        String sql = """
            SELECT server_id FROM registry_servers
            WHERE LOWER(public_host)=LOWER(?) AND (public_port=? OR ?=0)
            ORDER BY last_heartbeat DESC LIMIT 1
            """;
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, host.trim());
            ps.setInt(2, javaPort);
            ps.setInt(3, javaPort);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.ofNullable(rs.getString(1));
                }
            }
        } catch (SQLException e) {
            return Optional.empty();
        }
        // host-only match
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(
                "SELECT server_id FROM registry_servers WHERE LOWER(public_host)=LOWER(?) ORDER BY last_heartbeat DESC LIMIT 1")) {
            ps.setString(1, host.trim());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.ofNullable(rs.getString(1));
                }
            }
        } catch (SQLException ignored) {
        }
        return Optional.empty();
    }

    public boolean hasPortalEdge(String fromServerId, String toServerId) {
        if (!enabled() || fromServerId == null || toServerId == null) {
            return false;
        }
        String sql = """
            SELECT 1 FROM registry_portals
            WHERE server_id=? AND dest_server_id=? AND status='ACTIVE'
            LIMIT 1
            """;
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, fromServerId);
            ps.setString(2, toServerId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            return false;
        }
    }

    public List<RegistryPortal> listPortals(int limit) {
        if (!enabled()) {
            return List.of();
        }
        List<RegistryPortal> out = new ArrayList<>();
        String sql = """
            SELECT * FROM registry_portals
            ORDER BY server_id, world, x, z
            LIMIT ?
            """;
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, Math.max(1, limit));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(mapPortal(rs));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Registry listPortals failed: " + e.getMessage());
        }
        return out;
    }

    public List<RegistryPortal> listPortalsOnServer(String serverId) {
        if (!enabled() || serverId == null) {
            return List.of();
        }
        List<RegistryPortal> out = new ArrayList<>();
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM registry_portals WHERE server_id=? ORDER BY world, x, z")) {
            ps.setString(1, serverId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(mapPortal(rs));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Registry listPortalsOnServer failed: " + e.getMessage());
        }
        return out;
    }

    /** Edges that can send a player TO this server (return paths / inbound). */
    public List<RegistryPortal> listInboundTo(String serverId) {
        if (!enabled() || serverId == null) {
            return List.of();
        }
        List<RegistryPortal> out = new ArrayList<>();
        String sql = """
            SELECT * FROM registry_portals
            WHERE dest_server_id=? AND status='ACTIVE'
            ORDER BY server_id, updated_at DESC
            """;
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, serverId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(mapPortal(rs));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Registry listInboundTo failed: " + e.getMessage());
        }
        return out;
    }

    private static RegistryPortal mapPortal(ResultSet rs) throws SQLException {
        return new RegistryPortal(
                rs.getString("server_id"),
                rs.getString("portal_id"),
                rs.getString("portal_type"),
                rs.getString("status"),
                rs.getString("world"),
                rs.getInt("x"),
                rs.getInt("y"),
                rs.getInt("z"),
                rs.getFloat("yaw"),
                rs.getString("name"),
                rs.getString("dest_kind"),
                rs.getString("dest_server_id"),
                rs.getString("dest_host"),
                colIntObj(rs, "dest_port"),
                colIntObj(rs, "dest_java_port"),
                rs.getString("dest_portal_id"),
                rs.getString("dest_label"),
                rs.getInt("return_capable") == 1,
                parseSignsJson(colString(rs, "signs_json")),
                rs.getLong("updated_at")
        );
    }

    private static String colString(ResultSet rs, String col) {
        try {
            return rs.getString(col);
        } catch (SQLException e) {
            return null;
        }
    }

    private static List<String> parseSignsJson(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            JsonArray arr = JsonParser.parseString(json).getAsJsonArray();
            List<String> out = new ArrayList<>();
            for (JsonElement el : arr) {
                if (el != null && el.isJsonPrimitive()) {
                    String s = el.getAsString();
                    if (s != null && !s.isBlank()) {
                        out.add(s);
                    }
                }
            }
            return out;
        } catch (Exception e) {
            try {
                List<String> typed = GSON.fromJson(json, new TypeToken<List<String>>(){}.getType());
                return typed == null ? List.of() : typed;
            } catch (Exception ignored) {
                return List.of();
            }
        }
    }

    private static int colIntObj(ResultSet rs, String col) throws SQLException {
        Object o = rs.getObject(col);
        return o == null ? 0 : ((Number) o).intValue();
    }

    public void setMultiOptIn(boolean optIn) {
        if (!enabled()) {
            return;
        }
        long now = System.currentTimeMillis();
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(
                "UPDATE registry_servers SET multi_opt_in=?, last_heartbeat=?, last_online_at=? WHERE server_id=?")) {
            ps.setInt(1, optIn ? 1 : 0);
            ps.setLong(2, now);
            ps.setLong(3, now);
            ps.setString(4, config.serverId());
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().warning("Registry opt-in update failed: " + e.getMessage());
        }
    }

    /** Alive multi-capable servers excluding self — sparsest hub mesh first (fewest portal links). */
    public List<RegistryServer> listMultiTargets(long maxAgeMs) {
        if (!enabled()) {
            return List.of();
        }
        long minHb = System.currentTimeMillis() - maxAgeMs;
        // Prefer servers with fewer unique hub↔hub portal edges so the mesh fills in faster.
        String sql = """
            SELECT s.* FROM registry_servers s
            LEFT JOIN (
              SELECT peer_id AS server_id, COUNT(DISTINCT other_id) AS degree
              FROM (
                SELECT server_id AS peer_id, dest_server_id AS other_id
                FROM registry_portals
                WHERE dest_server_id IS NOT NULL AND dest_server_id <> ''
                  AND dest_server_id <> server_id
                  AND status = 'ACTIVE'
                UNION
                SELECT dest_server_id AS peer_id, server_id AS other_id
                FROM registry_portals
                WHERE dest_server_id IS NOT NULL AND dest_server_id <> ''
                  AND dest_server_id <> server_id
                  AND status = 'ACTIVE'
                UNION
                SELECT p.server_id AS peer_id, s2.server_id AS other_id
                FROM registry_portals p
                INNER JOIN registry_servers s2
                  ON LOWER(s2.public_host) = LOWER(p.dest_host)
                 AND (p.dest_java_port IS NULL OR p.dest_java_port <= 0 OR p.dest_java_port = s2.public_port)
                WHERE p.dest_host IS NOT NULL AND p.dest_host <> ''
                  AND (p.dest_server_id IS NULL OR p.dest_server_id = '')
                  AND s2.server_id <> p.server_id
                  AND p.status = 'ACTIVE'
                UNION
                SELECT s2.server_id AS peer_id, p.server_id AS other_id
                FROM registry_portals p
                INNER JOIN registry_servers s2
                  ON LOWER(s2.public_host) = LOWER(p.dest_host)
                 AND (p.dest_java_port IS NULL OR p.dest_java_port <= 0 OR p.dest_java_port = s2.public_port)
                WHERE p.dest_host IS NOT NULL AND p.dest_host <> ''
                  AND (p.dest_server_id IS NULL OR p.dest_server_id = '')
                  AND s2.server_id <> p.server_id
                  AND p.status = 'ACTIVE'
              ) edges
              GROUP BY peer_id
            ) d ON d.server_id = s.server_id
            WHERE s.multi_opt_in=1 AND s.accept_transfers=1 AND s.last_heartbeat>=?
              AND s.server_id<>?
            ORDER BY COALESCE(d.degree, 0) ASC, s.last_heartbeat DESC
            """;
        List<RegistryServer> list = new ArrayList<>();
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, minHb);
            ps.setString(2, config.serverId());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(map(rs));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Registry list failed: " + e.getMessage());
        }
        return list;
    }

    /**
     * Unique hub peers each server is linked to via ACTIVE portal edges (undirected).
     * Missing key ⇒ 0 links.
     */
    public Map<String, Integer> hubLinkDegrees() {
        if (!enabled()) {
            return Map.of();
        }
        String sql = """
            SELECT peer_id, COUNT(DISTINCT other_id) AS degree
            FROM (
              SELECT server_id AS peer_id, dest_server_id AS other_id
              FROM registry_portals
              WHERE dest_server_id IS NOT NULL AND dest_server_id <> ''
                AND dest_server_id <> server_id AND status = 'ACTIVE'
              UNION
              SELECT dest_server_id AS peer_id, server_id AS other_id
              FROM registry_portals
              WHERE dest_server_id IS NOT NULL AND dest_server_id <> ''
                AND dest_server_id <> server_id AND status = 'ACTIVE'
              UNION
              SELECT p.server_id AS peer_id, s2.server_id AS other_id
              FROM registry_portals p
              INNER JOIN registry_servers s2
                ON LOWER(s2.public_host) = LOWER(p.dest_host)
               AND (p.dest_java_port IS NULL OR p.dest_java_port <= 0 OR p.dest_java_port = s2.public_port)
              WHERE p.dest_host IS NOT NULL AND p.dest_host <> ''
                AND (p.dest_server_id IS NULL OR p.dest_server_id = '')
                AND s2.server_id <> p.server_id AND p.status = 'ACTIVE'
              UNION
              SELECT s2.server_id AS peer_id, p.server_id AS other_id
              FROM registry_portals p
              INNER JOIN registry_servers s2
                ON LOWER(s2.public_host) = LOWER(p.dest_host)
               AND (p.dest_java_port IS NULL OR p.dest_java_port <= 0 OR p.dest_java_port = s2.public_port)
              WHERE p.dest_host IS NOT NULL AND p.dest_host <> ''
                AND (p.dest_server_id IS NULL OR p.dest_server_id = '')
                AND s2.server_id <> p.server_id AND p.status = 'ACTIVE'
            ) edges
            GROUP BY peer_id
            """;
        Map<String, Integer> out = new HashMap<>();
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.put(rs.getString("peer_id"), rs.getInt("degree"));
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("hubLinkDegrees failed: " + e.getMessage());
        }
        return out;
    }

    /** Catalog / known_mvp score: fewer hub links → higher priority (max 100). */
    public static double sparseHubScore(int degree) {
        return Math.max(20.0, 100.0 - Math.max(0, degree) * 12.0);
    }

    public List<RegistryServer> listAll(long maxAgeMs) {
        if (!enabled()) {
            return List.of();
        }
        long minHb = System.currentTimeMillis() - maxAgeMs;
        String sql = "SELECT * FROM registry_servers WHERE last_heartbeat>=? ORDER BY last_heartbeat DESC";
        List<RegistryServer> list = new ArrayList<>();
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, minHb);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(map(rs));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Registry listAll failed: " + e.getMessage());
        }
        return list;
    }

    /** Admin directory: all rows including stale, newest ping first. */
    public List<RegistryServer> listAllAny(int limit) {
        if (!enabled()) {
            return List.of();
        }
        String sql = "SELECT * FROM registry_servers ORDER BY last_heartbeat DESC LIMIT ?";
        List<RegistryServer> list = new ArrayList<>();
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, Math.max(1, limit));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(map(rs));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Registry listAllAny failed: " + e.getMessage());
        }
        return list;
    }

    public Optional<RegistryServer> find(String serverId) {
        if (!enabled()) {
            return Optional.empty();
        }
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM registry_servers WHERE server_id=?")) {
            ps.setString(1, serverId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(map(rs));
            }
        } catch (SQLException e) {
            return Optional.empty();
        }
    }

    private static RegistryServer map(ResultSet rs) throws SQLException {
        String mc = rs.getString("mc_version");
        if (mc == null || mc.isBlank()) {
            mc = rs.getString("protocol_version");
        }
        int protocolId = 0;
        try {
            Object o = rs.getObject("protocol_id");
            if (o != null) {
                protocolId = ((Number) o).intValue();
            }
        } catch (SQLException ignored) {
        }
        boolean viaV = colBool(rs, "has_via_version");
        boolean viaB = colBool(rs, "has_via_backwards");
        boolean viaR = colBool(rs, "has_via_rewind");
        int viaMin = colInt(rs, "via_min", protocolId);
        int viaMax = colInt(rs, "via_max", protocolId);
        ServerCaps caps = new ServerCaps(
                colStr(rs, "mvp_version"),
                colBool(rs, "has_geyser"),
                colBool(rs, "has_floodgate"),
                colStr(rs, "geyser_version"),
                colInt(rs, "bedrock_port", 0),
                colInt(rs, "bedrock_protocol", 0),
                colStr(rs, "bedrock_version"),
                colBool(rs, "accept_bedrock"),
                colBoolDefault(rs, "ingress_enabled", true),
                colInt(rs, "ingress_max_online", 0),
                colInt(rs, "ingress_reserve", 0),
                colDouble(rs, "ingress_min_score", 0),
                colBool(rs, "ingress_deny_unknown"),
                colInt(rs, "ingress_max_per_hour", 0),
                colBool(rs, "export_inventory"),
                colBool(rs, "import_inventory")
        );
        boolean hasIcon = false;
        try {
            byte[] icon = rs.getBytes("icon_png");
            hasIcon = icon != null && icon.length > 0;
        } catch (SQLException ignored) {
        }
        String description = colStr(rs, "description");
        return new RegistryServer(
                rs.getString("server_id"),
                rs.getString("display_name"),
                rs.getString("public_host"),
                rs.getInt("public_port"),
                rs.getString("federation_url"),
                rs.getInt("has_plugin") == 1,
                rs.getInt("multi_opt_in") == 1,
                rs.getInt("accept_transfers") == 1,
                rs.getString("tags"),
                rs.getString("motd"),
                description,
                hasIcon,
                (Integer) rs.getObject("max_players"),
                (Integer) rs.getObject("online_players"),
                mc,
                protocolId,
                viaV,
                viaB,
                viaR,
                viaMin,
                viaMax,
                rs.getLong("last_heartbeat"),
                colLong(rs, "created_at", 0L),
                colLong(rs, "last_online_at", rs.getLong("last_heartbeat")),
                colLong(rs, "last_offline_at", 0L),
                caps
        );
    }

    private static long colLong(ResultSet rs, String col, long def) {
        try {
            Object o = rs.getObject(col);
            if (o == null) {
                return def;
            }
            return ((Number) o).longValue();
        } catch (SQLException e) {
            return def;
        }
    }

    private static boolean colBool(ResultSet rs, String col) {
        try {
            return rs.getInt(col) == 1;
        } catch (SQLException e) {
            return false;
        }
    }

    private static boolean colBoolDefault(ResultSet rs, String col, boolean def) {
        try {
            Object o = rs.getObject(col);
            if (o == null) {
                return def;
            }
            return rs.getInt(col) == 1;
        } catch (SQLException e) {
            return def;
        }
    }

    private static int colInt(ResultSet rs, String col, int def) {
        try {
            Object o = rs.getObject(col);
            return o == null ? def : ((Number) o).intValue();
        } catch (SQLException e) {
            return def;
        }
    }

    private static double colDouble(ResultSet rs, String col, double def) {
        try {
            Object o = rs.getObject(col);
            return o == null ? def : ((Number) o).doubleValue();
        } catch (SQLException e) {
            return def;
        }
    }

    private static String colStr(ResultSet rs, String col) {
        try {
            String s = rs.getString(col);
            return s == null ? "" : s;
        } catch (SQLException e) {
            return "";
        }
    }

    public void publishPairInvite(String code, String portalId, String world, int x, int y, int z, long ttlMs) {
        if (!enabled()) {
            return;
        }
        long now = System.currentTimeMillis();
        String sql = """
            INSERT INTO registry_pair_invites(
              invite_code, host_server_id, host_portal_id, host_world, host_x, host_y, host_z, created_at, expires_at)
            VALUES (?,?,?,?,?,?,?,?,?)
            ON DUPLICATE KEY UPDATE host_portal_id=VALUES(host_portal_id), expires_at=VALUES(expires_at)
            """;
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, code);
            ps.setString(2, config.serverId());
            ps.setString(3, portalId);
            ps.setString(4, world);
            ps.setInt(5, x);
            ps.setInt(6, y);
            ps.setInt(7, z);
            ps.setLong(8, now);
            ps.setLong(9, now + ttlMs);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().warning("publishPairInvite: " + e.getMessage());
        }
    }

    public Optional<PairInvite> claimPairInvite(String code, String claimerPortalId) {
        if (!enabled()) {
            return Optional.empty();
        }
        long now = System.currentTimeMillis();
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement sel = c.prepareStatement(
                    "SELECT * FROM registry_pair_invites WHERE invite_code=? FOR UPDATE")) {
                sel.setString(1, code.toUpperCase());
                try (ResultSet rs = sel.executeQuery()) {
                    if (!rs.next()) {
                        c.rollback();
                        return Optional.empty();
                    }
                    if (rs.getLong("expires_at") < now) {
                        c.rollback();
                        return Optional.empty();
                    }
                    if (rs.getString("claimed_by_server") != null) {
                        c.rollback();
                        return Optional.empty();
                    }
                    PairInvite invite = new PairInvite(
                            rs.getString("invite_code"),
                            rs.getString("host_server_id"),
                            rs.getString("host_portal_id"),
                            rs.getString("host_world"),
                            rs.getInt("host_x"),
                            rs.getInt("host_y"),
                            rs.getInt("host_z")
                    );
                    try (PreparedStatement upd = c.prepareStatement(
                            "UPDATE registry_pair_invites SET claimed_by_server=?, claimed_portal_id=? WHERE invite_code=?")) {
                        upd.setString(1, config.serverId());
                        upd.setString(2, claimerPortalId);
                        upd.setString(3, code.toUpperCase());
                        upd.executeUpdate();
                    }
                    c.commit();
                    return Optional.of(invite);
                }
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("claimPairInvite: " + e.getMessage());
            return Optional.empty();
        }
    }

    public void saveTravel(String sessionId, String playerUuid, String from, String to, String toPortalId,
                           String portalType, boolean carry, String invB64, Double score, long ttlMs,
                           boolean landingReturn) {
        saveTravel(sessionId, playerUuid, from, to, toPortalId, portalType, carry, invB64, score, ttlMs,
                landingReturn, null, 0);
    }

    public void saveTravel(String sessionId, String playerUuid, String from, String to, String toPortalId,
                           String portalType, boolean carry, String invB64, Double score, long ttlMs,
                           boolean landingReturn, String destHost, int destJavaPort) {
        if (!enabled()) {
            return;
        }
        long now = System.currentTimeMillis();
        String sql = """
            INSERT INTO registry_travel(
              session_id, player_uuid, from_server, to_server, to_portal_id, portal_type,
              carry_inventory, inventory_b64, score, status, created_at, expires_at, landing_return,
              dest_host, dest_java_port)
            VALUES (?,?,?,?,?,?,?,?,?,'PENDING',?,?,?,?,?)
            ON DUPLICATE KEY UPDATE status='PENDING', expires_at=VALUES(expires_at),
              to_portal_id=VALUES(to_portal_id), landing_return=VALUES(landing_return),
              dest_host=COALESCE(VALUES(dest_host), dest_host),
              dest_java_port=COALESCE(VALUES(dest_java_port), dest_java_port)
            """;
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, sessionId);
            ps.setString(2, playerUuid);
            ps.setString(3, from);
            ps.setString(4, to);
            ps.setString(5, toPortalId);
            ps.setString(6, portalType);
            ps.setInt(7, carry ? 1 : 0);
            ps.setString(8, invB64);
            if (score == null) {
                ps.setNull(9, Types.DOUBLE);
            } else {
                ps.setDouble(9, score);
            }
            ps.setLong(10, now);
            ps.setLong(11, now + ttlMs);
            ps.setInt(12, landingReturn ? 1 : 0);
            if (destHost == null || destHost.isBlank() || destJavaPort <= 0) {
                ps.setNull(13, Types.VARCHAR);
                ps.setNull(14, Types.INTEGER);
            } else {
                ps.setString(13, destHost.trim().toLowerCase(java.util.Locale.ROOT));
                ps.setInt(14, destJavaPort);
            }
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().warning("saveTravel: " + e.getMessage());
        }
    }

    /**
     * Hub catalog score is driven by Transfer outcomes (not SLP probes).
     * success → +transferSuccessScore / success_count++; fail (bounce) → −transferFailScore / fail_count++ / BAD_JOIN.
     */
    public void recordTransferOutcome(String host, int javaPort, boolean success, String source) {
        if (!enabled() || host == null || host.isBlank() || javaPort <= 0) {
            return;
        }
        long now = System.currentTimeMillis();
        String h = host.trim().toLowerCase(java.util.Locale.ROOT);
        double delta = success ? transferSuccessScore() : -transferFailScore();
        String st = success ? "OK" : "BAD_JOIN";
        String src = source == null || source.isBlank() ? (success ? "travel-ok" : "bounce") : source;
        String sql = """
            INSERT INTO registry_scanner_hosts(
              host, java_port, mc_version, version_branch, score, status,
              bedrock_port, bedrock_protocol, bedrock_version, display_name, source,
              success_count, fail_count, last_ok_at, last_fail_at, last_seen_at, updated_at)
            VALUES(?,?,NULL,NULL,?,?,NULL,NULL,NULL,NULL,?,?,?,?,?,?,?)
            ON DUPLICATE KEY UPDATE
              status=VALUES(status),
              source=COALESCE(VALUES(source), source),
              success_count=success_count + VALUES(success_count),
              fail_count=fail_count + VALUES(fail_count),
              last_ok_at=CASE WHEN VALUES(success_count)>0 THEN VALUES(last_ok_at) ELSE last_ok_at END,
              last_fail_at=CASE WHEN VALUES(fail_count)>0 THEN VALUES(last_fail_at) ELSE last_fail_at END,
              last_seen_at=GREATEST(COALESCE(last_seen_at,0), COALESCE(VALUES(last_seen_at),0)),
              score=GREATEST(-100, LEAST(250, COALESCE(score,0) + VALUES(score))),
              updated_at=VALUES(updated_at)
            """;
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, h);
            ps.setInt(2, javaPort);
            ps.setDouble(3, delta);
            ps.setString(4, st);
            ps.setString(5, src);
            ps.setInt(6, success ? 1 : 0);
            ps.setInt(7, success ? 0 : 1);
            if (success) {
                ps.setLong(8, now);
                ps.setNull(9, Types.BIGINT);
            } else {
                ps.setNull(8, Types.BIGINT);
                ps.setLong(9, now);
            }
            ps.setLong(10, now);
            ps.setLong(11, now);
            ps.executeUpdate();
            plugin.getLogger().info("hub transfer-score " + (success ? "OK" : "FAIL")
                    + " " + h + ":" + javaPort + " delta=" + delta + " src=" + src);
        } catch (SQLException e) {
            plugin.getLogger().warning("recordTransferOutcome: " + e.getMessage());
        }
    }

    private double transferSuccessScore() {
        return Math.max(0.0, plugin.getConfig().getDouble("scanner.hub-pool.transfer-success-score", 20.0));
    }

    private double transferFailScore() {
        return Math.max(0.0, plugin.getConfig().getDouble("scanner.hub-pool.transfer-fail-score", 40.0));
    }

    /**
     * Origin detected bounce-back: player rejoined soon after Transfer — mark hop failed.
     * Does not require knowing the Minecraft disconnect reason.
     */
    public void markTravelBounced(String sessionId) {
        if (!enabled() || sessionId == null || sessionId.isBlank()) {
            return;
        }
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement("""
                UPDATE registry_travel
                SET status='BOUNCED'
                WHERE session_id=? AND status IN ('PENDING','ARRIVED')
                """)) {
            ps.setString(1, sessionId.trim());
            int n = ps.executeUpdate();
            if (n > 0) {
                plugin.getLogger().info("registry_travel BOUNCED session=" + sessionId);
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("markTravelBounced: " + e.getMessage());
        }
    }

    /** Fallback when session id unknown: latest pending hop from this origin for the player. */
    public void markRecentTravelBounced(String playerUuid, String fromServerId, long withinMs) {
        if (!enabled() || playerUuid == null || playerUuid.isBlank()) {
            return;
        }
        long cutoff = System.currentTimeMillis() - Math.max(1_000L, withinMs);
        String from = fromServerId == null ? "" : fromServerId.trim();
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement("""
                UPDATE registry_travel
                SET status='BOUNCED'
                WHERE player_uuid=? AND status IN ('PENDING','ARRIVED') AND created_at>=?
                  AND (?='' OR from_server=?)
                ORDER BY created_at DESC
                LIMIT 1
                """)) {
            ps.setString(1, playerUuid);
            ps.setLong(2, cutoff);
            ps.setString(3, from);
            ps.setString(4, from);
            int n = ps.executeUpdate();
            if (n > 0) {
                plugin.getLogger().info("registry_travel BOUNCED player=" + playerUuid
                        + " from=" + (from.isEmpty() ? "?" : from));
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("markRecentTravelBounced: " + e.getMessage());
        }
    }

    public Optional<RegistryTravel> takePendingTravel(String playerUuid) {
        return takePendingTravel(playerUuid, config.serverId());
    }

    /**
     * Consume PENDING inbound travel for a destination server id
     * (local join, or hub /travel/claim for leaf servers without MySQL).
     */
    public Optional<RegistryTravel> takePendingTravel(String playerUuid, String toServerId) {
        if (!enabled() || playerUuid == null || playerUuid.isBlank()
                || toServerId == null || toServerId.isBlank()) {
            return Optional.empty();
        }
        long now = System.currentTimeMillis();
        String sql = """
            SELECT * FROM registry_travel
            WHERE player_uuid=? AND status='PENDING' AND to_server=? AND expires_at>=?
            ORDER BY created_at DESC LIMIT 1
            """;
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, playerUuid);
            ps.setString(2, toServerId);
            ps.setLong(3, now);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                String destHost = null;
                int destPort = 0;
                try {
                    destHost = rs.getString("dest_host");
                    Object dp = rs.getObject("dest_java_port");
                    if (dp != null) {
                        destPort = ((Number) dp).intValue();
                    }
                } catch (SQLException ignored) {
                    // columns may not exist yet on very old hubs — alterIgnore adds them on boot
                }
                RegistryTravel t = new RegistryTravel(
                        rs.getString("session_id"),
                        rs.getString("player_uuid"),
                        rs.getString("from_server"),
                        rs.getString("to_server"),
                        rs.getString("to_portal_id"),
                        rs.getString("portal_type"),
                        rs.getInt("carry_inventory") == 1,
                        rs.getString("inventory_b64"),
                        rs.getObject("score") == null ? null : rs.getDouble("score"),
                        colBoolDefault(rs, "landing_return", false)
                );
                try (PreparedStatement upd = c.prepareStatement(
                        "UPDATE registry_travel SET status='ARRIVED' WHERE session_id=?")) {
                    upd.setString(1, t.sessionId());
                    upd.executeUpdate();
                }
                // Successful Transfer → hub catalog score for destination
                String scoreHost = destHost;
                int scorePort = destPort;
                if (scoreHost == null || scoreHost.isBlank() || scorePort <= 0) {
                    scoreHost = config.publicHost();
                    scorePort = config.publicPort() > 0 ? config.publicPort() : 25565;
                }
                if (scoreHost != null && !scoreHost.isBlank() && scorePort > 0) {
                    recordTransferOutcome(scoreHost, scorePort, true, "travel-arrived");
                }
                return Optional.of(t);
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("takePendingTravel: " + e.getMessage());
            return Optional.empty();
        }
    }

    /** Historical arrivals to this server recorded in the hub registry. */
    public long countArrivalsToThisServer() {
        if (!enabled()) {
            return 0L;
        }
        String sql = """
            SELECT COUNT(*) FROM registry_travel
            WHERE to_server=? AND status IN ('ARRIVED','RETURNED')
            """;
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, config.serverId());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("countArrivalsToThisServer: " + e.getMessage());
            return 0L;
        }
    }

    /** Invites we hosted that were claimed by the other side — activate PAIR on host. */
    public List<ClaimedPair> listClaimedForHost() {
        if (!enabled()) {
            return List.of();
        }
        String sql = """
            SELECT invite_code, host_portal_id, claimed_by_server, claimed_portal_id
            FROM registry_pair_invites
            WHERE host_server_id=? AND claimed_by_server IS NOT NULL
            """;
        List<ClaimedPair> list = new ArrayList<>();
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, config.serverId());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(new ClaimedPair(
                            rs.getString("invite_code"),
                            rs.getString("host_portal_id"),
                            rs.getString("claimed_by_server"),
                            rs.getString("claimed_portal_id")
                    ));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("listClaimedForHost: " + e.getMessage());
        }
        return list;
    }

    public record PairInvite(
            String code, String hostServerId, String hostPortalId,
            String hostWorld, int hostX, int hostY, int hostZ
    ) {}

    public record ClaimedPair(
            String code, String hostPortalId, String claimedByServer, String claimedPortalId
    ) {}

    public record RegistryTravel(
            String sessionId, String playerUuid, String fromServer, String toServer,
            String toPortalId, String portalType, boolean carry, String inventoryB64, Double score,
            boolean landingReturn
    ) {}

    // --- Central public-scanner pool (not MVP club) ---

    public record ScannerHost(
            String host,
            int javaPort,
            String mcVersion,
            String versionBranch,
            double score,
            String status,
            int bedrockPort,
            int bedrockProtocol,
            String bedrockVersion,
            Integer onlinePlayers,
            Integer maxPlayers,
            String displayName,
            String motd,
            String source,
            int successCount,
            int failCount,
            long lastOkAt,
            long lastFailAt,
            long lastSeenAt,
            long updatedAt
    ) {}

    public int upsertScannerFromScanned(List<io.multiverseportals.scanner.ScannedServer> servers) {
        if (!enabled() || servers == null || servers.isEmpty()) {
            return 0;
        }
        long now = System.currentTimeMillis();
        String sql = """
            INSERT INTO registry_scanner_hosts(
              host, java_port, mc_version, version_branch, score, status,
              online_players, max_players, display_name, motd, source,
              success_count, fail_count, last_ok_at, last_fail_at, last_seen_at, updated_at)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,0,0,NULL,NULL,?,?)
            ON DUPLICATE KEY UPDATE
              mc_version=COALESCE(VALUES(mc_version), mc_version),
              version_branch=COALESCE(VALUES(version_branch), version_branch),
              online_players=COALESCE(VALUES(online_players), online_players),
              max_players=COALESCE(VALUES(max_players), max_players),
              display_name=COALESCE(VALUES(display_name), display_name),
              motd=COALESCE(VALUES(motd), motd),
              source=COALESCE(VALUES(source), source),
              last_seen_at=GREATEST(COALESCE(last_seen_at,0), COALESCE(VALUES(last_seen_at),0)),
              score=score,
              status=CASE
                WHEN status='OK' THEN 'OK'
                WHEN status IN ('DEAD','BAD_JOIN') AND last_fail_at IS NOT NULL
                  AND last_fail_at >= ? THEN status
                ELSE 'SEEN'
              END,
              updated_at=VALUES(updated_at)
            """;
        int n = 0;
        long deadCutoff = now - 900_000L;
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (var s : servers) {
                if (s == null || s.host() == null || s.host().isBlank() || s.port() <= 0) {
                    continue;
                }
                String h = s.host().trim().toLowerCase(java.util.Locale.ROOT);
                String ver = s.version();
                long seen = s.lastSeenMs() > 0 ? s.lastSeenMs() : now;
                ps.setString(1, h);
                ps.setInt(2, s.port());
                ps.setString(3, ver);
                ps.setString(4, versionBranchOf(ver));
                ps.setDouble(5, 8.0);
                ps.setString(6, "SEEN");
                if (s.onlinePlayers() >= 0) {
                    ps.setInt(7, s.onlinePlayers());
                } else {
                    ps.setNull(7, Types.INTEGER);
                }
                if (s.maxPlayers() >= 0) {
                    ps.setInt(8, s.maxPlayers());
                } else {
                    ps.setNull(8, Types.INTEGER);
                }
                ps.setString(9, s.labelForSign());
                ps.setString(10, s.motd());
                ps.setString(11, s.source());
                ps.setLong(12, seen);
                ps.setLong(13, now);
                ps.setLong(14, deadCutoff);
                ps.addBatch();
                n++;
            }
            ps.executeBatch();
        } catch (SQLException e) {
            plugin.getLogger().warning("upsertScannerFromScanned: " + e.getMessage());
            return 0;
        }
        return n;
    }

    public void upsertScannerProbeReport(
            String host,
            int javaPort,
            String status,
            String mcVersion,
            Integer bedrockPort,
            Integer bedrockProtocol,
            String bedrockVersion,
            String displayName,
            String source,
            Double scoreDelta
    ) {
        if (!enabled() || host == null || host.isBlank() || javaPort <= 0 || status == null) {
            return;
        }
        long now = System.currentTimeMillis();
        String h = host.trim().toLowerCase(java.util.Locale.ROOT);
        String st = status.trim().toUpperCase(java.util.Locale.ROOT);
        boolean ok = "OK".equals(st);
        // Probe reports refresh liveness only — catalog score comes from Transfer outcomes.
        boolean probeAffectsScore = plugin.getConfig().getBoolean("scanner.hub-pool.probe-affects-score", false);
        double delta = 0.0;
        if (scoreDelta != null) {
            delta = scoreDelta;
        } else if (probeAffectsScore) {
            delta = scannerScoreDelta(st);
            if (ok && bedrockPort != null && bedrockPort > 0) {
                delta += 5;
            }
        }
        String sql = """
            INSERT INTO registry_scanner_hosts(
              host, java_port, mc_version, version_branch, score, status,
              bedrock_port, bedrock_protocol, bedrock_version, display_name, source,
              success_count, fail_count, last_ok_at, last_fail_at, last_seen_at, updated_at)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            ON DUPLICATE KEY UPDATE
              mc_version=COALESCE(VALUES(mc_version), mc_version),
              version_branch=COALESCE(VALUES(version_branch), version_branch),
              status=CASE
                WHEN VALUES(status) IN ('OK','SEEN')
                  AND status='BAD_JOIN'
                  AND last_fail_at IS NOT NULL
                  AND last_fail_at >= (VALUES(updated_at) - 900000)
                THEN status
                ELSE VALUES(status)
              END,
              bedrock_port=COALESCE(VALUES(bedrock_port), bedrock_port),
              bedrock_protocol=COALESCE(VALUES(bedrock_protocol), bedrock_protocol),
              bedrock_version=COALESCE(VALUES(bedrock_version), bedrock_version),
              display_name=COALESCE(VALUES(display_name), display_name),
              source=COALESCE(VALUES(source), source),
              last_ok_at=CASE WHEN VALUES(status)='OK' THEN VALUES(last_ok_at) ELSE last_ok_at END,
              last_fail_at=CASE
                WHEN VALUES(status) IN ('DEAD','BAD_JOIN','BAD_PROTO','NO_GEYSER') THEN VALUES(last_fail_at)
                ELSE last_fail_at
              END,
              last_seen_at=GREATEST(COALESCE(last_seen_at,0), COALESCE(VALUES(last_seen_at),0)),
              score=GREATEST(-100, LEAST(250, COALESCE(score,0) + VALUES(score))),
              updated_at=VALUES(updated_at)
            """;
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, h);
            ps.setInt(2, javaPort);
            ps.setString(3, mcVersion);
            ps.setString(4, versionBranchOf(mcVersion));
            ps.setDouble(5, delta);
            ps.setString(6, st);
            if (bedrockPort != null && bedrockPort > 0) {
                ps.setInt(7, bedrockPort);
            } else {
                ps.setNull(7, Types.INTEGER);
            }
            if (bedrockProtocol != null && bedrockProtocol > 0) {
                ps.setInt(8, bedrockProtocol);
            } else {
                ps.setNull(8, Types.INTEGER);
            }
            ps.setString(9, bedrockVersion);
            ps.setString(10, displayName);
            ps.setString(11, source == null || source.isBlank() ? "leaf" : source);
            // Probe must not inflate transfer success/fail counters
            ps.setInt(12, 0);
            ps.setInt(13, 0);
            if (ok) {
                ps.setLong(14, now);
            } else {
                ps.setNull(14, Types.BIGINT);
            }
            if ("DEAD".equals(st) || "BAD_JOIN".equals(st) || "BAD_PROTO".equals(st) || "NO_GEYSER".equals(st)) {
                ps.setLong(15, now);
            } else {
                ps.setNull(15, Types.BIGINT);
            }
            ps.setLong(16, now);
            ps.setLong(17, now);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().warning("upsertScannerProbeReport: " + e.getMessage());
        }
    }

    public List<ScannerHost> queryScannerCandidates(
            String versionBranch,
            boolean needBedrock,
            int bedrockProtocol,
            int limit,
            double minScore,
            Set<String> excludeHosts
    ) {
        if (!enabled()) {
            return List.of();
        }
        int lim = Math.max(1, Math.min(200, limit <= 0 ? 40 : limit));
        long now = System.currentTimeMillis();
        long staleFail = now - 900_000L;
        String branch = versionBranch == null ? "" : versionBranch.trim();
        StringBuilder sql = new StringBuilder("""
            SELECT host, java_port, mc_version, version_branch, score, status,
                   bedrock_port, bedrock_protocol, bedrock_version,
                   online_players, max_players, display_name, motd, source,
                   success_count, fail_count, last_ok_at, last_fail_at, last_seen_at, updated_at
            FROM registry_scanner_hosts
            WHERE score >= ?
              AND NOT (status='DEAD' AND last_fail_at IS NOT NULL AND last_fail_at >= ?)
            """);
        if (!branch.isEmpty()) {
            sql.append(" AND (version_branch IS NULL OR version_branch='' OR version_branch=?)");
        }
        if (needBedrock) {
            sql.append(" AND bedrock_port IS NOT NULL AND bedrock_port > 0");
            if (bedrockProtocol > 0) {
                sql.append(" AND (bedrock_protocol IS NULL OR bedrock_protocol=0 OR bedrock_protocol=?)");
            }
        }
        // Soft rank: low transfer-score (incl. BAD_JOIN after bounce) comes later — never hard-excluded.
        sql.append(" ORDER BY CASE WHEN bedrock_port IS NOT NULL AND bedrock_port > 0 THEN 1 ELSE 0 END DESC,")
                .append(" score DESC,")
                .append(" CASE WHEN status='BAD_JOIN' THEN 1 ELSE 0 END ASC,")
                .append(" COALESCE(last_ok_at,0) DESC, COALESCE(last_seen_at,0) DESC")
                .append(" LIMIT ?");

        List<ScannerHost> out = new ArrayList<>();
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql.toString())) {
            int i = 1;
            ps.setDouble(i++, minScore);
            ps.setLong(i++, staleFail);
            if (!branch.isEmpty()) {
                ps.setString(i++, branch);
            }
            if (needBedrock && bedrockProtocol > 0) {
                ps.setInt(i++, bedrockProtocol);
            }
            ps.setInt(i, Math.max(lim * 3, lim));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ScannerHost row = mapScannerHost(rs);
                    String key = row.host() + ":" + row.javaPort();
                    if (excludeHosts != null && (excludeHosts.contains(key)
                            || excludeHosts.contains(row.host()))) {
                        continue;
                    }
                    out.add(row);
                    if (out.size() >= lim) {
                        break;
                    }
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("queryScannerCandidates: " + e.getMessage());
        }
        return out;
    }

    /** Top rows for hub re-probe (freshest / highest score). */
    public List<ScannerHost> listScannerHostsForProbe(int limit) {
        if (!enabled()) {
            return List.of();
        }
        int lim = Math.max(1, Math.min(100, limit));
        // Rotate through the whole pool instead of hammering the same top-score rows:
        // never-Geyser-checked hosts (SEEN) come first so the confirmed-Geyser pool keeps
        // growing, then the least-recently-touched rows so stale OK/NO_GEYSER entries refresh.
        String sql = """
            SELECT host, java_port, mc_version, version_branch, score, status,
                   bedrock_port, bedrock_protocol, bedrock_version,
                   online_players, max_players, display_name, motd, source,
                   success_count, fail_count, last_ok_at, last_fail_at, last_seen_at, updated_at
            FROM registry_scanner_hosts
            WHERE status NOT IN ('BAD_JOIN')
            ORDER BY CASE WHEN status='SEEN' THEN 0 ELSE 1 END ASC,
                     COALESCE(updated_at, 0) ASC
            LIMIT ?
            """;
        List<ScannerHost> out = new ArrayList<>();
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, lim);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(mapScannerHost(rs));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("listScannerHostsForProbe: " + e.getMessage());
        }
        return out;
    }

    public int scannerHostCount() {
        if (!enabled()) {
            return 0;
        }
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM registry_scanner_hosts");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            return 0;
        }
    }

    /** Latest known online count for a scanned host (null if unknown). */
    public Integer scannerOnlinePlayers(String host, int javaPort) {
        if (!enabled() || host == null || host.isBlank() || javaPort <= 0) {
            return null;
        }
        String sql = """
            SELECT online_players FROM registry_scanner_hosts
            WHERE host=? AND java_port=? LIMIT 1
            """;
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, host.trim().toLowerCase(java.util.Locale.ROOT));
            ps.setInt(2, javaPort);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                Object o = rs.getObject(1);
                return o == null ? null : ((Number) o).intValue();
            }
        } catch (SQLException e) {
            return null;
        }
    }

    private static ScannerHost mapScannerHost(ResultSet rs) throws SQLException {
        return new ScannerHost(
                rs.getString("host"),
                rs.getInt("java_port"),
                rs.getString("mc_version"),
                rs.getString("version_branch"),
                rs.getDouble("score"),
                rs.getString("status"),
                rs.getObject("bedrock_port") == null ? 0 : rs.getInt("bedrock_port"),
                rs.getObject("bedrock_protocol") == null ? 0 : rs.getInt("bedrock_protocol"),
                rs.getString("bedrock_version"),
                (Integer) rs.getObject("online_players"),
                (Integer) rs.getObject("max_players"),
                rs.getString("display_name"),
                rs.getString("motd"),
                rs.getString("source"),
                rs.getInt("success_count"),
                rs.getInt("fail_count"),
                rs.getObject("last_ok_at") == null ? 0L : rs.getLong("last_ok_at"),
                rs.getObject("last_fail_at") == null ? 0L : rs.getLong("last_fail_at"),
                rs.getObject("last_seen_at") == null ? 0L : rs.getLong("last_seen_at"),
                rs.getObject("updated_at") == null ? 0L : rs.getLong("updated_at")
        );
    }

    public static String versionBranchOf(String v) {
        if (v == null || v.isBlank()) {
            return "";
        }
        String s = v.trim();
        int a = s.indexOf('.');
        if (a < 0) {
            return s;
        }
        int b = s.indexOf('.', a + 1);
        return b < 0 ? s : s.substring(0, b);
    }

    private static double scannerScoreDelta(String status) {
        return switch (status) {
            case "OK" -> 12;
            case "SEEN" -> 1.5;
            case "FULL" -> -3;
            case "NO_GEYSER" -> -2;
            case "BAD_PROTO" -> -8;
            case "DEAD" -> -18;
            case "BAD_JOIN" -> -35;
            default -> 0;
        };
    }
}
