package com.example.iptvcheckerapp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class M3UParser {

    // 3 seviyeli yapı: Ana Grup -> Kategori -> Kanallar
    public static Map<String, Map<String, List<String>>> parseM3UContent(String m3uContent) {
        Map<String, Map<String, List<String>>> mainGroupMap = new HashMap<>();
        Map<String, List<String>> categoryOrderMap = new LinkedHashMap<>(); // Kategori sırasını takip etmek için

        android.util.Log.d("M3UParser", "M3U içeriği uzunluğu: " + (m3uContent != null ? m3uContent.length() : "null"));

        if (m3uContent == null || m3uContent.isEmpty()) {
            return mainGroupMap;
        }

        String[] lines = m3uContent.split("\n");
        String currentChannelName = null;
        String currentCategory = "GENEL";
        String currentUrl = null;

        for (String line : lines) {
            line = line.trim();

            if (line.startsWith("#EXTINF:")) {
                currentChannelName = extractChannelName(line);
                currentCategory = extractCategory(line);

            } else if (!line.isEmpty() && !line.startsWith("#") && currentChannelName != null) {
                currentUrl = line;

                String cleanCategory = cleanCategory(currentCategory);
                String mainGroup = determineMainGroup(currentUrl);

                if (!mainGroupMap.containsKey(mainGroup)) {
                    mainGroupMap.put(mainGroup, new LinkedHashMap<>()); // Sırayı korumak için LinkedHashMap
                }

                Map<String, List<String>> categoryMap = mainGroupMap.get(mainGroup);

                if (!categoryMap.containsKey(cleanCategory)) {
                    categoryMap.put(cleanCategory, new ArrayList<>());
                    // Kategori sırasını kaydet (sadece ilk kez görüldüğünde)
                    if (!categoryOrderMap.containsKey(cleanCategory)) {
                        categoryOrderMap.put(cleanCategory, new ArrayList<>());
                    }
                }

                List<String> channels = categoryMap.get(cleanCategory);

                if (!channels.contains(currentChannelName)) {
                    channels.add(currentChannelName);
                }

                currentChannelName = null;
                currentUrl = null;
            }
        }

        // Ana grupları ve kategorileri sırala
        Map<String, Map<String, List<String>>> sortedMainGroupMap = sortMainGroupsAndCategories(mainGroupMap, categoryOrderMap);

        android.util.Log.d("M3UParser", "Toplam " + sortedMainGroupMap.size() + " ana grup bulundu");
        return sortedMainGroupMap;
    }

    private static String extractChannelName(String extinfLine) {
        try {
            int lastComma = extinfLine.lastIndexOf(",");
            if (lastComma != -1 && lastComma + 1 < extinfLine.length()) {
                return extinfLine.substring(lastComma + 1).trim();
            }

            if (extinfLine.contains("tvg-name=")) {
                int start = extinfLine.indexOf("tvg-name=\"") + 10;
                int end = extinfLine.indexOf("\"", start);
                if (start != -1 && end != -1 && start < end) {
                    return extinfLine.substring(start, end).trim();
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        return "Bilinmeyen Kanal";
    }

    private static String extractCategory(String extinfLine) {
        try {
            if (extinfLine.contains("group-title=")) {
                int start = extinfLine.indexOf("group-title=\"") + 13;
                int end = extinfLine.indexOf("\"", start);
                if (start != -1 && end != -1 && start < end) {
                    return extinfLine.substring(start, end).trim();
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return "GENEL";
    }

    private static String cleanCategory(String category) {
        if (category == null || category.isEmpty()) {
            return "GENEL";
        }
        return category.trim();
    }

    // Ana grup belirleme
    private static String determineMainGroup(String url) {
        if (url == null || url.isEmpty()) return "DİĞER";

        if (hasSlashNumberFormat(url)) {
            return "CANLI TV";
        } else if (isMovieUrl(url)) {
            return "FİLMLER";
        } else if (isSeriesUrl(url)) {
            return "DİZİLER";
        }

        return "DİĞER";
    }

    // Ana grupları ve kategorileri sırala
    private static Map<String, Map<String, List<String>>> sortMainGroupsAndCategories(
            Map<String, Map<String, List<String>>> mainGroupMap,
            Map<String, List<String>> categoryOrderMap) {

        Map<String, Map<String, List<String>>> sortedMap = new LinkedHashMap<>();

        // Ana grup sıralaması
        String[] mainGroupOrder = {"CANLI TV", "FİLMLER", "DİZİLER", "DİĞER"};

        for (String mainGroup : mainGroupOrder) {
            if (mainGroupMap.containsKey(mainGroup)) {
                Map<String, List<String>> categoryMap = mainGroupMap.get(mainGroup);

                if (mainGroup.equals("CANLI TV")) {
                    // CANLI TV: Türkçe öncelikli + M3U orijinal sıralama
                    Map<String, List<String>> sortedCategoryMap = sortLiveTvCategoriesWithTurkishPriority(categoryMap);
                    sortedMap.put(mainGroup, sortedCategoryMap);
                } else {
                    // Diğer gruplar: M3U orijinal sıralama (LinkedHashMap zaten sırayı koruyor)
                    sortedMap.put(mainGroup, categoryMap);
                }
            }
        }

        return sortedMap;
    }

    // CANLI TV kategorilerini Türkçe öncelikli + M3U orijinal sıralama
    private static Map<String, List<String>> sortLiveTvCategoriesWithTurkishPriority(
            Map<String, List<String>> categoryMap) {

        Map<String, List<String>> sortedMap = new LinkedHashMap<>();

        // 1. AŞAMA: Türkçe isimli kategoriler
        Map<String, List<String>> turkishNamedCategories = new LinkedHashMap<>();

        // 2. AŞAMA: Diğer kategoriler (M3U orijinal sırası)
        Map<String, List<String>> otherCategories = new LinkedHashMap<>();

        // Kategorileri ayır
        for (Map.Entry<String, List<String>> entry : categoryMap.entrySet()) {
            String category = entry.getKey();
            List<String> channels = entry.getValue();

            if (isTurkishCategory(category)) {
                // 1. AŞAMA: Türkçe isimli kategoriler
                turkishNamedCategories.put(category, channels);
                android.util.Log.d("M3UParser", "Türk Kategori: " + category + " - 1. Aşama");
            } else {
                // 2. AŞAMA: Diğer kategoriler (M3U sırası korunacak)
                otherCategories.put(category, channels);
                android.util.Log.d("M3UParser", "Diğer Kategori: " + category + " - 2. Aşama (M3U sırası)");
            }
        }

        // Sıralı birleştir: Önce Türkçe kategoriler, sonra diğerleri (M3U sırasında)
        sortedMap.putAll(turkishNamedCategories);
        sortedMap.putAll(otherCategories);

        android.util.Log.d("M3UParser", "Sıralama Tamamlandı: " +
                turkishNamedCategories.size() + " Türk isimli, " +
                otherCategories.size() + " diğer kategori");

        return sortedMap;
    }

    // Kategori Türkçe mi kontrol et (1. Aşama)
    private static boolean isTurkishCategory(String categoryName) {
        if (categoryName == null || categoryName.isEmpty()) {
            return false;
        }

        String lowerCategory = categoryName.toLowerCase();

        return lowerCategory.startsWith("turk") ||
                lowerCategory.startsWith("tr:") ||
                lowerCategory.startsWith("türk") ||
                lowerCategory.contains("turkish") ||
                lowerCategory.contains("türkçe") ||
                lowerCategory.contains("turkey") ||
                lowerCategory.contains("yerli");
    }

    // Linkin sonunda "/sayı" formatı olup olmadığını kontrol et (CANLI TV)
    private static boolean hasSlashNumberFormat(String url) {
        if (url == null || url.isEmpty()) return false;

        String cleanUrl = url.split("[?#]")[0];
        String lastPart = cleanUrl.trim();

        boolean hasSlashNumber = lastPart.matches(".*/\\d+$");

        return hasSlashNumber;
    }

    // Film URL'si mi kontrol et
    private static boolean isMovieUrl(String url) {
        if (url == null || url.isEmpty()) return false;

        String lowerUrl = url.toLowerCase();
        return lowerUrl.contains("movie") ||
                lowerUrl.contains("film") ||
                lowerUrl.contains("/movies/") ||
                lowerUrl.contains("/film/");
    }

    // Dizi URL'si mi kontrol et
    private static boolean isSeriesUrl(String url) {
        if (url == null || url.isEmpty()) return false;

        String lowerUrl = url.toLowerCase();
        return lowerUrl.contains("series") ||
                lowerUrl.contains("dizi") ||
                lowerUrl.contains("serial") ||
                lowerUrl.contains("/series/") ||
                lowerUrl.contains("/dizi/");
    }

    public static List<Channel> parseToChannelList(String m3uContent) {
        List<Channel> channels = new ArrayList<>();

        if (m3uContent == null || m3uContent.isEmpty()) {
            return channels;
        }

        String[] lines = m3uContent.split("\n");
        String currentName = null;
        String currentCategory = "GENEL";
        String currentLogo = "";
        String currentUrl = null;

        for (String line : lines) {
            line = line.trim();

            if (line.startsWith("#EXTINF:")) {
                currentName = extractChannelName(line);
                currentCategory = extractCategory(line);
                currentLogo = extractLogo(line);

            } else if (!line.isEmpty() && !line.startsWith("#") && currentName != null) {
                currentUrl = line;

                String cleanCategory = cleanCategory(currentCategory);
                channels.add(new Channel(currentName, currentUrl, cleanCategory, currentLogo));
                currentName = null;
                currentUrl = null;
            }
        }

        return channels;
    }

    private static String extractLogo(String extinfLine) {
        try {
            if (extinfLine.contains("tvg-logo=")) {
                int start = extinfLine.indexOf("tvg-logo=\"") + 10;
                int end = extinfLine.indexOf("\"", start);
                if (start != -1 && end != -1 && start < end) {
                    return extinfLine.substring(start, end).trim();
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return "";
    }
}