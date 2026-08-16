package io.multiverseportals.i18n;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EffectsCopyTest {

    @Test
    void oldPlateCopyIsStale() {
        assertTrue(EffectsCopy.isStaleOverride("<gray>Не сходи с плиты…</gray>"));
        assertTrue(EffectsCopy.isStaleOverride("<aqua>Портал</aqua>"));
        assertTrue(EffectsCopy.isStaleOverride("<gray>Stay on the plate…</gray>"));
        assertTrue(EffectsCopy.isStaleOverride("don't move"));
        assertTrue(EffectsCopy.isStaleOverride("Finding a world"));
    }

    @Test
    void blankAndCustomAreNotStale() {
        assertFalse(EffectsCopy.isStaleOverride(""));
        assertFalse(EffectsCopy.isStaleOverride("   "));
        assertFalse(EffectsCopy.isStaleOverride("<aqua>%dest%</aqua>"));
        assertFalse(EffectsCopy.isStaleOverride("<gold>Honeyed Island</gold>"));
    }
}
