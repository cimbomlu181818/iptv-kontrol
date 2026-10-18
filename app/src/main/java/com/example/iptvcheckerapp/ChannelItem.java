package com.example.iptvcheckerapp;

public class ChannelItem {
    private String categoryName;
    private String channelName;

    public ChannelItem(String categoryName, String channelName) {
        this.categoryName = categoryName;
        this.channelName = channelName;
    }

    public String getCategoryName() { return categoryName; }
    public String getChannelName() { return channelName; }
}