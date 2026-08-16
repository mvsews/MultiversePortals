package io.multiverseportals.i18n;

import java.util.Locale;

/**
 * Old {@code effects.title}/{@code effects.subtitle} defaults from when travel
 * required a pressure plate. Those strings must not override per-player lang files.
 */
public final class EffectsCopy {

    private EffectsCopy() {
    }

    /** True when a config line is leftover stock copy, not an admin customisation. */
    public static boolean isStaleOverride(String raw) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        String p = plain(raw);
        if (p.isEmpty()) {
            return false;
        }
        if (p.equals("портал") || p.equals("portal") || p.equals("nether portal")) {
            return true;
        }
        if (p.contains("плит") || p.contains("не сход") || p.contains("не двига")) {
            return true;
        }
        if (p.contains("the plate") || p.contains("a plate") || p.contains("pressure plate")) {
            return true;
        }
        if (p.contains("stay on") || p.contains("don't move") || p.contains("dont move")
                || p.contains("don't leave") || p.contains("dont leave")) {
            return true;
        }
        return p.contains("finding a world") || p.contains("finding a server");
    }

    static String plain(String raw) {
        String s = raw.replaceAll("(?i)<[^>]+>", "");
        s = s.replaceAll("§.", "");
        s = s.replace('\u2026', '.').replace("...", ".");
        s = s.replace('ё', 'е');
        return s.trim().toLowerCase(Locale.ROOT);
    }
}
