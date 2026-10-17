package dev.tanner.pl3xmap.factions;

import java.util.regex.Pattern;

final class MapText {
    private static final Pattern LEGACY_COLOR = Pattern.compile("(?i)[§&][0-9A-FK-ORX]");

    private MapText() {
    }

    static String plain(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        return LEGACY_COLOR.matcher(text).replaceAll("");
    }

    static String escape(String text) {
        String plain = plain(text);
        StringBuilder out = new StringBuilder(plain.length());
        for (int i = 0; i < plain.length(); i++) {
            char c = plain.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    static int argb(int rgb, double opacity) {
        int alpha = (int) Math.round(Math.clamp(opacity, 0.0D, 1.0D) * 255.0D);
        return (alpha << 24) | (rgb & 0xFFFFFF);
    }

    static int parseColor(String text, int fallback) {
        if (text == null || text.isBlank()) {
            return fallback;
        }
        String hex = text.charAt(0) == '#' ? text.substring(1) : text;
        try {
            return Integer.parseInt(hex, 16) & 0xFFFFFF;
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }
}
