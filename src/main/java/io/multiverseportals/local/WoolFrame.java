package io.multiverseportals.local;

import org.bukkit.DyeColor;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.WallSign;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** ColorPortals-compatible wool frame (3×4 outline, wall-sign on top mid). */
public final class WoolFrame {

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

    public static boolean frameIsComplete(Block signBlock) {
        if (!(signBlock.getBlockData() instanceof WallSign sign)) {
            return false;
        }
        Block keyBlock = signBlock.getRelative(sign.getFacing().getOppositeFace());
        DyeColor color = woolColor(keyBlock);
        if (color == null) {
            return false;
        }
        for (Block b : frameBlocks(signBlock)) {
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

    /** All 10 wool blocks of the frame (not sign / plate / button). */
    public static List<Block> frameBlocks(Block signBlock) {
        List<Block> out = new ArrayList<>(10);
        if (!(signBlock.getBlockData() instanceof WallSign sign)) {
            return out;
        }
        Block keyBlock = signBlock.getRelative(sign.getFacing().getOppositeFace());
        BlockFace travel = (sign.getFacing() == BlockFace.NORTH || sign.getFacing() == BlockFace.SOUTH)
                ? BlockFace.EAST : BlockFace.NORTH;

        Block topLeft = keyBlock.getRelative(travel);
        Block topRight = keyBlock.getRelative(travel.getOppositeFace());
        Block leftMid = topLeft.getRelative(BlockFace.DOWN);
        Block rightMid = topRight.getRelative(BlockFace.DOWN);
        Block lowerLeft = leftMid.getRelative(BlockFace.DOWN);
        Block lowerRight = rightMid.getRelative(BlockFace.DOWN);
        Block bottomLeft = lowerLeft.getRelative(BlockFace.DOWN);
        Block bottomRight = lowerRight.getRelative(BlockFace.DOWN);
        Block bottomMid = keyBlock.getRelative(BlockFace.DOWN).getRelative(BlockFace.DOWN).getRelative(BlockFace.DOWN);

        out.add(keyBlock);
        out.add(topLeft);
        out.add(topRight);
        out.add(leftMid);
        out.add(rightMid);
        out.add(lowerLeft);
        out.add(lowerRight);
        out.add(bottomLeft);
        out.add(bottomRight);
        out.add(bottomMid);
        return out;
    }

    /** Sign + 10 wool + inner mid/upper/lower (occupied volume for break detection). */
    public static List<Block> occupiedBlocks(Block signBlock) {
        List<Block> out = new ArrayList<>(14);
        out.add(signBlock);
        if (!(signBlock.getBlockData() instanceof WallSign sign)) {
            return out;
        }
        Block midTop = signBlock.getRelative(sign.getFacing().getOppositeFace());
        Block midUpper = midTop.getRelative(BlockFace.DOWN);
        Block midLower = midUpper.getRelative(BlockFace.DOWN);
        Block midBottom = midLower.getRelative(BlockFace.DOWN);
        BlockFace travel = (sign.getFacing() == BlockFace.NORTH || sign.getFacing() == BlockFace.SOUTH)
                ? BlockFace.EAST : BlockFace.NORTH;
        out.add(midTop);
        out.add(midUpper);
        out.add(midLower);
        out.add(midBottom);
        out.add(midTop.getRelative(travel));
        out.add(midUpper.getRelative(travel));
        out.add(midLower.getRelative(travel));
        out.add(midBottom.getRelative(travel));
        out.add(midTop.getRelative(travel.getOppositeFace()));
        out.add(midUpper.getRelative(travel.getOppositeFace()));
        out.add(midLower.getRelative(travel.getOppositeFace()));
        out.add(midBottom.getRelative(travel.getOppositeFace()));
        return out;
    }

    public static org.bukkit.Location warpLocation(Block signBlock) {
        if (!(signBlock.getBlockData() instanceof WallSign sign)) {
            return signBlock.getLocation().add(0.5, 0, 0.5);
        }
        org.bukkit.Location warp = signBlock
                .getRelative(sign.getFacing().getOppositeFace())
                .getRelative(BlockFace.DOWN)
                .getRelative(BlockFace.DOWN)
                .getLocation()
                .add(0.5, 0, 0.5);
        warp.setYaw(faceToYaw(sign.getFacing()) + 180F);
        return warp;
    }

    public static org.bukkit.Location cartWarpLocation(Block signBlock) {
        org.bukkit.Location warp = warpLocation(signBlock).clone();
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
        if (!(signBlock.getBlockData() instanceof WallSign sign)) {
            return false;
        }
        Block key = signBlock.getRelative(sign.getFacing().getOppositeFace());
        Block bottom = key.getRelative(BlockFace.DOWN).getRelative(BlockFace.DOWN).getRelative(BlockFace.DOWN);
        return isWool(key.getType()) && isWool(bottom.getType());
    }

    public static String colorPermKey(DyeColor color) {
        return color.name().toLowerCase(Locale.ROOT);
    }
}
