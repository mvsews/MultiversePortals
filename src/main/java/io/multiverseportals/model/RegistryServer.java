package io.multiverseportals.model;

import io.multiverseportals.compat.ServerCaps;

public final class RegistryServer {

    private final String serverId;
    private final String displayName;
    private final String publicHost;
    private final int publicPort;
    private final String federationUrl;
    private final boolean hasPlugin;
    private final boolean multiOptIn;
    private final boolean acceptTransfers;
    private final String tags;
    private final String motd;
    private final String description;
    private final boolean hasIcon;
    private final Integer maxPlayers;
    private final Integer onlinePlayers;
    private final String mcVersion;
    private final int protocolId;
    private final boolean hasViaVersion;
    private final boolean hasViaBackwards;
    private final boolean hasViaRewind;
    private final int viaMin;
    private final int viaMax;
    private final long lastHeartbeat;
    /** First insert into the central registry (ms epoch). */
    private final long registeredAt;
    /** Last successful heartbeat / announce (ms epoch). */
    private final long lastOnlineAt;
    /** Last online→offline transition (ms epoch), 0 if never marked offline. */
    private final long lastOfflineAt;
    private final ServerCaps caps;

    public RegistryServer(
            String serverId,
            String displayName,
            String publicHost,
            int publicPort,
            String federationUrl,
            boolean hasPlugin,
            boolean multiOptIn,
            boolean acceptTransfers,
            String tags,
            String motd,
            String description,
            boolean hasIcon,
            Integer maxPlayers,
            Integer onlinePlayers,
            String mcVersion,
            int protocolId,
            boolean hasViaVersion,
            boolean hasViaBackwards,
            boolean hasViaRewind,
            int viaMin,
            int viaMax,
            long lastHeartbeat,
            long registeredAt,
            long lastOnlineAt,
            long lastOfflineAt,
            ServerCaps caps
    ) {
        this.serverId = serverId;
        this.displayName = displayName;
        this.publicHost = publicHost;
        this.publicPort = publicPort;
        this.federationUrl = federationUrl;
        this.hasPlugin = hasPlugin;
        this.multiOptIn = multiOptIn;
        this.acceptTransfers = acceptTransfers;
        this.tags = tags;
        this.motd = motd;
        this.description = description == null ? "" : description;
        this.hasIcon = hasIcon;
        this.maxPlayers = maxPlayers;
        this.onlinePlayers = onlinePlayers;
        this.mcVersion = mcVersion;
        this.protocolId = protocolId;
        this.hasViaVersion = hasViaVersion;
        this.hasViaBackwards = hasViaBackwards;
        this.hasViaRewind = hasViaRewind;
        this.viaMin = viaMin;
        this.viaMax = viaMax;
        this.lastHeartbeat = lastHeartbeat;
        this.registeredAt = registeredAt;
        this.lastOnlineAt = lastOnlineAt;
        this.lastOfflineAt = lastOfflineAt;
        this.caps = caps == null ? ServerCaps.empty() : caps;
    }

    public String serverId() { return serverId; }
    public String displayName() { return displayName; }
    public String publicHost() { return publicHost; }
    public int publicPort() { return publicPort; }
    public String federationUrl() { return federationUrl; }
    public boolean hasPlugin() { return hasPlugin; }
    public boolean multiOptIn() { return multiOptIn; }
    public boolean acceptTransfers() { return acceptTransfers; }
    public String tags() { return tags; }
    public String motd() { return motd; }
    public String description() { return description; }
    public boolean hasIcon() { return hasIcon; }
    public Integer maxPlayers() { return maxPlayers; }
    public Integer onlinePlayers() { return onlinePlayers; }
    public String mcVersion() { return mcVersion; }
    public int protocolId() { return protocolId; }
    public boolean hasViaVersion() { return hasViaVersion; }
    public boolean hasViaBackwards() { return hasViaBackwards; }
    public boolean hasViaRewind() { return hasViaRewind; }
    public int viaMin() { return viaMin; }
    public int viaMax() { return viaMax; }
    public long lastHeartbeat() { return lastHeartbeat; }
    public long registeredAt() { return registeredAt; }
    public long lastOnlineAt() { return lastOnlineAt; }
    public long lastOfflineAt() { return lastOfflineAt; }
    public ServerCaps caps() { return caps; }

    /** @deprecated use mcVersion() */
    public String protocolVersion() { return mcVersion; }

    public TrustedPeer toTrustedPeer(String sharedSecretFallback) {
        String fed = federationUrl == null || federationUrl.isBlank()
                ? "http://" + publicHost + ":25765/mvp/v1"
                : federationUrl;
        return new TrustedPeer(
                serverId,
                displayName,
                fed,
                publicHost,
                publicPort,
                sharedSecretFallback == null ? "" : sharedSecretFallback,
                hasPlugin
        );
    }
}
