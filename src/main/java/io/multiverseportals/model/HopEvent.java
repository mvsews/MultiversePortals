package io.multiverseportals.model;

import com.google.gson.JsonObject;

import java.util.UUID;

/**
 * One hop record: who went from where to where, and whether it stuck.
 */
public record HopEvent(
        String id,
        String reporterId,
        String playerUuid,
        String playerName,
        String fromServer,
        String toServer,
        String toHost,
        int toPort,
        String outcome,
        String kind,
        long at
) {

    public static String outcomeOf(PeerEdge.Kind kind) {
        if (kind == null) {
            return "OK";
        }
        return switch (kind) {
            case FAILED -> "BOUNCED";
            case REJECTED -> "REFUSED";
            case ARRIVED, DEPARTED -> "OK";
        };
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        if (id != null) {
            o.addProperty("id", id);
        }
        if (reporterId != null) {
            o.addProperty("reporter", reporterId);
        }
        if (playerUuid != null) {
            o.addProperty("playerUuid", playerUuid);
        }
        if (playerName != null) {
            o.addProperty("player", playerName);
        }
        if (fromServer != null) {
            o.addProperty("from", fromServer);
        }
        if (toServer != null) {
            o.addProperty("to", toServer);
        }
        if (toHost != null && !toHost.isBlank()) {
            o.addProperty("host", toHost);
        }
        if (toPort > 0) {
            o.addProperty("port", toPort);
        }
        o.addProperty("outcome", outcome == null ? "OK" : outcome);
        if (kind != null) {
            o.addProperty("kind", kind);
        }
        o.addProperty("ok", "OK".equalsIgnoreCase(outcome));
        o.addProperty("at", at);
        return o;
    }

    public static HopEvent fromJson(JsonObject o) {
        if (o == null) {
            return null;
        }
        String from = str(o, "from");
        String to = str(o, "to");
        String host = str(o, "host");
        int port = o.has("port") && o.get("port").isJsonPrimitive() ? o.get("port").getAsInt() : 0;
        if ((from == null || from.isBlank()) && (to == null || to.isBlank())
                && (host == null || host.isBlank())) {
            return null;
        }
        String id = str(o, "id");
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
        }
        String outcome = str(o, "outcome");
        if (outcome == null || outcome.isBlank()) {
            outcome = o.has("ok") && o.get("ok").isJsonPrimitive() && !o.get("ok").getAsBoolean()
                    ? "BOUNCED" : "OK";
        }
        long at = o.has("at") ? o.get("at").getAsLong() : System.currentTimeMillis();
        return new HopEvent(
                id,
                str(o, "reporter"),
                str(o, "playerUuid"),
                str(o, "player"),
                from,
                to,
                host,
                port,
                outcome,
                str(o, "kind"),
                at
        );
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
