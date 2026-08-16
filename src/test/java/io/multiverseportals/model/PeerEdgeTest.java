package io.multiverseportals.model;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class PeerEdgeTest {

    @Test
    void keyPrefersServerId() {
        assertEquals("id:alpha", PeerEdge.key("Alpha", "1.2.3.4", 25565));
        assertEquals("id:alpha", PeerEdge.key(" alpha ", null, 0));
    }

    @Test
    void keyFallsBackToHostPort() {
        assertEquals("hp:play.example:25565", PeerEdge.key(null, "Play.Example", 25565));
        assertEquals("hp:play.example:25565", PeerEdge.key("  ", "Play.Example", 25565));
    }

    @Test
    void jsonRoundTripKeepsOutcomeBreakdown() {
        PeerEdge e = new PeerEdge("alpha", "1.2.3.4", 25565, 3, 2, 1, 4, 1, 123L);
        JsonObject o = e.toJson();
        assertEquals(1, o.get("sentOk").getAsInt());
        assertEquals(4, o.get("bounced").getAsInt());
        assertEquals(2, o.get("receivedOk").getAsInt());
        assertEquals(1, o.get("refused").getAsInt());
        PeerEdge back = PeerEdge.fromJson(o);
        assertNotNull(back);
        assertEquals("alpha", back.peerServerId());
        assertEquals(2, back.arrived());
        assertEquals(1, back.departed());
        assertEquals(4, back.failed());
        assertEquals(1, back.rejected());
    }
}
