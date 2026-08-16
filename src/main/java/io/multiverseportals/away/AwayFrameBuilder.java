package io.multiverseportals.away;

import io.multiverseportals.portal.FrameDetector;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.type.WallSign;

/**
 * Small 4×5 closed ring (vanilla nether size) of the origin biome's material.
 * Control sign hangs on the <em>right</em> jamb (when looking at the portal), not on top.
 */
public final class AwayFrameBuilder {

    private AwayFrameBuilder() {}

    public record Built(Block sign, Location stand, Location signLoc) {}

    public static Location findSpot(World world, Location hint, Location avoid, int minDistance) {
        return findSpot(world, hint, avoid, minDistance, null);
    }

    public static Location findSpot(World world, Location hint, Location avoid, int minDistance, String biomeKey) {
        if (world == null || hint == null) {
            return null;
        }
        int hx = hint.getBlockX();
        int hz = hint.getBlockZ();
        for (int ring = 0; ring <= 48; ring += 4) {
            for (int dx = -ring; dx <= ring; dx += 4) {
                for (int dz = -ring; dz <= ring; dz += 4) {
                    if (ring > 0 && Math.abs(dx) != ring && Math.abs(dz) != ring) {
                        continue;
                    }
                    int x = hx + dx;
                    int z = hz + dz;
                    if (avoid != null && avoid.getWorld() == world) {
                        double ddx = x + 0.5 - avoid.getX();
                        double ddz = z + 0.5 - avoid.getZ();
                        if (ddx * ddx + ddz * ddz < (long) minDistance * minDistance) {
                            continue;
                        }
                    }
                    int y = world.getHighestBlockYAt(x, z, HeightMap.WORLD_SURFACE);
                    if (biomeKey != null && !biomeKey.isBlank()
                            && !biomeKey.equalsIgnoreCase(BiomeFrames.keyOf(biomeAt(world, x, y, z)))) {
                        continue;
                    }
                    Block ground = world.getBlockAt(x, y, z);
                    if (ground.getType() == Material.LAVA || ground.getType() == Material.FIRE) {
                        continue;
                    }
                    // Prefer a 4×1 footprint that is not leaves-only
                    if (y < world.getMinHeight() + 8 || y > world.getMaxHeight() - 10) {
                        continue;
                    }
                    return new Location(world, x + 0.5, y + 1, z + 0.5);
                }
            }
        }
        int y = world.getHighestBlockYAt(hx, hz, HeightMap.MOTION_BLOCKING_NO_LEAVES);
        return new Location(world, hx + 0.5, Math.max(world.getMinHeight() + 8, y + 1), hz + 0.5);
    }

    private static org.bukkit.block.Biome biomeAt(World world, int x, int y, int z) {
        try {
            return world.getBiome(x, y, z);
        } catch (Throwable t) {
            return world.getBlockAt(x, y, z).getBiome();
        }
    }

    public static Built build(World world, Location feet, BlockFace facing, Material frameMat) {
        if (world == null || feet == null || frameMat == null) {
            return null;
        }
        BlockFace face = (facing == BlockFace.EAST || facing == BlockFace.WEST
                || facing == BlockFace.NORTH || facing == BlockFace.SOUTH)
                ? facing : BlockFace.SOUTH;
        boolean xAxis = face == BlockFace.NORTH || face == BlockFace.SOUTH;
        int x0 = feet.getBlockX();
        int y0 = feet.getBlockY();
        int z0 = feet.getBlockZ();

        // Outer 4×5 in the portal plane; interior 2×3.
        for (int w = 0; w < 4; w++) {
            for (int h = 0; h < 5; h++) {
                boolean ring = w == 0 || w == 3 || h == 0 || h == 4;
                Block b = cell(world, x0, y0, z0, xAxis, w, h);
                if (ring) {
                    b.setType(frameMat, false);
                } else {
                    b.setType(Material.AIR, false);
                }
            }
        }
        // Floor under the ring so it does not float in water/void
        for (int w = 0; w < 4; w++) {
            Block under = cell(world, x0, y0, z0, xAxis, w, -1);
            if (!under.getType().isSolid()) {
                under.setType(frameMat, false);
            }
        }

        // Right jamb, mid-opening — looking at the portal, not stacked on the lintel
        Block jamb = cell(world, x0, y0, z0, xAxis, rightWidthIndex(face, 4), 2);
        Block signBlock = jamb.getRelative(face);
        signBlock.setType(Material.OAK_WALL_SIGN, false);
        if (signBlock.getBlockData() instanceof WallSign wall) {
            wall.setFacing(face);
            signBlock.setBlockData(wall, false);
        } else if (signBlock.getBlockData() instanceof Directional dir) {
            dir.setFacing(face);
            signBlock.setBlockData(dir, false);
        }

        Block floor = cell(world, x0, y0, z0, xAxis, 1, 1);
        Location stand = floor.getLocation().add(0.5, 0.05, 0.5);
        stand.add(face.getModX() * 1.5, 0, face.getModZ() * 1.5);
        stand.setYaw(yawOf(face));
        Location signLoc = signBlock.getLocation();
        signLoc.setYaw(yawOf(face));
        return new Built(signBlock, stand, signLoc);
    }

    /** Rebuild the 4×5 ring so the control sign sits at {@code signX,Y,Z} (even if that block is air). */
    public static Built rebuildAtSign(World world, int signX, int signY, int signZ, BlockFace facing, Material frameMat) {
        BlockFace face = cardinal(facing);
        int[] feet = feetFromSign(signX, signY, signZ, face);
        return build(world, new Location(world, feet[0], feet[1], feet[2]), face, frameMat);
    }

    /**
     * Bottom-left of the 4×5 ring from a wall-sign in front of the right jamb (h=2).
     */
    static int[] feetFromSign(int sx, int sy, int sz, BlockFace face) {
        BlockFace into = cardinal(face).getOppositeFace();
        int jx = sx + into.getModX();
        int jy = sy + into.getModY();
        int jz = sz + into.getModZ();
        int right = rightWidthIndex(face, 4);
        int y0 = jy - 2;
        if (face == BlockFace.NORTH || face == BlockFace.SOUTH) {
            return new int[]{jx - right, y0, jz};
        }
        return new int[]{jx, y0, jz - right};
    }

    static BlockFace cardinal(BlockFace face) {
        if (face == BlockFace.EAST || face == BlockFace.WEST
                || face == BlockFace.NORTH || face == BlockFace.SOUTH) {
            return face;
        }
        return BlockFace.SOUTH;
    }

    /**
     * Width index of the right pillar when standing in front of the portal (sign facing {@code face}).
     */
    static int rightWidthIndex(BlockFace face, int width) {
        int last = Math.max(0, width - 1);
        return (face == BlockFace.SOUTH || face == BlockFace.WEST) ? last : 0;
    }

    private static Block cell(World world, int x0, int y0, int z0, boolean xAxis, int w, int h) {
        if (xAxis) {
            return world.getBlockAt(x0 + w, y0 + h, z0);
        }
        return world.getBlockAt(x0, y0 + h, z0 + w);
    }

    public static float yawOf(BlockFace face) {
        return switch (face) {
            case SOUTH -> 0f;
            case WEST -> 90f;
            case NORTH -> 180f;
            case EAST -> 270f;
            default -> 0f;
        };
    }

    public static BlockFace facingFromYaw(float yaw) {
        float n = yaw % 360f;
        if (n < 0) {
            n += 360f;
        }
        if (n >= 315 || n < 45) {
            return BlockFace.SOUTH;
        }
        if (n < 135) {
            return BlockFace.WEST;
        }
        if (n < 225) {
            return BlockFace.NORTH;
        }
        return BlockFace.EAST;
    }

    public static boolean looksClosed(Block sign, int radius) {
        return FrameDetector.scan(sign, radius).closed();
    }
}
