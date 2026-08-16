package io.multiverseportals.away;

import org.bukkit.Material;
import org.bukkit.Registry;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Frame material for an overworld biome (logs where trees exist, else a characteristic block). */
public final class BiomeFrames {

    private static final Map<String, Material> BY_KEY = new HashMap<>();

    static {
        map(Material.OAK_LOG,
                "plains", "sunflower_plains", "forest", "flower_forest", "meadow",
                "swamp", "wooded_badlands");
        map(Material.BIRCH_LOG, "birch_forest", "old_growth_birch_forest");
        map(Material.SPRUCE_LOG,
                "taiga", "snowy_taiga", "old_growth_pine_taiga", "old_growth_spruce_taiga",
                "grove", "snowy_plains", "snowy_slopes", "snowy_beach");
        map(Material.ACACIA_LOG, "savanna", "savanna_plateau", "windswept_savanna");
        map(Material.JUNGLE_LOG, "jungle", "sparse_jungle");
        map(Material.BAMBOO_BLOCK, "bamboo_jungle");
        map(Material.DARK_OAK_LOG, "dark_forest");
        map(Material.CHERRY_LOG, "cherry_grove");
        map(Material.PALE_OAK_LOG, "pale_garden");
        map(Material.MANGROVE_LOG, "mangrove_swamp");
        map(Material.SANDSTONE, "desert");
        map(Material.RED_SANDSTONE, "badlands", "eroded_badlands");
        map(Material.RED_SAND, "warm_ocean", "deep_warm_ocean", "lukewarm_ocean", "deep_lukewarm_ocean");
        map(Material.SAND,
                "beach", "ocean", "deep_ocean", "cold_ocean", "deep_cold_ocean",
                "river", "stony_shore");
        map(Material.PACKED_ICE,
                "frozen_ocean", "deep_frozen_ocean", "frozen_river", "ice_spikes", "frozen_peaks");
        map(Material.MUSHROOM_STEM, "mushroom_fields");
        map(Material.STONE, "stony_peaks", "windswept_hills", "windswept_forest", "windswept_gravelly_hills");
        map(Material.COBBLESTONE, "jagged_peaks");
        map(Material.DRIPSTONE_BLOCK, "dripstone_caves");
        map(Material.MOSS_BLOCK, "lush_caves");
        map(Material.DEEPSLATE, "deep_dark");
    }

    private BiomeFrames() {}

    private static void map(Material material, String... keys) {
        for (String k : keys) {
            BY_KEY.put(k, material);
        }
    }

    public static String keyOf(Biome biome) {
        if (biome == null) {
            return "plains";
        }
        try {
            var keyed = Registry.BIOME.getKey(biome);
            if (keyed != null) {
                return keyed.getKey().toLowerCase(Locale.ROOT);
            }
        } catch (Throwable ignored) {
        }
        try {
            return biome.getKey().getKey().toLowerCase(Locale.ROOT);
        } catch (Throwable ignored) {
        }
        return biome.toString().toLowerCase(Locale.ROOT);
    }

    public static Material materialFor(Biome biome) {
        return materialFor(keyOf(biome));
    }

    public static Material materialFor(String biomeKey) {
        if (biomeKey == null || biomeKey.isBlank()) {
            return Material.OAK_LOG;
        }
        Material m = BY_KEY.get(biomeKey.toLowerCase(Locale.ROOT));
        return m != null ? m : Material.OAK_LOG;
    }

    public static boolean matches(Material block, Biome biome) {
        return matches(block, keyOf(biome));
    }

    public static boolean matches(Material block, String biomeKey) {
        if (block == null) {
            return false;
        }
        Material want = materialFor(biomeKey);
        if (block == want) {
            return true;
        }
        // stripped / wood variants of the same species
        String a = block.name();
        String b = want.name();
        if (b.endsWith("_LOG") && (a.equals(b.replace("_LOG", "_WOOD"))
                || a.equals("STRIPPED_" + b)
                || a.equals("STRIPPED_" + b.replace("_LOG", "_WOOD")))) {
            return true;
        }
        if (want == Material.SANDSTONE && (block == Material.SMOOTH_SANDSTONE || block == Material.CUT_SANDSTONE)) {
            return true;
        }
        if (want == Material.RED_SANDSTONE && (block == Material.SMOOTH_RED_SANDSTONE || block == Material.CUT_RED_SANDSTONE)) {
            return true;
        }
        return false;
    }

    /** True if a majority of nearby solid blocks (behind/around the sign) match this biome. */
    public static boolean frameLooksLikeBiome(Block sign, Biome biome) {
        if (sign == null || biome == null) {
            return false;
        }
        org.bukkit.block.BlockFace into = io.multiverseportals.portal.FrameDetector.facingOf(sign).getOppositeFace();
        Block key = sign.getRelative(into);
        if (matches(key.getType(), biome)) {
            return true;
        }
        int hit = 0;
        int solid = 0;
        for (int dy = -1; dy <= 4; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                Block b = axisRelative(key, into, dx, dy);
                if (!b.getType().isSolid() || b.equals(sign)) {
                    continue;
                }
                solid++;
                if (matches(b.getType(), biome)) {
                    hit++;
                }
            }
        }
        return solid >= 4 && hit * 2 >= solid;
    }

    private static Block axisRelative(Block key, org.bukkit.block.BlockFace into, int across, int dy) {
        boolean xAxis = into == org.bukkit.block.BlockFace.NORTH || into == org.bukkit.block.BlockFace.SOUTH;
        if (xAxis) {
            return key.getWorld().getBlockAt(key.getX() + across, key.getY() + dy, key.getZ());
        }
        return key.getWorld().getBlockAt(key.getX(), key.getY() + dy, key.getZ() + across);
    }

    /** Most common solid block around the sign (the actual frame). */
    public static Material majorityFrameMaterial(Block sign) {
        if (sign == null) {
            return null;
        }
        org.bukkit.block.BlockFace into = io.multiverseportals.portal.FrameDetector.facingOf(sign).getOppositeFace();
        Block key = sign.getRelative(into);
        Map<Material, Integer> counts = new HashMap<>();
        for (int dy = -1; dy <= 4; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                Block b = axisRelative(key, into, dx, dy);
                if (!b.getType().isSolid() || b.equals(sign)) {
                    continue;
                }
                counts.merge(b.getType(), 1, Integer::sum);
            }
        }
        Material best = null;
        int n = 0;
        for (var e : counts.entrySet()) {
            if (e.getValue() > n) {
                n = e.getValue();
                best = e.getKey();
            }
        }
        return best;
    }

    public static String blockLangKey(Material material) {
        if (material == null) {
            return "block.minecraft.oak_log";
        }
        try {
            return "block.minecraft." + material.getKey().getKey();
        } catch (Throwable ignored) {
            return "block.minecraft." + material.name().toLowerCase(Locale.ROOT);
        }
    }

    public static String biomeLangKey(String biomeKey) {
        if (biomeKey == null || biomeKey.isBlank()) {
            return "biome.minecraft.plains";
        }
        return "biome.minecraft." + biomeKey.toLowerCase(Locale.ROOT);
    }

    public static String biomeLangKey(Biome biome) {
        return biomeLangKey(keyOf(biome));
    }

    public static boolean isNetherOrEnd(String key) {
        if (key == null) {
            return false;
        }
        String k = key.toLowerCase(Locale.ROOT);
        return k.contains("nether") || k.contains("the_end") || k.contains("end_")
                || k.equals("basalt_deltas") || k.equals("crimson_forest")
                || k.equals("soul_sand_valley") || k.equals("warped_forest")
                || k.equals("nether_wastes") || k.equals("small_end_islands")
                || k.equals("end_barrens") || k.equals("end_highlands") || k.equals("end_midlands");
    }
}
