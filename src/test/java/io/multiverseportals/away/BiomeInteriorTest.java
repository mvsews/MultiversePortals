package io.multiverseportals.away;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BiomeInteriorTest {

    /** Rectangle [0,200]×[0,200]. Locate hits the west edge. */
    @Test
    void westEdgeWalksToCenter() {
        BiomeInterior.InBiome box = (x, z) -> x >= 0 && x <= 200 && z >= 0 && z <= 200;
        int[] c = BiomeInterior.centerXz(-500, 100, 0, 100, box, 16, 2048);
        assertTrue(Math.abs(c[0] - 100) <= 16, "x=" + c[0]);
        assertTrue(Math.abs(c[1] - 100) <= 16, "z=" + c[1]);
    }

    @Test
    void cornerSeedStillInterior() {
        BiomeInterior.InBiome box = (x, z) -> x >= 0 && x <= 200 && z >= 0 && z <= 200;
        int[] c = BiomeInterior.centerXz(-10, -10, 0, 0, box, 16, 2048);
        assertTrue(c[0] >= 80 && c[0] <= 120, "x=" + c[0]);
        assertTrue(c[1] >= 80 && c[1] <= 120, "z=" + c[1]);
    }

    @Test
    void tinyBiomeStaysPut() {
        BiomeInterior.InBiome cell = (x, z) -> x == 50 && z == 60;
        int[] c = BiomeInterior.centerXz(0, 0, 50, 60, cell, 32, 2048);
        assertEquals(50, c[0]);
        assertEquals(60, c[1]);
    }

    @Test
    void extentCapStopsBeforeFarShore() {
        BiomeInterior.InBiome huge = (x, z) -> x >= 0 && x <= 8000 && z >= 0 && z <= 200;
        int[] c = BiomeInterior.centerXz(-100, 100, 0, 100, huge, 32, 512);
        // west=0, east=capped ~512, center ~256 — inland, not the edge
        assertTrue(c[0] >= 200 && c[0] <= 320, "x=" + c[0]);
        assertTrue(Math.abs(c[1] - 100) <= 32, "z=" + c[1]);
    }
}
