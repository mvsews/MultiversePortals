package io.multiverseportals.local;

import io.multiverseportals.portal.FrameDetector;
import org.bukkit.Axis;
import org.bukkit.DyeColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.WallSign;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Local wool portal: any closed ring of one wool color (same detector as network portals).
 * Sign hangs on the right jamb when looking at the portal.
 */
public final class WoolFrame {

    public static final int DEFAULT_RADIUS = 24;

    private WoolFrame() {}

    public static boolean isWool(Material m) {
        return m != null && Tag.WOOL.isTagged(m);
    }

    public static DyeColor woolColor(Block block) {
        if (block == null || !isWool(block.getType())) {
            return null;
        }
        String name = block.getType().name();
        if (name.endsWith("_WOOL")) {
            try {
                return DyeColor.valueOf(name.substring(0, name.length() - "_WOOL".length()));
            } catch (IllegalArgumentException ignored) {
            }
        }
        return null;
    }

    public static FrameDetector.Scan scan(Block signBlock) {
        return scan(signBlock, DEFAULT_RADIUS);
    }

    public static FrameDetector.Scan scan(Block signBlock, int maxRadius) {
        return scan(signBlock, maxRadius, Integer.MAX_VALUE);
    }

    public static FrameDetector.Scan scan(Block signBlock, int maxRadius, int maxInterior) {
        if (signBlock == null) {
            return FrameDetector.Scan.open();
        }
        return FrameDetector.scan(signBlock, maxRadius, maxInterior);
    }

    public static boolean frameIsComplete(Block signBlock) {
        return frameIsComplete(signBlock, DEFAULT_RADIUS);
    }

    public static boolean frameIsComplete(Block signBlock, int maxRadius) {
        return frameIsComplete(signBlock, maxRadius, Integer.MAX_VALUE);
    }

    public static boolean frameIsComplete(Block signBlock, int maxRadius, int maxInterior) {
        DyeColor color = colorOfFrame(signBlock).orElse(null);
        if (color == null) {
            return false;
        }
        FrameDetector.Scan scanned = scan(signBlock, maxRadius, maxInterior);
        if (!scanned.closed() || scanned.interior().isEmpty() || scanned.frameBlocks().size() < 6) {
            return false;
        }
        for (Block b : scanned.frameBlocks()) {
            if (woolColor(b) != color) {
                return false;
            }
        }
        return true;
    }

    public static Optional<DyeColor> colorOfFrame(Block signBlock) {
        if (!(signBlock.getBlockData() instanceof WallSign sign)) {
            return Optional.empty();
        }
        DyeColor c = woolColor(signBlock.getRelative(sign.getFacing().getOppositeFace()));
        return Optional.ofNullable(c);
    }

    public static List<Block> frameBlocks(Block signBlock) {
        return frameBlocks(signBlock, DEFAULT_RADIUS);
    }

    public static List<Block> frameBlocks(Block signBlock, int maxRadius) {
        return new ArrayList<>(scan(signBlock, maxRadius).frameBlocks());
    }

    public static List<Block> occupiedBlocks(Block signBlock) {
        return occupiedBlocks(signBlock, DEFAULT_RADIUS);
    }

    public static List<Block> occupiedBlocks(Block signBlock, int maxRadius) {
        List<Block> out = new ArrayList<>();
        out.add(signBlock);
        FrameDetector.Scan scanned = scan(signBlock, maxRadius);
        out.addAll(scanned.frameBlocks());
        for (Location loc : scanned.interior()) {
            out.add(loc.getBlock());
        }
        return out;
    }

    public static List<Block> interiorBlocks(Block signBlock) {
        return interiorBlocks(signBlock, DEFAULT_RADIUS);
    }

    public static List<Block> interiorBlocks(Block signBlock, int maxRadius) {
        return interiorBlocks(signBlock, maxRadius, Integer.MAX_VALUE);
    }

    public static List<Block> interiorBlocks(Block signBlock, int maxRadius, int maxInterior) {
        List<Block> out = new ArrayList<>();
        for (Location loc : scan(signBlock, maxRadius, maxInterior).interior()) {
            out.add(loc.getBlock());
        }
        return out;
    }

    public static Axis sheetAxis(Block signBlock) {
        return sheetAxis(signBlock, DEFAULT_RADIUS);
    }

    public static Axis sheetAxis(Block signBlock, int maxRadius) {
        return sheetAxis(signBlock, maxRadius, Integer.MAX_VALUE);
    }

    public static Axis sheetAxis(Block signBlock, int maxRadius, int maxInterior) {
        FrameDetector.Scan scanned = scan(signBlock, maxRadius, maxInterior);
        if (scanned.closed()) {
            return scanned.axis();
        }
        if (!(signBlock.getBlockData() instanceof WallSign sign)) {
            return Axis.X;
        }
        BlockFace facing = sign.getFacing();
        return (facing == BlockFace.NORTH || facing == BlockFace.SOUTH) ? Axis.X : Axis.Z;
    }

    public static boolean standingInOpening(Location loc, Block signBlock) {
        return standingInOpening(loc, signBlock, DEFAULT_RADIUS);
    }

    public static boolean standingInOpening(Location loc, Block signBlock, int maxRadius) {
        return standingInOpening(loc, signBlock, maxRadius, Integer.MAX_VALUE);
    }

    public static boolean standingInOpening(Location loc, Block signBlock, int maxRadius, int maxInterior) {
        if (loc == null || loc.getWorld() == null || signBlock == null) {
            return false;
        }
        if (!loc.getWorld().equals(signBlock.getWorld())) {
            return false;
        }
        FrameDetector.Scan scanned = scan(signBlock, maxRadius, maxInterior);
        int x = loc.getBlockX();
        int y = loc.getBlockY();
        int z = loc.getBlockZ();
        return scanned.interiorContains(x, y, z) || scanned.interiorContains(x, y + 1, z);
    }

    public static boolean containsBlock(Block signBlock, Block target, int maxRadius) {
        return containsBlock(signBlock, target, maxRadius, Integer.MAX_VALUE);
    }

    public static boolean containsBlock(Block signBlock, Block target, int maxRadius, int maxInterior) {
        if (signBlock == null || target == null) {
            return false;
        }
        if (!signBlock.getWorld().equals(target.getWorld())) {
            return false;
        }
        if (signBlock.getX() == target.getX() && signBlock.getY() == target.getY() && signBlock.getZ() == target.getZ()) {
            return true;
        }
        FrameDetector.Scan scanned = scan(signBlock, maxRadius, maxInterior);
        int x = target.getX();
        int y = target.getY();
        int z = target.getZ();
        return scanned.contains(x, y, z);
    }

    public static Location warpLocation(Block signBlock) {
        return warpLocation(signBlock, DEFAULT_RADIUS);
    }

    public static Location warpLocation(Block signBlock, int maxRadius) {
        return warpLocation(signBlock, maxRadius, Integer.MAX_VALUE);
    }

    public static Location warpLocation(Block signBlock, int maxRadius, int maxInterior) {
        if (!(signBlock.getBlockData() instanceof WallSign sign)) {
            return signBlock.getLocation().add(0.5, 0, 0.5);
        }
        List<Location> interior = scan(signBlock, maxRadius, maxInterior).interior();
        Location warp;
        if (interior.isEmpty()) {
            warp = signBlock
                    .getRelative(sign.getFacing().getOppositeFace())
                    .getRelative(BlockFace.DOWN)
                    .getRelative(BlockFace.DOWN)
                    .getLocation()
                    .add(0.5, 0, 0.5);
        } else {
            int minY = Integer.MAX_VALUE;
            for (Location loc : interior) {
                minY = Math.min(minY, loc.getBlockY());
            }
            double x = 0;
            double z = 0;
            int n = 0;
            for (Location loc : interior) {
                if (loc.getBlockY() != minY) {
                    continue;
                }
                x += loc.getBlockX();
                z += loc.getBlockZ();
                n++;
            }
            warp = new Location(signBlock.getWorld(), x / n + 0.5, minY, z / n + 0.5);
        }
        warp.setYaw(faceToYaw(sign.getFacing()) + 180F);
        return warp;
    }

    public static Location arrivalLocation(Block signBlock) {
        return arrivalLocation(signBlock, DEFAULT_RADIUS);
    }

    public static Location arrivalLocation(Block signBlock, int maxRadius) {
        return arrivalLocation(signBlock, maxRadius, Integer.MAX_VALUE);
    }

    public static Location arrivalLocation(Block signBlock, int maxRadius, int maxInterior) {
        if (!(signBlock.getBlockData() instanceof WallSign sign)) {
            return signBlock.getLocation().add(0.5, -1.9, 0.5);
        }
        BlockFace face = sign.getFacing();
        Location loc = warpLocation(signBlock, maxRadius, maxInterior).clone();
        loc.add(face.getModX() * 1.5, 0, face.getModZ() * 1.5);
        loc.setYaw(faceToYaw(face));
        for (int i = 0; i < 4; i++) {
            Material feet = loc.getBlock().getType();
            Material head = loc.clone().add(0, 1, 0).getBlock().getType();
            if (feet != Material.NETHER_PORTAL && head != Material.NETHER_PORTAL) {
                break;
            }
            loc.add(face.getModX(), 0, face.getModZ());
        }
        return loc;
    }

    public static Location cartWarpLocation(Block signBlock) {
        return cartWarpLocation(signBlock, DEFAULT_RADIUS);
    }

    public static Location cartWarpLocation(Block signBlock, int maxRadius) {
        return cartWarpLocation(signBlock, maxRadius, Integer.MAX_VALUE);
    }

    public static Location cartWarpLocation(Block signBlock, int maxRadius, int maxInterior) {
        Location warp = warpLocation(signBlock, maxRadius, maxInterior).clone();
        if (!(signBlock.getBlockData() instanceof WallSign sign)) {
            return warp;
        }
        switch (sign.getFacing()) {
            case NORTH -> warp.setZ(warp.getZ() - 0.5);
            case EAST -> warp.setX(warp.getX() + 0.5);
            case SOUTH -> warp.setZ(warp.getZ() + 0.5);
            case WEST -> warp.setX(warp.getX() - 0.5);
            default -> {
            }
        }
        return warp;
    }

    public static float faceToYaw(BlockFace face) {
        return switch (face) {
            case SOUTH -> 0F;
            case WEST -> 90F;
            case NORTH -> 180F;
            case EAST -> 270F;
            default -> 0F;
        };
    }

    public static boolean looksLikeColorPortalSign(Block signBlock) {
        return looksLikeColorPortalSign(signBlock, DEFAULT_RADIUS);
    }

    public static boolean looksLikeColorPortalSign(Block signBlock, int maxRadius) {
        return looksLikeColorPortalSign(signBlock, maxRadius, Integer.MAX_VALUE);
    }

    public static boolean looksLikeColorPortalSign(Block signBlock, int maxRadius, int maxInterior) {
        if (!(signBlock.getBlockData() instanceof WallSign sign)) {
            return false;
        }
        Block key = signBlock.getRelative(sign.getFacing().getOppositeFace());
        return isWool(key.getType()) && frameIsComplete(signBlock, maxRadius, maxInterior);
    }

    public static String colorPermKey(DyeColor color) {
        return color.name().toLowerCase(Locale.ROOT);
    }
}
