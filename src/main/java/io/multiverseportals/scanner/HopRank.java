package io.multiverseportals.scanner;

/**
 * Soft bind rank from directed hop outcomes — not a single reputation number.
 * {@code succeeded} / {@code bounced} are origin reports about this dest;
 * {@code accepted} / {@code refused} are the dest reporting inbound landings vs kicks.
 */
public final class HopRank {

    private HopRank() {
    }

    /**
     * Higher is better. All zeros → 0 (unknown dest, no penalty and no bonus).
     */
    public static double score(int succeeded, int bounced, int accepted, int refused) {
        int ok = Math.max(0, succeeded) + Math.max(0, accepted);
        int bad = Math.max(0, bounced) + Math.max(0, refused);
        if (ok == 0 && bad == 0) {
            return 0.0;
        }
        return 10.0 * Math.log1p(ok) - 18.0 * Math.log1p(bad);
    }
}
