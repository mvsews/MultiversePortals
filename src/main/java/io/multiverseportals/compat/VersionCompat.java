package io.multiverseportals.compat;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.OptionalInt;
import java.util.logging.Logger;

/**
 * Detects native protocol + ViaVersion/ViaBackwards/ViaRewind (soft).
 * Used to avoid transferring players to servers their client cannot join.
 */
public final class VersionCompat {

    private final Logger log;
    private final String mcVersion;
    private final int nativeProtocol;
    private final boolean viaVersion;
    private final boolean viaBackwards;
    private final boolean viaRewind;
    private final int viaMin;
    private final int viaMax;

    public VersionCompat(Logger log) {
        this.log = log;
        this.mcVersion = Bukkit.getMinecraftVersion();
        this.nativeProtocol = detectNativeProtocol();
        this.viaVersion = Bukkit.getPluginManager().getPlugin("ViaVersion") != null;
        this.viaBackwards = Bukkit.getPluginManager().getPlugin("ViaBackwards") != null;
        this.viaRewind = Bukkit.getPluginManager().getPlugin("ViaRewind") != null;
        int[] range = detectViaRange();
        this.viaMin = range[0];
        this.viaMax = range[1];
        log.info("VersionCompat: mc=" + mcVersion + " protocol=" + nativeProtocol
                + " ViaVersion=" + viaVersion + " ViaBackwards=" + viaBackwards
                + " ViaRewind=" + viaRewind
                + (viaVersion ? (" viaRange=[" + viaMin + ".." + viaMax + "]") : ""));
    }

    public String mcVersion() { return mcVersion; }
    public int nativeProtocol() { return nativeProtocol; }
    public boolean viaVersion() { return viaVersion; }
    public boolean viaBackwards() { return viaBackwards; }
    public boolean viaRewind() { return viaRewind; }
    public int viaMin() { return viaMin; }
    public int viaMax() { return viaMax; }

    /** Protocol the player is actually using (Via if present, else native). */
    public int playerProtocol(Player player) {
        if (viaVersion) {
            OptionalInt v = viaPlayerProtocol(player);
            if (v.isPresent()) {
                return v.getAsInt();
            }
        }
        return nativeProtocol;
    }

    /**
     * Can a client with {@code clientProtocol} join destination described by these fields?
     */
    public static boolean canClientJoinDest(
            int clientProtocol,
            int destNativeProtocol,
            boolean destViaVersion,
            boolean destViaBackwards,
            boolean destViaRewind,
            int destViaMin,
            int destViaMax
    ) {
        if (clientProtocol <= 0 || destNativeProtocol <= 0) {
            // Unknown protocol metadata — refuse rather than risk a failed join
            return false;
        }
        if (clientProtocol == destNativeProtocol) {
            return true;
        }
        // Prefer explicit Via range published by destination
        if ((destViaVersion || destViaBackwards || destViaRewind) && destViaMin > 0 && destViaMax > 0) {
            return clientProtocol >= destViaMin && clientProtocol <= destViaMax;
        }
        if (!destViaVersion && !destViaBackwards && !destViaRewind) {
            // No adapter on dest → only exact match (already handled above)
            return false;
        }
        // Heuristic without published range (older peers that haven't announced via_min/max yet)
        if (clientProtocol < destNativeProtocol) {
            // Older client → newer server: ViaBackwards / ViaRewind
            return destViaBackwards || destViaRewind;
        }
        // Newer client → older server: ViaVersion
        return destViaVersion;
    }

    public boolean canJoinDest(Player player, int destNative, boolean dv, boolean db, boolean dr, int dMin, int dMax) {
        return canClientJoinDest(playerProtocol(player), destNative, dv, db, dr, dMin, dMax);
    }

    private int detectNativeProtocol() {
        // Prefer Via's view of server protocol when present
        try {
            Class<?> via = Class.forName("com.viaversion.viaversion.api.Via");
            Object api = via.getMethod("getAPI").invoke(null);
            Object serverVersion = api.getClass().getMethod("getServerVersion").invoke(api);
            // highestSupportedVersion() -> ProtocolVersion
            Method highest = serverVersion.getClass().getMethod("highestSupportedVersion");
            Object pv = highest.invoke(serverVersion);
            int id = extractProtocolId(pv);
            if (id > 0) {
                return id;
            }
        } catch (Throwable ignored) {
        }
        try {
            // Paper: Bukkit.getMinecraftVersion() + SharedConstants reflection
            Class<?> sc = Class.forName("net.minecraft.SharedConstants");
            Object v = sc.getMethod("getCurrentVersion").invoke(null);
            Method getProtocol = v.getClass().getMethod("getProtocolVersion");
            Object p = getProtocol.invoke(v);
            if (p instanceof Number n) {
                return n.intValue();
            }
        } catch (Throwable ignored) {
        }
        return protocolFromMcVersion(mcVersion);
    }

    private int[] detectViaRange() {
        int min = nativeProtocol;
        int max = nativeProtocol;
        if (!viaVersion) {
            return new int[]{min, max};
        }
        try {
            Class<?> via = Class.forName("com.viaversion.viaversion.api.Via");
            Object api = via.getMethod("getAPI").invoke(null);
            // getSupportedProtocolVersions() returns SortedSet<ProtocolVersion> on newer Via
            Method m;
            try {
                m = api.getClass().getMethod("getSupportedProtocolVersions");
            } catch (NoSuchMethodException e) {
                m = api.getClass().getMethod("getSupportedVersions");
            }
            Object set = m.invoke(api);
            if (set instanceof Iterable<?> it) {
                for (Object pv : it) {
                    int id = extractProtocolId(pv);
                    if (id > 0) {
                        min = Math.min(min, id);
                        max = Math.max(max, id);
                    }
                }
            }
        } catch (Throwable t) {
            log.warning("Could not read Via supported versions: " + t.getMessage());
        }
        // If API returned only native (common when called too early / incomplete set),
        // expand using known Via* adapters so we don't lie that only exact match works.
        if (min >= max && (viaBackwards || viaRewind || viaVersion)) {
            if (viaRewind) {
                min = 47; // 1.8
            } else if (viaBackwards) {
                min = 47; // ViaBackwards typically covers 1.9+; be inclusive for registry filter
            } else {
                // ViaVersion alone: newer clients joining this older server
                min = nativeProtocol;
            }
            max = Math.max(max, nativeProtocol + 40);
            log.info("VersionCompat: expanded via range (adapters) to [" + min + ".." + max + "]");
        }
        return new int[]{min, max};
    }

    private OptionalInt viaPlayerProtocol(Player player) {
        try {
            Class<?> via = Class.forName("com.viaversion.viaversion.api.Via");
            Object api = via.getMethod("getAPI").invoke(null);
            Method getPlayerVersion = api.getClass().getMethod("getPlayerVersion", java.util.UUID.class);
            Object result = getPlayerVersion.invoke(api, player.getUniqueId());
            if (result instanceof Integer i) {
                return OptionalInt.of(i);
            }
            int id = extractProtocolId(result);
            if (id > 0) {
                return OptionalInt.of(id);
            }
        } catch (Throwable ignored) {
        }
        return OptionalInt.empty();
    }

    private static int extractProtocolId(Object pv) {
        if (pv == null) {
            return -1;
        }
        if (pv instanceof Integer i) {
            return i;
        }
        if (pv instanceof Number n) {
            return n.intValue();
        }
        try {
            Method getVersion = pv.getClass().getMethod("getVersion");
            Object v = getVersion.invoke(pv);
            if (v instanceof Number n) {
                return n.intValue();
            }
        } catch (Exception ignored) {
        }
        try {
            Method getId = pv.getClass().getMethod("getId");
            Object v = getId.invoke(pv);
            if (v instanceof Number n) {
                return n.intValue();
            }
        } catch (Exception ignored) {
        }
        return -1;
    }

    /** Best-effort map for common versions (kept up to date for 1.20–1.21). */
    public static int protocolFromMcVersion(String ver) {
        if (ver == null) {
            return 0;
        }
        String v = ver.trim();
        // strip patch noise
        return switch (v) {
            case "1.21.11", "1.21.10" -> 773; // approx — 1.21.9+ area; Paper may differ
            case "1.21.9" -> 773;
            case "1.21.8" -> 772;
            case "1.21.7", "1.21.6" -> 771;
            case "1.21.5" -> 770;
            case "1.21.4" -> 769;
            case "1.21.3" -> 768;
            case "1.21.2" -> 768;
            case "1.21.1", "1.21" -> 767;
            case "1.20.6" -> 766;
            case "1.20.5" -> 766;
            case "1.20.4" -> 765;
            case "1.20.3" -> 765;
            case "1.20.2" -> 764;
            case "1.20.1", "1.20" -> 763;
            case "1.19.4" -> 762;
            default -> {
                // crude parse: 1.21.x → ~767+
                if (v.startsWith("1.21")) {
                    yield 767;
                }
                if (v.startsWith("1.20")) {
                    yield 765;
                }
                if (v.startsWith("1.19")) {
                    yield 762;
                }
                yield 0;
            }
        };
    }
}
