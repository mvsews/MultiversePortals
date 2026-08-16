package io.multiverseportals.away;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Biome;

/**
 * {@code World#locateNearestBiome} returns the <em>closest</em> sample — usually the near
 * edge of the biome. Walk axis extents from that seed and take the cross centroid so the
 * auto-built exit sits in the interior.
 */
public final class BiomeInterior {

    @FunctionalInterface
    public interface InBiome {
        boolean test(int x, int z);
    }

    private BiomeInterior() {}

    public static Location towardCenter(World world, Location origin, Location seed, String biomeKey, int step, int maxExtent) {
        if (world == null || seed == null || biomeKey == null || biomeKey.isBlank()) {
            return seed;
        }
        int y = sampleY(seed);
        InBiome in = (x, z) -> biomeKey.equalsIgnoreCase(BiomeFrames.keyOf(biomeAt(world, x, y, z)));
        int ox = origin != null ? origin.getBlockX() : seed.getBlockX();
        int oz = origin != null ? origin.getBlockZ() : seed.getBlockZ();
        int[] xz = centerXz(ox, oz, seed.getBlockX(), seed.getBlockZ(), in, Math.max(8, step), Math.max(step, maxExtent));
        Location out = seed.clone();
        out.setX(xz[0] + 0.5);
        out.setZ(xz[1] + 0.5);
        return out;
    }

    /**
     * Axis-aligned cross centroid of the connected biome around {@code seed}.
     * If the centroid lands outside (odd shapes), snap back toward the seed.
     */
    public static int[] centerXz(int originX, int originZ, int seedX, int seedZ, InBiome in, int step, int maxExtent) {
        int sx = seedX;
        int sz = seedZ;
        if (!in.test(sx, sz)) {
            int[] found = spiralFind(sx, sz, in, Math.min(256, maxExtent), Math.max(8, step));
            if (found == null) {
                return new int[]{seedX, seedZ};
            }
            sx = found[0];
            sz = found[1];
        }
        int west = walk(sx, sz, -1, 0, in, step, maxExtent)[0];
        int east = walk(sx, sz, 1, 0, in, step, maxExtent)[0];
        int north = walk(sx, sz, 0, -1, in, step, maxExtent)[1];
        int south = walk(sx, sz, 0, 1, in, step, maxExtent)[1];
        int cx = (west + east) / 2;
        int cz = (north + south) / 2;
        if (!in.test(cx, cz)) {
            int[] snapped = walkToward(cx, cz, sx, sz, in, Math.max(8, step / 2));
            if (snapped != null) {
                cx = snapped[0];
                cz = snapped[1];
            } else {
                cx = sx;
                cz = sz;
            }
        }
        return new int[]{cx, cz};
    }

    static int[] walk(int x, int z, int dx, int dz, InBiome in, int step, int maxExtent) {
        int lastX = x;
        int lastZ = z;
        int dist = 0;
        while (dist + step <= maxExtent) {
            int nx = lastX + dx * step;
            int nz = lastZ + dz * step;
            if (!in.test(nx, nz)) {
                break;
            }
            lastX = nx;
            lastZ = nz;
            dist += step;
        }
        return new int[]{lastX, lastZ};
    }

    static int[] walkToward(int fromX, int fromZ, int toX, int toZ, InBiome in, int step) {
        int x = fromX;
        int z = fromZ;
        int guard = 0;
        while (guard++ < 256 && (x != toX || z != toZ)) {
            if (in.test(x, z)) {
                return new int[]{x, z};
            }
            if (x != toX) {
                x += Integer.compare(toX, x) * Math.min(step, Math.abs(toX - x));
            }
            if (z != toZ) {
                z += Integer.compare(toZ, z) * Math.min(step, Math.abs(toZ - z));
            }
        }
        return in.test(toX, toZ) ? new int[]{toX, toZ} : null;
    }

    static int[] spiralFind(int x, int z, InBiome in, int maxR, int step) {
        if (in.test(x, z)) {
            return new int[]{x, z};
        }
        for (int r = step; r <= maxR; r += step) {
            for (int dx = -r; dx <= r; dx += step) {
                if (in.test(x + dx, z - r)) {
                    return new int[]{x + dx, z - r};
                }
                if (in.test(x + dx, z + r)) {
                    return new int[]{x + dx, z + r};
                }
            }
            for (int dz = -r + step; dz <= r - step; dz += step) {
                if (in.test(x - r, z + dz)) {
                    return new int[]{x - r, z + dz};
                }
                if (in.test(x + r, z + dz)) {
                    return new int[]{x + r, z + dz};
                }
            }
        }
        return null;
    }

    private static int sampleY(Location seed) {
        int y = seed.getBlockY();
        if (y < 32 || y > 200) {
            return 80;
        }
        return y;
    }

    private static Biome biomeAt(World world, int x, int y, int z) {
        try {
            return world.getBiome(x, y, z);
        } catch (Throwable t) {
            return world.getBlockAt(x, y, z).getBiome();
        }
    }
}
