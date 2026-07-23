package io.multiverseportals.scanner;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.function.ToIntFunction;

/**
 * Soft ranking of Multi destinations by relative online population (busy→quiet flow).
 * Pure functions — no Bukkit — so unit tests can compare before/after rankings.
 */
public final class FlowBalance {

    public record Weights(
            boolean enabled,
            int busyAt,
            int quietAt,
            int megaOnline,
            double megaPenalty,
            double megaPenaltyPerExtra,
            double emptyPenalty,
            boolean megaHardCap,
            int quietTargetMin,
            int quietTargetMax,
            double busyPreferQuietWeight,
            double quietPreferBandWeight,
            double epsilon
    ) {
        public static Weights defaults() {
            return new Weights(
                    true,
                    12,
                    4,
                    80,
                    80.0,
                    0.5,
                    8.0,
                    false,
                    3,
                    25,
                    1.0,
                    1.0,
                    2.0
            );
        }

        public static Weights disabled() {
            Weights d = defaults();
            return new Weights(
                    false,
                    d.busyAt,
                    d.quietAt,
                    d.megaOnline,
                    d.megaPenalty,
                    d.megaPenaltyPerExtra,
                    d.emptyPenalty,
                    d.megaHardCap,
                    d.quietTargetMin,
                    d.quietTargetMax,
                    d.busyPreferQuietWeight,
                    d.quietPreferBandWeight,
                    d.epsilon
            );
        }
    }

    private FlowBalance() {
    }

    /**
     * Higher is better. Unknown dest online ({@code < 0}) scores as neutral mid (no empty/mega hit).
     */
    public static double score(int originOnline, int destOnline, Weights w) {
        Objects.requireNonNull(w, "weights");
        if (!w.enabled()) {
            return 0.0;
        }
        double s = 0.0;
        boolean known = destOnline >= 0;

        if (known && destOnline == 0) {
            s -= Math.max(0.0, w.emptyPenalty());
        }
        if (known && destOnline >= w.megaOnline()) {
            s -= Math.max(0.0, w.megaPenalty());
            int extra = destOnline - w.megaOnline();
            if (extra > 0 && w.megaPenaltyPerExtra() > 0) {
                s -= extra * w.megaPenaltyPerExtra();
            }
        }

        int origin = Math.max(0, originOnline);
        if (origin >= w.busyAt()) {
            // Prefer quieter destinations
            if (known) {
                s += w.busyPreferQuietWeight() * (100.0 - Math.min(100, destOnline));
            }
        } else if (origin <= w.quietAt()) {
            // Prefer a moderate band (not empty-by-default, not giant)
            if (known) {
                int min = Math.max(0, w.quietTargetMin());
                int max = Math.max(min, w.quietTargetMax());
                if (destOnline >= min && destOnline <= max) {
                    // Peak at mid of band
                    double mid = (min + max) / 2.0;
                    double half = Math.max(1.0, (max - min) / 2.0);
                    double closeness = 1.0 - Math.min(1.0, Math.abs(destOnline - mid) / half);
                    s += w.quietPreferBandWeight() * (40.0 + 40.0 * closeness);
                } else if (destOnline > max && destOnline < w.megaOnline()) {
                    s += w.quietPreferBandWeight() * 10.0;
                }
            }
        } else {
            // Mid origin: slight preference for non-empty moderate hosts
            if (known && destOnline > 0 && destOnline < w.megaOnline()) {
                s += 5.0;
            }
        }
        return s;
    }

    /** Soft reject for public binds when hard-cap is on. Club peers should skip this check. */
    public static boolean rejectMega(int destOnline, Weights w) {
        return w != null && w.enabled() && w.megaHardCap() && destOnline >= w.megaOnline();
    }

    /**
     * Sort in place by flow score descending. Near-ties ({@code |Δ| ≤ epsilon}) are shuffled
     * so the same host does not always win.
     */
    public static <T> void sortByFlow(
            List<T> items,
            int originOnline,
            ToIntFunction<T> destOnlineFn,
            Weights w,
            boolean shuffleNearTies,
            Random rng
    ) {
        if (items == null || items.size() < 2 || w == null || !w.enabled()) {
            if (items != null && shuffleNearTies && items.size() > 1 && rng != null) {
                Collections.shuffle(items, rng);
            }
            return;
        }
        record Scored<T>(T item, double score) {
        }
        List<Scored<T>> scored = new ArrayList<>(items.size());
        for (T item : items) {
            int dest = destOnlineFn.applyAsInt(item);
            scored.add(new Scored<>(item, score(originOnline, dest, w)));
        }
        scored.sort(Comparator.comparingDouble((Scored<T> s) -> s.score).reversed());
        if (shuffleNearTies && rng != null && w.epsilon() > 0) {
            int i = 0;
            while (i < scored.size()) {
                int j = i + 1;
                double base = scored.get(i).score;
                while (j < scored.size() && Math.abs(scored.get(j).score - base) <= w.epsilon()) {
                    j++;
                }
                if (j - i > 1) {
                    Collections.shuffle(scored.subList(i, j), rng);
                }
                i = j;
            }
        }
        items.clear();
        for (Scored<T> s : scored) {
            items.add(s.item);
        }
    }

    /** Stable ranking for tests: no near-tie shuffle. */
    public static List<Integer> rankDestOnlines(int originOnline, List<Integer> destOnlines, Weights w) {
        List<Integer> copy = new ArrayList<>(destOnlines);
        sortByFlow(copy, originOnline, Integer::intValue, w, false, null);
        return copy;
    }
}
