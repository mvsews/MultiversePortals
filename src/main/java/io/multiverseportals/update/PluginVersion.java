package io.multiverseportals.update;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Semver-ish: major.minor.patch[-channel]. Compare notify on major.minor only. */
public final class PluginVersion implements Comparable<PluginVersion> {

    private static final Pattern RE = Pattern.compile(
            "^\\s*v?(\\d+)(?:\\.(\\d+))?(?:\\.(\\d+))?([\\-+].*)?\\s*$",
            Pattern.CASE_INSENSITIVE);

    private final int major;
    private final int minor;
    private final int patch;
    private final String raw;

    public PluginVersion(int major, int minor, int patch, String raw) {
        this.major = Math.max(0, major);
        this.minor = Math.max(0, minor);
        this.patch = Math.max(0, patch);
        this.raw = raw == null ? major + "." + minor + "." + patch : raw.trim();
    }

    public static Optional<PluginVersion> parse(String s) {
        if (s == null || s.isBlank()) {
            return Optional.empty();
        }
        Matcher m = RE.matcher(s.trim());
        if (!m.matches()) {
            return Optional.empty();
        }
        int maj = Integer.parseInt(m.group(1));
        int min = m.group(2) != null ? Integer.parseInt(m.group(2)) : 0;
        int pat = m.group(3) != null ? Integer.parseInt(m.group(3)) : 0;
        return Optional.of(new PluginVersion(maj, min, pat, s.trim()));
    }

    public int major() {
        return major;
    }

    public int minor() {
        return minor;
    }

    public int patch() {
        return patch;
    }

    public String raw() {
        return raw;
    }

    /** First two digits: 1.1.5 → "1.1" */
    public String majorMinor() {
        return major + "." + minor;
    }

    /** True when remote major.minor is newer (patch ignored). Release and beta both notify. */
    public boolean isMajorMinorBehind(PluginVersion latest) {
        if (latest == null) {
            return false;
        }
        if (latest.major != major) {
            return latest.major > major;
        }
        return latest.minor > minor;
    }

    @Override
    public int compareTo(PluginVersion o) {
        if (major != o.major) {
            return Integer.compare(major, o.major);
        }
        if (minor != o.minor) {
            return Integer.compare(minor, o.minor);
        }
        return Integer.compare(patch, o.patch);
    }

    @Override
    public String toString() {
        return raw.isBlank() ? major + "." + minor + "." + patch : raw;
    }

    public String normalizeDisplay() {
        String base = major + "." + minor + "." + patch;
        String lower = raw.toLowerCase(Locale.ROOT);
        if (lower.contains("beta") || lower.contains("-b") || lower.contains("rc")) {
            int dash = raw.indexOf('-');
            if (dash > 0) {
                return base + raw.substring(dash);
            }
        }
        return base;
    }
}
