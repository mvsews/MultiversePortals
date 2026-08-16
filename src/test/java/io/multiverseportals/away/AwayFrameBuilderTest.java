package io.multiverseportals.away;

import org.bukkit.block.BlockFace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AwayFrameBuilderTest {

    @Test
    void rightJambIsOnTheViewersRight() {
        assertEquals(3, AwayFrameBuilder.rightWidthIndex(BlockFace.SOUTH, 4));
        assertEquals(0, AwayFrameBuilder.rightWidthIndex(BlockFace.NORTH, 4));
        assertEquals(0, AwayFrameBuilder.rightWidthIndex(BlockFace.EAST, 4));
        assertEquals(3, AwayFrameBuilder.rightWidthIndex(BlockFace.WEST, 4));
    }

    @Test
    void feetFromRightJambSignRoundtrip() {
        BlockFace south = BlockFace.SOUTH;
        int x0 = 10;
        int y0 = 64;
        int z0 = 20;
        int right = AwayFrameBuilder.rightWidthIndex(south, 4);
        int sx = x0 + right;
        int sy = y0 + 2;
        int sz = z0 + south.getModZ();
        int[] feet = AwayFrameBuilder.feetFromSign(sx, sy, sz, south);
        assertEquals(x0, feet[0]);
        assertEquals(y0, feet[1]);
        assertEquals(z0, feet[2]);
    }
}
