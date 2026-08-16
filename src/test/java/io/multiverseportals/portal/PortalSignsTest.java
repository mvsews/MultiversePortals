package io.multiverseportals.portal;

import io.multiverseportals.model.Portal;
import io.multiverseportals.model.PortalFrame;
import io.multiverseportals.model.PortalStatus;
import io.multiverseportals.model.PortalType;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PortalSignsTest {

    @Test
    void boundMultiShowsDestEvenWhileStatusIsNotActive() {
        Portal p = new Portal(
                "p1",
                PortalType.MULTI,
                PortalStatus.BINDING,
                new PortalFrame("world", 0, 64, 0, "", 0f),
                "multi",
                UUID.randomUUID()
        );
        assertFalse(PortalSigns.hasVisibleDestination(p));
        p.setBoundHost("play.example.com");
        p.setBoundPort(25565);
        assertTrue(PortalSigns.hasVisibleDestination(p));
        assertFalse(PortalSigns.isReady(p));
    }
}
