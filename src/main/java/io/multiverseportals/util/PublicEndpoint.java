package io.multiverseportals.util;

import org.bukkit.Bukkit;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * Resolves the address other clients use for Transfer, and whether this host
 * should appear in the public catalog.
 */
public final class PublicEndpoint {

    private PublicEndpoint() {}

    /** Explicit override, else {@code server-ip}, else best-effort local guess. */
    public static String resolveHost(String configured) {
        if (configured != null) {
            String c = configured.trim();
            if (!c.isEmpty() && !"auto".equalsIgnoreCase(c)) {
                return c;
            }
        }
        try {
            String ip = Bukkit.getIp();
            if (ip != null && !ip.isBlank() && !"0.0.0.0".equals(ip) && !"*".equals(ip)) {
                return ip.trim();
            }
        } catch (Throwable ignored) {
        }
        try {
            String props = readServerProperty("server-ip");
            if (props != null && !props.isBlank() && !"0.0.0.0".equals(props)) {
                return props.trim();
            }
        } catch (Throwable ignored) {
        }
        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (Exception e) {
            return "127.0.0.1";
        }
    }

    /** Config port, or {@code 0}/{@code auto} → {@link Bukkit#getPort()}. */
    public static int resolvePort(int configured) {
        if (configured > 0 && configured <= 65535) {
            return configured;
        }
        try {
            int p = Bukkit.getPort();
            if (p > 0) {
                return p;
            }
        } catch (Throwable ignored) {
        }
        try {
            String props = readServerProperty("server-port");
            if (props != null && !props.isBlank()) {
                return Integer.parseInt(props.trim());
            }
        } catch (Exception ignored) {
        }
        return 25565;
    }

    /**
     * Optional WAN discovery (ipify). Only when configured host is still private/local
     * and {@code discoverPublicIp} is true.
     */
    public static String maybeDiscoverWan(String host, boolean discoverPublicIp, Logger log) {
        if (!discoverPublicIp || !isPrivateOrLocal(host)) {
            return host;
        }
        try {
            URL url = URI.create("https://api.ipify.org").toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(4000);
            conn.setRequestMethod("GET");
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String wan = br.readLine();
                if (wan != null) {
                    wan = wan.trim();
                    if (!wan.isBlank() && !isPrivateOrLocal(wan)) {
                        if (log != null) {
                            log.info("Discovered public IP " + wan + " (server.discover-public-ip)");
                        }
                        return wan;
                    }
                }
            }
        } catch (Exception e) {
            if (log != null) {
                log.warning("Public IP discovery failed: " + e.getMessage());
            }
        }
        return host;
    }

    public static boolean acceptTransfersEnabled() {
        try {
            var m = Bukkit.getServer().getClass().getMethod("isAcceptingTransfers");
            Object v = m.invoke(Bukkit.getServer());
            if (v instanceof Boolean b) {
                return b;
            }
        } catch (Throwable ignored) {
        }
        try {
            // Paper/vanilla: accepts-transfers (with s); older docs sometimes say accept-transfers
            String v = readServerProperty("accepts-transfers");
            if (v == null || v.isBlank()) {
                v = readServerProperty("accept-transfers");
            }
            if (v != null && !v.isBlank()) {
                return Boolean.parseBoolean(v.trim());
            }
        } catch (Throwable ignored) {
        }
        // Unknown — assume false so we don't falsely advertise inbound Transfer
        return false;
    }

    public enum AcceptTransfersPatch {
        /** Runtime already accepts transfers. */
        ALREADY_ON,
        /** File already true, but server must restart to apply. */
        RESTART_PENDING,
        /** We wrote true into server.properties — restart once. */
        WROTE_RESTART,
        /** Skipped (disabled in config, or already patched once and left off). */
        SKIPPED,
        /** Could not write server.properties. */
        FAILED
    }

    /**
     * If transfers are off, write {@code accepts-transfers=true} into {@code server.properties}
     * (once). Takes effect after the next full server restart.
     */
    public static AcceptTransfersPatch ensureAcceptTransfers(Path dataFolder, boolean autoEnable, Logger log) {
        if (acceptTransfersEnabled()) {
            return AcceptTransfersPatch.ALREADY_ON;
        }
        Path marker = dataFolder.resolve(".accept-transfers-enabled");
        boolean fileTrue = isAcceptTransfersTrueInFile();
        if (fileTrue) {
            if (log != null) {
                log.warning("server.properties already has accepts-transfers=true — restart the server once so guests can arrive.");
            }
            return AcceptTransfersPatch.RESTART_PENDING;
        }
        if (!autoEnable) {
            return AcceptTransfersPatch.SKIPPED;
        }
        if (Files.isRegularFile(marker)) {
            // We already tried once; admin may have turned it off on purpose
            return AcceptTransfersPatch.SKIPPED;
        }
        try {
            if (!writeAcceptTransfersTrue()) {
                return AcceptTransfersPatch.FAILED;
            }
            Files.writeString(marker, "1\n", StandardCharsets.UTF_8);
            if (log != null) {
                log.warning("Enabled accepts-transfers=true in server.properties. "
                        + "Restart the server once so guests can arrive through portals.");
            }
            return AcceptTransfersPatch.WROTE_RESTART;
        } catch (Exception e) {
            if (log != null) {
                log.warning("Could not enable accepts-transfers in server.properties: " + e.getMessage());
            }
            return AcceptTransfersPatch.FAILED;
        }
    }

    public static boolean isAcceptTransfersTrueInFile() {
        String v = readServerProperty("accepts-transfers");
        if (v == null || v.isBlank()) {
            v = readServerProperty("accept-transfers");
        }
        return v != null && Boolean.parseBoolean(v.trim());
    }

    static boolean writeAcceptTransfersTrue() throws Exception {
        return writeAcceptTransfers(true);
    }

    /**
     * Prefer Paper key {@code accepts-transfers}; migrate legacy {@code accept-transfers}.
     * Preserves other lines / comments. Takes effect after a full server restart.
     */
    public static boolean writeAcceptTransfers(boolean enabled) throws Exception {
        Path props = Path.of("server.properties");
        if (!Files.isRegularFile(props)) {
            return false;
        }
        java.util.List<String> lines = Files.readAllLines(props, StandardCharsets.UTF_8);
        boolean sawAccepts = false;
        String value = enabled ? "true" : "false";
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String trimmed = line.trim();
            if (trimmed.startsWith("#") || !trimmed.contains("=")) {
                continue;
            }
            int eq = trimmed.indexOf('=');
            String key = trimmed.substring(0, eq).trim();
            if ("accepts-transfers".equalsIgnoreCase(key) || "accept-transfers".equalsIgnoreCase(key)) {
                lines.set(i, "accepts-transfers=" + value);
                sawAccepts = true;
            }
        }
        if (!sawAccepts) {
            if (!lines.isEmpty() && !lines.get(lines.size() - 1).isBlank()) {
                lines.add("");
            }
            lines.add("accepts-transfers=" + value);
        }
        Path tmp = props.resolveSibling("server.properties.mvp-tmp");
        Files.write(tmp, lines, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, props, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(tmp, props, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        return true;
    }

    public static boolean isPrivateOrLocal(String host) {
        if (host == null || host.isBlank()) {
            return true;
        }
        String h = host.trim().toLowerCase(Locale.ROOT);
        if ("localhost".equals(h) || "0.0.0.0".equals(h) || "*".equals(h)
                || h.endsWith(".local") || h.endsWith(".lan") || h.endsWith(".internal")) {
            return true;
        }
        try {
            InetAddress addr = InetAddress.getByName(h);
            return addr.isAnyLocalAddress()
                    || addr.isLoopbackAddress()
                    || addr.isLinkLocalAddress()
                    || addr.isSiteLocalAddress();
        } catch (Exception e) {
            // Unresolvable hostname — treat as public candidate (DNS may work for players)
            return false;
        }
    }

    /**
     * {@code auto}: list only when accept-transfers + non-private host.
     * {@code always}/{@code never}: force.
     */
    public static boolean shouldListPublicly(String mode, String host, boolean acceptTransfers) {
        String m = mode == null ? "auto" : mode.trim().toLowerCase(Locale.ROOT);
        if ("never".equals(m) || "false".equals(m) || "off".equals(m) || "local".equals(m)) {
            return false;
        }
        if ("always".equals(m) || "true".equals(m) || "on".equals(m) || "public".equals(m)) {
            return true;
        }
        // auto
        return acceptTransfers && !isPrivateOrLocal(host);
    }

    private static String readServerProperty(String key) {
        Path props = Path.of("server.properties");
        if (!Files.isRegularFile(props)) {
            return null;
        }
        try (var in = Files.newInputStream(props)) {
            Properties p = new Properties();
            p.load(in);
            return p.getProperty(key);
        } catch (Exception e) {
            return null;
        }
    }
}
