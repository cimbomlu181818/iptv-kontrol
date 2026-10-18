package com.example.iptvcheckerapp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** M3U listesini Ana grup > Kategori > Kanal yapısına ayırır. Android'e bağlı değildir. */
public final class M3UParser {

    private M3UParser() {}

    public static final String LIVE = "CANLI TV";
    public static final String MOVIES = "FİLMLER";
    public static final String SERIES = "DİZİLER";
    public static final String OTHER = "DİĞER";
    /** Kategori bilgisi olmayan kanalların toplandığı kategori adı. */
    public static final String GENERAL = "GENEL";

    /** Bellek korumak için okunacak en fazla kanal sayısı; aşılırsa liste "kısmen gösteriliyor" sayılır. */
    public static final int MAX_CHANNELS = 250000;

    private static final Pattern GROUP_TITLE = Pattern.compile("group-title=\"([^\"]*)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern TVG_NAME = Pattern.compile("tvg-name=\"([^\"]*)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern LIVE_ID = Pattern.compile(".*/\\d+(\\.(ts|m3u8))?$");
    private static final Pattern VIDEO_FILE = Pattern.compile(".*\\.(mp4|mkv|avi|mov|wmv|flv|mpg|mpeg)$");

    public static class Category {
        public final String name;
        public final List<String> channels = new ArrayList<>();

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
        /** true ise liste M3U'dan değil, panelin API'sinden (player_api.php) oluşturulmuştur. */
        public boolean fromApi;
    }

    /** İndirme ilerlemesi için geri çağırım (arka plan iş parçacığından çağrılır). */
    public interface Progress {
        void onProgress(long bytes, int channels);
    }

    /** Ana grup > Kategori > Kanal yapısını adım adım kurar (M3U ve API aynı yapıyı kullanır). */
    public static final class Builder {
        private final Map<String, Group> groups = new LinkedHashMap<>();
        private int count;

        private Group group(String main) {
            Group g = groups.get(main);
            if (g == null) {
                g = new Group(main);
                groups.put(main, g);
            }
            return g;
        }

        private Category category(String main, String cat) {
            Group g = group(main);
            Category c = g.index.get(cat);
            if (c == null) {
                c = new Category(cat);
                g.index.put(cat, c);
                g.categories.add(c);
            }
            return c;
        }

        /** Kanalı olmasa bile kategoriyi oluşturur (API'deki boş kategoriler için). */
        public void declare(String main, String cat) {
            category(main, cat);
        }

        /** Kanalı ekler. Kanal sınırına ulaşıldıysa false döner (okumayı bırakmak gerekir). */
        public boolean add(String main, String cat, String name) {
            category(main, cat).channels.add(name);
            count++;
            return count < MAX_CHANNELS;
        }

        public int count() {
            return count;
        }

        public boolean isFull() {
            return count >= MAX_CHANNELS;
        }

        public Result build(boolean truncated) {
            Result result = new Result();
            result.truncated = truncated || isFull();
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
    }

    /**
     * Liste "kategorisiz" mi? (Her ana grubun tek kategorisi "GENEL" ise: sunucu group-title
     * bilgisini göndermemiştir, örneğin linkte type=m3u olduğunda.)
     */
    public static boolean isUncategorized(Result r) {
        if (r == null || r.groups.isEmpty()) return false;
        for (Group g : r.groups) {
            if (g.categories.size() != 1 || !GENERAL.equals(g.categories.get(0).name)) return false;
        }
        return true;
    }

    public static Result parse(InputStream in, long maxBytes) throws IOException {
        return parse(in, maxBytes, null);
    }

    public static Result parse(InputStream in, long maxBytes, Progress progress) throws IOException {
        Http.LimitedInputStream limited = new Http.LimitedInputStream(in, maxBytes);
        BufferedReader br = new BufferedReader(new InputStreamReader(limited, "UTF-8"), 64 * 1024);
        Builder builder = new Builder();
        String pendingName = null;
        String pendingCategory = null;
        String extGrp = null;
        boolean capped = false;
        int lines = 0;
        long lastNotify = System.nanoTime();
        String line;
        while ((line = br.readLine()) != null) {
            if (progress != null && (++lines & 255) == 0) {
                long now = System.nanoTime();
                if (now - lastNotify > 100000000L) {          // en fazla 0,1 saniyede bir haber ver
                    lastNotify = now;
                    progress.onProgress(limited.bytesRead(), builder.count());
                }
            }
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
                if (cat == null || cat.isEmpty()) cat = GENERAL;
                boolean room = builder.add(mainGroupOf(line), cat, pendingName);
                pendingName = null;
                pendingCategory = null;
                extGrp = null;
                if (!room) {
                    capped = true;
                    break;
                }
            }
        }
        if (progress != null) progress.onProgress(limited.bytesRead(), builder.count());
        return builder.build(limited.wasTruncated() || capped);
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
