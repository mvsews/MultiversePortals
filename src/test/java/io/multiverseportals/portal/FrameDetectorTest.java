package io.multiverseportals.portal;

import org.bukkit.Axis;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Packed 2×3 rings that share a 1-block pillar must stay separate holes.
 */
class FrameDetectorTest {

    /**
     * Row 0 is the top (highest Y). {@code #} solid, {@code .} air, {@code S} sign on solid.
     */
    private static final String[] PACKED = {
            "#########",
            "##S##S###",
            "#..#..###",
            "#..#..###",
            "#..#..###",
            "#########"
    };

    @Test
    void signOnRightPillarFindsTwoByThree() {
        String[] grid = {
                "####",
                "#..#",
                "#..S",
                "#..#",
                "####"
        };
        FrameDetector.PlaneResult scan = scanAt(grid, 'S');
        assertTrue(scan.closed());
        assertEquals(6, scan.interior().size());
        assertTrue(covers(scan, 1, 1, 2, 3));
    }

    @Test
    void singleTwoByThreeIsClosed() {
        String[] grid = {
                "####",
                "#S##",
                "#..#",
                "#..#",
                "#..#",
                "####"
        };
        FrameDetector.PlaneResult scan = scanAt(grid, 'S');
        assertTrue(scan.closed());
        assertEquals(6, scan.interior().size());
    }

    @Test
    void standingOnLintelIsNotInsideOpening() {
        String[] grid = {
                "####",
                "#..#",
                "#..S",
                "#..#",
                "####"
        };
        FrameDetector.PlaneResult scan = scanAt(grid, 'S');
        assertTrue(scan.closed());
        assertTrue(FrameDetector.standingInOpening(scan.interior(), 1, 2, 0));
        int lintelY = 0;
        for (FrameDetector.Coord c : scan.interior()) {
            lintelY = Math.max(lintelY, c.y() + 1);
        }
        assertFalse(FrameDetector.standingInOpening(scan.interior(), 1, lintelY, 0));
        assertFalse(FrameDetector.standingInOpening(scan.interior(), 1, lintelY + 1, 0));
    }

    @Test
    void packedPortalsEachOwnSixCells() {
        FrameDetector.PlaneResult left = scanAt(PACKED, 2, 4);
        FrameDetector.PlaneResult right = scanAt(PACKED, 5, 4);
        assertTrue(left.closed());
        assertTrue(right.closed());
        assertEquals(6, left.interior().size());
        assertEquals(6, right.interior().size());
        assertTrue(disjoint(left, right));
        assertTrue(covers(left, 1, 1, 2, 3));
        assertTrue(covers(right, 4, 1, 2, 3));
    }

    @Test
    void signOnSharedPillarPicksOneHoleNotBoth() {
        String[] grid = {
                "#########",
                "###S#####",
                "#..#..###",
                "#..#..###",
                "#..#..###",
                "#########"
        };
        FrameDetector.PlaneResult scan = scanAt(grid, 'S');
        assertTrue(scan.closed());
        assertEquals(6, scan.interior().size());
        boolean left = covers(scan, 1, 1, 2, 3);
        boolean right = covers(scan, 4, 1, 2, 3);
        assertTrue(left ^ right, "must pick exactly one opening");
    }

    @Test
    void openGapIsNotClosed() {
        String[] grid = {
                "#####",
                "#S###",
                "#....",
                "#....",
                "#....",
                "#####"
        };
        FrameDetector.PlaneResult scan = scanAt(grid, 'S');
        assertFalse(scan.closed());
    }

    @Test
    void tinyPeepholeDoesNotStealTwoByThree() {
        String[] grid = {
                "######",
                "#.S###",
                "#...##",
                "##..##",
                "##..##",
                "######"
        };
        FrameDetector.PlaneResult scan = scanAt(grid, 'S');
        assertTrue(scan.closed());
        assertTrue(scan.interior().size() >= 6);
    }

    private static FrameDetector.PlaneResult scanAt(String[] rows, char mark) {
        int[] s = find(rows, mark);
        return scanAt(rows, s[0], s[1]);
    }

    private static FrameDetector.PlaneResult scanAt(String[] rows, int sx, int sy) {
        int h = rows.length;
        int w = rows[0].length();
        FrameDetector.CellFn passable = (x, y, z) -> z == 0 && (!in(w, h, x, y) || cell(rows, x, y) == '.');
        FrameDetector.CellFn solid = (x, y, z) -> z == 0 && in(w, h, x, y)
                && (cell(rows, x, y) == '#' || cell(rows, x, y) == 'S');
        return FrameDetector.scanPlane(sx, sy, 0, sx, sy, 0, 24, Axis.X, passable, solid);
    }

    private static boolean covers(FrameDetector.PlaneResult scan, int x, int y, int w, int h) {
        Set<String> have = new HashSet<>();
        for (FrameDetector.Coord c : scan.interior()) {
            have.add(c.x() + "," + c.y());
        }
        for (int dx = 0; dx < w; dx++) {
            for (int dy = 0; dy < h; dy++) {
                if (!have.contains((x + dx) + "," + (y + dy))) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean disjoint(FrameDetector.PlaneResult a, FrameDetector.PlaneResult b) {
        Set<String> have = new HashSet<>();
        for (FrameDetector.Coord c : a.interior()) {
            have.add(c.x() + "," + c.y() + "," + c.z());
        }
        for (FrameDetector.Coord c : b.interior()) {
            if (have.contains(c.x() + "," + c.y() + "," + c.z())) {
                return false;
            }
        }
        return true;
    }

    private static int[] find(String[] rows, char mark) {
        int h = rows.length;
        for (int y = 0; y < h; y++) {
            int gy = h - 1 - y;
            String row = rows[y];
            for (int x = 0; x < row.length(); x++) {
                if (row.charAt(x) == mark) {
                    return new int[]{x, gy};
                }
            }
        }
        throw new IllegalArgumentException("mark not found");
    }

    private static char cell(String[] rows, int x, int y) {
        int row = rows.length - 1 - y;
        return rows[row].charAt(x);
    }

    private static boolean in(int w, int h, int x, int y) {
        return x >= 0 && y >= 0 && x < w && y < h;
    }
}
