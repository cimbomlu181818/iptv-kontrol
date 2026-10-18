package com.example.iptvcheckerapp;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

public class DynamicFilterManager {
    private Set<Integer> availableMaxUsers = new TreeSet<>();
    private Set<String> availableYears = new TreeSet<>();
    private Set<String> availableDomains = new TreeSet<>();

    public void updateFromItems(List<IPTVItem> items) {
        updateMaxUsers(items);
        updateYears(items);
        updateDomains(items);
    }

    private void updateMaxUsers(List<IPTVItem> items) {
        Set<Integer> users = new TreeSet<>();
        for (IPTVItem item : items) {
            if (item.getMaxUsers() > 0) {
                users.add(item.getMaxUsers());
            }
        }
        availableMaxUsers = users;
    }

    private void updateYears(List<IPTVItem> items) {
        Set<String> years = new TreeSet<>();
        for (IPTVItem item : items) {
            if (item.getExpirationDate() != null && !item.getExpirationDate().isEmpty()) {
                // Tarihten yılı çıkar (dd.MM.yyyy formatından)
                String date = item.getExpirationDate();
                if (date.length() >= 10) {
                    String year = date.substring(6, 10); // yyyy kısmını al
                    if (year.matches("\\d{4}")) {
                        years.add(year);
                    }
                }
                // Timestamp formatı için alternatif kontrol
                else if (date.matches("\\d+")) {
                    try {
                        long timestamp = Long.parseLong(date);
                        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy");
                        String year = sdf.format(new java.util.Date(timestamp * 1000));
                        years.add(year);
                    } catch (Exception e) {
                        // Geçersiz timestamp, atla
                    }
                }
            }
        }
        availableYears = years;
    }

    private void updateDomains(List<IPTVItem> items) {
        Set<String> domains = new TreeSet<>();
        for (IPTVItem item : items) {
            if (item.getPanelUrl() != null && !item.getPanelUrl().isEmpty()) {
                String domain = extractDomain(item.getPanelUrl());
                if (domain != null && !domain.equals("unknown")) {
                    domains.add(domain);
                }
            }
        }
        availableDomains = domains;
    }

    private String extractDomain(String panelUrl) {
        try {
            // URL formatını düzelt
            String url = panelUrl;
            if (!url.startsWith("http")) {
                url = "http://" + url;
            }

            // Domain ve port'u ayır
            String host = url.replaceFirst("http://", "").replaceFirst("https://", "");
            if (host.contains("/")) {
                host = host.substring(0, host.indexOf("/"));
            }

            // Port'u kaldır
            if (host.contains(":")) {
                host = host.substring(0, host.indexOf(":"));
            }

            return host;
        } catch (Exception e) {
            return panelUrl;
        }
    }

    // Getter metodları
    public List<Integer> getAvailableMaxUsers() {
        return new ArrayList<>(availableMaxUsers);
    }

    public List<String> getAvailableYears() {
        return new ArrayList<>(availableYears);
    }

    public List<String> getAvailableDomains() {
        return new ArrayList<>(availableDomains);
    }

    public void clear() {
        availableMaxUsers.clear();
        availableYears.clear();
        availableDomains.clear();
    }
}