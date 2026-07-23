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
import io.multiverseportals.scanner.FlowBalance;
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

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

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
    /** Howl's dial: cooldown per portal id so players can't spam rebinds. */
    private final Map<String, Long> dialCooldownUntil = new ConcurrentHashMap<>();
    private static final long DIAL_COOLDOWN_MS = 3_000L;
    /** Hosts recently chosen by dial / Multi bind — skipped for {@code dial-recent-exclude-seconds}. */
    private final Map<String, Long> recentBindUntil = new ConcurrentHashMap<>();

    public PortalBindService(MultiversePortalsPlugin plugin, Database db, PluginConfig config) {
        this.plugin = plugin;
        this.db = db;
        this.config = config;
    }

    /**
     * Debug/API: same candidate order a new {@code [Multi]} bind would try (no live probes).
     * {@code shuffle=false} keeps a stable host order within each tier for reproducible debug.
     */
    public JsonObject previewBindOrder(
            boolean needBedrock,
            Boolean requireGeyserOverride,
            String portalId,
            int limit,
            boolean shuffle
    ) {
        boolean requireGeyser = requireGeyserOverride != null
                ? requireGeyserOverride
                : config.bindRequireGeyser();
        boolean preferDual = needBedrock || requireGeyser || config.bindPreferGeyser();
        Portal seed = null;
        if (portalId != null && !portalId.isBlank()) {
            seed = db.findPortal(portalId.trim()).orElse(null);
        }
        if (seed == null) {
            // Synthetic seed: empty multi-pool → full registry/scanner path; no nearby-dupe filter.
            seed = new Portal(
                    "_preview",
                    PortalType.MULTI,
                    PortalStatus.BINDING,
                    null,
                    "preview",
                    new UUID(0, 0)
            );
        }

        List<TrustedPeer> peers = collectCandidates(seed);
        orderBindPeers(peers, preferDual, shuffle);
        Set<String> geyserKeys = knownGeyserKeySet(preferDual);
        FlowBalance.Weights flowW = config.flowBalanceWeights();
        int originOnline = Bukkit.getOnlinePlayers().size();
        Map<String, Integer> onlineMap = db.probeOnlineMap(
                Math.max(500, peers.size() + 100));

        int maxAttempts = Math.max(1, config.scannerMaxAttempts());
        int probeSlots = 0;
        JsonArray arr = new JsonArray();
        int rank = 0;
        for (TrustedPeer peer : peers) {
            if (limit > 0 && rank >= limit) {
                break;
            }
            rank++;
            String skip = null;
            if (isOwnPublicHost(peer.publicHost())) {
                skip = "own-host";
            } else if (recentExcludeHosts().contains(
                    peer.publicHost() == null ? "" : peer.publicHost().toLowerCase(Locale.ROOT))) {
                skip = "recently-bound";
            } else if (!peer.hasPlugin() && config.scannerMemoryEnabled() && config.scannerDeadTtlMs() > 0
                    && db.isRecentlyFailed(peer.publicHost(), peer.publicPort(),
                    config.scannerDeadTtlMs(), true)) {
                skip = "recently-failed-hard";
            } else if (nearbyPortalPointsTo(seed, peer.publicHost())) {
                skip = "nearby-duplicate";
            } else if (probeSlots >= maxAttempts) {
                skip = "past-max-attempts";
            }

            boolean wouldProbe = skip == null;
            if (wouldProbe) {
                probeSlots++;
            }

            boolean geyser = preferDual && geyserKeys.contains(keyHost(peer.publicHost(), peer.publicPort()));
            String tier = peer.hasPlugin()
                    ? (geyser ? "club-geyser" : "club")
                    : (geyser ? "ext-geyser" : "ext");
            int destOnline = onlineMap.getOrDefault(
                    keyHost(peer.publicHost(), peer.publicPort()), -1);
            double flowScore = FlowBalance.score(originOnline, destOnline, flowW);

            JsonObject o = new JsonObject();
            o.addProperty("rank", rank);
            o.addProperty("host", peer.publicHost());
            o.addProperty("javaPort", peer.publicPort());
            o.addProperty("serverId", peer.serverId() == null ? "" : peer.serverId());
            o.addProperty("label", peer.displayName() == null ? "" : peer.displayName());
            o.addProperty("hasPlugin", peer.hasPlugin());
            o.addProperty("tier", tier);
            o.addProperty("online", destOnline);
            o.addProperty("flowScore", Math.round(flowScore * 10.0) / 10.0);
            o.addProperty("wouldProbe", wouldProbe);
            if (skip != null) {
                o.addProperty("skip", skip);
            }
            arr.add(o);
        }

        long club = peers.stream().filter(TrustedPeer::hasPlugin).count();
        JsonObject out = new JsonObject();
        out.addProperty("ok", true);
        out.addProperty("serverId", config.serverId());
        out.addProperty("publicHost", config.publicHost());
        out.addProperty("needBedrock", needBedrock);
        out.addProperty("requireGeyser", requireGeyser);
        out.addProperty("preferGeyser", preferDual);
        out.addProperty("maxAttempts", maxAttempts);
        out.addProperty("poolSize", peers.size());
        out.addProperty("clubSize", club);
        out.addProperty("shuffle", shuffle);
        out.addProperty("flowBalance", flowW.enabled());
        out.addProperty("originOnline", originOnline);
        out.addProperty("portalId", seed.id());
        out.addProperty("note", "Order matches [Multi] bind; wouldProbe marks the first maxAttempts "
                + "candidates that are not skipped. online/flowScore use probe_cache (not live SLP). "
                + "Toggle scanner.flow-balance.enabled and /mvp reload to compare rankings.");
        out.add("candidates", arr);
        return out;
    }

    /** Cached geyser host keys for preview tier labels (same source as orderBindPeers). */
    private Set<String> knownGeyserKeySet(boolean preferDual) {
        Set<String> keys = new java.util.HashSet<>();
        if (!preferDual) {
            return keys;
        }
        for (TrustedPeer g : knownGeyserPeers()) {
            keys.add(keyHost(g.publicHost(), g.publicPort()));
        }
        return keys;
    }

    public void startBind(Portal portal, Player creator) {
        startBind(portal, creator, Set.of());
    }

    /**
     * Howl's Moving Castle dial: button on a random [Multi] portal switches the sticky destination.
     * Skips the current host and hosts chosen in the last {@code dial-recent-exclude-seconds}
     * so the dial does not keep flipping hub ↔ one peer.
     */
    public void cycleBind(Player player, Portal portal) {
        if (player == null || portal == null || portal.type() != PortalType.MULTI) {
            return;
        }
        if (!player.hasPermission("multiverseportals.travel")) {
            player.sendMessage(mm.deserialize(config.prefix(player) + config.message(player, "no-permission-travel")));
            return;
        }
        if (fixedEndpoint(portal).isPresent()) {
            player.sendMessage(mm.deserialize(config.prefix(player) + config.message(player, "dial-fixed-denied")));
            return;
        }
        if (binding.contains(portal.id()) || portal.status() == PortalStatus.BINDING) {
            player.sendMessage(mm.deserialize(config.prefix(player) + config.message(player, "dial-busy")));
            return;
        }
        long now = System.currentTimeMillis();
        Long until = dialCooldownUntil.get(portal.id());
        if (until != null && now < until) {
            player.sendMessage(mm.deserialize(config.prefix(player) + config.message(player, "dial-cooldown")));
            return;
        }
        dialCooldownUntil.put(portal.id(), now + DIAL_COOLDOWN_MS);

        String currentHost = portal.boundHost();

        player.sendMessage(mm.deserialize(config.prefix(player) + config.message(player, "dial-searching")));

        if (!binding.add(portal.id())) {
            player.sendMessage(mm.deserialize(config.prefix(player) + config.message(player, "dial-busy")));
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

        UUID playerId = player.getUniqueId();
        boolean needBedrock = BedrockPlayers.isBedrock(player);
        int bedProto = needBedrock ? BedrockPlayers.protocolVersion(player).orElse(0) : 0;
        String bedVer = needBedrock ? BedrockPlayers.versionString(player) : "";
        final String portalId = portal.id();
        final Set<String> exclude = new HashSet<>(recentExcludeHosts());
        if (currentHost != null && !currentHost.isBlank()) {
            exclude.add(currentHost.toLowerCase(Locale.ROOT));
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            BoundDest dest = null;
            try {
                refreshHubBeforeBind(player);
                // Re-read club after hub/catalog pull — new MVP peers may have appeared.
                List<TrustedPeer> freshClub = new ArrayList<>(collectClubPeers(
                        db.findPortal(portalId).orElse(portal)));
                Collections.shuffle(freshClub, ThreadLocalRandom.current());
                for (TrustedPeer peer : freshClub) {
                    if (peer.publicHost() == null || peer.publicHost().isBlank()) {
                        continue;
                    }
                    if (exclude.contains(peer.publicHost().toLowerCase(Locale.ROOT))) {
                        continue;
                    }
                    if (isOwnPublicHost(peer.publicHost())) {
                        continue;
                    }
                    BoundDest probed = probeFixedHost(
                            peer.publicHost(), peer.publicPort(), needBedrock, peer.displayName());
                    if (probed != null) {
                        dest = probed;
                        break;
                    }
                }
                Portal seed = db.findPortal(portalId).orElse(null);
                if (dest == null && seed != null) {
                    dest = searchBindTarget(
                            portalId,
                            seed,
                            needBedrock,
                            config.bindRequireGeyser() && needBedrock,
                            bedProto,
                            bedVer,
                            exclude
                    );
                }
                // Soft fallback: if everything recent was skipped and the pool is thin, allow
                // reusing older dial targets (still never the current host).
                if (dest == null && seed != null && exclude.size() > 1) {
                    Set<String> soft = new HashSet<>();
                    if (currentHost != null && !currentHost.isBlank()) {
                        soft.add(currentHost.toLowerCase(Locale.ROOT));
                    }
                    plugin.getLogger().info("Dial " + portalId + ": no fresh target, soft-retry without recent exclude");
                    dest = searchBindTarget(
                            portalId,
                            seed,
                            needBedrock,
                            config.bindRequireGeyser() && needBedrock,
                            bedProto,
                            bedVer,
                            soft
                    );
                }
            } catch (Throwable t) {
                plugin.getLogger().warning("Dial bind failed for " + portalId + ": " + t.getMessage());
            }
            BoundDest finalDest = dest;
            Bukkit.getScheduler().runTask(plugin, () -> {
                // On success use dial-switched (not bind-ready); on failure still notify via finishBind.
                finishBind(portalId, finalDest, finalDest == null ? playerId : null);
                Player p = Bukkit.getPlayer(playerId);
                if (p != null && p.isOnline() && finalDest != null) {
                    p.sendMessage(mm.deserialize(config.prefix(p) + config.message(p, "dial-switched")
                            .replace("%host%", finalDest.host())
                            .replace("%port%", String.valueOf(finalDest.javaPort()))));
                }
            });
        });
    }

    public void startBind(Portal portal, Player creator, Set<String> excludeHosts) {
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
        // Hard Geyser only for actual Bedrock creators. bind-require-geyser still prefers dual-stack
        // hosts, but must not skip MultiversePortals club peers for random public Geyser.
        boolean needBedrock = creatorBedrock;
        boolean requireGeyser = config.bindRequireGeyser();
        int bedProto = creatorBedrock ? BedrockPlayers.protocolVersion(creator).orElse(0) : 0;
        String bedVer = creatorBedrock ? BedrockPlayers.versionString(creator) : "";
        final String portalId = portal.id();
        final Set<String> exclude = excludeHosts == null || excludeHosts.isEmpty()
                ? Set.of()
                : Set.copyOf(excludeHosts);

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            BoundDest dest = null;
            try {
                final Set<String> excludeAll = new HashSet<>(exclude);
                excludeAll.addAll(recentExcludeHosts());
                refreshHubBeforeBind(creator);
                dest = searchBindTarget(portalId, portal, needBedrock, requireGeyser, bedProto, bedVer, excludeAll);
                // New Multi portal: nearby-duplicate already diversifies within radius.
                // If recent-exclude emptied the club (e.g. only one Geyser hub peer left),
                // soft-retry without it so we stay on club instead of random public.
                if (dest == null && !recentExcludeHosts().isEmpty()) {
                    Set<String> soft = new HashSet<>(exclude);
                    plugin.getLogger().info("Bind " + portalId
                            + ": no target with recent exclude, soft-retry without it");
                    dest = searchBindTarget(portalId, portal, needBedrock, requireGeyser, bedProto, bedVer, soft);
                }
            } catch (Throwable t) {
                plugin.getLogger().warning("Bind search failed for " + portalId + ": " + t.getMessage());
            }
            BoundDest finalDest = dest;
            Bukkit.getScheduler().runTask(plugin, () -> finishBind(portalId, finalDest, creatorId));
        });
    }

    /** Always refresh central catalog + hub-pool before Multi/dial bind. */
    private void refreshHubBeforeBind(Player creator) {
        if (plugin.scannerPool() != null) {
            try {
                int n = plugin.scannerPool().pullForBind(creator);
                if (n > 0) {
                    plugin.getLogger().info("Bind: hub-pool pull +" + n + " candidates");
                }
            } catch (Throwable t) {
                plugin.getLogger().warning("Bind: hub-pool pull failed: "
                        + (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage()));
            }
        }
        if (plugin.catalogShareService() != null) {
            plugin.catalogShareService().pullForBindBlocking();
        }
    }

    /** Club-only candidates (registry MVP + known_mvp), same hosts a dial cycles through. */
    private List<TrustedPeer> collectClubPeers(Portal portal) {
        List<TrustedPeer> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        VersionCompat vc = plugin.versionCompat();
        String ourBranch = versionBranch(vc != null ? vc.mcVersion() : Bukkit.getMinecraftVersion());
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
        return out;
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
        boolean needBedrock = creator != null && BedrockPlayers.isBedrock(creator);
        final String portalId = portal.id();
        final String hostF = host.trim();
        final int javaF = javaPort;

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            BoundDest dest = null;
            try {
                dest = probeFixedHost(hostF, javaF, needBedrock, null);
            } catch (Throwable t) {
                plugin.getLogger().warning("Fixed bind failed for " + portalId + ": " + t.getMessage());
            }
            BoundDest finalDest = dest;
            Bukkit.getScheduler().runTask(plugin, () -> finishBind(portalId, finalDest, creatorId));
        });
    }

    /**
     * Live-probe a known host. Sign label prefers {@code preferredLabel} (hub display name),
     * then Java MOTD, never the raw IP or Bedrock protocol version.
     */
    private BoundDest probeFixedHost(String host, int javaPort, boolean needBedrock, String preferredLabel) {
        int timeout = config.scannerProbeTimeoutMs();
        ServerProbe.StatusInfo info = ServerProbe.probeStatus(host, javaPort, timeout);
        if (info.status() != ServerProbe.Status.OK && info.status() != ServerProbe.Status.FULL) {
            plugin.getLogger().info("Fixed bind " + host + ":" + javaPort + " → " + info.status());
            return null;
        }
        String label = resolveBindLabel(preferredLabel, info.motd(), host);
        int transferPort = javaPort;
        int bedProto = 0;
        String bedVer = "";
        if (needBedrock || config.bindPreferGeyser()) {
            var bed = ServerProbe.findBedrock(host,
                    ServerProbe.mergeBedrockPorts(List.of(javaPort), config.scannerBedrockPorts()), timeout);
            if (bed.isPresent()) {
                transferPort = bed.get().port();
                bedProto = bed.get().protocol();
                bedVer = bed.get().version() == null ? "" : bed.get().version();
                // Prefer Bedrock MOTD only when we still have no human label
                if (labelEqualsHost(label, host) && bed.get().motd() != null && !bed.get().motd().isBlank()) {
                    label = truncate(PortalSigns.cleanLabel(bed.get().name()), 40);
                    if (label.isBlank()) {
                        label = host;
                    }
                }
            } else if (needBedrock && config.bindRequireGeyser()) {
                plugin.getLogger().info("Fixed bind " + host + ":" + javaPort + " — no Geyser for Bedrock");
                return null;
            }
        }
        return new BoundDest(host, transferPort, javaPort, label, bedProto, bedVer);
    }

    private static String resolveBindLabel(String preferred, String motd, String host) {
        if (preferred != null && !preferred.isBlank()) {
            String p = truncate(PortalSigns.cleanLabel(preferred), 40);
            if (!p.isBlank() && !labelEqualsHost(p, host)) {
                return p;
            }
            if (!p.isBlank()) {
                // preferred was literally the host — still try MOTD below
            } else {
                preferred = null;
            }
        }
        if (motd != null && !motd.isBlank()) {
            String m = truncate(PortalSigns.cleanLabel(motd), 40);
            if (!m.isBlank() && !labelEqualsHost(m, host) && !looksLikeVersionOnlyLabel(m)) {
                return m;
            }
        }
        if (preferred != null && !preferred.isBlank()) {
            String p = truncate(PortalSigns.cleanLabel(preferred), 40);
            if (!p.isBlank()) {
                return p;
            }
        }
        return host == null ? "?" : host;
    }

    private static boolean labelEqualsHost(String label, String host) {
        if (label == null || host == null) {
            return false;
        }
        return label.equalsIgnoreCase(host.trim());
    }

    private static boolean looksLikeVersionOnlyLabel(String s) {
        return s != null && s.matches("(?i)v?\\d+(\\.\\d+){1,3}([-_].*)?");
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
        rememberRecentBind(dest.host());
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
            boolean requireGeyser,
            int bedProto,
            String bedVer
    ) {
        return searchBindTarget(portalId, seed, needBedrock, requireGeyser, bedProto, bedVer, Set.of());
    }

    private BoundDest searchBindTarget(
            String portalId,
            Portal seed,
            boolean needBedrock,
            boolean requireGeyser,
            int bedProto,
            String bedVer,
            Set<String> excludeHosts
    ) {
        long deadline = System.currentTimeMillis() + config.bindSearchSeconds() * 1000L;
        int timeout = config.scannerProbeTimeoutMs();
        boolean preferGeyser = config.bindPreferGeyser() || needBedrock || requireGeyser;
        int attempt = 0;
        long lastRefreshMs = 0L;
        Set<String> exclude = excludeHosts == null ? Set.of() : excludeHosts;

        int totalTried = 0;
        int okJava = 0;
        int noGeyser = 0;
        int bedMismatch = 0;
        int dead = 0;
        int megaSkipped = 0;
        // Live target that works but duplicates a nearby portal's destination — used only
        // when no distinct alternative is found.
        BoundDest duplicateFallback = null;
        FlowBalance.Weights flowW = config.flowBalanceWeights();
        int originOnline = Bukkit.getOnlinePlayers().size();

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
                    Thread.sleep(400);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return null;
                }
                continue;
            }

            // Club (MVP peers with the plugin) before any public scanner / MineScan host.
            orderBindPeers(peers, needBedrock || requireGeyser, true);
            long club = peers.stream().filter(TrustedPeer::hasPlugin).count();
            if (attempt == 0 && totalTried == 0) {
                plugin.getLogger().info("Bind " + portalId + ": pool=" + peers.size()
                        + " club=" + club
                        + " needBedrock=" + needBedrock
                        + " requireGeyser=" + requireGeyser
                        + " flowBalance=" + flowW.enabled()
                        + " originOnline=" + originOnline
                        + (exclude.isEmpty() ? "" : " exclude=" + exclude.size()));
            }
            int max = Math.max(1, config.scannerMaxAttempts());
            int tried = 0;
            // When flow-balance is on, collect several OK peers in this pass and pick best live flow.
            List<ScoredOk> passOk = new ArrayList<>();
            int okBand = flowW.enabled() ? Math.min(config.flowLiveOkBand(), max) : 1;
            // Club/MVP first as its own phase — never mix with public in the same flow-pick.
            // Nearby-duplicate may skip a hub peer; only after the whole club tier is done
            // do we fall through to scanners.
            boolean clubPhase = true;
            int clubSeen = 0;
            for (TrustedPeer peer : peers) {
                if (clubPhase && !peer.hasPlugin()) {
                    if (!passOk.isEmpty()) {
                        passOk.sort(Comparator.comparingDouble((ScoredOk s) -> s.score).reversed());
                        BoundDest best = passOk.get(0).dest;
                        plugin.getLogger().info("Bind " + portalId + ": club flow-pick "
                                + best.host() + " among " + passOk.size() + " club OK"
                                + " (best flow=" + String.format(Locale.ROOT, "%.1f", passOk.get(0).score) + ")");
                        return best;
                    }
                    // Nearby-duplicate / recent-exclude must NOT win here — keep searching public.
                    // duplicateFallback is only a last resort after the full club+public pass.
                    clubPhase = false;
                    plugin.getLogger().info("Bind " + portalId + ": club exhausted (seen=" + clubSeen
                            + "), falling back to public scanners"
                            + (duplicateFallback != null
                            ? " (holding " + duplicateFallback.host() + " as last-resort only)"
                            : ""));
                    tried = 0;
                }
                if (tried >= max || System.currentTimeMillis() >= deadline) {
                    break;
                }
                if (flowW.enabled() && passOk.size() >= okBand) {
                    break;
                }
                if (isOwnPublicHost(peer.publicHost())) {
                    continue;
                }
                boolean recentExcluded = !exclude.isEmpty() && peer.publicHost() != null
                        && exclude.contains(peer.publicHost().toLowerCase(Locale.ROOT));
                // Public: hard-skip recent exclude. Club: still probe and keep as fallback so a
                // thin hub mesh does not fall through to random scanners.
                if (recentExcluded && !peer.hasPlugin()) {
                    continue;
                }
                if (config.scannerMemoryEnabled() && config.scannerDeadTtlMs() > 0
                        && db.isRecentlyFailed(peer.publicHost(), peer.publicPort(),
                        config.scannerDeadTtlMs(), true)
                        && !peer.hasPlugin()) {
                    continue;
                }
                if (peer.hasPlugin()) {
                    clubSeen++;
                }
                // Club (MultiversePortals) peers must stay eligible even if a past transfer
                // stamped BAD_JOIN — otherwise Random never reaches club/hub peers and only scanners win.
                // BAD_JOIN for public hosts is soft-ranked by score (later), not hard-skipped.
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

                if (!peer.hasPlugin() && FlowBalance.rejectMega(r.online(), flowW)) {
                    megaSkipped++;
                    plugin.getLogger().info("Skip " + peer.publicHost()
                            + " — mega hard-cap online=" + r.online());
                    continue;
                }

                int transferPort = peer.publicPort();
                int bedP = 0;
                String bedV = null;
                List<Integer> bedHints = new ArrayList<>();
                if (plugin.registry() != null && plugin.registry().enabled()) {
                    plugin.registry().find(peer.serverId()).ifPresent(rs -> {
                        int bp = rs.caps() != null ? rs.caps().bedrockPort() : 0;
                        if (bp > 0) {
                            bedHints.add(bp);
                        }
                    });
                }
                if (peer.publicPort() > 0) {
                    bedHints.add(peer.publicPort());
                }
                if (needBedrock) {
                    // Bedrock creator: Geyser required (stable double-pong)
                    var bed = confirmBedrockStable(
                            peer.publicHost(), timeout, true, bedProto, bedVer, bedHints);
                    if (bed == null || bed.mismatch) {
                        if (bed != null && bed.mismatch) {
                            bedMismatch++;
                            if (peer.hasPlugin()) {
                                plugin.getLogger().info("Bind " + portalId + ": skip club "
                                        + peer.publicHost() + " — Bedrock protocol mismatch");
                            }
                        } else {
                            noGeyser++;
                            if (peer.hasPlugin()) {
                                plugin.getLogger().info("Bind " + portalId + ": skip club "
                                        + peer.publicHost() + " — no Geyser (Bedrock creator)");
                            }
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
                } else if (requireGeyser && !peer.hasPlugin()) {
                    // Prefer dual-stack for Random, but never skip club peers without Geyser.
                    var bed = confirmBedrockStable(
                            peer.publicHost(), timeout, false, bedProto, bedVer, bedHints);
                    if (bed == null || bed.mismatch) {
                        noGeyser++;
                        if (config.scannerMemoryEnabled()) {
                            db.recordProbe(peer.publicHost(), peer.publicPort(),
                                    Database.ProbeStatus.NO_GEYSER,
                                    r.online(), r.max(), null, null, null, null);
                        }
                        reportHub(peer.publicHost(), peer.publicPort(), Database.ProbeStatus.NO_GEYSER,
                                null, null, null, null, peer.displayName());
                        continue;
                    }
                    transferPort = bed.info.port();
                    bedP = bed.info.protocol();
                    bedV = bed.info.version();
                } else if (preferGeyser) {
                    // Java / club: one quick Geyser peek — optional, never blocks bind
                    var bed = ServerProbe.findBedrock(peer.publicHost(),
                            ServerProbe.mergeBedrockPorts(bedHints, config.scannerBedrockPorts()), timeout);
                    if (bed.isPresent()) {
                        transferPort = bed.get().port();
                        bedP = bed.get().protocol();
                        bedV = bed.get().version();
                    }
                }

                double flowScore = FlowBalance.score(originOnline, r.online(), flowW);
                plugin.getLogger().info("Bind candidate OK " + peer.publicHost() + ":" + transferPort
                        + " label=" + label
                        + " club=" + peer.hasPlugin()
                        + " online=" + r.online() + "/" + r.max()
                        + " flow=" + String.format(Locale.ROOT, "%.1f", flowScore)
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
                    // Hard diversify: never pick a host that a nearby Multi portal already uses,
                    // while other club/public candidates remain. Keep only as absolute last resort.
                    if (duplicateFallback == null) {
                        duplicateFallback = cand;
                    }
                    plugin.getLogger().info("Skip " + peer.publicHost()
                            + " — a portal within " + (int) config.avoidDuplicateRadius()
                            + " blocks already leads there, trying a different target");
                    continue;
                }
                if (recentExcluded) {
                    if (duplicateFallback == null) {
                        duplicateFallback = cand;
                    }
                    plugin.getLogger().info("Skip " + peer.publicHost()
                            + " — recent dial/bind exclude, trying a different target");
                    continue;
                }
                if (!flowW.enabled()) {
                    return cand;
                }
                passOk.add(new ScoredOk(cand, flowScore));
            }

            if (!passOk.isEmpty()) {
                passOk.sort(Comparator.comparingDouble((ScoredOk s) -> s.score).reversed());
                BoundDest best = passOk.get(0).dest;
                plugin.getLogger().info("Bind " + portalId + ": flow-pick "
                        + best.host() + " among " + passOk.size() + " OK in pass"
                        + " (best flow=" + String.format(Locale.ROOT, "%.1f", passOk.get(0).score) + ")");
                return best;
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
                + " megaSkipped=" + megaSkipped
                + " requireGeyser=" + requireGeyser
                + " needBedrock=" + needBedrock);
        return null;
    }

    private record ScoredOk(BoundDest dest, double score) {
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

    /**
     * Bind order: network club (has MultiversePortals) before any public scanner host.
     * Within each tier, confirmed-Geyser hosts rise when dual-stack is preferred — but
     * external Geyser must never outrank a club peer without Geyser.
     * Soft flow-balance sorts each tier by origin vs dest online (cached probe).
     */
    private void orderBindPeers(List<TrustedPeer> peers, boolean preferDualStack, boolean shuffle) {
        Set<String> geyserKeys = new java.util.HashSet<>();
        if (preferDualStack) {
            for (TrustedPeer g : knownGeyserPeers()) {
                String k = keyHost(g.publicHost(), g.publicPort());
                geyserKeys.add(k);
                boolean known = false;
                for (TrustedPeer p : peers) {
                    if (k.equals(keyHost(p.publicHost(), p.publicPort()))) {
                        known = true;
                        break;
                    }
                }
                if (!known) {
                    peers.add(g);
                }
            }
        }
        List<TrustedPeer> clubGeyser = new ArrayList<>();
        List<TrustedPeer> clubOther = new ArrayList<>();
        List<TrustedPeer> extGeyser = new ArrayList<>();
        List<TrustedPeer> extOther = new ArrayList<>();
        for (TrustedPeer p : peers) {
            boolean geyser = preferDualStack && geyserKeys.contains(keyHost(p.publicHost(), p.publicPort()));
            if (p.hasPlugin()) {
                if (geyser) {
                    clubGeyser.add(p);
                } else {
                    clubOther.add(p);
                }
            } else if (geyser) {
                extGeyser.add(p);
            } else {
                extOther.add(p);
            }
        }
        FlowBalance.Weights flowW = config.flowBalanceWeights();
        int originOnline = Bukkit.getOnlinePlayers().size();
        Map<String, Integer> onlineMap = flowW.enabled()
                ? db.probeOnlineMap(Math.max(500, peers.size() + 100))
                : Map.of();
        Random rng = ThreadLocalRandom.current();
        // Club: sparsest hub mesh first (known_mvp score / registry degree) — then soft shuffle ties.
        sortClubBySparseHubLinks(clubGeyser, rng);
        sortClubBySparseHubLinks(clubOther, rng);
        // Public scanners: flow-balance / shuffle as before.
        rankTier(extGeyser, originOnline, onlineMap, flowW, shuffle, rng);
        rankTier(extOther, originOnline, onlineMap, flowW, shuffle, rng);
        peers.clear();
        peers.addAll(clubGeyser);
        peers.addAll(clubOther);
        peers.addAll(extGeyser);
        peers.addAll(extOther);
    }

    /**
     * Prefer MVP peers with fewer hub portal links so the club mesh fills in evenly.
     * Uses registry degrees when available, else known_mvp score (hub exports sparseHubScore).
     */
    private void sortClubBySparseHubLinks(List<TrustedPeer> tier, Random rng) {
        if (tier.size() < 2) {
            return;
        }
        Map<String, Integer> degreeById = Map.of();
        if (plugin.registry() != null && plugin.registry().enabled()) {
            degreeById = plugin.registry().hubLinkDegrees();
        }
        Map<String, Double> scoreById = new HashMap<>();
        Map<String, Double> scoreByHost = new HashMap<>();
        for (var known : db.listKnownMvp(Math.max(80, config.scannerVerifiedLimit() * 2))) {
            if (known.serverId() != null) {
                scoreById.put(known.serverId().toLowerCase(Locale.ROOT), known.score());
            }
            if (known.publicHost() != null) {
                scoreByHost.put(keyHost(known.publicHost(), known.publicPort()), known.score());
            }
        }
        final Map<String, Integer> deg = degreeById;
        record ClubRank(TrustedPeer peer, int degree, double score, double jitter) {}
        List<ClubRank> ranked = new ArrayList<>(tier.size());
        for (TrustedPeer p : tier) {
            int d = 999;
            if (p.serverId() != null && deg.containsKey(p.serverId())) {
                d = deg.get(p.serverId());
            }
            double sc = 0;
            if (p.serverId() != null) {
                sc = scoreById.getOrDefault(p.serverId().toLowerCase(Locale.ROOT), 0.0);
            }
            if (sc <= 0) {
                sc = scoreByHost.getOrDefault(keyHost(p.publicHost(), p.publicPort()), 0.0);
            }
            ranked.add(new ClubRank(p, d, sc, rng.nextDouble()));
        }
        ranked.sort(Comparator
                .comparingInt(ClubRank::degree)
                .thenComparing(Comparator.comparingDouble(ClubRank::score).reversed())
                .thenComparingDouble(ClubRank::jitter));
        tier.clear();
        for (ClubRank r : ranked) {
            tier.add(r.peer());
        }
    }

    private void rankTier(
            List<TrustedPeer> tier,
            int originOnline,
            Map<String, Integer> onlineMap,
            FlowBalance.Weights flowW,
            boolean shuffle,
            Random rng
    ) {
        if (tier.size() < 2) {
            return;
        }
        if (flowW.enabled()) {
            FlowBalance.sortByFlow(
                    tier,
                    originOnline,
                    p -> onlineMap.getOrDefault(keyHost(p.publicHost(), p.publicPort()), -1),
                    flowW,
                    shuffle,
                    rng
            );
            return;
        }
        if (shuffle) {
            Collections.shuffle(tier, rng);
        } else {
            Comparator<TrustedPeer> byHost = Comparator
                    .comparing((TrustedPeer p) -> p.publicHost() == null ? "" : p.publicHost().toLowerCase())
                    .thenComparingInt(TrustedPeer::publicPort);
            tier.sort(byHost);
        }
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
        VersionCompat vc = plugin.versionCompat();
        String ourBranch = versionBranch(vc != null ? vc.mcVersion() : Bukkit.getMinecraftVersion());
        // 1) Network club first: registry MVP + known_mvp (has MultiversePortals)
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
        // 2) Public scanners (MineScan etc.) — fallback after club peers; fail-open per source
        if (plugin.scannerHub() != null && config.scannerEnabled()) {
            try {
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
            } catch (Throwable t) {
                plugin.getLogger().warning("Bind: scanner cache skipped: "
                        + (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage()));
            }
        }
        // 3) Always merge local probe_cache / hub pull — do not stop at a thin scanner cache alone
        for (var entry : db.listBindCandidates(ourBranch, limit, config.scannerDeadTtlMs())) {
            if (isOwnPublicHost(entry.host())) {
                continue;
            }
            String k = keyHost(entry.host(), entry.javaPort());
            if (seen.add(k)) {
                out.add(entry.toPeer());
            }
        }
        if (out.size() < Math.max(8, limit / 4)) {
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

    /** Record a successful Multi/dial destination so the dial skips it for a while. */
    private void rememberRecentBind(String host) {
        int sec = config.dialRecentExcludeSeconds();
        if (sec <= 0 || host == null || host.isBlank()) {
            return;
        }
        recentBindUntil.put(host.toLowerCase(Locale.ROOT), System.currentTimeMillis() + sec * 1000L);
    }

    /** Hosts chosen recently by dial / Multi bind (lowercase). */
    private Set<String> recentExcludeHosts() {
        int sec = config.dialRecentExcludeSeconds();
        if (sec <= 0) {
            return Set.of();
        }
        long now = System.currentTimeMillis();
        recentBindUntil.entrySet().removeIf(e -> e.getValue() == null || e.getValue() <= now);
        return Set.copyOf(recentBindUntil.keySet());
    }

    /** Same public IP/host as this server — never bind Random portals to ourselves. */
    private boolean isOwnPublicHost(String host) {
        return config.isOwnPublicHost(host) || isPrivateOrLocalHost(host);
    }

    /** Docker/LAN addresses must not enter the Random bind pool. */
    private static boolean isPrivateOrLocalHost(String host) {
        if (host == null || host.isBlank()) {
            return true;
        }
        String h = host.trim().toLowerCase(java.util.Locale.ROOT);
        if (h.equals("localhost") || h.equals("127.0.0.1") || h.equals("0.0.0.0") || h.equals("::1")) {
            return true;
        }
        if (h.startsWith("10.") || h.startsWith("192.168.") || h.startsWith("169.254.")) {
            return true;
        }
        if (h.startsWith("172.")) {
            int dot = h.indexOf('.', 4);
            if (dot > 4) {
                try {
                    int second = Integer.parseInt(h.substring(4, dot));
                    if (second >= 16 && second <= 31) {
                        return true;
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return false;
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
        return confirmBedrockStable(host, timeout, needBedrock, clientProto, clientVer, List.of());
    }

    private BedrockConfirm confirmBedrockStable(
            String host,
            int timeout,
            boolean needBedrock,
            int clientProto,
            String clientVer,
            List<Integer> preferredPorts
    ) {
        int n = config.scannerBindConfirmProbes();
        ServerProbe.BedrockInfo first = null;
        List<Integer> ports = ServerProbe.mergeBedrockPorts(preferredPorts, config.scannerBedrockPorts());
        for (int i = 0; i < n; i++) {
            var bed = ServerProbe.findBedrock(host, ports, timeout);
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
