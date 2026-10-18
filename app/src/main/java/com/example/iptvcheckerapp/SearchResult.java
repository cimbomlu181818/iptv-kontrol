package com.example.iptvcheckerapp;

public class SearchResult {
    private String name;
    private String mainGroup;
    private String parentCategory;
    private boolean isCategory;

    public SearchResult(String name, String mainGroup, String parentCategory, boolean isCategory) {
        this.name = name;
        this.mainGroup = mainGroup;
        this.parentCategory = parentCategory;
        this.isCategory = isCategory;
    }

    public String getName() { return name; }
    public String getMainGroup() { return mainGroup; }
    public String getParentCategory() { return parentCategory; }
    public boolean isCategory() { return isCategory; }
}