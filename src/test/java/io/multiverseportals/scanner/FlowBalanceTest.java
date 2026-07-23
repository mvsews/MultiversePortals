package io.multiverseportals.scanner;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowBalanceTest {

    private static final List<Integer> FIXTURE = List.of(0, 2, 8, 20, 90, 500);

    @Test
    void disabledKeepsInputOrder() {
        List<Integer> ranked = FlowBalance.rankDestOnlines(20, FIXTURE, FlowBalance.Weights.disabled());
        assertEquals(FIXTURE, ranked);
    }

    @Test
    void busyOriginPrefersQuieterAndPenalizesMega() {
        List<Integer> ranked = FlowBalance.rankDestOnlines(20, FIXTURE, FlowBalance.Weights.defaults());
        // Busy origin: quieter ahead of mega; empty still better than Hypixel-class
        assertTrue(ranked.indexOf(2) < ranked.indexOf(90));
        assertTrue(ranked.indexOf(8) < ranked.indexOf(500));
        assertTrue(ranked.indexOf(0) < ranked.indexOf(500));
        assertEquals(500, ranked.get(ranked.size() - 1));
    }

    @Test
    void quietOriginPrefersModerateBand() {
        List<Integer> ranked = FlowBalance.rankDestOnlines(2, FIXTURE, FlowBalance.Weights.defaults());
        // Sweet spot ~3..25; mid-band (8, 20) above empty and mega
        assertTrue(ranked.indexOf(8) < ranked.indexOf(0));
        assertTrue(ranked.indexOf(20) < ranked.indexOf(90));
        assertTrue(ranked.indexOf(8) < ranked.indexOf(500));
    }

    @Test
    void midOriginKeepsMegaLast() {
        List<Integer> ranked = FlowBalance.rankDestOnlines(7, FIXTURE, FlowBalance.Weights.defaults());
        assertEquals(500, ranked.get(ranked.size() - 1));
        assertTrue(ranked.indexOf(8) < ranked.indexOf(90));
    }

    @Test
    void emptyAllowedWhenTunedForQuiet() {
        FlowBalance.Weights w = new FlowBalance.Weights(
                true,
                12,
                4,
                80,
                80.0,
                0.5,
                0.0, // no empty penalty
                false,
                0, // empty is inside quiet band
                25,
                1.0,
                1.0,
                2.0
        );
        List<Integer> ranked = FlowBalance.rankDestOnlines(2, FIXTURE, w);
        assertTrue(ranked.indexOf(0) < ranked.indexOf(90));
        assertTrue(ranked.indexOf(0) < ranked.indexOf(500));
        // Empty can sit in the preferred band now
        double empty = FlowBalance.score(2, 0, w);
        double mega = FlowBalance.score(2, 500, w);
        assertTrue(empty > mega);
    }

    @Test
    void rejectMegaOnlyWhenHardCapOn() {
        FlowBalance.Weights soft = FlowBalance.Weights.defaults();
        assertTrue(!FlowBalance.rejectMega(500, soft));

        FlowBalance.Weights hard = new FlowBalance.Weights(
                true, soft.busyAt(), soft.quietAt(), soft.megaOnline(),
                soft.megaPenalty(), soft.megaPenaltyPerExtra(), soft.emptyPenalty(),
                true, soft.quietTargetMin(), soft.quietTargetMax(),
                soft.busyPreferQuietWeight(), soft.quietPreferBandWeight(), soft.epsilon()
        );
        assertTrue(FlowBalance.rejectMega(500, hard));
        assertTrue(!FlowBalance.rejectMega(20, hard));
    }
}
