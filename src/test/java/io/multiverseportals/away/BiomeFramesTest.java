package io.multiverseportals.away;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BiomeFramesTest {

    @Test
    void plainsWantsOakLog() {
        assertEquals(Material.OAK_LOG, BiomeFrames.materialFor("plains"));
        assertEquals(Material.SANDSTONE, BiomeFrames.materialFor("desert"));
        assertEquals(Material.PACKED_ICE, BiomeFrames.materialFor("ice_spikes"));
    }

    @Test
    void blockLangKeyIsMinecraftBlock() {
        assertEquals("block.minecraft.oak_log", BiomeFrames.blockLangKey(Material.OAK_LOG));
        assertEquals("biome.minecraft.desert", BiomeFrames.biomeLangKey("desert"));
    }

    @Test
    void strippedLogMatchesSpecies() {
        assertTrue(BiomeFrames.matches(Material.STRIPPED_OAK_LOG, "plains"));
        assertTrue(BiomeFrames.matches(Material.OAK_WOOD, "plains"));
    }
}
