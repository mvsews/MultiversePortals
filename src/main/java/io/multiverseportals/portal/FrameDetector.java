package io.multiverseportals.portal;

import org.bukkit.Axis;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.type.WallSign;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Closed portal opening of any size in the sign's plane (vanilla nether ring, giant circle, …).
 * Interior flood-fill must not leak past {@code maxRadius}.
 * Packed rings that share a 1-block pillar stay separate: the scan picks the closed hole
 * attached to <em>this</em> sign, not a neighbour's.
 */
public final class FrameDetector {

    public record Scan(
            boolean closed,
            Axis axis,
            List<Location> interior,
            List<Block> frameBlocks
    ) {
        public static Scan open() {
            return new Scan(false, Axis.X, List.of(), List.of());
        }

        public boolean contains(int x, int y, int z) {
            return interiorContains(x, y, z) || frameContains(x, y, z);
        }

        /** The hole only — standing on the ring (lintel) is not inside. */
        public boolean interiorContains(int x, int y, int z) {
            for (Location loc : interior) {
                if (loc.getBlockX() == x && loc.getBlockY() == y && loc.getBlockZ() == z) {
                    return true;
                }
            }
            return false;
        }

        public boolean frameContains(int x, int y, int z) {
            for (Block b : frameBlocks) {
                if (b.getX() == x && b.getY() == y && b.getZ() == z) {
                    return true;
                }
            }
            return false;
        }
    }

    @FunctionalInterface
    public interface CellFn {
        boolean test(int x, int y, int z);
    }

    public record Coord(int x, int y, int z) {}

    /** Feet or head in the hole — not the ring, and not the block under the lintel. */
    public static boolean standingInOpening(List<Coord> interior, int x, int y, int z) {
        return coordIn(interior, x, y, z) || coordIn(interior, x, y + 1, z);
    }

    private static boolean coordIn(List<Coord> interior, int x, int y, int z) {
        for (Coord c : interior) {
            if (c.x() == x && c.y() == y && c.z() == z) {
                return true;
            }
        }
        return false;
    }

    public record PlaneResult(boolean closed, List<Coord> interior, List<Coord> frame) {
        static PlaneResult open() {
            return new PlaneResult(false, List.of(), List.of());
        }
    }

    private FrameDetector() {}

    public static Scan scan(Block sign, int maxRadius) {
        if (sign == null || maxRadius < 2) {
            return Scan.open();
        }
        int r = Math.max(2, Math.min(48, maxRadius));
        BlockFace facing = facingOf(sign);
        BlockFace into = facing.getOppositeFace();
        Block key = sign.getRelative(into);
        World world = sign.getWorld();
        CellFn passable = (x, y, z) -> isPassable(world.getBlockAt(x, y, z).getType());
        CellFn solid = (x, y, z) -> world.getBlockAt(x, y, z).getType().isSolid();
        if (facing == BlockFace.EAST || facing == BlockFace.WEST) {
            return toScan(world, scanPlane(
                    sign.getX(), sign.getY(), sign.getZ(),
                    key.getX(), key.getY(), key.getZ(),
                    r, Axis.Z, passable, solid), Axis.Z);
        }
        if (facing == BlockFace.NORTH || facing == BlockFace.SOUTH) {
            return toScan(world, scanPlane(
                    sign.getX(), sign.getY(), sign.getZ(),
                    key.getX(), key.getY(), key.getZ(),
                    r, Axis.X, passable, solid), Axis.X);
        }
        PlaneResult a = scanPlane(
                sign.getX(), sign.getY(), sign.getZ(),
                key.getX(), key.getY(), key.getZ(),
                r, Axis.X, passable, solid);
        PlaneResult b = scanPlane(
                sign.getX(), sign.getY(), sign.getZ(),
                key.getX(), key.getY(), key.getZ(),
                r, Axis.Z, passable, solid);
        if (a.closed() && !b.closed()) {
            return toScan(world, a, Axis.X);
        }
        if (b.closed() && !a.closed()) {
            return toScan(world, b, Axis.Z);
        }
        // Unknown facing: the hole is the smaller closed region (not the room behind a 1-thick wall).
        PlaneResult pick = a.interior().size() <= b.interior().size() ? a : b;
        Axis axis = pick == a ? Axis.X : Axis.Z;
        return toScan(world, pick, axis);
    }

    /**
     * Flood-fill in one wall plane. When several closed holes sit next to the sign
     * (shared pillar), pick the largest hole that still belongs to this sign —
     * not a neighbour, and not a 1-block decorative gap.
     */
    static PlaneResult scanPlane(
            int sx, int sy, int sz,
            int kx, int ky, int kz,
            int maxRadius,
            Axis axis,
            CellFn passable,
            CellFn solid
    ) {
        int[][] dirs = inPlaneDeltas(axis);
        List<Coord> seeds = findInteriorSeeds(sx, sy, sz, kx, ky, kz, maxRadius, axis, passable);
        if (seeds.isEmpty()) {
            return PlaneResult.open();
        }

        Set<Long> claimed = new HashSet<>();
        List<PlaneResult> candidates = new ArrayList<>();
        for (Coord seed : seeds) {
            long pk = pack(seed.x(), seed.y(), seed.z());
            if (!claimed.add(pk)) {
                continue;
            }
            PlaneResult flood = floodFrom(sx, sy, sz, seed, maxRadius, dirs, passable, solid);
            if (flood.closed()) {
                for (Coord c : flood.interior()) {
                    claimed.add(pack(c.x(), c.y(), c.z()));
                }
                candidates.add(flood);
            } else {
                for (Coord c : flood.interior()) {
                    claimed.add(pack(c.x(), c.y(), c.z()));
                }
            }
        }
        if (candidates.isEmpty()) {
            return PlaneResult.open();
        }

        List<PlaneResult> local = new ArrayList<>();
        for (PlaneResult c : candidates) {
            if (attachedBelowSign(c, sx, sy, sz, maxRadius)) {
                local.add(c);
            }
        }
        if (local.isEmpty()) {
            local = candidates;
        }
        PlaneResult best = local.get(0);
        double bestScore = componentScore(best, sx, sy, sz);
        for (int i = 1; i < local.size(); i++) {
            PlaneResult c = local.get(i);
            double score = componentScore(c, sx, sy, sz);
            if (score < bestScore) {
                bestScore = score;
                best = c;
            }
        }
        return best;
    }

    /** Larger hole first (portal over a peephole); equal size → closer to this sign. */
    private static double componentScore(PlaneResult scan, int sx, int sy, int sz) {
        double dist = centroidDistSq(scan, sx, sy, sz);
        return -scan.interior().size() * 1_000_000.0 + dist;
    }

    private static boolean attachedBelowSign(PlaneResult scan, int sx, int sy, int sz, int maxRadius) {
        int near = Math.min(3, maxRadius);
        for (Coord c : scan.interior()) {
            if (c.y() > sy) {
                continue;
            }
            if (chebyshev(sx, sy, sz, c.x(), c.y(), c.z()) <= near) {
                return true;
            }
        }
        return false;
    }

    private static double centroidDistSq(PlaneResult scan, int sx, int sy, int sz) {
        if (scan.interior().isEmpty()) {
            return Double.POSITIVE_INFINITY;
        }
        double x = 0;
        double y = 0;
        double z = 0;
        for (Coord c : scan.interior()) {
            x += c.x();
            y += c.y();
            z += c.z();
        }
        int n = scan.interior().size();
        x = x / n - sx;
        y = y / n - sy;
        z = z / n - sz;
        return x * x + y * y + z * z;
    }

    static List<Coord> findInteriorSeeds(
            int sx, int sy, int sz,
            int kx, int ky, int kz,
            int maxRadius,
            Axis axis,
            CellFn passable
    ) {
        List<Coord> out = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        int reach = Math.min(2, maxRadius);
        for (int d = 0; d <= reach; d++) {
            for (int a = -d; a <= d; a++) {
                for (int b = -d; b <= d; b++) {
                    if (Math.max(Math.abs(a), Math.abs(b)) != d) {
                        continue;
                    }
                    int x;
                    int y;
                    int z;
                    if (axis == Axis.Z) {
                        x = kx;
                        y = ky + b;
                        z = kz + a;
                    } else {
                        x = kx + a;
                        y = ky + b;
                        z = kz;
                    }
                    if (chebyshev(sx, sy, sz, x, y, z) > maxRadius) {
                        continue;
                    }
                    if (!passable.test(x, y, z)) {
                        continue;
                    }
                    if (seen.add(pack(x, y, z))) {
                        out.add(new Coord(x, y, z));
                    }
                }
            }
        }
        if (out.isEmpty() && passable.test(kx, ky, kz)
                && chebyshev(sx, sy, sz, kx, ky, kz) <= maxRadius) {
            out.add(new Coord(kx, ky, kz));
        }
        out.sort((p, q) -> {
            boolean pb = p.y() <= sy;
            boolean qb = q.y() <= sy;
            if (pb != qb) {
                return pb ? -1 : 1;
            }
            int dy = Integer.compare(q.y(), p.y());
            if (dy != 0) {
                return dy;
            }
            int dp = chebyshev(sx, sy, sz, p.x(), p.y(), p.z());
            int dq = chebyshev(sx, sy, sz, q.x(), q.y(), q.z());
            if (dp != dq) {
                return Integer.compare(dp, dq);
            }
            if (p.x() != q.x()) {
                return Integer.compare(p.x(), q.x());
            }
            return Integer.compare(p.z(), q.z());
        });
        return out;
    }

    private static PlaneResult floodFrom(
            int sx, int sy, int sz,
            Coord start,
            int maxRadius,
            int[][] dirs,
            CellFn passable,
            CellFn solid
    ) {
        Set<Long> visited = new HashSet<>();
        ArrayDeque<Coord> q = new ArrayDeque<>();
        q.add(start);
        visited.add(pack(start.x(), start.y(), start.z()));
        List<Coord> interior = new ArrayList<>();
        boolean leaked = false;

        while (!q.isEmpty()) {
            Coord cur = q.poll();
            if (chebyshev(sx, sy, sz, cur.x(), cur.y(), cur.z()) > maxRadius) {
                leaked = true;
                break;
            }
            interior.add(cur);
            if (interior.size() > maxRadius * maxRadius) {
                leaked = true;
                break;
            }
            for (int[] d : dirs) {
                int nx = cur.x() + d[0];
                int ny = cur.y() + d[1];
                int nz = cur.z() + d[2];
                if (!passable.test(nx, ny, nz)) {
                    continue;
                }
                long pk = pack(nx, ny, nz);
                if (!visited.add(pk)) {
                    continue;
                }
                if (chebyshev(sx, sy, sz, nx, ny, nz) > maxRadius) {
                    leaked = true;
                    break;
                }
                q.add(new Coord(nx, ny, nz));
            }
            if (leaked) {
                break;
            }
        }

        if (leaked || interior.isEmpty()) {
            return new PlaneResult(false, List.copyOf(interior), List.of());
        }

        Set<Long> frameKeys = new HashSet<>();
        List<Coord> frame = new ArrayList<>();
        for (Coord cell : interior) {
            for (int[] d : dirs) {
                int nx = cell.x() + d[0];
                int ny = cell.y() + d[1];
                int nz = cell.z() + d[2];
                if (passable.test(nx, ny, nz)) {
                    continue;
                }
                if (nx == sx && ny == sy && nz == sz) {
                    continue;
                }
                if (!solid.test(nx, ny, nz)) {
                    continue;
                }
                long pk = pack(nx, ny, nz);
                if (frameKeys.add(pk)) {
                    frame.add(new Coord(nx, ny, nz));
                }
            }
        }
        if (frame.size() < 6) {
            return PlaneResult.open();
        }
        return new PlaneResult(true, List.copyOf(interior), List.copyOf(frame));
    }

    private static Scan toScan(World world, PlaneResult result, Axis axis) {
        if (!result.closed()) {
            return Scan.open();
        }
        List<Location> interior = new ArrayList<>(result.interior().size());
        for (Coord c : result.interior()) {
            interior.add(new Location(world, c.x(), c.y(), c.z()));
        }
        List<Block> frame = new ArrayList<>(result.frame().size());
        for (Coord c : result.frame()) {
            frame.add(world.getBlockAt(c.x(), c.y(), c.z()));
        }
        return new Scan(true, axis, List.copyOf(interior), List.copyOf(frame));
    }

    private static int[][] inPlaneDeltas(Axis axis) {
        if (axis == Axis.Z) {
            return new int[][]{{0, 0, -1}, {0, 0, 1}, {0, 1, 0}, {0, -1, 0}};
        }
        return new int[][]{{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}};
    }

    public static BlockFace facingOf(Block sign) {
        if (sign.getBlockData() instanceof WallSign wall) {
            return wall.getFacing();
        }
        if (sign.getBlockData() instanceof Directional dir) {
            return dir.getFacing();
        }
        return BlockFace.NORTH;
    }

    public static boolean isPassable(Material m) {
        if (m == null) {
            return false;
        }
        if (m.isAir()) {
            return true;
        }
        return m == Material.WATER
                || m == Material.BUBBLE_COLUMN
                || m == Material.KELP
                || m == Material.KELP_PLANT
                || m == Material.SEAGRASS
                || m == Material.TALL_SEAGRASS
                || m == Material.CAVE_AIR
                || m == Material.VOID_AIR
                || m == Material.NETHER_PORTAL
                || m == Material.END_GATEWAY
                || m == Material.END_PORTAL;
    }

    private static int chebyshev(int ax, int ay, int az, int bx, int by, int bz) {
        return Math.max(Math.abs(ax - bx), Math.max(Math.abs(ay - by), Math.abs(az - bz)));
    }

    public static long pack(int x, int y, int z) {
        return (((long) x) << 42) ^ (((long) z) << 21) ^ (y & 0x1fffff);
    }
}
