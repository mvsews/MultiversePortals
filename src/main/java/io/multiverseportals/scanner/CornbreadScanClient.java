package io.multiverseportals.scanner;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.multiverseportals.config.PluginConfig;
import io.multiverseportals.db.Database;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Public Minecraft server database (Cornbread / MCSS API).
 * https://api.cornbread2100.com/v1/servers/random
 */
public final class CornbreadScanClient {

    private final JavaPlugin plugin;
    private final PluginConfig config;
    private final Database db;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .build();
    private final CopyOnWriteArrayList<ScannedServer> cache = new CopyOnWriteArrayList<>();
    private final AtomicLong lastRefreshMs = new AtomicLong(0);
    private final AtomicLong lastErrorMs = new AtomicLong(0);
    private volatile String lastError = "";
    private BukkitTask task;

    public CornbreadScanClient(JavaPlugin plugin, PluginConfig config, Database db) {
        this.plugin = plugin;
        this.config = config;
        this.db = db;
    }

    public void start() {
        if (!config.scannerEnabled() || !config.cornbreadEnabled()) {
            return;
        }
        stop();
        int sec = Math.max(30, config.scannerRefreshSeconds());
        task = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::refreshSafe, 60L, sec * 20L);
        plugin.getLogger().info("Cornbread scanner started (refresh every " + sec + "s)");
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    public List<ScannedServer> cached() {
        return Collections.unmodifiableList(new ArrayList<>(cache));
    }

    public int cacheSize() {
        return cache.size();
    }

    public long lastRefreshMs() {
        return lastRefreshMs.get();
    }

    public String lastError() {
        return lastError;
    }

    public void refreshNow() {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, this::refreshSafe);
    }

    public void refreshBlocking() throws Exception {
        lastRefreshMs.set(0);
        refresh();
    }

    private void refreshSafe() {
        try {
            refresh();
        } catch (Throwable t) {
            lastError = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
            lastErrorMs.set(System.currentTimeMillis());
            plugin.getLogger().warning("Cornbread refresh failed: " + lastError);
        }
    }

    private void refresh() throws Exception {
        if (!config.cornbreadEnabled()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastRefreshMs.get() < 5000 && !cache.isEmpty()) {
            return;
        }
        if (now - lastErrorMs.get() < 15000 && lastError.contains("429")) {
            return;
        }

        String base = config.cornbreadBaseUrl().replaceAll("/$", "");
        String version = config.scannerVersionPrefix();
        if (version == null || version.isBlank()) {
            version = Bukkit.getMinecraftVersion();
            int dot = version.indexOf('.');
            if (dot > 0) {
                int second = version.indexOf('.', dot + 1);
                if (second > 0) {
                    version = version.substring(0, second);
                }
            }
        }

        int count = Math.min(50, Math.max(1, config.scannerSampleCount()));
        StringBuilder q = new StringBuilder();
        q.append("limit=").append(count);
        q.append("&minPlayers=").append(Math.max(0, config.scannerMinPlayers()));
        if (version != null && !version.isBlank()) {
            q.append("&version=").append(URLEncoder.encode(version, StandardCharsets.UTF_8));
        }

        URI uri = URI.create(base + "/servers/random?" + q);
        HttpRequest req = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/json")
                .header("User-Agent", "MultiversePortals/0.1")
                .GET()
                .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() == 429) {
            lastError = "429 rate limit";
            lastErrorMs.set(now);
            throw new IllegalStateException("429");
        }
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("HTTP " + resp.statusCode());
        }

        JsonObject root = JsonParser.parseString(resp.body()).getAsJsonObject();
        JsonArray arr = root.has("data") ? root.getAsJsonArray("data") : new JsonArray();
        List<ScannedServer> next = new ArrayList<>();
        // Cornbread lastSeen is often stale vs MineScan — optional separate TTL (0 = off)
        long maxAgeMs = config.cornbreadMaxAgeHours() * 3600_000L;

        for (JsonElement el : arr) {
            if (!el.isJsonObject()) {
                continue;
            }
            ScannedServer s = map(el.getAsJsonObject());
            if (s == null || s.full()) {
                continue;
            }
            if (maxAgeMs > 0 && s.lastSeenMs() > 0 && now - s.lastSeenMs() > maxAgeMs) {
                continue;
            }
            if (config.scannerExcludeWhitelist() && "whitelist".equalsIgnoreCase(s.authMode())) {
                continue;
            }
            String mode = config.scannerAuthMode().toLowerCase(Locale.ROOT);
            if (!"any".equals(mode) && !mode.isBlank()) {
                if ("online".equals(mode) && "offline".equalsIgnoreCase(s.authMode())) {
                    continue;
                }
                if ("offline".equals(mode) && "online".equalsIgnoreCase(s.authMode())) {
                    continue;
                }
            }
            next.add(s);
        }

        cache.clear();
        cache.addAll(next);
        lastRefreshMs.set(System.currentTimeMillis());
        lastError = "";
        int stored = 0;
        if (db != null) {
            stored = db.upsertScannedServers(next);
            db.pruneLocalCatalogIfNeeded(config);
        }
        plugin.getLogger().info("Cornbread cache: " + cache.size() + " servers (version~" + version
                + (stored > 0 ? ", catalog+" + stored : "") + ")");
    }

    private static ScannedServer map(JsonObject o) {
        if (!o.has("ip") || o.get("ip").isJsonNull()) {
            return null;
        }
        String host = intToIpv4(o.get("ip").getAsLong());
        if (host == null) {
            return null;
        }
        int port = o.has("port") && !o.get("port").isJsonNull() ? o.get("port").getAsInt() : 25565;
        String version = null;
        if (o.has("version") && o.get("version").isJsonObject()) {
            JsonObject v = o.getAsJsonObject("version");
            version = str(v, "name");
        }
        int online = 0;
        int max = 0;
        if (o.has("players") && o.get("players").isJsonObject()) {
            JsonObject p = o.getAsJsonObject("players");
            if (p.has("online") && !p.get("online").isJsonNull()) {
                online = p.get("online").getAsInt();
            }
            if (p.has("max") && !p.get("max").isJsonNull()) {
                max = p.get("max").getAsInt();
            }
        }
        boolean full = max > 0 && online >= max;
        String auth = "online";
        if (o.has("cracked") && !o.get("cracked").isJsonNull() && o.get("cracked").getAsBoolean()) {
            auth = "offline";
        }
        if (o.has("whitelisted") && !o.get("whitelisted").isJsonNull() && o.get("whitelisted").getAsBoolean()) {
            auth = "whitelist";
        }
        String motd = str(o, "description");
        long lastSeen = 0;
        String ls = str(o, "lastSeen");
        if (ls != null) {
            try {
                long sec = Long.parseLong(ls.trim());
                lastSeen = sec < 10_000_000_000L ? sec * 1000L : sec;
            } catch (NumberFormatException ignored) {
            }
        }
        return new ScannedServer(host, port, version, auth, "cornbread", motd, online, max, full, lastSeen, "cornbread");
    }

    /** Cornbread stores IPv4 as unsigned 32-bit integer. */
    static String intToIpv4(long ip) {
        long u = ip & 0xFFFF_FFFFL;
        return ((u >> 24) & 0xFF) + "." + ((u >> 16) & 0xFF) + "." + ((u >> 8) & 0xFF) + "." + (u & 0xFF);
    }

    private static String str(JsonObject o, String key) {
        if (!o.has(key) || o.get(key).isJsonNull()) {
            return null;
        }
        return o.get(key).getAsString();
    }
}
