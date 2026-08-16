package io.multiverseportals.model;

import com.google.gson.JsonObject;

import java.util.Locale;

/**
 * This server's directed opinion of one peer — not a single global score.
 * {@code departed}/{@code sentOk}: we sent them a player who stayed.
 * {@code arrived}/{@code receivedOk}: they sent us a player we accepted.
 * {@code failed}/{@code bounced}: our player came back (dest did not keep them).
 * {@code rejected}/{@code refused}: we kicked their player (ingress / guests closed).
 */
public record PeerEdge(
        String peerServerId,
        String peerHost,
        int peerPort,
        int reputation,
        int arrived,
        int departed,
        int failed,
        int rejected,
        long updatedAt
) {

    public enum Kind { ARRIVED, DEPARTED, FAILED, REJECTED }

    public static String key(String serverId, String host, int port) {
        if (serverId != null && !serverId.isBlank()) {
            return "id:" + serverId.trim().toLowerCase(Locale.ROOT);
        }
        String h = host == null ? "" : host.trim().toLowerCase(Locale.ROOT);
        return "hp:" + h + ":" + Math.max(0, port);
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        if (peerServerId != null && !peerServerId.isBlank()) {
            o.addProperty("serverId", peerServerId);
        }
        if (peerHost != null && !peerHost.isBlank()) {
            o.addProperty("host", peerHost);
        }
        if (peerPort > 0) {
            o.addProperty("port", peerPort);
        }
        o.addProperty("reputation", reputation);
        o.addProperty("arrived", arrived);
        o.addProperty("departed", departed);
        o.addProperty("failed", failed);
        o.addProperty("rejected", rejected);
        o.addProperty("receivedOk", arrived);
        o.addProperty("sentOk", departed);
        o.addProperty("bounced", failed);
        o.addProperty("refused", rejected);
        o.addProperty("updatedAt", updatedAt);
        return o;
    }

    public static PeerEdge fromJson(JsonObject o) {
        if (o == null) {
            return null;
        }
        String id = str(o, "serverId");
        String host = str(o, "host");
        int port = intVal(o, "port", 0);
        int arr = firstInt(o, 0, "arrived", "receivedOk");
        int dep = firstInt(o, 0, "departed", "sentOk");
        int fail = firstInt(o, 0, "failed", "bounced");
        int rej = firstInt(o, 0, "rejected", "refused");
        int rep = o.has("reputation") ? o.get("reputation").getAsInt() : (arr + dep - fail - rej);
        long at = o.has("updatedAt") ? o.get("updatedAt").getAsLong() : 0L;
        if ((id == null || id.isBlank()) && (host == null || host.isBlank()) && port <= 0) {
            return null;
        }
        return new PeerEdge(id, host, port, rep, arr, dep, fail, rej, at);
    }

    private static int firstInt(JsonObject o, int fallback, String... keys) {
        for (String k : keys) {
            if (o.has(k) && o.get(k).isJsonPrimitive()) {
                try {
                    return o.get(k).getAsInt();
                } catch (Exception ignored) {
                }
            }
        }
        return fallback;
    }

    private static int intVal(JsonObject o, String k, int fallback) {
        if (!o.has(k) || !o.get(k).isJsonPrimitive()) {
            return fallback;
        }
        try {
            return o.get(k).getAsInt();
        } catch (Exception e) {
            return fallback;
        }
    }

    private static String str(JsonObject o, String k) {
        if (o == null || !o.has(k) || o.get(k).isJsonNull()) {
            return null;
        }
        try {
            String s = o.get(k).getAsString();
            return s == null || s.isBlank() ? null : s;
        } catch (Exception e) {
            return null;
        }
    }
}
