package io.multiverseportals.federation;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.multiverseportals.MultiversePortalsPlugin;
import io.multiverseportals.config.PluginConfig;
import io.multiverseportals.db.Database;
import io.multiverseportals.db.RegistryDatabase;
import io.multiverseportals.model.RegistryServer;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Syncs MVP club: MySQL registry → SQLite known_mvp_servers, and P2P/hub catalog gossip.
 */
public final class CatalogShareService {

    private final MultiversePortalsPlugin plugin;
    private final PluginConfig config;
    private final Database db;
    private final RegistryDatabase registry;
    private final PeerClient peerClient;
    private BukkitTask task;

    public CatalogShareService(
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
        if (!config.catalogShareEnabled()) {
            plugin.getLogger().info("Catalog share disabled (catalog-share.enabled=false).");
            return;
        }
        int sec = config.catalogShareIntervalSeconds();
        long period = 20L * sec;
        task = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::tickSafe, 20L * 15L, period);
        plugin.getLogger().info("Catalog share on (interval=" + sec + "s, hub=" + config.catalogShareHub()
                + ", pull=" + config.catalogSharePull() + ", push=" + config.catalogSharePush() + ")");
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private void tickSafe() {
        try {
            tick();
        } catch (Exception e) {
            plugin.getLogger().warning("Catalog share tick failed: " + e.getMessage());
        }
    }

    void tick() {
        if (config.catalogShareHub() && registry != null && registry.enabled()) {
            registry.markStaleOffline();
        }
        syncFromRegistry();
        upsertSelf();
        if (config.catalogSharePull()) {
            pullAll();
        }
        // Announce to public hub only when listable (accept-transfers + public address)
        if (config.catalogSharePush() && config.shouldListPublicly()) {
            pushAll();
        }
    }

    /** Snapshot for HTTP /catalog/export (hub prefers fresh registry rows). */
    public JsonObject buildExportPayload() {
        int max = config.catalogShareMaxEntries();
        JsonArray servers = new JsonArray();
        Set<String> seen = new LinkedHashSet<>();

        if (config.catalogShareHub() && registry != null && registry.enabled()) {
            // Wider window for hub directory than travel stale filter
            long hubAge = Math.max(config.registryStaleMs(), 600_000L);
            for (RegistryServer rs : registry.listAll(hubAge)) {
                if (!rs.hasPlugin() || !rs.multiOptIn()) {
                    continue;
                }
                if (!seen.add(rs.serverId().toLowerCase(Locale.ROOT))) {
                    continue;
                }
                servers.add(toJson(
                        rs.serverId(),
                        publicLabel(rs.displayName(), rs.motd(), rs.serverId()),
                        rs.publicHost(),
                        rs.publicPort(),
                        rs.federationUrl(),
                        rs.mcVersion(),
                        rs.caps().bedrockPort(),
                        rs.lastHeartbeat(),
                        80,
                        "registry",
                        rs.caps(),
                        rs.motd(),
                        rs.description(),
                        rs.hasIcon(),
                        rs.registeredAt(),
                        rs.lastOnlineAt(),
                        rs.lastOfflineAt()
                ));
                if (servers.size() >= max) {
                    break;
                }
            }
        }

        if (servers.size() < max) {
            for (Database.KnownMvpServer k : db.listKnownMvp(max)) {
                if (!seen.add(k.serverId().toLowerCase(Locale.ROOT))) {
                    continue;
                }
                servers.add(toJson(
                        k.serverId(),
                        publicLabel(k.displayName(), null, k.serverId()),
                        k.publicHost(),
                        k.publicPort(),
                        k.federationUrl(),
                        k.mcVersion(),
                        k.bedrockPort(),
                        k.lastSeenAt(),
                        k.score(),
                        k.source(),
                        null,
                        null,
                        null,
                        false,
                        0L,
                        k.lastSeenAt(),
                        0L
                ));
                if (servers.size() >= max) {
                    break;
                }
            }
        }

        if (config.shouldListPublicly()) {
            enrichSelfBranding(servers);
        } else {
            // Local-only: never embed ourselves in catalog snapshots we push/export
            removeSelf(servers);
        }
        JsonObject out = new JsonObject();
        out.addProperty("ok", true);
        out.addProperty("serverId", config.serverId());
        out.addProperty("hub", config.catalogShareHub());
        out.addProperty("listPublicly", config.shouldListPublicly());
        out.add("servers", servers);
        out.add("portals", config.shouldListPublicly() ? buildPortalsArray() : new JsonArray());
        return out;
    }

    private void removeSelf(JsonArray servers) {
        String selfId = config.serverId();
        for (int i = servers.size() - 1; i >= 0; i--) {
            JsonElement el = servers.get(i);
            if (el.isJsonObject() && selfId.equalsIgnoreCase(str(el.getAsJsonObject(), "serverId"))) {
                servers.remove(i);
            }
        }
    }

    /** Attach local MOTD name/description/icon so the hub can store list branding. */
    private void enrichSelfBranding(JsonArray servers) {
        io.multiverseportals.util.ServerBranding.Snapshot brand =
                io.multiverseportals.util.ServerBranding.local();
        String selfId = config.serverId();
        JsonObject self = null;
        for (JsonElement el : servers) {
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject o = el.getAsJsonObject();
            if (selfId.equalsIgnoreCase(str(o, "serverId"))) {
                self = o;
                break;
            }
        }
        if (self == null) {
            self = toJson(
                    selfId,
                    brand.name(),
                    config.publicHost(),
                    config.publicPort(),
                    config.registryFederationUrlOverride().orElse(
                            "http://" + config.publicHost() + ":" + config.federationPort() + config.federationPath()),
                    plugin.versionCompat() != null ? plugin.versionCompat().mcVersion() : Bukkit.getMinecraftVersion(),
                    0,
                    System.currentTimeMillis(),
                    100,
                    "self",
                    null,
                    brand.motd(),
                    brand.description(),
                    brand.hasIcon(),
                    0L,
                    System.currentTimeMillis(),
                    0L
            );
            servers.add(self);
        }
        self.addProperty("displayName", brand.name());
        self.addProperty("description", brand.description());
        self.addProperty("motd", brand.motd());
        self.addProperty("acceptTransfers", config.acceptTransfersEnabled());
        if (brand.hasIcon()) {
            self.addProperty("hasIcon", true);
            self.addProperty("iconPngBase64", brand.iconBase64());
        }
    }

    private JsonArray buildPortalsArray() {
        JsonArray portals = new JsonArray();
        if (!config.registryPublishPortals() || !config.shouldListPublicly()) {
            return portals;
        }
        // Hub: full graph from MySQL. Peers: local portals so announce/export carries edges.
        if (registry != null && registry.enabled()) {
            for (var rp : registry.listPortals(Math.max(50, config.catalogShareMaxEntries() * 4))) {
                portals.add(registryPortalToJson(rp));
            }
            return portals;
        }
        for (var p : db.listPortals()) {
            portals.add(localPortalToJson(p, Map.of()));
        }
        return portals;
    }

    private JsonObject registryPortalToJson(io.multiverseportals.model.RegistryPortal rp) {
        JsonObject o = new JsonObject();
        o.addProperty("serverId", rp.serverId());
        o.addProperty("portalId", rp.portalId());
        o.addProperty("type", rp.portalType());
        o.addProperty("status", rp.status());
        o.addProperty("world", rp.world());
        o.addProperty("x", rp.x());
        o.addProperty("y", rp.y());
        o.addProperty("z", rp.z());
        o.addProperty("yaw", rp.yaw());
        if (rp.name() != null) {
            o.addProperty("name", rp.name());
        }
        o.addProperty("destKind", rp.destKind() == null ? "none" : rp.destKind());
        if (rp.destServerId() != null) {
            o.addProperty("destServerId", rp.destServerId());
        }
        if (rp.destHost() != null) {
            o.addProperty("destHost", rp.destHost());
        }
        if (rp.destPort() > 0) {
            o.addProperty("destPort", rp.destPort());
        }
        if (rp.destJavaPort() > 0) {
            o.addProperty("destJavaPort", rp.destJavaPort());
        }
        if (rp.destPortalId() != null) {
            o.addProperty("destPortalId", rp.destPortalId());
        }
        if (rp.destLabel() != null) {
            o.addProperty("destLabel", rp.destLabel());
        }
        o.addProperty("returnCapable", rp.returnCapable());
        o.addProperty("updatedAt", rp.updatedAt());
        o.addProperty("edge", rp.edgeLabel());
        if (rp.hasSigns()) {
            JsonArray signs = new JsonArray();
            for (String s : rp.signs()) {
                signs.add(s);
            }
            o.add("signs", signs);
        }
        return o;
    }

    private JsonObject localPortalToJson(
            io.multiverseportals.model.Portal p,
            Map<String, List<String>> commentSigns
    ) {
        JsonObject o = new JsonObject();
        o.addProperty("serverId", config.serverId());
        o.addProperty("portalId", p.id());
        o.addProperty("type", p.type().name());
        o.addProperty("status", p.status().name());
        o.addProperty("world", p.frame().world());
        o.addProperty("x", p.frame().x());
        o.addProperty("y", p.frame().y());
        o.addProperty("z", p.frame().z());
        o.addProperty("yaw", p.frame().yaw());
        if (p.name() != null) {
            o.addProperty("name", p.name());
        }
        String destKind = "none";
        if (p.type() == io.multiverseportals.model.PortalType.PAIR && p.pairServerId() != null) {
            destKind = "pair";
            o.addProperty("destServerId", p.pairServerId());
            if (p.pairPortalId() != null) {
                o.addProperty("destPortalId", p.pairPortalId());
            }
            o.addProperty("returnCapable", p.pairPortalId() != null && !p.pairPortalId().isBlank());
        } else if (p.hasBoundDestination()) {
            destKind = "bound";
            o.addProperty("destHost", p.boundHost());
            if (p.boundPort() > 0) {
                o.addProperty("destPort", p.boundPort());
            }
            int javaPort = p.boundJavaPort() > 0 ? p.boundJavaPort() : p.boundPort();
            if (javaPort > 0) {
                o.addProperty("destJavaPort", javaPort);
            }
            if (p.boundVersion() != null) {
                o.addProperty("destLabel", p.boundVersion());
            }
            if (p.boundDestPortalId() != null) {
                o.addProperty("destPortalId", p.boundDestPortalId());
            }
            o.addProperty("returnCapable", p.boundDestPortalId() != null);
        } else {
            o.addProperty("returnCapable", false);
        }
        o.addProperty("destKind", destKind);
        o.addProperty("updatedAt", System.currentTimeMillis());
        List<String> signs = commentSigns.getOrDefault(p.id(), List.of());
        if (!signs.isEmpty()) {
            JsonArray arr = new JsonArray();
            for (String s : signs) {
                arr.add(s);
            }
            o.add("signs", arr);
        }
        return o;
    }

    /**
     * Live snapshot of THIS server's federation portals + nearby comment signs.
     * Must run on the main thread (world/sign access).
     */
    public JsonObject buildLivePortalsPayload() {
        JsonArray portals = new JsonArray();
        for (var p : db.listPortals()) {
            JsonObject o = new JsonObject();
            o.addProperty("portalId", p.id());
            o.addProperty("type", p.type().name());
            o.addProperty("status", p.status().name());
            o.addProperty("world", p.frame().world());
            o.addProperty("x", p.frame().x());
            o.addProperty("y", p.frame().y());
            o.addProperty("z", p.frame().z());
            o.addProperty("yaw", p.frame().yaw());
            if (p.name() != null) {
                o.addProperty("name", p.name());
            }
            JsonObject dest = new JsonObject();
            if (p.type() == io.multiverseportals.model.PortalType.PAIR && p.pairServerId() != null) {
                dest.addProperty("kind", "pair");
                dest.addProperty("serverId", p.pairServerId());
                if (p.pairPortalId() != null) {
                    dest.addProperty("portalId", p.pairPortalId());
                }
            } else if (p.hasBoundDestination()) {
                dest.addProperty("kind", "bound");
                dest.addProperty("host", p.boundHost());
                dest.addProperty("port", p.boundPort());
                if (p.boundJavaPort() > 0) {
                    dest.addProperty("javaPort", p.boundJavaPort());
                }
                if (p.boundVersion() != null) {
                    dest.addProperty("label", p.boundVersion());
                }
            } else {
                dest.addProperty("kind", "none");
            }
            o.add("dest", dest);
            List<String> signs = io.multiverseportals.portal.PortalCommentSigns.collect(p);
            if (!signs.isEmpty()) {
                JsonArray arr = new JsonArray();
                for (String s : signs) {
                    arr.add(s);
                }
                o.add("signs", arr);
            }
            portals.add(o);
        }
        JsonObject out = new JsonObject();
        out.addProperty("ok", true);
        out.addProperty("serverId", config.serverId());
        out.addProperty("displayName", config.displayName());
        out.addProperty("publicHost", config.publicHost());
        out.addProperty("publicPort", config.publicPort());
        out.addProperty("count", portals.size());
        out.add("portals", portals);
        return out;
    }

    /** Registry-backed portals for a serverId (hub), signs only if present. */
    public JsonObject buildRegistryPortalsPayload(String serverId) {
        String sid = serverId == null || serverId.isBlank() ? config.serverId() : serverId;
        JsonArray portals = new JsonArray();
        if (registry != null && registry.enabled()) {
            for (var rp : registry.listPortalsOnServer(sid)) {
                JsonObject o = new JsonObject();
                o.addProperty("portalId", rp.portalId());
                o.addProperty("type", rp.portalType());
                o.addProperty("status", rp.status());
                o.addProperty("world", rp.world());
                o.addProperty("x", rp.x());
                o.addProperty("y", rp.y());
                o.addProperty("z", rp.z());
                JsonObject dest = new JsonObject();
                dest.addProperty("kind", rp.destKind() == null ? "none" : rp.destKind());
                if (rp.destServerId() != null) {
                    dest.addProperty("serverId", rp.destServerId());
                }
                if (rp.destHost() != null) {
                    dest.addProperty("host", rp.destHost());
                }
                if (rp.destPort() > 0) {
                    dest.addProperty("port", rp.destPort());
                }
                if (rp.destJavaPort() > 0) {
                    dest.addProperty("javaPort", rp.destJavaPort());
                }
                if (rp.destLabel() != null) {
                    dest.addProperty("label", rp.destLabel());
                }
                o.add("dest", dest);
                if (rp.hasSigns()) {
                    JsonArray arr = new JsonArray();
                    for (String s : rp.signs()) {
                        arr.add(s);
                    }
                    o.add("signs", arr);
                }
                portals.add(o);
            }
        }
        JsonObject out = new JsonObject();
        out.addProperty("ok", true);
        out.addProperty("serverId", sid);
        out.addProperty("source", "registry");
        out.addProperty("count", portals.size());
        out.add("portals", portals);
        return out;
    }

    public JsonObject handleAnnounce(JsonObject body) {
        if (body != null && body.has("offline") && !body.get("offline").isJsonNull()
                && body.get("offline").getAsBoolean()) {
            String owner = str(body, "serverId");
            if (owner == null || owner.isBlank()) {
                owner = str(body, "from");
            }
            int marked = 0;
            if (owner != null && !owner.isBlank()
                    && !owner.equalsIgnoreCase(config.serverId())
                    && config.catalogShareHub()
                    && registry != null && registry.enabled()) {
                registry.markPeerOffline(owner);
                marked = 1;
            }
            JsonObject out = new JsonObject();
            out.addProperty("ok", true);
            out.addProperty("offline", true);
            out.addProperty("marked", marked);
            return out;
        }
        int n = ingestServers(body, "gossip");
        int portals = ingestPortals(body);
        JsonObject out = new JsonObject();
        out.addProperty("ok", true);
        out.addProperty("upserted", n);
        out.addProperty("portals", portals);
        return out;
    }

    /** Hub: accept peer portal edges from announce/export body. */
    public int ingestPortals(JsonObject body) {
        if (body == null || !config.catalogShareHub()
                || registry == null || !registry.enabled() || !config.registryPublishPortals()) {
            return 0;
        }
        if (!body.has("portals") || !body.get("portals").isJsonArray()) {
            return 0;
        }
        String owner = str(body, "serverId");
        if (owner == null || owner.isBlank() || owner.equalsIgnoreCase(config.serverId())) {
            return 0;
        }
        int n = registry.ingestPeerPortals(owner, body.getAsJsonArray("portals"));
        if (n > 0) {
            plugin.getLogger().info("Catalog portals from " + owner + ": " + n);
        }
        return n;
    }

    public int ingestServers(JsonObject body, String defaultSource) {
        if (body == null || !body.has("servers") || !body.get("servers").isJsonArray()) {
            return 0;
        }
        int n = 0;
        String self = config.serverId();
        for (JsonElement el : body.getAsJsonArray("servers")) {
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject o = el.getAsJsonObject();
            String id = str(o, "serverId");
            if (id == null || id.isBlank() || id.equalsIgnoreCase(self)) {
                continue;
            }
            String host = str(o, "publicHost");
            int port = o.has("publicPort") ? o.get("publicPort").getAsInt() : 0;
            if (host == null || host.isBlank() || port <= 0) {
                continue;
            }
            // Never list LAN/loopback in the public directory
            if (io.multiverseportals.util.PublicEndpoint.isPrivateOrLocal(host)) {
                continue;
            }
            if (o.has("acceptTransfers") && !o.get("acceptTransfers").isJsonNull()
                    && !o.get("acceptTransfers").getAsBoolean()) {
                continue;
            }
            // Hub: skip hosts that do not answer SLP from the outside
            if (config.catalogShareHub() && registry != null && registry.enabled()
                    && !io.multiverseportals.scanner.ServerProbe.isReachable(host, port, 2200)) {
                plugin.getLogger().info("Catalog skip " + id + " @ " + host + ":" + port + " — unreachable from hub");
                continue;
            }
            String source = str(o, "source");
            if (source == null || source.isBlank()) {
                source = defaultSource;
            }
            double score = o.has("score") ? o.get("score").getAsDouble() : 55;
            long seen = o.has("lastSeenAt") ? o.get("lastSeenAt").getAsLong() : System.currentTimeMillis();
            int bed = o.has("bedrockPort") ? o.get("bedrockPort").getAsInt() : 0;
            db.upsertKnownMvp(
                    id,
                    str(o, "displayName"),
                    host,
                    port,
                    str(o, "federationUrl"),
                    str(o, "mcVersion"),
                    bed,
                    seen,
                    score,
                    source
            );
            // Central hub MySQL — peers announce over HTTPS, no JDBC on their side
            if (config.catalogShareHub() && registry != null && registry.enabled()) {
                var brand = io.multiverseportals.util.ServerBranding.fromParts(
                        str(o, "displayName"),
                        str(o, "description"),
                        str(o, "motd"),
                        str(o, "iconPngBase64")
                );
                String name = brand.name().isBlank() ? str(o, "displayName") : brand.name();
                registry.upsertPeerAnnounce(
                        id,
                        name,
                        host,
                        port,
                        str(o, "federationUrl"),
                        str(o, "mcVersion"),
                        seen,
                        capsFromAnnounce(o, bed),
                        brand.description(),
                        brand.motd(),
                        brand.hasIcon() ? brand.iconPng() : null
                );
                if (!brand.hasIcon()) {
                    scheduleHubResolveBranding(id, host, port);
                }
            }
            n++;
        }
        return n;
    }

    /** Hub fallback: SLP ping public address for MOTD + favicon when peer omitted icon. */
    private void scheduleHubResolveBranding(String serverId, String host, int port) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                var info = io.multiverseportals.scanner.ServerProbe.probeStatus(host, port, 2500);
                if (info.status() == io.multiverseportals.scanner.ServerProbe.Status.UNREACHABLE) {
                    return;
                }
                var brand = io.multiverseportals.util.ServerBranding.fromMotdAndFavicon(
                        info.motd(), info.faviconPng());
                if (registry != null && registry.enabled()) {
                    registry.updateBranding(
                            serverId,
                            brand.name(),
                            brand.description(),
                            brand.motd(),
                            brand.hasIcon() ? brand.iconPng() : null
                    );
                }
            } catch (Exception e) {
                plugin.getLogger().fine("Hub branding resolve failed for " + serverId + ": " + e.getMessage());
            }
        });
    }

    private static io.multiverseportals.compat.ServerCaps capsFromAnnounce(JsonObject o, int bedrockPortFallback) {
        if (o == null || !o.has("caps") || !o.get("caps").isJsonObject()) {
            int bed = bedrockPortFallback > 0 ? bedrockPortFallback : 0;
            return new io.multiverseportals.compat.ServerCaps(
                    "", bed > 0, false, "", bed, 0, "", bed > 0,
                    false, 0, 0, 0, false, 0, false, false
            );
        }
        JsonObject c = o.getAsJsonObject("caps");
        int bedPort = c.has("bedrockPort") ? c.get("bedrockPort").getAsInt() : bedrockPortFallback;
        int bedProto = c.has("bedrockProtocol") ? c.get("bedrockProtocol").getAsInt() : 0;
        boolean geyser = c.has("geyser") && c.get("geyser").getAsBoolean();
        boolean floodgate = c.has("floodgate") && c.get("floodgate").getAsBoolean();
        boolean acceptBed = c.has("acceptBedrock") ? c.get("acceptBedrock").getAsBoolean() : geyser;
        String mvpVer = c.has("mvpVersion") && !c.get("mvpVersion").isJsonNull()
                ? c.get("mvpVersion").getAsString() : "";
        String geyVer = c.has("geyserVersion") && !c.get("geyserVersion").isJsonNull()
                ? c.get("geyserVersion").getAsString() : "";
        String bedVer = c.has("bedrockVersion") && !c.get("bedrockVersion").isJsonNull()
                ? c.get("bedrockVersion").getAsString() : "";
        return new io.multiverseportals.compat.ServerCaps(
                mvpVer, geyser, floodgate, geyVer, bedPort, bedProto, bedVer, acceptBed,
                false, 0, 0, 0, false, 0, false, false
        );
    }

    private void syncFromRegistry() {
        if (registry == null || !registry.enabled()) {
            return;
        }
        for (RegistryServer rs : registry.listMultiTargets(config.registryStaleMs() * 2)) {
            if (!rs.hasPlugin()) {
                continue;
            }
            db.upsertKnownMvp(
                    rs.serverId(),
                    publicLabel(rs.displayName(), rs.motd(), rs.serverId()),
                    rs.publicHost(),
                    rs.publicPort(),
                    rs.federationUrl(),
                    rs.mcVersion(),
                    rs.caps().bedrockPort(),
                    rs.lastHeartbeat(),
                    90,
                    "registry"
            );
        }
    }

    private void upsertSelf() {
        String fed = config.registryFederationUrlOverride()
                .orElse("http://" + config.publicHost() + ":" + config.federationPort() + config.federationPath());
        String ver = plugin.versionCompat() != null ? plugin.versionCompat().mcVersion() : Bukkit.getMinecraftVersion();
        db.upsertKnownMvp(
                config.serverId(),
                config.displayName(),
                config.publicHost(),
                config.publicPort(),
                fed,
                ver,
                0,
                System.currentTimeMillis(),
                100,
                "self"
        );
    }

    private void pullAll() {
        for (String base : catalogTargets(true)) {
            peerClient.catalogExport(base).ifPresent(resp -> {
                int n = ingestServers(resp, baseContainsBootstrap(base) ? "hub" : "gossip");
                int portals = ingestPortals(resp);
                if (n > 0 || portals > 0) {
                    plugin.getLogger().info("Catalog pull from " + base + ": servers+" + n
                            + (portals > 0 ? " portals+" + portals : ""));
                }
            });
        }
    }

    private void pushAll() {
        JsonObject snap = buildExportPayload();
        for (String base : catalogTargets(false)) {
            peerClient.catalogAnnounce(base, snap).ifPresent(resp -> {
                if (resp.has("upserted") && resp.get("upserted").getAsInt() > 0) {
                    plugin.getLogger().fine("Catalog push to " + base + " upserted=" + resp.get("upserted").getAsInt());
                }
                if (resp.has("portals") && resp.get("portals").getAsInt() > 0) {
                    plugin.getLogger().fine("Catalog push to " + base + " portals=" + resp.get("portals").getAsInt());
                }
            });
        }
    }

    /**
     * Immediate catalog push (servers + local portal edges) — call after bind/create/delete
     * so the public map updates without waiting for the share interval.
     */
    public void pushNowAsync() {
        if (!config.catalogShareEnabled() || !config.catalogSharePush() || !config.shouldListPublicly()) {
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                pushAll();
            } catch (Exception e) {
                plugin.getLogger().warning("Catalog pushNow failed: " + e.getMessage());
            }
        });
    }

    /**
     * Blocking graceful-shutdown ping to the hub so the map can mark this server offline
     * immediately (must run on the disable thread before HTTP/DB are torn down).
     */
    public void notifyShutdownBlocking() {
        if (!config.catalogShareEnabled() || !config.shouldListPublicly()) {
            return;
        }
        JsonObject body = new JsonObject();
        body.addProperty("serverId", config.serverId());
        body.addProperty("offline", true);
        body.addProperty("publicHost", config.publicHost());
        body.addProperty("publicPort", config.publicPort());
        int ok = 0;
        for (String base : catalogTargets(false)) {
            try {
                if (peerClient.catalogAnnounceQuick(base, body).isPresent()) {
                    ok++;
                }
            } catch (Exception ignored) {
            }
        }
        if (ok > 0) {
            plugin.getLogger().info("Catalog shutdown notify sent (" + ok + ")");
        }
    }

    private boolean baseContainsBootstrap(String base) {
        String b = trimSlash(base).toLowerCase(Locale.ROOT);
        for (String u : config.catalogShareBootstrapUrls()) {
            if (trimSlash(u).toLowerCase(Locale.ROOT).equals(b)) {
                return true;
            }
        }
        return false;
    }

    private List<String> catalogTargets(boolean forPull) {
        LinkedHashSet<String> urls = new LinkedHashSet<>();
        for (String u : config.catalogShareBootstrapUrls()) {
            urls.add(trimSlash(u));
        }
        for (String u : db.listKnownMvpFederationUrls(24)) {
            urls.add(trimSlash(u));
        }
        if (registry != null && registry.enabled()) {
            for (RegistryServer rs : registry.listMultiTargets(config.registryStaleMs() * 3)) {
                if (rs.federationUrl() != null && !rs.federationUrl().isBlank()) {
                    urls.add(trimSlash(rs.federationUrl()));
                }
            }
        }
        String selfFed = config.registryFederationUrlOverride()
                .orElse("http://" + config.publicHost() + ":" + config.federationPort() + config.federationPath());
        String selfKey = trimSlash(selfFed).toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String u : urls) {
            if (u == null || u.isBlank()) {
                continue;
            }
            if (trimSlash(u).toLowerCase(Locale.ROOT).equals(selfKey)) {
                continue;
            }
            out.add(u);
            if (out.size() >= 16) {
                break;
            }
        }
        // Pull prefers bootstrap first (already LinkedHashSet order)
        if (!forPull && out.isEmpty()) {
            return out;
        }
        return out;
    }

    /** Prefer a real name over a bare machine id; fall back to MOTD then id. */
    private static String publicLabel(String displayName, String motd, String serverId) {
        String name = displayName == null ? "" : displayName.trim();
        String id = serverId == null ? "" : serverId.trim();
        if (!name.isBlank() && (id.isBlank() || !name.equalsIgnoreCase(id))) {
            return name;
        }
        if (motd != null) {
            String m = motd.replace('\n', ' ').replace('\r', ' ').replaceAll("\\s+", " ").trim();
            if (!m.isBlank()) {
                return m.length() <= 64 ? m : m.substring(0, 64).trim();
            }
        }
        return !name.isBlank() ? name : id;
    }

    private static JsonObject toJson(
            String id,
            String name,
            String host,
            int port,
            String fed,
            String mc,
            int bed,
            long seen,
            double score,
            String source,
            io.multiverseportals.compat.ServerCaps caps,
            String motd,
            String description,
            boolean hasIcon,
            long registeredAt,
            long lastOnlineAt,
            long lastOfflineAt
    ) {
        JsonObject o = new JsonObject();
        o.addProperty("serverId", id);
        if (name != null) {
            o.addProperty("displayName", name);
        }
        if (motd != null && !motd.isBlank()) {
            o.addProperty("motd", motd);
        }
        if (description != null && !description.isBlank()) {
            o.addProperty("description", description);
        }
        if (hasIcon && id != null && !id.isBlank()) {
            o.addProperty("hasIcon", true);
            o.addProperty("iconUrl", "/mvp/v1/icon/" + id);
        }
        o.addProperty("publicHost", host);
        o.addProperty("publicPort", port);
        if (fed != null) {
            o.addProperty("federationUrl", fed);
        }
        if (mc != null) {
            o.addProperty("mcVersion", mc);
        }
        if (bed > 0) {
            o.addProperty("bedrockPort", bed);
        }
        o.addProperty("lastSeenAt", seen);
        if (registeredAt > 0) {
            o.addProperty("registeredAt", registeredAt);
        }
        if (lastOnlineAt > 0) {
            o.addProperty("lastOnlineAt", lastOnlineAt);
        }
        if (lastOfflineAt > 0) {
            o.addProperty("lastOfflineAt", lastOfflineAt);
        }
        boolean offline = lastOfflineAt > 0 && (lastOnlineAt <= 0 || lastOfflineAt >= lastOnlineAt);
        o.addProperty("online", !offline);
        long ageSec = seen > 0 ? Math.max(0L, (System.currentTimeMillis() - seen) / 1000L) : -1L;
        // Graceful shutdown stamps lastOfflineAt while heartbeat is still fresh — force map offline.
        if (offline) {
            ageSec = Math.max(ageSec, 5400L);
        }
        o.addProperty("lastPingAgeSec", ageSec);
        o.addProperty("lastPingAgo", offline ? "offline" : formatPingAgo(seen));
        o.addProperty("score", score);
        o.addProperty("source", source);
        if (caps != null) {
            JsonObject c = new JsonObject();
            if (!caps.mvpVersion().isBlank()) {
                c.addProperty("mvpVersion", caps.mvpVersion());
            }
            c.addProperty("geyser", caps.hasGeyser());
            c.addProperty("floodgate", caps.hasFloodgate());
            if (!caps.geyserVersion().isBlank()) {
                c.addProperty("geyserVersion", caps.geyserVersion());
            }
            if (caps.bedrockPort() > 0) {
                c.addProperty("bedrockPort", caps.bedrockPort());
            }
            if (caps.bedrockProtocol() > 0) {
                c.addProperty("bedrockProtocol", caps.bedrockProtocol());
            }
            if (!caps.bedrockVersion().isBlank()) {
                c.addProperty("bedrockVersion", caps.bedrockVersion());
            }
            c.addProperty("acceptBedrock", caps.acceptBedrock());
            JsonObject ing = new JsonObject();
            ing.addProperty("enabled", caps.ingressEnabled());
            ing.addProperty("maxOnline", caps.ingressMaxOnline());
            ing.addProperty("reserveSlots", caps.ingressReserveSlots());
            ing.addProperty("minScore", caps.ingressMinScore());
            ing.addProperty("denyUnknownScore", caps.ingressDenyUnknownScore());
            ing.addProperty("maxArrivalsPerHour", caps.ingressMaxArrivalsPerHour());
            c.add("ingress", ing);
            JsonObject inv = new JsonObject();
            inv.addProperty("export", caps.exportInventory());
            inv.addProperty("import", caps.importInventory());
            c.add("inventory", inv);
            o.add("caps", c);
        }
        return o;
    }

    static String formatPingAgo(long seenAtMs) {
        if (seenAtMs <= 0) {
            return "never";
        }
        long ageMs = Math.max(0L, System.currentTimeMillis() - seenAtMs);
        long sec = ageMs / 1000L;
        if (sec < 60) {
            return sec + "s ago";
        }
        long min = sec / 60L;
        if (min < 60) {
            return min + "m ago";
        }
        long hr = min / 60L;
        if (hr < 48) {
            return hr + "h ago";
        }
        return (hr / 24L) + "d ago";
    }

    private static String str(JsonObject o, String key) {
        if (!o.has(key) || o.get(key).isJsonNull()) {
            return null;
        }
        try {
            return o.get(key).getAsString();
        } catch (Exception e) {
            return null;
        }
    }

    private static String trimSlash(String url) {
        if (url == null) {
            return "";
        }
        String u = url.trim();
        while (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        return u;
    }
}
