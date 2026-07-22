package io.multiverseportals.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.multiverseportals.config.PluginConfig;
import io.multiverseportals.model.*;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class Database {

    private final JavaPlugin plugin;
    private final PluginConfig config;
    /** Single writer: SQLite allows only one writer at a time, so we serialize mutations. */
    private HikariDataSource ds;
    /**
     * Read-only pool. WAL lets many readers run concurrently with the single writer, so
     * scanner batch writes no longer stall bind/travel/FX SELECTs on one shared connection.
     */
    private HikariDataSource readDs;

    private static final int READ_POOL_SIZE = 4;

    public Database(JavaPlugin plugin, PluginConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    public void init() {
        plugin.getDataFolder().mkdirs();
        String jdbcUrl = "jdbc:sqlite:" + config.sqliteFile().getAbsolutePath();

        HikariConfig hc = new HikariConfig();
        // Single writer + WAL: avoids SQLITE_BUSY storms from scanners vs bind/FX
        hc.setJdbcUrl(jdbcUrl);
        hc.setMaximumPoolSize(1);
        hc.setConnectionTimeout(30_000);
        hc.setPoolName("MVP-SQLite-write");
        hc.setConnectionInitSql("PRAGMA busy_timeout=30000; PRAGMA foreign_keys=ON;");
        this.ds = new HikariDataSource(hc);
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA synchronous=NORMAL");
            st.execute("PRAGMA busy_timeout=30000");
        } catch (SQLException e) {
            plugin.getLogger().warning("SQLite WAL setup: " + e.getMessage());
        }
        migrate();

        // Read pool created after WAL is persisted on the file (WAL mode is sticky).
        // NOTE: don't open with SQLITE_OPEN_READONLY / Hikari setReadOnly — WAL readers still
        // write the -shm wal-index, and xerial throws when flipping an RW connection to RO.
        // PRAGMA query_only=ON enforces read-only semantics while keeping the file RW-opened.
        HikariConfig rc = new HikariConfig();
        rc.setJdbcUrl(jdbcUrl);
        rc.setMaximumPoolSize(READ_POOL_SIZE);
        rc.setMinimumIdle(1);
        rc.setConnectionTimeout(30_000);
        rc.setPoolName("MVP-SQLite-read");
        rc.setConnectionInitSql("PRAGMA busy_timeout=30000; PRAGMA query_only=ON;");
        this.readDs = new HikariDataSource(rc);
    }

    private void migrate() {
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("""
                CREATE TABLE IF NOT EXISTS peers (
                  server_id TEXT PRIMARY KEY,
                  display_name TEXT NOT NULL,
                  federation_url TEXT NOT NULL,
                  public_host TEXT NOT NULL,
                  public_port INTEGER NOT NULL,
                  shared_secret TEXT NOT NULL,
                  has_plugin INTEGER NOT NULL DEFAULT 1,
                  their_allow_travel INTEGER NOT NULL DEFAULT 0,
                  their_export_inv INTEGER NOT NULL DEFAULT 0,
                  their_import_inv INTEGER NOT NULL DEFAULT 0,
                  their_export_score INTEGER NOT NULL DEFAULT 0,
                  their_import_score INTEGER NOT NULL DEFAULT 0
                )
                """);
            st.execute("""
                CREATE TABLE IF NOT EXISTS portals (
                  id TEXT PRIMARY KEY,
                  type TEXT NOT NULL,
                  status TEXT NOT NULL,
                  world TEXT NOT NULL,
                  x INTEGER NOT NULL,
                  y INTEGER NOT NULL,
                  z INTEGER NOT NULL,
                  yaw REAL NOT NULL,
                  shape_hash TEXT NOT NULL,
                  name TEXT NOT NULL,
                  creator TEXT NOT NULL,
                  pair_server_id TEXT,
                  pair_portal_id TEXT,
                  pair_invite_code TEXT,
                  multi_pool TEXT,
                  bound_host TEXT,
                  bound_port INTEGER,
                  bound_java_port INTEGER,
                  bound_version TEXT
                )
                """);
            try { st.execute("ALTER TABLE portals ADD COLUMN bound_host TEXT"); } catch (SQLException ignored) {}
            try { st.execute("ALTER TABLE portals ADD COLUMN bound_port INTEGER"); } catch (SQLException ignored) {}
            try { st.execute("ALTER TABLE portals ADD COLUMN bound_java_port INTEGER"); } catch (SQLException ignored) {}
            try { st.execute("ALTER TABLE portals ADD COLUMN bound_version TEXT"); } catch (SQLException ignored) {}
            try { st.execute("ALTER TABLE portals ADD COLUMN bound_dest_portal_id TEXT"); } catch (SQLException ignored) {}
            st.execute("""
                CREATE TABLE IF NOT EXISTS player_state (
                  uuid TEXT PRIMARY KEY,
                  inventory BLOB,
                  ender BLOB,
                  xp INTEGER NOT NULL DEFAULT 0,
                  score REAL NOT NULL DEFAULT 0,
                  updated_at INTEGER NOT NULL
                )
                """);
            st.execute("""
                CREATE TABLE IF NOT EXISTS travel_sessions (
                  session_id TEXT PRIMARY KEY,
                  player_uuid TEXT NOT NULL,
                  from_server TEXT NOT NULL,
                  to_server TEXT NOT NULL,
                  from_portal_id TEXT,
                  to_portal_id TEXT,
                  portal_type TEXT NOT NULL,
                  carried INTEGER NOT NULL,
                  carried_blob BLOB,
                  score_snapshot TEXT,
                  status TEXT NOT NULL,
                  created_at INTEGER NOT NULL,
                  expires_at INTEGER NOT NULL
                )
                """);
            st.execute("""
                CREATE TABLE IF NOT EXISTS remote_scores (
                  uuid TEXT NOT NULL,
                  server_id TEXT NOT NULL,
                  score REAL NOT NULL,
                  updated_at INTEGER NOT NULL,
                  PRIMARY KEY (uuid, server_id)
                )
                """);
            st.execute("""
                CREATE TABLE IF NOT EXISTS player_flags (
                  uuid TEXT PRIMARY KEY,
                  ready INTEGER NOT NULL DEFAULT 0,
                  mod_rep REAL NOT NULL DEFAULT 0,
                  updated_at INTEGER NOT NULL
                )
                """);
            try {
                st.execute("ALTER TABLE player_flags ADD COLUMN mod_rep REAL NOT NULL DEFAULT 0");
            } catch (SQLException ignored) {
            }
            st.execute("""
                CREATE TABLE IF NOT EXISTS ingress_deny (
                  uuid TEXT PRIMARY KEY,
                  name TEXT,
                  reason TEXT,
                  created_at INTEGER NOT NULL
                )
                """);
            st.execute("""
                CREATE TABLE IF NOT EXISTS probe_cache (
                  host TEXT NOT NULL,
                  java_port INTEGER NOT NULL,
                  status TEXT NOT NULL,
                  java_online INTEGER,
                  java_max INTEGER,
                  bedrock_port INTEGER,
                  bedrock_protocol INTEGER,
                  bedrock_version TEXT,
                  mc_version TEXT,
                  success_count INTEGER NOT NULL DEFAULT 0,
                  fail_count INTEGER NOT NULL DEFAULT 0,
                  last_ok_at INTEGER,
                  last_fail_at INTEGER,
                  last_checked_at INTEGER NOT NULL,
                  last_transfer_at INTEGER,
                  PRIMARY KEY (host, java_port)
                )
                """);
            st.execute("CREATE INDEX IF NOT EXISTS idx_probe_ok ON probe_cache(last_ok_at DESC)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_probe_status ON probe_cache(status, last_fail_at)");
            // Local server catalog extras (scanners write, portals pick by score; dead rows stay)
            try { st.execute("ALTER TABLE probe_cache ADD COLUMN score REAL DEFAULT 0"); } catch (SQLException ignored) {}
            try { st.execute("ALTER TABLE probe_cache ADD COLUMN display_name TEXT"); } catch (SQLException ignored) {}
            try { st.execute("ALTER TABLE probe_cache ADD COLUMN source TEXT"); } catch (SQLException ignored) {}
            try { st.execute("ALTER TABLE probe_cache ADD COLUMN last_seen_at INTEGER"); } catch (SQLException ignored) {}
            try { st.execute("UPDATE probe_cache SET score=0 WHERE score IS NULL"); } catch (SQLException ignored) {}
            st.execute("CREATE INDEX IF NOT EXISTS idx_probe_score ON probe_cache(score DESC)");
            st.execute("""
                CREATE TABLE IF NOT EXISTS known_mvp_servers (
                  server_id TEXT PRIMARY KEY,
                  display_name TEXT,
                  public_host TEXT NOT NULL,
                  public_port INTEGER NOT NULL,
                  federation_url TEXT,
                  mc_version TEXT,
                  bedrock_port INTEGER DEFAULT 0,
                  last_seen_at INTEGER NOT NULL,
                  score REAL NOT NULL DEFAULT 50,
                  source TEXT NOT NULL
                )
                """);
            st.execute("CREATE INDEX IF NOT EXISTS idx_known_mvp_seen ON known_mvp_servers(last_seen_at DESC)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_known_mvp_score ON known_mvp_servers(score DESC)");
            st.execute("""
                CREATE TABLE IF NOT EXISTS local_portals (
                  id TEXT PRIMARY KEY,
                  name TEXT NOT NULL,
                  color TEXT NOT NULL,
                  channel INTEGER NOT NULL,
                  node INTEGER NOT NULL,
                  world TEXT NOT NULL,
                  x INTEGER NOT NULL,
                  y INTEGER NOT NULL,
                  z INTEGER NOT NULL,
                  creator TEXT NOT NULL,
                  linked_portal_id TEXT
                )
                """);
            st.execute("CREATE INDEX IF NOT EXISTS idx_local_portals_sign ON local_portals(world, x, y, z)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_local_portals_family ON local_portals(color, channel, node)");
            st.execute("""
                CREATE TABLE IF NOT EXISTS meta (
                  key TEXT PRIMARY KEY,
                  value TEXT NOT NULL
                )
                """);
        } catch (SQLException e) {
            throw new IllegalStateException("DB migrate failed", e);
        }
    }

    private static final String META_TOTAL_ARRIVALS = "total_arrivals";

    public long getTotalArrivals() {
        return getMetaLong(META_TOTAL_ARRIVALS, 0L);
    }

    /** Lifetime portal arrivals accepted on this server (persisted). */
    public long incrementTotalArrivals() {
        return incrementMeta(META_TOTAL_ARRIVALS, 1L);
    }

    /** Seed counter from hub history if local meta was never written. */
    public void ensureTotalArrivalsAtLeast(long min) {
        if (min <= 0) {
            return;
        }
        long cur = getTotalArrivals();
        if (cur < min) {
            setMetaLong(META_TOTAL_ARRIVALS, min);
        }
    }

    public long getMetaLong(String key, long def) {
        try (Connection c = connection(); PreparedStatement ps = c.prepareStatement(
                "SELECT value FROM meta WHERE key=?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return def;
                }
                try {
                    return Long.parseLong(rs.getString("value").trim());
                } catch (NumberFormatException e) {
                    return def;
                }
            }
        } catch (SQLException e) {
            return def;
        }
    }

    public void setMetaLong(String key, long value) {
        String sql = """
            INSERT INTO meta(key, value) VALUES(?,?)
            ON CONFLICT(key) DO UPDATE SET value=excluded.value
            """;
        try (Connection c = connection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, key);
            ps.setString(2, Long.toString(value));
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().warning("meta set failed: " + e.getMessage());
        }
    }

    public long incrementMeta(String key, long by) {
        long next = getMetaLong(key, 0L) + by;
        setMetaLong(key, next);
        return next;
    }

    public enum ProbeStatus {
        OK, DEAD, FULL, NO_GEYSER, BAD_PROTO, BAD_JOIN, SEEN
    }

    public record ProbeEntry(
            String host,
            int javaPort,
            ProbeStatus status,
            int bedrockPort,
            int bedrockProtocol,
            String bedrockVersion,
            int successCount,
            int failCount,
            long lastOkAt,
            long lastFailAt,
            long lastCheckedAt,
            double score,
            String displayName,
            String mcVersion,
            long lastSeenAt,
            String source
    ) {
        public TrustedPeer toPeer() {
            String label = displayName != null && !displayName.isBlank() ? displayName : host;
            return new TrustedPeer(
                    "mem:" + host + ":" + javaPort,
                    label,
                    "",
                    host,
                    javaPort,
                    "",
                    false
            );
        }

        public boolean hasGeyser() {
            return bedrockPort > 0;
        }
    }

    private static final double SCORE_MIN = -100;
    private static final double SCORE_MAX = 250;

    /** Write connection (single, serialized). Use for any INSERT/UPDATE/DELETE/DDL. */
    public Connection connection() throws SQLException {
        return ds.getConnection();
    }

    /**
     * Read connection from the concurrent read-only pool. Use for pure SELECTs on hot paths
     * (bind/travel/scanner candidate lookups) so they don't queue behind scanner writes.
     * Falls back to the write pool before the read pool is initialized.
     */
    public Connection readConnection() throws SQLException {
        return readDs != null ? readDs.getConnection() : ds.getConnection();
    }

    private static boolean isBusy(SQLException e) {
        String m = e.getMessage();
        return m != null && (m.contains("SQLITE_BUSY") || m.contains("database is locked"));
    }

    /** Retry reads/writes briefly when another thread holds the SQLite lock. */
    private <T> T withBusyRetry(SqlCall<T> call) throws SQLException {
        SQLException last = null;
        for (int i = 0; i < 8; i++) {
            try {
                return call.run();
            } catch (SQLException e) {
                last = e;
                if (!isBusy(e) || i == 7) {
                    throw e;
                }
                try {
                    Thread.sleep(25L * (i + 1));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
        throw last != null ? last : new SQLException("sqlite busy");
    }

    @FunctionalInterface
    private interface SqlCall<T> {
        T run() throws SQLException;
    }

    public void close() {
        if (readDs != null) {
            readDs.close();
        }
        if (ds != null) {
            ds.close();
        }
    }

    // --- peers ---

    public void upsertPeer(TrustedPeer peer) {
        String sql = """
            INSERT INTO peers(server_id, display_name, federation_url, public_host, public_port, shared_secret, has_plugin,
              their_allow_travel, their_export_inv, their_import_inv, their_export_score, their_import_score)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
            ON CONFLICT(server_id) DO UPDATE SET
              display_name=excluded.display_name,
              federation_url=excluded.federation_url,
              public_host=excluded.public_host,
              public_port=excluded.public_port,
              shared_secret=excluded.shared_secret,
              has_plugin=excluded.has_plugin,
              their_allow_travel=excluded.their_allow_travel,
              their_export_inv=excluded.their_export_inv,
              their_import_inv=excluded.their_import_inv,
              their_export_score=excluded.their_export_score,
              their_import_score=excluded.their_import_score
            """;
        PeerPolicy p = peer.theirPolicy();
        try (Connection c = connection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, peer.serverId());
            ps.setString(2, peer.displayName());
            ps.setString(3, peer.federationUrl());
            ps.setString(4, peer.publicHost());
            ps.setInt(5, peer.publicPort());
            ps.setString(6, peer.sharedSecret());
            ps.setInt(7, peer.hasPlugin() ? 1 : 0);
            ps.setInt(8, p.allowTravel() ? 1 : 0);
            ps.setInt(9, p.exportInventory() ? 1 : 0);
            ps.setInt(10, p.importInventory() ? 1 : 0);
            ps.setInt(11, p.exportScore() ? 1 : 0);
            ps.setInt(12, p.importScore() ? 1 : 0);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public Optional<TrustedPeer> findPeer(String serverId) {
        try (Connection c = readConnection(); PreparedStatement ps = c.prepareStatement("SELECT * FROM peers WHERE server_id=?")) {
            ps.setString(1, serverId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(mapPeer(rs));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public List<TrustedPeer> listPeers() {
        List<TrustedPeer> list = new ArrayList<>();
        try (Connection c = readConnection(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT * FROM peers")) {
            while (rs.next()) {
                list.add(mapPeer(rs));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return list;
    }

    private TrustedPeer mapPeer(ResultSet rs) throws SQLException {
        TrustedPeer peer = new TrustedPeer(
                rs.getString("server_id"),
                rs.getString("display_name"),
                rs.getString("federation_url"),
                rs.getString("public_host"),
                rs.getInt("public_port"),
                rs.getString("shared_secret"),
                rs.getInt("has_plugin") == 1
        );
        peer.setTheirPolicy(new PeerPolicy(
                rs.getInt("their_allow_travel") == 1,
                rs.getInt("their_export_inv") == 1,
                rs.getInt("their_import_inv") == 1,
                rs.getInt("their_export_score") == 1,
                rs.getInt("their_import_score") == 1
        ));
        return peer;
    }

    // --- known MVP club (registry / hub / gossip) ---

    public record KnownMvpServer(
            String serverId,
            String displayName,
            String publicHost,
            int publicPort,
            String federationUrl,
            String mcVersion,
            int bedrockPort,
            long lastSeenAt,
            double score,
            String source
    ) {
        public TrustedPeer toTrustedPeer(String sharedSecretFallback) {
            String fed = federationUrl == null || federationUrl.isBlank()
                    ? "http://" + publicHost + ":25765/mvp/v1"
                    : federationUrl;
            String name = displayName == null || displayName.isBlank() ? serverId : displayName;
            return new TrustedPeer(
                    serverId,
                    name,
                    fed,
                    publicHost,
                    publicPort,
                    sharedSecretFallback == null ? "" : sharedSecretFallback,
                    true
            );
        }
    }

    public void upsertKnownMvp(
            String serverId,
            String displayName,
            String publicHost,
            int publicPort,
            String federationUrl,
            String mcVersion,
            int bedrockPort,
            long lastSeenAt,
            double score,
            String source
    ) {
        if (serverId == null || serverId.isBlank() || publicHost == null || publicHost.isBlank() || publicPort <= 0) {
            return;
        }
        String sql = """
            INSERT INTO known_mvp_servers(
              server_id, display_name, public_host, public_port, federation_url,
              mc_version, bedrock_port, last_seen_at, score, source)
            VALUES (?,?,?,?,?,?,?,?,?,?)
            ON CONFLICT(server_id) DO UPDATE SET
              display_name=COALESCE(excluded.display_name, known_mvp_servers.display_name),
              public_host=excluded.public_host,
              public_port=excluded.public_port,
              federation_url=COALESCE(excluded.federation_url, known_mvp_servers.federation_url),
              mc_version=COALESCE(excluded.mc_version, known_mvp_servers.mc_version),
              bedrock_port=CASE WHEN excluded.bedrock_port>0 THEN excluded.bedrock_port ELSE known_mvp_servers.bedrock_port END,
              last_seen_at=MAX(excluded.last_seen_at, known_mvp_servers.last_seen_at),
              score=MAX(excluded.score, known_mvp_servers.score),
              source=excluded.source
            """;
        try (Connection c = connection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, serverId);
            ps.setString(2, displayName);
            ps.setString(3, publicHost);
            ps.setInt(4, publicPort);
            ps.setString(5, federationUrl);
            ps.setString(6, mcVersion);
            ps.setInt(7, Math.max(0, bedrockPort));
            ps.setLong(8, lastSeenAt > 0 ? lastSeenAt : System.currentTimeMillis());
            ps.setDouble(9, score);
            ps.setString(10, source == null ? "gossip" : source);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().warning("known_mvp upsert failed: " + e.getMessage());
        }
    }

    public List<KnownMvpServer> listKnownMvp(int limit) {
        List<KnownMvpServer> out = new ArrayList<>();
        String sql = """
            SELECT * FROM known_mvp_servers
            ORDER BY score DESC, last_seen_at DESC
            LIMIT ?
            """;
        try (Connection c = readConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, Math.max(1, limit));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(mapKnownMvp(rs));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("known_mvp list failed: " + e.getMessage());
        }
        return out;
    }

    public List<String> listKnownMvpFederationUrls(int limit) {
        List<String> out = new ArrayList<>();
        String sql = """
            SELECT federation_url FROM known_mvp_servers
            WHERE federation_url IS NOT NULL AND federation_url <> ''
            ORDER BY last_seen_at DESC
            LIMIT ?
            """;
        try (Connection c = readConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, Math.max(1, limit));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String u = rs.getString(1);
                    if (u != null && !u.isBlank()) {
                        out.add(u.trim());
                    }
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("known_mvp fed urls failed: " + e.getMessage());
        }
        return out;
    }

    private static KnownMvpServer mapKnownMvp(ResultSet rs) throws SQLException {
        return new KnownMvpServer(
                rs.getString("server_id"),
                rs.getString("display_name"),
                rs.getString("public_host"),
                rs.getInt("public_port"),
                rs.getString("federation_url"),
                rs.getString("mc_version"),
                rs.getInt("bedrock_port"),
                rs.getLong("last_seen_at"),
                rs.getDouble("score"),
                rs.getString("source")
        );
    }

    // --- portals ---

    public void savePortal(Portal portal) {
        String sql = """
            INSERT INTO portals(id,type,status,world,x,y,z,yaw,shape_hash,name,creator,pair_server_id,pair_portal_id,pair_invite_code,multi_pool,
              bound_host,bound_port,bound_java_port,bound_version,bound_dest_portal_id)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            ON CONFLICT(id) DO UPDATE SET
              status=excluded.status,
              shape_hash=excluded.shape_hash,
              pair_server_id=excluded.pair_server_id,
              pair_portal_id=excluded.pair_portal_id,
              pair_invite_code=excluded.pair_invite_code,
              multi_pool=excluded.multi_pool,
              bound_host=excluded.bound_host,
              bound_port=excluded.bound_port,
              bound_java_port=excluded.bound_java_port,
              bound_version=excluded.bound_version,
              bound_dest_portal_id=excluded.bound_dest_portal_id
            """;
        try (Connection c = connection(); PreparedStatement ps = c.prepareStatement(sql)) {
            PortalFrame f = portal.frame();
            ps.setString(1, portal.id());
            ps.setString(2, portal.type().name());
            ps.setString(3, portal.status().name());
            ps.setString(4, f.world());
            ps.setInt(5, f.x());
            ps.setInt(6, f.y());
            ps.setInt(7, f.z());
            ps.setFloat(8, f.yaw());
            ps.setString(9, f.shapeHash());
            ps.setString(10, portal.name());
            ps.setString(11, portal.creator().toString());
            ps.setString(12, portal.pairServerId());
            ps.setString(13, portal.pairPortalId());
            ps.setString(14, portal.pairInviteCode());
            ps.setString(15, String.join(",", portal.multiPool()));
            ps.setString(16, portal.boundHost());
            if (portal.boundPort() > 0) {
                ps.setInt(17, portal.boundPort());
            } else {
                ps.setNull(17, Types.INTEGER);
            }
            if (portal.boundJavaPort() > 0) {
                ps.setInt(18, portal.boundJavaPort());
            } else {
                ps.setNull(18, Types.INTEGER);
            }
            ps.setString(19, portal.boundVersion());
            ps.setString(20, portal.boundDestPortalId());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public Optional<Portal> findPortal(String id) {
        try {
            return withBusyRetry(() -> {
                try (Connection c = readConnection(); PreparedStatement ps = c.prepareStatement("SELECT * FROM portals WHERE id=?")) {
                    ps.setString(1, id);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            return Optional.empty();
                        }
                        return Optional.of(mapPortal(rs));
                    }
                }
            });
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Like {@link #findPortal} but never throws on SQLITE_BUSY — returns empty only when
     * the row is missing. On lock errors returns {@code Optional.ofNullable(null)} via
     * a sentinel: callers should use {@link #portalAlive(String)} for bind loops.
     */
    public boolean portalAlive(String id) {
        try {
            return findPortal(id).isPresent();
        } catch (RuntimeException e) {
            // DB glitch — do not treat as deleted mid-bind
            return true;
        }
    }

    public Optional<Portal> findPortalByFrame(String world, int x, int y, int z) {
        String sql = "SELECT * FROM portals WHERE world=? AND x=? AND y=? AND z=?";
        try (Connection c = readConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, world);
            ps.setInt(2, x);
            ps.setInt(3, y);
            ps.setInt(4, z);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(mapPortal(rs));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public Optional<Portal> findPortalByInvite(String code) {
        try (Connection c = readConnection(); PreparedStatement ps = c.prepareStatement("SELECT * FROM portals WHERE pair_invite_code=?")) {
            ps.setString(1, code);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(mapPortal(rs));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public List<Portal> listPortals() {
        List<Portal> list = new ArrayList<>();
        try (Connection c = readConnection(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT * FROM portals")) {
            while (rs.next()) {
                list.add(mapPortal(rs));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return list;
    }

    public void deletePortal(String id) {
        try (Connection c = connection(); PreparedStatement ps = c.prepareStatement("DELETE FROM portals WHERE id=?")) {
            ps.setString(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private Portal mapPortal(ResultSet rs) throws SQLException {
        PortalFrame frame = new PortalFrame(
                rs.getString("world"),
                rs.getInt("x"),
                rs.getInt("y"),
                rs.getInt("z"),
                rs.getString("shape_hash"),
                rs.getFloat("yaw")
        );
        Portal portal = new Portal(
                rs.getString("id"),
                PortalType.valueOf(rs.getString("type")),
                PortalStatus.valueOf(rs.getString("status")),
                frame,
                rs.getString("name"),
                UUID.fromString(rs.getString("creator"))
        );
        portal.setPairServerId(rs.getString("pair_server_id"));
        portal.setPairPortalId(rs.getString("pair_portal_id"));
        portal.setPairInviteCode(rs.getString("pair_invite_code"));
        String pool = rs.getString("multi_pool");
        if (pool != null && !pool.isBlank()) {
            for (String p : pool.split(",")) {
                if (!p.isBlank()) {
                    portal.multiPool().add(p.trim());
                }
            }
        }
        try {
            portal.setBoundHost(rs.getString("bound_host"));
            int bp = rs.getInt("bound_port");
            if (!rs.wasNull()) {
                portal.setBoundPort(bp);
            }
            int bjp = rs.getInt("bound_java_port");
            if (!rs.wasNull()) {
                portal.setBoundJavaPort(bjp);
            }
            portal.setBoundVersion(rs.getString("bound_version"));
            portal.setBoundDestPortalId(rs.getString("bound_dest_portal_id"));
        } catch (SQLException ignored) {
            // older schema without bind columns
        }
        return portal;
    }

    // --- player state ---

    public void savePlayerState(UUID uuid, byte[] inv, byte[] ender, int xp, double score) {
        String sql = """
            INSERT INTO player_state(uuid, inventory, ender, xp, score, updated_at)
            VALUES (?,?,?,?,?,?)
            ON CONFLICT(uuid) DO UPDATE SET
              inventory=excluded.inventory,
              ender=excluded.ender,
              xp=excluded.xp,
              score=excluded.score,
              updated_at=excluded.updated_at
            """;
        try (Connection c = connection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setBytes(2, inv);
            ps.setBytes(3, ender);
            ps.setInt(4, xp);
            ps.setDouble(5, score);
            ps.setLong(6, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public Optional<byte[]> loadInventory(UUID uuid) {
        try (Connection c = connection(); PreparedStatement ps = c.prepareStatement("SELECT inventory FROM player_state WHERE uuid=?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.ofNullable(rs.getBytes(1));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public double localScore(UUID uuid) {
        try (Connection c = connection(); PreparedStatement ps = c.prepareStatement("SELECT score FROM player_state WHERE uuid=?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return 0;
                }
                return rs.getDouble(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public void upsertRemoteScore(UUID uuid, String serverId, double score) {
        String sql = """
            INSERT INTO remote_scores(uuid, server_id, score, updated_at) VALUES (?,?,?,?)
            ON CONFLICT(uuid, server_id) DO UPDATE SET score=excluded.score, updated_at=excluded.updated_at
            """;
        try (Connection c = connection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, serverId);
            ps.setDouble(3, score);
            ps.setLong(4, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    // --- sessions ---

    public void saveSession(TravelSession s) {
        String sql = """
            INSERT INTO travel_sessions(session_id, player_uuid, from_server, to_server, from_portal_id, to_portal_id,
              portal_type, carried, carried_blob, score_snapshot, status, created_at, expires_at)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
            ON CONFLICT(session_id) DO UPDATE SET status=excluded.status, carried_blob=excluded.carried_blob
            """;
        try (Connection c = connection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, s.sessionId());
            ps.setString(2, s.playerId().toString());
            ps.setString(3, s.fromServer());
            ps.setString(4, s.toServer());
            ps.setString(5, s.fromPortalId());
            ps.setString(6, s.toPortalId());
            ps.setString(7, s.portalType().name());
            ps.setInt(8, s.carriedInventory() ? 1 : 0);
            ps.setBytes(9, s.carriedBlob());
            ps.setString(10, s.scoreSnapshotJson());
            ps.setString(11, s.status().name());
            ps.setLong(12, s.createdAt());
            ps.setLong(13, s.expiresAt());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public Optional<TravelSession> findSession(String id) {
        try (Connection c = connection(); PreparedStatement ps = c.prepareStatement("SELECT * FROM travel_sessions WHERE session_id=?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(mapSession(rs));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public Optional<TravelSession> findPendingForPlayer(UUID uuid) {
        String sql = "SELECT * FROM travel_sessions WHERE player_uuid=? AND status='PENDING' ORDER BY created_at DESC LIMIT 1";
        try (Connection c = connection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(mapSession(rs));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private TravelSession mapSession(ResultSet rs) throws SQLException {
        return new TravelSession(
                rs.getString("session_id"),
                UUID.fromString(rs.getString("player_uuid")),
                rs.getString("from_server"),
                rs.getString("to_server"),
                rs.getString("from_portal_id"),
                rs.getString("to_portal_id"),
                PortalType.valueOf(rs.getString("portal_type")),
                rs.getInt("carried") == 1,
                rs.getBytes("carried_blob"),
                rs.getString("score_snapshot"),
                TravelSession.Status.valueOf(rs.getString("status")),
                rs.getLong("created_at"),
                rs.getLong("expires_at")
        );
    }

    public boolean isPlayerReady(UUID uuid) {
        try (Connection c = connection(); PreparedStatement ps = c.prepareStatement(
                "SELECT ready FROM player_flags WHERE uuid=?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt("ready") == 1;
            }
        } catch (SQLException e) {
            return false;
        }
    }

    public void setPlayerReady(UUID uuid, boolean ready) {
        String sql = """
            INSERT INTO player_flags(uuid, ready, mod_rep, updated_at) VALUES(?,?,COALESCE((SELECT mod_rep FROM player_flags WHERE uuid=?),0),?)
            ON CONFLICT(uuid) DO UPDATE SET ready=excluded.ready, updated_at=excluded.updated_at
            """;
        try (Connection c = connection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setInt(2, ready ? 1 : 0);
            ps.setString(3, uuid.toString());
            ps.setLong(4, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException e) {
            // fallback simpler upsert
            try (Connection c = connection(); PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO player_flags(uuid, ready, mod_rep, updated_at) VALUES(?,?,0,?) ON CONFLICT(uuid) DO UPDATE SET ready=excluded.ready, updated_at=excluded.updated_at")) {
                ps.setString(1, uuid.toString());
                ps.setInt(2, ready ? 1 : 0);
                ps.setLong(3, System.currentTimeMillis());
                ps.executeUpdate();
            } catch (SQLException e2) {
                throw new IllegalStateException(e2);
            }
        }
    }

    public double modReputation(UUID uuid) {
        try (Connection c = connection(); PreparedStatement ps = c.prepareStatement(
                "SELECT mod_rep FROM player_flags WHERE uuid=?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getDouble("mod_rep") : 0;
            }
        } catch (SQLException e) {
            return 0;
        }
    }

    public void addModReputation(UUID uuid, double delta) {
        String sql = """
            INSERT INTO player_flags(uuid, ready, mod_rep, updated_at) VALUES(?,0,?,?)
            ON CONFLICT(uuid) DO UPDATE SET mod_rep=player_flags.mod_rep+excluded.mod_rep, updated_at=excluded.updated_at
            """;
        try (Connection c = connection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setDouble(2, delta);
            ps.setLong(3, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public boolean isDenied(UUID uuid) {
        try (Connection c = connection(); PreparedStatement ps = c.prepareStatement(
                "SELECT 1 FROM ingress_deny WHERE uuid=?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            return false;
        }
    }

    public void denyPlayer(UUID uuid, String name, String reason) {
        String sql = """
            INSERT INTO ingress_deny(uuid, name, reason, created_at) VALUES(?,?,?,?)
            ON CONFLICT(uuid) DO UPDATE SET name=excluded.name, reason=excluded.reason, created_at=excluded.created_at
            """;
        try (Connection c = connection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, name);
            ps.setString(3, reason == null ? "" : reason);
            ps.setLong(4, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public void allowPlayer(UUID uuid) {
        try (Connection c = connection(); PreparedStatement ps = c.prepareStatement(
                "DELETE FROM ingress_deny WHERE uuid=?")) {
            ps.setString(1, uuid.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public java.util.List<String> listDenied(int limit) {
        java.util.List<String> out = new java.util.ArrayList<>();
        try (Connection c = connection(); PreparedStatement ps = c.prepareStatement(
                "SELECT name, uuid, reason FROM ingress_deny ORDER BY created_at DESC LIMIT ?")) {
            ps.setInt(1, Math.max(1, limit));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String n = rs.getString("name");
                    out.add((n == null || n.isBlank() ? "?" : n) + " (" + rs.getString("uuid") + ")"
                            + (rs.getString("reason") == null || rs.getString("reason").isBlank()
                            ? "" : " — " + rs.getString("reason")));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    public static byte[] utf8(String s) {
        return s == null ? null : s.getBytes(StandardCharsets.UTF_8);
    }

    // --- probe memory (skip dead, prefer verified) ---

    public boolean isRecentlyDead(String host, int javaPort, long deadTtlMs) {
        return isRecentlyFailed(host, javaPort, deadTtlMs, false);
    }

    /**
     * @param hardOnly if true, only DEAD/BAD_JOIN count (not NO_GEYSER/FULL/BAD_PROTO) —
     *                 used by bind-on-create so a soft miss doesn't poison the whole pool.
     */
    public boolean isRecentlyFailed(String host, int javaPort, long deadTtlMs, boolean hardOnly) {
        if (host == null || host.isBlank() || deadTtlMs <= 0) {
            return false;
        }
        long cutoff = System.currentTimeMillis() - deadTtlMs;
        try (Connection c = connection(); PreparedStatement ps = c.prepareStatement("""
                SELECT status, last_fail_at FROM probe_cache WHERE host=? AND java_port=?
                """)) {
            ps.setString(1, host.toLowerCase(java.util.Locale.ROOT));
            ps.setInt(2, javaPort);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return false;
                }
                String st = rs.getString("status");
                long failAt = rs.getLong("last_fail_at");
                if (rs.wasNull()) {
                    return false;
                }
                if ("OK".equals(st) || failAt < cutoff) {
                    return false;
                }
                if (!hardOnly) {
                    return true;
                }
                return "DEAD".equals(st) || "BAD_JOIN".equals(st);
            }
        } catch (SQLException e) {
            return false;
        }
    }

    public void recordProbe(
            String host,
            int javaPort,
            ProbeStatus status,
            Integer javaOnline,
            Integer javaMax,
            Integer bedrockPort,
            Integer bedrockProtocol,
            String bedrockVersion,
            String mcVersion
    ) {
        if (host == null || host.isBlank() || javaPort <= 0 || status == null) {
            return;
        }
        long now = System.currentTimeMillis();
        String h = host.toLowerCase(java.util.Locale.ROOT);
        boolean ok = status == ProbeStatus.OK;
        double delta = scoreDelta(status);
        if (ok && bedrockPort != null && bedrockPort > 0) {
            delta += 5;
        }
        String sql = """
            INSERT INTO probe_cache(host, java_port, status, java_online, java_max, bedrock_port, bedrock_protocol,
              bedrock_version, mc_version, success_count, fail_count, last_ok_at, last_fail_at, last_checked_at,
              last_transfer_at, score, display_name, source, last_seen_at)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,NULL,?,?,NULL,NULL)
            ON CONFLICT(host, java_port) DO UPDATE SET
              status=excluded.status,
              java_online=COALESCE(excluded.java_online, probe_cache.java_online),
              java_max=COALESCE(excluded.java_max, probe_cache.java_max),
              bedrock_port=COALESCE(excluded.bedrock_port, probe_cache.bedrock_port),
              bedrock_protocol=COALESCE(excluded.bedrock_protocol, probe_cache.bedrock_protocol),
              bedrock_version=COALESCE(excluded.bedrock_version, probe_cache.bedrock_version),
              mc_version=COALESCE(excluded.mc_version, probe_cache.mc_version),
              success_count=probe_cache.success_count + excluded.success_count,
              fail_count=probe_cache.fail_count + excluded.fail_count,
              last_ok_at=CASE WHEN excluded.success_count>0 THEN excluded.last_ok_at ELSE probe_cache.last_ok_at END,
              last_fail_at=CASE WHEN excluded.fail_count>0 THEN excluded.last_fail_at ELSE probe_cache.last_fail_at END,
              last_checked_at=excluded.last_checked_at,
              score=MAX(%f, MIN(%f, COALESCE(probe_cache.score, 0) + excluded.score))
            """.formatted(SCORE_MIN, SCORE_MAX);
        try (Connection c = connection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, h);
            ps.setInt(2, javaPort);
            ps.setString(3, status.name());
            if (javaOnline == null) {
                ps.setNull(4, Types.INTEGER);
            } else {
                ps.setInt(4, javaOnline);
            }
            if (javaMax == null) {
                ps.setNull(5, Types.INTEGER);
            } else {
                ps.setInt(5, javaMax);
            }
            if (bedrockPort == null || bedrockPort <= 0) {
                ps.setNull(6, Types.INTEGER);
            } else {
                ps.setInt(6, bedrockPort);
            }
            if (bedrockProtocol == null || bedrockProtocol <= 0) {
                ps.setNull(7, Types.INTEGER);
            } else {
                ps.setInt(7, bedrockProtocol);
            }
            ps.setString(8, bedrockVersion);
            ps.setString(9, mcVersion);
            ps.setInt(10, ok ? 1 : 0);
            ps.setInt(11, ok ? 0 : 1);
            if (ok) {
                ps.setLong(12, now);
                ps.setNull(13, Types.BIGINT);
            } else {
                ps.setNull(12, Types.BIGINT);
                ps.setLong(13, now);
            }
            ps.setLong(14, now);
            ps.setDouble(15, clampScore(ok ? 12 : delta));
            ps.setString(16, null);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().warning("probe_cache write failed: " + e.getMessage());
        }
    }

    public void recordProbeTransfer(String host, int javaPort) {
        if (host == null || host.isBlank()) {
            return;
        }
        long now = System.currentTimeMillis();
        try (Connection c = connection(); PreparedStatement ps = c.prepareStatement("""
                UPDATE probe_cache SET last_transfer_at=?, success_count=success_count+1, status='OK', last_ok_at=?,
                  score=MAX(%f, MIN(%f, COALESCE(score, 0) + 25))
                WHERE host=? AND java_port=?
                """.formatted(SCORE_MIN, SCORE_MAX))) {
            ps.setLong(1, now);
            ps.setLong(2, now);
            ps.setString(3, host.toLowerCase(java.util.Locale.ROOT));
            ps.setInt(4, javaPort);
            if (ps.executeUpdate() == 0) {
                recordProbe(host, javaPort, ProbeStatus.OK, null, null, null, null, null, null);
                try (PreparedStatement ps2 = c.prepareStatement("""
                        UPDATE probe_cache SET last_transfer_at=?, score=MAX(%f, MIN(%f, COALESCE(score, 0) + 25))
                        WHERE host=? AND java_port=?
                        """.formatted(SCORE_MIN, SCORE_MAX))) {
                    ps2.setLong(1, now);
                    ps2.setString(2, host.toLowerCase(java.util.Locale.ROOT));
                    ps2.setInt(3, javaPort);
                    ps2.executeUpdate();
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("probe_cache transfer failed: " + e.getMessage());
        }
    }

    /**
     * Scanners always upsert into the local catalog. Dead rows are never deleted —
     * only status/score change so we can rediscover live hosts later.
     */
    public int upsertScannedServers(List<io.multiverseportals.scanner.ScannedServer> servers) {
        if (servers == null || servers.isEmpty()) {
            return 0;
        }
        long now = System.currentTimeMillis();
        String sql = """
            INSERT INTO probe_cache(
              host, java_port, status, java_online, java_max,
              bedrock_port, bedrock_protocol, bedrock_version, mc_version,
              success_count, fail_count, last_ok_at, last_fail_at, last_checked_at, last_transfer_at,
              score, display_name, source, last_seen_at)
            VALUES(?,?,?,?,?, NULL,NULL,NULL,?, 0,0,NULL,NULL,?,NULL, ?,?,?,?)
            ON CONFLICT(host, java_port) DO UPDATE SET
              java_online=COALESCE(excluded.java_online, probe_cache.java_online),
              java_max=COALESCE(excluded.java_max, probe_cache.java_max),
              mc_version=COALESCE(excluded.mc_version, probe_cache.mc_version),
              display_name=COALESCE(excluded.display_name, probe_cache.display_name),
              source=COALESCE(excluded.source, probe_cache.source),
              last_seen_at=MAX(COALESCE(probe_cache.last_seen_at, 0), COALESCE(excluded.last_seen_at, 0)),
              last_checked_at=excluded.last_checked_at,
              score=MAX(%f, MIN(%f, COALESCE(probe_cache.score, 0) + 1.5)),
              status=CASE
                WHEN probe_cache.status = 'OK' THEN 'OK'
                WHEN probe_cache.status IN ('DEAD','BAD_JOIN')
                  AND probe_cache.last_fail_at IS NOT NULL
                  AND probe_cache.last_fail_at >= %d THEN probe_cache.status
                ELSE 'SEEN'
              END
            """.formatted(SCORE_MIN, SCORE_MAX, now - 900_000L);
        int n = 0;
        try (Connection c = connection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (var s : servers) {
                if (s == null || s.host() == null || s.host().isBlank() || s.port() <= 0) {
                    continue;
                }
                String h = s.host().toLowerCase(java.util.Locale.ROOT);
                String label = s.labelForSign();
                long seen = s.lastSeenMs() > 0 ? s.lastSeenMs() : now;
                ps.setString(1, h);
                ps.setInt(2, s.port());
                ps.setString(3, ProbeStatus.SEEN.name());
                ps.setInt(4, s.onlinePlayers());
                ps.setInt(5, s.maxPlayers());
                ps.setString(6, s.version());
                ps.setLong(7, now);
                ps.setDouble(8, 8.0);
                ps.setString(9, label);
                ps.setString(10, s.source());
                ps.setLong(11, seen);
                ps.addBatch();
                n++;
            }
            ps.executeBatch();
        } catch (SQLException e) {
            plugin.getLogger().warning("known_servers upsert failed: " + e.getMessage());
            return 0;
        }
        return n;
    }

    /**
     * Candidates for portals/bind: ordered by local score. Hard-dead within TTL are skipped
     * for selection but remain in the table.
     */
    public List<ProbeEntry> listScoredCandidates(String versionBranch, int limit, long deadTtlMs) {
        List<ProbeEntry> out = new ArrayList<>();
        int fetch = Math.max(20, Math.min(500, limit * 3));
        long cutoff = System.currentTimeMillis() - Math.max(0L, deadTtlMs);
        String sql = """
            SELECT host, java_port, status, bedrock_port, bedrock_protocol, bedrock_version,
                   success_count, fail_count, last_ok_at, last_fail_at, last_checked_at,
                   score, display_name, mc_version, last_seen_at, source
            FROM probe_cache
            ORDER BY score DESC, COALESCE(last_seen_at, 0) DESC, COALESCE(last_ok_at, 0) DESC
            LIMIT ?
            """;
        String branch = versionBranch == null ? "" : versionBranch.trim();
        try (Connection c = readConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, fetch);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ProbeEntry e = readProbe(rs);
                    if (isHardDeadForPick(e, cutoff)) {
                        continue;
                    }
                    if (!branch.isEmpty()) {
                        String vb = versionBranchOf(e.mcVersion());
                        if (!vb.isEmpty() && !vb.equals(branch)) {
                            continue;
                        }
                    }
                    out.add(e);
                    if (out.size() >= limit) {
                        break;
                    }
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("listScoredCandidates failed: " + e.getMessage());
        }
        return out;
    }

    public List<ProbeEntry> listTopScored(int limit) {
        List<ProbeEntry> out = new ArrayList<>();
        String sql = """
            SELECT host, java_port, status, bedrock_port, bedrock_protocol, bedrock_version,
                   success_count, fail_count, last_ok_at, last_fail_at, last_checked_at,
                   score, display_name, mc_version, last_seen_at, source
            FROM probe_cache
            ORDER BY score DESC
            LIMIT ?
            """;
        try (Connection c = readConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, Math.max(1, limit));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(readProbe(rs));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("listTopScored failed: " + e.getMessage());
        }
        return out;
    }

    /** Never pick for bind — player already bounced back from this host. */
    public boolean isBadJoinHost(String host, int javaPort) {
        if (host == null || host.isBlank() || javaPort <= 0) {
            return false;
        }
        try (Connection c = readConnection(); PreparedStatement ps = c.prepareStatement("""
                SELECT status FROM probe_cache WHERE host=? AND java_port=?
                """)) {
            ps.setString(1, host.toLowerCase(java.util.Locale.ROOT));
            ps.setInt(2, javaPort);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return false;
                }
                return ProbeStatus.BAD_JOIN.name().equals(rs.getString("status"));
            }
        } catch (SQLException e) {
            return false;
        }
    }

    /**
     * Bind candidates: score order, known Geyser first, skip BAD_JOIN forever.
     */
    public List<ProbeEntry> listBindCandidates(String versionBranch, int limit, long deadTtlMs) {
        List<ProbeEntry> out = new ArrayList<>();
        int fetch = Math.max(30, Math.min(500, limit * 4));
        long cutoff = System.currentTimeMillis() - Math.max(0L, deadTtlMs);
        String branch = versionBranch == null ? "" : versionBranch.trim();
        String sql = """
            SELECT host, java_port, status, bedrock_port, bedrock_protocol, bedrock_version,
                   success_count, fail_count, last_ok_at, last_fail_at, last_checked_at,
                   score, display_name, mc_version, last_seen_at, source
            FROM probe_cache
            WHERE status != 'BAD_JOIN'
            ORDER BY CASE WHEN bedrock_port IS NOT NULL AND bedrock_port > 0 THEN 1 ELSE 0 END DESC,
                     score DESC,
                     COALESCE(last_ok_at, 0) DESC,
                     COALESCE(last_seen_at, 0) DESC
            LIMIT ?
            """;
        try (Connection c = readConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, fetch);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ProbeEntry e = readProbe(rs);
                    if (isHardDeadForPick(e, cutoff)) {
                        continue;
                    }
                    if (!branch.isEmpty()) {
                        String vb = versionBranchOf(e.mcVersion());
                        if (!vb.isEmpty() && !vb.equals(branch)) {
                            continue;
                        }
                    }
                    out.add(e);
                    if (out.size() >= limit) {
                        break;
                    }
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("listBindCandidates failed: " + e.getMessage());
        }
        return out;
    }

    private static boolean isHardDeadForPick(ProbeEntry e, long failCutoff) {
        if (e.status() != ProbeStatus.DEAD && e.status() != ProbeStatus.BAD_JOIN) {
            return false;
        }
        return e.lastFailAt() > 0 && e.lastFailAt() >= failCutoff;
    }

    private static String versionBranchOf(String v) {
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

    private static double scoreDelta(ProbeStatus status) {
        return switch (status) {
            case OK -> 12;
            case SEEN -> 1.5;
            case FULL -> -3;
            case NO_GEYSER -> -2;
            case BAD_PROTO -> -8;
            case DEAD -> -18;
            case BAD_JOIN -> -35;
        };
    }

    private static double clampScore(double s) {
        return Math.max(SCORE_MIN, Math.min(SCORE_MAX, s));
    }

    /** Verified hosts that worked recently — fallback pool for random portals. */
    public List<ProbeEntry> listVerifiedProbes(long maxAgeMs, int minSuccess, int limit) {
        List<ProbeEntry> out = new ArrayList<>();
        long cutoff = System.currentTimeMillis() - Math.max(60_000L, maxAgeMs);
        String sql = """
            SELECT host, java_port, status, bedrock_port, bedrock_protocol, bedrock_version,
                   success_count, fail_count, last_ok_at, last_fail_at, last_checked_at,
                   score, display_name, mc_version, last_seen_at, source
            FROM probe_cache
            WHERE status='OK' AND success_count>=? AND last_ok_at IS NOT NULL AND last_ok_at>=?
            ORDER BY score DESC, COALESCE(last_transfer_at, 0) DESC, last_ok_at DESC
            LIMIT ?
            """;
        try (Connection c = readConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, Math.max(1, minSuccess));
            ps.setLong(2, cutoff);
            ps.setInt(3, Math.max(1, limit));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(readProbe(rs));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("probe_cache list failed: " + e.getMessage());
        }
        return out;
    }

    /** [total, ok, deadish, pickable] */
    public int[] probeCacheStats() {
        int[] s = new int[4];
        try (Connection c = connection(); Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM probe_cache")) {
                if (rs.next()) {
                    s[0] = rs.getInt(1);
                }
            }
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM probe_cache WHERE status='OK' AND success_count>=1")) {
                if (rs.next()) {
                    s[1] = rs.getInt(1);
                }
            }
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM probe_cache WHERE status IN ('DEAD','BAD_JOIN')")) {
                if (rs.next()) {
                    s[2] = rs.getInt(1);
                }
            }
            try (ResultSet rs = st.executeQuery(
                    "SELECT COUNT(*) FROM probe_cache WHERE status NOT IN ('DEAD','BAD_JOIN')")) {
                if (rs.next()) {
                    s[3] = rs.getInt(1);
                }
            }
        } catch (SQLException ignored) {
        }
        return s;
    }

    private static ProbeEntry readProbe(ResultSet rs) throws SQLException {
        ProbeStatus st;
        try {
            st = ProbeStatus.valueOf(rs.getString("status"));
        } catch (Exception e) {
            st = ProbeStatus.DEAD;
        }
        int bp = rs.getInt("bedrock_port");
        if (rs.wasNull()) {
            bp = 0;
        }
        int bproto = rs.getInt("bedrock_protocol");
        if (rs.wasNull()) {
            bproto = 0;
        }
        long okAt = rs.getLong("last_ok_at");
        if (rs.wasNull()) {
            okAt = 0;
        }
        long failAt = rs.getLong("last_fail_at");
        if (rs.wasNull()) {
            failAt = 0;
        }
        double score = 0;
        try {
            score = rs.getDouble("score");
            if (rs.wasNull()) {
                score = 0;
            }
        } catch (SQLException ignored) {
        }
        String display = null;
        String mcVer = null;
        String source = null;
        long seenAt = 0;
        try {
            display = rs.getString("display_name");
            mcVer = rs.getString("mc_version");
            source = rs.getString("source");
            seenAt = rs.getLong("last_seen_at");
            if (rs.wasNull()) {
                seenAt = 0;
            }
        } catch (SQLException ignored) {
        }
        return new ProbeEntry(
                rs.getString("host"),
                rs.getInt("java_port"),
                st,
                bp,
                bproto,
                rs.getString("bedrock_version"),
                rs.getInt("success_count"),
                rs.getInt("fail_count"),
                okAt,
                failAt,
                rs.getLong("last_checked_at"),
                score,
                display,
                mcVer,
                seenAt,
                source
        );
    }

    // --- local ColorPortals-style portals ---

    public void saveLocalPortal(io.multiverseportals.local.LocalPortal portal) {
        String sql = """
            INSERT INTO local_portals(id, name, color, channel, node, world, x, y, z, creator, linked_portal_id)
            VALUES(?,?,?,?,?,?,?,?,?,?,?)
            ON CONFLICT(id) DO UPDATE SET
              name=excluded.name,
              color=excluded.color,
              channel=excluded.channel,
              node=excluded.node,
              world=excluded.world,
              x=excluded.x,
              y=excluded.y,
              z=excluded.z,
              creator=excluded.creator,
              linked_portal_id=excluded.linked_portal_id
            """;
        try (Connection c = connection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, portal.id());
            ps.setString(2, portal.name());
            ps.setString(3, portal.color().name());
            ps.setInt(4, portal.channel());
            ps.setInt(5, portal.node());
            ps.setString(6, portal.world());
            ps.setInt(7, portal.x());
            ps.setInt(8, portal.y());
            ps.setInt(9, portal.z());
            ps.setString(10, portal.creator().toString());
            ps.setString(11, portal.linkedPortalId());
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().warning("local_portals save failed: " + e.getMessage());
        }
    }

    public void deleteLocalPortal(String id) {
        try (Connection c = connection(); PreparedStatement ps = c.prepareStatement(
                "DELETE FROM local_portals WHERE id=?")) {
            ps.setString(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().warning("local_portals delete failed: " + e.getMessage());
        }
    }

    public Optional<io.multiverseportals.local.LocalPortal> findLocalPortal(String id) {
        try (Connection c = connection(); PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM local_portals WHERE id=?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapLocalPortal(rs));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("local_portals find failed: " + e.getMessage());
        }
        return Optional.empty();
    }

    public Optional<io.multiverseportals.local.LocalPortal> findLocalPortalBySign(String world, int x, int y, int z) {
        try (Connection c = connection(); PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM local_portals WHERE world=? AND x=? AND y=? AND z=?")) {
            ps.setString(1, world);
            ps.setInt(2, x);
            ps.setInt(3, y);
            ps.setInt(4, z);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapLocalPortal(rs));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("local_portals findBySign failed: " + e.getMessage());
        }
        return Optional.empty();
    }

    public List<io.multiverseportals.local.LocalPortal> listLocalPortals() {
        List<io.multiverseportals.local.LocalPortal> out = new ArrayList<>();
        try (Connection c = connection(); PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM local_portals ORDER BY color, channel, node");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(mapLocalPortal(rs));
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("local_portals list failed: " + e.getMessage());
        }
        return out;
    }

    public List<io.multiverseportals.local.LocalPortal> listLocalPortalsByColorChannel(String color, int channel) {
        List<io.multiverseportals.local.LocalPortal> out = new ArrayList<>();
        try (Connection c = connection(); PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM local_portals WHERE color=? AND channel=? ORDER BY node")) {
            ps.setString(1, color);
            ps.setInt(2, channel);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(mapLocalPortal(rs));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("local_portals family failed: " + e.getMessage());
        }
        return out;
    }

    public int countLocalPortalsByCreator(UUID creator) {
        try (Connection c = connection(); PreparedStatement ps = c.prepareStatement(
                "SELECT COUNT(*) FROM local_portals WHERE creator=?")) {
            ps.setString(1, creator.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        } catch (SQLException e) {
            return 0;
        }
        return 0;
    }

    private static io.multiverseportals.local.LocalPortal mapLocalPortal(ResultSet rs) throws SQLException {
        org.bukkit.DyeColor color;
        try {
            color = org.bukkit.DyeColor.valueOf(rs.getString("color"));
        } catch (Exception e) {
            color = org.bukkit.DyeColor.WHITE;
        }
        UUID creator;
        try {
            creator = UUID.fromString(rs.getString("creator"));
        } catch (Exception e) {
            creator = new UUID(0, 0);
        }
        return new io.multiverseportals.local.LocalPortal(
                rs.getString("id"),
                rs.getString("name"),
                color,
                rs.getInt("channel"),
                rs.getInt("node"),
                rs.getString("world"),
                rs.getInt("x"),
                rs.getInt("y"),
                rs.getInt("z"),
                creator,
                rs.getString("linked_portal_id")
        );
    }
}
