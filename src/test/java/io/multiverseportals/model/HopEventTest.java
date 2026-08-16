package io.multiverseportals.model;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HopEventTest {

    @Test
    void outcomeMapsKind() {
        assertEquals("OK", HopEvent.outcomeOf(PeerEdge.Kind.ARRIVED));
        assertEquals("OK", HopEvent.outcomeOf(PeerEdge.Kind.DEPARTED));
        assertEquals("BOUNCED", HopEvent.outcomeOf(PeerEdge.Kind.FAILED));
        assertEquals("REFUSED", HopEvent.outcomeOf(PeerEdge.Kind.REJECTED));
    }

    @Test
    void jsonRoundTrip() {
        HopEvent e = new HopEvent(
                "evt-1", "server-a", "uuid-1", "Steve",
                "server-a", "server-b", "1.2.3.4", 25565,
                "BOUNCED", "FAILED", 99L);
        JsonObject o = e.toJson();
        assertFalse(o.get("ok").getAsBoolean());
        assertEquals("Steve", o.get("player").getAsString());
        assertEquals("server-a", o.get("from").getAsString());
        assertEquals("server-b", o.get("to").getAsString());
        HopEvent back = HopEvent.fromJson(o);
        assertNotNull(back);
        assertEquals("evt-1", back.id());
        assertEquals("BOUNCED", back.outcome());
        assertEquals(25565, back.toPort());
        assertTrue(back.at() == 99L);
    }
}
