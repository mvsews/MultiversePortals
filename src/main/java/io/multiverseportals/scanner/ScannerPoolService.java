package io.multiverseportals.scanner;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.multiverseportals.MultiversePortalsPlugin;
import io.multiverseportals.compat.BedrockPlayers;
import io.multiverseportals.compat.VersionCompat;
import io.multiverseportals.config.PluginConfig;
import io.multiverseportals.db.Database;
import io.multiverseportals.db.RegistryDatabase;
import io.multiverseportals.federation.PeerClient;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Leaf: pull scanner candidates from central hub before public APIs; report probe finds.
 * Hub: mirror ScannerHub listings into MySQL and lightly re-probe the top of the pool.
 */
public final class ScannerPoolService {

    private final MultiversePortalsPlugin plugin;
    private final PluginConfig config;
    private final Database db;
    private final RegistryDatabase registry;
    private final PeerClient peerClient;
    private BukkitTask pullTask;
    private BukkitTask probeTask;
    private final AtomicInteger lastHubPullCount = new AtomicInteger(0);
    private final AtomicLong lastPullMs = new AtomicLong(0);

    public ScannerPoolService(
            MultiversePortalsPlugin plugin,
            PluginConfig config,
            Database db,
            RegistryDatabase registry,
            PeerClient peerClient
    ) {
        this.plugin = plugin;
        this.config = config;
        this.db = db;
        this.registry = registry;
        this.peerClient = peerClient;
    }

    public void start() {
        if (!config.scannerHubPoolEnabled()) {
            plugin.getLogger().info("Scanner hub-pool disabled");
            return;
        }
        long pullPeriod = 20L * config.scannerHubPullIntervalSeconds();
        pullTask = Bukkit.getScheduler().runTaskTimerAsynchronously(
                plugin, this::pullTickSafe, 20L * 20L, pullPeriod);

        if (config.catalogShareHub() && registry != null && registry.enabled()) {
            probeTask = Bukkit.getScheduler().runTaskTimerAsynchronously(
                    plugin, this::hubProbeTickSafe, 20L * 60L, 20L * 90L);
            plugin.getLogger().info("Scanner hub-pool: serving MySQL pool + re-probe");
        } else {
            plugin.getLogger().info("Scanner hub-pool: pull/report → " + primaryHubUrl());
        }
    }

    public void stop() {
        if (pullTask != null) {
            pullTask.cancel();
            pullTask = null;
        }
        if (probeTask != null) {
            probeTask.cancel();
            probeTask = null;
        }
    }

    public int lastHubPullCount() {
        return lastHubPullCount.get();
    }

    public boolean preferSkipPublicScanners() {
        return config.scannerHubPreferOverPublic()
                && lastHubPullCount.get() >= config.scannerHubMinCandidates()
                && (System.currentTimeMillis() - lastPullMs.get()) < config.scannerHubPullIntervalSeconds() * 2000L;
    }

    /** Sync hub vacuum listings into MySQL (hub only). */
    public void publishScannedToHub(List<ScannedServer> servers) {
        if (!config.catalogShareHub() || registry == null || !registry.enabled()) {
            return;
        }
        int n = registry.upsertScannerFromScanned(servers);
        if (n > 0) {
            plugin.getLogger().info("Scanner hub-pool: upserted " + n + " scanned → MySQL (total="
                    + registry.scannerHostCount() + ")");
        }
    }

    /**
     * Blocking pull used before bind when local scanner catalog is thin.
     * @return number of servers ingested into local probe_cache
     */
    public int pullForBind(Player creator) {
        if (!config.scannerHubPoolEnabled() || !config.scannerHubPullBeforeBind()) {
            return 0;
        }
        return pullOnce(creator, true);
    }

    public void reportProbeAsync(
            String host,
            int javaPort,
            Database.ProbeStatus status,
            String mcVersion,
            Integer bedrockPort,
            Integer bedrockProtocol,
            String bedrockVersion,
            String displayName
    ) {
        if (!config.scannerHubPoolEnabled() || !config.scannerHubReportFinds()) {
            return;
        }
        if (host == null || host.isBlank() || javaPort <= 0 || status == null) {
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            JsonObject report = new JsonObject();
            report.addProperty("host", host);
            report.addProperty("javaPort", javaPort);
            report.addProperty("status", status.name());
            if (mcVersion != null) {
                report.addProperty("mcVersion", mcVersion);
            }
            if (bedrockPort != null && bedrockPort > 0) {
                report.addProperty("bedrockPort", bedrockPort);
            }
            if (bedrockProtocol != null && bedrockProtocol > 0) {
                report.addProperty("bedrockProtocol", bedrockProtocol);
            }
            if (bedrockVersion != null) {
                report.addProperty("bedrockVersion", bedrockVersion);
            }
            if (displayName != null) {
                report.addProperty("displayName", displayName);
            }
            report.addProperty("source", "leaf");
            JsonArray arr = new JsonArray();
            arr.add(report);
            JsonObject body = new JsonObject();
            body.add("reports", arr);
            for (String url : hubUrls()) {
                peerClient.scannerReport(url, body);
            }
        });
    }

    private void pullTickSafe() {
        try {
            if (config.catalogShareHub() && registry != null && registry.enabled()) {
                // Hub serves the pool; still keep local probe_cache warm from MySQL for local binds
                pullFromLocalRegistry();
                return;
            }
            pullOnce(null, false);
        } catch (Exception e) {
            plugin.getLogger().warning("Scanner hub-pool pull failed: " + e.getMessage());
        }
    }

    private void hubProbeTickSafe() {
        try {
            hubReprobeTop();
        } catch (Exception e) {
            plugin.getLogger().warning("Scanner hub-pool re-probe failed: " + e.getMessage());
        }
    }

    private int pullOnce(Player creator, boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - lastPullMs.get() < 15_000L) {
            return lastHubPullCount.get();
        }
        JsonObject filters = buildFilters(creator);
        int ingested = 0;
        for (String url : hubUrls()) {
            var resp = peerClient.scannerCandidates(url, filters.deepCopy());
            if (resp.isEmpty() || !resp.get().has("servers") || !resp.get().get("servers").isJsonArray()) {
                continue;
            }
            ingested = Math.max(ingested, ingestCandidates(resp.get().getAsJsonArray("servers")));
            if (ingested > 0) {
                break;
            }
        }
        lastHubPullCount.set(ingested);
        lastPullMs.set(now);
        if (ingested > 0) {
            plugin.getLogger().info("Scanner hub-pool: pulled " + ingested + " candidates");
        }
        return ingested;
    }

    private void pullFromLocalRegistry() {
        if (registry == null || !registry.enabled()) {
            return;
        }
        String branch = localVersionBranch();
        var rows = registry.queryScannerCandidates(
                branch, false, 0, Math.max(40, config.scannerVerifiedLimit()), -50, java.util.Set.of());
        List<ScannedServer> list = new ArrayList<>();
        for (var r : rows) {
            list.add(new ScannedServer(
                    r.host(), r.javaPort(), r.mcVersion(), "online", null,
                    r.motd() != null ? r.motd() : r.displayName(),
                    r.onlinePlayers() == null ? -1 : r.onlinePlayers(),
                    r.maxPlayers() == null ? -1 : r.maxPlayers(),
                    false,
                    r.lastSeenAt() > 0 ? r.lastSeenAt() : System.currentTimeMillis(),
                    "hub"
            ));
            if (r.bedrockPort() > 0 || "OK".equalsIgnoreCase(r.status())) {
                db.recordProbe(
                        r.host(), r.javaPort(),
                        parseStatus(r.status()),
                        r.onlinePlayers(), r.maxPlayers(),
                        r.bedrockPort() > 0 ? r.bedrockPort() : null,
                        r.bedrockProtocol() > 0 ? r.bedrockProtocol() : null,
                        r.bedrockVersion(),
                        r.mcVersion()
                );
            }
        }
        int n = db.upsertScannedServers(list);
        lastHubPullCount.set(n);
        lastPullMs.set(System.currentTimeMillis());
    }

    private int ingestCandidates(JsonArray servers) {
        List<ScannedServer> list = new ArrayList<>();
        for (var el : servers) {
            if (el == null || !el.isJsonObject()) {
                continue;
            }
            JsonObject o = el.getAsJsonObject();
            if (!o.has("host") || !o.has("javaPort")) {
                continue;
            }
            String host = o.get("host").getAsString();
            int port = o.get("javaPort").getAsInt();
            String ver = o.has("mcVersion") && !o.get("mcVersion").isJsonNull()
                    ? o.get("mcVersion").getAsString() : null;
            String label = o.has("displayName") && !o.get("displayName").isJsonNull()
                    ? o.get("displayName").getAsString() : host;
            long seen = o.has("lastSeenAt") && !o.get("lastSeenAt").isJsonNull()
                    ? o.get("lastSeenAt").getAsLong() : System.currentTimeMillis();
            list.add(new ScannedServer(host, port, ver, "online", null, label, -1, -1, false, seen, "hub"));

            Integer bedP = o.has("bedrockPort") && !o.get("bedrockPort").isJsonNull()
                    ? o.get("bedrockPort").getAsInt() : null;
            Integer bedProto = o.has("bedrockProtocol") && !o.get("bedrockProtocol").isJsonNull()
                    ? o.get("bedrockProtocol").getAsInt() : null;
            String bedVer = o.has("bedrockVersion") && !o.get("bedrockVersion").isJsonNull()
                    ? o.get("bedrockVersion").getAsString() : null;
            String status = o.has("status") && !o.get("status").isJsonNull()
                    ? o.get("status").getAsString() : "SEEN";
            if ((bedP != null && bedP > 0) || "OK".equalsIgnoreCase(status)) {
                db.recordProbe(host, port, parseStatus(status), null, null, bedP, bedProto, bedVer, ver);
            }
        }
        return db.upsertScannedServers(list);
    }

    private void hubReprobeTop() {
        if (registry == null || !registry.enabled()) {
            return;
        }
        int timeout = config.scannerProbeTimeoutMs();
        var rows = registry.listScannerHostsForProbe(Math.min(25, config.scannerVerifiedLimit()));
        int ok = 0;
        for (var row : rows) {
            ServerProbe.Result r = ServerProbe.probe(row.host(), row.javaPort(), timeout);
            if (r.status() != ServerProbe.Status.OK) {
                registry.upsertScannerProbeReport(
                        row.host(), row.javaPort(), "DEAD", row.mcVersion(),
                        null, null, null, row.displayName(), "probe", null);
                continue;
            }
            Integer bedP = null;
            Integer bedProto = null;
            String bedVer = null;
            var bed = ServerProbe.findBedrock(row.host(), config.scannerBedrockPorts(), timeout);
            if (bed.isPresent()) {
                bedP = bed.get().port();
                bedProto = bed.get().protocol();
                bedVer = bed.get().version();
            }
            registry.upsertScannerProbeReport(
                    row.host(), row.javaPort(), bedP == null ? "OK" : "OK",
                    row.mcVersion() != null ? row.mcVersion() : null,
                    bedP, bedProto, bedVer, row.displayName(), "probe", null);
            if (bedP == null && config.bindRequireGeyser()) {
                registry.upsertScannerProbeReport(
                        row.host(), row.javaPort(), "NO_GEYSER", row.mcVersion(),
                        null, null, null, row.displayName(), "probe", null);
            }
            ok++;
        }
        if (!rows.isEmpty()) {
            plugin.getLogger().info("Scanner hub-pool re-probe: " + ok + "/" + rows.size() + " java-ok");
        }
    }

    private JsonObject buildFilters(Player creator) {
        JsonObject f = new JsonObject();
        String ver = localMcVersion();
        f.addProperty("mcVersion", ver);
        f.addProperty("versionBranch", RegistryDatabase.versionBranchOf(ver));
        f.addProperty("limit", Math.max(40, config.scannerVerifiedLimit()));
        // Prefer dual-stack candidates from hub when configured; club peers are ordered first
        // in PortalBindService and are accepted without Geyser for Java creators.
        boolean creatorBedrock = creator != null && BedrockPlayers.isBedrock(creator);
        boolean needBedrock = creatorBedrock || config.bindRequireGeyser();
        f.addProperty("needBedrock", needBedrock);
        if (creatorBedrock) {
            f.addProperty("bedrockProtocol", BedrockPlayers.protocolVersion(creator).orElse(0));
        }
        return f;
    }

    private String localMcVersion() {
        VersionCompat vc = plugin.versionCompat();
        if (vc != null && vc.mcVersion() != null) {
            return vc.mcVersion();
        }
        return Bukkit.getMinecraftVersion();
    }

    private String localVersionBranch() {
        return RegistryDatabase.versionBranchOf(localMcVersion());
    }

    private List<String> hubUrls() {
        List<String> urls = config.catalogShareBootstrapUrls();
        if (urls == null || urls.isEmpty()) {
            return List.of(PluginConfig.DEFAULT_CATALOG_HUB);
        }
        return urls;
    }

    private String primaryHubUrl() {
        List<String> urls = hubUrls();
        return urls.isEmpty() ? PluginConfig.DEFAULT_CATALOG_HUB : urls.get(0);
    }

    private static Database.ProbeStatus parseStatus(String s) {
        if (s == null || s.isBlank()) {
            return Database.ProbeStatus.SEEN;
        }
        try {
            return Database.ProbeStatus.valueOf(s.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (Exception e) {
            return Database.ProbeStatus.SEEN;
        }
    }
}
