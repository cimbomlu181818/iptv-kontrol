package com.example.iptvcheckerapp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** M3U listesini Ana grup > Kategori > Kanal yapısına ayırır. Android'e bağlı değildir. */
public final class M3UParser {

    private M3UParser() {}

    public static final String LIVE = "CANLI TV";
    public static final String MOVIES = "FİLMLER";
    public static final String SERIES = "DİZİLER";
    public static final String OTHER = "DİĞER";

    /** Bellek korumak için okunacak en fazla kanal sayısı; aşılırsa liste "kısmen gösteriliyor" sayılır. */
    public static final int MAX_CHANNELS = 250000;

    private static final Pattern GROUP_TITLE = Pattern.compile("group-title=\"([^\"]*)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern TVG_NAME = Pattern.compile("tvg-name=\"([^\"]*)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern LIVE_ID = Pattern.compile(".*/\\d+(\\.(ts|m3u8))?$");
    private static final Pattern VIDEO_FILE = Pattern.compile(".*\\.(mp4|mkv|avi|mov|wmv|flv|mpg|mpeg)$");

    public static class Category {
        public final String name;
        public final List<String> channels = new ArrayList<>();
        private final Set<String> seen = new HashSet<>();

        Category(String name) {
            this.name = name;
        }
    }

    public static class Group {
        public final String name;
        public final List<Category> categories = new ArrayList<>();
        private final Map<String, Category> index = new HashMap<>();

        Group(String name) {
            this.name = name;
        }

        public int channelCount() {
            int n = 0;
            for (Category c : categories) n += c.channels.size();
            return n;
        }
    }

    public static class Result {
        public final List<Group> groups = new ArrayList<>();
        public int channelCount;
        public int categoryCount;
        public boolean truncated;
    }

    public static Result parse(InputStream in, long maxBytes) throws IOException {
        Http.LimitedInputStream limited = new Http.LimitedInputStream(in, maxBytes);
        BufferedReader br = new BufferedReader(new InputStreamReader(limited, "UTF-8"), 64 * 1024);
        Map<String, Group> groups = new LinkedHashMap<>();
        String pendingName = null;
        String pendingCategory = null;
        String extGrp = null;
        int total = 0;
        boolean capped = false;
        String line;
        while ((line = br.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("#EXTINF")) {
                pendingName = nameOf(line);
                Matcher g = GROUP_TITLE.matcher(line);
                pendingCategory = g.find() ? g.group(1).trim() : null;
            } else if (line.startsWith("#EXTGRP:")) {
                extGrp = line.substring(8).trim();
            } else if (line.charAt(0) != '#') {
                if (pendingName == null) continue;
                String cat = pendingCategory;
                if (cat == null || cat.isEmpty()) cat = extGrp;
                if (cat == null || cat.isEmpty()) cat = "GENEL";
                String main = mainGroupOf(line);
                Group group = groups.get(main);
                if (group == null) {
                    group = new Group(main);
                    groups.put(main, group);
                }
                Category category = group.index.get(cat);
                if (category == null) {
                    category = new Category(cat);
                    group.index.put(cat, category);
                    group.categories.add(category);
                }
                boolean added = category.seen.add(pendingName);
                if (added) category.channels.add(pendingName);
                pendingName = null;
                pendingCategory = null;
                extGrp = null;
                if (added && ++total >= MAX_CHANNELS) {
                    capped = true;
                    break;
                }
            }
        }

        Result result = new Result();
        result.truncated = limited.wasTruncated() || capped;
        String[] order = {LIVE, MOVIES, SERIES, OTHER};
        for (String key : order) {
            Group g = groups.get(key);
            if (g == null) continue;
            if (key.equals(LIVE)) turkishFirst(g);
            result.groups.add(g);
            result.categoryCount += g.categories.size();
            result.channelCount += g.channelCount();
        }
        return result;
    }

    static String nameOf(String extinf) {
        int quote = extinf.lastIndexOf('"');
        int comma = extinf.indexOf(',', quote + 1);
        if (comma < 0) comma = extinf.lastIndexOf(',');
        String name = comma >= 0 ? extinf.substring(comma + 1).trim() : "";
        if (name.isEmpty()) {
            Matcher m = TVG_NAME.matcher(extinf);
            if (m.find()) name = m.group(1).trim();
        }
        return name.isEmpty() ? "Adsız kanal" : name;
    }

    /** Linkin yoluna bakarak canlı yayın / film / dizi ayrımı yapar (sunucu adına bakmaz). */
    static String mainGroupOf(String url) {
        String path = pathOf(url).toLowerCase(Locale.ROOT);
        if (path.contains("/movie/")) return MOVIES;
        if (path.contains("/series/")) return SERIES;
        if (path.contains("/live/")) return LIVE;
        if (LIVE_ID.matcher(path).matches()) return LIVE;
        if (VIDEO_FILE.matcher(path).matches()) return MOVIES;
        return OTHER;
    }

    private static String pathOf(String url) {
        String s = url;
        int sep = s.indexOf("://");
        if (sep >= 0) s = s.substring(sep + 3);
        int slash = s.indexOf('/');
        s = slash < 0 ? "" : s.substring(slash);
        int cut = s.indexOf('?');
        if (cut >= 0) s = s.substring(0, cut);
        cut = s.indexOf('#');
        if (cut >= 0) s = s.substring(0, cut);
        return s;
    }

    private static void turkishFirst(Group g) {
        List<Category> turkish = new ArrayList<>();
        List<Category> others = new ArrayList<>();
        for (Category c : g.categories) {
            if (isTurkish(c.name)) turkish.add(c);
            else others.add(c);
        }
        g.categories.clear();
        g.categories.addAll(turkish);
        g.categories.addAll(others);
    }

    private static boolean isTurkish(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.startsWith("tr") && (n.length() == 2 || !Character.isLetter(n.charAt(2)))
                || n.startsWith("turk") || n.startsWith("türk")
                || n.contains("turkish") || n.contains("türkçe") || n.contains("turkey")
                || n.contains("yerli") || n.contains("ulusal");
    }
}
