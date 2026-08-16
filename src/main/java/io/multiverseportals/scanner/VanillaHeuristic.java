package io.multiverseportals.scanner;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Soft "ordinary survival world" score for public Multi bind.
 * Paper / Purpur / Spigot / official Vanilla are all fine. Survival, skyblock, and the
 * stock MOTD {@code A Minecraft Server} are good.
 * Fabric/Forge/modpacks and minigames (bedwars, kitpvp, networks) go last.
 */
public final class VanillaHeuristic {

    public static final int VANILLA_LIKE = 60;

    private static final Pattern PLAIN_MC = Pattern.compile("^1\\.\\d{1,2}(\\.\\d{1,2})?([.-](pre|rc)\\d*)?$");

    private static final String[] CATALOG_SOFTWARE = {
            "cornbread", "minescan", "slowstack", "hub", "unknown", "scan", "mcsrvstat"
    };

    private static final String[] MODDED = {
            "fabric", "forge", "neoforge", "quilt", "arclight", "mohist", "magma",
            "catserver", "banner", "youer", "cardboard", "sponge", "modded", "modpack"
    };

    private static final String[] SIMPLE_MOTD = {
            "vanilla smp", "semi-vanilla", "semivanilla", "almost vanilla", "vanilla+",
            "vanilla survival", "no plugins", "no mods", "vanilla experience", "vanilla server",
            "survival smp", "survival server", "hardcore smp", "выживание", "ваниль", "без модов",
            "skyblock", "sky block", "скайблок", "oneblock", "one block", "ванблок"
    };

    private static final String[] COMPLEX = {
            "hypixel", "bedwars", "skywars", "kitpvp", "kit pvp", "factions",
            "prison", "pixelmon", "cobblemon", "manhunt", "bed wars", "sky wars",
            "the hive", "cubecraft", "mineplex", "funcraft", "anarchy", "2b2t",
            "allthemods", "atm9", "atm10", "rlcraft", "modpack", "minigame", "mini-game",
            "mini games", "мини-игр", "мини игр", "призон", "грифер",
            "parkour", "dropper", "murder mystery", "among us",
            "creative plot", "plot world", "kitmap", "hcf", "opfactions", "duels",
            "practice pvp", "mmorpg", "modded"
    };

    private VanillaHeuristic() {}

    public static boolean looksVanilla(String software, String version, String motd) {
        return score(software, version, motd) >= VANILLA_LIKE;
    }

    /**
     * 0–100. Higher = ordinary survival world (Paper is OK). Lower = mods or minigames.
     */
    public static int score(String software, String version, String motd) {
        String brand = compact(ignoreCatalog(software) + " " + nullToEmpty(version));
        String m = compact(motd);

        if (containsAny(brand, MODDED) || containsAny(m, MODDED)) {
            return 8;
        }
        if (containsAny(m, COMPLEX)) {
            return 12;
        }

        int s = 68;
        if (isDefaultMotd(m) || containsAny(m, SIMPLE_MOTD) || m.contains(" vanilla") || m.contains("survival")
                || m.contains("выживание") || m.contains("ваниль")
                || m.contains("skyblock") || m.contains("скайблок")) {
            s += 18;
        }
        if (containsAny(brand, new String[]{"vanilla"})) {
            s += 6;
        }
        if (s > 100) {
            return 100;
        }
        return s;
    }

    /** Untouched vanilla/Paper default MOTD — usually a plain survival world. */
    static boolean isDefaultMotd(String compactMotd) {
        return "a minecraft server".equals(compactMotd);
    }

    static boolean isPlainMcVersion(String version) {
        if (version == null || version.isBlank()) {
            return false;
        }
        String v = version.trim();
        int space = v.indexOf(' ');
        if (space > 0) {
            v = v.substring(0, space);
        }
        return PLAIN_MC.matcher(v).matches();
    }

    private static String ignoreCatalog(String software) {
        String s = compact(software);
        if (s.isEmpty()) {
            return "";
        }
        for (String cat : CATALOG_SOFTWARE) {
            if (s.equals(cat)) {
                return "";
            }
        }
        return software;
    }

    private static boolean containsAny(String hay, String[] needles) {
        if (hay == null || hay.isEmpty()) {
            return false;
        }
        for (String n : needles) {
            if (hay.contains(n)) {
                return true;
            }
        }
        return false;
    }

    private static String compact(String s) {
        String t = nullToEmpty(s)
                .replace('\n', ' ')
                .replace('\r', ' ')
                .replaceAll("§x(§[0-9a-fA-F]){6}", "")
                .replaceAll("§[0-9a-fk-orA-FK-OR]", "")
                .replaceAll("&[0-9a-fk-orA-FK-OR]", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ")
                .trim();
        return t;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
