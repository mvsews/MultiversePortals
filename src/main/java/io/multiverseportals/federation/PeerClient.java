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
import java.util.Optional;

public final class PeerClient {

    private final PluginConfig config;
    private final Gson gson = new Gson();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

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
        return postCatalog(federationBaseUrl, "/scanner/candidates", filters == null ? new JsonObject() : filters);
    }

    public Optional<JsonObject> scannerReport(String federationBaseUrl, JsonObject reports) {
        return postCatalog(federationBaseUrl, "/scanner/report", reports == null ? new JsonObject() : reports);
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

    private Optional<JsonObject> postCatalog(
            String federationBaseUrl,
            String path,
            JsonObject body,
            Duration timeout
    ) {
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
                    .timeout(timeout == null ? Duration.ofSeconds(10) : timeout)
                    .header("Content-Type", "application/json")
                    .header("X-MVP-Server", config.serverId())
                    .header("X-MVP-Timestamp", String.valueOf(ts))
                    .header("X-MVP-Signature", sig)
                    .header("X-MVP-Catalog", "1")
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

    private static String trimSlash(String url) {
        if (url.endsWith("/")) {
            return url.substring(0, url.length() - 1);
        }
        return url;
    }
}
