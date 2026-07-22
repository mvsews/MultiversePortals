package io.multiverseportals.util;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

/**
 * Local Minecraft list branding: MOTD line 1 = name, line 2 = description, server-icon.png.
 */
public final class ServerBranding {

    public record Snapshot(String name, String description, String motd, byte[] iconPng) {
        public boolean hasIcon() {
            return iconPng != null && iconPng.length > 0;
        }

        public String iconBase64() {
            return hasIcon() ? Base64.getEncoder().encodeToString(iconPng) : "";
        }
    }

    private ServerBranding() {}

    public static Snapshot local() {
        String motd = "";
        try {
            motd = PlainTextComponentSerializer.plainText().serialize(Bukkit.motd());
        } catch (Throwable ignored) {
        }
        if (motd == null) {
            motd = "";
        }
        motd = motd.replace('\r', '\n').trim();
        String name = "";
        String description = "";
        if (!motd.isBlank()) {
            String[] lines = motd.split("\\n", 2);
            name = cleanLine(lines[0], 64);
            if (lines.length > 1) {
                description = cleanLine(lines[1].replace('\n', ' '), 128);
            }
        }
        if (name.isBlank()) {
            name = "A Minecraft Server";
        }
        return new Snapshot(name, description, cleanLine(motd.replace('\n', ' '), 255), readServerIcon());
    }

    public static Snapshot fromParts(String name, String description, String motd, String iconBase64) {
        String n = cleanLine(name, 64);
        String d = cleanLine(description, 128);
        String m = cleanLine(motd, 255);
        if (m.isBlank()) {
            m = (n + (d.isBlank() ? "" : " " + d)).trim();
        }
        if (n.isBlank() && !m.isBlank()) {
            String[] lines = m.split("\\s+", 2);
            n = cleanLine(lines[0], 64);
        }
        byte[] icon = decodeIcon(iconBase64);
        return new Snapshot(n, d, m, icon);
    }

    public static Snapshot fromMotdAndFavicon(String motdRaw, byte[] faviconPng) {
        String motd = motdRaw == null ? "" : motdRaw.replace('\r', '\n').trim();
        String name = "";
        String description = "";
        if (!motd.isBlank()) {
            String[] lines = motd.split("\\n", 2);
            name = cleanLine(lines[0], 64);
            if (lines.length > 1) {
                description = cleanLine(lines[1].replace('\n', ' '), 128);
            }
        }
        return new Snapshot(
                name.isBlank() ? "A Minecraft Server" : name,
                description,
                cleanLine(motd.replace('\n', ' '), 255),
                faviconPng
        );
    }

    private static byte[] readServerIcon() {
        try {
            Path icon = Bukkit.getWorldContainer().toPath().resolve("server-icon.png");
            if (!Files.isRegularFile(icon)) {
                return null;
            }
            long size = Files.size(icon);
            if (size <= 0 || size > 256_000) {
                return null;
            }
            return Files.readAllBytes(icon);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static byte[] decodeIcon(String b64) {
        if (b64 == null || b64.isBlank()) {
            return null;
        }
        String s = b64.trim();
        if (s.startsWith("data:")) {
            int comma = s.indexOf(',');
            if (comma > 0) {
                s = s.substring(comma + 1);
            }
        }
        try {
            byte[] raw = Base64.getDecoder().decode(s);
            if (raw.length == 0 || raw.length > 256_000) {
                return null;
            }
            // PNG magic
            if (raw.length < 8 || raw[0] != (byte) 0x89 || raw[1] != 0x50) {
                return null;
            }
            return raw;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String cleanLine(String s, int max) {
        if (s == null) {
            return "";
        }
        String t = s.replaceAll("\\s+", " ").trim();
        if (t.length() <= max) {
            return t;
        }
        return t.substring(0, max).trim();
    }
}
