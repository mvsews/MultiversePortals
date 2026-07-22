package io.multiverseportals.model;

import java.util.UUID;

public final class TravelSession {

    public enum Status { PENDING, ARRIVED, RETURNED, EXPIRED, REJECTED }

    private final String sessionId;
    private final UUID playerId;
    private final String fromServer;
    private final String toServer;
    private final String fromPortalId;
    private final String toPortalId;
    private final PortalType portalType;
    private final boolean carriedInventory;
    private final byte[] carriedBlob;
    private final String scoreSnapshotJson;
    private Status status;
    private final long createdAt;
    private final long expiresAt;

    public TravelSession(
            String sessionId,
            UUID playerId,
            String fromServer,
            String toServer,
            String fromPortalId,
            String toPortalId,
            PortalType portalType,
            boolean carriedInventory,
            byte[] carriedBlob,
            String scoreSnapshotJson,
            Status status,
            long createdAt,
            long expiresAt
    ) {
        this.sessionId = sessionId;
        this.playerId = playerId;
        this.fromServer = fromServer;
        this.toServer = toServer;
        this.fromPortalId = fromPortalId;
        this.toPortalId = toPortalId;
        this.portalType = portalType;
        this.carriedInventory = carriedInventory;
        this.carriedBlob = carriedBlob;
        this.scoreSnapshotJson = scoreSnapshotJson;
        this.status = status;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public String sessionId() { return sessionId; }
    public UUID playerId() { return playerId; }
    public String fromServer() { return fromServer; }
    public String toServer() { return toServer; }
    public String fromPortalId() { return fromPortalId; }
    public String toPortalId() { return toPortalId; }
    public PortalType portalType() { return portalType; }
    public boolean carriedInventory() { return carriedInventory; }
    public byte[] carriedBlob() { return carriedBlob; }
    public String scoreSnapshotJson() { return scoreSnapshotJson; }
    public Status status() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public long createdAt() { return createdAt; }
    public long expiresAt() { return expiresAt; }

    public boolean expired() {
        return System.currentTimeMillis() > expiresAt;
    }
}
