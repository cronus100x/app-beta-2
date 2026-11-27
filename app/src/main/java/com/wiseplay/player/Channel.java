package com.wiseplay.player;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public class Channel {
    private final String name;
    private final String url;
    private final String group;
    private final String image;
    private final String userAgent;
    private final String referer;
    private final boolean host;
    private final Map<String, String> headers;

    public Channel(String name, String url, String group) {
        this(name, url, group, "", "", "", false, Collections.emptyMap());
    }

    public Channel(String name, String url, String group, String image, String userAgent,
                   String referer, boolean host, Map<String, String> headers) {
        this.name = name == null || name.trim().isEmpty() ? "Canal sin nombre" : name.trim();
        this.url = url == null ? "" : url.trim();
        this.group = group == null ? "" : group.trim();
        this.image = image == null ? "" : image.trim();
        this.userAgent = userAgent == null ? "" : userAgent.trim();
        this.referer = referer == null ? "" : referer.trim();
        this.host = host;
        this.headers = headers == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(headers));
    }

    public String getName() {
        return name;
    }

    public String getUrl() {
        return url;
    }

    public String getGroup() {
        return group;
    }

    public String getImage() { return image; }
    public String getUserAgent() { return userAgent; }
    public String getReferer() { return referer; }
    public boolean isHost() { return host; }
    public Map<String, String> getHeaders() { return headers; }

    public String getDisplayName() {
        return group.isEmpty() ? name : group + " · " + name;
    }
}
