package io.multiverseportals.scanner;

import io.multiverseportals.model.TrustedPeer;

/** One public server from MineScan / Cornbread / similar. */
public final class ScannedServer {

    private final String host;
    private final int port;
    private final String version;
    private final String authMode;
    private final String software;
    private final String motd;
    private final int onlinePlayers;
    private final int maxPlayers;
    private final boolean full;
    private final long lastSeenMs;
    private final String source;

    public ScannedServer(
            String host,
            int port,
            String version,
            String authMode,
            String software,
            String motd,
            int onlinePlayers,
            int maxPlayers,
            boolean full,
            long lastSeenMs
    ) {
        this(host, port, version, authMode, software, motd, onlinePlayers, maxPlayers, full, lastSeenMs, "minescan");
    }

    public ScannedServer(
            String host,
            int port,
            String version,
            String authMode,
            String software,
            String motd,
            int onlinePlayers,
            int maxPlayers,
            boolean full,
            long lastSeenMs,
            String source
    ) {
        this.host = host;
        this.port = port;
        this.version = version;
        this.authMode = authMode == null ? "" : authMode;
        this.software = software;
        this.motd = motd;
        this.onlinePlayers = onlinePlayers;
        this.maxPlayers = maxPlayers;
        this.full = full;
        this.lastSeenMs = lastSeenMs;
        this.source = source == null || source.isBlank() ? "unknown" : source;
    }

    public String host() { return host; }
    public int port() { return port; }
    public String version() { return version; }
    public String authMode() { return authMode; }
    public String software() { return software; }
    public String motd() { return motd; }
    public int onlinePlayers() { return onlinePlayers; }
    public int maxPlayers() { return maxPlayers; }
    public boolean full() { return full; }
    public long lastSeenMs() { return lastSeenMs; }
    public String source() { return source; }

    public String id() {
        return "scan:" + source + ":" + host + ":" + port;
    }

    public TrustedPeer toPeer() {
        String label = labelForSign();
        return new TrustedPeer(
                id(),
                label,
                "",
                host,
                port,
                "",
                false
        );
    }

    /** Prefer MOTD, else host — used as portal sign destination name. */
    public String labelForSign() {
        if (motd != null && !motd.isBlank()) {
            String cleaned = motd
                    .replaceAll("§[0-9A-FK-ORa-fk-or]", "")
                    .replaceAll("<[^>]+>", "")
                    .replace('\n', ' ')
                    .replace('\r', ' ')
                    .trim()
                    .replaceAll("\\s+", " ");
            if (!cleaned.isBlank()) {
                return cleaned;
            }
        }
        return host != null ? host : (version != null ? version : id());
    }
}
