package com.wiseplay.player;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Obtiene el host y el segmento tok_ del CDN Flow/CVATTV mediante una URL
 * semilla, como hace KillerTV. El token se reutiliza durante una hora y se
 * puede invalidar cuando el reproductor recibe 401/403.
 */
public final class FlowTokenManager {
    private static final long CACHE_TTL_MS = 3_600_000L;
    private static final int MAX_REDIRECTS = 5;
    private static final String USER_AGENT = "PlayTVPremium";
    private static final Pattern TOKEN_PATTERN = Pattern.compile("/(tok_[^/]+)");
    private static final String[] SEEDS = {
            "https://chromecast.cvattv.com.ar/live/c6eds/Viajar/SA_Live_dash_cenc/Viajar.mpd",
            "https://cdn-py.cvattv.com.ar/live/c6eds/EWTN/SA_Live_dash_enc/EWTN.mpd",
            "https://cdn-py.cvattv.com.ar/live/c4eds/UNICANAL_C4/SA_Live_dash_enc/UNICANAL_C4.mpd",
            "https://cdn-py.cvattv.com.ar/live/c4eds/TELEFUTURO_C4/SA_Live_dash_enc/TELEFUTURO_C4.mpd"
    };

    private static String cachedHost = "";
    private static String cachedTokenPath = "";
    private static long cachedAt;

    private FlowTokenManager() { }

    public static synchronized String rewrite(String sourceUrl) {
        if (!requiresSigning(sourceUrl)) return sourceUrl;
        String path = extractRelativePath(sourceUrl);
        if (path.isEmpty()) return sourceUrl;
        TokenInfo info = getFreshTokenInfo(false);
        if (info == null) return sourceUrl;
        return "https://" + info.host + "/" + info.tokenPath + "/" + path;
    }

    public static synchronized void invalidate() {
        cachedHost = "";
        cachedTokenPath = "";
        cachedAt = 0L;
    }

    private static boolean requiresSigning(String url) {
        if (url == null) return false;
        String lower = url.toLowerCase(Locale.ROOT);
        return (lower.endsWith(".mpd") || lower.contains(".mpd?") || lower.contains(".mpd#"))
                && (lower.contains("cvattv.com.ar") || lower.contains("cvattv.com.py")
                || lower.contains("flow.com.ar") || lower.contains("flow.com.py")
                || lower.contains("cvattv.get-femon.net") || lower.contains("app.flow.com"));
    }

    private static String extractRelativePath(String url) {
        if (url == null) return "";
        Matcher matcher = Pattern.compile("(live/c\\d+eds/[^?#]*)", Pattern.CASE_INSENSITIVE).matcher(url);
        if (!matcher.find()) {
            matcher = Pattern.compile("(c\\d+eds/[^?#]*)", Pattern.CASE_INSENSITIVE).matcher(url);
            if (!matcher.find()) return "";
            return "live/" + matcher.group(1);
        }
        String path = matcher.group(1);
        // CanalesLatam publica el manifiesto CENC como fuente del token,
        // pero la reproducción necesita la variante ENC firmada.
        path = path.replace("_cenc/", "_enc/");
        return path;
    }

    private static TokenInfo getFreshTokenInfo(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && !cachedHost.isEmpty() && !cachedTokenPath.isEmpty()
                && now - cachedAt < CACHE_TTL_MS) {
            return new TokenInfo(cachedHost, cachedTokenPath);
        }
        for (String seed : SEEDS) {
            TokenInfo info = probe(seed);
            if (info != null) {
                cachedHost = info.host;
                cachedTokenPath = info.tokenPath;
                cachedAt = now;
                return info;
            }
        }
        if (!cachedHost.isEmpty() && !cachedTokenPath.isEmpty()) {
            return new TokenInfo(cachedHost, cachedTokenPath);
        }
        return null;
    }

    private static TokenInfo probe(String initialUrl) {
        String current = initialUrl;
        for (int attempt = 0; attempt < MAX_REDIRECTS; attempt++) {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(current).openConnection();
                connection.setInstanceFollowRedirects(false);
                connection.setRequestMethod("GET");
                connection.setRequestProperty("User-Agent", USER_AGENT);
                connection.setConnectTimeout(10_000);
                connection.setReadTimeout(10_000);
                int code = connection.getResponseCode();
                String location = connection.getHeaderField("Location");
                if (location != null && !location.isEmpty()) {
                    String resolved = new URL(new URL(current), location).toString();
                    TokenInfo info = extractTokenInfo(resolved);
                    if (info != null) return info;
                    current = resolved;
                    continue;
                }
                if (code < 300 || code >= 309) return extractTokenInfo(current);
            } catch (Exception ignored) {
                return null;
            } finally {
                if (connection != null) connection.disconnect();
            }
        }
        return null;
    }

    private static TokenInfo extractTokenInfo(String url) {
        try {
            URL parsed = new URL(url);
            Matcher matcher = TOKEN_PATTERN.matcher(parsed.getPath());
            if (matcher.find()) return new TokenInfo(parsed.getHost(), matcher.group(1));
        } catch (Exception ignored) { }
        return null;
    }

    private static final class TokenInfo {
        final String host;
        final String tokenPath;
        TokenInfo(String host, String tokenPath) {
            this.host = host;
            this.tokenPath = tokenPath;
        }
    }
}
