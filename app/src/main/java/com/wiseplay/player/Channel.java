package com.wiseplay.player;

public class Channel {
    private final String name;
    private final String url;
    private final String group;

    public Channel(String name, String url, String group) {
        this.name = name == null || name.trim().isEmpty() ? "Canal sin nombre" : name.trim();
        this.url = url == null ? "" : url.trim();
        this.group = group == null ? "" : group.trim();
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

    public String getDisplayName() {
        return group.isEmpty() ? name : group + " · " + name;
    }
}
