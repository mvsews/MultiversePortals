package io.multiverseportals.travel;

/**
 * Origin-side verdict after a Transfer.
 * <ul>
 *   <li>No portal on dest: player back within the window = refused; stayed away = accepted.</li>
 *   <li>Dest has a portal: dest reports ARRIVED / REJECTED (session + hop). Rejoining
 *       through their return portal is not a bounce.</li>
 * </ul>
 */
public final class TransferSettle {

    public enum Outcome { ACCEPT, REFUSE }

    private TransferSettle() {}

    public static Outcome decide(
            boolean destHasPortal,
            boolean playerBack,
            String travelStatus,
            String hopOutcome
    ) {
        String hop = norm(hopOutcome);
        String st = norm(travelStatus);
        if (isRefuse(hop) || isRefuseStatus(st)) {
            return Outcome.REFUSE;
        }
        if (isAccept(hop) || isAcceptStatus(st)) {
            return Outcome.ACCEPT;
        }
        if (!destHasPortal) {
            return playerBack ? Outcome.REFUSE : Outcome.ACCEPT;
        }
        // Dest has a portal but never claimed the travel cookie.
        return playerBack ? Outcome.REFUSE : Outcome.ACCEPT;
    }

    private static boolean isRefuse(String hop) {
        return hop.contains("REFUSE") || "BOUNCED".equals(hop) || "FAILED".equals(hop)
                || "REJECTED".equals(hop);
    }

    private static boolean isRefuseStatus(String st) {
        return "BOUNCED".equals(st) || "REJECTED".equals(st);
    }

    private static boolean isAccept(String hop) {
        return "OK".equals(hop) || "ARRIVED".equals(hop) || "DEPARTED".equals(hop);
    }

    private static boolean isAcceptStatus(String st) {
        return "ARRIVED".equals(st) || "RETURNED".equals(st);
    }

    private static String norm(String s) {
        return s == null ? "" : s.trim().toUpperCase();
    }
}
