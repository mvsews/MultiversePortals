package io.multiverseportals.portal;

import com.google.gson.JsonObject;
import io.multiverseportals.MultiversePortalsPlugin;
import io.multiverseportals.config.PluginConfig;
import io.multiverseportals.compat.BedrockPlayers;
import io.multiverseportals.compat.VersionCompat;
import io.multiverseportals.db.Database;
import io.multiverseportals.db.RegistryDatabase;
import io.multiverseportals.model.*;
import io.multiverseportals.util.ShapeHasher;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public final class PortalService {

    private final MultiversePortalsPlugin plugin;
    private final Database db;
    private final RegistryDatabase registry;
    private final PluginConfig config;
    private BukkitTask heartbeatTask;
    private BukkitTask registryTask;

    public PortalService(MultiversePortalsPlugin plugin, Database db, RegistryDatabase registry, PluginConfig config) {
        this.plugin = plugin;
        this.db = db;
        this.registry = registry;
        this.config = config;
    }

    public void startHeartbeat() {
        long period = config.heartbeatSeconds() * 20L;
        heartbeatTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::heartbeatTick, period, period);
        if (registry.enabled()) {
            long regPeriod = config.registryHeartbeatSeconds() * 20L;
            // First central-DB heartbeat almost immediately; then on the normal interval
            registryTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::registryAnnounce, 20L, regPeriod);
        }
        // Particles inside portal matter + keep displays present
        Bukkit.getScheduler().runTaskTimer(plugin, this::matterTick, 40L, 10L);
    }

    private void matterTick() {
        if (plugin.portalMatter() == null) {
            return;
        }
        for (Portal portal : db.listPortals()) {
            plugin.portalMatter().tickParticles(portal);
        }
    }

    public void stopHeartbeat() {
        if (heartbeatTask != null) {
            heartbeatTask.cancel();
        }
        if (registryTask != null) {
            registryTask.cancel();
        }
    }

    private void registryAnnounce() {
        Bukkit.getScheduler().runTask(plugin, () -> {
            int online = Bukkit.getOnlinePlayers().size();
            int max = Bukkit.getMaxPlayers();
            String motd = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                    .serialize(Bukkit.motd());
            List<Portal> portalSnapshot = db.listPortals();
            Map<String, List<String>> commentSigns = collectCommentSigns(portalSnapshot);
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                var vc = plugin.versionCompat();
                if (vc == null) {
                    registry.announceSelf(online, max, motd, "unknown", 0, false, false, false, 0, 0,
                            io.multiverseportals.compat.ServerCaps.detect(config));
                } else {
                    registry.announceSelf(
                            online, max, motd,
                            vc.mcVersion(),
                            vc.nativeProtocol(),
                            vc.viaVersion(),
                            vc.viaBackwards(),
                            vc.viaRewind(),
                            vc.viaMin(),
                            vc.viaMax(),
                            io.multiverseportals.compat.ServerCaps.detect(config)
                    );
                }
                if (config.registryPublishPortals()) {
                    int n = registry.syncPortals(portalSnapshot, commentSigns);
                    if (n > 0) {
                        plugin.getLogger().fine("Registry portal graph sync: " + n);
                    }
                }
                syncClaimedPairs();
            });
        });
    }

    /** Push portal graph to shared registry / hub catalog (async). Comment signs on main thread. */
    public void publishPortalGraphAsync() {
        Bukkit.getScheduler().runTask(plugin, () -> {
            List<Portal> list = db.listPortals();
            Map<String, List<String>> signs = collectCommentSigns(list);
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    if (registry.enabled() && config.registryPublishPortals()) {
                        registry.syncPortals(list, signs);
                    }
                } catch (Exception e) {
                    plugin.getLogger().warning("Portal graph publish failed: " + e.getMessage());
                }
                // Peers (no JDBC): push edges to hub so mp.mvse.ws map updates live
                if (plugin.catalogShareService() != null) {
                    plugin.catalogShareService().pushNowAsync();
                }
            });
        });
    }

    /** Main-thread only. */
    private static Map<String, List<String>> collectCommentSigns(List<Portal> portals) {
        Map<String, List<String>> out = new HashMap<>();
        for (Portal p : portals) {
            if (p == null) {
                continue;
            }
            List<String> signs = PortalCommentSigns.collect(p);
            if (!signs.isEmpty()) {
                out.put(p.id(), signs);
            }
        }
        return out;
    }

    /** When someone accepts our [Pair] invite on another server — activate local side. */
    private void syncClaimedPairs() {
        for (var claimed : registry.listClaimedForHost()) {
            db.findPortal(claimed.hostPortalId()).ifPresent(portal -> {
                if (portal.pairServerId() == null || portal.status() != PortalStatus.ACTIVE) {
                    acceptPairLocally(portal, claimed.claimedByServer(), claimed.claimedPortalId());
                    plugin.getLogger().info("PAIR activated via registry: " + portal.id()
                            + " ↔ " + claimed.claimedByServer());
                    if (plugin.portalMatter() != null) {
                        Bukkit.getScheduler().runTask(plugin, () -> plugin.portalMatter().refresh(portal));
                    }
                }
            });
        }
    }

    private void heartbeatTick() {
        // Sign/block state must be read on the main thread (Paper throws on async access).
        Bukkit.getScheduler().runTask(plugin, () -> {
            List<String> multiGone = new ArrayList<>();
            for (Portal portal : db.listPortals()) {
                boolean hasSign = hasPortalSign(portal);
                boolean intact = hasSign && isLocalIntact(portal);

                if (portal.type() == PortalType.MULTI) {
                    if (!hasSign) {
                        // Only delete when the control sign is gone (player broke it to re-roll).
                        multiGone.add(portal.id());
                        continue;
                    }
                    if (!intact) {
                        // Nearby rebuild changed shape hash — keep sticky bind, refresh hash.
                        refreshShapeHash(portal);
                    }
                    if (portal.status() == PortalStatus.BROKEN_LOCAL) {
                        portal.setStatus(PortalStatus.ACTIVE);
                        db.savePortal(portal);
                        PortalSigns.update(portal);
                        if (plugin.portalMatter() != null) {
                            plugin.portalMatter().refresh(portal);
                        }
                    }
                } else if (!intact && portal.status() == PortalStatus.ACTIVE) {
                    portal.setStatus(PortalStatus.BROKEN_LOCAL);
                    db.savePortal(portal);
                    PortalSigns.update(portal);
                    notifyPairBroken(portal);
                    if (plugin.portalMatter() != null) {
                        plugin.portalMatter().remove(portal.id());
                    }
                } else if (intact && portal.status() == PortalStatus.BROKEN_LOCAL) {
                    // stays broken until remote confirms; local heal alone is not enough for PAIR
                }

                if (portal.type() == PortalType.PAIR && portal.pairServerId() != null) {
                    final boolean intactFinal = intact;
                    Bukkit.getScheduler().runTaskAsynchronously(plugin, () ->
                            db.findPeer(portal.pairServerId()).ifPresent(peer -> {
                                JsonObject beat = new JsonObject();
                                beat.addProperty("portalId", portal.id());
                                beat.addProperty("remotePortalId", portal.pairPortalId());
                                beat.addProperty("intact", intactFinal);
                                beat.addProperty("status", portal.status().name());
                                plugin.peerClient().heartbeatPortal(peer, beat).ifPresent(resp -> {
                                    Bukkit.getScheduler().runTask(plugin, () -> applyPairHeartbeat(portal, intactFinal, resp));
                                });
                            }));
                }
            }
            for (String id : multiGone) {
                delete(id);
            }
        });
    }

    private void refreshShapeHash(Portal portal) {
        World world = Bukkit.getWorld(portal.frame().world());
        if (world == null) {
            return;
        }
        Block block = world.getBlockAt(portal.frame().x(), portal.frame().y(), portal.frame().z());
        String hash = ShapeHasher.hashAround(block.getLocation());
        if (hash.equals(portal.frame().shapeHash())) {
            return;
        }
        portal.setFrame(PortalFrame.from(block.getLocation(), hash));
        db.savePortal(portal);
        plugin.getLogger().info("Portal " + portal.id() + " shape hash refreshed (sticky bind kept)");
    }

    private void applyPairHeartbeat(Portal portal, boolean intact, JsonObject resp) {
        if (resp.has("remoteIntact") && !resp.get("remoteIntact").getAsBoolean()) {
            if (portal.status() == PortalStatus.ACTIVE) {
                portal.setStatus(PortalStatus.BROKEN_REMOTE);
                db.savePortal(portal);
                PortalSigns.update(portal);
            }
        } else if (resp.has("remoteIntact") && resp.get("remoteIntact").getAsBoolean()
                && intact && portal.status() != PortalStatus.DISABLED) {
            portal.setStatus(PortalStatus.ACTIVE);
            db.savePortal(portal);
            PortalSigns.update(portal);
        }
    }

    private void notifyPairBroken(Portal portal) {
        if (portal.type() != PortalType.PAIR || portal.pairServerId() == null) {
            return;
        }
        db.findPeer(portal.pairServerId()).ifPresent(peer -> {
            JsonObject beat = new JsonObject();
            beat.addProperty("portalId", portal.id());
            beat.addProperty("remotePortalId", portal.pairPortalId());
            beat.addProperty("intact", false);
            beat.addProperty("status", PortalStatus.BROKEN_LOCAL.name());
            plugin.peerClient().heartbeatPortal(peer, beat);
        });
    }

    public boolean isLocalIntact(Portal portal) {
        World world = Bukkit.getWorld(portal.frame().world());
        if (world == null) {
            return false;
        }
        Block block = world.getBlockAt(portal.frame().x(), portal.frame().y(), portal.frame().z());
        if (!ShapeHasher.looksLikePortalSign(block)) {
            return false;
        }
        String hash = ShapeHasher.hashAround(block.getLocation());
        return hash.equals(portal.frame().shapeHash());
    }

    /** Sign still present (MVP/control), even if nearby frame blocks changed. */
    public boolean hasPortalSign(Portal portal) {
        World world = Bukkit.getWorld(portal.frame().world());
        if (world == null) {
            return false;
        }
        Block block = world.getBlockAt(portal.frame().x(), portal.frame().y(), portal.frame().z());
        return ShapeHasher.looksLikePortalSign(block);
    }

    public Optional<Portal> findNear(Location loc) {
        World w = loc.getWorld();
        if (w == null) {
            return Optional.empty();
        }
        // Always nearest frame — adjacent portals (4 blocks apart) must not steal each other's plates.
        Portal best = null;
        double bestDist = 5.5;
        for (Portal portal : db.listPortals()) {
            if (!w.getName().equals(portal.frame().world())) {
                continue;
            }
            double dx = portal.frame().x() + 0.5 - loc.getX();
            double dy = portal.frame().y() + 0.5 - loc.getY();
            double dz = portal.frame().z() + 0.5 - loc.getZ();
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (dist < bestDist) {
                bestDist = dist;
                best = portal;
            }
        }
        return Optional.ofNullable(best);
    }

    public Portal createFromSign(Player player, Block signBlock, PortalType type, String name) {
        if (!(signBlock.getState() instanceof Sign)) {
            throw new IllegalArgumentException("Not a sign");
        }
        // Recreate at same frame → drop old portal + cancel bind
        findPortalByFrameLoc(signBlock.getLocation()).ifPresent(old -> delete(old.id()));

        String hash = ShapeHasher.hashAround(signBlock.getLocation());
        PortalFrame frame = PortalFrame.from(signBlock.getLocation(), hash);
        String id = UUID.randomUUID().toString();
        PortalStatus status = type == PortalType.PAIR ? PortalStatus.PENDING_PAIR
                : (type == PortalType.MULTI ? PortalStatus.BINDING : PortalStatus.ACTIVE);
        Portal portal = new Portal(id, type, status, frame, name, player.getUniqueId());
        if (type == PortalType.PAIR) {
            portal.setPairInviteCode(randomCode());
        }
        db.savePortal(portal);
        Bukkit.getScheduler().runTask(plugin, () -> {
            PortalSigns.update(portal);
            if (plugin.portalMatter() != null) {
                plugin.portalMatter().refresh(portal);
            }
        });
        return portal;
    }

    public Optional<Portal> findPortalByFrameLoc(Location loc) {
        if (loc == null || loc.getWorld() == null) {
            return Optional.empty();
        }
        return db.findPortalByFrame(loc.getWorld().getName(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
    }

    public String beginPairInvite(Portal portal) {
        if (portal.pairInviteCode() == null) {
            portal.setPairInviteCode(randomCode());
            db.savePortal(portal);
        }
        return portal.pairInviteCode();
    }

    public boolean acceptPairLocally(Portal localPortal, String remoteServerId, String remotePortalId) {
        localPortal.setPairServerId(remoteServerId);
        localPortal.setPairPortalId(remotePortalId);
        localPortal.setStatus(PortalStatus.ACTIVE);
        localPortal.setPairInviteCode(null);
        db.savePortal(localPortal);
        PortalSigns.update(localPortal);
        return true;
    }

    public JsonObject handleRemoteHeartbeat(TrustedPeer peer, JsonObject body) {
        String remotePortalId = body.has("remotePortalId") ? body.get("remotePortalId").getAsString() : null;
        boolean intact = body.has("intact") && body.get("intact").getAsBoolean();
        JsonObject out = new JsonObject();
        if (remotePortalId == null) {
            out.addProperty("error", "missing portal");
            return out;
        }
        Optional<Portal> opt = db.findPortal(remotePortalId);
        if (opt.isEmpty()) {
            out.addProperty("error", "not found");
            return out;
        }
        Portal portal = opt.get();
        if (!peer.serverId().equals(portal.pairServerId())) {
            out.addProperty("error", "wrong peer");
            return out;
        }
        boolean localIntact = isLocalIntact(portal);
        if (!intact) {
            portal.setStatus(PortalStatus.BROKEN_REMOTE);
            db.savePortal(portal);
        } else if (localIntact && portal.status() != PortalStatus.DISABLED) {
            portal.setStatus(PortalStatus.ACTIVE);
            db.savePortal(portal);
        }
        out.addProperty("ok", true);
        out.addProperty("remoteIntact", localIntact);
        out.addProperty("status", portal.status().name());
        return out;
    }

    public JsonObject handlePairPropose(TrustedPeer peer, JsonObject body) {
        // Remote created a pair invite; we store pending link metadata for admin accept
        JsonObject out = new JsonObject();
        out.addProperty("ok", true);
        out.addProperty("serverId", config.serverId());
        out.addProperty("message", "Propose received. Use /mvp pair accept <code> at your portal sign.");
        if (body.has("inviteCode")) {
            out.addProperty("inviteCode", body.get("inviteCode").getAsString());
        }
        return out;
    }

    public JsonObject handlePairResolve(TrustedPeer peer, JsonObject body) {
        JsonObject out = new JsonObject();
        String invite = body.has("inviteCode") ? body.get("inviteCode").getAsString() : null;
        String acceptorPortalId = body.has("acceptorPortalId") ? body.get("acceptorPortalId").getAsString() : null;
        if (invite == null || acceptorPortalId == null) {
            out.addProperty("error", "missing fields");
            return out;
        }
        Optional<Portal> opt = db.findPortalByInvite(invite);
        if (opt.isEmpty()) {
            out.addProperty("error", "not found");
            return out;
        }
        Portal portal = opt.get();
        acceptPairLocally(portal, peer.serverId(), acceptorPortalId);
        out.addProperty("ok", true);
        out.addProperty("portalId", portal.id());
        out.addProperty("world", portal.frame().world());
        out.addProperty("x", portal.frame().x());
        out.addProperty("y", portal.frame().y());
        out.addProperty("z", portal.frame().z());
        return out;
    }

    public JsonObject handlePairAccept(TrustedPeer peer, JsonObject body) {
        JsonObject out = new JsonObject();
        String localPortalId = body.has("theirPortalId") ? body.get("theirPortalId").getAsString() : null;
        String remotePortalId = body.has("myPortalId") ? body.get("myPortalId").getAsString() : null;
        if (localPortalId == null || remotePortalId == null) {
            out.addProperty("error", "missing ids");
            return out;
        }
        Optional<Portal> opt = db.findPortal(localPortalId);
        if (opt.isEmpty()) {
            out.addProperty("error", "not found");
            return out;
        }
        Portal portal = opt.get();
        acceptPairLocally(portal, peer.serverId(), remotePortalId);
        out.addProperty("ok", true);
        out.addProperty("portalId", portal.id());
        out.addProperty("world", portal.frame().world());
        out.addProperty("x", portal.frame().x());
        out.addProperty("y", portal.frame().y());
        out.addProperty("z", portal.frame().z());
        out.addProperty("yaw", portal.frame().yaw());
        return out;
    }

    public Optional<TrustedPeer> pickMultiTarget(Portal portal, Player player) {
        List<TrustedPeer> pool = listMultiCandidates(portal, player);
        if (pool.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(pool.get(ThreadLocalRandom.current().nextInt(pool.size())));
    }

    /** Shuffled candidate list for probe retries (registry peers first, then scanner). */
    public List<TrustedPeer> listMultiCandidates(Portal portal, Player player) {
        List<TrustedPeer> pool = new ArrayList<>();
        List<RegistryServer> candidates = new ArrayList<>();

        boolean fixedPool = !portal.multiPool().isEmpty();

        if (registry.enabled() && !fixedPool) {
            candidates.addAll(registry.listMultiTargets(config.registryStaleMs()));
        } else if (registry.enabled() && fixedPool) {
            for (String id : portal.multiPool()) {
                registry.find(id).ifPresent(candidates::add);
            }
        }

        var vc = plugin.versionCompat();
        int clientProto = (vc != null && player != null) ? vc.playerProtocol(player)
                : (vc != null ? vc.nativeProtocol() : 0);

        java.util.Set<String> seen = new java.util.HashSet<>();
        for (RegistryServer rs : candidates) {
            if (config.isOwnPublicHost(rs.publicHost())
                    || (rs.serverId() != null && rs.serverId().equalsIgnoreCase(config.serverId()))) {
                continue;
            }
            if (!config.multiAllowPluginless() && !rs.hasPlugin()) {
                continue;
            }
            long age = System.currentTimeMillis() - rs.lastHeartbeat();
            if (rs.lastHeartbeat() > 0 && age > config.registryStaleMs()) {
                continue;
            }
            if (config.compatibilityEnabled() && clientProto > 0) {
                boolean ok = VersionCompat.canClientJoinDest(
                        clientProto,
                        rs.protocolId() > 0 ? rs.protocolId() : VersionCompat.protocolFromMcVersion(rs.mcVersion()),
                        rs.hasViaVersion(),
                        rs.hasViaBackwards(),
                        rs.hasViaRewind(),
                        rs.viaMin(),
                        rs.viaMax()
                );
                if (!ok) {
                    continue;
                }
            }
            seen.add(key(rs.publicHost(), rs.publicPort()));
            pool.add(rs.toTrustedPeer(config.signingSecret()));
        }

        // known_mvp hub/gossip club (after registry)
        if (!fixedPool) {
            String ourBranch = versionBranch(vc != null ? vc.mcVersion() : Bukkit.getMinecraftVersion());
            for (var known : db.listKnownMvp(Math.max(40, config.scannerVerifiedLimit()))) {
                if (known.serverId() != null && known.serverId().equalsIgnoreCase(config.serverId())) {
                    continue;
                }
                if (config.isOwnPublicHost(known.publicHost())) {
                    continue;
                }
                String branch = versionBranch(known.mcVersion());
                if (!ourBranch.isEmpty() && !branch.isEmpty() && !ourBranch.equals(branch)
                        && config.compatibilityEnabled() && clientProto > 0) {
                    int destProto = VersionCompat.protocolFromMcVersion(known.mcVersion());
                    if (destProto > 0
                            && !VersionCompat.canClientJoinDest(clientProto, destProto, false, false, false, 0, 0)) {
                        continue;
                    }
                }
                String k = key(known.publicHost(), known.publicPort());
                if (seen.add(k)) {
                    pool.add(known.toTrustedPeer(config.signingSecret()));
                }
            }
        }

        // Local catalog filled by scanners — pick by score; dead rows stay but are filtered
        if (!fixedPool && config.scannerEnabled()) {
            String ourVer = vc != null ? vc.mcVersion() : Bukkit.getMinecraftVersion();
            String ourBranch = versionBranch(ourVer);
            boolean bedrock = player != null && BedrockPlayers.isBedrock(player);
            int bedProto = bedrock ? BedrockPlayers.protocolVersion(player).orElse(0) : 0;
            String bedVer = bedrock ? BedrockPlayers.versionString(player) : "";
            int limit = Math.max(40, config.scannerVerifiedLimit());
            List<TrustedPeer> scored = new ArrayList<>();
            for (var entry : db.listScoredCandidates(ourBranch, limit, config.scannerDeadTtlMs())) {
                if (config.isOwnPublicHost(entry.host())) {
                    continue;
                }
                if (bedrock && config.scannerRequireGeyserForBedrock()) {
                    // Known OK without Geyser — skip; SEEN/unknown still worth probing
                    if (entry.status() == Database.ProbeStatus.OK && entry.bedrockPort() <= 0) {
                        continue;
                    }
                    if (entry.status() == Database.ProbeStatus.NO_GEYSER) {
                        continue;
                    }
                    if (entry.bedrockPort() > 0 && config.scannerRequireBedrockProtocolMatch()
                            && bedProto > 0 && entry.bedrockProtocol() > 0
                            && !BedrockPlayers.canJoinDest(
                            bedProto, entry.bedrockProtocol(),
                            bedVer, entry.bedrockVersion(),
                            config.scannerRequireBedrockVersionMatch())) {
                        continue;
                    }
                }
                if (config.compatibilityEnabled() && clientProto > 0 && entry.mcVersion() != null) {
                    String branch = versionBranch(entry.mcVersion());
                    if (!ourBranch.isEmpty() && !branch.isEmpty() && !ourBranch.equals(branch)) {
                        int destProto = VersionCompat.protocolFromMcVersion(entry.mcVersion());
                        if (destProto > 0
                                && !VersionCompat.canClientJoinDest(clientProto, destProto, false, false, false, 0, 0)) {
                            continue;
                        }
                    }
                }
                String k = key(entry.host(), entry.javaPort());
                if (seen.add(k)) {
                    scored.add(entry.toPeer());
                }
            }
            // Weighted: keep score order in first half, shuffle lightly for variety
            pool.addAll(scored);
        }

        if (pool.isEmpty() && !config.compatibilityEnabled()) {
            List<TrustedPeer> peers = db.listPeers().stream()
                    .filter(p -> !config.isOwnPublicHost(p.publicHost())
                            && (p.serverId() == null || !p.serverId().equalsIgnoreCase(config.serverId())))
                    .toList();
            if (!fixedPool) {
                pool.addAll(config.multiAllowPluginless()
                        ? peers
                        : peers.stream().filter(TrustedPeer::hasPlugin).toList());
            } else {
                pool.addAll(peers.stream().filter(p -> portal.multiPool().contains(p.serverId())).toList());
            }
        }

        // Soft shuffle of top candidates so we don't always hit #1
        if (pool.size() > 3) {
            int top = Math.min(12, pool.size());
            List<TrustedPeer> head = new ArrayList<>(pool.subList(0, top));
            List<TrustedPeer> tail = new ArrayList<>(pool.subList(top, pool.size()));
            Collections.shuffle(head, ThreadLocalRandom.current());
            pool.clear();
            pool.addAll(head);
            pool.addAll(tail);
        } else {
            Collections.shuffle(pool, ThreadLocalRandom.current());
        }
        return pool;
    }

    private static String key(String host, int port) {
        return (host == null ? "" : host.toLowerCase(java.util.Locale.ROOT)) + ":" + port;
    }

    /** True if destination can send the player back (MVP on both sides / pair). */
    public boolean isReturnCapable(PortalType type, TrustedPeer peer) {
        if (type == PortalType.PAIR) {
            return true;
        }
        return peer != null && peer.hasPlugin();
    }

    /** Check if a registry target is joinable for this player. */
    public boolean isCompatible(Player player, RegistryServer rs) {
        if (!config.compatibilityEnabled()) {
            return true;
        }
        // Stale heartbeat = treat as unavailable
        long age = System.currentTimeMillis() - rs.lastHeartbeat();
        if (rs.lastHeartbeat() > 0 && age > config.registryStaleMs()) {
            return false;
        }
        var vc = plugin.versionCompat();
        if (vc == null) {
            return true;
        }
        int destProto = rs.protocolId() > 0 ? rs.protocolId() : VersionCompat.protocolFromMcVersion(rs.mcVersion());
        return VersionCompat.canClientJoinDest(
                vc.playerProtocol(player),
                destProto,
                rs.hasViaVersion(),
                rs.hasViaBackwards(),
                rs.hasViaRewind(),
                rs.viaMin(),
                rs.viaMax()
        );
    }

    public List<Portal> list() {
        return db.listPortals();
    }

    public Optional<Portal> get(String id) {
        return db.findPortal(id);
    }

    public void delete(String id) {
        db.findPortal(id).ifPresent(p -> {
            if (p.type() == PortalType.PAIR && p.pairServerId() != null) {
                notifyPairBroken(p);
            }
            if (plugin.portalBindService() != null) {
                plugin.portalBindService().cancel(id);
            }
            if (plugin.portalMatter() != null) {
                plugin.portalMatter().remove(id);
            }
            db.deletePortal(id);
            publishPortalGraphAsync();
        });
    }

    private static String randomCode() {
        final String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        StringBuilder sb = new StringBuilder(8);
        for (int i = 0; i < 8; i++) {
            sb.append(alphabet.charAt(ThreadLocalRandom.current().nextInt(alphabet.length())));
        }
        return sb.toString();
    }

    /** "1.21.10" → "1.21" */
    private static String versionBranch(String ver) {
        if (ver == null || ver.isBlank()) {
            return "";
        }
        String v = ver.trim();
        int a = v.indexOf('.');
        if (a < 0) {
            return v;
        }
        int b = v.indexOf('.', a + 1);
        return b < 0 ? v : v.substring(0, b);
    }
}
