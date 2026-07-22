package io.multiverseportals.model;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class Portal {

    private final String id;
    private final PortalType type;
    private PortalStatus status;
    private PortalFrame frame;
    private final String name;
    private final UUID creator;

    /** PAIR: remote server + remote portal id */
    private String pairServerId;
    private String pairPortalId;
    private String pairInviteCode;

    /** MULTI: pool of server ids (empty = open bind from scanners) */
    private final List<String> multiPool = new ArrayList<>();

    /** MULTI: fixed destination after bind search */
    private String boundHost;
    private int boundPort;
    private int boundJavaPort;
    private String boundVersion;
    /** When dest has MVP — portal id to land on (and reverse landing target). */
    private String boundDestPortalId;

    public Portal(String id, PortalType type, PortalStatus status, PortalFrame frame, String name, UUID creator) {
        this.id = id;
        this.type = type;
        this.status = status;
        this.frame = frame;
        this.name = name;
        this.creator = creator;
    }

    public String id() { return id; }
    public PortalType type() { return type; }
    public PortalStatus status() { return status; }
    public void setStatus(PortalStatus status) { this.status = status; }
    public PortalFrame frame() { return frame; }
    public void setFrame(PortalFrame frame) { this.frame = frame; }
    public String name() { return name; }
    public UUID creator() { return creator; }

    public String pairServerId() { return pairServerId; }
    public void setPairServerId(String pairServerId) { this.pairServerId = pairServerId; }
    public String pairPortalId() { return pairPortalId; }
    public void setPairPortalId(String pairPortalId) { this.pairPortalId = pairPortalId; }
    public String pairInviteCode() { return pairInviteCode; }
    public void setPairInviteCode(String pairInviteCode) { this.pairInviteCode = pairInviteCode; }

    public List<String> multiPool() { return multiPool; }

    public String boundHost() { return boundHost; }
    public void setBoundHost(String boundHost) { this.boundHost = boundHost; }
    public int boundPort() { return boundPort; }
    public void setBoundPort(int boundPort) { this.boundPort = boundPort; }
    public int boundJavaPort() { return boundJavaPort; }
    public void setBoundJavaPort(int boundJavaPort) { this.boundJavaPort = boundJavaPort; }
    public String boundVersion() { return boundVersion; }
    public void setBoundVersion(String boundVersion) { this.boundVersion = boundVersion; }

    public String boundDestPortalId() { return boundDestPortalId; }
    public void setBoundDestPortalId(String boundDestPortalId) { this.boundDestPortalId = boundDestPortalId; }

    public boolean hasBoundDestination() {
        return boundHost != null && !boundHost.isBlank()
                && (boundPort > 0 || boundJavaPort > 0);
    }

    public void clearBound() {
        boundHost = null;
        boundPort = 0;
        boundJavaPort = 0;
        boundVersion = null;
        boundDestPortalId = null;
    }

    public boolean isTravelReady() {
        if (type == PortalType.MULTI) {
            return status == PortalStatus.ACTIVE && hasBoundDestination();
        }
        if (status != PortalStatus.ACTIVE) {
            return false;
        }
        return pairServerId != null && pairPortalId != null;
    }
}
