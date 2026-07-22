package io.multiverseportals.travel;

import io.multiverseportals.config.PluginConfig;
import io.multiverseportals.db.Database;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;

/**
 * Destination-side gates for portal arrivals: online cap, min score, denylist, rate limit.
 */
public final class IngressPolicy {

    public enum DenyReason {
        OK,
        DISABLED,
        DENYLIST,
        FULL,
        LOW_SCORE,
        RATE_LIMIT,
        UNKNOWN_SCORE
    }

    private final PluginConfig config;
    private final Database db;
    private final Deque<Long> arrivalTimes = new ArrayDeque<>();

    public IngressPolicy(PluginConfig config, Database db) {
        this.config = config;
        this.db = db;
    }

    public DenyReason check(Player player, Double travelScore) {
        if (!config.ingressEnabled()) {
            return DenyReason.OK;
        }
        if (db.isDenied(player.getUniqueId())) {
            return DenyReason.DENYLIST;
        }

        int online = Bukkit.getOnlinePlayers().size();
        int cap = config.ingressMaxOnline();
        if (cap <= 0) {
            cap = Bukkit.getMaxPlayers();
        }
        int reserve = Math.max(0, config.ingressReserveSlots());
        if (online > Math.max(0, cap - reserve)) {
            return DenyReason.FULL;
        }

        double effective = effectiveScore(player.getUniqueId(), travelScore);
        if (travelScore == null && config.ingressDenyUnknownScore()) {
            return DenyReason.UNKNOWN_SCORE;
        }
        if (effective < config.ingressMinScore()) {
            return DenyReason.LOW_SCORE;
        }

        if (!allowRate()) {
            return DenyReason.RATE_LIMIT;
        }
        return DenyReason.OK;
    }

    /** Call after accepting a portal arrival. */
    public void recordArrival() {
        long now = System.currentTimeMillis();
        synchronized (arrivalTimes) {
            arrivalTimes.addLast(now);
            prune(now);
        }
        db.incrementTotalArrivals();
    }

    /** Lifetime portal arrivals on this server (all history). */
    public long totalArrivals() {
        return db.getTotalArrivals();
    }

    public double effectiveScore(UUID uuid, Double travelScore) {
        double base = travelScore != null ? travelScore : db.localScore(uuid);
        return base + db.modReputation(uuid);
    }

    private boolean allowRate() {
        int max = config.ingressMaxArrivalsPerHour();
        if (max <= 0) {
            return true;
        }
        long now = System.currentTimeMillis();
        synchronized (arrivalTimes) {
            prune(now);
            return arrivalTimes.size() < max;
        }
    }

    private void prune(long now) {
        long hourAgo = now - 3_600_000L;
        while (!arrivalTimes.isEmpty() && arrivalTimes.peekFirst() < hourAgo) {
            arrivalTimes.removeFirst();
        }
    }

    public int arrivalsLastHour() {
        long now = System.currentTimeMillis();
        synchronized (arrivalTimes) {
            prune(now);
            return arrivalTimes.size();
        }
    }

    public String reasonMessage(DenyReason reason) {
        return reasonMessage(null, reason);
    }

    public String reasonMessage(org.bukkit.entity.Player player, DenyReason reason) {
        return switch (reason) {
            case DENYLIST -> config.message(player, "ingress-denied");
            case FULL -> config.message(player, "ingress-full");
            case LOW_SCORE -> config.message(player, "ingress-low-score");
            case RATE_LIMIT -> config.message(player, "ingress-rate");
            case UNKNOWN_SCORE -> config.message(player, "ingress-unknown-score");
            default -> config.message(player, "ingress-denied");
        };
    }
}
