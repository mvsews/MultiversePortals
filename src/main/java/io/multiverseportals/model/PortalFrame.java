package io.multiverseportals.model;

import org.bukkit.Location;
import org.bukkit.World;

import java.util.Objects;

/** Anchor of a portal: sign location + shape hash of surrounding frame. */
public final class PortalFrame {

    private final String world;
    private final int x;
    private final int y;
    private final int z;
    private final String shapeHash;
    private final float yaw;

    public PortalFrame(String world, int x, int y, int z, String shapeHash, float yaw) {
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
        this.shapeHash = shapeHash;
        this.yaw = yaw;
    }

    public static PortalFrame from(Location loc, String shapeHash) {
        return new PortalFrame(
                Objects.requireNonNull(loc.getWorld()).getName(),
                loc.getBlockX(),
                loc.getBlockY(),
                loc.getBlockZ(),
                shapeHash,
                loc.getYaw()
        );
    }

    public Location toLocation(World world) {
        Location loc = new Location(world, x + 0.5, y, z + 0.5);
        loc.setYaw(yaw);
        return loc;
    }

    public String world() { return world; }
    public int x() { return x; }
    public int y() { return y; }
    public int z() { return z; }
    public String shapeHash() { return shapeHash; }
    public float yaw() { return yaw; }

    public String key() {
        return world + ":" + x + "," + y + "," + z;
    }
}
