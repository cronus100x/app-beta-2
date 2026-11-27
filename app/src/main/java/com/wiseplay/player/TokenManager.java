package com.wiseplay.player;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Gestor de sesión preparado para APIs que devuelven token/access_token y refresh_token.
 * Configura AUTH_ENDPOINT y las credenciales en la pantalla de ajustes de la aplicación.
 */
public final class TokenManager {
    private static final String PREFS = "wiseplay_session";
    private static final String TOKEN = "access_token";
    private static final String REFRESH = "refresh_token";
    private static final String EXPIRY = "expires_at";
    private static final long REFRESH_MARGIN_MS = 60_000L;
    private static final AtomicReference<TokenManager> INSTANCES = new AtomicReference<>();

    private final SharedPreferences preferences;
    private final Context context;
    private volatile String authEndpoint = "";
    private volatile String username = "";
    private volatile String password = "";

    private TokenManager(Context context) {
        this.context = context.getApplicationContext();
        this.preferences = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static TokenManager get(Context context) {
        TokenManager current = INSTANCES.get();
        if (current != null) return current;
        TokenManager created = new TokenManager(context);
        return INSTANCES.compareAndSet(null, created) ? created : INSTANCES.get();
    }

    public void configure(String endpoint, String user, String pass) {
        authEndpoint = endpoint == null ? "" : endpoint.trim();
        username = user == null ? "" : user;
        password = pass == null ? "" : pass;
    }

    public String getToken() {
        return preferences.getString(TOKEN, "");
    }

    public synchronized String getValidToken() {
        String token = getToken();
        long expiry = preferences.getLong(EXPIRY, 0L);
        if (!token.isEmpty() && (expiry == 0L || System.currentTimeMillis() + REFRESH_MARGIN_MS < expiry)) {
            return token;
        }
        return renew();
    }

    public synchronized String renew() {
        try {
            String refreshToken = preferences.getString(REFRESH, "");
            JSONObject body = new JSONObject();
            if (!refreshToken.isEmpty()) {
                body.put("refresh_token", refreshToken);
            } else {
                body.put("username", username);
                body.put("password", password);
            }
            if (authEndpoint.isEmpty()) return getToken();
            java.net.HttpURLConnection connection = (java.net.HttpURLConnection)
                    new java.net.URL(authEndpoint).openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(15_000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
            connection.getOutputStream().write(payload);
            if (connection.getResponseCode() < 200 || connection.getResponseCode() >= 300) {
                return getToken();
            }
            java.io.InputStream input = connection.getInputStream();
            java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
            JSONObject response = new JSONObject(output.toString(StandardCharsets.UTF_8.name()));
            String access = first(response, "access_token", "token", "jwt");
            if (!access.isEmpty()) {
                long expiresIn = response.optLong("expires_in", 3600L);
                String newRefresh = first(response, "refresh_token", "refreshToken");
                preferences.edit().putString(TOKEN, access)
                        .putLong(EXPIRY, System.currentTimeMillis() + expiresIn * 1000L)
                        .apply();
                if (!newRefresh.isEmpty()) preferences.edit().putString(REFRESH, newRefresh).apply();
                return access;
            }
        } catch (Exception ignored) {
            // La reproducción puede continuar con el token anterior si la renovación falla.
        }
        return getToken();
    }

    public void clear() {
        preferences.edit().clear().apply();
    }

    private static String first(JSONObject object, String... names) {
        for (String name : names) {
            String value = object.optString(name, "");
            if (!value.isEmpty()) return value;
        }
        return "";
    }

    public static String appendToken(String url, String token) {
        if (url == null || url.isEmpty() || token == null || token.isEmpty()) return url;
        String separator = url.contains("?") ? "&" : "?";
        return url + separator + "token=" + android.net.Uri.encode(token);
    }
}
