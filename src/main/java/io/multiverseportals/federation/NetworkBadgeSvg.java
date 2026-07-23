package io.multiverseportals.federation;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Neon cyberpunk SVG badges for GitHub / README (mp.mvse.ws).
 */
final class NetworkBadgeSvg {

    private static final String VOID = "#07010f";
    private static final String PANEL = "#12061f";
    private static final String INK = "#f4f0ff";
    private static final String FONT =
            "ui-sans-serif,system-ui,-apple-system,'Segoe UI',Roboto,Ubuntu,Cantarell,'Noto Sans',sans-serif";

    /** Players — electric cyan */
    private static final String NEON_CYAN = "#00f0ff";
    private static final String NEON_CYAN_DIM = "#0a7a88";
    /** Servers — Razer green */
    private static final String NEON_RAZER = "#44ff2e";
    private static final String NEON_RAZER_DIM = "#1c8a18";
    private static final String NEON_VIOLET = "#b44dff";
    private static final String NEON_LIME = "#b8ff3c";

    private NetworkBadgeSvg() {
    }

    enum Tone {
        PLAYERS(NEON_CYAN, NEON_CYAN_DIM),
        SERVERS(NEON_RAZER, NEON_RAZER_DIM);

        final String neon;
        final String dim;

        Tone(String neon, String dim) {
            this.neon = neon;
            this.dim = dim;
        }
    }

    static byte[] splitBadge(String label, String value, Tone tone, boolean lit) {
        String safeLabel = xml(label);
        String safeValue = xml(value);
        String neon = lit ? tone.neon : tone.dim;
        int labelW = textWidth(label, 7.0, 22);
        int valueW = textWidth(value, 7.6, 20);
        int pad = 6;
        int innerW = labelW + valueW;
        int innerH = 28;
        int w = innerW + pad * 2;
        int h = innerH + pad * 2;
        int x0 = pad;
        int y0 = pad;
        int labelCx = x0 + labelW / 2;
        int valueCx = x0 + labelW + valueW / 2;
        // Center of pill (pad=6, h=28) — central baseline, slight optical nudge down
        int textY = y0 + innerH / 2 + 1;
        String id = "n" + Integer.toHexString((label + neon).hashCode() & 0xffff);

        String svg = """
                <svg xmlns="http://www.w3.org/2000/svg" width="%d" height="%d" role="img" aria-label="%s: %s">
                  <title>%s: %s</title>
                  <defs>
                    <linearGradient id="%s-bg" x1="0" y1="0" x2="1" y2="1">
                      <stop offset="0%%" stop-color="%s"/>
                      <stop offset="100%%" stop-color="%s"/>
                    </linearGradient>
                    <linearGradient id="%s-val" x1="0" y1="0" x2="0" y2="1">
                      <stop offset="0%%" stop-color="%s" stop-opacity=".3"/>
                      <stop offset="100%%" stop-color="%s" stop-opacity=".08"/>
                    </linearGradient>
                    <filter id="%s-glow" x="-40%%" y="-60%%" width="180%%" height="220%%">
                      <feGaussianBlur stdDeviation="2.5" result="b"/>
                      <feMerge>
                        <feMergeNode in="b"/>
                        <feMergeNode in="SourceGraphic"/>
                      </feMerge>
                    </filter>
                    <filter id="%s-soft" x="-30%%" y="-50%%" width="160%%" height="200%%">
                      <feDropShadow dx="0" dy="1" stdDeviation="1.8" flood-color="%s" flood-opacity=".6"/>
                    </filter>
                  </defs>
                  <rect x="%d" y="%d" width="%d" height="%d" rx="7" fill="url(#%s-bg)" filter="url(#%s-soft)"/>
                  <rect x="%d" y="%d" width="%d" height="%d" rx="7" fill="url(#%s-val)"/>
                  <rect x="%d" y="%d" width="%d" height="%d" rx="7" fill="none"
                        stroke="%s" stroke-width="1.5" filter="url(#%s-glow)"/>
                  <line x1="%d" y1="%d" x2="%d" y2="%d" stroke="%s" stroke-opacity=".4" stroke-width="1"/>
                  <g font-family="%s" font-size="12" text-anchor="middle" dominant-baseline="central">
                    <text x="%d" y="%d" fill="%s" fill-opacity=".88" font-weight="600"
                          letter-spacing="0.04em">%s</text>
                    <text x="%d" y="%d" fill="%s" font-weight="800" filter="url(#%s-glow)">%s</text>
                  </g>
                </svg>
                """.formatted(
                w, h, safeLabel, safeValue,
                safeLabel, safeValue,
                id, PANEL, VOID,
                id, neon, neon,
                id,
                id, neon,
                x0, y0, innerW, innerH, id, id,
                x0 + labelW, y0, valueW, innerH, id,
                x0, y0, innerW, innerH, neon, id,
                x0 + labelW, y0 + 6, x0 + labelW, y0 + innerH - 6, neon,
                FONT,
                labelCx, textY, neon, safeLabel,
                valueCx, textY, neon, id, safeValue
        );
        return svg.getBytes(StandardCharsets.UTF_8);
    }

    static byte[] networkStrip(int players, int servers) {
        String left = "Multiverse Portals";
        String mid = formatCount(players) + " players";
        String right = formatCount(servers) + " servers";
        int leftW = textWidth(left, 6.9, 36);
        int midW = textWidth(mid, 7.0, 20);
        int rightW = textWidth(right, 7.0, 20);
        int pad = 7;
        int innerW = leftW + midW + rightW;
        int innerH = 32;
        int w = innerW + pad * 2;
        int h = innerH + pad * 2;
        int x0 = pad;
        int y0 = pad;
        int midCx = x0 + leftW + midW / 2;
        int rightCx = x0 + leftW + midW + rightW / 2;
        // Center of strip (pad=7, h=32) — central baseline, slight optical nudge down
        int textY = y0 + innerH / 2 + 1;
        int cy = y0 + innerH / 2;
        String id = "net";

        String svg = """
                <svg xmlns="http://www.w3.org/2000/svg" width="%d" height="%d" role="img"
                     aria-label="Multiverse Portals: %s, %s">
                  <title>Multiverse Portals — %s · %s</title>
                  <defs>
                    <linearGradient id="%s-bg" x1="0" y1="0" x2="1" y2="1">
                      <stop offset="0%%" stop-color="#1a0830"/>
                      <stop offset="55%%" stop-color="#0a0214"/>
                      <stop offset="100%%" stop-color="#041018"/>
                    </linearGradient>
                    <linearGradient id="%s-scan" x1="0" y1="0" x2="0" y2="1">
                      <stop offset="0%%" stop-color="#fff" stop-opacity=".08"/>
                      <stop offset="55%%" stop-color="#fff" stop-opacity="0"/>
                      <stop offset="100%%" stop-color="%s" stop-opacity=".07"/>
                    </linearGradient>
                    <filter id="%s-glow" x="-35%%" y="-55%%" width="170%%" height="210%%">
                      <feGaussianBlur stdDeviation="2.4" result="b"/>
                      <feMerge>
                        <feMergeNode in="b"/>
                        <feMergeNode in="SourceGraphic"/>
                      </feMerge>
                    </filter>
                    <filter id="%s-soft" x="-25%%" y="-40%%" width="150%%" height="180%%">
                      <feDropShadow dx="0" dy="2" stdDeviation="2.4" flood-color="%s" flood-opacity=".45"/>
                    </filter>
                  </defs>
                  <rect x="%d" y="%d" width="%d" height="%d" rx="9" fill="url(#%s-bg)" filter="url(#%s-soft)"/>
                  <rect x="%d" y="%d" width="%d" height="%d" rx="9" fill="url(#%s-scan)"/>
                  <rect x="%d" y="%d" width="%d" height="%d" rx="9" fill="none"
                        stroke="%s" stroke-width="1.6" filter="url(#%s-glow)"/>
                  <circle cx="%d" cy="%d" r="5" fill="none" stroke="%s" stroke-width="1.5" filter="url(#%s-glow)"/>
                  <circle cx="%d" cy="%d" r="2" fill="%s" filter="url(#%s-glow)"/>
                  <g font-family="%s" font-size="12" dominant-baseline="central">
                    <text x="%d" y="%d" fill="%s" font-weight="800" filter="url(#%s-glow)"
                          letter-spacing="0.02em">%s</text>
                    <text x="%d" y="%d" fill="%s" font-weight="700" text-anchor="middle"
                          filter="url(#%s-glow)">%s</text>
                    <text x="%d" y="%d" fill="%s" font-weight="800" text-anchor="middle"
                          filter="url(#%s-glow)">%s</text>
                  </g>
                  <line x1="%d" y1="%d" x2="%d" y2="%d" stroke="%s" stroke-opacity=".4"/>
                  <line x1="%d" y1="%d" x2="%d" y2="%d" stroke="%s" stroke-opacity=".4"/>
                </svg>
                """.formatted(
                w, h, xml(mid), xml(right),
                xml(mid), xml(right),
                id,
                id, NEON_CYAN,
                id,
                id, NEON_VIOLET,
                x0, y0, innerW, innerH, id, id,
                x0, y0, innerW, innerH, id,
                x0, y0, innerW, innerH, NEON_VIOLET, id,
                x0 + 14, cy, NEON_LIME, id,
                x0 + 14, cy, NEON_LIME, id,
                FONT,
                x0 + 26, textY, NEON_VIOLET, id, xml(left),
                midCx, textY, NEON_CYAN, id, xml(mid),
                rightCx, textY, NEON_RAZER, id, xml(right),
                x0 + leftW, y0 + 7, x0 + leftW, y0 + innerH - 7, NEON_CYAN,
                x0 + leftW + midW, y0 + 7, x0 + leftW + midW, y0 + innerH - 7, NEON_RAZER
        );
        return svg.getBytes(StandardCharsets.UTF_8);
    }

    static String formatCount(int n) {
        if (n < 0) {
            n = 0;
        }
        return String.format(Locale.US, "%,d", n);
    }

    private static int textWidth(String text, double charW, int pad) {
        int len = text == null ? 0 : text.length();
        return Math.max(40, (int) Math.ceil(len * charW) + pad);
    }

    private static String xml(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
