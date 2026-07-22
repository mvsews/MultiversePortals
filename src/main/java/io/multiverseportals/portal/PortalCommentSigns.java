package io.multiverseportals.portal;

import io.multiverseportals.model.Portal;
import io.multiverseportals.util.ShapeHasher;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Collects player comment signs near a federation portal (excludes the system MVP sign).
 * Must run on the main thread.
 */
public final class PortalCommentSigns {

    /** Chebyshev radius around the portal frame sign. */
    public static final int RADIUS = 3;
    public static final int MAX_SIGNS = 8;
    public static final int MAX_TEXT_LEN = 120;

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private PortalCommentSigns() {}

    public static List<String> collect(Portal portal) {
        if (portal == null || portal.frame() == null) {
            return List.of();
        }
        World world = Bukkit.getWorld(portal.frame().world());
        if (world == null) {
            return List.of();
        }
        int fx = portal.frame().x();
        int fy = portal.frame().y();
        int fz = portal.frame().z();
        Set<String> uniq = new LinkedHashSet<>();
        List<String> out = new ArrayList<>();

        for (int dx = -RADIUS; dx <= RADIUS; dx++) {
            for (int dy = -RADIUS; dy <= RADIUS; dy++) {
                for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                    if (out.size() >= MAX_SIGNS) {
                        return out;
                    }
                    int x = fx + dx;
                    int y = fy + dy;
                    int z = fz + dz;
                    // Skip the system portal sign on the frame block
                    if (x == fx && y == fy && z == fz) {
                        continue;
                    }
                    Block b = world.getBlockAt(x, y, z);
                    if (!(b.getState() instanceof Sign sign)) {
                        continue;
                    }
                    if (isSystemPortalSign(sign)) {
                        continue;
                    }
                    String text = joinLines(sign);
                    if (text.isBlank()) {
                        continue;
                    }
                    if (text.length() > MAX_TEXT_LEN) {
                        text = text.substring(0, MAX_TEXT_LEN);
                    }
                    if (uniq.add(text)) {
                        out.add(text);
                    }
                }
            }
        }
        return out;
    }

    /** True for MVP control signs ([Multi]/Portal / Scan…), not free-form comments. */
    public static boolean isSystemPortalSign(Sign sign) {
        String line0 = ShapeHasher.plain(sign, 0).trim();
        if (ShapeHasher.parseType(line0) != null) {
            return true;
        }
        String low = line0.toLowerCase(Locale.ROOT);
        return "portal".equals(low) || "scan...".equals(low) || "scan…".equals(low);
    }

    public static String joinLines(Sign sign) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            String line;
            try {
                line = PLAIN.serialize(sign.line(i)).trim();
            } catch (Throwable t) {
                @SuppressWarnings("deprecation")
                String legacy = sign.getLine(i);
                line = legacy == null ? "" : legacy.trim();
            }
            if (line.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(line);
        }
        return sb.toString().trim();
    }
}
