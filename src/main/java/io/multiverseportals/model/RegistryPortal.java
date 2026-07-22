package io.multiverseportals.model;

import java.util.List;

/** Portal node published to the shared registry (for graph / return-path discovery). */
public record RegistryPortal(
        String serverId,
        String portalId,
        String portalType,
        String status,
        String world,
        int x,
        int y,
        int z,
        float yaw,
        String name,
        String destKind,
        String destServerId,
        String destHost,
        int destPort,
        int destJavaPort,
        String destPortalId,
        String destLabel,
        boolean returnCapable,
        List<String> signs,
        long updatedAt
) {
    public RegistryPortal {
        signs = signs == null ? List.of() : List.copyOf(signs);
    }

    public String coordKey() {
        return world + " " + x + "," + y + "," + z;
    }

    public String edgeLabel() {
        String dest = destServerId != null && !destServerId.isBlank()
                ? destServerId
                : (destHost == null || destHost.isBlank() ? "?" : destHost + ":" + (destJavaPort > 0 ? destJavaPort : destPort));
        return serverId + " --" + portalType + "--> " + dest
                + (returnCapable ? " [return]" : " [one-way]");
    }

    public boolean hasSigns() {
        return signs != null && !signs.isEmpty();
    }
}
