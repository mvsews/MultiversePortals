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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Fetches random public servers from MineScan (https://data.minescan.xyz).
 */
public final class MineScanClient {

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

    public MineScanClient(JavaPlugin plugin, PluginConfig config, Database db) {
        this.plugin = plugin;
        this.config = config;
        this.db = db;
    }

    public void start() {
        if (!config.scannerEnabled()) {
            plugin.getLogger().info("Public scanner disabled");
            return;
        }
        stop();
        int sec = Math.max(30, config.scannerRefreshSeconds());
        task = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::refreshSafe, 40L, sec * 20L);
        plugin.getLogger().info("MineScan client started (refresh every " + sec + "s)");
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

    /** Synchronous refresh for travel retry (call from async thread only). */
    public void refreshBlocking() throws Exception {
        lastRefreshMs.set(0); // force
        refresh();
    }

    private void refreshSafe() {
        try {
            refresh();
        } catch (Exception e) {
            lastError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            lastErrorMs.set(System.currentTimeMillis());
            plugin.getLogger().warning("MineScan refresh failed: " + lastError);
        }
    }

    private void refresh() throws Exception {
        long now = System.currentTimeMillis();
        if (now - lastRefreshMs.get() < 5000 && !cache.isEmpty()) {
            return;
        }
        if (now - lastErrorMs.get() < 15000 && lastError.contains("429")) {
            return;
        }

        String base = config.scannerBaseUrl().replaceAll("/$", "");
        String version = config.scannerVersionPrefix();
        if (version == null || version.isBlank()) {
            version = Bukkit.getMinecraftVersion();
            // partial: 1.21.10 -> 1.21
            int dot = version.indexOf('.');
            if (dot > 0) {
                int second = version.indexOf('.', dot + 1);
                if (second > 0) {
                    version = version.substring(0, second);
                }
            }
        }

        StringBuilder q = new StringBuilder();
        // MineScan rejects count > 20 (HTTP 422)
        q.append("count=").append(Math.min(20, Math.max(1, config.scannerSampleCount())));
        q.append("&minPlayers=").append(Math.max(0, config.scannerMinPlayers()));
        if (version != null && !version.isBlank()) {
            q.append("&version=").append(URLEncoder.encode(version, StandardCharsets.UTF_8));
        }

        URI uri = URI.create(base + "/servers/random?" + q);
        HttpRequest req = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(12))
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
        JsonArray arr = root.has("servers") ? root.getAsJsonArray("servers") : new JsonArray();
        List<ScannedServer> next = new ArrayList<>();
        long maxAgeMs = config.scannerMaxAgeHours() * 3600_000L;

        for (JsonElement el : arr) {
            if (!el.isJsonObject()) {
                continue;
            }
            ScannedServer s = map(el.getAsJsonObject());
            if (s == null) {
                continue;
            }
            if (s.full()) {
                continue;
            }
            if (maxAgeMs > 0 && s.lastSeenMs() > 0 && now - s.lastSeenMs() > maxAgeMs) {
                continue;
            }
            String mode = config.scannerAuthMode().toLowerCase(Locale.ROOT);
            if (!"any".equals(mode) && !mode.isBlank()) {
                if (!mode.equalsIgnoreCase(s.authMode())) {
                    continue;
                }
            }
            if (config.scannerExcludeWhitelist() && "whitelist".equalsIgnoreCase(s.authMode())) {
                continue;
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
        }
        plugin.getLogger().info("MineScan cache: " + cache.size() + " servers (version~" + version
                + (stored > 0 ? ", catalog+" + stored : "") + ")");
    }

    private static ScannedServer map(JsonObject o) {
        String host = str(o, "serverip");
        if (host == null || host.isBlank()) {
            return null;
        }
        int port = o.has("port") && !o.get("port").isJsonNull() ? o.get("port").getAsInt() : 25565;
        String version = str(o, "version");
        String auth = str(o, "authmode");
        String software = str(o, "software");
        String motd = str(o, "motd");
        int online = o.has("onlinePlayers") && !o.get("onlinePlayers").isJsonNull()
                ? o.get("onlinePlayers").getAsInt() : 0;
        int max = o.has("maxPlayers") && !o.get("maxPlayers").isJsonNull()
                ? o.get("maxPlayers").getAsInt() : 0;
        boolean full = o.has("isFull") && o.get("isFull").getAsBoolean();
        long lastSeen = parseTime(str(o, "lastSeen"));
        return new ScannedServer(host, port, version, auth, software, motd, online, max, full, lastSeen, "minescan");
    }

    private static String str(JsonObject o, String key) {
        if (!o.has(key) || o.get(key).isJsonNull()) {
            return null;
        }
        return o.get(key).getAsString();
    }

    private static long parseTime(String iso) {
        if (iso == null || iso.isBlank()) {
            return 0;
        }
        try {
            return Instant.parse(iso).toEpochMilli();
        } catch (Exception e) {
            try {
                // without zone
                return Instant.parse(iso + "Z").toEpochMilli();
            } catch (Exception ignored) {
                return 0;
            }
        }
    }
}
