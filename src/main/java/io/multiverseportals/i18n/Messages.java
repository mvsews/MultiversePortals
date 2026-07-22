package io.multiverseportals.i18n;

import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bundled languages: en (base), de, ru, zh.
 * Server {@code language} is the fallback; with {@code language-per-player}
 * each player's client locale is preferred when we ship that language.
 */
public final class Messages {

    public static final Set<String> SUPPORTED = Set.of("en", "de", "ru", "zh");

    private final JavaPlugin plugin;
    private final Map<String, FileConfiguration> langs = new ConcurrentHashMap<>();
    private volatile String serverLang = "en";
    private volatile boolean perPlayer = true;

    public Messages(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void load(String language) {
        load(language, true);
    }

    public void load(String language, boolean languagePerPlayer) {
        String code = normalize(language);
        for (String id : SUPPORTED) {
            ensureResource("lang/" + id + ".yml");
        }
        langs.clear();
        for (String id : SUPPORTED) {
            langs.put(id, loadYaml("lang/" + id + ".yml"));
        }
        this.serverLang = langs.containsKey(code) ? code : "en";
        this.perPlayer = languagePerPlayer;
        plugin.getLogger().info("Language: " + this.serverLang
                + (perPlayer ? " (per-player locale on, fallback=" + this.serverLang + ")" : " (server-wide)"));
    }

    public String lang() {
        return serverLang;
    }

    public boolean perPlayer() {
        return perPlayer;
    }

    /** Server fallback language string. */
    public String get(String key) {
        return get(key, serverLang);
    }

    public String get(CommandSender sender, String key) {
        if (sender instanceof Player player) {
            return get(key, langFor(player));
        }
        return get(key, serverLang);
    }

    public String get(Player player, String key) {
        return get(key, langFor(player));
    }

    public String get(String key, String langCode) {
        String override = plugin.getConfig().getString("messages." + key);
        if (override != null && !override.isBlank()) {
            return override;
        }
        String primary = normalize(langCode);
        String v = lookup(primary, key);
        if (v != null) {
            return v;
        }
        if (!primary.equals(serverLang)) {
            v = lookup(serverLang, key);
            if (v != null) {
                return v;
            }
        }
        if (!"en".equals(primary) && !"en".equals(serverLang)) {
            v = lookup("en", key);
            if (v != null) {
                return v;
            }
        }
        return key;
    }

    /**
     * Player client locale if supported; otherwise server {@link #lang()}.
     */
    public String langFor(Player player) {
        if (player == null || !perPlayer) {
            return serverLang;
        }
        try {
            Locale loc = player.locale();
            String mapped = mapSupported(loc.getLanguage());
            if (mapped == null) {
                mapped = mapSupported(loc.toLanguageTag());
            }
            if (mapped == null) {
                mapped = mapSupported(loc.toString());
            }
            if (mapped != null && langs.containsKey(mapped)) {
                return mapped;
            }
        } catch (Throwable ignored) {
            // older bridges / odd clients
        }
        return serverLang;
    }

    /** Map to a shipped language, or null if we don't have that locale. */
    public static String mapSupported(String language) {
        if (language == null || language.isBlank()) {
            return null;
        }
        String l = language.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        if (l.startsWith("de")) {
            return "de";
        }
        if (l.startsWith("ru")) {
            return "ru";
        }
        if (l.startsWith("zh") || l.equals("cn") || l.equals("chinese")) {
            return "zh";
        }
        if (l.startsWith("en")) {
            return "en";
        }
        return SUPPORTED.contains(l) ? l : null;
    }

    /** For config {@code language:} — unknown values become {@code en}. */
    public static String normalize(String language) {
        String mapped = mapSupported(language);
        return mapped != null ? mapped : "en";
    }

    private String lookup(String langCode, String key) {
        FileConfiguration cfg = langs.get(langCode);
        if (cfg == null) {
            return null;
        }
        String v = cfg.getString(key);
        if (v == null || v.isBlank()) {
            return null;
        }
        return v;
    }

    private void ensureResource(String path) {
        File out = new File(plugin.getDataFolder(), path);
        if (!out.exists()) {
            plugin.saveResource(path, false);
        }
    }

    private FileConfiguration loadYaml(String path) {
        File file = new File(plugin.getDataFolder(), path);
        if (file.exists()) {
            return YamlConfiguration.loadConfiguration(file);
        }
        InputStream in = plugin.getResource(path);
        if (in == null) {
            return new YamlConfiguration();
        }
        return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
    }
}
