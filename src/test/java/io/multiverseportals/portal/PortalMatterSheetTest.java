package io.multiverseportals.portal;

import org.bukkit.Location;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PortalMatterSheetTest {

    @Test
    void oneByTwoPeepholeUsesDisplays() {
        assertFalse(PortalMatter.fitsVanillaNetherSheet(rect(1, 2)));
    }

    @Test
    void vanillaTwoByThreeUsesRealBlocks() {
        assertTrue(PortalMatter.fitsVanillaNetherSheet(rect(2, 3)));
    }

    @Test
    void vanillaTwentyOneUsesRealBlocks() {
        assertTrue(PortalMatter.fitsVanillaNetherSheet(rect(21, 21)));
    }

    @Test
    void widerThanVanillaUsesDisplays() {
        assertFalse(PortalMatter.fitsVanillaNetherSheet(rect(22, 10)));
    }

    @Test
    void circleIsNotAVanillaRectangle() {
        List<Location> cells = new ArrayList<>();
        int r = 8;
        for (int y = -r; y <= r; y++) {
            for (int x = -r; x <= r; x++) {
                if (x * x + y * y <= r * r) {
                    cells.add(new Location(null, x, y, 0));
                }
            }
        }
        assertFalse(PortalMatter.fitsVanillaNetherSheet(cells));
    }

    private static List<Location> rect(int width, int height) {
        List<Location> cells = new ArrayList<>();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                cells.add(new Location(null, x, y, 0));
            }
        }
        return cells;
    }
}
