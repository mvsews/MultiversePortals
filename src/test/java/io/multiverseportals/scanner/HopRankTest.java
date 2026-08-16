package io.multiverseportals.scanner;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HopRankTest {

    @Test
    void unknownIsNeutral() {
        assertEquals(0.0, HopRank.score(0, 0, 0, 0));
    }

    @Test
    void successesBeatBounces() {
        assertTrue(HopRank.score(8, 0, 0, 0) > HopRank.score(8, 4, 0, 0));
        assertTrue(HopRank.score(0, 0, 5, 0) > HopRank.score(0, 0, 5, 5));
    }

    @Test
    void bounceHeavyDestRanksBelowUnknown() {
        assertTrue(HopRank.score(0, 6, 0, 0) < 0.0);
        assertTrue(HopRank.score(1, 6, 0, 0) < HopRank.score(0, 0, 0, 0));
    }

    @Test
    void reliableDestBeatsUnknown() {
        assertTrue(HopRank.score(12, 1, 4, 0) > 0.0);
    }
}
