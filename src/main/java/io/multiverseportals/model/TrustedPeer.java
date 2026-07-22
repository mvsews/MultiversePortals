package io.multiverseportals.model;

public final class TrustedPeer {

    private final String serverId;
    private final String displayName;
    private final String federationUrl;
    private final String publicHost;
    private final int publicPort;
    private final String sharedSecret;
    private final boolean hasPlugin;
    private PeerPolicy theirPolicy;

    public TrustedPeer(
            String serverId,
            String displayName,
            String federationUrl,
            String publicHost,
            int publicPort,
            String sharedSecret,
            boolean hasPlugin
    ) {
        this.serverId = serverId;
        this.displayName = displayName;
        this.federationUrl = federationUrl;
        this.publicHost = publicHost;
        this.publicPort = publicPort;
        this.sharedSecret = sharedSecret;
        this.hasPlugin = hasPlugin;
        this.theirPolicy = PeerPolicy.restrictive();
    }

    public String serverId() { return serverId; }
    public String displayName() { return displayName; }
    public String federationUrl() { return federationUrl; }
    public String publicHost() { return publicHost; }
    public int publicPort() { return publicPort; }
    public String sharedSecret() { return sharedSecret; }
    public boolean hasPlugin() { return hasPlugin; }
    public PeerPolicy theirPolicy() { return theirPolicy; }
    public void setTheirPolicy(PeerPolicy theirPolicy) { this.theirPolicy = theirPolicy; }
}
