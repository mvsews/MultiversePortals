package io.multiverseportals.scanner;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * TCP Java SLP + optional Bedrock/Geyser UDP (RakNet) probe before transfer.
 */
public final class ServerProbe {

    private static final byte[] RAKNET_MAGIC = new byte[]{
            0x00, (byte) 0xff, (byte) 0xff, 0x00, (byte) 0xfe, (byte) 0xfe, (byte) 0xfe, (byte) 0xfe,
            (byte) 0xfd, (byte) 0xfd, (byte) 0xfd, (byte) 0xfd, 0x12, 0x34, 0x56, 0x78
    };

    public enum Status {
        OK,
        FULL,
        UNREACHABLE
    }

    public record Result(Status status, int online, int max) {
        public static Result unreachable() {
            return new Result(Status.UNREACHABLE, -1, -1);
        }

        public static Result full(int online, int max) {
            return new Result(Status.FULL, online, max);
        }

        public static Result ok(int online, int max) {
            return new Result(Status.OK, online, max);
        }

        public boolean joinable() {
            return status == Status.OK;
        }
    }

    /** Full SLP payload for catalog branding (MOTD + favicon). */
    public record StatusInfo(Status status, int online, int max, String motd, byte[] faviconPng) {
        public static StatusInfo unreachable() {
            return new StatusInfo(Status.UNREACHABLE, -1, -1, "", null);
        }

        public boolean hasFavicon() {
            return faviconPng != null && faviconPng.length > 0;
        }
    }

    private ServerProbe() {}

    public static boolean isReachable(String host, int port, int timeoutMs) {
        return probe(host, port, timeoutMs).status != Status.UNREACHABLE;
    }

    public static Result probe(String host, int port, int timeoutMs) {
        StatusInfo info = probeStatus(host, port, timeoutMs);
        return new Result(info.status(), info.online(), info.max());
    }

    public static StatusInfo probeStatus(String host, int port, int timeoutMs) {
        if (host == null || host.isBlank() || port <= 0) {
            return StatusInfo.unreachable();
        }
        int timeout = Math.max(300, timeoutMs);
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeout);
            socket.setSoTimeout(timeout);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            ByteArrayOutputStream hs = new ByteArrayOutputStream();
            DataOutputStream hsData = new DataOutputStream(hs);
            writeVarInt(hsData, 0x00);
            writeVarInt(hsData, 767);
            writeString(hsData, host);
            hsData.writeShort(port & 0xFFFF);
            writeVarInt(hsData, 1);
            writePacket(out, hs.toByteArray());
            writePacket(out, new byte[]{0x00});

            DataInputStream dis = new DataInputStream(in);
            int packetLen = readVarInt(dis);
            if (packetLen <= 0 || packetLen > 1_000_000) {
                return StatusInfo.unreachable();
            }
            byte[] packet = dis.readNBytes(packetLen);
            if (packet.length < 1) {
                return StatusInfo.unreachable();
            }
            DataInputStream pkt = new DataInputStream(new java.io.ByteArrayInputStream(packet));
            int packetId = readVarInt(pkt);
            if (packetId != 0x00) {
                return StatusInfo.unreachable();
            }
            return parseStatusInfo(readString(pkt));
        } catch (Exception e) {
            return StatusInfo.unreachable();
        }
    }

    /**
     * Bedrock/Geyser listen info from RakNet Unconnected Pong.
     * MCPE string: edition;motd;protocol;version;online;max;...
     */
    public record BedrockInfo(int port, int protocol, String version, int online, int max, String motd) {
        public boolean full() {
            return max > 0 && online >= max;
        }

        public String name() {
            return motd == null ? "" : motd;
        }
    }

    /**
     * Find Geyser/Bedrock UDP that answers RakNet and parse protocol/version.
     */
    public static java.util.Optional<BedrockInfo> findBedrock(String host, List<Integer> ports, int timeoutMs) {
        if (host == null || host.isBlank() || ports == null || ports.isEmpty()) {
            return java.util.Optional.empty();
        }
        int timeout = Math.max(300, timeoutMs);
        for (Integer p : ports) {
            if (p == null || p <= 0 || p > 65535) {
                continue;
            }
            java.util.Optional<BedrockInfo> info = probeRakNetInfo(host, p, timeout);
            if (info.isPresent()) {
                return info;
            }
        }
        return java.util.Optional.empty();
    }

    /** @deprecated use {@link #findBedrock} */
    public static int findGeyserPort(String host, List<Integer> ports, int timeoutMs) {
        return findBedrock(host, ports, timeoutMs).map(BedrockInfo::port).orElse(-1);
    }

    public static boolean probeRakNet(String host, int port, int timeoutMs) {
        return probeRakNetInfo(host, port, timeoutMs).isPresent();
    }

    public static java.util.Optional<BedrockInfo> probeRakNetInfo(String host, int port, int timeoutMs) {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(timeoutMs);
            InetAddress addr = InetAddress.getByName(host);
            byte[] ping = buildUnconnectedPing();
            socket.send(new DatagramPacket(ping, ping.length, addr, port));
            byte[] buf = new byte[4096];
            DatagramPacket resp = new DatagramPacket(buf, buf.length);
            socket.receive(resp);
            if (resp.getLength() < 35 || (buf[0] & 0xFF) != 0x1c) {
                return java.util.Optional.empty();
            }
            // Unconnected Pong: 1 + 8 ping + 8 guid + 16 magic + 2 strlen + string
            int off = 1 + 8 + 8 + 16;
            if (resp.getLength() < off + 2) {
                return java.util.Optional.of(new BedrockInfo(port, 0, "", -1, -1, ""));
            }
            int strLen = ((buf[off] & 0xFF) << 8) | (buf[off + 1] & 0xFF);
            off += 2;
            if (strLen <= 0 || off + strLen > resp.getLength()) {
                return java.util.Optional.of(new BedrockInfo(port, 0, "", -1, -1, ""));
            }
            String motd = new String(buf, off, strLen, StandardCharsets.UTF_8);
            return java.util.Optional.of(parseMcpe(motd, port));
        } catch (Exception e) {
            return java.util.Optional.empty();
        }
    }

    static BedrockInfo parseMcpe(String motd, int port) {
        // MCPE;Dedicated Server;827;1.21.90;3;20;...
        String[] p = motd.split(";", -1);
        int protocol = 0;
        String version = "";
        int online = -1;
        int max = -1;
        if (p.length > 2) {
            try {
                protocol = Integer.parseInt(p[2].trim());
            } catch (NumberFormatException ignored) {
            }
        }
        if (p.length > 3) {
            version = p[3].trim();
        }
        if (p.length > 4) {
            try {
                online = Integer.parseInt(p[4].trim());
            } catch (NumberFormatException ignored) {
            }
        }
        if (p.length > 5) {
            try {
                max = Integer.parseInt(p[5].trim());
            } catch (NumberFormatException ignored) {
            }
        }
        String name = p.length > 1 ? p[1].trim() : "";
        return new BedrockInfo(port, protocol, version, online, max, name);
    }

    private static byte[] buildUnconnectedPing() {
        ByteBuffer bb = ByteBuffer.allocate(33).order(ByteOrder.BIG_ENDIAN);
        bb.put((byte) 0x01);
        bb.putLong(System.currentTimeMillis());
        bb.put(RAKNET_MAGIC);
        bb.putLong(0L);
        return bb.array();
    }

    private static Result parseStatusJson(String json) {
        StatusInfo info = parseStatusInfo(json);
        return new Result(info.status(), info.online(), info.max());
    }

    private static StatusInfo parseStatusInfo(String json) {
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            int online = -1;
            int max = -1;
            if (root.has("players") && root.get("players").isJsonObject()) {
                JsonObject players = root.getAsJsonObject("players");
                if (players.has("online") && !players.get("online").isJsonNull()) {
                    online = players.get("online").getAsInt();
                }
                if (players.has("max") && !players.get("max").isJsonNull()) {
                    max = players.get("max").getAsInt();
                }
            }
            String motd = descriptionToPlain(root.get("description"));
            byte[] favicon = null;
            if (root.has("favicon") && !root.get("favicon").isJsonNull()) {
                String fav = root.get("favicon").getAsString();
                if (fav != null && !fav.isBlank()) {
                    String b64 = fav;
                    int comma = b64.indexOf(',');
                    if (b64.startsWith("data:") && comma > 0) {
                        b64 = b64.substring(comma + 1);
                    }
                    try {
                        byte[] raw = java.util.Base64.getDecoder().decode(b64);
                        if (raw.length > 0 && raw.length <= 256_000) {
                            favicon = raw;
                        }
                    } catch (IllegalArgumentException ignored) {
                    }
                }
            }
            Status st = (max > 0 && online >= max) ? Status.FULL : Status.OK;
            return new StatusInfo(st, online, max, motd, favicon);
        } catch (Exception e) {
            return new StatusInfo(Status.OK, -1, -1, "", null);
        }
    }

    private static String descriptionToPlain(com.google.gson.JsonElement el) {
        if (el == null || el.isJsonNull()) {
            return "";
        }
        if (el.isJsonPrimitive()) {
            return el.getAsString();
        }
        if (el.isJsonObject()) {
            JsonObject o = el.getAsJsonObject();
            StringBuilder sb = new StringBuilder();
            if (o.has("text") && !o.get("text").isJsonNull()) {
                sb.append(o.get("text").getAsString());
            }
            if (o.has("extra") && o.get("extra").isJsonArray()) {
                for (com.google.gson.JsonElement part : o.getAsJsonArray("extra")) {
                    sb.append(descriptionToPlain(part));
                }
            }
            return sb.toString();
        }
        if (el.isJsonArray()) {
            StringBuilder sb = new StringBuilder();
            for (com.google.gson.JsonElement part : el.getAsJsonArray()) {
                sb.append(descriptionToPlain(part));
            }
            return sb.toString();
        }
        return "";
    }

    private static void writePacket(OutputStream out, byte[] data) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(buf);
        writeVarInt(dos, data.length);
        dos.write(data);
        out.write(buf.toByteArray());
        out.flush();
    }

    private static void writeString(DataOutputStream out, String s) throws IOException {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        writeVarInt(out, bytes.length);
        out.write(bytes);
    }

    private static String readString(DataInputStream in) throws IOException {
        int len = readVarInt(in);
        if (len < 0 || len > 32767 * 4) {
            throw new IOException("bad string len " + len);
        }
        byte[] bytes = in.readNBytes(len);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void writeVarInt(DataOutputStream out, int value) throws IOException {
        int v = value;
        while ((v & ~0x7F) != 0) {
            out.writeByte((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        out.writeByte(v);
    }

    private static int readVarInt(DataInputStream in) throws IOException {
        int numRead = 0;
        int result = 0;
        byte read;
        do {
            read = in.readByte();
            int value = (read & 0b01111111);
            result |= (value << (7 * numRead));
            numRead++;
            if (numRead > 5) {
                throw new IOException("VarInt too big");
            }
        } while ((read & 0b10000000) != 0);
        return result;
    }
}
