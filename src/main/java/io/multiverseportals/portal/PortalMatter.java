package io.multiverseportals.portal;

import io.multiverseportals.MultiversePortalsPlugin;
import io.multiverseportals.config.PluginConfig;
import io.multiverseportals.model.Portal;
import io.multiverseportals.model.PortalType;
import org.bukkit.Axis;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.Orientable;
import org.bukkit.block.data.type.WallSign;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Fills the portal opening with nether/end-like "matter" using BlockDisplay
 * (looks like portal blocks between the frame) + dense particles.
 */
public final class PortalMatter {

    public static final String TAG_PREFIX = "mvp_matter_";

    private final MultiversePortalsPlugin plugin;
    private final PluginConfig config;
    private final org.bukkit.NamespacedKey keyPortalId;

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
        // MULTI only shows purple matter after a destination is bound
        if (portal.type() == PortalType.MULTI && !portal.hasBoundDestination()) {
            return;
        }
        World world = Bukkit.getWorld(portal.frame().world());
        if (world == null) {
            return;
        }
        Block sign = world.getBlockAt(portal.frame().x(), portal.frame().y(), portal.frame().z());
        List<Location> cells = findOpeningCells(sign);
        if (cells.isEmpty()) {
            // fallback: 2x3 in front of wall sign
            cells = fallbackOpening(sign);
        }
        Axis axis = detectAxis(cells);
        Material look = matterMaterial(portal);
        for (Location cell : cells) {
            spawnMatterBlock(world, cell, look, axis, portal.id());
        }
    }

    public void remove(String portalId) {
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
                refresh(p);
            }
        });
    }

    /** Dense portal particles inside each matter cell (call periodically). */
    public void tickParticles(Portal portal) {
        if (!config.matterEnabled() || !config.matterParticles()) {
            return;
        }
        if (portal.status() == io.multiverseportals.model.PortalStatus.BINDING
                || portal.status() == io.multiverseportals.model.PortalStatus.BIND_FAILED) {
            return; // white FX owned by PortalBindService
        }
        if (portal.type() == PortalType.MULTI && !portal.hasBoundDestination()) {
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
            // only if player nearby
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

    private void spawnMatterBlock(World world, Location cell, Material look, Axis axis, String portalId) {
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
            display.setViewRange(64f);
            display.setShadowStrength(0f);
        });
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
        // Wider along X → portal plane faces Z (nether portal axis X)
        return (maxX - minX) >= (maxZ - minZ) ? Axis.X : Axis.Z;
    }

    private Material matterMaterial(Portal portal) {
        String style = config.matterStyle();
        if ("end".equalsIgnoreCase(style)) {
            return Material.END_PORTAL;
        }
        if ("gateway".equalsIgnoreCase(style)) {
            return Material.END_GATEWAY;
        }
        // nether portal block texture (works as BlockDisplay without valid frame)
        return Material.NETHER_PORTAL;
    }

    /**
     * Find air blocks that look like the interior of a portal frame near the sign.
     */
    public static List<Location> findOpeningCells(Block sign) {
        World world = sign.getWorld();
        Set<Long> found = new HashSet<>();
        List<Location> out = new ArrayList<>();

        int sx = sign.getX();
        int sy = sign.getY();
        int sz = sign.getZ();

        for (int dx = -3; dx <= 3; dx++) {
            for (int dy = -1; dy <= 4; dy++) {
                for (int dz = -3; dz <= 3; dz++) {
                    Block b = world.getBlockAt(sx + dx, sy + dy, sz + dz);
                    if (!b.getType().isAir()) {
                        continue;
                    }
                    int solid = 0;
                    for (BlockFace f : new BlockFace[]{
                            BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST,
                            BlockFace.UP, BlockFace.DOWN
                    }) {
                        if (b.getRelative(f).getType().isSolid()) {
                            solid++;
                        }
                    }
                    // Interior of a frame: several solid neighbors
                    if (solid < 2) {
                        continue;
                    }
                    // Prefer blocks that sit in a vertical shaft / opening (solid left-right or N-S)
                    boolean verticalSlot =
                            (b.getRelative(BlockFace.EAST).getType().isSolid()
                                    && b.getRelative(BlockFace.WEST).getType().isSolid())
                                    || (b.getRelative(BlockFace.NORTH).getType().isSolid()
                                    && b.getRelative(BlockFace.SOUTH).getType().isSolid());
                    if (!verticalSlot && solid < 3) {
                        continue;
                    }
                    long key = (((long) b.getX()) << 42) ^ (((long) b.getZ()) << 21) ^ b.getY();
                    if (found.add(key)) {
                        out.add(b.getLocation());
                    }
                }
            }
        }

        // Cap size — avoid filling whole caves
        if (out.size() > 24) {
            out.sort((a, b) -> {
                double da = a.distanceSquared(sign.getLocation());
                double db = b.distanceSquared(sign.getLocation());
                return Double.compare(da, db);
            });
            return new ArrayList<>(out.subList(0, 18));
        }
        return out;
    }

    private static List<Location> fallbackOpening(Block sign) {
        List<Location> out = new ArrayList<>();
        BlockFace facing = BlockFace.NORTH;
        if (sign.getBlockData() instanceof WallSign wall) {
            facing = wall.getFacing();
        } else if (sign.getBlockData() instanceof Directional dir) {
            facing = dir.getFacing();
        }
        // Opening "inside" opposite to sign face (into the frame)
        BlockFace into = facing.getOppositeFace();
        Block base = sign.getRelative(into);
        // Axis along the portal width
        boolean xAxis = into == BlockFace.NORTH || into == BlockFace.SOUTH;
        for (int h = 0; h < 3; h++) {
            for (int w = -1; w <= 0; w++) {
                Block cell = base.getRelative(0, h, 0);
                if (xAxis) {
                    cell = base.getWorld().getBlockAt(base.getX() + w, base.getY() + h, base.getZ());
                } else {
                    cell = base.getWorld().getBlockAt(base.getX(), base.getY() + h, base.getZ() + w);
                }
                if (cell.getType().isAir() || !cell.getType().isSolid()) {
                    if (cell.getType().isAir()) {
                        out.add(cell.getLocation());
                    }
                }
            }
        }
        // If still empty, just place 2x3 in front of sign
        if (out.isEmpty()) {
            for (int h = 0; h < 3; h++) {
                for (int w = 0; w < 2; w++) {
                    Block cell;
                    if (xAxis) {
                        cell = sign.getWorld().getBlockAt(sign.getX() + w - 1, sign.getY() + h, sign.getZ() + into.getModZ());
                    } else {
                        cell = sign.getWorld().getBlockAt(sign.getX() + into.getModX(), sign.getY() + h, sign.getZ() + w - 1);
                    }
                    if (cell.getType().isAir()) {
                        out.add(cell.getLocation());
                    }
                }
            }
        }
        return out;
    }
}
