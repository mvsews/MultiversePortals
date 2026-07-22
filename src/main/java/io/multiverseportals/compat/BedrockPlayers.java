package io.multiverseportals.compat;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.OptionalInt;
import java.util.UUID;

/**
 * Soft-detect Floodgate / Geyser (Bedrock) players and their protocol/version.
 */
public final class BedrockPlayers {

    private BedrockPlayers() {}

    public static boolean isBedrock(Player player) {
        return protocolVersion(player).isPresent() || floodgatePlayer(player) != null || geyserConnection(player) != null;
    }

    /** Bedrock protocol id from GeyserConnection, if available. */
    public static OptionalInt protocolVersion(Player player) {
        Object conn = geyserConnection(player);
        if (conn == null) {
            return OptionalInt.empty();
        }
        try {
            Object v = conn.getClass().getMethod("protocolVersion").invoke(conn);
            if (v instanceof Integer i && i > 0) {
                return OptionalInt.of(i);
            }
            if (v instanceof Number n && n.intValue() > 0) {
                return OptionalInt.of(n.intValue());
            }
        } catch (Throwable ignored) {
        }
        return OptionalInt.empty();
    }

    /** Human Bedrock version string from Floodgate / Geyser, if available. */
    public static String versionString(Player player) {
        Object fg = floodgatePlayer(player);
        if (fg != null) {
            try {
                Object v = fg.getClass().getMethod("getVersion").invoke(fg);
                if (v != null && !String.valueOf(v).isBlank()) {
                    return String.valueOf(v);
                }
            } catch (Throwable ignored) {
            }
        }
        Object conn = geyserConnection(player);
        if (conn != null) {
            for (String m : new String[]{"version", "gameVersion", "clientVersion", "getVersion"}) {
                try {
                    Object v = conn.getClass().getMethod(m).invoke(conn);
                    if (v != null && !String.valueOf(v).isBlank()) {
                        return String.valueOf(v);
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        return "";
    }

    /**
     * Bedrock↔Geyser transfers require an exact protocol match (no Via* on Bedrock side).
     */
    public static boolean canJoinDest(int clientProtocol, int destProtocol) {
        if (clientProtocol <= 0 || destProtocol <= 0) {
            return false;
        }
        return clientProtocol == destProtocol;
    }

    /**
     * Normalize Bedrock version labels: {@code 1.26.33} / {@code 26.33} / {@code v26.33} → {@code 26.33}.
     */
    public static String normalizeVersion(String version) {
        if (version == null || version.isBlank()) {
            return "";
        }
        String v = version.trim().toLowerCase(java.util.Locale.ROOT);
        if (v.startsWith("v")) {
            v = v.substring(1);
        }
        if (v.startsWith("1.") && v.length() > 2 && Character.isDigit(v.charAt(2))) {
            String rest = v.substring(2);
            int first = rest.indexOf('.');
            if (first > 0) {
                try {
                    int major = Integer.parseInt(rest.substring(0, first));
                    if (major >= 20) {
                        v = rest;
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if ((c >= '0' && c <= '9') || c == '.') {
                sb.append(c);
            } else if (sb.length() > 0) {
                break;
            }
        }
        String n = sb.toString();
        while (n.endsWith(".")) {
            n = n.substring(0, n.length() - 1);
        }
        return n;
    }

    /** Exact normalized version match (e.g. 26.33 == 1.26.33, ≠ 26.30). */
    public static boolean versionsMatch(String clientVersion, String destVersion) {
        String a = normalizeVersion(clientVersion);
        String b = normalizeVersion(destVersion);
        if (a.isEmpty() || b.isEmpty()) {
            return false;
        }
        return a.equals(b);
    }

    /**
     * Can this Bedrock client join a destination advertising {@code destProtocol}?
     * <p>
     * Matching protocol IDs are authoritative (Bedrock has no Via*). Geyser UDP MOTD
     * often shows a nearby version string (e.g. {@code 26.32} vs client {@code 1.26.33})
     * while advertising the same protocol — that must still be treated as joinable.
     * Version strings are only used when a protocol id is missing.
     */
    public static boolean canJoinDest(
            int clientProtocol,
            int destProtocol,
            String clientVersion,
            String destVersion,
            boolean requireVersionMatch
    ) {
        if (clientProtocol > 0 && destProtocol > 0) {
            return clientProtocol == destProtocol;
        }
        // Missing protocol on one side — optional version fallback
        if (!requireVersionMatch) {
            return false;
        }
        String a = normalizeVersion(clientVersion);
        String b = normalizeVersion(destVersion);
        if (a.isEmpty() || b.isEmpty()) {
            return false;
        }
        if (a.equals(b)) {
            return true;
        }
        return patchFamily(a).equals(patchFamily(b));
    }

    /** {@code 26.32} / {@code 26.32.1} → {@code 26.32}; {@code 26} → {@code 26}. */
    static String patchFamily(String normalized) {
        if (normalized == null || normalized.isBlank()) {
            return "";
        }
        String[] p = normalized.split("\\.");
        if (p.length >= 2) {
            return p[0] + "." + p[1];
        }
        return p[0];
    }

    private static Object floodgatePlayer(Player player) {
        if (player == null) {
            return null;
        }
        try {
            Class<?> api = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
            Object inst = api.getMethod("getInstance").invoke(null);
            Method is = api.getMethod("isFloodgatePlayer", UUID.class);
            if (!Boolean.TRUE.equals(is.invoke(inst, player.getUniqueId()))) {
                return null;
            }
            return api.getMethod("getPlayer", UUID.class).invoke(inst, player.getUniqueId());
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object geyserConnection(Player player) {
        if (player == null) {
            return null;
        }
        try {
            if (Bukkit.getPluginManager().getPlugin("Geyser-Spigot") == null
                    && Bukkit.getPluginManager().getPlugin("Geyser-Paper") == null) {
                return null;
            }
            Class<?> apiClass = Class.forName("org.geysermc.geyser.api.GeyserApi");
            Object api = apiClass.getMethod("api").invoke(null);
            Object conn = apiClass.getMethod("connectionByUuid", UUID.class).invoke(api, player.getUniqueId());
            if (conn != null) {
                return conn;
            }
            // Fallback: match by name among online connections
            Object coll = apiClass.getMethod("onlineConnections").invoke(api);
            if (coll instanceof Iterable<?> it) {
                for (Object c : it) {
                    try {
                        Object name = c.getClass().getMethod("bedrockUsername").invoke(c);
                        if (player.getName().equalsIgnoreCase(String.valueOf(name))) {
                            return c;
                        }
                    } catch (NoSuchMethodException ignored) {
                    }
                    try {
                        Object name = c.getClass().getMethod("name").invoke(c);
                        if (player.getName().equalsIgnoreCase(String.valueOf(name))) {
                            return c;
                        }
                    } catch (NoSuchMethodException ignored) {
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}
