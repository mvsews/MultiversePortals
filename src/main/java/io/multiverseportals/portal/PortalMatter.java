package io.multiverseportals.portal;

import io.multiverseportals.MultiversePortalsPlugin;
import io.multiverseportals.config.PluginConfig;
import io.multiverseportals.local.WoolFrame;
import io.multiverseportals.model.Portal;
import io.multiverseportals.model.PortalType;
import io.multiverseportals.util.ShapeHasher;
import org.bukkit.Axis;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Orientable;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import io.papermc.paper.event.entity.EntityInsideBlockEvent;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPortalEnterEvent;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.PortalCreateEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Fills a closed portal opening with vanilla nether-portal blocks (animated purple sheet).
 * Physics and Nether transfer are cancelled so any frame shape can hold the texture.
 */
public final class PortalMatter implements Listener {

    public static final String TAG_PREFIX = "mvp_matter_";

    private final MultiversePortalsPlugin plugin;
    private final PluginConfig config;
    private final org.bukkit.NamespacedKey keyPortalId;
    /** portalId → packed cell keys (world|x|y|z). */
    private final Map<String, Set<String>> cellsByPortal = new HashMap<>();
    private final Map<String, String> portalByCell = new HashMap<>();

    public PortalMatter(MultiversePortalsPlugin plugin, PluginConfig config) {
        this.plugin = plugin;
        this.config = config;
        this.keyPortalId = new org.bukkit.NamespacedKey(plugin, "portal_id");
    }

    public void refresh(Portal portal) {
        if (!config.matterEnabled()) {
            return;
        }
        remove(portal.id());
        if (portal.status() == io.multiverseportals.model.PortalStatus.BROKEN_LOCAL
                || portal.status() == io.multiverseportals.model.PortalStatus.BROKEN_REMOTE
                || portal.status() == io.multiverseportals.model.PortalStatus.DISABLED
                || portal.status() == io.multiverseportals.model.PortalStatus.BINDING
                || portal.status() == io.multiverseportals.model.PortalStatus.BIND_FAILED
                || portal.status() == io.multiverseportals.model.PortalStatus.PENDING_PAIR) {
            return;
        }
        if (portal.type() == PortalType.MULTI && !portal.hasBoundDestination()) {
            return;
        }
        if (portal.type() == PortalType.AWAY && !portal.hasAwayDestination()) {
            return;
        }
        World world = Bukkit.getWorld(portal.frame().world());
        if (world == null) {
            return;
        }
        Block sign = world.getBlockAt(portal.frame().x(), portal.frame().y(), portal.frame().z());
        FrameDetector.Scan scan = FrameDetector.scan(sign, config.maxFrameRadius(), config.maxInterior());
        if (!scan.closed() || scan.interior().isEmpty()) {
            return;
        }
        paintCells(portal.id(), world, scan.interior(), scan.axis());
    }

    /** Fill an opening (network or local wool) with the purple sheet. */
    public void fillOpening(String portalId, World world, List<Location> cells, Axis axis) {
        if (!config.matterEnabled() || portalId == null || world == null || cells == null || cells.isEmpty()) {
            return;
        }
        remove(portalId);
        paintCells(portalId, world, cells, axis);
    }

    /**
     * Real nether-portal blocks only survive vanilla 21×21 rectangles. Giant rings
     * (and anything the Nether would pop) use BlockDisplay so the sheet stays.
     */
    private void paintCells(String portalId, World world, List<Location> cells, Axis axis) {
        clearStrayNetherPortal(world, cells, axis, portalId);
        Material look = matterMaterial();
        boolean realNether = look == Material.NETHER_PORTAL && fitsVanillaNetherSheet(cells);
        for (Location cell : cells) {
            Block block = world.getBlockAt(cell.getBlockX(), cell.getBlockY(), cell.getBlockZ());
            String occupied = portalByCell.get(cellKey(block));
            if (occupied != null && !occupied.equals(portalId)) {
                continue;
            }
            if (ShapeHasher.isPressurePlate(block.getType())) {
                block.setType(Material.AIR, false);
            }
            if (realNether) {
                placeNetherSheet(world, cell, axis, portalId);
            } else {
                spawnMatterBlock(world, cell, look, axis, portalId);
            }
        }
    }

    /**
     * Drop leftover nether-portal blocks from a previous over-fill (cave behind the
     * jamb) so a small doorway does not keep a giant purple wall beside it.
     */
    private void clearStrayNetherPortal(World world, List<Location> keep, Axis axis, String portalId) {
        if (world == null || keep == null || keep.isEmpty()) {
            return;
        }
        Set<String> keepKeys = new HashSet<>();
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (Location c : keep) {
            keepKeys.add(world.getName() + "|" + c.getBlockX() + "|" + c.getBlockY() + "|" + c.getBlockZ());
            minX = Math.min(minX, c.getBlockX());
            maxX = Math.max(maxX, c.getBlockX());
            minY = Math.min(minY, c.getBlockY());
            maxY = Math.max(maxY, c.getBlockY());
            minZ = Math.min(minZ, c.getBlockZ());
            maxZ = Math.max(maxZ, c.getBlockZ());
        }
        int pad = 1;
        int x0 = minX - pad, x1 = maxX + pad;
        int y0 = minY - pad, y1 = maxY + pad;
        int z0 = minZ - pad, z1 = maxZ + pad;
        if (axis == Axis.X) {
            z0 = minZ;
            z1 = maxZ;
        } else {
            x0 = minX;
            x1 = maxX;
        }
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    Block block = world.getBlockAt(x, y, z);
                    if (block.getType() != Material.NETHER_PORTAL) {
                        continue;
                    }
                    String key = cellKey(block);
                    if (keepKeys.contains(key)) {
                        continue;
                    }
                    String occupied = portalByCell.get(key);
                    if (occupied != null && !occupied.equals(portalId)) {
                        continue;
                    }
                    block.setType(Material.AIR, false);
                    if (portalId.equals(portalByCell.get(key))) {
                        portalByCell.remove(key);
                    }
                }
            }
        }
    }

    public boolean hasSheet(String portalId) {
        Set<String> cells = cellsByPortal.get(portalId);
        return cells != null && !cells.isEmpty();
    }

    /**
     * When the sign's chunk is loaded but matter was never painted (Nether often
     * starts unloaded), fill the closed opening.
     */
    public void ensureSheet(Portal portal) {
        if (!config.matterEnabled() || portal == null || hasSheet(portal.id())) {
            return;
        }
        if (portal.status() == io.multiverseportals.model.PortalStatus.BROKEN_LOCAL
                || portal.status() == io.multiverseportals.model.PortalStatus.BROKEN_REMOTE
                || portal.status() == io.multiverseportals.model.PortalStatus.DISABLED
                || portal.status() == io.multiverseportals.model.PortalStatus.BINDING
                || portal.status() == io.multiverseportals.model.PortalStatus.BIND_FAILED
                || portal.status() == io.multiverseportals.model.PortalStatus.PENDING_PAIR) {
            return;
        }
        if (portal.type() == PortalType.MULTI && !portal.hasBoundDestination()) {
            return;
        }
        if (portal.type() == PortalType.AWAY && !portal.hasAwayDestination()) {
            return;
        }
        World world = Bukkit.getWorld(portal.frame().world());
        if (world == null || !world.isChunkLoaded(portal.frame().x() >> 4, portal.frame().z() >> 4)) {
            return;
        }
        Block sign = world.getBlockAt(portal.frame().x(), portal.frame().y(), portal.frame().z());
        FrameDetector.Scan scan = FrameDetector.scan(sign, config.maxFrameRadius(), config.maxInterior());
        if (!scan.closed() || scan.interior().isEmpty()) {
            return;
        }
        fillOpening(portal.id(), world, scan.interior(), scan.axis());
    }

    public void remove(String portalId) {
        Set<String> cells = cellsByPortal.remove(portalId);
        if (cells != null) {
            for (String key : cells) {
                if (!portalId.equals(portalByCell.get(key))) {
                    continue;
                }
                portalByCell.remove(key);
                Block b = blockFromKey(key);
                if (b != null && b.getType() == Material.NETHER_PORTAL) {
                    b.setType(Material.AIR, false);
                }
            }
        }
        String tag = TAG_PREFIX + portalId;
        for (World world : Bukkit.getWorlds()) {
            for (Entity e : world.getEntitiesByClass(BlockDisplay.class)) {
                if (e.getScoreboardTags().contains(tag)
                        || portalId.equals(e.getPersistentDataContainer().get(keyPortalId, PersistentDataType.STRING))) {
                    e.remove();
                }
            }
        }
    }

    public void removeAll() {
        for (String id : new ArrayList<>(cellsByPortal.keySet())) {
            remove(id);
        }
        cellsByPortal.clear();
        portalByCell.clear();
        for (World world : Bukkit.getWorlds()) {
            for (Entity e : world.getEntitiesByClass(BlockDisplay.class)) {
                for (String tag : e.getScoreboardTags()) {
                    if (tag.startsWith(TAG_PREFIX)) {
                        e.remove();
                        break;
                    }
                }
            }
        }
    }

    public void refreshAll(Iterable<Portal> portals) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            for (Portal p : portals) {
                World w = Bukkit.getWorld(p.frame().world());
                if (w != null) {
                    w.getChunkAt(p.frame().x() >> 4, p.frame().z() >> 4);
                }
                refresh(p);
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChunkLoad(ChunkLoadEvent event) {
        World world = event.getWorld();
        int cx = event.getChunk().getX();
        int cz = event.getChunk().getZ();
        for (Portal p : plugin.database().listPortals()) {
            if (!world.getName().equals(p.frame().world())) {
                continue;
            }
            if ((p.frame().x() >> 4) != cx || (p.frame().z() >> 4) != cz) {
                continue;
            }
            ensureSheet(p);
        }
    }

    public boolean isMatterBlock(Block block) {
        return block != null && portalByCell.containsKey(cellKey(block));
    }

    /** True if this location is standing in / next to our fake Nether sheet. */
    public boolean inOurSheet(Location loc) {
        return nearOurPortal(loc);
    }

    public Location standInFront(Location loc) {
        if (loc == null || loc.getWorld() == null || !nearOurPortal(loc)) {
            return null;
        }
        World world = loc.getWorld();
        String id = portalIdNear(loc);
        BlockFace face = BlockFace.SOUTH;
        if (id != null) {
            var portal = plugin.database().findPortal(id).orElse(null);
            if (portal != null) {
                Block sign = world.getBlockAt(portal.frame().x(), portal.frame().y(), portal.frame().z());
                face = FrameDetector.facingOf(sign);
                Location stand = new Location(
                        world,
                        sign.getX() + 0.5 + face.getModX() * 1.2,
                        loc.getY(),
                        sign.getZ() + 0.5 + face.getModZ() * 1.2,
                        io.multiverseportals.away.AwayFrameBuilder.yawOf(face),
                        loc.getPitch()
                );
                if (stand.getBlock().getType() == Material.NETHER_PORTAL
                        || stand.clone().add(0, 1, 0).getBlock().getType() == Material.NETHER_PORTAL) {
                    stand.add(face.getModX(), 0, face.getModZ());
                }
                return stand;
            }
            var locals = plugin.localPortalService();
            if (locals != null) {
                var local = locals.findById(id).orElse(null);
                if (local != null) {
                    Block sign = world.getBlockAt(local.x(), local.y(), local.z());
                    return WoolFrame.arrivalLocation(sign, plugin.pluginConfig().maxFrameRadius(),
                            plugin.pluginConfig().maxInterior());
                }
            }
        }
        Block sheet = loc.getBlock();
        if (sheet.getType() != Material.NETHER_PORTAL) {
            sheet = sheet.getRelative(BlockFace.DOWN);
        }
        if (sheet.getBlockData() instanceof Orientable orientable) {
            face = orientable.getAxis() == Axis.X ? BlockFace.SOUTH : BlockFace.EAST;
        }
        Location stand = loc.clone().add(face.getModX() * 1.5, 0, face.getModZ() * 1.5);
        stand.setYaw(io.multiverseportals.away.AwayFrameBuilder.yawOf(face));
        return stand;
    }

    private String portalIdNear(Location loc) {
        World world = loc.getWorld();
        int x = loc.getBlockX();
        int y = loc.getBlockY();
        int z = loc.getBlockZ();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 2; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    Block b = world.getBlockAt(x + dx, y + dy, z + dz);
                    String id = portalByCell.get(cellKey(b));
                    if (id != null) {
                        return id;
                    }
                }
            }
        }
        return null;
    }

    public void ejectFromSheet(org.bukkit.entity.Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        Location safe = standInFront(player.getLocation());
        if (safe == null) {
            return;
        }
        player.setPortalCooldown(300);
        player.teleport(safe);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoinInSheet(PlayerJoinEvent event) {
        org.bukkit.entity.Player player = event.getPlayer();
        if (!inOurSheet(player.getLocation())) {
            return;
        }
        player.setPortalCooldown(300);
        Bukkit.getScheduler().runTask(plugin, () -> ejectFromSheet(player));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPhysics(BlockPhysicsEvent event) {
        if (event.getBlock().getType() == Material.NETHER_PORTAL && isMatterBlock(event.getBlock())) {
            event.setCancelled(true);
        }
        if (event.getChangedType() == Material.NETHER_PORTAL && isMatterBlock(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onWater(BlockFromToEvent event) {
        if (isMatterBlock(event.getToBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreakMatter(BlockBreakEvent event) {
        if (!isMatterBlock(event.getBlock())) {
            return;
        }
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInside(EntityInsideBlockEvent event) {
        if (event.getBlock().getType() != Material.NETHER_PORTAL || !isMatterBlock(event.getBlock())) {
            return;
        }
        event.setCancelled(true);
        Location loc = event.getBlock().getLocation();
        var listener = plugin.portalListener();
        if (listener == null) {
            return;
        }
        if (event.getEntity() instanceof org.bukkit.entity.Player player) {
            listener.tryActivateFromInside(player, loc);
        } else if (event.getEntity() instanceof org.bukkit.entity.LivingEntity living) {
            listener.tryActivateEntityFromInside(living, loc);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerNether(PlayerPortalEvent event) {
        if (nearOurPortal(event.getFrom()) || nearOurPortal(event.getTo())) {
            event.setCancelled(true);
            ejectFromSheet(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityNether(EntityPortalEvent event) {
        if (event.getFrom() != null && nearOurPortal(event.getFrom())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEnter(EntityPortalEnterEvent event) {
        if (nearOurPortal(event.getEntity().getLocation())) {
            event.setCancelled(true);
            if (event.getEntity() instanceof org.bukkit.entity.Player player) {
                player.setPortalCooldown(300);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (event.getCause() == PlayerTeleportEvent.TeleportCause.NETHER_PORTAL
                && (nearOurPortal(event.getFrom()) || nearOurPortal(event.getTo()))) {
            event.setCancelled(true);
            ejectFromSheet(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPortalCreate(PortalCreateEvent event) {
        if (event.getEntity() != null && nearOurPortal(event.getEntity().getLocation())) {
            event.setCancelled(true);
            return;
        }
        for (org.bukkit.block.BlockState state : event.getBlocks()) {
            if (isMatterBlock(state.getBlock())) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlaceOver(BlockPlaceEvent event) {
        if (isMatterBlock(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBucket(PlayerBucketEmptyEvent event) {
        Block into = event.getBlockClicked().getRelative(event.getBlockFace());
        if (isMatterBlock(into) || isMatterBlock(event.getBlockClicked())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(this::isMatterBlock);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().removeIf(this::isMatterBlock);
    }

    /** Vanilla portal blocks already swirl; extra particles only for BlockDisplay styles. */
    public void tickParticles(Portal portal) {
        if (!config.matterEnabled() || !config.matterParticles()) {
            return;
        }
        if ("nether".equalsIgnoreCase(config.matterStyle())) {
            return;
        }
        if (portal.status() == io.multiverseportals.model.PortalStatus.BINDING
                || portal.status() == io.multiverseportals.model.PortalStatus.BIND_FAILED) {
            return;
        }
        if (portal.type() == PortalType.MULTI && !portal.hasBoundDestination()) {
            return;
        }
        if (portal.type() == PortalType.AWAY && !portal.hasAwayDestination()) {
            return;
        }
        World world = Bukkit.getWorld(portal.frame().world());
        if (world == null) {
            return;
        }
        String tag = TAG_PREFIX + portal.id();
        Particle particle = Particle.PORTAL;
        for (Entity e : world.getEntitiesByClass(BlockDisplay.class)) {
            if (!e.getScoreboardTags().contains(tag)) {
                continue;
            }
            Location c = e.getLocation().add(0.5, 0.5, 0.5);
            boolean near = false;
            for (var p : world.getPlayers()) {
                if (p.getLocation().distanceSquared(c) < 400) {
                    near = true;
                    break;
                }
            }
            if (!near) {
                continue;
            }
            world.spawnParticle(particle, c, 8, 0.25, 0.35, 0.25, 0.4);
            if (config.matterStyle().equalsIgnoreCase("end")) {
                world.spawnParticle(Particle.END_ROD, c, 1, 0.2, 0.2, 0.2, 0.01);
            }
        }
    }

    private void placeNetherSheet(World world, Location cell, Axis axis, String portalId) {
        Block block = world.getBlockAt(cell.getBlockX(), cell.getBlockY(), cell.getBlockZ());
        if (!FrameDetector.isPassable(block.getType()) && block.getType() != Material.NETHER_PORTAL) {
            return;
        }
        BlockData data = Material.NETHER_PORTAL.createBlockData();
        if (data instanceof Orientable orientable) {
            orientable.setAxis(axis == Axis.Z ? Axis.Z : Axis.X);
            data = orientable;
        }
        String key = cellKey(block);
        portalByCell.put(key, portalId);
        cellsByPortal.computeIfAbsent(portalId, id -> new HashSet<>()).add(key);
        block.setBlockData(data, false);
    }

    private boolean nearOurPortal(Location loc) {
        if (loc == null || loc.getWorld() == null) {
            return false;
        }
        World world = loc.getWorld();
        int x = loc.getBlockX();
        int y = loc.getBlockY();
        int z = loc.getBlockZ();
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -1; dy <= 2; dy++) {
                for (int dz = -2; dz <= 2; dz++) {
                    Block b = world.getBlockAt(x + dx, y + dy, z + dz);
                    if (isMatterBlock(b)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private void spawnMatterBlock(World world, Location cell, Material look, Axis axis, String portalId) {
        Block block = world.getBlockAt(cell.getBlockX(), cell.getBlockY(), cell.getBlockZ());
        if (!FrameDetector.isPassable(block.getType()) && block.getType() != Material.NETHER_PORTAL) {
            return;
        }
        if (block.getType() == Material.NETHER_PORTAL) {
            block.setType(Material.AIR, false);
        }
        String key = cellKey(block);
        portalByCell.put(key, portalId);
        cellsByPortal.computeIfAbsent(portalId, id -> new HashSet<>()).add(key);
        Location spawnAt = cell.toBlockLocation();
        BlockData data = look.createBlockData();
        if (data instanceof Orientable orientable) {
            orientable.setAxis(axis == Axis.Z ? Axis.Z : Axis.X);
            data = orientable;
        }
        BlockData finalData = data;
        world.spawn(spawnAt, BlockDisplay.class, display -> {
            display.setBlock(finalData);
            display.setPersistent(true);
            display.setGravity(false);
            display.setInvulnerable(true);
            display.setSilent(true);
            display.addScoreboardTag(TAG_PREFIX + portalId);
            display.getPersistentDataContainer().set(keyPortalId, PersistentDataType.STRING, portalId);
            display.setBrightness(new Display.Brightness(15, 15));
            float s = 1.02f;
            display.setTransformation(new Transformation(
                    new Vector3f(-0.01f, -0.01f, -0.01f),
                    new AxisAngle4f(0, 0, 1, 0),
                    new Vector3f(s, s, s),
                    new AxisAngle4f(0, 0, 1, 0)
            ));
            display.setViewRange(128f);
            display.setShadowStrength(0f);
        });
    }

    /**
     * Real nether-portal blocks for a 1-thick filled rectangle (1–21 wide, 2–21 high).
     * Vanilla would pop anything outside 2×3–21×21; {@link #onPhysics} keeps ours.
     * Circles and anything larger still use BlockDisplay.
     */
    static boolean fitsVanillaNetherSheet(List<Location> cells) {
        if (cells == null || cells.isEmpty()) {
            return false;
        }
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (Location c : cells) {
            minX = Math.min(minX, c.getBlockX());
            maxX = Math.max(maxX, c.getBlockX());
            minY = Math.min(minY, c.getBlockY());
            maxY = Math.max(maxY, c.getBlockY());
            minZ = Math.min(minZ, c.getBlockZ());
            maxZ = Math.max(maxZ, c.getBlockZ());
        }
        int dx = maxX - minX + 1;
        int dy = maxY - minY + 1;
        int dz = maxZ - minZ + 1;
        int thick = Math.min(dx, dz);
        int width = Math.max(dx, dz);
        if (thick != 1 || width < 1 || width > 21 || dy < 2 || dy > 21) {
            return false;
        }
        return cells.size() == (long) width * dy;
    }

    private static String cellKey(Block block) {
        return block.getWorld().getName() + "|" + block.getX() + "|" + block.getY() + "|" + block.getZ();
    }

    private Block blockFromKey(String key) {
        String[] p = key.split("\\|");
        if (p.length != 4) {
            return null;
        }
        World world = Bukkit.getWorld(p[0]);
        if (world == null) {
            return null;
        }
        try {
            return world.getBlockAt(Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Axis detectAxis(List<Location> cells) {
        if (cells.isEmpty()) {
            return Axis.X;
        }
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (Location c : cells) {
            minX = Math.min(minX, c.getBlockX());
            maxX = Math.max(maxX, c.getBlockX());
            minZ = Math.min(minZ, c.getBlockZ());
            maxZ = Math.max(maxZ, c.getBlockZ());
        }
        return (maxX - minX) >= (maxZ - minZ) ? Axis.X : Axis.Z;
    }

    private Material matterMaterial() {
        String style = config.matterStyle();
        if ("end".equalsIgnoreCase(style)) {
            return Material.END_PORTAL;
        }
        if ("gateway".equalsIgnoreCase(style)) {
            return Material.END_GATEWAY;
        }
        return Material.NETHER_PORTAL;
    }

    public static List<Location> findOpeningCells(Block sign) {
        return findOpeningCells(sign, 24);
    }

    public static List<Location> findOpeningCells(Block sign, int maxRadius) {
        FrameDetector.Scan scan = FrameDetector.scan(sign, maxRadius);
        if (scan.closed() && !scan.interior().isEmpty()) {
            int cap = Math.max(24, (2 * maxRadius + 1) * (2 * maxRadius + 1));
            if (scan.interior().size() <= cap) {
                return new ArrayList<>(scan.interior());
            }
            return new ArrayList<>(scan.interior().subList(0, cap));
        }
        return List.of();
    }
}
