package io.multiverseportals.model;

public record PeerPolicy(
        boolean allowTravel,
        boolean exportInventory,
        boolean importInventory,
        boolean exportScore,
        boolean importScore
) {
    public static PeerPolicy restrictive() {
        return new PeerPolicy(false, false, false, false, false);
    }

    /** Effective travel = both sides allow. */
    public static boolean canTravel(PeerPolicy localAsSource, PeerPolicy remoteAsDest) {
        return localAsSource.allowTravel() && remoteAsDest.allowTravel();
    }

    /** Items move only if source exports AND destination imports. */
    public static boolean canCarryItems(PeerPolicy localAsSource, PeerPolicy remoteAsDest) {
        return localAsSource.exportInventory() && remoteAsDest.importInventory();
    }

    public static boolean canShareScore(PeerPolicy exporter, PeerPolicy importer) {
        return exporter.exportScore() && importer.importScore();
    }
}
