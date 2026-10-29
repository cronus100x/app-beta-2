package com.wiseplay.player;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public final class PlaylistParser {
    private PlaylistParser() {
    }

    public static List<Channel> parse(String content) throws Exception {
        if (content == null || content.trim().isEmpty()) {
            throw new IllegalArgumentException("El archivo o la respuesta están vacíos");
        }

        String text = content.trim();
        if (text.startsWith("#EXTM3U") || text.contains("#EXTINF:")) {
            return parseM3u(text);
        }
        if (text.startsWith("{") || text.startsWith("[")) {
            return parseJson(text);
        }
        throw new IllegalArgumentException("Formato no reconocido. Usa M3U, M3U8 o JSON");
    }

    private static List<Channel> parseM3u(String text) {
        List<Channel> channels = new ArrayList<>();
        String pendingName = null;
        String pendingGroup = "";

        String[] lines = text.replace("\uFEFF", "").split("\\r?\\n");
        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("#EXTINF:")) {
                int comma = line.indexOf(',');
                String attributes = comma >= 0 ? line.substring(0, comma) : line;
                pendingName = comma >= 0 ? line.substring(comma + 1).trim() : "Canal";
                pendingGroup = attribute(attributes, "group-title");
                if (pendingName.isEmpty()) {
                    pendingName = attribute(attributes, "tvg-name");
                }
            } else if (!line.startsWith("#") && isStreamUrl(line)) {
                channels.add(new Channel(
                        pendingName == null || pendingName.isEmpty() ? line : pendingName,
                        line,
                        pendingGroup));
                pendingName = null;
                pendingGroup = "";
            }
        }
        return channels;
    }

    private static String attribute(String line, String name) {
        String marker = name + "=";
        int start = line.indexOf(marker);
        if (start < 0) {
            return "";
        }
        start += marker.length();
        if (start < line.length() && line.charAt(start) == '"') {
            int end = line.indexOf('"', start + 1);
            return end > start ? line.substring(start + 1, end) : "";
        }
        int end = line.indexOf(' ', start);
        return end > start ? line.substring(start, end) : line.substring(start);
    }

    private static boolean isStreamUrl(String value) {
        if (value == null || value.trim().isEmpty()) {
            return false;
        }
        String lower = value.toLowerCase();
        return lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("rtmp://");
    }

    private static List<Channel> parseJson(String text) throws JSONException {
        List<Channel> channels = new ArrayList<>();
        if (text.startsWith("{")) {
            JSONObject root = new JSONObject(text);
            JSONArray groups = root.optJSONArray("groups");
            if (groups != null) {
                parseW3uGroups(groups, channels);
                if (channels.isEmpty()) {
                    throw new IllegalArgumentException("El archivo W3U no contiene estaciones válidas");
                }
                return channels;
            }
        }

        Object root = text.startsWith("[") ? new JSONArray(text) : new JSONObject(text);
        collectJsonChannels(root, channels);
        if (channels.isEmpty()) {
            throw new IllegalArgumentException("El JSON no contiene canales con nombre y URL");
        }
        return channels;
    }

    private static void parseW3uGroups(JSONArray groups, List<Channel> channels) throws JSONException {
        for (int i = 0; i < groups.length(); i++) {
            JSONObject group = groups.optJSONObject(i);
            if (group == null) {
                continue;
            }
            String groupName = firstString(group, "name", "title", "group", "category");
            JSONArray stations = group.optJSONArray("stations");
            if (stations == null) {
                stations = group.optJSONArray("channels");
            }
            if (stations == null) {
                continue;
            }
            for (int j = 0; j < stations.length(); j++) {
                JSONObject station = stations.optJSONObject(j);
                if (station == null) {
                    continue;
                }
                String url = firstString(station, "url", "stream_url", "streamUrl", "link", "src", "file", "url2", "url3");
                if (!isStreamUrl(url)) {
                    continue;
                }
                String name = firstString(station, "name", "title", "channel", "channel_name", "label");
                channels.add(new Channel(name == null ? url : name, url, groupName));
            }
        }
    }

    private static void collectJsonChannels(Object value, List<Channel> channels) throws JSONException {
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length(); i++) {
                collectJsonChannels(array.get(i), channels);
            }
            return;
        }
        if (!(value instanceof JSONObject)) {
            return;
        }

        JSONObject object = (JSONObject) value;
        String url = firstString(object, "url", "stream_url", "streamUrl", "link", "src", "file");
        if (url != null && isStreamUrl(url)) {
            String name = firstString(object, "name", "title", "channel", "channel_name", "label");
            String group = firstString(object, "group", "group-title", "group_title", "category", "genre");
            channels.add(new Channel(name == null ? url : name, url, group));
        }

        Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            Object child = object.get(key);
            if (child instanceof JSONArray || child instanceof JSONObject) {
                collectJsonChannels(child, channels);
            }
        }
    }

    private static String firstString(JSONObject object, String... keys) {
        for (String key : keys) {
            Object value = object.opt(key);
            if (value != null && value != JSONObject.NULL) {
                String result = String.valueOf(value).trim();
                if (!result.isEmpty()) {
                    return result;
                }
            }
        }
        return null;
    }
}
