package io.multiverseportals.portal;

import io.multiverseportals.MultiversePortalsPlugin;
import io.multiverseportals.compat.BedrockPlayers;
import io.multiverseportals.compat.VersionCompat;
import io.multiverseportals.config.PluginConfig;
import io.multiverseportals.db.Database;
import io.multiverseportals.model.Portal;
import io.multiverseportals.model.PortalStatus;
import io.multiverseportals.model.PortalType;
import io.multiverseportals.model.TrustedPeer;
import io.multiverseportals.scanner.ScannedServer;
import io.multiverseportals.scanner.ServerProbe;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * On MULTI portal create: search a live compatible host (Java + preferably Geyser),
 * then permanently sticky-bind the portal to that destination until the sign is broken.
 */
public final class PortalBindService {

    private final MultiversePortalsPlugin plugin;
    private final Database db;
    private final PluginConfig config;
    private final MiniMessage mm = MiniMessage.miniMessage();
    private final Set<String> binding = ConcurrentHashMap.newKeySet();
    private final Map<String, BukkitTask> fxTasks = new ConcurrentHashMap<>();

    public PortalBindService(MultiversePortalsPlugin plugin, Database db, PluginConfig config) {
        this.plugin = plugin;
        this.db = db;
        this.config = config;
    }

    public void startBind(Portal portal, Player creator) {
        if (portal.type() != PortalType.MULTI) {
            return;
        }
        if (!binding.add(portal.id())) {
            return;
        }
        portal.clearBound();
        portal.setStatus(PortalStatus.BINDING);
        db.savePortal(portal);
        PortalSigns.update(portal);
        startWhiteFx(portal);
        if (plugin.portalMatter() != null) {
            plugin.portalMatter().remove(portal.id());
        }
        // Presence ping as soon as Random bind starts (map updates without waiting 15 min)
        if (plugin.portalService() != null) {
            plugin.portalService().publishPortalGraphAsync();
        }

        UUID creatorId = creator != null ? creator.getUniqueId() : portal.creator();
        boolean creatorBedrock = creator != null && BedrockPlayers.isBedrock(creator);
        // Offline creator / resume must still honor bind-require-geyser (Bedrock players can't use Java-only dests)
        boolean needBedrock = creatorBedrock || config.bindRequireGeyser();
        int bedProto = creatorBedrock ? BedrockPlayers.protocolVersion(creator).orElse(0) : 0;
        String bedVer = creatorBedrock ? BedrockPlayers.versionString(creator) : "";
        final String portalId = portal.id();

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            BoundDest dest = null;
            try {
                if (plugin.scannerPool() != null) {
                    plugin.scannerPool().pullForBind(creator);
                }
                dest = searchBindTarget(portalId, portal, needBedrock, bedProto, bedVer);
            } catch (Throwable t) {
                plugin.getLogger().warning("Bind search failed for " + portalId + ": " + t.getMessage());
            }
            BoundDest finalDest = dest;
            Bukkit.getScheduler().runTask(plugin, () -> finishBind(portalId, finalDest, creatorId));
        });
    }

    /** Resume unfinished MULTI binds after restart. Already-linked portals are left alone. */
    public void resumePending() {
        for (Portal p : db.listPortals()) {
            if (p.type() != PortalType.MULTI) {
                continue;
            }
            if (p.status() == PortalStatus.BINDING
                    || p.status() == PortalStatus.BIND_FAILED
                    || (p.status() == PortalStatus.ACTIVE && !p.hasBoundDestination())) {
                retryBind(p);
            }
        }
    }

    /** Retry failed/pending bind: fixed host:port from [To] sign, else scanner/registry search. */
    public void retryBind(Portal portal) {
        var fixed = fixedEndpoint(portal);
        if (fixed.isPresent()) {
            bindFixedHost(portal, Bukkit.getPlayer(portal.creator()), fixed.get().host(), fixed.get().port());
        } else {
            startBind(portal, Bukkit.getPlayer(portal.creator()));
        }
    }

    public void cancel(String portalId) {
        binding.remove(portalId);
        stopWhiteFx(portalId);
    }

    /**
     * Sticky bind to an explicit host:port from a [To] sign (no scanner / no registry id).
     */
    public void bindFixedHost(Portal portal, Player creator, String host, int javaPort) {
        if (portal.type() != PortalType.MULTI || host == null || host.isBlank() || javaPort <= 0) {
            return;
        }
        if (!binding.add(portal.id())) {
            return;
        }
        portal.clearBound();
        portal.setStatus(PortalStatus.BINDING);
        db.savePortal(portal);
        PortalSigns.update(portal);
        startWhiteFx(portal);
        if (plugin.portalMatter() != null) {
            plugin.portalMatter().remove(portal.id());
        }
        if (plugin.portalService() != null) {
            plugin.portalService().publishPortalGraphAsync();
        }

        UUID creatorId = creator != null ? creator.getUniqueId() : portal.creator();
        boolean needBedrock = (creator != null && BedrockPlayers.isBedrock(creator))
                || config.bindRequireGeyser();
        final String portalId = portal.id();
        final String hostF = host.trim();
        final int javaF = javaPort;

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            BoundDest dest = null;
            try {
                dest = probeFixedHost(hostF, javaF, needBedrock);
            } catch (Throwable t) {
                plugin.getLogger().warning("Fixed bind failed for " + portalId + ": " + t.getMessage());
            }
            BoundDest finalDest = dest;
            Bukkit.getScheduler().runTask(plugin, () -> finishBind(portalId, finalDest, creatorId));
        });
    }

    private BoundDest probeFixedHost(String host, int javaPort, boolean needBedrock) {
        int timeout = config.scannerProbeTimeoutMs();
        ServerProbe.Result r = ServerProbe.probe(host, javaPort, timeout);
        if (r.status() != ServerProbe.Status.OK && r.status() != ServerProbe.Status.FULL) {
            plugin.getLogger().info("Fixed bind " + host + ":" + javaPort + " → " + r.status());
            return null;
        }
        String label = host;
        int transferPort = javaPort;
        int bedProto = 0;
        String bedVer = "";
        if (needBedrock || config.bindPreferGeyser()) {
            var bed = ServerProbe.findBedrock(host, config.scannerBedrockPorts(), timeout);
            if (bed.isPresent()) {
                transferPort = bed.get().port();
                bedProto = bed.get().protocol();
                bedVer = bed.get().version() == null ? "" : bed.get().version();
                label = truncate(bed.get().version() != null ? bed.get().version() : label, 40);
            } else if (needBedrock && config.bindRequireGeyser()) {
                plugin.getLogger().info("Fixed bind " + host + ":" + javaPort + " — no Geyser for Bedrock");
                return null;
            }
        }
        return new BoundDest(host, transferPort, javaPort, label, bedProto, bedVer);
    }

    /**
     * Fixed [To] target stored as {@code @host:port} in multiPool (not a registry id).
     */
    public static Optional<HostPort> fixedEndpoint(Portal portal) {
        if (portal == null) {
            return Optional.empty();
        }
        for (String s : portal.multiPool()) {
            if (s == null || !s.startsWith("@")) {
                continue;
            }
            Optional<HostPort> hp = parseHostPort(s.substring(1));
            if (hp.isPresent()) {
                return hp;
            }
        }
        return Optional.empty();
    }

    public static Optional<HostPort> parseHostPort(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String s = raw.trim();
        int colon = s.lastIndexOf(':');
        if (colon <= 0 || colon >= s.length() - 1) {
            return Optional.empty();
        }
        String host = s.substring(0, colon).trim();
        int port;
        try {
            port = Integer.parseInt(s.substring(colon + 1).trim());
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
        if (host.isEmpty() || port <= 0 || port > 65535) {
            return Optional.empty();
        }
        return Optional.of(new HostPort(host, port));
    }

    public record HostPort(String host, int port) {
        public String display() {
            return host + ":" + port;
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        String t = s.replace('\n', ' ').trim();
        return t.length() <= max ? t : t.substring(0, max);
    }

    private void finishBind(String portalId, BoundDest dest, UUID notify) {
        binding.remove(portalId);
        Optional<Portal> live = db.findPortal(portalId);
        if (live.isEmpty()) {
            stopWhiteFx(portalId);
            return;
        }
        Portal portal = live.get();
        if (portal.type() != PortalType.MULTI) {
            stopWhiteFx(portalId);
            return;
        }
        if (dest == null) {
            portal.setStatus(PortalStatus.BIND_FAILED);
            portal.clearBound();
            db.savePortal(portal);
            PortalSigns.update(portal);
            // Keep white FX running (BIND_FAILED still shows Scan...)
            Bukkit.getScheduler().runTaskLater(plugin, () -> db.findPortal(portalId).ifPresent(p -> {
                if (p.status() == PortalStatus.BIND_FAILED) {
                    retryBind(p);
                }
            }), Math.max(20L, config.bindRetrySeconds() * 20L / 4L));
            Player p = notify != null ? Bukkit.getPlayer(notify) : null;
            if (p != null && p.isOnline()) {
                p.sendMessage(mm.deserialize(config.prefix(p) + config.message(p, "bind-failed")));
            }
            return;
        }

        stopWhiteFx(portalId);
        portal.setBoundHost(dest.host());
        // Sticky = one server: java TCP always; boundPort keeps Bedrock/Geyser when known
        portal.setBoundJavaPort(dest.javaPort());
        portal.setBoundPort(dest.transferPort() > 0 ? dest.transferPort() : dest.javaPort());
        portal.setBoundVersion(dest.label());
        portal.setStatus(PortalStatus.ACTIVE);
        db.savePortal(portal);
        if (plugin.portalMatter() != null) {
            plugin.portalMatter().refresh(portal);
        }
        PortalSigns.update(portal);
        if (plugin.portalService() != null) {
            plugin.portalService().publishPortalGraphAsync();
        }
        if (config.scannerMemoryEnabled()) {
            db.recordProbe(dest.host(), dest.javaPort(), Database.ProbeStatus.OK,
                    null, null,
                    dest.transferPort() != dest.javaPort() ? dest.transferPort() : null,
                    dest.bedrockProto() > 0 ? dest.bedrockProto() : null,
                    dest.bedrockVersion(),
                    dest.label());
        }
        Player creator = notify != null ? Bukkit.getPlayer(notify) : null;
        String portHint = dest.transferPort() != dest.javaPort()
                ? dest.javaPort() + "/" + dest.transferPort()
                : String.valueOf(dest.javaPort());
        String msg = config.message(creator, "bind-ready")
                .replace("%host%", dest.host())
                .replace("%port%", portHint)
                .replace("%version%", dest.label() == null ? "?" : dest.label());
        if (creator != null && creator.isOnline()) {
            creator.sendMessage(mm.deserialize(config.prefix(creator) + msg));
        }
        plugin.getLogger().info("Portal " + portalId + " bound → " + dest.host()
                + " java=" + dest.javaPort()
                + (dest.transferPort() != dest.javaPort() ? " bedrock=" + dest.transferPort() : "")
                + " (same server, route by client)");
    }

    private BoundDest searchBindTarget(
            String portalId,
            Portal seed,
            boolean needBedrock,
            int bedProto,
            String bedVer
    ) {
        long deadline = System.currentTimeMillis() + config.bindSearchSeconds() * 1000L;
        int timeout = config.scannerProbeTimeoutMs();
        boolean preferGeyser = config.bindPreferGeyser() || needBedrock;
        int attempt = 0;
        long lastRefreshMs = 0L;

        int totalTried = 0;
        int okJava = 0;
        int noGeyser = 0;
        int bedMismatch = 0;
        int dead = 0;
        // Live target that works but duplicates a nearby portal's destination — used only
        // when no distinct alternative is found.
        BoundDest duplicateFallback = null;

        while (System.currentTimeMillis() < deadline) {
            if (!db.portalAlive(portalId)) {
                return null;
            }
            List<TrustedPeer> peers = collectCandidates(seed);
            if (peers.isEmpty()) {
                attempt++;
                long now = System.currentTimeMillis();
                if (plugin.scannerHub() != null && now - lastRefreshMs > 20_000L) {
                    lastRefreshMs = now;
                    try {
                        plugin.scannerHub().refreshBlocking();
                    } catch (Exception e) {
                        plugin.getLogger().warning("Bind refresh (empty pool): " + e.getMessage());
                    }
                }
                if (attempt == 1 || attempt % 5 == 0) {
                    plugin.getLogger().info("Bind " + portalId + ": waiting for scanner pool…");
                }
                try {
                    Thread.sleep(1500);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return null;
                }
                continue;
            }

            Collections.shuffle(peers);
            if (needBedrock) {
                // The raw scanner pool is almost entirely Java-only hosts, and the confirmed
                // Geyser catalog (probe_cache / MySQL) never makes it into collectCandidates.
                // Prepend those hosts so a Geyser-required bind tries them first instead of
                // burning max-attempts on random Java hosts forever.
                List<TrustedPeer> geyser = knownGeyserPeers();
                if (!geyser.isEmpty()) {
                    Collections.shuffle(geyser);
                    Set<String> gk = new java.util.HashSet<>();
                    for (TrustedPeer g : geyser) {
                        gk.add(keyHost(g.publicHost(), g.publicPort()));
                    }
                    peers.removeIf(p -> gk.contains(keyHost(p.publicHost(), p.publicPort())));
                    peers.addAll(0, geyser);
                }
            }
            int max = Math.max(1, config.scannerMaxAttempts());
            int tried = 0;
            for (TrustedPeer peer : peers) {
                if (tried >= max || System.currentTimeMillis() >= deadline) {
                    break;
                }
                if (isOwnPublicHost(peer.publicHost())) {
                    continue;
                }
                if (config.scannerMemoryEnabled() && config.scannerDeadTtlMs() > 0
                        && db.isRecentlyFailed(peer.publicHost(), peer.publicPort(),
                        config.scannerDeadTtlMs(), true)) {
                    continue;
                }
                if (db.isBadJoinHost(peer.publicHost(), peer.publicPort())) {
                    continue;
                }
                tried++;
                totalTried++;
                ServerProbe.Result r = ServerProbe.probe(peer.publicHost(), peer.publicPort(), timeout);
                if (r.status() != ServerProbe.Status.OK) {
                    dead++;
                    if (config.scannerMemoryEnabled()) {
                        db.recordProbe(peer.publicHost(), peer.publicPort(),
                                r.status() == ServerProbe.Status.FULL
                                        ? Database.ProbeStatus.FULL : Database.ProbeStatus.DEAD,
                                r.online() >= 0 ? r.online() : null,
                                r.max() >= 0 ? r.max() : null,
                                null, null, null, null);
                    }
                    reportHub(peer.publicHost(), peer.publicPort(),
                            r.status() == ServerProbe.Status.FULL
                                    ? Database.ProbeStatus.FULL : Database.ProbeStatus.DEAD,
                            null, null, null, null, peer.displayName());
                    continue;
                }
                okJava++;

                String label = peer.displayName() != null && !peer.displayName().isBlank()
                        ? peer.displayName()
                        : peer.publicHost();
                if (looksClosedMotd(label)) {
                    plugin.getLogger().info("Skip " + peer.publicHost() + " — MOTD looks closed: " + label);
                    continue;
                }

                int transferPort = peer.publicPort();
                int bedP = 0;
                String bedV = null;
                if (needBedrock) {
                    // Bedrock creator: Geyser required (stable double-pong)
                    var bed = confirmBedrockStable(
                            peer.publicHost(), timeout, true, bedProto, bedVer);
                    if (bed == null || bed.mismatch) {
                        if (bed != null && bed.mismatch) {
                            bedMismatch++;
                        } else {
                            noGeyser++;
                            if (config.scannerMemoryEnabled()) {
                                db.recordProbe(peer.publicHost(), peer.publicPort(),
                                        Database.ProbeStatus.NO_GEYSER,
                                        r.online(), r.max(), null, null, null, null);
                            }
                            reportHub(peer.publicHost(), peer.publicPort(), Database.ProbeStatus.NO_GEYSER,
                                    null, null, null, null, peer.displayName());
                        }
                        continue;
                    }
                    transferPort = bed.info.port();
                    bedP = bed.info.protocol();
                    bedV = bed.info.version();
                    if (looksClosedMotd(bed.info.name())) {
                        plugin.getLogger().info("Skip " + peer.publicHost()
                                + " — Bedrock MOTD looks closed: " + bed.info.name());
                        continue;
                    }
                } else if (preferGeyser) {
                    // Java creator: one quick Geyser peek — optional, never blocks bind
                    var bed = ServerProbe.findBedrock(peer.publicHost(), config.scannerBedrockPorts(), timeout);
                    if (bed.isPresent()) {
                        transferPort = bed.get().port();
                        bedP = bed.get().protocol();
                        bedV = bed.get().version();
                    }
                }

                plugin.getLogger().info("Bind candidate OK " + peer.publicHost() + ":" + transferPort
                        + " label=" + label
                        + " online=" + r.online() + "/" + r.max()
                        + (bedP > 0 ? " bed=" + bedP + "/" + bedV : ""));
                Integer bedPortOut = bedP > 0 ? transferPort : null;
                Integer bedProtoOut = bedP > 0 ? bedP : null;
                if (config.scannerMemoryEnabled()) {
                    db.recordProbe(peer.publicHost(), peer.publicPort(), Database.ProbeStatus.OK,
                            r.online(), r.max(), bedPortOut, bedProtoOut, bedV, null);
                }
                reportHub(peer.publicHost(), peer.publicPort(), Database.ProbeStatus.OK,
                        null, bedPortOut, bedProtoOut, bedV, label);
                BoundDest cand = new BoundDest(peer.publicHost(), transferPort, peer.publicPort(), label, bedP, bedV);
                if (nearbyPortalPointsTo(seed, peer.publicHost())) {
                    if (duplicateFallback == null) {
                        duplicateFallback = cand;
                    }
                    plugin.getLogger().info("Skip " + peer.publicHost()
                            + " — a portal within " + (int) config.avoidDuplicateRadius()
                            + " blocks already leads there, trying a different target");
                    continue;
                }
                return cand;
            }

            if (duplicateFallback != null) {
                // Finished a full pass and every working target duplicates a nearby portal —
                // accept the duplicate rather than keep scanning.
                break;
            }

            attempt++;
            long now = System.currentTimeMillis();
            if (attempt % 2 == 0 && config.scannerRefreshOnFail() && plugin.scannerHub() != null
                    && now - lastRefreshMs > 20_000L) {
                lastRefreshMs = now;
                try {
                    plugin.scannerHub().refreshBlocking();
                } catch (Exception e) {
                    plugin.getLogger().warning("Bind refresh: " + e.getMessage());
                }
            }
            try {
                Thread.sleep(400);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        if (duplicateFallback != null) {
            plugin.getLogger().info("Bind " + portalId + ": no distinct target available, reusing "
                    + duplicateFallback.host() + " despite a nearby portal to it");
            return duplicateFallback;
        }
        plugin.getLogger().warning("Bind " + portalId + " failed: poolTried=" + totalTried
                + " javaOk=" + okJava + " noGeyser=" + noGeyser
                + " bedMismatch=" + bedMismatch + " dead=" + dead
                + " requireGeyser=" + config.bindRequireGeyser()
                + " needBedrock=" + needBedrock);
        return null;
    }

    /**
     * True when another cross-server portal within {@code scanner.avoid-duplicate-radius} blocks
     * (same world) is already bound to {@code host} — two nearby Random portals leading to the
     * same server are pointless.
     */
    private boolean nearbyPortalPointsTo(Portal seed, String host) {
        double radius = config.avoidDuplicateRadius();
        if (seed == null || host == null || radius <= 0) {
            return false;
        }
        double r2 = radius * radius;
        for (Portal p : db.listPortals()) {
            if (p.id().equals(seed.id()) || p.type() != PortalType.MULTI) {
                continue;
            }
            if (!p.hasBoundDestination() || !host.equalsIgnoreCase(p.boundHost())) {
                continue;
            }
            if (p.frame() == null || seed.frame() == null
                    || !seed.frame().world().equalsIgnoreCase(p.frame().world())) {
                continue;
            }
            double dx = seed.frame().x() - p.frame().x();
            double dy = seed.frame().y() - p.frame().y();
            double dz = seed.frame().z() - p.frame().z();
            if (dx * dx + dy * dy + dz * dz <= r2) {
                return true;
            }
        }
        return false;
    }

    /** Catalog entries already confirmed to run Geyser (local probe cache + MySQL registry). */
    private List<TrustedPeer> knownGeyserPeers() {
        List<TrustedPeer> out = new ArrayList<>();
        Set<String> seen = new java.util.HashSet<>();
        VersionCompat vc = plugin.versionCompat();
        String branch = versionBranch(vc != null ? vc.mcVersion() : Bukkit.getMinecraftVersion());
        int limit = Math.max(80, config.scannerVerifiedLimit() * 2);
        for (var e : db.listBindCandidates(branch, limit, config.scannerDeadTtlMs())) {
            if (e.bedrockPort() <= 0 || isOwnPublicHost(e.host())) {
                continue;
            }
            if (seen.add(keyHost(e.host(), e.javaPort()))) {
                out.add(e.toPeer());
            }
        }
        if (plugin.registry() != null && plugin.registry().enabled()) {
            try {
                for (var sh : plugin.registry().queryScannerCandidates(branch, true, 0, limit, -50, Set.of())) {
                    if (sh.bedrockPort() <= 0 || isOwnPublicHost(sh.host())) {
                        continue;
                    }
                    if (seen.add(keyHost(sh.host(), sh.javaPort()))) {
                        String label = sh.displayName() != null && !sh.displayName().isBlank()
                                ? sh.displayName() : sh.host();
                        out.add(new TrustedPeer(
                                "hub:" + sh.host() + ":" + sh.javaPort(),
                                label, "", sh.host(), sh.javaPort(), "", false));
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    private List<TrustedPeer> collectCandidates(Portal portal) {
        List<TrustedPeer> out = new ArrayList<>();
        if (!portal.multiPool().isEmpty() && plugin.registry() != null && plugin.registry().enabled()) {
            for (String sid : portal.multiPool()) {
                if (sid == null || sid.startsWith("@")) {
                    continue;
                }
                plugin.registry().find(sid).ifPresent(rs -> {
                    if (!isOwnPublicHost(rs.publicHost())
                            && (rs.serverId() == null || !rs.serverId().equalsIgnoreCase(config.serverId()))) {
                        out.add(rs.toTrustedPeer(config.signingSecret()));
                    }
                });
            }
            if (!out.isEmpty()) {
                return out;
            }
        }
        java.util.Set<String> seen = new java.util.HashSet<>();
        // 1) In-memory scanner cache first (no SQLite lock)
        VersionCompat vc = plugin.versionCompat();
        String ourBranch = versionBranch(vc != null ? vc.mcVersion() : Bukkit.getMinecraftVersion());
        if (plugin.scannerHub() != null && config.scannerEnabled()) {
            for (ScannedServer s : plugin.scannerHub().cached()) {
                if (isOwnPublicHost(s.host())) {
                    continue;
                }
                String branch = versionBranch(s.version());
                if (!ourBranch.isEmpty() && !branch.isEmpty() && !ourBranch.equals(branch)) {
                    continue;
                }
                String k = keyHost(s.host(), s.port());
                if (seen.add(k)) {
                    out.add(s.toPeer());
                }
            }
        }
        // 2) MySQL registry club
        if (plugin.registry() != null && plugin.registry().enabled()) {
            for (var rs : plugin.registry().listMultiTargets(config.registryStaleMs())) {
                if (!rs.hasPlugin()) {
                    continue;
                }
                if (isOwnPublicHost(rs.publicHost())
                        || (rs.serverId() != null && rs.serverId().equalsIgnoreCase(config.serverId()))) {
                    continue;
                }
                String k = keyHost(rs.publicHost(), rs.publicPort());
                if (seen.add(k)) {
                    out.add(rs.toTrustedPeer(config.signingSecret()));
                }
            }
        }
        // 3) known_mvp from hub/gossip
        int limit = Math.max(40, config.scannerVerifiedLimit());
        for (var known : db.listKnownMvp(limit)) {
            if (known.serverId() != null && known.serverId().equalsIgnoreCase(config.serverId())) {
                continue;
            }
            if (isOwnPublicHost(known.publicHost())) {
                continue;
            }
            String branch = versionBranch(known.mcVersion());
            if (!ourBranch.isEmpty() && !branch.isEmpty() && !ourBranch.equals(branch)) {
                continue;
            }
            String k = keyHost(known.publicHost(), known.publicPort());
            if (seen.add(k)) {
                out.add(known.toTrustedPeer(config.signingSecret()));
            }
        }
        if (!out.isEmpty()) {
            return out;
        }
        // 4) Local scanner catalog (SQLite)
        for (var entry : db.listBindCandidates(ourBranch, limit, config.scannerDeadTtlMs())) {
            if (isOwnPublicHost(entry.host())) {
                continue;
            }
            String k = keyHost(entry.host(), entry.javaPort());
            if (seen.add(k)) {
                out.add(entry.toPeer());
            }
        }
        if (out.isEmpty()) {
            for (var entry : db.listScoredCandidates(ourBranch, limit, config.scannerDeadTtlMs())) {
                if (isOwnPublicHost(entry.host())) {
                    continue;
                }
                String k = keyHost(entry.host(), entry.javaPort());
                if (seen.add(k)) {
                    out.add(entry.toPeer());
                }
            }
        }
        if (out.isEmpty()) {
            for (TrustedPeer p : db.listPeers()) {
                if (isOwnPublicHost(p.publicHost())
                        || (p.serverId() != null && p.serverId().equalsIgnoreCase(config.serverId()))) {
                    continue;
                }
                String k = keyHost(p.publicHost(), p.publicPort());
                if (seen.add(k)) {
                    out.add(p);
                }
            }
        }
        return out;
    }

    /** Same public IP/host as this server — never bind Random portals to ourselves. */
    private boolean isOwnPublicHost(String host) {
        return config.isOwnPublicHost(host);
    }

    private static String keyHost(String host, int port) {
        return (host == null ? "" : host.toLowerCase(java.util.Locale.ROOT)) + ":" + port;
    }

    private static String versionBranch(String v) {
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

    private void startWhiteFx(Portal portal) {
        stopWhiteFx(portal.id());
        final String id = portal.id();
        final String worldName = portal.frame().world();
        final int fx = portal.frame().x();
        final int fy = portal.frame().y();
        final int fz = portal.frame().z();
        // Snapshot frame — avoid SQLite hits on the main thread every few ticks
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!binding.contains(id)) {
                Optional<Portal> opt;
                try {
                    opt = db.findPortal(id);
                } catch (RuntimeException e) {
                    return;
                }
                if (opt.isEmpty()) {
                    stopWhiteFx(id);
                    return;
                }
                PortalStatus st = opt.get().status();
                if (st != PortalStatus.BINDING && st != PortalStatus.BIND_FAILED) {
                    stopWhiteFx(id);
                    return;
                }
            }
            World world = Bukkit.getWorld(worldName);
            if (world == null) {
                return;
            }
            Block sign = world.getBlockAt(fx, fy, fz);
            List<Location> cells = PortalMatter.findOpeningCells(sign);
            if (cells.isEmpty()) {
                cells = List.of(sign.getLocation());
            }
            for (Location cell : cells) {
                Location c = cell.clone().add(0.5, 0.5, 0.5);
                boolean near = false;
                for (Player pl : world.getPlayers()) {
                    if (pl.getLocation().distanceSquared(c) < 576) {
                        near = true;
                        break;
                    }
                }
                if (!near) {
                    continue;
                }
                world.spawnParticle(Particle.END_ROD, c, 3, 0.25, 0.4, 0.25, 0.01);
                world.spawnParticle(Particle.WHITE_ASH, c, 6, 0.3, 0.4, 0.3, 0.02);
                world.spawnParticle(Particle.FIREWORK, c, 1, 0.2, 0.3, 0.2, 0.01);
            }
        }, 5L, 8L);
        fxTasks.put(id, task);
    }

    private void stopWhiteFx(String portalId) {
        BukkitTask t = fxTasks.remove(portalId);
        if (t != null) {
            t.cancel();
        }
    }

    /**
     * Sticky bind: once linked, destination stays until the player breaks the sign.
     * Bounce / dead host no longer rebinds — only a new [Multi] sign gets a new random.
     */
    public void rebindAfterBounce(String portalId, String host, int javaPort) {
        plugin.getLogger().info("Sticky bind: keep portal " + portalId
                + " → " + host + " after bounce (break sign to re-roll)");
    }

    /** Sticky bind — do not clear a successful link when the dest is temporarily down. */
    public void rebindUnreachable(String portalId, String reason) {
        plugin.getLogger().info("Sticky bind: keep portal " + portalId
                + " despite " + reason + " (break sign to re-roll)");
    }

    /**
     * Multiple Bedrock pongs must agree (proto/version/port). online=0 is OK; full is not.
     * @return null if no geyser / unstable; mismatch=true if version/proto rejected
     */
    private BedrockConfirm confirmBedrockStable(
            String host,
            int timeout,
            boolean needBedrock,
            int clientProto,
            String clientVer
    ) {
        int n = config.scannerBindConfirmProbes();
        ServerProbe.BedrockInfo first = null;
        for (int i = 0; i < n; i++) {
            var bed = ServerProbe.findBedrock(host, config.scannerBedrockPorts(), timeout);
            if (bed.isEmpty()) {
                return null;
            }
            var info = bed.get();
            if (info.full()) {
                return null;
            }
            if (first == null) {
                first = info;
            } else if (first.port() != info.port()
                    || first.protocol() != info.protocol()
                    || !java.util.Objects.equals(
                    normalizeEmpty(first.version()), normalizeEmpty(info.version()))) {
                plugin.getLogger().info("Skip " + host + " — unstable Bedrock pong"
                        + " (" + first.protocol() + "/" + first.version()
                        + " → " + info.protocol() + "/" + info.version() + ")");
                return null;
            }
            if (i + 1 < n) {
                try {
                    Thread.sleep(120);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
        }
        if (first == null) {
            return null;
        }
        // Protocol match only applies when we know the client's Bedrock protocol (real Bedrock
        // creator). For a Java/offline creator (clientProto<=0) binding under bind-require-geyser,
        // any stable Geyser is acceptable — the future Bedrock visitor's protocol is unknown yet.
        if (needBedrock && clientProto > 0 && config.scannerRequireBedrockProtocolMatch()) {
            if (!BedrockPlayers.canJoinDest(
                    clientProto, first.protocol(), clientVer, first.version(),
                    config.scannerRequireBedrockVersionMatch())) {
                plugin.getLogger().info("Skip " + host + " — Bedrock mismatch"
                        + " client=" + clientProto + "/" + clientVer
                        + " dest=" + first.protocol() + "/" + first.version());
                return new BedrockConfirm(first, true);
            }
        }
        return new BedrockConfirm(first, false);
    }

    private void reportHub(
            String host,
            int javaPort,
            Database.ProbeStatus status,
            String mcVersion,
            Integer bedrockPort,
            Integer bedrockProtocol,
            String bedrockVersion,
            String displayName
    ) {
        if (plugin.scannerPool() != null) {
            plugin.scannerPool().reportProbeAsync(
                    host, javaPort, status, mcVersion, bedrockPort, bedrockProtocol, bedrockVersion, displayName);
        }
    }

    private boolean looksClosedMotd(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String low = text.toLowerCase(java.util.Locale.ROOT);
        for (String kw : config.scannerRejectMotdKeywords()) {
            if (kw != null && !kw.isBlank() && low.contains(kw.toLowerCase(java.util.Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static String normalizeEmpty(String s) {
        return s == null ? "" : s.trim();
    }

    private record BedrockConfirm(ServerProbe.BedrockInfo info, boolean mismatch) {}

    private record BoundDest(
            String host,
            int transferPort,
            int javaPort,
            String label,
            int bedrockProto,
            String bedrockVersion
    ) {}
}
