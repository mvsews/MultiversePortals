package io.multiverseportals.federation;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.multiverseportals.MultiversePortalsPlugin;
import io.multiverseportals.config.PluginConfig;
import io.multiverseportals.db.Database;
import io.multiverseportals.model.PeerPolicy;
import io.multiverseportals.model.TrustedPeer;
import io.multiverseportals.portal.PortalService;
import io.multiverseportals.travel.TravelService;
import io.multiverseportals.util.Hmac;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.bukkit.Bukkit;

public final class FederationServer {

    private final MultiversePortalsPlugin plugin;
    private final PluginConfig config;
    private final Database db;
    private final TravelService travelService;
    private final PortalService portalService;
    private final CatalogShareService catalogShare;
    private final Gson gson = new Gson();
    private HttpServer server;

    public FederationServer(
            MultiversePortalsPlugin plugin,
            PluginConfig config,
            Database db,
            TravelService travelService,
            PortalService portalService,
            CatalogShareService catalogShare
    ) {
        this.plugin = plugin;
        this.config = config;
        this.db = db;
        this.travelService = travelService;
        this.portalService = portalService;
        this.catalogShare = catalogShare;
    }

    public void start() throws IOException {
        String base = config.federationPath();
        server = HttpServer.create(new InetSocketAddress(config.federationBind(), config.federationPort()), 0);
        server.createContext(base, this::handle);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        plugin.getLogger().info("Federation API http://" + config.federationBind() + ":" + config.federationPort() + base);
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private void handle(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            String base = config.federationPath();
            String sub = path.startsWith(base) ? path.substring(base.length()) : path;
            String method = ex.getRequestMethod();

            // Public portal directory (GET or POST) — coords, dest, optional comment signs
            if ("/portals".equals(sub) || "/portals/export".equals(sub)) {
                if (!"GET".equalsIgnoreCase(method) && !"POST".equalsIgnoreCase(method)) {
                    write(ex, 405, err("method"));
                    return;
                }
                handlePortals(ex, method, sub);
                return;
            }

            // Public server-icon.png from central registry
            if (sub != null && sub.startsWith("/icon/")) {
                if (!"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method)) {
                    write(ex, 405, err("method"));
                    return;
                }
                handleIcon(ex, sub.substring("/icon/".length()), method);
                return;
            }

            if (!"POST".equalsIgnoreCase(method)) {
                write(ex, 405, err("method"));
                return;
            }

            byte[] bytes;
            try (InputStream in = ex.getRequestBody()) {
                bytes = in.readAllBytes();
            }
            String raw = new String(bytes, StandardCharsets.UTF_8);
            String from = header(ex, "X-MVP-Server");
            String ts = header(ex, "X-MVP-Timestamp");
            String sig = header(ex, "X-MVP-Signature");

            if ("/catalog/export".equals(sub) || "/catalog/announce".equals(sub)) {
                handleCatalog(ex, sub, from, ts, sig, raw);
                return;
            }

            if ("/scanner/candidates".equals(sub) || "/scanner/report".equals(sub)) {
                handleScannerPool(ex, sub, from, ts, sig, raw);
                return;
            }

            // Open-network travel landing request (catalog secret or trusted peer)
            if ("/travel/offer".equals(sub)) {
                handleTravelOfferHttp(ex, from, ts, sig, raw);
                return;
            }

            Optional<TrustedPeer> peerOpt = from == null ? Optional.empty() : db.findPeer(from);
            if (peerOpt.isEmpty()) {
                write(ex, 403, err("unknown peer"));
                return;
            }
            TrustedPeer peer = peerOpt.get();
            String payload = from + "|" + ts + "|" + raw;
            if (!Hmac.verify(peer.sharedSecret(), payload, sig)) {
                write(ex, 403, err("bad signature"));
                return;
            }
            long timestamp = Long.parseLong(ts);
            if (Math.abs(System.currentTimeMillis() - timestamp) > 120_000L) {
                write(ex, 403, err("timestamp expired"));
                return;
            }

            JsonObject body = gson.fromJson(raw.isBlank() ? "{}" : raw, JsonObject.class);
            JsonObject out = switch (sub) {
                case "/policy/get" -> policyGet(peer);
                case "/travel/offer" -> travelService.handleRemoteOffer(peer, body);
                case "/portal/heartbeat" -> portalService.handleRemoteHeartbeat(peer, body);
                case "/portal/pair/propose" -> portalService.handlePairPropose(peer, body);
                case "/portal/pair/accept" -> portalService.handlePairAccept(peer, body);
                case "/portal/pair/resolve" -> portalService.handlePairResolve(peer, body);
                default -> err("not found");
            };
            int code = 200;
            if (out.has("error")) {
                String e = out.get("error").getAsString();
                code = switch (e) {
                    case "not found" -> 404;
                    case "rejected" -> 409;
                    default -> 400;
                };
            }
            write(ex, code, out);
        } catch (Exception e) {
            plugin.getLogger().warning("Federation error: " + e.getMessage());
            write(ex, 500, err("internal"));
        }
    }

    private void handleIcon(HttpExchange ex, String serverIdRaw, String method) throws IOException {
        if (!config.catalogSharePublicRead() && !config.catalogShareEnabled()) {
            write(ex, 403, err("icon unavailable"));
            return;
        }
        String serverId = java.net.URLDecoder.decode(
                serverIdRaw == null ? "" : serverIdRaw, StandardCharsets.UTF_8).trim();
        if (serverId.isBlank() || serverId.contains("/") || serverId.contains("..")) {
            write(ex, 404, err("not found"));
            return;
        }
        byte[] png = null;
        if (plugin.registry() != null && plugin.registry().enabled()) {
            png = plugin.registry().iconPng(serverId);
        }
        if (png == null || png.length == 0) {
            // Local fallback for this hub itself
            if (serverId.equalsIgnoreCase(config.serverId())) {
                var brand = io.multiverseportals.util.ServerBranding.local();
                if (brand.hasIcon()) {
                    png = brand.iconPng();
                }
            }
        }
        if (png == null || png.length == 0) {
            write(ex, 404, err("no icon"));
            return;
        }
        ex.getResponseHeaders().set("Content-Type", "image/png");
        ex.getResponseHeaders().set("Cache-Control", "public, max-age=300");
        if ("HEAD".equalsIgnoreCase(method)) {
            ex.sendResponseHeaders(200, -1);
            ex.close();
            return;
        }
        ex.sendResponseHeaders(200, png.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(png);
        }
    }

    private void handlePortals(HttpExchange ex, String method, String sub) throws IOException {
        if (!config.catalogShareEnabled() && !config.catalogSharePublicRead()) {
            // Still allow if federation is on and public-read for catalog
        }
        boolean publicOk = config.catalogSharePublicRead();
        String from = header(ex, "X-MVP-Server");
        String ts = header(ex, "X-MVP-Timestamp");
        String sig = header(ex, "X-MVP-Signature");
        if (!publicOk) {
            if (from == null || ts == null || sig == null
                    || !Hmac.verify(config.catalogShareNetworkSecret(), from + "|" + ts + "|{}", sig)) {
                write(ex, 403, err("portals auth required"));
                return;
            }
        }

        String queryServer = null;
        if ("POST".equalsIgnoreCase(method)) {
            byte[] bytes;
            try (InputStream in = ex.getRequestBody()) {
                bytes = in.readAllBytes();
            }
            String raw = new String(bytes, StandardCharsets.UTF_8);
            if (!raw.isBlank()) {
                JsonObject body = gson.fromJson(raw, JsonObject.class);
                if (body != null && body.has("serverId")) {
                    queryServer = body.get("serverId").getAsString();
                }
            }
        } else {
            String q = ex.getRequestURI().getQuery();
            if (q != null) {
                for (String part : q.split("&")) {
                    String[] kv = part.split("=", 2);
                    if (kv.length == 2 && "serverId".equals(kv[0])) {
                        queryServer = java.net.URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
                    }
                }
            }
        }

        JsonObject out;
        if (queryServer != null && !queryServer.isBlank()
                && !queryServer.equalsIgnoreCase(config.serverId())
                && catalogShare != null) {
            // Other server: from hub registry snapshot
            out = catalogShare.buildRegistryPortalsPayload(queryServer);
        } else if (catalogShare != null) {
            // This server: live scan (comment signs from world)
            try {
                CompletableFuture<JsonObject> fut = new CompletableFuture<>();
                Bukkit.getScheduler().runTask(plugin, () -> {
                    try {
                        fut.complete(catalogShare.buildLivePortalsPayload());
                    } catch (Throwable t) {
                        fut.completeExceptionally(t);
                    }
                });
                out = fut.get(8, TimeUnit.SECONDS);
            } catch (Exception e) {
                plugin.getLogger().warning("Live portals export failed: " + e.getMessage());
                out = catalogShare.buildRegistryPortalsPayload(config.serverId());
            }
        } else {
            out = err("catalog unavailable");
            write(ex, 503, out);
            return;
        }
        write(ex, 200, out);
    }

    private void handleCatalog(HttpExchange ex, String sub, String from, String ts, String sig, String raw)
            throws IOException {
        if (!config.catalogShareEnabled()) {
            write(ex, 403, err("catalog share disabled"));
            return;
        }
        boolean export = "/catalog/export".equals(sub);
        boolean publicExport = export && config.catalogSharePublicRead();
        if (!publicExport) {
            if (from == null || ts == null || sig == null) {
                write(ex, 403, err("missing catalog auth"));
                return;
            }
            String payload = from + "|" + ts + "|" + raw;
            if (!Hmac.verify(config.catalogShareNetworkSecret(), payload, sig)) {
                write(ex, 403, err("bad catalog signature"));
                return;
            }
            long timestamp = Long.parseLong(ts);
            if (Math.abs(System.currentTimeMillis() - timestamp) > 120_000L) {
                write(ex, 403, err("timestamp expired"));
                return;
            }
        } else if (from != null && ts != null && sig != null && !sig.isBlank()) {
            // Prefer verifying when client sent a signature
            String payload = from + "|" + ts + "|" + raw;
            if (!Hmac.verify(config.catalogShareNetworkSecret(), payload, sig)) {
                // Still allow public-read export on bad sig? Safer to reject if they tried to auth
                write(ex, 403, err("bad catalog signature"));
                return;
            }
            try {
                long timestamp = Long.parseLong(ts);
                if (Math.abs(System.currentTimeMillis() - timestamp) > 120_000L) {
                    write(ex, 403, err("timestamp expired"));
                    return;
                }
            } catch (NumberFormatException e) {
                write(ex, 403, err("bad timestamp"));
                return;
            }
        }

        if (catalogShare == null) {
            write(ex, 503, err("catalog unavailable"));
            return;
        }
        JsonObject body = gson.fromJson(raw.isBlank() ? "{}" : raw, JsonObject.class);
        JsonObject out;
        if (export) {
            out = catalogShare.buildExportPayload();
        } else {
            out = catalogShare.handleAnnounce(body);
        }
        write(ex, 200, out);
    }

    private void handleScannerPool(HttpExchange ex, String sub, String from, String ts, String sig, String raw)
            throws IOException {
        if (!config.catalogShareEnabled() && !config.scannerHubPoolEnabled()) {
            write(ex, 403, err("scanner pool disabled"));
            return;
        }
        boolean candidates = "/scanner/candidates".equals(sub);
        boolean publicOk = candidates && config.catalogSharePublicRead();
        if (!publicOk) {
            if (from == null || ts == null || sig == null) {
                write(ex, 403, err("missing scanner auth"));
                return;
            }
            String payload = from + "|" + ts + "|" + raw;
            if (!Hmac.verify(config.catalogShareNetworkSecret(), payload, sig)) {
                write(ex, 403, err("bad scanner signature"));
                return;
            }
            try {
                if (Math.abs(System.currentTimeMillis() - Long.parseLong(ts)) > 120_000L) {
                    write(ex, 403, err("timestamp expired"));
                    return;
                }
            } catch (NumberFormatException e) {
                write(ex, 403, err("bad timestamp"));
                return;
            }
        }

        var registry = plugin.registry();
        if (registry == null || !registry.enabled()) {
            write(ex, 503, err("scanner pool unavailable"));
            return;
        }

        JsonObject body = gson.fromJson(raw.isBlank() ? "{}" : raw, JsonObject.class);
        if (body == null) {
            body = new JsonObject();
        }

        if (candidates) {
            write(ex, 200, buildScannerCandidates(registry, body));
        } else {
            write(ex, 200, ingestScannerReports(registry, body));
        }
    }

    private JsonObject buildScannerCandidates(io.multiverseportals.db.RegistryDatabase registry, JsonObject body) {
        String branch = "";
        if (body.has("versionBranch") && !body.get("versionBranch").isJsonNull()) {
            branch = body.get("versionBranch").getAsString();
        } else if (body.has("mcVersion") && !body.get("mcVersion").isJsonNull()) {
            branch = io.multiverseportals.db.RegistryDatabase.versionBranchOf(body.get("mcVersion").getAsString());
        }
        boolean needBedrock = body.has("needBedrock") && body.get("needBedrock").getAsBoolean();
        int bedProto = body.has("bedrockProtocol") && !body.get("bedrockProtocol").isJsonNull()
                ? body.get("bedrockProtocol").getAsInt() : 0;
        int limit = body.has("limit") && !body.get("limit").isJsonNull()
                ? body.get("limit").getAsInt() : config.scannerVerifiedLimit();
        double minScore = body.has("minScore") && !body.get("minScore").isJsonNull()
                ? body.get("minScore").getAsDouble() : -50;
        java.util.Set<String> exclude = new java.util.HashSet<>();
        if (body.has("excludeHosts") && body.get("excludeHosts").isJsonArray()) {
            for (var el : body.getAsJsonArray("excludeHosts")) {
                if (el != null && !el.isJsonNull()) {
                    exclude.add(el.getAsString().trim().toLowerCase(java.util.Locale.ROOT));
                }
            }
        }
        var rows = registry.queryScannerCandidates(branch, needBedrock, bedProto, limit, minScore, exclude);
        com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
        for (var r : rows) {
            JsonObject o = new JsonObject();
            o.addProperty("host", r.host());
            o.addProperty("javaPort", r.javaPort());
            if (r.mcVersion() != null) {
                o.addProperty("mcVersion", r.mcVersion());
            }
            if (r.versionBranch() != null) {
                o.addProperty("versionBranch", r.versionBranch());
            }
            o.addProperty("score", r.score());
            o.addProperty("status", r.status());
            if (r.bedrockPort() > 0) {
                o.addProperty("bedrockPort", r.bedrockPort());
            }
            if (r.bedrockProtocol() > 0) {
                o.addProperty("bedrockProtocol", r.bedrockProtocol());
            }
            if (r.bedrockVersion() != null) {
                o.addProperty("bedrockVersion", r.bedrockVersion());
            }
            if (r.displayName() != null) {
                o.addProperty("displayName", r.displayName());
            }
            if (r.source() != null) {
                o.addProperty("source", r.source());
            }
            if (r.lastOkAt() > 0) {
                o.addProperty("lastOkAt", r.lastOkAt());
            }
            if (r.lastSeenAt() > 0) {
                o.addProperty("lastSeenAt", r.lastSeenAt());
            }
            arr.add(o);
        }
        JsonObject out = new JsonObject();
        out.addProperty("ok", true);
        out.addProperty("hub", true);
        out.add("servers", arr);
        return out;
    }

    private JsonObject ingestScannerReports(io.multiverseportals.db.RegistryDatabase registry, JsonObject body) {
        int n = 0;
        com.google.gson.JsonArray reports = body.has("reports") && body.get("reports").isJsonArray()
                ? body.getAsJsonArray("reports") : new com.google.gson.JsonArray();
        for (var el : reports) {
            if (el == null || !el.isJsonObject()) {
                continue;
            }
            JsonObject r = el.getAsJsonObject();
            if (!r.has("host") || !r.has("javaPort") || !r.has("status")) {
                continue;
            }
            String host = r.get("host").getAsString();
            int port = r.get("javaPort").getAsInt();
            String status = r.get("status").getAsString();
            String mc = r.has("mcVersion") && !r.get("mcVersion").isJsonNull() ? r.get("mcVersion").getAsString() : null;
            Integer bedP = r.has("bedrockPort") && !r.get("bedrockPort").isJsonNull() ? r.get("bedrockPort").getAsInt() : null;
            Integer bedProto = r.has("bedrockProtocol") && !r.get("bedrockProtocol").isJsonNull()
                    ? r.get("bedrockProtocol").getAsInt() : null;
            String bedVer = r.has("bedrockVersion") && !r.get("bedrockVersion").isJsonNull()
                    ? r.get("bedrockVersion").getAsString() : null;
            String label = r.has("displayName") && !r.get("displayName").isJsonNull()
                    ? r.get("displayName").getAsString() : null;
            String source = r.has("source") && !r.get("source").isJsonNull() ? r.get("source").getAsString() : "leaf";
            Double delta = r.has("scoreDelta") && !r.get("scoreDelta").isJsonNull()
                    ? r.get("scoreDelta").getAsDouble() : null;
            registry.upsertScannerProbeReport(host, port, status, mc, bedP, bedProto, bedVer, label, source, delta);
            n++;
        }
        JsonObject out = new JsonObject();
        out.addProperty("ok", true);
        out.addProperty("ingested", n);
        return out;
    }

    private void handleTravelOfferHttp(HttpExchange ex, String from, String ts, String sig, String raw)
            throws IOException {
        boolean okAuth = false;
        if (from != null && ts != null && sig != null) {
            String payload = from + "|" + ts + "|" + raw;
            if (Hmac.verify(config.catalogShareNetworkSecret(), payload, sig)) {
                okAuth = true;
            } else {
                Optional<TrustedPeer> peerOpt = db.findPeer(from);
                if (peerOpt.isPresent() && Hmac.verify(peerOpt.get().sharedSecret(), payload, sig)) {
                    okAuth = true;
                }
            }
            try {
                if (Math.abs(System.currentTimeMillis() - Long.parseLong(ts)) > 120_000L) {
                    write(ex, 403, err("timestamp expired"));
                    return;
                }
            } catch (NumberFormatException e) {
                write(ex, 403, err("bad timestamp"));
                return;
            }
        }
        if (!okAuth && !config.catalogSharePublicRead()) {
            write(ex, 403, err("travel offer auth required"));
            return;
        }
        JsonObject body = gson.fromJson(raw.isBlank() ? "{}" : raw, JsonObject.class);
        JsonObject out = travelService.handleTravelOffer(body, from);
        write(ex, out.has("ok") && out.get("ok").getAsBoolean() ? 200 : 409, out);
    }

    private JsonObject policyGet(TrustedPeer peer) {
        PeerPolicy local = config.policyFor(peer.serverId());
        JsonObject policy = new JsonObject();
        policy.addProperty("allowTravel", local.allowTravel());
        policy.addProperty("exportInventory", local.exportInventory());
        policy.addProperty("importInventory", local.importInventory());
        policy.addProperty("exportScore", local.exportScore());
        policy.addProperty("importScore", local.importScore());
        JsonObject out = new JsonObject();
        out.add("policy", policy);
        out.addProperty("serverId", config.serverId());
        out.addProperty("displayName", config.displayName());
        out.addProperty("publicHost", config.publicHost());
        out.addProperty("publicPort", config.publicPort());
        return out;
    }

    private static String header(HttpExchange ex, String name) {
        return ex.getRequestHeaders().getFirst(name);
    }

    private JsonObject err(String msg) {
        JsonObject o = new JsonObject();
        o.addProperty("error", msg);
        return o;
    }

    private void write(HttpExchange ex, int code, JsonObject body) throws IOException {
        byte[] data = gson.toJson(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(code, data.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(data);
        }
    }
}
