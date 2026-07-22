package io.multiverseportals.portal;

import io.multiverseportals.model.Portal;
import io.multiverseportals.model.PortalStatus;
import io.multiverseportals.model.PortalType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;

/**
 * Sign layout for federation portals:
 * <pre>
 *  Portal
 *  Scan...          |  ShortServerName (color from name)
 *  ->  or  <->
 *  [invite code while pairing]
 * </pre>
 */
public final class PortalSigns {

    public static final int NAME_MAX = 14;

    private PortalSigns() {}

    public static void update(Portal portal) {
        if (portal == null || portal.frame() == null) {
            return;
        }
        World world = Bukkit.getWorld(portal.frame().world());
        if (world == null) {
            return;
        }
        Block block = world.getBlockAt(portal.frame().x(), portal.frame().y(), portal.frame().z());
        if (!(block.getState() instanceof Sign sign)) {
            return;
        }

        sign.line(0, Component.text("Portal").color(NamedTextColor.GOLD));

        if (isReady(portal)) {
            String label = destinationLabel(portal);
            sign.line(1, Component.text(truncate(label, NAME_MAX)).color(colorFromName(label)));
        } else {
            sign.line(1, Component.text("Scan...").color(NamedTextColor.GRAY));
        }

        sign.line(2, Component.text(directionGlyph(portal)).color(NamedTextColor.DARK_AQUA));

        if (portal.type() == PortalType.PAIR
                && portal.status() == PortalStatus.PENDING_PAIR
                && portal.pairInviteCode() != null
                && !portal.pairInviteCode().isBlank()) {
            sign.line(3, Component.text(truncate(portal.pairInviteCode(), NAME_MAX)).color(NamedTextColor.DARK_GRAY));
        } else {
            sign.line(3, Component.empty());
        }

        sign.update(true);
    }

    public static boolean isReady(Portal portal) {
        if (portal.type() == PortalType.MULTI) {
            return portal.status() == PortalStatus.ACTIVE && portal.hasBoundDestination();
        }
        return portal.status() == PortalStatus.ACTIVE
                && portal.pairServerId() != null
                && !portal.pairServerId().isBlank();
    }

    public static String directionGlyph(Portal portal) {
        return portal.type() == PortalType.PAIR ? "<->" : "->";
    }

    public static String destinationLabel(Portal portal) {
        if (portal.type() == PortalType.PAIR) {
            return cleanLabel(portal.pairServerId());
        }
        String fromBound = cleanLabel(portal.boundVersion());
        if (!fromBound.isBlank() && !looksLikeVersionOnly(fromBound)) {
            return fromBound;
        }
        String host = portal.boundHost();
        if (host == null || host.isBlank()) {
            return "?";
        }
        // drop trailing domain bits for readability when needed
        return host;
    }

    public static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        String t = s.trim();
        if (t.length() <= max) {
            return t;
        }
        return t.substring(0, max);
    }

    public static TextColor colorFromName(String name) {
        if (name == null || name.isBlank()) {
            return NamedTextColor.AQUA;
        }
        int h = name.hashCode();
        // Stable pleasant hue from name; avoid too-dark values for sign readability
        float hue = Integer.remainderUnsigned(h, 360) / 360f;
        float sat = Math.min(0.9f, 0.55f + (Integer.remainderUnsigned(h >>> 8, 35) / 100f));
        float bri = Math.min(1f, 0.85f + (Integer.remainderUnsigned(h >>> 16, 15) / 100f));
        return TextColor.color(hsvToRgb(hue, sat, bri));
    }

    private static int hsvToRgb(float h, float s, float v) {
        int i = (int) (h * 6f);
        float f = h * 6f - i;
        float p = v * (1f - s);
        float q = v * (1f - f * s);
        float t = v * (1f - (1f - f) * s);
        float r;
        float g;
        float b;
        switch (i % 6) {
            case 0 -> { r = v; g = t; b = p; }
            case 1 -> { r = q; g = v; b = p; }
            case 2 -> { r = p; g = v; b = t; }
            case 3 -> { r = p; g = q; b = v; }
            case 4 -> { r = t; g = p; b = v; }
            default -> { r = v; g = p; b = q; }
        }
        return ((int) (r * 255) << 16) | ((int) (g * 255) << 8) | (int) (b * 255);
    }

    public static String cleanLabel(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw
                .replaceAll("§[0-9A-FK-ORa-fk-or]", "")
                .replaceAll("<[^>]+>", "")
                .replace('\n', ' ')
                .replace('\r', ' ')
                .trim();
        // collapse whitespace
        s = s.replaceAll("\\s+", " ");
        return s;
    }

    private static boolean looksLikeVersionOnly(String s) {
        return s.matches("(?i)v?\\d+(\\.\\d+){1,3}([-_].*)?");
    }
}
