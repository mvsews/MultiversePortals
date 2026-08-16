package io.multiverseportals.travel;

import com.google.gson.JsonObject;
import io.multiverseportals.MultiversePortalsPlugin;
import io.multiverseportals.config.PluginConfig;
import io.multiverseportals.db.Database;
import io.multiverseportals.db.RegistryDatabase;
import io.multiverseportals.federation.PeerClient;
import io.multiverseportals.model.*;
import io.multiverseportals.away.AwayFrameBuilder;
import io.multiverseportals.compat.BedrockPlayers;
import io.multiverseportals.portal.FrameDetector;
import io.multiverseportals.portal.PortalEffects;
import io.multiverseportals.portal.PortalService;
import io.multiverseportals.scanner.ServerProbe;
import io.multiverseportals.util.InventoryCodec;
import io.multiverseportals.util.ShapeHasher;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Open-network travel. Destination probe runs during charge animation —
 * if the target is full, animation is aborted and no transfer is started.
 */
public final class TravelService {

    private final MultiversePortalsPlugin plugin;
    private final Database db;
    private final RegistryDatabase registry;
    private final PluginConfig config;
    private final PeerClient peerClient;
    private final PortalService portalService;
    private final ConsentService consent;
    private final IngressPolicy ingress;
    private final MiniMessage mm = MiniMessage.miniMessage();
    private final Map<UUID, ChargeSession> sessions = new ConcurrentHashMap<>();
    /** Recent transfers — if player rejoins quickly, dest was a false positive. */
    private final Map<UUID, PendingTransfer> pendingTransfers = new ConcurrentHashMap<>();

    public TravelService(
            MultiversePortalsPlugin plugin,
            Database db,
            PluginConfig config,
            PeerClient peerClient,
            PortalService portalService,
            ConsentService consent,
            IngressPolicy ingress
    ) {
        this.plugin = plugin;
        this.db = db;
        this.registry = plugin.registry();
        this.config = config;
        this.peerClient = peerClient;
        this.portalService = portalService;
        this.consent = consent;
        this.ingress = ingress;
    }

    /**
     * Start charge animation, probe destination in parallel, transfer when both are ready.
     */
    public void beginTravel(Player player, Portal portal, Location plate) {
        if (!player.hasPermission("multiverseportals.travel")) {
            player.sendMessage(mm.deserialize(config.prefix(player) + config.message(player, "no-permission-travel")));
            return;
        }
        if (portal.status() == PortalStatus.BROKEN_LOCAL
                || portal.status() == PortalStatus.BROKEN_REMOTE) {
            player.sendMessage(mm.deserialize(config.prefix(player) + config.message(player, "frame-open")));
            return;
        }

        Portal going = overlayGuestHome(player, portal);
        if (!config.portalKindEnabled(going)) {
            player.sendMessage(mm.deserialize(config.prefix(player)
                    + config.message(player, "type-disabled").replace("%type%", config.portalKindKey(going))));
            return;
        }
        if (going.type() == PortalType.AWAY) {
            if (plugin.biomePortalService() != null) {
                plugin.biomePortalService().travel(player, going);
            }
            return;
        }

        PortalEffects effects = plugin.portalEffects();
        if (effects == null) {
            tryEnter(player, going);
            return;
        }

        if (going.type() == PortalType.PAIR) {
            if (!going.isTravelReady() || going.status() != PortalStatus.ACTIVE) {
                player.sendMessage(mm.deserialize(config.prefix(player) + config.message(player, "pair-broken")));
                return;
            }
        } else {
            if (going.status() == PortalStatus.BINDING) {
                player.sendMessage(mm.deserialize(config.prefix(player) + config.message(player, "bind-wait")));
                return;
            }
            if (going.status() == PortalStatus.BIND_FAILED || !going.hasBoundDestination()) {
                player.sendMessage(mm.deserialize(config.prefix(player) + config.message(player, "bind-not-ready")));
                if (plugin.portalBindService() != null) {
                    plugin.portalBindService().retryBind(going);
                }
                return;
            }
            if (config.readyRequiredForOneWay() && !consent.isReady(player)) {
                player.sendMessage(mm.deserialize(config.prefix(player) + config.message(player, "need-ready")));
                return;
            }
        }

        UUID uuid = player.getUniqueId();
        if (sessions.containsKey(uuid) || effects.isCharging(uuid)) {
            return;
        }

        // Sticky MULTI: check destination while charge animation runs (~5s)
        if (going.type() == PortalType.MULTI && going.hasBoundDestination()) {
            player.sendMessage(mm.deserialize(config.prefix(player) + config.message(player, "checking-server")));
            ChargeSession session = new ChargeSession(going, plate);
            sessions.put(uuid, session);
            effects.chargeAndHold(player, going, plate, this::onMinChargeDone);
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                ResolveResult result = resolveBoundDestination(player, going);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!player.isOnline()) {
                        sessions.remove(player.getUniqueId());
                        return;
                    }
                    if (!sessions.containsKey(player.getUniqueId())) {
                        return; // left plate
                    }
                    if (result.ok()) {
                        player.sendMessage(mm.deserialize(config.prefix(player) + config.message(player, "going-to")
                                .replace("%host%", result.host())
                                .replace("%port%", String.valueOf(result.port()))));
                        PortalEffects fx = plugin.portalEffects();
                        if (fx != null) {
                            String label = going.boundVersion() != null && !going.boundVersion().isBlank()
                                    ? going.boundVersion().replace('\n', ' ').trim()
                                    : (result.serverId() != null
                                    && !result.serverId().isBlank()
                                    && !result.serverId().startsWith("bound:")
                                    ? result.serverId()
                                    : result.host() + ":" + result.port());
                            fx.setChargeDestination(player, label);
                            if (result.iconPng() != null && result.iconPng().length > 0) {
                                fx.setChargeIcon(player, result.iconPng());
                            }
                        }
                    }
                    applyResolve(player, result);
                });
            });
            return;
        }

        ChargeSession session = new ChargeSession(going, plate);
        sessions.put(uuid, session);

        player.sendMessage(mm.deserialize(config.prefix(player) + config.message(player, "plate-triggered")));
        player.sendMessage(mm.deserialize(config.prefix(player) + config.message(player, "searching-server")));
        effects.chargeAndHold(player, going, plate, this::onMinChargeDone);
        startSearchLoop(player, going);
    }

    /**
     * If this player arrived without a true return portal, the landing MULTI acts as home
     * back to origin for {@code travel.guest-home-seconds}.
     */
    private Portal overlayGuestHome(Player player, Portal portal) {
        if (player == null || portal == null || config.guestHomeSeconds() <= 0) {
            return portal;
        }
        var opt = db.findGuestHome(player.getUniqueId());
        if (opt.isEmpty()) {
            return portal;
        }
        Database.GuestHome home = opt.get();
        if (System.currentTimeMillis() > home.expiresAt()) {
            db.deleteGuestHome(player.getUniqueId());
            return portal;
        }
        if (!portal.id().equals(home.portalId()) || home.destHost() == null || home.destHost().isBlank()) {
            return portal;
        }
        Portal shadow = new Portal(
                portal.id(),
                PortalType.MULTI,
                PortalStatus.ACTIVE,
                portal.frame(),
                "home",
                portal.creator()
        );
        shadow.setBoundHost(home.destHost());
        shadow.setBoundPort(home.destPort() > 0 ? home.destPort() : home.destJavaPort());
        shadow.setBoundJavaPort(home.destJavaPort() > 0 ? home.destJavaPort() : home.destPort());
        shadow.setBoundDestPortalId(home.destPortalId());
        shadow.setBoundVersion(home.destServer() != null ? home.destServer() : home.destHost());
        player.sendMessage(mm.deserialize(config.prefix(player) + config.message(player, "guest-home-travel")
                .replace("%from%", home.destServer() != null ? home.destServer() : home.destHost())));
        return shadow;
    }

    private void onMinChargeDone(Player player) {
        ChargeSession session = sessions.get(player.getUniqueId());
        if (session == null) {
            return;
        }
        session.chargeDone.set(true);
        ResolveResult r = session.result.get();
        if (r != null && r.ok()) {
            finishOrAbort(player, session, r);
        }
    }

    /** Keep probing while session is alive (player on plate). */
    private void startSearchLoop(Player player, Portal portal) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            long deadline = System.currentTimeMillis() + config.scannerSearchMaxSeconds() * 1000L;
            int round = 0;
            while (System.currentTimeMillis() < deadline) {
                UUID uuid = player.getUniqueId();
                if (!sessions.containsKey(uuid)) {
                    return; // left plate / cancelled
                }
                ResolveResult result;
                try {
                    result = resolveDestinationOnce(player, portal);
                } catch (Throwable t) {
                    plugin.getLogger().warning("search round failed: " + t.getMessage());
                    result = ResolveResult.fail(config.message(player, "probe-failed"));
                }
                if (result.ok()) {
                    ResolveResult ok = result;
                    Bukkit.getScheduler().runTask(plugin, () -> applyResolve(player, ok));
                    return;
                }
                round++;
                if (round % 2 == 0 && config.scannerRefreshOnFail() && plugin.scannerHub() != null) {
                    try {
                        plugin.scannerHub().refreshBlocking();
                    } catch (Exception e) {
                        plugin.getLogger().warning("Scanner refresh: " + e.getMessage());
                    }
                }
                final int r = round;
                final String tip = result.failMessage();
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!player.isOnline() || !sessions.containsKey(player.getUniqueId())) {
                        return;
                    }
                    String dest = PortalEffects.destinationLabel(portal);
                    player.sendActionBar(mm.deserialize(config.message(player, "charge-hold")
                            .replace("%dest%", dest == null || dest.isBlank() ? "…" : dest)));
                    if (r == 1 || r % 3 == 0) {
                        String msg = tip != null && !tip.isBlank() ? tip : config.message(player, "searching-still")
                                .replace("%n%", String.valueOf(r));
                        // Avoid spamming full "probe-failed" — prefer hold tip / bedrock tip
                        if (tip != null && tip.equals(config.message(player, "probe-failed"))) {
                            msg = config.message(player, "searching-still").replace("%n%", String.valueOf(r));
                        }
                        player.sendMessage(mm.deserialize(config.prefix(player) + msg));
                    }
                });
                try {
                    Thread.sleep(400);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!sessions.containsKey(player.getUniqueId())) {
                    return;
                }
                applyResolve(player, ResolveResult.fail(config.message(player, "search-timeout")
                        .replace("%sec%", String.valueOf(config.scannerSearchMaxSeconds()))));
            });
        });
    }

    private void applyResolve(Player player, ResolveResult result) {
        if (!player.isOnline()) {
            sessions.remove(player.getUniqueId());
            return;
        }
        ChargeSession session = sessions.get(player.getUniqueId());
        if (session == null) {
            return;
        }
        if (!result.ok()) {
            PortalEffects effects = plugin.portalEffects();
            if (effects != null) {
                effects.abort(player.getUniqueId(), config.prefix(player) + result.failMessage());
            } else {
                player.sendMessage(mm.deserialize(config.prefix(player) + result.failMessage()));
            }
            sessions.remove(player.getUniqueId());
            return;
        }
        session.result.set(result);
        PortalEffects fx = plugin.portalEffects();
        if (fx != null && result.ok()) {
            String label = session.portal.boundVersion() != null && !session.portal.boundVersion().isBlank()
                    ? session.portal.boundVersion().replace('\n', ' ').trim()
                    : (result.serverId() != null
                    && !result.serverId().isBlank()
                    && !result.serverId().startsWith("bound:")
                    ? result.serverId()
                    : result.host() + ":" + result.port());
            fx.setChargeDestination(player, label);
            if (result.iconPng() != null && result.iconPng().length > 0) {
                fx.setChargeIcon(player, result.iconPng());
            }
        }
        if (session.chargeDone.get()) {
            finishOrAbort(player, session, result);
        }
        // else: wait for min charge, then onMinChargeDone will depart
    }

    private void finishOrAbort(Player player, ChargeSession session, ResolveResult result) {
        if (!result.ok()) {
            sessions.remove(player.getUniqueId());
            player.sendMessage(mm.deserialize(config.prefix(player) + result.failMessage()));
            return;
        }
        // Sticky bind already probed during the countdown — go, don't "find a world" again.
        if (!config.scannerConfirmBeforeTransfer() || session.portal.hasBoundDestination()) {
            commitDepart(player, session, result);
            return;
        }
        // Confirm off the main thread (network I/O)
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            ResolveResult confirmed = confirmDestination(player, result);
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) {
                    sessions.remove(player.getUniqueId());
                    return;
                }
                ChargeSession live = sessions.get(player.getUniqueId());
                if (live == null) {
                    return; // left plate
                }
                if (confirmed == null) {
                    if (config.scannerMemoryEnabled() && result.javaPort() > 0) {
                        // already recorded inside confirmDestination
                    }
                    player.sendMessage(mm.deserialize(config.prefix(player) + config.message(player, "dest-unstable")));
                    String dest = PortalEffects.destinationLabel(live.portal);
                    player.sendActionBar(mm.deserialize(config.message(player, "charge-hold")
                            .replace("%dest%", dest == null || dest.isBlank() ? "…" : dest)));
                    live.result.set(null);
                    startSearchLoop(player, live.portal);
                    return;
                }
                commitDepart(player, live, confirmed);
            });
        });
    }

    private void commitDepart(Player player, ChargeSession session, ResolveResult result) {
        sessions.remove(player.getUniqueId());
        PortalEffects effects = plugin.portalEffects();
        if (effects != null) {
            effects.signalDepart(player);
        }
        pendingTransfers.put(player.getUniqueId(), new PendingTransfer(
                result.host(), result.javaPort(), result.serverId(), session.portal.id(),
                System.currentTimeMillis(), null, pendingLabel(session.portal, result.host()),
                destHasPortal(result.toPortalId(), result.returnCapable())));
        depart(player, session.portal, result.host(), result.port(), result.serverId(),
                result.toPortalId(), result.type(), result.returnCapable());
    }

    /** Immediate re-check so we don't transfer to a host that just died / lied in MOTD. */
    private ResolveResult confirmDestination(Player player, ResolveResult result) {
        int timeout = Math.max(800, config.scannerProbeTimeoutMs());
        ServerProbe.Result r = ServerProbe.probe(result.host(), result.javaPort(), timeout);
        if (r.status() != ServerProbe.Status.OK) {
            if (config.scannerMemoryEnabled()) {
                db.recordProbe(result.host(), result.javaPort(), Database.ProbeStatus.DEAD,
                        null, null, null, null, null, null);
            }
            plugin.getLogger().warning("Confirm failed (java) " + result.host() + ":" + result.javaPort());
            return null;
        }
        if (BedrockPlayers.isBedrock(player)) {
            var bed = ServerProbe.findBedrock(result.host(),
                    bedrockPortsFor(result.host(), result.javaPort(), result.port()), timeout);
            if (bed.isEmpty()) {
                if (config.scannerMemoryEnabled()) {
                    db.recordProbe(result.host(), result.javaPort(), Database.ProbeStatus.NO_GEYSER,
                            r.online(), r.max(), null, null, null, null);
                }
                // Treat as hard fail for this attempt — caller shows dest-unstable / retries.
                // Bound path already uses bedrock-dest-java-only before confirm.
                return null;
            }
            var info = bed.get();
            int transferPort = info.port();
            if (transferPort != result.port()) {
                plugin.getLogger().warning("Confirm bedrock port changed " + result.port() + "→" + transferPort
                        + " for " + result.host());
                result = ResolveResult.ok(
                        result.host(), transferPort, result.javaPort(), result.serverId(),
                        result.toPortalId(), result.type(), result.returnCapable(), result.iconPng());
            }
            int clientProto = BedrockPlayers.protocolVersion(player).orElse(0);
            String clientVer = BedrockPlayers.versionString(player);
            if (!BedrockPlayers.canJoinDest(
                    clientProto, info.protocol(), clientVer, info.version(),
                    config.scannerRequireBedrockVersionMatch())) {
                if (config.scannerMemoryEnabled()) {
                    db.recordProbe(result.host(), result.javaPort(), Database.ProbeStatus.BAD_PROTO,
                            r.online(), r.max(), info.port(), info.protocol(), info.version(), null);
                }
                plugin.getLogger().warning("Confirm version/proto mismatch dest="
                        + info.protocol() + "/" + info.version() + " client=" + clientProto + "/" + clientVer);
                return null;
            }
        }
        return result;
    }

    /**
     * Bedrock UDP candidates: sticky / registry announced port first, then scanner list,
     * then Java TCP port last (clone-remote-port Geyser setups).
     */
    private List<Integer> bedrockPortsFor(String host, int javaPort, int... preferred) {
        java.util.ArrayList<Integer> pref = new java.util.ArrayList<>();
        if (preferred != null) {
            for (int p : preferred) {
                if (p > 0) {
                    pref.add(p);
                }
            }
        }
        if (registry != null && registry.enabled() && host != null && javaPort > 0) {
            registry.resolveServerIdByHost(host, javaPort).flatMap(registry::find).ifPresent(rs -> {
                int bp = rs.caps() != null ? rs.caps().bedrockPort() : 0;
                if (bp > 0) {
                    pref.add(bp);
                }
            });
        }
        List<Integer> ports = ServerProbe.mergeBedrockPorts(pref, config.scannerBedrockPorts());
        if (javaPort > 0 && !ports.contains(javaPort)) {
            ports.add(javaPort);
        }
        return ports;
    }

    private String bedrockJavaOnlyMessage(Player player, String host) {
        String ver = BedrockPlayers.versionString(player);
        int proto = BedrockPlayers.protocolVersion(player).orElse(0);
        return config.message(player, "bedrock-dest-java-only")
                .replace("%host%", host == null ? "?" : host)
                .replace("%version%", ver == null || ver.isBlank() ? String.valueOf(proto) : ver)
                .replace("%proto%", String.valueOf(proto));
    }

    /** Player came back soon after a portal transfer → dest was not actually joinable. */
    public void notePossibleBounceBack(Player player) {
        PendingTransfer p = pendingTransfers.get(player.getUniqueId());
        if (p == null) {
            return;
        }
        long age = System.currentTimeMillis() - p.atMs;
        long windowMs = config.scannerBounceBackSeconds() * 1000L;
        if (age > windowMs) {
            return;
        }
        // Dest has a portal: they may walk back through it. Wait for dest ARRIVED/REJECTED.
        if (p.destHasPortal) {
            return;
        }
        if (!pendingTransfers.remove(player.getUniqueId(), p)) {
            return;
        }
        if (config.scannerMemoryEnabled()) {
            db.recordProbe(p.host, p.javaPort, Database.ProbeStatus.BAD_JOIN,
                    null, null, null, null, null, null);
        }
        reportBounceFailure(player, p, age);
        notePeer(player, PeerEdge.Kind.FAILED, p.toServerId, p.host, p.javaPort);
        plugin.getLogger().warning("Bounce-back from " + p.host + ":" + p.javaPort
                + " after " + (age / 1000) + "s — blacklisted for new binds (sticky portal kept)");
        if (p.portalId != null && plugin.portalBindService() != null) {
            plugin.portalBindService().rebindAfterBounce(p.portalId, p.host, p.javaPort);
        }
        player.sendMessage(mm.deserialize(config.prefix(player) + config.message(player, "bounce-back")
                .replace("%host%", p.host == null ? "?" : p.host)
                .replace("%label%", bounceDisplayLabel(p))
                .replace("%port%", String.valueOf(p.javaPort))
                .replace("%delta%", String.valueOf((int) Math.round(
                        Math.max(0.0, plugin.getConfig().getDouble("scanner.hub-pool.transfer-fail-score", 40.0)))))));
    }

    private static String bounceDisplayLabel(PendingTransfer p) {
        if (p.label != null && !p.label.isBlank()) {
            return p.label.trim();
        }
        return p.host == null || p.host.isBlank() ? "?" : p.host;
    }

    private static String pendingLabel(Portal portal, String host) {
        if (portal != null) {
            String v = portal.boundVersion();
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return host == null ? "?" : host;
    }

    /**
     * Tell the central hub: transfer failed (player rejoined origin within bounce window).
     * We cannot read the dest kick reason — only this heuristic.
     */
    private void reportBounceFailure(Player player, PendingTransfer p, long ageMs) {
        final String sessionId = p.sessionId;
        final String playerUuid = player.getUniqueId().toString();
        final String fromServer = config.serverId();
        final long withinMs = Math.max(ageMs + 5_000L, config.scannerBounceBackSeconds() * 1000L);

        // Hub with MySQL: Transfer-fail score + mark travel directly (no HTTP double-count).
        if (registry != null && registry.enabled()) {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    registry.recordTransferOutcome(p.host, p.javaPort, false, "bounce");
                    if (sessionId != null && !sessionId.isBlank()) {
                        registry.markTravelBounced(sessionId);
                    } else {
                        registry.markRecentTravelBounced(playerUuid, fromServer, withinMs);
                    }
                } catch (Throwable t) {
                    plugin.getLogger().warning("hub bounce report: "
                            + (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage()));
                }
            });
            return;
        }

        // Leaf: HTTPS report into central scanner pool (status=BAD_JOIN).
        if (plugin.scannerPool() != null) {
            plugin.scannerPool().reportProbeAsync(
                    p.host, p.javaPort, Database.ProbeStatus.BAD_JOIN,
                    null, null, null, null, null);
        }
    }

    /** Single pass: try current candidate pool (no infinite refresh). */
    private ResolveResult resolveDestinationOnce(Player player, Portal portal) {
        int timeout = config.scannerProbeTimeoutMs();
        if (portal.type() == PortalType.PAIR) {
            if (!portal.isTravelReady() || portal.status() != PortalStatus.ACTIVE) {
                return ResolveResult.fail(config.message(player, "pair-broken"));
            }
            Optional<RegistryServer> dest = registry.enabled()
                    ? registry.find(portal.pairServerId())
                    : Optional.empty();
            String host;
            int port;
            String sid;
            if (dest.isPresent()) {
                RegistryServer rs = dest.get();
                if (!portalService.isCompatible(player, rs)) {
                    return ResolveResult.fail(config.message(player, "incompatible-version")
                            .replace("%target%", rs.serverId())
                            .replace("%version%", rs.mcVersion() == null ? "?" : rs.mcVersion()));
                }
                host = rs.publicHost();
                port = rs.publicPort();
                sid = rs.serverId();
            } else {
                Optional<TrustedPeer> peer = db.findPeer(portal.pairServerId());
                if (peer.isEmpty()) {
                    return ResolveResult.fail(config.message(player, "unknown-server"));
                }
                host = peer.get().publicHost();
                port = peer.get().publicPort();
                sid = peer.get().serverId();
            }
            ServerProbe.Result r = ServerProbe.probe(host, port, timeout);
            if (r.status() == ServerProbe.Status.FULL) {
                return ResolveResult.fail(config.message(player, "target-full")
                        .replace("%target%", sid)
                        .replace("%online%", String.valueOf(r.online()))
                        .replace("%max%", String.valueOf(r.max())));
            }
            if (r.status() == ServerProbe.Status.UNREACHABLE) {
                return ResolveResult.fail(config.message(player, "probe-failed"));
            }
            int javaPort = port;
            int transferPort = javaPort;
            // Same destination server: Bedrock → Geyser UDP only, Java → Java TCP only
            if (BedrockPlayers.isBedrock(player)) {
                var bed = ServerProbe.findBedrock(host, bedrockPortsFor(host, javaPort), timeout);
                if (bed.isEmpty()) {
                    return ResolveResult.fail(bedrockJavaOnlyMessage(player, host));
                }
                transferPort = bed.get().port();
            }
            return ResolveResult.ok(host, transferPort, javaPort, sid, portal.pairPortalId(), PortalType.PAIR, true);
        }

        if (portal.hasBoundDestination()) {
            return resolveBoundDestination(player, portal);
        }
        List<TrustedPeer> raw = portalService.listMultiCandidates(portal, player);
        if (raw.isEmpty()) {
            return ResolveResult.fail(config.message(player, "no-compatible-targets"));
        }
        List<TrustedPeer> ordered = buildOrderedCandidates(portal, player);
        if (ordered.isEmpty()) {
            return ResolveResult.fail(config.message(player, "need-ready"));
        }
        return probeCandidates(player, ordered);
    }

    /** Probe the portal's permanently bound host before transfer. */
    private ResolveResult resolveBoundDestination(Player player, Portal portal) {
        if (!portal.hasBoundDestination()) {
            return ResolveResult.fail(config.message(player, "bind-not-ready"));
        }
        String host = portal.boundHost();
        // One logical server: always know Java TCP; Bedrock port may be stored in boundPort
        int javaPort = portal.boundJavaPort() > 0 ? portal.boundJavaPort() : portal.boundPort();
        int storedBedrockPort = 0;
        if (portal.boundJavaPort() > 0 && portal.boundPort() > 0 && portal.boundPort() != portal.boundJavaPort()) {
            storedBedrockPort = portal.boundPort();
        }
        int timeout = config.scannerProbeTimeoutMs();

        // Never transfer to ourselves (bad Random bind / scanner listed our WAN IP)
        if (config.isOwnPublicHost(host)) {
            plugin.getLogger().warning("Bound destination is this server (" + host
                    + ") — clearing sticky and rebinding");
            if (!"home".equals(portal.name())) {
                portal.clearBound();
                portal.setStatus(PortalStatus.BINDING);
                db.savePortal(portal);
                if (plugin.portalBindService() != null) {
                    plugin.portalBindService().retryBind(portal);
                }
            }
            return ResolveResult.fail(config.message(player, "bind-not-ready"));
        }

        if (db.isBadJoinHost(host, javaPort)) {
            // Sticky: still try live probe — blacklist only affects NEW random binds
            plugin.getLogger().info("Bound " + host + " has BAD_JOIN memory — probing anyway (sticky)");
        }

        ServerProbe.StatusInfo slp = ServerProbe.probeStatus(host, javaPort, timeout);
        ServerProbe.Result r = new ServerProbe.Result(slp.status(), slp.online(), slp.max());
        if (r.status() == ServerProbe.Status.FULL) {
            return ResolveResult.fail(config.message(player, "target-full")
                    .replace("%target%", host)
                    .replace("%online%", String.valueOf(r.online()))
                    .replace("%max%", String.valueOf(r.max())));
        }
        if (r.status() != ServerProbe.Status.OK) {
            if (config.scannerMemoryEnabled()) {
                db.recordProbe(host, javaPort, Database.ProbeStatus.DEAD, null, null, null, null, null, null);
            }
            // Sticky: keep bound_* — when dest wakes up, next plate step works again
            return ResolveResult.fail(config.message(player, "bound-inactive").replace("%host%", host));
        }
        portal.setBoundLastOkAt(System.currentTimeMillis());
        if (!"home".equals(portal.name())) {
            db.savePortal(portal);
        }
        final byte[] favicon = slp.hasFavicon() ? slp.faviconPng() : null;

        // Platform routing: Java → Java TCP only; Bedrock → Geyser UDP only (never cross)
        int transferPort = javaPort;
        if (BedrockPlayers.isBedrock(player)) {
            var bed = ServerProbe.findBedrock(host,
                    bedrockPortsFor(host, javaPort, storedBedrockPort), timeout);
            if (bed.isEmpty() && storedBedrockPort > 0) {
                // Fall back to sticky Bedrock port if live UDP probe flaked
                transferPort = storedBedrockPort;
            } else if (bed.isEmpty()) {
                // Java-only destination — Bedrock cannot join
                return ResolveResult.fail(bedrockJavaOnlyMessage(player, host));
            } else {
                var info = bed.get();
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                var bed2 = ServerProbe.findBedrock(host,
                        bedrockPortsFor(host, javaPort, info.port(), storedBedrockPort), timeout);
                if (bed2.isEmpty() || bed2.get().protocol() != info.protocol()) {
                    if (storedBedrockPort > 0) {
                        transferPort = storedBedrockPort;
                    } else {
                        return ResolveResult.fail(bedrockJavaOnlyMessage(player, host));
                    }
                } else {
                    int clientProto = BedrockPlayers.protocolVersion(player).orElse(0);
                    String clientVer = BedrockPlayers.versionString(player);
                    if (config.scannerRequireBedrockProtocolMatch()
                            && !BedrockPlayers.canJoinDest(
                            clientProto, info.protocol(), clientVer, info.version(),
                            config.scannerRequireBedrockVersionMatch())) {
                        if (config.scannerMemoryEnabled()) {
                            db.recordProbe(host, javaPort, Database.ProbeStatus.BAD_PROTO,
                                    r.online(), r.max(), info.port(), info.protocol(), info.version(), null);
                        }
                        return ResolveResult.fail(config.message(player, "bedrock-bound-mismatch")
                                .replace("%version%", clientVer.isBlank() ? String.valueOf(clientProto) : clientVer)
                                .replace("%proto%", String.valueOf(clientProto)));
                    }
                    transferPort = info.port();
                    // Remember Bedrock port on sticky for next time
                    if (portal.boundPort() != transferPort || portal.boundJavaPort() != javaPort) {
                        portal.setBoundPort(transferPort);
                        portal.setBoundJavaPort(javaPort);
                        db.savePortal(portal);
                    }
                }
            }
        } else {
            // Java clients must never be sent to the Geyser UDP port
            transferPort = javaPort;
            if (portal.boundJavaPort() <= 0 && javaPort > 0) {
                portal.setBoundJavaPort(javaPort);
                db.savePortal(portal);
            }
        }

        // MVP destination: land at a return portal so the player can step back home.
        // Works with MySQL registry OR open-network club (known_mvp / trusted peers) when
        // registry.enabled=false on normal (non-hub) servers.
        String destServerId = "bound:" + host;
        String toPortalId = portal.boundDestPortalId();
        boolean returnCapable = false;
        Optional<MvpDest> mvpDest = resolveMvpDestination(host, javaPort);
        if (mvpDest.isPresent()) {
            destServerId = mvpDest.get().serverId();
            MvpLanding landing = requestMvpLanding(
                    mvpDest.get().serverId(), mvpDest.get().federationUrl(), portal, player);
            if (landing != null && landing.portalId() != null && !landing.portalId().isBlank()) {
                toPortalId = landing.portalId();
                returnCapable = landing.isReturn();
                // Only remember the reverse landing when it is a real return portal.
                if (landing.isReturn() && !landing.portalId().equals(portal.boundDestPortalId())) {
                    portal.setBoundDestPortalId(landing.portalId());
                    db.savePortal(portal);
                }
            } else if (toPortalId != null && !toPortalId.isBlank()) {
                returnCapable = true;
            } else {
                toPortalId = findRegistryReturnPortal(destServerId).orElse(null);
                returnCapable = toPortalId != null;
            }
        }
        plugin.getLogger().info("Route " + player.getName()
                + (BedrockPlayers.isBedrock(player) ? " (Bedrock)" : " (Java)")
                + " → " + host + ":" + transferPort + " [java " + javaPort + "]");
        return ResolveResult.ok(host, transferPort, javaPort, destServerId, toPortalId, PortalType.MULTI, returnCapable, favicon);
    }

    /** Club / registry MVP destination used for /travel/offer. */
    private record MvpDest(String serverId, String federationUrl) {}

    /** Landing decision returned by the destination server. */
    private record MvpLanding(String portalId, boolean isReturn) {}

    /**
     * Resolve MultiversePortals destination even when local registry JDBC is off:
     * registry → known_mvp_servers → trusted peers → hub bootstrap federation URL.
     */
    private Optional<MvpDest> resolveMvpDestination(String host, int javaPort) {
        if (host == null || host.isBlank()) {
            return Optional.empty();
        }
        if (registry != null && registry.enabled()) {
            Optional<String> resolved = registry.resolveServerIdByHost(host, javaPort);
            if (resolved.isPresent()) {
                Optional<RegistryServer> rs = registry.find(resolved.get());
                if (rs.isPresent() && rs.get().hasPlugin()) {
                    return Optional.of(new MvpDest(rs.get().serverId(), rs.get().federationUrl()));
                }
            }
        }
        Optional<Database.KnownMvpServer> known = db.findKnownMvpByHostPort(host, javaPort);
        if (known.isPresent()) {
            Database.KnownMvpServer k = known.get();
            String fed = k.federationUrl();
            if (fed == null || fed.isBlank()) {
                fed = "http://" + k.publicHost() + ":25765/mvp/v1";
            }
            return Optional.of(new MvpDest(k.serverId(), fed));
        }
        for (TrustedPeer peer : db.listPeers()) {
            if (!peer.hasPlugin()) {
                continue;
            }
            if (!host.equalsIgnoreCase(peer.publicHost())) {
                continue;
            }
            if (javaPort > 0 && peer.publicPort() > 0 && peer.publicPort() != javaPort) {
                continue;
            }
            return Optional.of(new MvpDest(peer.serverId(), peer.federationUrl()));
        }
        // Leaf → club peer before known_mvp catches up: try dest's default federation port.
        // Failed offers fail fast on connection-refused; vanilla sticky binds stay one-way.
        return Optional.of(new MvpDest("bound:" + host, "http://" + host + ":25765/mvp/v1"));
    }

    /** Ask dest MVP which portal to land on (and link reverse to this portal). */
    private MvpLanding requestMvpLanding(String destServerId, String federationUrl, Portal fromPortal, Player player) {
        JsonObject offer = new JsonObject();
        offer.addProperty("fromServer", config.serverId());
        offer.addProperty("fromPortalId", fromPortal.id());
        offer.addProperty("publicHost", config.publicHost());
        offer.addProperty("publicPort", config.publicPort());
        if (player != null) {
            offer.addProperty("playerUuid", player.getUniqueId().toString());
        }
        Optional<JsonObject> resp = Optional.empty();
        if (destServerId != null && !destServerId.isBlank() && !destServerId.startsWith("bound:")) {
            Optional<TrustedPeer> peer = db.findPeer(destServerId);
            if (peer.isPresent()) {
                resp = peerClient.offerTravel(peer.get(), offer);
            }
        }
        if (resp.isEmpty() && federationUrl != null && !federationUrl.isBlank()) {
            // Fail-open: never stall Transfer on a dead hub / unreachable dest federation.
            boolean guessed = destServerId != null && destServerId.startsWith("bound:");
            boolean hubUrl = peerClient.isHubCoolingDown(federationUrl)
                    || (federationUrl.toLowerCase(java.util.Locale.ROOT).contains("mp.mvse.ws"));
            if (peerClient.isHubCoolingDown(federationUrl)) {
                plugin.getLogger().fine("Skip travel offer — hub cooling down: " + federationUrl);
            } else {
                java.time.Duration to = guessed || hubUrl
                        ? java.time.Duration.ofSeconds(2)
                        : java.time.Duration.ofSeconds(4);
                resp = peerClient.postCatalog(federationUrl, "/travel/offer", offer, to);
            }
        }
        if (resp.isEmpty()) {
            plugin.getLogger().info("Travel offer failed for dest=" + destServerId
                    + " fed=" + federationUrl
                    + (peerClient.lastCatalogError() != null ? " (" + peerClient.lastCatalogError() + ")" : ""));
            return null;
        }
        JsonObject body = resp.get();
        if (!body.has("ok") || !body.get("ok").getAsBoolean()) {
            return null;
        }
        if (body.has("landingPortalId") && !body.get("landingPortalId").isJsonNull()) {
            // Legacy dest servers omit "return" and only ever sent a landing for a real return
            // portal, so treat a missing flag as a genuine return.
            boolean isReturn = !body.has("return") || body.get("return").isJsonNull()
                    || body.get("return").getAsBoolean();
            return new MvpLanding(body.get("landingPortalId").getAsString(), isReturn);
        }
        return null;
    }

    private Optional<String> findRegistryReturnPortal(String destServerId) {
        if (registry == null || !registry.enabled()) {
            return Optional.empty();
        }
        for (var rp : registry.listPortalsOnServer(destServerId)) {
            if (!"ACTIVE".equalsIgnoreCase(rp.status())) {
                continue;
            }
            if (config.serverId().equalsIgnoreCase(rp.destServerId())) {
                return Optional.of(rp.portalId());
            }
            if (config.publicHost() != null && config.publicHost().equalsIgnoreCase(rp.destHost())) {
                return Optional.of(rp.portalId());
            }
        }
        return Optional.empty();
    }

    /** Legacy entry (no animation) — still probes before transfer. */
    public void tryEnter(Player player, Portal portal) {
        if (!player.hasPermission("multiverseportals.travel")) {
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            ResolveResult result = resolveDestinationOnce(player, portal);
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) {
                    return;
                }
                if (!result.ok()) {
                    player.sendMessage(mm.deserialize(config.prefix(player) + result.failMessage()));
                    return;
                }
                java.util.function.Consumer<ResolveResult> go = dest -> {
                    pendingTransfers.put(player.getUniqueId(), new PendingTransfer(
                            dest.host(), dest.javaPort(), dest.serverId(), portal.id(),
                            System.currentTimeMillis(), null, pendingLabel(portal, dest.host()),
                            destHasPortal(dest.toPortalId(), dest.returnCapable())));
                    depart(player, portal, dest.host(), dest.port(), dest.serverId(),
                            dest.toPortalId(), dest.type(), dest.returnCapable());
                };
                if (!config.scannerConfirmBeforeTransfer()) {
                    go.accept(result);
                    return;
                }
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    ResolveResult confirmed = confirmDestination(player, result);
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (!player.isOnline()) {
                            return;
                        }
                        if (confirmed == null) {
                            player.sendMessage(mm.deserialize(config.prefix(player) + config.message(player, "dest-unstable")));
                            return;
                        }
                        go.accept(confirmed);
                    });
                });
            });
        });
    }

    private List<TrustedPeer> buildOrderedCandidates(Portal portal, Player player) {
        List<TrustedPeer> candidates = portalService.listMultiCandidates(portal, player);
        if (candidates.isEmpty()) {
            return candidates;
        }
        boolean anyReturn = candidates.stream().anyMatch(TrustedPeer::hasPlugin);
        boolean allOneWay = candidates.stream().noneMatch(TrustedPeer::hasPlugin);
        if (allOneWay && config.readyRequiredForOneWay() && !consent.isReady(player)) {
            return List.of();
        }
        if (!consent.isReady(player) && anyReturn) {
            List<TrustedPeer> onlyReturn = candidates.stream().filter(TrustedPeer::hasPlugin).toList();
            return onlyReturn.isEmpty() ? List.of() : onlyReturn;
        }
        return candidates;
    }

    private ResolveResult probeCandidates(Player player, List<TrustedPeer> ordered) {
        int timeout = config.scannerProbeTimeoutMs();
        int maxAttempts = Math.max(1, config.scannerMaxAttempts());
        boolean bedrock = BedrockPlayers.isBedrock(player);
        int clientBedrockProto = bedrock
                ? BedrockPlayers.protocolVersion(player).orElse(0)
                : 0;
        String clientBedrockVer = bedrock ? BedrockPlayers.versionString(player) : "";
        boolean memory = config.scannerMemoryEnabled();
        long deadTtl = config.scannerDeadTtlMs();
        int tried = 0;
        int skippedKnownDead = 0;
        int fullHits = 0;
        int deadHits = 0;
        int noGeyserHits = 0;
        int badProtoHits = 0;
        for (TrustedPeer peer : ordered) {
            if (tried >= maxAttempts) {
                break;
            }
            boolean oneWay = !peer.hasPlugin();
            if (oneWay && config.readyRequiredForOneWay() && !consent.isReady(player)) {
                continue;
            }
            if (memory && deadTtl > 0 && db.isRecentlyDead(peer.publicHost(), peer.publicPort(), deadTtl)) {
                skippedKnownDead++;
                continue;
            }
            tried++;
            ServerProbe.StatusInfo infoSlp = ServerProbe.probeStatus(peer.publicHost(), peer.publicPort(), timeout);
            ServerProbe.Result r = new ServerProbe.Result(infoSlp.status(), infoSlp.online(), infoSlp.max());
            byte[] favicon = infoSlp.hasFavicon() ? infoSlp.faviconPng() : null;
            plugin.getLogger().info("Probe " + peer.publicHost() + ":" + peer.publicPort()
                    + " → " + r.status() + (r.online() >= 0 ? " (" + r.online() + "/" + r.max() + ")" : ""));
            if (r.status() == ServerProbe.Status.FULL) {
                fullHits++;
                if (memory) {
                    db.recordProbe(peer.publicHost(), peer.publicPort(), Database.ProbeStatus.FULL,
                            r.online(), r.max(), null, null, null, null);
                }
                continue;
            }
            if (r.status() != ServerProbe.Status.OK) {
                deadHits++;
                if (memory) {
                    db.recordProbe(peer.publicHost(), peer.publicPort(), Database.ProbeStatus.DEAD,
                            null, null, null, null, null, null);
                }
                continue;
            }

            int transferPort = peer.publicPort();
            Integer bedPort = null;
            Integer bedProto = null;
            String bedVer = null;
            if (bedrock) {
                var bed = ServerProbe.findBedrock(
                        peer.publicHost(),
                        bedrockPortsFor(peer.publicHost(), peer.publicPort()),
                        timeout);
                if (bed.isEmpty()) {
                    noGeyserHits++;
                    if (memory) {
                        db.recordProbe(peer.publicHost(), peer.publicPort(), Database.ProbeStatus.NO_GEYSER,
                                r.online(), r.max(), null, null, null, null);
                    }
                    plugin.getLogger().info("Skip " + peer.publicHost() + " — Java OK but no Geyser/Bedrock UDP");
                    continue;
                }
                ServerProbe.BedrockInfo info = bed.get();
                bedPort = info.port();
                bedProto = info.protocol();
                bedVer = info.version();
                if (info.full()) {
                    fullHits++;
                    if (memory) {
                        db.recordProbe(peer.publicHost(), peer.publicPort(), Database.ProbeStatus.FULL,
                                r.online(), r.max(), bedPort, bedProto, bedVer, null);
                    }
                    plugin.getLogger().info("Skip " + peer.publicHost() + ":" + info.port()
                            + " — Bedrock full (" + info.online() + "/" + info.max() + ")");
                    continue;
                }
                if (config.scannerRequireBedrockProtocolMatch()) {
                    if (clientBedrockProto <= 0 || info.protocol() <= 0) {
                        badProtoHits++;
                        if (memory) {
                            db.recordProbe(peer.publicHost(), peer.publicPort(), Database.ProbeStatus.BAD_PROTO,
                                    r.online(), r.max(), bedPort, bedProto, bedVer, null);
                        }
                        plugin.getLogger().info("Skip " + peer.publicHost() + " — unknown Bedrock protocol"
                                + " (client=" + clientBedrockProto + "/" + clientBedrockVer
                                + " dest=" + info.protocol() + "/" + info.version() + ")");
                        continue;
                    }
                    if (!BedrockPlayers.canJoinDest(
                            clientBedrockProto, info.protocol(),
                            clientBedrockVer, info.version(),
                            config.scannerRequireBedrockVersionMatch())) {
                        badProtoHits++;
                        if (memory) {
                            db.recordProbe(peer.publicHost(), peer.publicPort(), Database.ProbeStatus.BAD_PROTO,
                                    r.online(), r.max(), bedPort, bedProto, bedVer, null);
                        }
                        plugin.getLogger().info("Skip " + peer.publicHost() + ":" + info.port()
                                + " — Bedrock mismatch client=" + clientBedrockProto
                                + "/" + clientBedrockVer + " dest=" + info.protocol()
                                + "/" + info.version());
                        continue;
                    }
                }
                transferPort = info.port();
                plugin.getLogger().info("Bedrock OK " + peer.publicHost() + ":" + transferPort
                        + " proto=" + info.protocol() + " ver=" + info.version()
                        + " client=" + clientBedrockProto + "/" + clientBedrockVer);
            }

            if (memory) {
                db.recordProbe(peer.publicHost(), peer.publicPort(), Database.ProbeStatus.OK,
                        r.online(), r.max(), bedPort, bedProto, bedVer, null);
            }
            return ResolveResult.ok(
                    peer.publicHost(), transferPort, peer.publicPort(), peer.serverId(),
                    null, PortalType.MULTI, !oneWay, favicon
            );
        }
        if (skippedKnownDead > 0) {
            plugin.getLogger().info("Skipped " + skippedKnownDead + " recently-dead hosts from memory");
        }
        if (tried == 0) {
            return ResolveResult.fail(config.message(player, "need-ready"));
        }
        if (fullHits > 0 && fullHits >= tried) {
            return ResolveResult.fail(config.message(player, "targets-full"));
        }
        if (bedrock && badProtoHits > 0 && (noGeyserHits + badProtoHits) >= Math.max(1, tried - deadHits - fullHits)) {
            plugin.getLogger().warning("Bedrock: protocol mismatches=" + badProtoHits
                    + " noGeyser=" + noGeyserHits + " client=" + clientBedrockProto + "/" + clientBedrockVer);
            return ResolveResult.fail(config.message(player, "bedrock-version-mismatch")
                    .replace("%version%", clientBedrockVer.isBlank() ? String.valueOf(clientBedrockProto) : clientBedrockVer)
                    .replace("%proto%", String.valueOf(clientBedrockProto)));
        }
        if (bedrock && noGeyserHits > 0 && noGeyserHits >= (tried - deadHits - fullHits)) {
            plugin.getLogger().warning("Bedrock: " + noGeyserHits + " Java servers without Geyser skipped");
            return ResolveResult.fail(bedrockJavaOnlyMessage(player, null));
        }
        plugin.getLogger().warning("No live server after " + tried + " probes (" + deadHits + " dead, "
                + fullHits + " full, noGeyser=" + noGeyserHits + ", badProto=" + badProtoHits + ")");
        return ResolveResult.fail(config.message(player, "probe-failed"));
    }

    private void depart(
            Player player,
            Portal portal,
            String host,
            int port,
            String toServerId,
            String toPortalId,
            PortalType type,
            boolean returnCapable
    ) {
        boolean exportItems = returnCapable && config.policyFor(toServerId).exportInventory();
        byte[] blob = InventoryCodec.encode(player.getInventory());
        double score = db.localScore(player.getUniqueId());
        db.savePlayerState(player.getUniqueId(), blob, null, player.getLevel(), score);

        String sessionId = UUID.randomUUID().toString();
        String invB64 = exportItems ? Base64.getEncoder().encodeToString(blob) : null;

        // Track for bounce-back detection (player rejoins origin within window).
        PendingTransfer prev = pendingTransfers.get(player.getUniqueId());
        pendingTransfers.put(player.getUniqueId(), new PendingTransfer(
                host,
                prev != null && prev.javaPort > 0 ? prev.javaPort : port,
                toServerId,
                portal.id(),
                System.currentTimeMillis(),
                sessionId,
                pendingLabel(portal, host),
                destHasPortal(toPortalId, returnCapable)
        ));
        scheduleDepartSuccess(player.getUniqueId(), sessionId);

        if (registry != null && registry.enabled()) {
            registry.saveTravel(
                    sessionId,
                    player.getUniqueId().toString(),
                    config.serverId(),
                    toServerId,
                    toPortalId,
                    type.name(),
                    exportItems,
                    invB64,
                    score,
                    config.sessionTtlSeconds() * 1000L,
                    returnCapable,
                    host,
                    prev != null && prev.javaPort > 0 ? prev.javaPort : port
            );
        }

        long now = System.currentTimeMillis();
        db.saveSession(new TravelSession(
                sessionId,
                player.getUniqueId(),
                config.serverId(),
                toServerId,
                portal.id(),
                toPortalId,
                type,
                exportItems,
                exportItems ? blob : null,
                "{\"from\":" + score + "}",
                TravelSession.Status.PENDING,
                now,
                now + config.sessionTtlSeconds() * 1000L
        ));

        if (exportItems) {
            player.getInventory().clear();
            player.sendMessage(mm.deserialize(config.prefix(player) + config.message(player, "items-exported")));
        }
        if (!returnCapable) {
            player.sendMessage(mm.deserialize(config.prefix(player) + config.message(player, "one-way-depart")));
        }
        player.sendMessage(mm.deserialize(config.prefix(player) + config.message(player, "going-to")
                .replace("%host%", host)
                .replace("%port%", String.valueOf(port))));
        plugin.getLogger().info("Transfer " + player.getName() + " → " + host + ":" + port
                + " (server=" + toServerId + ", return=" + returnCapable + ")");
        transfer(player, host, port);
    }

    private void transfer(Player player, String host, int port) {
        if (!config.useTransferPacket()) {
            player.kick(mm.deserialize("<yellow>Зайди на</yellow> <white>" + host + ":" + port + "</white>"));
            return;
        }
        if (plugin.portalMatter() != null) {
            plugin.portalMatter().ejectFromSheet(player);
        }
        // Bedrock (Geyser/Floodgate): Paper transfer packet is often a no-op — use Geyser API first.
        if (tryGeyserTransfer(player, host, port)) {
            return;
        }
        try {
            player.transfer(host, port);
        } catch (Throwable t) {
            plugin.getLogger().warning("transfer failed: " + t.getMessage());
            player.sendMessage(mm.deserialize(config.prefix(player)
                    + "<yellow>Автопереход не удался.</yellow> <gray>Зайди вручную:</gray> <white>"
                    + host + ":" + port + "</white>"));
            player.kick(mm.deserialize("<yellow>Join</yellow> <white>" + host + ":" + port + "</white>"));
        }
    }

    /** Soft-depend Geyser transfer for Bedrock clients. Returns true if handed off. */
    private boolean tryGeyserTransfer(Player player, String host, int port) {
        try {
            if (Bukkit.getPluginManager().getPlugin("Geyser-Spigot") == null
                    && Bukkit.getPluginManager().getPlugin("Geyser-Paper") == null) {
                return false;
            }
            Class<?> apiClass = Class.forName("org.geysermc.geyser.api.GeyserApi");
            Object api = apiClass.getMethod("api").invoke(null);
            Object conn = apiClass.getMethod("connectionByUuid", UUID.class).invoke(api, player.getUniqueId());
            if (conn == null) {
                conn = findGeyserConnection(apiClass, api, player);
            }
            if (conn == null) {
                return false;
            }
            conn.getClass().getMethod("transfer", String.class, int.class).invoke(conn, host, port);
            plugin.getLogger().info("Geyser transfer " + player.getName() + " → " + host + ":" + port);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        } catch (Throwable t) {
            plugin.getLogger().warning("Geyser transfer failed: " + t.getMessage());
            return false;
        }
    }

    private static Object findGeyserConnection(Class<?> apiClass, Object api, Player player) throws Exception {
        try {
            Class<?> fg = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
            Object fgApi = fg.getMethod("getInstance").invoke(null);
            Boolean isFg = (Boolean) fg.getMethod("isFloodgatePlayer", UUID.class)
                    .invoke(fgApi, player.getUniqueId());
            if (!Boolean.TRUE.equals(isFg)) {
                return null;
            }
        } catch (ClassNotFoundException e) {
            return null;
        }
        Object coll = apiClass.getMethod("onlineConnections").invoke(api);
        if (coll instanceof Iterable<?> it) {
            for (Object c : it) {
                Object name = c.getClass().getMethod("name").invoke(c);
                if (player.getName().equalsIgnoreCase(String.valueOf(name))) {
                    return c;
                }
                try {
                    Object bedrock = c.getClass().getMethod("bedrockUsername").invoke(c);
                    if (player.getName().equalsIgnoreCase(String.valueOf(bedrock))) {
                        return c;
                    }
                } catch (NoSuchMethodException ignored) {
                }
            }
        }
        return null;
    }

    public void handleJoin(Player player) {
        // Quick rejoin after portal transfer ⇒ dest looked alive but join failed
        notePossibleBounceBack(player);

        if (registry != null && registry.enabled()) {
            Optional<RegistryDatabase.RegistryTravel> incoming = registry.takePendingTravel(player.getUniqueId().toString());
            if (incoming.isPresent()) {
                applyRegistryTravel(player, incoming.get());
                return;
            }
        }

        // Leaf dest / offer-written SQLite session (no MySQL registry on this node)
        Optional<TravelSession> localIncoming = db.takePendingSession(player.getUniqueId());
        if (localIncoming.isPresent()) {
            applyLocalTravel(player, localIncoming.get());
            return;
        }

        // Leaf without open federation: origin hub already saved registry_travel — claim via HTTPS.
        if ((registry == null || !registry.enabled()) && config.catalogShareEnabled()
                && !config.catalogShareBootstrapUrls().isEmpty()) {
            final UUID uuid = player.getUniqueId();
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                Optional<RegistryDatabase.RegistryTravel> claimed = claimPendingFromHub(uuid);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!player.isOnline()) {
                        return;
                    }
                    if (claimed.isPresent()) {
                        applyRegistryTravel(player, claimed.get());
                        return;
                    }
                    finishNormalJoin(player);
                });
            });
            return;
        }

        finishNormalJoin(player);
    }

    private void finishNormalJoin(Player player) {
        if (config.ingressEnabled() && db.isDenied(player.getUniqueId())) {
            player.kick(mm.deserialize(config.prefix(player) + config.message(player, "ingress-denied")));
            return;
        }
        db.loadInventory(player.getUniqueId()).ifPresent(data -> {
            if (isEmpty(player)) {
                InventoryCodec.decodeInto(data, player.getInventory());
            }
        });
    }

    private void applyRegistryTravel(Player player, RegistryDatabase.RegistryTravel t) {
        db.clearPendingSessions(player.getUniqueId());

        if (!config.acceptInbound()) {
            plugin.getLogger().info("Inbound closed — kicking " + player.getName()
                    + " from " + t.fromServer());
            notePeer(player, PeerEdge.Kind.REJECTED, t.fromServer(), null, 0);
            player.kick(mm.deserialize(config.prefix(player) + config.message(player, "guests-closed")));
            return;
        }

        IngressPolicy.DenyReason deny = ingress.check(player, t.score());
        if (deny != IngressPolicy.DenyReason.OK) {
            plugin.getLogger().info("Ingress rejected " + player.getName() + ": " + deny);
            notePeer(player, PeerEdge.Kind.REJECTED, t.fromServer(), null, 0);
            player.kick(mm.deserialize(config.prefix(player) + ingress.reasonMessage(player, deny)));
            return;
        }
        ingress.recordArrival();

        if (t.toPortalId() != null) {
            boolean landedAtPortal = db.findPortal(t.toPortalId()).map(portal -> {
                Location loc = arrivalStandLocation(portal);
                if (loc == null) {
                    return false;
                }
                teleportArrival(player, loc);
                String key = t.landingReturn() ? "arrived-at-return" : "arrived-placed-at-portal";
                player.sendMessage(mm.deserialize(config.prefix(player) + config.message(player, key)
                        .replace("%from%", t.fromServer() == null ? "?" : t.fromServer())));
                return true;
            }).orElse(false);
            if (!landedAtPortal && t.fromServer() != null) {
                player.sendMessage(mm.deserialize(config.prefix(player) + config.message(player, "arrived-no-return-portal")));
            }
        } else if (t.fromServer() != null) {
            player.sendMessage(mm.deserialize(config.prefix(player) + config.message(player, "arrived-no-return-portal")));
        }
        boolean importItems = config.policyFor(t.fromServer()).importInventory();
        if (t.carry() && t.inventoryB64() != null && importItems) {
            byte[] data = Base64.getDecoder().decode(t.inventoryB64());
            InventoryCodec.decodeInto(data, player.getInventory());
        } else if (t.carry() && t.inventoryB64() != null) {
            player.sendMessage(mm.deserialize(config.prefix(player) + config.message(player, "arriving-clean")));
        }
        if (t.score() != null) {
            db.upsertRemoteScore(player.getUniqueId(), t.fromServer(), t.score());
        }
        notePeer(player, PeerEdge.Kind.ARRIVED, t.fromServer(), null, 0);
    }

    private void applyLocalTravel(Player player, TravelSession t) {
        if (!config.acceptInbound()) {
            plugin.getLogger().info("Inbound closed — kicking " + player.getName()
                    + " from " + t.fromServer());
            notePeer(player, PeerEdge.Kind.REJECTED, t.fromServer(), null, 0);
            player.kick(mm.deserialize(config.prefix(player) + config.message(player, "guests-closed")));
            return;
        }
        ingress.recordArrival();
        notePeer(player, PeerEdge.Kind.ARRIVED, t.fromServer(), null, 0);
        boolean landingReturn = t.scoreSnapshotJson() != null
                && t.scoreSnapshotJson().contains("\"landingReturn\":true");
        if (t.toPortalId() != null) {
            boolean landedAtPortal = db.findPortal(t.toPortalId()).map(portal -> {
                Location loc = arrivalStandLocation(portal);
                if (loc == null) {
                    return false;
                }
                teleportArrival(player, loc);
                String key = landingReturn ? "arrived-at-return" : "arrived-placed-at-portal";
                player.sendMessage(mm.deserialize(config.prefix(player) + config.message(player, key)
                        .replace("%from%", t.fromServer() == null ? "?" : t.fromServer())));
                return true;
            }).orElse(false);
            if (!landedAtPortal && t.fromServer() != null) {
                player.sendMessage(mm.deserialize(config.prefix(player) + config.message(player, "arrived-no-return-portal")));
            }
        } else if (t.fromServer() != null) {
            player.sendMessage(mm.deserialize(config.prefix(player) + config.message(player, "arrived-no-return-portal")));
        }
    }

    /** Ask catalog hub for a PENDING arrival booked by a registry-enabled origin. */
    private Optional<RegistryDatabase.RegistryTravel> claimPendingFromHub(UUID playerUuid) {
        JsonObject body = new JsonObject();
        body.addProperty("playerUuid", playerUuid.toString());
        body.addProperty("toServer", config.serverId());
        for (String hub : config.catalogShareBootstrapUrls()) {
            if (hub == null || hub.isBlank() || "none".equalsIgnoreCase(hub)) {
                continue;
            }
            if (peerClient.isHubCoolingDown(hub)) {
                continue;
            }
            Optional<JsonObject> resp = peerClient.postCatalog(
                    hub, "/travel/claim", body, java.time.Duration.ofSeconds(2));
            if (resp.isEmpty()) {
                continue;
            }
            JsonObject j = resp.get();
            if (!j.has("ok") || !j.get("ok").getAsBoolean()) {
                continue;
            }
            plugin.getLogger().info("Claimed hub travel landing for " + playerUuid
                    + " portal=" + (j.has("toPortalId") ? j.get("toPortalId").getAsString() : "?"));
            return Optional.of(new RegistryDatabase.RegistryTravel(
                    j.has("sessionId") ? j.get("sessionId").getAsString() : UUID.randomUUID().toString(),
                    playerUuid.toString(),
                    j.has("fromServer") && !j.get("fromServer").isJsonNull()
                            ? j.get("fromServer").getAsString() : null,
                    config.serverId(),
                    j.has("toPortalId") && !j.get("toPortalId").isJsonNull()
                            ? j.get("toPortalId").getAsString() : null,
                    j.has("portalType") && !j.get("portalType").isJsonNull()
                            ? j.get("portalType").getAsString() : PortalType.MULTI.name(),
                    j.has("carry") && j.get("carry").getAsBoolean(),
                    j.has("inventoryB64") && !j.get("inventoryB64").isJsonNull()
                            ? j.get("inventoryB64").getAsString() : null,
                    j.has("score") && !j.get("score").isJsonNull()
                            ? j.get("score").getAsDouble() : null,
                    !j.has("landingReturn") || j.get("landingReturn").isJsonNull()
                            || j.get("landingReturn").getAsBoolean()
            ));
        }
        return Optional.empty();
    }

    public void clearSession(UUID uuid) {
        sessions.remove(uuid);
    }

    public boolean isTravelPending(UUID uuid) {
        return sessions.containsKey(uuid);
    }

    /**
     * Hub side: leaf destination claims a PENDING registry_travel row for itself.
     * {@code claimedFrom} is X-MVP-Server of the destination (must match to_server).
     */
    public JsonObject handleTravelClaim(JsonObject body, String claimedFrom) {
        JsonObject out = new JsonObject();
        if (registry == null || !registry.enabled()) {
            out.addProperty("ok", false);
            out.addProperty("error", "registry disabled");
            return out;
        }
        if (claimedFrom == null || claimedFrom.isBlank()) {
            out.addProperty("ok", false);
            out.addProperty("error", "from required");
            return out;
        }
        String playerUuid = body != null && body.has("playerUuid") && !body.get("playerUuid").isJsonNull()
                ? body.get("playerUuid").getAsString() : null;
        if (playerUuid == null || playerUuid.isBlank()) {
            out.addProperty("ok", false);
            out.addProperty("error", "playerUuid required");
            return out;
        }
        Optional<RegistryDatabase.RegistryTravel> t = registry.takePendingTravel(playerUuid, claimedFrom);
        if (t.isEmpty()) {
            out.addProperty("ok", false);
            out.addProperty("error", "no pending");
            return out;
        }
        RegistryDatabase.RegistryTravel travel = t.get();
        out.addProperty("ok", true);
        out.addProperty("sessionId", travel.sessionId());
        out.addProperty("fromServer", travel.fromServer());
        out.addProperty("toServer", travel.toServer());
        if (travel.toPortalId() != null) {
            out.addProperty("toPortalId", travel.toPortalId());
        }
        out.addProperty("portalType", travel.portalType() == null ? PortalType.MULTI.name() : travel.portalType());
        out.addProperty("carry", travel.carry());
        if (travel.inventoryB64() != null) {
            out.addProperty("inventoryB64", travel.inventoryB64());
        }
        if (travel.score() != null) {
            out.addProperty("score", travel.score());
        }
        out.addProperty("landingReturn", travel.landingReturn());
        plugin.getLogger().info("Travel claim: " + claimedFrom + " took pending for " + playerUuid
                + " → portal " + travel.toPortalId());
        return out;
    }

    public JsonObject handleRemoteOffer(TrustedPeer peer, JsonObject body) {
        return handleTravelOffer(body, peer != null ? peer.serverId() : null);
    }

    /**
     * Dest side: pick a local portal that leads back to the origin, link reverse landing id,
     * return landingPortalId for registry_travel.to_portal_id.
     */
    public JsonObject handleTravelOffer(JsonObject body, String claimedFrom) {
        JsonObject out = new JsonObject();
        if (body == null) {
            out.addProperty("ok", false);
            out.addProperty("error", "empty");
            return out;
        }
        String fromServer = body.has("fromServer") ? body.get("fromServer").getAsString() : claimedFrom;
        String fromPortalId = body.has("fromPortalId") ? body.get("fromPortalId").getAsString() : null;
        String fromHost = body.has("publicHost") ? body.get("publicHost").getAsString() : null;
        int fromPort = body.has("publicPort") ? body.get("publicPort").getAsInt() : 0;
        if (fromServer == null || fromServer.isBlank()) {
            out.addProperty("ok", false);
            out.addProperty("error", "fromServer required");
            return out;
        }

        // 1) Prefer a portal that leads back to the origin (true return).
        Portal landing = findLocalReturnPortal(fromServer, fromHost, fromPort).orElse(null);
        boolean isReturn = landing != null;
        // 2) Otherwise land at any of our network portals, so the player materializes next
        //    to portal infrastructure instead of being dropped at world spawn.
        if (landing == null) {
            landing = findAnyArrivalPortal().orElse(null);
        }
        // 3) Nothing here at all → tell origin there's no landing; client spawns normally.
        if (landing == null) {
            out.addProperty("ok", false);
            out.addProperty("error", "no return portal");
            out.addProperty("hint", "Build a [Multi]/[To]/" + fromServer + " portal pointing home");
            return out;
        }

        // Only a genuine return portal gets its reverse landing linked back to the origin portal.
        if (isReturn && fromPortalId != null && !fromPortalId.isBlank()) {
            landing.setBoundDestPortalId(fromPortalId);
            // PAIR already has pairPortalId; MULTI sticky keeps bound host, only stores reverse landing
            if (landing.type() == PortalType.PAIR) {
                landing.setPairPortalId(fromPortalId);
            }
            db.savePortal(landing);
            if (plugin.portalService() != null) {
                plugin.portalService().publishPortalGraphAsync();
            }
        }

        out.addProperty("ok", true);
        out.addProperty("landingPortalId", landing.id());
        out.addProperty("return", isReturn);
        out.addProperty("world", landing.frame().world());
        out.addProperty("x", landing.frame().x());
        out.addProperty("y", landing.frame().y());
        out.addProperty("z", landing.frame().z());
        // Origin may have registry.enabled=false — persist pending arrival here so join teleports.
        if (body.has("playerUuid") && !body.get("playerUuid").isJsonNull()) {
            String playerUuid = body.get("playerUuid").getAsString();
            if (playerUuid != null && !playerUuid.isBlank()) {
                rememberInboundLanding(playerUuid, fromServer, landing.id(), isReturn);
                if (!isReturn && config.guestHomeSeconds() > 0) {
                    saveGuestHome(playerUuid, landing, fromServer, fromHost, fromPort, fromPortalId);
                }
            }
        }
        plugin.getLogger().info("Travel offer: land at " + landing.id()
                + (isReturn ? " for return to " + fromServer : " (fallback placement, no return to " + fromServer + ")")
                + (isReturn && fromPortalId != null ? " portal " + fromPortalId : ""));
        return out;
    }

    /**
     * Dest-side booking: origin leaf servers cannot write MySQL registry_travel.
     * Persist PENDING here (registry and/or local SQLite) keyed by player UUID.
     */
    private void rememberInboundLanding(
            String playerUuid,
            String fromServer,
            String landingPortalId,
            boolean isReturn
    ) {
        String sessionId = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        long ttlMs = config.sessionTtlSeconds() * 1000L;
        if (registry != null && registry.enabled()) {
            registry.saveTravel(
                    sessionId,
                    playerUuid,
                    fromServer,
                    config.serverId(),
                    landingPortalId,
                    PortalType.MULTI.name(),
                    false,
                    null,
                    null,
                    ttlMs,
                    isReturn
            );
        }
        try {
            UUID uuid = UUID.fromString(playerUuid);
            db.saveSession(new TravelSession(
                    sessionId,
                    uuid,
                    fromServer,
                    config.serverId(),
                    null,
                    landingPortalId,
                    PortalType.MULTI,
                    false,
                    null,
                    "{\"landingReturn\":" + isReturn + "}",
                    TravelSession.Status.PENDING,
                    now,
                    now + ttlMs
            ));
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("rememberInboundLanding bad uuid: " + playerUuid);
        } catch (RuntimeException e) {
            plugin.getLogger().warning("rememberInboundLanding: " + e.getMessage());
        }
    }

    private void saveGuestHome(
            String playerUuid,
            Portal landing,
            String fromServer,
            String fromHost,
            int fromPort,
            String fromPortalId
    ) {
        try {
            UUID uuid = UUID.fromString(playerUuid);
            long exp = System.currentTimeMillis() + config.guestHomeSeconds() * 1000L;
            db.saveGuestHome(new Database.GuestHome(
                    uuid,
                    landing.id(),
                    fromHost,
                    fromPort,
                    fromPort,
                    fromServer,
                    fromPortalId,
                    exp
            ));
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("saveGuestHome bad uuid: " + playerUuid);
        }
    }

    private Optional<Portal> findLocalReturnPortal(String fromServer, String fromHost, int fromPort) {
        Portal best = null;
        int bestScore = -1;
        for (Portal p : db.listPortals()) {
            if (p.status() != PortalStatus.ACTIVE && p.status() != PortalStatus.BINDING) {
                continue;
            }
            int score = 0;
            if (p.type() == PortalType.PAIR && fromServer.equalsIgnoreCase(p.pairServerId())) {
                score = 100;
            } else if (p.type() == PortalType.MULTI && p.hasBoundDestination()) {
                boolean hostMatch = fromHost != null && fromHost.equalsIgnoreCase(p.boundHost());
                boolean portMatch = fromPort <= 0
                        || p.boundJavaPort() == fromPort
                        || p.boundPort() == fromPort;
                if (hostMatch && portMatch) {
                    score = 80;
                } else if (hostMatch) {
                    score = 60;
                } else if (registry != null && registry.enabled()) {
                    Optional<String> sid = registry.resolveServerIdByHost(
                            p.boundHost(),
                            p.boundJavaPort() > 0 ? p.boundJavaPort() : p.boundPort());
                    if (sid.isPresent() && fromServer.equalsIgnoreCase(sid.get())) {
                        score = 70;
                    }
                }
                if (score < 70) {
                    Optional<Database.KnownMvpServer> known = db.findKnownMvpByHostPort(
                            p.boundHost(),
                            p.boundJavaPort() > 0 ? p.boundJavaPort() : p.boundPort());
                    if (known.isPresent() && fromServer.equalsIgnoreCase(known.get().serverId())) {
                        score = Math.max(score, 70);
                    }
                }
            } else {
                continue;
            }
            if (p.status() == PortalStatus.ACTIVE) {
                score += 5;
            }
            if (p.boundDestPortalId() != null && !p.boundDestPortalId().isBlank()) {
                score += 10;
            }
            if (score > bestScore) {
                bestScore = score;
                best = p;
            }
        }
        return Optional.ofNullable(best);
    }

    /**
     * Stand in front of the opening, not on the plate and not in the purple sheet.
     * Standing on the portal after arrival used to start a reverse hop.
     */
    private Location arrivalStandLocation(Portal portal) {
        if (portal == null || portal.frame() == null) {
            return null;
        }
        World world = Bukkit.getWorld(portal.frame().world());
        if (world == null) {
            return null;
        }
        PortalFrame f = portal.frame();
        Block sign = world.getBlockAt(f.x(), f.y(), f.z());
        BlockFace face = FrameDetector.facingOf(sign);
        Location loc = findArrivalPlate(world, f);
        if (loc == null) {
            loc = f.toLocation(world);
            loc.add(0.5, -1.9, 0.5);
        }
        loc.add(face.getModX() * 1.5, 0, face.getModZ() * 1.5);
        loc.setYaw(AwayFrameBuilder.yawOf(face));
        return pushOutOfPortal(loc, face);
    }

    private static Location findArrivalPlate(World world, PortalFrame f) {
        int[] dys = { -2, -1, -3, 0 };
        for (int dy : dys) {
            Block b = world.getBlockAt(f.x(), f.y() + dy, f.z());
            if (ShapeHasher.isPressurePlate(b.getType())) {
                return new Location(world, f.x() + 0.5, f.y() + dy + 0.05, f.z() + 0.5);
            }
        }
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                for (int dy : dys) {
                    Block b = world.getBlockAt(f.x() + dx, f.y() + dy, f.z() + dz);
                    if (ShapeHasher.isPressurePlate(b.getType())) {
                        return new Location(
                                world, f.x() + dx + 0.5, f.y() + dy + 0.05, f.z() + dz + 0.5);
                    }
                }
            }
        }
        return null;
    }

    private static Location pushOutOfPortal(Location loc, BlockFace face) {
        for (int i = 0; i < 4; i++) {
            Block feet = loc.getBlock();
            Block head = loc.clone().add(0, 1, 0).getBlock();
            Block below = feet.getRelative(BlockFace.DOWN);
            boolean inside = feet.getType() == Material.NETHER_PORTAL
                    || head.getType() == Material.NETHER_PORTAL
                    || ShapeHasher.isPressurePlate(feet.getType())
                    || ShapeHasher.isPressurePlate(below.getType());
            if (!inside) {
                break;
            }
            loc.add(face.getModX(), 0, face.getModZ());
        }
        return loc;
    }

    private void teleportArrival(Player player, Location loc) {
        player.teleportAsync(loc).thenAccept(ok -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (plugin.portalListener() != null) {
                plugin.portalListener().syncOccupancy(player);
            }
        }));
    }

    /**
     * Fallback landing when no portal leads back to the origin: pick any network portal so the
     * arriving player spawns next to portal infrastructure. Prefer ACTIVE, then a bound/cross
     * destination. Local wool portals are excluded — those are intra-server only.
     */
    private Optional<Portal> findAnyArrivalPortal() {
        Portal best = null;
        int bestScore = -1;
        for (Portal p : db.listPortals()) {
            if (p.type() != PortalType.MULTI && p.type() != PortalType.PAIR) {
                continue;
            }
            if (p.status() != PortalStatus.ACTIVE && p.status() != PortalStatus.BINDING) {
                continue;
            }
            if (Bukkit.getWorld(p.frame().world()) == null) {
                continue;
            }
            int score = 0;
            if (p.status() == PortalStatus.ACTIVE) {
                score += 10;
            }
            if (p.type() == PortalType.MULTI && io.multiverseportals.portal.PortalBindService.fixedEndpoint(p).isEmpty()) {
                score += 20;
            }
            if (p.hasBoundDestination()) {
                score += 5;
            }
            if (p.type() == PortalType.PAIR && p.pairServerId() != null && !p.pairServerId().isBlank()) {
                score += 3;
            }
            if (score > bestScore) {
                bestScore = score;
                best = p;
            }
        }
        return Optional.ofNullable(best);
    }

    private void scheduleDepartSuccess(UUID uuid, String sessionId) {
        long ticks = 20L * Math.max(30, config.scannerBounceBackSeconds());
        Bukkit.getScheduler().runTaskLater(plugin, () ->
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> settleDepartTimer(uuid, sessionId)),
                ticks);
    }

    /**
     * Dest with a portal reported ARRIVED / REJECTED via catalog hop. Settles immediately.
     */
    public void onDestHopReport(HopEvent event) {
        if (event == null) {
            return;
        }
        String self = config.serverId();
        if (self != null && event.fromServer() != null
                && !self.equalsIgnoreCase(event.fromServer())) {
            return;
        }
        String uuidStr = event.playerUuid();
        if (uuidStr == null || uuidStr.isBlank()) {
            return;
        }
        UUID uuid;
        try {
            uuid = UUID.fromString(uuidStr);
        } catch (IllegalArgumentException e) {
            return;
        }
        PendingTransfer p = pendingTransfers.get(uuid);
        if (p == null || !p.destHasPortal) {
            return;
        }
        TransferSettle.Outcome out = TransferSettle.decide(
                true, Bukkit.getPlayer(uuid) != null, null, event.outcome());
        settlePending(uuid, p, out);
    }

    private void settleDepartTimer(UUID uuid, String sessionId) {
        PendingTransfer still = pendingTransfers.get(uuid);
        if (still == null || sessionId == null || !sessionId.equals(still.sessionId)) {
            return;
        }
        Player online = Bukkit.getPlayer(uuid);
        boolean playerBack = online != null && online.isOnline();
        String travelSt = null;
        String hop = null;
        if (registry != null && registry.enabled()) {
            travelSt = registry.travelStatus(sessionId).orElse(null);
            hop = registry.recentHopOutcome(config.serverId(), uuid.toString(), still.atMs - 5_000L)
                    .orElse(null);
        }
        TransferSettle.Outcome out = TransferSettle.decide(still.destHasPortal, playerBack, travelSt, hop);
        settlePending(uuid, still, out);
    }

    private void settlePending(UUID uuid, PendingTransfer p, TransferSettle.Outcome outcome) {
        if (p == null || outcome == null) {
            return;
        }
        if (!pendingTransfers.remove(uuid, p)) {
            return;
        }
        if (outcome == TransferSettle.Outcome.REFUSE) {
            notePeer(uuid, null, PeerEdge.Kind.FAILED, p.toServerId, p.host, p.javaPort);
            if (config.scannerMemoryEnabled() && p.host != null && p.javaPort > 0) {
                db.recordProbe(p.host, p.javaPort, Database.ProbeStatus.BAD_JOIN,
                        null, null, null, null, null, null);
            }
            final String sessionId = p.sessionId;
            final String playerUuid = uuid.toString();
            final String fromServer = config.serverId();
            if (registry != null && registry.enabled()) {
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    try {
                        registry.recordTransferOutcome(p.host, p.javaPort, false, "bounce");
                        if (sessionId != null && !sessionId.isBlank()) {
                            registry.markTravelBounced(sessionId);
                        } else {
                            registry.markRecentTravelBounced(playerUuid, fromServer,
                                    config.scannerBounceBackSeconds() * 1000L);
                        }
                    } catch (Exception e) {
                        plugin.getLogger().warning("settle refuse: " + e.getMessage());
                    }
                });
            }
            plugin.getLogger().warning("Transfer refused " + p.host + ":" + p.javaPort
                    + (p.destHasPortal ? " (dest portal)" : " (no dest portal, bounce)"));
            if (p.portalId != null && plugin.portalBindService() != null) {
                Bukkit.getScheduler().runTask(plugin, () ->
                        plugin.portalBindService().rebindAfterBounce(p.portalId, p.host, p.javaPort));
            }
            return;
        }
        notePeer(uuid, null, PeerEdge.Kind.DEPARTED, p.toServerId, p.host, p.javaPort);
        plugin.getLogger().info("Transfer accepted " + p.host + ":" + p.javaPort
                + " — +1 reputation");
    }

    private static boolean destHasPortal(String toPortalId, boolean returnCapable) {
        return returnCapable || (toPortalId != null && !toPortalId.isBlank());
    }

    private void notePeer(Player player, PeerEdge.Kind kind, String serverId, String host, int port) {
        UUID uuid = player == null ? null : player.getUniqueId();
        String name = player == null ? null : player.getName();
        notePeer(uuid, name, kind, serverId, host, port);
    }

    private void notePeer(UUID uuid, String name, PeerEdge.Kind kind, String serverId, String host, int port) {
        if (kind == null) {
            return;
        }
        boolean hasId = serverId != null && !serverId.isBlank();
        boolean hasHost = host != null && !host.isBlank();
        if (!hasId && !hasHost) {
            return;
        }
        final String playerUuid = uuid == null ? null : uuid.toString();
        final String playerName = name;
        final PeerEdge.Kind eventKind = kind;
        final String sid = serverId;
        final String h = host;
        final int p = port;
        Runnable write = () -> {
            db.recordPeerEvent(eventKind, sid, h, p, playerUuid, playerName);
            if (plugin.catalogShareService() != null) {
                plugin.catalogShareService().pushNowAsync();
            }
        };
        if (Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, write);
        } else {
            write.run();
        }
    }

    private static boolean isEmpty(Player player) {
        for (var item : player.getInventory().getContents()) {
            if (item != null && !item.getType().isAir()) {
                return false;
            }
        }
        return true;
    }

    private static final class ChargeSession {
        final Portal portal;
        final Location plate;
        final AtomicReference<ResolveResult> result = new AtomicReference<>();
        final AtomicBoolean chargeDone = new AtomicBoolean(false);

        ChargeSession(Portal portal, Location plate) {
            this.portal = portal;
            this.plate = plate;
        }
    }

    private record PendingTransfer(
            String host,
            int javaPort,
            String toServerId,
            String portalId,
            long atMs,
            String sessionId,
            String label,
            boolean destHasPortal
    ) {}

    private record ResolveResult(
            boolean ok,
            String failMessage,
            String host,
            int port,
            int javaPort,
            String serverId,
            String toPortalId,
            PortalType type,
            boolean returnCapable,
            byte[] iconPng
    ) {
        static ResolveResult fail(String msg) {
            return new ResolveResult(false, msg, null, 0, 0, null, null, null, false, null);
        }

        static ResolveResult ok(String host, int transferPort, int javaPort, String serverId, String toPortalId,
                                PortalType type, boolean returnCapable) {
            return ok(host, transferPort, javaPort, serverId, toPortalId, type, returnCapable, null);
        }

        static ResolveResult ok(String host, int transferPort, int javaPort, String serverId, String toPortalId,
                                PortalType type, boolean returnCapable, byte[] iconPng) {
            return new ResolveResult(true, null, host, transferPort, javaPort, serverId, toPortalId, type, returnCapable, iconPng);
        }
    }
}
