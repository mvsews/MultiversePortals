package io.multiverseportals.federation;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.multiverseportals.config.PluginConfig;
import io.multiverseportals.model.PeerPolicy;
import io.multiverseportals.model.TrustedPeer;
import io.multiverseportals.util.Hmac;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public final class PeerClient {

    private final PluginConfig config;
    private final Gson gson = new Gson();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();
    /** Fail-open circuit for official catalog hubs only (not peer :25765). */
    private final ConcurrentHashMap<String, AtomicInteger> hubFailStreak = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> hubCooldownUntilMs = new ConcurrentHashMap<>();

    public PeerClient(PluginConfig config) {
        this.config = config;
    }

    public Optional<JsonObject> post(TrustedPeer peer, String path, JsonObject body) {
        try {
            long ts = System.currentTimeMillis();
            body.addProperty("from", config.serverId());
            body.addProperty("ts", ts);
            String raw = gson.toJson(body);
            String sig = Hmac.sign(peer.sharedSecret(), config.serverId() + "|" + ts + "|" + raw);

            String url = trimSlash(peer.federationUrl()) + path;
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(8))
                    .header("Content-Type", "application/json")
                    .header("X-MVP-Server", config.serverId())
                    .header("X-MVP-Timestamp", String.valueOf(ts))
                    .header("X-MVP-Signature", sig)
                    .POST(HttpRequest.BodyPublishers.ofString(raw, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                return Optional.empty();
            }
            return Optional.of(gson.fromJson(resp.body(), JsonObject.class));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    public Optional<JsonObject> offerTravel(TrustedPeer peer, JsonObject offer) {
        return post(peer, "/travel/offer", offer);
    }

    public Optional<JsonObject> heartbeatPortal(TrustedPeer peer, JsonObject beat) {
        return post(peer, "/portal/heartbeat", beat);
    }

    public Optional<JsonObject> pairPropose(TrustedPeer peer, JsonObject propose) {
        return post(peer, "/portal/pair/propose", propose);
    }

    /**
     * POST to an arbitrary federation base URL using the open catalog network secret
     * (not a per-peer trust secret).
     */
    public Optional<JsonObject> postCatalog(String federationBaseUrl, String path, JsonObject body) {
        return postCatalog(federationBaseUrl, path, body, Duration.ofSeconds(10));
    }

    public Optional<JsonObject> postCatalog(
            String federationBaseUrl,
            String path,
            JsonObject body,
            Duration timeout
    ) {
        return postCatalogInternal(federationBaseUrl, path, body, timeout);
    }

    public Optional<JsonObject> catalogExport(String federationBaseUrl) {
        return postCatalog(federationBaseUrl, "/catalog/export", new JsonObject());
    }

    public Optional<JsonObject> catalogAnnounce(String federationBaseUrl, JsonObject snapshot) {
        return postCatalog(federationBaseUrl, "/catalog/announce", snapshot);
    }

    /** Short timeout for shutdown notify so disable doesn't hang. */
    public Optional<JsonObject> catalogAnnounceQuick(String federationBaseUrl, JsonObject snapshot) {
        return postCatalog(federationBaseUrl, "/catalog/announce", snapshot, Duration.ofSeconds(3));
    }

    public Optional<JsonObject> scannerCandidates(String federationBaseUrl, JsonObject filters) {
        return scannerCandidates(federationBaseUrl, filters, Duration.ofSeconds(8));
    }

    /** Short timeout for bind-time hub pull — must not block scanner fallback. */
    public Optional<JsonObject> scannerCandidates(
            String federationBaseUrl,
            JsonObject filters,
            Duration timeout
    ) {
        return postCatalog(
                federationBaseUrl,
                "/scanner/candidates",
                filters == null ? new JsonObject() : filters,
                timeout == null ? Duration.ofSeconds(8) : timeout
        );
    }

    public Optional<JsonObject> scannerReport(String federationBaseUrl, JsonObject reports) {
        return postCatalog(
                federationBaseUrl,
                "/scanner/report",
                reports == null ? new JsonObject() : reports,
                Duration.ofSeconds(4)
        );
    }

    /** True when an official hub URL is in cooldown after repeated failures. */
    public boolean isHubCoolingDown(String federationBaseUrl) {
        if (federationBaseUrl == null || federationBaseUrl.isBlank() || !isOfficialHub(federationBaseUrl)) {
            return false;
        }
        Long until = hubCooldownUntilMs.get(hubKey(federationBaseUrl));
        return until != null && System.currentTimeMillis() < until;
    }

    public Optional<PeerPolicy> fetchPolicy(TrustedPeer peer) {
        JsonObject body = new JsonObject();
        Optional<JsonObject> resp = post(peer, "/policy/get", body);
        if (resp.isEmpty()) {
            return Optional.empty();
        }
        JsonObject p = resp.get().getAsJsonObject("policy");
        if (p == null) {
            return Optional.empty();
        }
        return Optional.of(new PeerPolicy(
                p.get("allowTravel").getAsBoolean(),
                p.get("exportInventory").getAsBoolean(),
                p.get("importInventory").getAsBoolean(),
                p.get("exportScore").getAsBoolean(),
                p.get("importScore").getAsBoolean()
        ));
    }

    private Optional<JsonObject> postCatalogInternal(
            String federationBaseUrl,
            String path,
            JsonObject body,
            Duration timeout
    ) {
        if (federationBaseUrl == null || federationBaseUrl.isBlank()) {
            return Optional.empty();
        }
        boolean officialHub = isOfficialHub(federationBaseUrl);
        if (officialHub && isHubCoolingDown(federationBaseUrl)) {
            lastCatalogError = "hub cooldown (using local/scanner fallbacks)";
            return Optional.empty();
        }
        try {
            long ts = System.currentTimeMillis();
            if (body == null) {
                body = new JsonObject();
            }
            body.addProperty("from", config.serverId());
            body.addProperty("ts", ts);
            String raw = gson.toJson(body);
            String secret = config.catalogShareNetworkSecret();
            String sig = Hmac.sign(secret, config.serverId() + "|" + ts + "|" + raw);

            String url = trimSlash(federationBaseUrl) + path;
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(timeout == null ? Duration.ofSeconds(8) : timeout)
                    .header("Content-Type", "application/json")
                    .header("X-MVP-Server", config.serverId())
                    .header("X-MVP-Timestamp", String.valueOf(ts))
                    .header("X-MVP-Signature", sig)
                    .header("X-MVP-Catalog", "1")
                    .POST(HttpRequest.BodyPublishers.ofString(raw, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                lastCatalogError = "HTTP " + resp.statusCode() + " " + url;
                noteHubFailure(federationBaseUrl, officialHub);
                return Optional.empty();
            }
            lastCatalogError = null;
            noteHubSuccess(federationBaseUrl, officialHub);
            return Optional.of(gson.fromJson(resp.body(), JsonObject.class));
        } catch (Exception e) {
            lastCatalogError = e.getClass().getSimpleName() + ": " + e.getMessage();
            noteHubFailure(federationBaseUrl, officialHub);
            return Optional.empty();
        }
    }

    /** Last catalog HTTP failure reason (for push logging); null if last call ok. */
    private volatile String lastCatalogError;

    public String lastCatalogError() {
        return lastCatalogError;
    }

    private void noteHubSuccess(String federationBaseUrl, boolean officialHub) {
        if (!officialHub) {
            return;
        }
        String key = hubKey(federationBaseUrl);
        hubFailStreak.computeIfAbsent(key, k -> new AtomicInteger()).set(0);
        hubCooldownUntilMs.remove(key);
    }

    private void noteHubFailure(String federationBaseUrl, boolean officialHub) {
        if (!officialHub) {
            return;
        }
        String key = hubKey(federationBaseUrl);
        int n = hubFailStreak.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
        // After 3 fails: cool down 2–5 min so bind/travel don't wait on a dead hub.
        if (n >= 3) {
            long coolMs = Math.min(300_000L, 60_000L * Math.min(n - 2, 5));
            hubCooldownUntilMs.put(key, System.currentTimeMillis() + coolMs);
            lastCatalogError = "hub cooldown " + (coolMs / 1000) + "s after " + n + " failures";
        }
    }

    private boolean isOfficialHub(String federationBaseUrl) {
        String u = trimSlash(federationBaseUrl).toLowerCase(Locale.ROOT);
        if (u.contains("mp.mvse.ws")) {
            return true;
        }
        for (String b : config.catalogShareBootstrapUrls()) {
            if (b == null || b.isBlank() || "none".equalsIgnoreCase(b.trim())) {
                continue;
            }
            if (trimSlash(b).equalsIgnoreCase(trimSlash(federationBaseUrl))) {
                return true;
            }
        }
        return false;
    }

    private static String hubKey(String url) {
        return trimSlash(url).toLowerCase(Locale.ROOT);
    }

    private static String trimSlash(String url) {
        if (url == null) {
            return "";
        }
        if (url.endsWith("/")) {
            return url.substring(0, url.length() - 1);
        }
        return url;
    }
}
