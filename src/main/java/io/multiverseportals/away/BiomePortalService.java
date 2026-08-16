package io.multiverseportals.away;

import io.multiverseportals.MultiversePortalsPlugin;
import io.multiverseportals.config.PluginConfig;
import io.multiverseportals.db.Database;
import io.multiverseportals.model.Portal;
import io.multiverseportals.model.PortalFrame;
import io.multiverseportals.model.PortalStatus;
import io.multiverseportals.model.PortalType;
import io.multiverseportals.portal.PortalSigns;
import io.multiverseportals.util.ShapeHasher;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.BiomeSearchResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Nether-like sticky pairing of Away portals across overworld biomes.
 */
public final class BiomePortalService {

    private final MultiversePortalsPlugin plugin;
    private final Database db;
    private final PluginConfig config;
    private final MiniMessage mm = MiniMessage.miniMessage();
    private final Map<UUID, Long> entityCooldown = new ConcurrentHashMap<>();

    public BiomePortalService(MultiversePortalsPlugin plugin, Database db, PluginConfig config) {
        this.plugin = plugin;
        this.db = db;
        this.config = config;
    }

    public void startBind(Portal portal, Player player) {
        if (portal == null || portal.type() != PortalType.AWAY) {
            return;
        }
        World world = Bukkit.getWorld(portal.frame().world());
        if (world == null) {
            return;
        }
        Location origin = portal.frame().toLocation(world);
        Biome here = origin.getBlock().getBiome();
        String originKey = BiomeFrames.keyOf(here);
        portal.setAwayOriginBiome(originKey);
        db.savePortal(portal);

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                bindAsync(portal.id(), origin, originKey, player != null ? player.getUniqueId() : null);
            } catch (Exception e) {
                plugin.getLogger().warning("Away bind failed: " + e.getMessage());
                Bukkit.getScheduler().runTask(plugin, () -> failBind(portal.id(), player != null ? player.getUniqueId() : null));
            }
        });
    }

    private void bindAsync(String portalId, Location origin, String originKey, UUID notify) {
        Portal live = db.findPortal(portalId).orElse(null);
        if (live == null) {
            return;
        }
        String destKey = live.awayDestBiome();
        if (destKey == null || destKey.isBlank()) {
            destKey = pickDestBiome(origin.getWorld(), origin, originKey);
            if (destKey == null) {
                Bukkit.getScheduler().runTask(plugin, () -> failBind(portalId, notify));
                return;
            }
        }

        final String destBiomeKey = destKey;
        Bukkit.getScheduler().runTask(plugin, () -> {
            Portal portal = db.findPortal(portalId).orElse(null);
            if (portal == null) {
                return;
            }
            portal.setAwayDestBiome(destBiomeKey);
            db.savePortal(portal);

            Portal existing = findLinkedInBiome(origin.getWorld(), destBiomeKey, originKey, portalId);
            if (existing != null && exitIntact(existing)) {
                pair(portal, existing);
                notifyBound(notify, destBiomeKey);
                return;
            }

            if (ensureExit(portal)) {
                notifyBound(notify, destBiomeKey);
                return;
            }

            if (!config.awayAutoBuildReturn()) {
                portal.setStatus(PortalStatus.ACTIVE);
                db.savePortal(portal);
                PortalSigns.update(portal);
                if (plugin.portalMatter() != null) {
                    plugin.portalMatter().refresh(portal);
                }
                notifyBound(notify, destBiomeKey);
                return;
            }

            Location hint = locateBiome(origin, destBiomeKey);
            if (hint == null) {
                portal.setStatus(PortalStatus.ACTIVE);
                db.savePortal(portal);
                PortalSigns.update(portal);
                notifyBound(notify, destBiomeKey);
                plugin.getLogger().info("Away " + portalId + " → " + destBiomeKey + " (no locate, no auto-frame)");
                return;
            }

            Location edge = hint.clone();
            if (config.awayPreferCenter()) {
                hint = BiomeInterior.towardCenter(
                        origin.getWorld(), origin, hint, destBiomeKey,
                        config.awayCenterStep(), config.awayCenterMaxRadius());
                plugin.getLogger().info("Away " + portalId + " locate " + destBiomeKey
                        + " edge=" + edge.getBlockX() + "," + edge.getBlockZ()
                        + " center=" + hint.getBlockX() + "," + hint.getBlockZ());
            }

            Location spot = AwayFrameBuilder.findSpot(
                    origin.getWorld(), hint, origin, config.awayMinDistance(), destBiomeKey);
            if (spot == null) {
                portal.setStatus(PortalStatus.ACTIVE);
                db.savePortal(portal);
                PortalSigns.update(portal);
                notifyBound(notify, destBiomeKey);
                return;
            }

            Material mat = BiomeFrames.materialFor(originKey);
            BlockFace face = AwayFrameBuilder.facingFromYaw(portal.frame().yaw());
            AwayFrameBuilder.Built built = AwayFrameBuilder.build(origin.getWorld(), spot, face, mat);
            if (built == null || built.sign() == null) {
                portal.setStatus(PortalStatus.ACTIVE);
                db.savePortal(portal);
                PortalSigns.update(portal);
                notifyBound(notify, destBiomeKey);
                return;
            }

            String hash = ShapeHasher.hashAround(built.sign().getLocation());
            PortalFrame frame = PortalFrame.from(built.signLoc(), hash);
            Portal dest = new Portal(
                    UUID.randomUUID().toString(),
                    PortalType.AWAY,
                    PortalStatus.ACTIVE,
                    frame,
                    "away",
                    portal.creator()
            );
            dest.setAwayOriginBiome(originKey);
            dest.setAwayDestBiome(originKey);
            dest.setAwayDestPortalId(portal.id());
            db.savePortal(dest);
            pair(portal, dest);
            if (plugin.portalMatter() != null) {
                plugin.portalMatter().refresh(dest);
                plugin.portalMatter().refresh(portal);
            }
            notifyBound(notify, destBiomeKey);
            plugin.getLogger().info("Away auto-frame " + dest.id() + " in " + destBiomeKey
                    + " from " + originKey);
        });
    }

    private void pair(Portal a, Portal b) {
        a.setAwayDestPortalId(b.id());
        if (a.awayDestBiome() == null || a.awayDestBiome().isBlank()) {
            World w = Bukkit.getWorld(b.frame().world());
            if (w != null) {
                a.setAwayDestBiome(BiomeFrames.keyOf(w.getBlockAt(b.frame().x(), b.frame().y(), b.frame().z()).getBiome()));
            }
        }
        b.setAwayDestPortalId(a.id());
        World wa = Bukkit.getWorld(a.frame().world());
        if (wa != null) {
            b.setAwayDestBiome(BiomeFrames.keyOf(wa.getBlockAt(a.frame().x(), a.frame().y(), a.frame().z()).getBiome()));
        }
        a.setStatus(PortalStatus.ACTIVE);
        b.setStatus(PortalStatus.ACTIVE);
        a.setAwayExit(b.frame());
        b.setAwayExit(a.frame());
        db.savePortal(a);
        db.savePortal(b);
        PortalSigns.update(a);
        PortalSigns.update(b);
        if (plugin.portalService() != null) {
            plugin.portalService().checkFrameNow(a);
            plugin.portalService().checkFrameNow(b);
        }
        if (plugin.portalMatter() != null) {
            plugin.portalMatter().refresh(a);
            plugin.portalMatter().refresh(b);
        }
    }

    private Portal findLinkedInBiome(World world, String destBiomeKey, String originBiomeKey, String skipId) {
        Portal best = null;
        long bestAge = Long.MAX_VALUE;
        for (Portal p : db.listPortals()) {
            if (p.type() != PortalType.AWAY || p.id().equals(skipId)) {
                continue;
            }
            if (!world.getName().equals(p.frame().world())) {
                continue;
            }
            Block sign = world.getBlockAt(p.frame().x(), p.frame().y(), p.frame().z());
            String here = BiomeFrames.keyOf(sign.getBiome());
            boolean inDest = destBiomeKey.equalsIgnoreCase(here);
            boolean linked = originBiomeKey.equalsIgnoreCase(p.awayDestBiome())
                    || originBiomeKey.equalsIgnoreCase(p.awayOriginBiome());
            if (inDest && linked && exitIntact(p)) {
                long age = p.id().hashCode() & 0xffffffffL;
                if (age < bestAge) {
                    bestAge = age;
                    best = p;
                }
            }
        }
        return best;
    }

    private String pickDestBiome(World world, Location origin, String originKey) {
        List<Biome> pool = new ArrayList<>();
        try {
            for (Biome b : Registry.BIOME) {
                String k = BiomeFrames.keyOf(b);
                if (BiomeFrames.isNetherOrEnd(k) || k.equalsIgnoreCase(originKey)) {
                    continue;
                }
                pool.add(b);
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("Biome registry read: " + t.getMessage());
        }
        if (pool.isEmpty()) {
            return null;
        }
        Collections.shuffle(pool, ThreadLocalRandom.current());
        int tries = Math.min(12, pool.size());
        for (int i = 0; i < tries; i++) {
            Biome cand = pool.get(i);
            Location found = locateBiome(origin, BiomeFrames.keyOf(cand));
            if (found != null) {
                if (origin.distanceSquared(found) < (long) config.awayMinDistance() * config.awayMinDistance()
                        && !BiomeFrames.keyOf(cand).equalsIgnoreCase(originKey)) {
                    // too close but different biome — still accept if locate worked far enough on retry
                    continue;
                }
                return BiomeFrames.keyOf(cand);
            }
        }
        return BiomeFrames.keyOf(pool.getFirst());
    }

    private Location locateBiome(Location origin, String biomeKey) {
        World world = origin.getWorld();
        if (world == null || biomeKey == null) {
            return null;
        }
        Biome biome = Registry.BIOME.get(NamespacedKey.minecraft(biomeKey));
        if (biome == null) {
            return null;
        }
        int radius = config.awayLocateRadius();
        try {
            BiomeSearchResult result = world.locateNearestBiome(origin, radius, 32, 64, biome);
            if (result != null && result.getLocation() != null) {
                return result.getLocation();
            }
        } catch (Throwable t) {
            try {
                @SuppressWarnings("deprecation")
                Location loc = world.locateNearestBiome(origin, biome, radius);
                return loc;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    public Location arrivalLocation(Portal dest) {
        if (dest == null || dest.frame() == null) {
            return null;
        }
        World world = Bukkit.getWorld(dest.frame().world());
        if (world == null) {
            return null;
        }
        Location plate = findPlate(dest, world);
        BlockFace face = io.multiverseportals.portal.FrameDetector.facingOf(
                world.getBlockAt(dest.frame().x(), dest.frame().y(), dest.frame().z()));
        if (plate != null) {
            Location stand = plate.clone().add(0, 0.05, 0);
            stand.add(face.getModX() * 1.4, 0, face.getModZ() * 1.4);
            stand.setYaw(AwayFrameBuilder.yawOf(face));
            return stand;
        }
        if (plugin.portalService() != null) {
            var scan = plugin.portalService().scanOf(dest);
            if (scan.closed() && !scan.interior().isEmpty()) {
                org.bukkit.Location lowest = scan.interior().get(0);
                for (org.bukkit.Location c : scan.interior()) {
                    if (c.getY() < lowest.getY()) {
                        lowest = c;
                    }
                }
                Location stand = lowest.clone().add(0.5, 0.05, 0.5);
                stand.add(face.getModX() * 1.5, 0, face.getModZ() * 1.5);
                stand.setYaw(AwayFrameBuilder.yawOf(face));
                return stand;
            }
        }
        Location loc = dest.frame().toLocation(world);
        loc.add(0.5, -1.5, 0.5);
        loc.add(face.getModX() * 1.5, 0, face.getModZ() * 1.5);
        loc.setYaw(AwayFrameBuilder.yawOf(face));
        return loc;
    }

    private Location findPlate(Portal portal, World world) {
        var f = portal.frame();
        int[] dys = {-2, -1, -3, 0, 1};
        for (int dy : dys) {
            Block b = world.getBlockAt(f.x(), f.y() + dy, f.z());
            if (ShapeHasher.isPressurePlate(b.getType())) {
                return new Location(world, f.x() + 0.5, f.y() + dy + 0.05, f.z() + 0.5);
            }
        }
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy : dys) {
                    Block b = world.getBlockAt(f.x() + dx, f.y() + dy, f.z() + dz);
                    if (ShapeHasher.isPressurePlate(b.getType())) {
                        return new Location(world, b.getX() + 0.5, b.getY() + 0.05, b.getZ() + 0.5);
                    }
                }
            }
        }
        return null;
    }

    /**
     * Teleport through an Away portal. Rebuilds dest frame at the same spot if it was broken.
     */
    public void travel(Player player, Portal portal) {
        if (!portal.hasAwayDestination()) {
            startBind(portal, player);
            player.sendMessage(mm.deserialize(config.prefix(player) + config.message(player, "away-searching")));
            return;
        }
        Portal live = db.findPortal(portal.id()).orElse(portal);
        Portal dest = live.awayDestPortalId() != null
                ? db.findPortal(live.awayDestPortalId()).orElse(null) : null;
        if (dest == null || !exitIntact(dest)) {
            player.sendMessage(mm.deserialize(config.prefix(player) + config.message(player, "away-rebuilding")));
            if (!ensureExit(live)) {
                startBind(live, player);
                return;
            }
            live = db.findPortal(portal.id()).orElse(live);
            dest = live.awayDestPortalId() != null
                    ? db.findPortal(live.awayDestPortalId()).orElse(null) : null;
        }
        if (dest == null) {
            player.sendMessage(mm.deserialize(config.prefix(player) + config.message(player, "away-no-landing")));
            return;
        }
        Location to = arrivalLocation(dest);
        if (to == null) {
            player.sendMessage(mm.deserialize(config.prefix(player) + config.message(player, "away-no-landing")));
            return;
        }
        String label = BiomeColors.prettyName(live.awayDestBiome());
        player.teleportAsync(to).thenAccept(ok -> {
            if (Boolean.TRUE.equals(ok)) {
                player.sendMessage(mm.deserialize(config.prefix(player) + config.message(player, "away-arrived")
                        .replace("%biome%", label)));
            }
        });
    }

    /** Same-world pass-through for villagers, zombies, Citizens mobs, … */
    public void travelEntity(LivingEntity entity, Portal portal) {
        if (entity == null || entity instanceof Player || portal == null) {
            return;
        }
        if (!portal.hasAwayDestination()) {
            return;
        }
        long now = System.currentTimeMillis();
        Long last = entityCooldown.get(entity.getUniqueId());
        if (last != null && now - last < 3000) {
            return;
        }
        Portal dest = null;
        if (portal.awayDestPortalId() != null) {
            dest = db.findPortal(portal.awayDestPortalId()).orElse(null);
        }
        if (dest == null || !exitIntact(dest)) {
            Portal live = db.findPortal(portal.id()).orElse(portal);
            if (!ensureExit(live)) {
                return;
            }
            live = db.findPortal(portal.id()).orElse(live);
            dest = live.awayDestPortalId() != null
                    ? db.findPortal(live.awayDestPortalId()).orElse(null) : null;
        }
        if (dest == null) {
            return;
        }
        Location to = arrivalLocation(dest);
        if (to == null) {
            return;
        }
        entityCooldown.put(entity.getUniqueId(), now);
        entity.teleportAsync(to);
    }

    /**
     * Keep the dest <em>spot</em> when the exit portal row is deleted (sign broken).
     */
    public void onPortalDeleted(Portal gone) {
        if (gone == null || gone.type() != PortalType.AWAY) {
            return;
        }
        if (gone.awayDestPortalId() != null) {
            db.findPortal(gone.awayDestPortalId()).ifPresent(partner -> {
                partner.setAwayExit(gone.frame());
                db.savePortal(partner);
            });
        }
        for (Portal p : db.listPortals()) {
            if (p.type() != PortalType.AWAY || p.id().equals(gone.id())) {
                continue;
            }
            if (gone.id().equals(p.awayDestPortalId())) {
                p.setAwayExit(gone.frame());
                db.savePortal(p);
            }
        }
    }

    /** Closed ring + control sign still there. */
    private boolean exitIntact(Portal dest) {
        if (dest == null) {
            return false;
        }
        if (plugin.portalService() != null) {
            return plugin.portalService().isLocalIntact(dest);
        }
        return dest.status() == PortalStatus.ACTIVE;
    }

    /**
     * Rebuild the paired exit at the last known sign coordinates. Returns false if we never
     * stored a spot (first bind still has to locate a biome).
     */
    boolean ensureExit(Portal origin) {
        if (origin == null || !origin.hasAwayDestination()) {
            return false;
        }
        Portal dest = origin.awayDestPortalId() != null
                ? db.findPortal(origin.awayDestPortalId()).orElse(null) : null;
        if (exitIntact(dest)) {
            if (!origin.hasAwayExit()) {
                origin.setAwayExit(dest.frame());
                db.savePortal(origin);
            }
            return true;
        }
        PortalFrame anchor = dest != null ? dest.frame() : origin.awayExit();
        if (anchor == null) {
            return false;
        }
        World world = Bukkit.getWorld(anchor.world());
        if (world == null) {
            return false;
        }
        String originKey = origin.awayOriginBiome();
        if (originKey == null || originKey.isBlank()) {
            World ow = Bukkit.getWorld(origin.frame().world());
            if (ow != null) {
                originKey = BiomeFrames.keyOf(ow.getBlockAt(
                        origin.frame().x(), origin.frame().y(), origin.frame().z()).getBiome());
            } else {
                originKey = "plains";
            }
        }
        Material mat = BiomeFrames.materialFor(originKey);
        BlockFace face = originFace(origin);
        AwayFrameBuilder.Built built = AwayFrameBuilder.rebuildAtSign(
                world, anchor.x(), anchor.y(), anchor.z(), face, mat);
        if (built == null || built.sign() == null) {
            return false;
        }
        String hash = ShapeHasher.hashAround(built.sign().getLocation());
        PortalFrame frame = PortalFrame.from(built.signLoc(), hash);
        if (dest == null) {
            dest = new Portal(
                    UUID.randomUUID().toString(),
                    PortalType.AWAY,
                    PortalStatus.ACTIVE,
                    frame,
                    "away",
                    origin.creator()
            );
            dest.setAwayOriginBiome(originKey);
            dest.setAwayDestBiome(originKey);
            dest.setAwayDestPortalId(origin.id());
            db.savePortal(dest);
        } else {
            dest.setFrame(frame);
            dest.setStatus(PortalStatus.ACTIVE);
            db.savePortal(dest);
        }
        pair(origin, dest);
        plugin.getLogger().info("Away rebuilt exit " + dest.id() + " at "
                + anchor.world() + " " + anchor.x() + "," + anchor.y() + "," + anchor.z());
        return true;
    }

    private BlockFace originFace(Portal origin) {
        World w = Bukkit.getWorld(origin.frame().world());
        if (w != null) {
            Block sign = w.getBlockAt(origin.frame().x(), origin.frame().y(), origin.frame().z());
            BlockFace f = io.multiverseportals.portal.FrameDetector.facingOf(sign);
            if (f == BlockFace.EAST || f == BlockFace.WEST
                    || f == BlockFace.NORTH || f == BlockFace.SOUTH) {
                return f;
            }
        }
        return AwayFrameBuilder.facingFromYaw(origin.frame().yaw());
    }

    public void resumePending() {
        for (Portal p : db.listPortals()) {
            if (p.type() != PortalType.AWAY) {
                continue;
            }
            if (p.status() == PortalStatus.BINDING
                    || (p.status() == PortalStatus.ACTIVE && !p.hasAwayDestination())) {
                startBind(p, Bukkit.getPlayer(p.creator()));
            }
        }
    }

    private void failBind(String portalId, UUID notify) {
        db.findPortal(portalId).ifPresent(p -> {
            p.setStatus(PortalStatus.BIND_FAILED);
            db.savePortal(p);
            PortalSigns.update(p);
        });
        Player pl = notify != null ? Bukkit.getPlayer(notify) : null;
        if (pl != null && pl.isOnline()) {
            pl.sendMessage(mm.deserialize(config.prefix(pl) + config.message(pl, "away-bind-failed")));
        }
    }

    private void notifyBound(UUID notify, String destBiomeKey) {
        Player pl = notify != null ? Bukkit.getPlayer(notify) : null;
        if (pl != null && pl.isOnline()) {
            pl.sendMessage(mm.deserialize(config.prefix(pl) + config.message(pl, "away-bound")
                    .replace("%biome%", BiomeColors.prettyName(destBiomeKey))));
        }
    }
}
