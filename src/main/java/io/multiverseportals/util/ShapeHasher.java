package io.multiverseportals.util;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;

public final class ShapeHasher {

    /** Normalized keyword → portal kind: multi | away | to | pair */
    private static final Map<String, String> KEYWORDS = new HashMap<>();

    static {
        alias("multi",
                "portal", "multi", "random", "mvp",
                "портал", "мульти", "случайный", "случайныйпортал", "рандом",
                "传送门", "傳送門", "传送", "傳送", "随机", "隨機", "随机门", "隨機門",
                "zufall", "zufallsportal");
        alias("away",
                "away", "авей", "биом", "биомпортал",
                "异界", "異界", "群系", "群系门", "群系門",
                "weg", "biom");
        alias("to",
                "to", "goto", "server",
                "к", "на", "сервер", "ксерверу",
                "前往", "到", "到服",
                "zu", "nach");
        alias("pair",
                "pair", "link",
                "пара", "связь", "парный",
                "配对", "配對", "双门", "雙門",
                "paar", "koppel");
    }

    private ShapeHasher() {}

    private static void alias(String kind, String... words) {
        for (String w : words) {
            KEYWORDS.put(norm(w), kind);
        }
    }

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
        String t = norm(line0);
        if (t.isEmpty()) {
            return null;
        }
        return KEYWORDS.get(t);
    }

    /**
     * Second line under Portal/Портал: away / multi / pair / to, empty string if blank, or null (host/IP).
     */
    public static String parseDestKind(String line) {
        String t = norm(line);
        if (t.isEmpty()) {
            return "";
        }
        return KEYWORDS.get(t);
    }

    static String norm(String raw) {
        if (raw == null) {
            return "";
        }
        String t = Normalizer.normalize(raw, Normalizer.Form.NFKC);
        t = t.toLowerCase(Locale.ROOT)
                .replace('ё', 'е')
                .replace("[", "").replace("]", "")
                .replace("【", "").replace("】", "")
                .replace("「", "").replace("」", "")
                .replace("〔", "").replace("〕", "")
                .trim();
        t = t.replaceAll("\\s+", "");
        return t;
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
