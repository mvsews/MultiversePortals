package io.multiverseportals.away;

import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.block.Biome;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Display color for a biome name on the portal sign. */
public final class BiomeColors {

    private static final Map<String, TextColor> BY_KEY = new HashMap<>();

    static {
        put("plains", 0x91BD59);
        put("sunflower_plains", 0xC5D63C);
        put("forest", 0x056621);
        put("flower_forest", 0x2D8A4E);
        put("birch_forest", 0xC8D4A8);
        put("old_growth_birch_forest", 0xC8D4A8);
        put("dark_forest", 0x1B5C2A);
        put("taiga", 0x2B6658);
        put("old_growth_pine_taiga", 0x2B6658);
        put("old_growth_spruce_taiga", 0x2B6658);
        put("snowy_taiga", 0xC8DCE8);
        put("savanna", 0xBFA44A);
        put("savanna_plateau", 0xBFA44A);
        put("windswept_savanna", 0xA8883A);
        put("jungle", 0x30B33A);
        put("sparse_jungle", 0x4CC24A);
        put("bamboo_jungle", 0x6BCB3C);
        put("swamp", 0x6A7039);
        put("mangrove_swamp", 0x4A7A5A);
        put("desert", 0xD4C07A);
        put("badlands", 0xC86020);
        put("eroded_badlands", 0xD07030);
        put("wooded_badlands", 0xA05020);
        put("beach", 0xFADE9A);
        put("snowy_beach", 0xE8F0F4);
        put("stony_shore", 0x888888);
        put("ocean", 0x3F76E4);
        put("deep_ocean", 0x2A5BBF);
        put("warm_ocean", 0x43D5EE);
        put("lukewarm_ocean", 0x45ADF2);
        put("cold_ocean", 0x3D57D6);
        put("frozen_ocean", 0x74A5F0);
        put("deep_warm_ocean", 0x2BB8D0);
        put("deep_lukewarm_ocean", 0x2F8FD4);
        put("deep_cold_ocean", 0x2B45C0);
        put("deep_frozen_ocean", 0x5A90E0);
        put("river", 0x3F76E4);
        put("frozen_river", 0xA0C8F0);
        put("mushroom_fields", 0xC080C0);
        put("ice_spikes", 0xD0F0FF);
        put("frozen_peaks", 0xE8F4FF);
        put("jagged_peaks", 0xC8C8C8);
        put("stony_peaks", 0x888888);
        put("grove", 0x80A0A8);
        put("snowy_slopes", 0xE0EEF4);
        put("snowy_plains", 0xE8F0F4);
        put("meadow", 0x83BB6D);
        put("cherry_grove", 0xF2B6C6);
        put("pale_garden", 0xB8C4A8);
        put("dripstone_caves", 0xA07858);
        put("lush_caves", 0x4AAA6A);
        put("deep_dark", 0x0D2B2B);
        put("windswept_hills", 0x8A8A8A);
        put("windswept_forest", 0x6A8A6A);
        put("windswept_gravelly_hills", 0x7A7A7A);
    }

    private BiomeColors() {}

    private static void put(String key, int rgb) {
        BY_KEY.put(key, TextColor.color(rgb));
    }

    public static TextColor of(Biome biome) {
        return of(BiomeFrames.keyOf(biome));
    }

    public static TextColor of(String biomeKey) {
        if (biomeKey == null || biomeKey.isBlank()) {
            return NamedTextColor.GREEN;
        }
        TextColor c = BY_KEY.get(biomeKey.toLowerCase(Locale.ROOT));
        return c != null ? c : NamedTextColor.GREEN;
    }

    public static String prettyName(String biomeKey) {
        if (biomeKey == null || biomeKey.isBlank()) {
            return "?";
        }
        String[] parts = biomeKey.toLowerCase(Locale.ROOT).split("_");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append(Character.toUpperCase(p.charAt(0)));
            if (p.length() > 1) {
                sb.append(p.substring(1));
            }
        }
        return sb.toString();
    }
}
