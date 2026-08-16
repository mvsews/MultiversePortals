package io.multiverseportals.scanner;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

class VanillaHeuristicTest {

    @Test
    void paperSurvivalCountsAsOrdinaryWorld() {
        assertTrue(VanillaHeuristic.looksVanilla("Paper", "Paper 1.21.4", "Survival SMP"));
        assertTrue(VanillaHeuristic.looksVanilla("Purpur", "git-Purpur-1234", "A world"));
        int paper = VanillaHeuristic.score("Paper", "Paper 1.21.4", "Survival");
        int vanillaJar = VanillaHeuristic.score("Vanilla", "1.21.4", "Survival");
        assertTrue(Math.abs(paper - vanillaJar) <= 10, "paper=" + paper + " vanillaJar=" + vanillaJar);
    }

    @Test
    void modsAndMinigamesAreNotOrdinary() {
        assertFalse(VanillaHeuristic.looksVanilla("Fabric", "fabric-loader 0.16", "Minecraft"));
        assertFalse(VanillaHeuristic.looksVanilla("Forge", "47.2", "Modded"));
        assertFalse(VanillaHeuristic.looksVanilla("Paper", "Paper 1.21.4", "Hypixel Network"));
        assertFalse(VanillaHeuristic.looksVanilla("Paper", "1.21.4", "Bedwars lobby"));
        assertFalse(VanillaHeuristic.looksVanilla("", "1.21.4", "Скайблок и мини-игры"));
    }

    @Test
    void skyblockAndSurvivalAreOrdinary() {
        assertTrue(VanillaHeuristic.looksVanilla("Paper", "1.21.4", "Skyblock season 8"));
        assertTrue(VanillaHeuristic.looksVanilla("Paper", "1.21.4", "Скайблок выживание"));
        assertTrue(VanillaHeuristic.looksVanilla("Purpur", "1.21.4", "Просто выживание"));
    }

    @Test
    void defaultMotdIsOrdinaryAndPreferred() {
        assertTrue(VanillaHeuristic.looksVanilla("Vanilla", "1.21.4", "A Minecraft Server"));
        assertTrue(VanillaHeuristic.looksVanilla("Paper", "1.21.4", "A Minecraft Server"));
        assertTrue(VanillaHeuristic.looksVanilla("Paper", "1.21.4", "§aA Minecraft Server"));
        int def = VanillaHeuristic.score("Paper", "1.21.4", "A Minecraft Server");
        int custom = VanillaHeuristic.score("Paper", "1.21.4", "Welcome to my cool world");
        assertTrue(def > custom, "default=" + def + " custom=" + custom);
        assertEquals(def, VanillaHeuristic.score("Paper", "1.21.4", "Survival SMP"));
        int hypixelDefault = VanillaHeuristic.score("Paper", "1.21.4", "A Minecraft Server Hypixel");
        assertEquals(12, hypixelDefault);
    }

    @Test
    void catalogSourceDoesNotHidePaperSurvival() {
        assertTrue(VanillaHeuristic.looksVanilla("cornbread", "Paper 1.21.4", "Survival"));
        assertTrue(VanillaHeuristic.looksVanilla("cornbread", "1.21.4", "Vanilla SMP"));
    }

    @Test
    void unknownListingStillBeatsModpack() {
        int unknown = VanillaHeuristic.score("", "Something custom", "");
        int pack = VanillaHeuristic.score("Fabric", "0.16", "ATM10");
        assertTrue(unknown >= VanillaHeuristic.VANILLA_LIKE);
        assertTrue(unknown > pack);
        assertEquals(8, pack);
    }
}
