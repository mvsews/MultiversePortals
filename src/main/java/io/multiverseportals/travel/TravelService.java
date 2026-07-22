package io.multiverseportals.travel;

import com.google.gson.JsonObject;
import io.multiverseportals.MultiversePortalsPlugin;
import io.multiverseportals.config.PluginConfig;
import io.multiverseportals.db.Database;
import io.multiverseportals.db.RegistryDatabase;
import io.multiverseportals.federation.PeerClient;
import io.multiverseportals.model.*;
import io.multiverseportals.compat.BedrockPlayers;
import io.multiverseportals.portal.PortalEffects;
import io.multiverseportals.portal.PortalService;
import io.multiverseportals.scanner.ServerProbe;
import io.multiverseportals.util.InventoryCodec;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
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
        PortalEffects effects = plugin.portalEffects();
        if (effects == null) {
            tryEnter(player, portal);
            return;
        }

        if (portal.type() == PortalType.PAIR) {
            if (!portal.isTravelReady() || portal.status() != PortalStatus.ACTIVE) {
                player.sendMessage(mm.deserialize(config.prefix(player) + config.message(player, "pair-broken")));
                return;
            }
        } else {
            if (portal.status() == PortalStatus.BINDING) {
                player.sendMessage(mm.deserialize(config.prefix(player) + config.message(player, "bind-wait")));
                return;
            }
            if (portal.status() == PortalStatus.BIND_FAILED || !portal.hasBoundDestination()) {
                player.sendMessage(mm.deserialize(config.prefix(player) + config.message(player, "bind-not-ready")));
                if (plugin.portalBindService() != null) {
                    plugin.portalBindService().retryBind(portal);
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
            player.sendMessage(mm.deserialize(config.prefix(player) + config.message(player, "searching-hold")));
            return;
        }

        // Sticky MULTI: check destination while charge animation runs (~5s)
        if (portal.type() == PortalType.MULTI && portal.hasBoundDestination()) {
            player.sendMessage(mm.deserialize(config.prefix(player) + config.message(player, "checking-server")));
            ChargeSession session = new ChargeSession(portal, plate);
            sessions.put(uuid, session);
            effects.chargeAndHold(player, portal, plate, this::onMinChargeDone);
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                ResolveResult result = resolveBoundDestination(player, portal);
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
                            String label = portal.boundVersion() != null && !portal.boundVersion().isBlank()
                                    ? portal.boundVersion().replace('\n', ' ').trim()
                                    : (result.serverId() != null
                                    && !result.serverId().isBlank()
                                    && !result.serverId().startsWith("bound:")
                                    ? result.serverId()
                                    : result.host() + ":" + result.port());
                            fx.setChargeDestination(player, label);
                        }
                    }
                    applyResolve(player, result);
                });
            });
            return;
        }

        ChargeSession session = new ChargeSession(portal, plate);
        sessions.put(uuid, session);

        player.sendMessage(mm.deserialize(config.prefix(player) + config.message(player, "plate-triggered")));
        player.sendMessage(mm.deserialize(config.prefix(player) + config.message(player, "searching-server")));
        effects.chargeAndHold(player, portal, plate, this::onMinChargeDone);
        startSearchLoop(player, portal);
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
        } else {
            player.sendMessage(mm.deserialize(config.prefix(player) + config.message(player, "searching-hold")));
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
                    player.sendActionBar(mm.deserialize(config.message(player, "searching-hold")));
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
        if (!config.scannerConfirmBeforeTransfer()) {
            commitDepart(player, session, result);
            return;
        }
        // Confirm off the main thread (network I/O)
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            boolean ok = confirmDestination(player, result);
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) {
                    sessions.remove(player.getUniqueId());
                    return;
                }
                ChargeSession live = sessions.get(player.getUniqueId());
                if (live == null) {
                    return; // left plate
                }
                if (!ok) {
                    if (config.scannerMemoryEnabled() && result.javaPort() > 0) {
                        // already recorded inside confirmDestination
                    }
                    player.sendMessage(mm.deserialize(config.prefix(player) + config.message(player, "dest-unstable")));
                    player.sendActionBar(mm.deserialize(config.message(player, "searching-hold")));
                    live.result.set(null);
                    startSearchLoop(player, live.portal);
                    return;
                }
                commitDepart(player, live, result);
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
                result.host(), result.javaPort(), session.portal.id(), System.currentTimeMillis()));
        depart(player, session.portal, result.host(), result.port(), result.serverId(),
                result.toPortalId(), result.type(), result.returnCapable());
    }

    /** Immediate re-check so we don't transfer to a host that just died / lied in MOTD. */
    private boolean confirmDestination(Player player, ResolveResult result) {
        int timeout = Math.max(800, config.scannerProbeTimeoutMs());
        ServerProbe.Result r = ServerProbe.probe(result.host(), result.javaPort(), timeout);
        if (r.status() != ServerProbe.Status.OK) {
            if (config.scannerMemoryEnabled()) {
                db.recordProbe(result.host(), result.javaPort(), Database.ProbeStatus.DEAD,
                        null, null, null, null, null, null);
            }
            plugin.getLogger().warning("Confirm failed (java) " + result.host() + ":" + result.javaPort());
            return false;
        }
        if (BedrockPlayers.isBedrock(player) && config.scannerRequireGeyserForBedrock()) {
            var bed = ServerProbe.findBedrock(result.host(), config.scannerBedrockPorts(), timeout);
            if (bed.isEmpty()) {
                if (config.scannerMemoryEnabled()) {
                    db.recordProbe(result.host(), result.javaPort(), Database.ProbeStatus.NO_GEYSER,
                            r.online(), r.max(), null, null, null, null);
                }
                return false;
            }
            var info = bed.get();
            if (info.port() != result.port()) {
                plugin.getLogger().warning("Confirm bedrock port changed " + result.port() + "→" + info.port());
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
                return false;
            }
        }
        return true;
    }

    /** Player came back soon after a portal transfer → dest was not actually joinable. */
    public void notePossibleBounceBack(Player player) {
        PendingTransfer p = pendingTransfers.remove(player.getUniqueId());
        if (p == null) {
            return;
        }
        long age = System.currentTimeMillis() - p.atMs;
        if (age > config.scannerBounceBackSeconds() * 1000L) {
            return;
        }
        if (config.scannerMemoryEnabled()) {
            db.recordProbe(p.host, p.javaPort, Database.ProbeStatus.BAD_JOIN,
                    null, null, null, null, null, null);
        }
        plugin.getLogger().warning("Bounce-back from " + p.host + ":" + p.javaPort
                + " after " + (age / 1000) + "s — blacklisted for new binds (sticky portal kept)");
        if (p.portalId != null && plugin.portalBindService() != null) {
            plugin.portalBindService().rebindAfterBounce(p.portalId, p.host, p.javaPort);
        }
        player.sendMessage(mm.deserialize(config.prefix(player) + config.message(player, "bounce-back")
                .replace("%host%", p.host)));
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
            // Same destination server: Bedrock → Geyser UDP, Java → Java TCP
            if (BedrockPlayers.isBedrock(player) && config.scannerRequireGeyserForBedrock()) {
                var bed = ServerProbe.findBedrock(host, config.scannerBedrockPorts(), timeout);
                if (bed.isEmpty()) {
                    return ResolveResult.fail(config.message(player, "no-bedrock-targets"));
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
            portal.clearBound();
            portal.setStatus(PortalStatus.BINDING);
            db.savePortal(portal);
            if (plugin.portalBindService() != null) {
                plugin.portalBindService().retryBind(portal);
            }
            return ResolveResult.fail(config.message(player, "bind-not-ready"));
        }

        if (db.isBadJoinHost(host, javaPort)) {
            // Sticky: still try live probe — blacklist only affects NEW random binds
            plugin.getLogger().info("Bound " + host + " has BAD_JOIN memory — probing anyway (sticky)");
        }

        ServerProbe.Result r = ServerProbe.probe(host, javaPort, timeout);
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

        // Platform routing: Java → Java TCP, Bedrock → Geyser UDP (same host)
        int transferPort = javaPort;
        if (BedrockPlayers.isBedrock(player)) {
            if (config.scannerRequireGeyserForBedrock()) {
                var bed = ServerProbe.findBedrock(host, config.scannerBedrockPorts(), timeout);
                if (bed.isEmpty() && storedBedrockPort > 0) {
                    // Fall back to sticky Bedrock port if live UDP probe flaked
                    transferPort = storedBedrockPort;
                } else if (bed.isEmpty()) {
                    return ResolveResult.fail(config.message(player, "bound-inactive").replace("%host%", host));
                } else {
                    var info = bed.get();
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                    var bed2 = ServerProbe.findBedrock(host, config.scannerBedrockPorts(), timeout);
                    if (bed2.isEmpty() || bed2.get().protocol() != info.protocol()) {
                        if (storedBedrockPort > 0) {
                            transferPort = storedBedrockPort;
                        } else {
                            return ResolveResult.fail(config.message(player, "bound-inactive").replace("%host%", host));
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
            }
        } else {
            // Java clients must never be sent to the Geyser UDP port
            transferPort = javaPort;
            if (portal.boundJavaPort() <= 0 && javaPort > 0) {
                portal.setBoundJavaPort(javaPort);
                db.savePortal(portal);
            }
        }

        // MVP destination: land at a return portal so the player can step back home
        String destServerId = "bound:" + host;
        String toPortalId = portal.boundDestPortalId();
        boolean returnCapable = false;
        if (registry != null && registry.enabled()) {
            Optional<String> resolved = registry.resolveServerIdByHost(host, javaPort);
            if (resolved.isPresent()) {
                destServerId = resolved.get();
            }
            Optional<RegistryServer> rs = destServerId.startsWith("bound:")
                    ? Optional.empty()
                    : registry.find(destServerId);
            if (rs.isPresent() && rs.get().hasPlugin()) {
                MvpLanding landing = requestMvpLanding(rs.get(), portal, player);
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
        }
        plugin.getLogger().info("Route " + player.getName()
                + (BedrockPlayers.isBedrock(player) ? " (Bedrock)" : " (Java)")
                + " → " + host + ":" + transferPort + " [java " + javaPort + "]");
        return ResolveResult.ok(host, transferPort, javaPort, destServerId, toPortalId, PortalType.MULTI, returnCapable);
    }

    /** Landing decision returned by the destination server. */
    private record MvpLanding(String portalId, boolean isReturn) {}

    /** Ask dest MVP which portal to land on (and link reverse to this portal). */
    private MvpLanding requestMvpLanding(RegistryServer dest, Portal fromPortal, Player player) {
        JsonObject offer = new JsonObject();
        offer.addProperty("fromServer", config.serverId());
        offer.addProperty("fromPortalId", fromPortal.id());
        offer.addProperty("publicHost", config.publicHost());
        offer.addProperty("publicPort", config.publicPort());
        if (player != null) {
            offer.addProperty("playerUuid", player.getUniqueId().toString());
        }
        Optional<JsonObject> resp = Optional.empty();
        Optional<TrustedPeer> peer = db.findPeer(dest.serverId());
        if (peer.isPresent()) {
            resp = peerClient.offerTravel(peer.get(), offer);
        }
        if (resp.isEmpty() && dest.federationUrl() != null && !dest.federationUrl().isBlank()) {
            resp = peerClient.postCatalog(dest.federationUrl(), "/travel/offer", offer);
        }
        if (resp.isEmpty()) {
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
                Runnable go = () -> {
                    pendingTransfers.put(player.getUniqueId(), new PendingTransfer(
                            result.host(), result.javaPort(), portal.id(), System.currentTimeMillis()));
                    depart(player, portal, result.host(), result.port(), result.serverId(),
                            result.toPortalId(), result.type(), result.returnCapable());
                };
                if (!config.scannerConfirmBeforeTransfer()) {
                    go.run();
                    return;
                }
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    boolean ok = confirmDestination(player, result);
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (!player.isOnline()) {
                            return;
                        }
                        if (!ok) {
                            player.sendMessage(mm.deserialize(config.prefix(player) + config.message(player, "dest-unstable")));
                            return;
                        }
                        go.run();
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
            ServerProbe.Result r = ServerProbe.probe(peer.publicHost(), peer.publicPort(), timeout);
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
            if (bedrock && config.scannerRequireGeyserForBedrock()) {
                var bed = ServerProbe.findBedrock(
                        peer.publicHost(), config.scannerBedrockPorts(), timeout);
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
                    null, PortalType.MULTI, !oneWay
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
            return ResolveResult.fail(config.message(player, "no-bedrock-targets"));
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
                    returnCapable
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
                RegistryDatabase.RegistryTravel t = incoming.get();

                IngressPolicy.DenyReason deny = ingress.check(player, t.score());
                if (deny != IngressPolicy.DenyReason.OK) {
                    plugin.getLogger().info("Ingress rejected " + player.getName() + ": " + deny);
                    player.kick(mm.deserialize(config.prefix(player) + ingress.reasonMessage(player, deny)));
                    return;
                }
                ingress.recordArrival();

                if (t.toPortalId() != null) {
                    boolean landedAtPortal = db.findPortal(t.toPortalId()).map(portal -> {
                        var world = Bukkit.getWorld(portal.frame().world());
                        if (world == null) {
                            return false;
                        }
                        Location loc = portal.frame().toLocation(world);
                        // Stand in front of the portal so plate isn't instantly re-triggered (join grace also helps)
                        float yaw = loc.getYaw();
                        double rad = Math.toRadians(yaw);
                        loc.add(-Math.sin(rad) * 1.5, 0.1, Math.cos(rad) * 1.5);
                        player.teleportAsync(loc);
                        String key = t.landingReturn() ? "arrived-at-return" : "arrived-placed-at-portal";
                        player.sendMessage(mm.deserialize(config.prefix(player) + config.message(player, key)
                                .replace("%from%", t.fromServer() == null ? "?" : t.fromServer())));
                        return true;
                    }).orElse(false);
                    // Landing portal vanished before arrival → fall back to a normal-spawn notice.
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
                return;
            }
        }

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

    public void clearSession(UUID uuid) {
        sessions.remove(uuid);
    }

    public boolean isTravelPending(UUID uuid) {
        return sessions.containsKey(uuid);
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
        plugin.getLogger().info("Travel offer: land at " + landing.id()
                + (isReturn ? " for return to " + fromServer : " (fallback placement, no return to " + fromServer + ")")
                + (isReturn && fromPortalId != null ? " portal " + fromPortalId : ""));
        return out;
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

    private record PendingTransfer(String host, int javaPort, String portalId, long atMs) {}

    private record ResolveResult(
            boolean ok,
            String failMessage,
            String host,
            int port,
            int javaPort,
            String serverId,
            String toPortalId,
            PortalType type,
            boolean returnCapable
    ) {
        static ResolveResult fail(String msg) {
            return new ResolveResult(false, msg, null, 0, 0, null, null, null, false);
        }

        static ResolveResult ok(String host, int transferPort, int javaPort, String serverId, String toPortalId,
                                PortalType type, boolean returnCapable) {
            return new ResolveResult(true, null, host, transferPort, javaPort, serverId, toPortalId, type, returnCapable);
        }
    }
}
