package com.example.iptvcheckerapp;

public class Channel {
    private String name;
    private String url;
    private String category;
    private String logo;

    public Channel(String name, String url, String category, String logo) {
        this.name = name;
        this.url = url;
        this.category = category;
        this.logo = logo;
    }

    public String getName() {
        return name;
    }

    public String getUrl() {
        return url;
    }

    public String getCategory() {
        return category != null ? category : "Genel";
    }

    public String getLogo() {
        return logo;
    }
}