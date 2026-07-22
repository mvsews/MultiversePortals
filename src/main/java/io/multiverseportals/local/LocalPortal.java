package io.multiverseportals.local;

import org.bukkit.DyeColor;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.UUID;

/** In-world ColorPortals-style portal (same server / worlds). */
public final class LocalPortal {

    private final String id;
    private String name;
    private DyeColor color;
    private int channel;
    private int node;
    private String world;
    private int x;
    private int y;
    private int z;
    private UUID creator;
    private String linkedPortalId;

    public LocalPortal(
            String id,
            String name,
            DyeColor color,
            int channel,
            int node,
            String world,
            int x,
            int y,
            int z,
            UUID creator,
            String linkedPortalId
    ) {
        this.id = id;
        this.name = name;
        this.color = color;
        this.channel = channel;
        this.node = node;
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
        this.creator = creator;
        this.linkedPortalId = linkedPortalId;
    }

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public DyeColor color() {
        return color;
    }

    public int channel() {
        return channel;
    }

    public int node() {
        return node;
    }

    public void setNode(int node) {
        this.node = node;
    }

    public String world() {
        return world;
    }

    public int x() {
        return x;
    }

    public int y() {
        return y;
    }

    public int z() {
        return z;
    }

    public UUID creator() {
        return creator;
    }

    public String linkedPortalId() {
        return linkedPortalId;
    }

    public void setLinkedPortalId(String linkedPortalId) {
        this.linkedPortalId = linkedPortalId;
    }

    public Location signLocation(World w) {
        return new Location(w, x, y, z);
    }

    public String key() {
        return world + ":" + x + ":" + y + ":" + z;
    }
}
