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
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Slowstack public Minecraft Java DB — https://slowstack.tv/docs/api-reference/servers
 * Requires Bearer API key. Filters: version, minPlayers, onlineOnly, onlineMode, whitelisted.
 */
public final class SlowstackScanClient {

    private final JavaPlugin plugin;
    private final PluginConfig config;
    private final Database db;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .build();
    private final CopyOnWriteArrayList<ScannedServer> cache = new CopyOnWriteArrayList<>();
    private final AtomicLong lastRefreshMs = new AtomicLong(0);
    private final AtomicLong lastErrorMs = new AtomicLong(0);
    private final AtomicInteger nextOffset = new AtomicInteger(0);
    private volatile String lastError = "";
    private BukkitTask task;

    public SlowstackScanClient(JavaPlugin plugin, PluginConfig config, Database db) {
        this.plugin = plugin;
        this.config = config;
        this.db = db;
    }

    public void start() {
        if (!config.scannerEnabled() || !config.slowstackEnabled()) {
            return;
        }
        if (config.slowstackApiKey().isBlank()) {
            plugin.getLogger().warning("Slowstack enabled but scanner.slowstack.api-key is empty — skipped");
            return;
        }
        stop();
        int sec = Math.max(30, config.scannerRefreshSeconds());
        task = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::refreshSafe, 80L, sec * 20L);
        plugin.getLogger().info("Slowstack scanner started (refresh every " + sec + "s)");
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
        } catch (Exception e) {
            lastError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            lastErrorMs.set(System.currentTimeMillis());
            plugin.getLogger().warning("Slowstack refresh failed: " + lastError);
        }
    }

    private void refresh() throws Exception {
        if (!config.slowstackEnabled()) {
            return;
        }
        String apiKey = config.slowstackApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            lastError = "missing api-key";
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastRefreshMs.get() < 5000 && !cache.isEmpty()) {
            return;
        }
        if (now - lastErrorMs.get() < 20000 && lastError.contains("429")) {
            return;
        }

        String base = config.slowstackBaseUrl().replaceAll("/$", "");
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

        int limit = Math.min(50, Math.max(1, config.scannerSampleCount()));
        int offset = Math.max(0, nextOffset.get());
        StringBuilder q = new StringBuilder();
        q.append("limit=").append(limit);
        q.append("&offset=").append(offset);
        if (config.slowstackOnlineOnly()) {
            q.append("&onlineOnly=true");
        }
        q.append("&minPlayers=").append(Math.max(0, config.scannerMinPlayers()));
        if (version != null && !version.isBlank()) {
            q.append("&version=").append(URLEncoder.encode(version, StandardCharsets.UTF_8));
        }
        String auth = config.scannerAuthMode().toLowerCase(Locale.ROOT);
        if ("online".equals(auth)) {
            q.append("&onlineMode=true");
        } else if ("offline".equals(auth)) {
            q.append("&onlineMode=false");
        }
        if (config.scannerExcludeWhitelist()) {
            q.append("&whitelisted=false");
        }
        String countries = config.slowstackCountryCodes();
        if (countries != null && !countries.isBlank()) {
            q.append("&countryCodes=").append(URLEncoder.encode(countries.trim(), StandardCharsets.UTF_8));
        }

        URI uri = URI.create(base + "/servers?" + q);
        HttpRequest req = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + apiKey.trim())
                .header("User-Agent", "MultiversePortals/0.1")
                .GET()
                .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() == 401 || resp.statusCode() == 403) {
            lastError = "HTTP " + resp.statusCode() + " (bad api-key?)";
            lastErrorMs.set(now);
            throw new IllegalStateException(lastError);
        }
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
        boolean hasMore = false;
        if (root.has("meta") && root.get("meta").isJsonObject()) {
            JsonObject meta = root.getAsJsonObject("meta");
            if (meta.has("hasMore") && !meta.get("hasMore").isJsonNull()) {
                hasMore = meta.get("hasMore").getAsBoolean();
            }
        }

        List<ScannedServer> next = new ArrayList<>();
        for (JsonElement el : arr) {
            if (!el.isJsonObject()) {
                continue;
            }
            ScannedServer s = map(el.getAsJsonObject());
            if (s == null || s.full()) {
                continue;
            }
            next.add(s);
        }

        if (hasMore) {
            nextOffset.set(offset + limit);
        } else {
            // restart from a random page for variety on next refresh
            nextOffset.set(ThreadLocalRandom.current().nextInt(0, Math.max(1, offset + 1)));
        }

        cache.clear();
        cache.addAll(next);
        lastRefreshMs.set(System.currentTimeMillis());
        lastError = "";
        int stored = 0;
        if (db != null) {
            stored = db.upsertScannedServers(next);
        }
        plugin.getLogger().info("Slowstack cache: " + cache.size() + " servers (version~" + version
                + ", offset=" + offset
                + (stored > 0 ? ", catalog+" + stored : "") + ")");
    }

    private static ScannedServer map(JsonObject o) {
        if (!o.has("ipAddress") || o.get("ipAddress").isJsonNull()) {
            return null;
        }
        String host = intToIpv4(o.get("ipAddress").getAsLong());
        if (host == null || host.isBlank()) {
            return null;
        }
        int port = o.has("port") && !o.get("port").isJsonNull() ? o.get("port").getAsInt() : 25565;
        String version = str(o, "version");
        int online = o.has("players") && !o.get("players").isJsonNull() ? o.get("players").getAsInt() : 0;
        int max = o.has("maxPlayers") && !o.get("maxPlayers").isJsonNull() ? o.get("maxPlayers").getAsInt() : 0;
        boolean full = max > 0 && online >= max;

        String auth = "online";
        if (o.has("onlineMode") && !o.get("onlineMode").isJsonNull() && !o.get("onlineMode").getAsBoolean()) {
            auth = "offline";
        }
        if (o.has("whitelisted") && !o.get("whitelisted").isJsonNull() && o.get("whitelisted").getAsBoolean()) {
            auth = "whitelist";
        }

        String motd = str(o, "description");
        String extra = str(o, "descriptionExtra");
        if (extra != null && !extra.isBlank()) {
            motd = (motd == null || motd.isBlank()) ? extra : motd + " " + extra;
        }

        long lastSeen = 0;
        String updated = str(o, "updatedAt");
        if (updated != null && !updated.isBlank()) {
            try {
                lastSeen = Instant.parse(updated).toEpochMilli();
            } catch (Exception ignored) {
            }
        }

        return new ScannedServer(host, port, version, auth, "slowstack", motd, online, max, full, lastSeen, "slowstack");
    }

    /** Slowstack ipAddress is a signed 32-bit int (see docs). */
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
