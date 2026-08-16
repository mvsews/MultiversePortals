package io.multiverseportals.travel;

import org.junit.jupiter.api.Test;

import static io.multiverseportals.travel.TransferSettle.Outcome.ACCEPT;
import static io.multiverseportals.travel.TransferSettle.Outcome.REFUSE;
import static org.junit.jupiter.api.Assertions.assertEquals;

class TransferSettleTest {

    @Test
    void noPortalStayAwayIsAccept() {
        assertEquals(ACCEPT, TransferSettle.decide(false, false, null, null));
    }

    @Test
    void noPortalComeBackIsRefuse() {
        assertEquals(REFUSE, TransferSettle.decide(false, true, null, null));
    }

    @Test
    void destPortalArrivedEvenIfPlayerCameHome() {
        assertEquals(ACCEPT, TransferSettle.decide(true, true, "ARRIVED", null));
        assertEquals(ACCEPT, TransferSettle.decide(true, true, null, "OK"));
    }

    @Test
    void destPortalRejected() {
        assertEquals(REFUSE, TransferSettle.decide(true, true, "PENDING", "REFUSED"));
        assertEquals(REFUSE, TransferSettle.decide(true, false, "BOUNCED", null));
    }

    @Test
    void destPortalNeverClaimedAndPlayerBackIsRefuse() {
        assertEquals(REFUSE, TransferSettle.decide(true, true, "PENDING", null));
    }

    @Test
    void destPortalNeverClaimedButPlayerStayedIsAccept() {
        assertEquals(ACCEPT, TransferSettle.decide(true, false, "PENDING", null));
    }
}
