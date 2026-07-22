package io.multiverseportals.util;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;

public final class ShapeHasher {

    private ShapeHasher() {}

    public static String hashAround(Location signLoc) {
        StringBuilder sb = new StringBuilder();
        Block base = signLoc.getBlock();
        for (int dy = -1; dy <= 3; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                Block b = base.getRelative(dx, dy, 0);
                sb.append(dx).append(',').append(dy).append('=').append(b.getType().name()).append(';');
            }
        }
        return sha256(sb.toString());
    }

    public static boolean looksLikePortalSign(Block block) {
        if (!(block.getState() instanceof Sign sign)) {
            return false;
        }
        return parseType(plain(sign, 0)) != null;
    }

    public static String parseType(String line0) {
        if (line0 == null) {
            return null;
        }
        String t = line0.toLowerCase(Locale.ROOT).trim()
                .replace("[", "").replace("]", "");
        return switch (t) {
            case "multi", "random", "mvp", "portal" -> "multi";
            case "to", "goto", "server" -> "to";
            case "pair", "link" -> "pair";
            default -> null;
        };
    }

    public static String plain(Sign sign, int line) {
        try {
            return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                    .serialize(sign.line(line));
        } catch (Throwable t) {
            @SuppressWarnings("deprecation")
            String legacy = sign.getLine(line);
            return legacy == null ? "" : legacy;
        }
    }

    public static boolean isPressurePlate(Material m) {
        String n = m.name();
        return n.endsWith("_PRESSURE_PLATE") || m == Material.STONE_PRESSURE_PLATE
                || m == Material.LIGHT_WEIGHTED_PRESSURE_PLATE
                || m == Material.HEAVY_WEIGHTED_PRESSURE_PLATE;
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
