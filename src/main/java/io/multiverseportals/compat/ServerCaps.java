package io.multiverseportals.compat;

import io.multiverseportals.config.PluginConfig;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.List;

/**
 * Soft-detect local stack capabilities for registry/catalog (not a full plugin list).
 */
public final class ServerCaps {

    private final String mvpVersion;
    private final boolean hasGeyser;
    private final boolean hasFloodgate;
    private final String geyserVersion;
    private final int bedrockPort;
    private final int bedrockProtocol;
    private final String bedrockVersion;
    private final boolean acceptBedrock;
    private final boolean ingressEnabled;
    private final int ingressMaxOnline;
    private final int ingressReserveSlots;
    private final double ingressMinScore;
    private final boolean ingressDenyUnknownScore;
    private final int ingressMaxArrivalsPerHour;
    private final boolean exportInventory;
    private final boolean importInventory;

    public ServerCaps(
            String mvpVersion,
            boolean hasGeyser,
            boolean hasFloodgate,
            String geyserVersion,
            int bedrockPort,
            int bedrockProtocol,
            String bedrockVersion,
            boolean acceptBedrock,
            boolean ingressEnabled,
            int ingressMaxOnline,
            int ingressReserveSlots,
            double ingressMinScore,
            boolean ingressDenyUnknownScore,
            int ingressMaxArrivalsPerHour,
            boolean exportInventory,
            boolean importInventory
    ) {
        this.mvpVersion = mvpVersion == null ? "" : mvpVersion;
        this.hasGeyser = hasGeyser;
        this.hasFloodgate = hasFloodgate;
        this.geyserVersion = geyserVersion == null ? "" : geyserVersion;
        this.bedrockPort = bedrockPort;
        this.bedrockProtocol = bedrockProtocol;
        this.bedrockVersion = bedrockVersion == null ? "" : bedrockVersion;
        this.acceptBedrock = acceptBedrock;
        this.ingressEnabled = ingressEnabled;
        this.ingressMaxOnline = ingressMaxOnline;
        this.ingressReserveSlots = ingressReserveSlots;
        this.ingressMinScore = ingressMinScore;
        this.ingressDenyUnknownScore = ingressDenyUnknownScore;
        this.ingressMaxArrivalsPerHour = ingressMaxArrivalsPerHour;
        this.exportInventory = exportInventory;
        this.importInventory = importInventory;
    }

    public static ServerCaps empty() {
        return new ServerCaps("", false, false, "", 0, 0, "", false, false, 0, 0, 0, false, 0, false, false);
    }

    public static ServerCaps detect(PluginConfig config) {
        Plugin mvp = plugin("MultiversePortals");
        String mvpVer = mvp != null ? safeVersion(mvp) : "";
        Plugin geyser = plugin("Geyser-Spigot");
        if (geyser == null) {
            geyser = plugin("Geyser-Paper");
        }
        Plugin floodgate = plugin("floodgate");
        if (floodgate == null) {
            floodgate = plugin("Floodgate");
        }
        boolean hasGeyser = geyser != null && geyser.isEnabled();
        boolean hasFloodgate = floodgate != null && floodgate.isEnabled();
        String geyserVer = hasGeyser ? safeVersion(geyser) : "";
        int bedPort = 0;
        if (hasGeyser) {
            bedPort = readGeyserListenPort(geyser, config);
        }
        int bedProto = hasGeyser ? detectBedrockProtocol() : 0;
        String bedVer = hasGeyser ? detectBedrockVersion() : "";
        return new ServerCaps(
                mvpVer,
                hasGeyser,
                hasFloodgate,
                geyserVer,
                bedPort,
                bedProto,
                bedVer,
                hasGeyser,
                config.ingressEnabled(),
                config.ingressMaxOnline(),
                config.ingressReserveSlots(),
                config.ingressMinScore(),
                config.ingressDenyUnknownScore(),
                config.ingressMaxArrivalsPerHour(),
                config.defaultExportInventory(),
                config.defaultImportInventory()
        );
    }

    private static Plugin plugin(String name) {
        return Bukkit.getPluginManager().getPlugin(name);
    }

    /**
     * Real Geyser UDP listen / broadcast port from its config.yml (not scanner default list).
     * Prefer {@code bedrock.broadcast-port} when set — that's what Bedrock clients must use.
     */
    private static int readGeyserListenPort(Plugin geyser, PluginConfig config) {
        try {
            File cfg = new File(geyser.getDataFolder(), "config.yml");
            if (cfg.isFile()) {
                YamlConfiguration yml = YamlConfiguration.loadConfiguration(cfg);
                int broadcast = yml.getInt("bedrock.broadcast-port", 0);
                if (broadcast > 0 && broadcast <= 65535) {
                    return broadcast;
                }
                if (yml.getBoolean("bedrock.clone-remote-port", false)) {
                    int javaPort = Bukkit.getPort();
                    if (javaPort > 0) {
                        return javaPort;
                    }
                }
                int port = yml.getInt("bedrock.port", 0);
                if (port > 0 && port <= 65535) {
                    return port;
                }
            }
        } catch (Throwable ignored) {
        }
        List<Integer> ports = config != null ? config.scannerBedrockPorts() : List.of();
        if (ports != null && !ports.isEmpty() && ports.get(0) != null && ports.get(0) > 0) {
            return ports.get(0);
        }
        return 19132;
    }

    private static String safeVersion(Plugin p) {
        try {
            return p.getPluginMeta() != null ? p.getPluginMeta().getVersion() : p.getDescription().getVersion();
        } catch (Throwable t) {
            try {
                return p.getDescription().getVersion();
            } catch (Throwable t2) {
                return "";
            }
        }
    }

    /** Best-effort: Geyser's current Bedrock codec / protocol id. */
    private static int detectBedrockProtocol() {
        try {
            Class<?> apiClass = Class.forName("org.geysermc.geyser.api.GeyserApi");
            Object api = apiClass.getMethod("api").invoke(null);
            try {
                Object v = api.getClass().getMethod("defaultBedrockProtocol").invoke(api);
                if (v instanceof Number n) {
                    return n.intValue();
                }
            } catch (NoSuchMethodException ignored) {
            }
        } catch (Throwable ignored) {
        }
        // GameProtocol.getPacketProtocolVersion() / DEFAULT_BEDROCK_PROTOCOL
        for (String cn : List.of(
                "org.geysermc.geyser.network.GameProtocol",
                "org.geysermc.geyser.session.GeyserSession"
        )) {
            try {
                Class<?> c = Class.forName(cn);
                try {
                    Object v = c.getMethod("getBedrockProtocol").invoke(null);
                    if (v instanceof Number n && n.intValue() > 0) {
                        return n.intValue();
                    }
                } catch (NoSuchMethodException ignored) {
                }
                try {
                    Object f = c.getField("DEFAULT_BEDROCK_PROTOCOL").get(null);
                    if (f instanceof Number n && n.intValue() > 0) {
                        return n.intValue();
                    }
                } catch (NoSuchFieldException ignored) {
                }
            } catch (Throwable ignored) {
            }
        }
        return 0;
    }

    private static String detectBedrockVersion() {
        try {
            Class<?> c = Class.forName("org.geysermc.geyser.network.GameProtocol");
            try {
                Object v = c.getMethod("getMinecraftVersion").invoke(null);
                if (v != null) {
                    return String.valueOf(v);
                }
            } catch (NoSuchMethodException ignored) {
            }
            try {
                Object v = c.getMethod("defaultBedrockCodec").invoke(null);
                if (v != null) {
                    try {
                        Object mv = v.getClass().getMethod("getMinecraftVersion").invoke(v);
                        if (mv != null) {
                            return String.valueOf(mv);
                        }
                    } catch (NoSuchMethodException ignored) {
                    }
                    try {
                        Object proto = v.getClass().getMethod("getProtocolVersion").invoke(v);
                        // keep version empty if only proto
                    } catch (NoSuchMethodException ignored) {
                    }
                }
            } catch (NoSuchMethodException ignored) {
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    public String mvpVersion() { return mvpVersion; }
    public boolean hasGeyser() { return hasGeyser; }
    public boolean hasFloodgate() { return hasFloodgate; }
    public String geyserVersion() { return geyserVersion; }
    public int bedrockPort() { return bedrockPort; }
    public int bedrockProtocol() { return bedrockProtocol; }
    public String bedrockVersion() { return bedrockVersion; }
    public boolean acceptBedrock() { return acceptBedrock; }
    public boolean ingressEnabled() { return ingressEnabled; }
    public int ingressMaxOnline() { return ingressMaxOnline; }
    public int ingressReserveSlots() { return ingressReserveSlots; }
    public double ingressMinScore() { return ingressMinScore; }
    public boolean ingressDenyUnknownScore() { return ingressDenyUnknownScore; }
    public int ingressMaxArrivalsPerHour() { return ingressMaxArrivalsPerHour; }
    public boolean exportInventory() { return exportInventory; }
    public boolean importInventory() { return importInventory; }

    public String shortLabel() {
        StringBuilder sb = new StringBuilder();
        sb.append("mvp");
        if (!mvpVersion.isBlank()) {
            sb.append('@').append(trimVer(mvpVersion));
        }
        sb.append(' ');
        if (hasGeyser) {
            sb.append("geyser");
            if (!geyserVersion.isBlank()) {
                sb.append('@').append(trimVer(geyserVersion));
            }
            if (bedrockProtocol > 0) {
                sb.append(" bedp").append(bedrockProtocol);
            }
            if (!bedrockVersion.isBlank()) {
                sb.append('/').append(bedrockVersion);
            }
        } else {
            sb.append("java-only");
        }
        if (hasFloodgate) {
            sb.append(" fg");
        }
        if (ingressEnabled) {
            sb.append(" in:min").append((int) ingressMinScore);
            if (ingressMaxArrivalsPerHour > 0) {
                sb.append("/h").append(ingressMaxArrivalsPerHour);
            }
            if (ingressReserveSlots > 0) {
                sb.append(" rsv").append(ingressReserveSlots);
            }
        }
        return sb.toString();
    }

    private static String trimVer(String v) {
        if (v.length() <= 24) {
            return v;
        }
        return v.substring(0, 24);
    }
}
