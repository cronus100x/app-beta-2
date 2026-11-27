package com.wiseplay.player;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class PlaylistParser {
    private PlaylistParser() { }

    public static List<Channel> parse(String content) throws Exception {
        if (content == null || content.trim().isEmpty()) throw new IllegalArgumentException("La lista está vacía");
        String text = content.trim();
        if (text.startsWith("#EXTM3U") || text.contains("#EXTINF:")) return parseM3u(text);
        if (text.startsWith("{") || text.startsWith("[")) return parseJson(text);
        throw new IllegalArgumentException("Formato no reconocido. Usa M3U, M3U8 o W3U");
    }

    private static List<Channel> parseM3u(String text) {
        List<Channel> channels = new ArrayList<>();
        String name = null, group = "", image = "", userAgent = "", referer = "";
        String[] lines = text.replace("\uFEFF", "").split("\\r?\\n");
        for (String raw : lines) {
            String line = raw.trim();
            if (line.startsWith("#EXTINF:")) {
                int comma = line.indexOf(',');
                String attributes = comma >= 0 ? line.substring(0, comma) : line;
                name = comma >= 0 ? line.substring(comma + 1).trim() : "Canal";
                group = attribute(attributes, "group-title");
                image = attribute(attributes, "tvg-logo");
                userAgent = attribute(attributes, "http-user-agent");
                referer = attribute(attributes, "referrer");
            } else if (!line.startsWith("#") && isStreamUrl(line)) {
                channels.add(new Channel(name == null || name.isEmpty() ? line : name, line, group,
                        image, userAgent, referer, false, "", "", new LinkedHashMap<>()));
                name = null; group = ""; image = ""; userAgent = ""; referer = "";
            }
        }
        return channels;
    }

    private static String attribute(String line, String name) {
        String marker = name + "=";
        int start = line.indexOf(marker);
        if (start < 0) return "";
        start += marker.length();
        if (start < line.length() && line.charAt(start) == '"') {
            int end = line.indexOf('"', start + 1);
            return end > start ? line.substring(start + 1, end) : "";
        }
        int end = line.indexOf(' ', start);
        return end > start ? line.substring(start, end) : line.substring(start);
    }

    private static boolean isStreamUrl(String value) {
        if (value == null || value.trim().isEmpty()) return false;
        String lower = value.toLowerCase();
        return lower.startsWith("http://") || lower.startsWith("https://")
                || lower.startsWith("rtmp://") || lower.startsWith("rtsp://");
    }

    private static List<Channel> parseJson(String text) throws JSONException {
        List<Channel> channels = new ArrayList<>();
        Object root = text.startsWith("[") ? new JSONArray(text) : new JSONObject(text);
        if (root instanceof JSONObject) {
            JSONObject object = (JSONObject) root;
            parseGroups(object.optJSONArray("groups"), "", channels);
            parseStations(object.optJSONArray("stations"), "", channels);
            parseStations(object.optJSONArray("channels"), "", channels);
            parseStations(object.optJSONArray("items"), "", channels);
        }
        collectJsonChannels(root, "", channels);
        if (channels.isEmpty()) throw new IllegalArgumentException("El JSON/W3U no contiene canales válidos");
        return deduplicate(channels);
    }

    private static void parseGroups(JSONArray groups, String parent, List<Channel> channels) throws JSONException {
        if (groups == null) return;
        for (int i = 0; i < groups.length(); i++) {
            JSONObject group = groups.optJSONObject(i);
            if (group == null) continue;
            String name = firstString(group, "name", "title", "group", "category");
            String fullName = parent == null || parent.isEmpty() ? name : parent + " / " + name;
            parseStations(group.optJSONArray("stations"), fullName, channels);
            parseStations(group.optJSONArray("channels"), fullName, channels);
            parseStations(group.optJSONArray("items"), fullName, channels);
            parseStations(group.optJSONArray("streams"), fullName, channels);
            parseGroups(group.optJSONArray("groups"), fullName, channels);
        }
    }

    private static void parseStations(JSONArray stations, String group, List<Channel> channels) throws JSONException {
        if (stations == null) return;
        for (int i = 0; i < stations.length(); i++) {
            JSONObject station = stations.optJSONObject(i);
            if (station != null) addStation(station, group, channels);
        }
    }

    private static void collectJsonChannels(Object value, String group, List<Channel> channels) throws JSONException {
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length(); i++) collectJsonChannels(array.get(i), group, channels);
            return;
        }
        if (!(value instanceof JSONObject)) return;
        JSONObject object = (JSONObject) value;
        if (object.has("url") || object.has("stream_url") || object.has("streamUrl") || object.has("link") || object.has("src")) {
            addStation(object, group, channels);
        }
        String nestedGroup = firstString(object, "name", "title", "group", "category");
        JSONArray groups = object.optJSONArray("groups");
        if (groups != null) parseGroups(groups, group, channels);
        Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            String key = keys.next(); Object child = object.get(key);
            if (child instanceof JSONArray || child instanceof JSONObject) collectJsonChannels(child, group, channels);
        }
    }

    private static void addStation(JSONObject station, String group, List<Channel> channels) {
        String url = firstString(station, "url", "stream_url", "streamUrl", "link", "src", "file", "url2", "url3");
        if (!isStreamUrl(url)) return;
        String name = firstString(station, "name", "title", "channel", "channel_name", "label");
        String stationGroup = firstString(station, "group", "group-title", "group_title", "category", "genre");
        if (stationGroup.isEmpty()) stationGroup = group == null ? "" : group;
        String image = firstString(station, "image", "logo", "icon", "tvg-logo", "thumb");
        String userAgent = firstString(station, "userAgent", "user_agent", "http-user-agent");
        String referer = firstString(station, "referer", "referrer");
        Map<String, String> headers = new LinkedHashMap<>();
        JSONObject headerObject = station.optJSONObject("headers");
        if (headerObject != null) {
            Iterator<String> keys = headerObject.keys();
            while (keys.hasNext()) { String key = keys.next(); headers.put(key, headerObject.optString(key, "")); }
        }
        channels.add(new Channel(name.isEmpty() ? url : name, url, stationGroup, image,
                userAgent, referer, station.optBoolean("isHost", false),
                firstString(station, "license_type", "licenseType", "drm_type"),
                firstString(station, "license_key", "licenseKey", "drm_key"), headers));
    }

    private static List<Channel> deduplicate(List<Channel> input) {
        Map<String, Channel> unique = new LinkedHashMap<>();
        for (Channel channel : input) unique.put(channel.getName() + "\u0000" + channel.getUrl(), channel);
        return new ArrayList<>(unique.values());
    }

    private static String firstString(JSONObject object, String... keys) {
        for (String key : keys) {
            Object value = object.opt(key);
            if (value != null && value != JSONObject.NULL) {
                String result = String.valueOf(value).trim();
                if (!result.isEmpty()) return result;
            }
        }
        return "";
    }
}
