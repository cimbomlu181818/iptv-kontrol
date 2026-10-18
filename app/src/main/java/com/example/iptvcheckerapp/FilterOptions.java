package com.example.iptvcheckerapp;

import java.util.ArrayList;
import java.util.List;

public class FilterOptions {
    // Kullanıcı sayısı filtreleri
    private List<Integer> selectedMaxUsers = new ArrayList<>();

    // Bitiş tarihi filtreleri
    private List<String> selectedYears = new ArrayList<>();

    // Durum filtreleri
    private boolean showFullyActive = false;
    private boolean showPartiallyActive = false;
    private boolean showInactive = false;

    // M3U/API filtreleri
    private boolean showM3uActive = false;
    private boolean showApiActive = false;

    // Aktif bağlantı filtreleri
    private List<Integer> selectedActiveConnections = new ArrayList<>();

    // Trial filtresi
    private Boolean showTrialOnly = null; // null = hepsi, true = sadece trial, false = sadece normal

    // Domain filtresi
    private List<String> selectedDomains = new ArrayList<>();

    public FilterOptions() {
        // Varsayılan: Tüm filtreleri kapat
    }

    // Getter metodları
    public List<Integer> getSelectedMaxUsers() { return selectedMaxUsers; }
    public List<String> getSelectedYears() { return selectedYears; }
    public boolean isShowFullyActive() { return showFullyActive; }
    public boolean isShowPartiallyActive() { return showPartiallyActive; }
    public boolean isShowInactive() { return showInactive; }
    public boolean isShowM3uActive() { return showM3uActive; }
    public boolean isShowApiActive() { return showApiActive; }
    public List<Integer> getSelectedActiveConnections() { return selectedActiveConnections; }
    public Boolean getShowTrialOnly() { return showTrialOnly; }
    public List<String> getSelectedDomains() { return selectedDomains; }

    // Setter metodları
    public void setShowFullyActive(boolean showFullyActive) { this.showFullyActive = showFullyActive; }
    public void setShowPartiallyActive(boolean showPartiallyActive) { this.showPartiallyActive = showPartiallyActive; }
    public void setShowInactive(boolean showInactive) { this.showInactive = showInactive; }
    public void setShowM3uActive(boolean showM3uActive) { this.showM3uActive = showM3uActive; }
    public void setShowApiActive(boolean showApiActive) { this.showApiActive = showApiActive; }
    public void setShowTrialOnly(Boolean showTrialOnly) { this.showTrialOnly = showTrialOnly; }

    // Filtreleri temizle
    public void clearAll() {
        selectedMaxUsers.clear();
        selectedYears.clear();
        showFullyActive = false;
        showPartiallyActive = false;
        showInactive = false;
        showM3uActive = false;
        showApiActive = false;
        selectedActiveConnections.clear();
        showTrialOnly = null;
        selectedDomains.clear();
    }

    // Aktif filtre var mı kontrolü
    public boolean hasActiveFilters() {
        return !selectedMaxUsers.isEmpty() ||
                !selectedYears.isEmpty() ||
                showFullyActive ||
                showPartiallyActive ||
                showInactive ||
                showM3uActive ||
                showApiActive ||
                !selectedActiveConnections.isEmpty() ||
                showTrialOnly != null ||
                !selectedDomains.isEmpty();
    }

    // Bir item'ın filtrelere uyup uymadığını kontrol et
    public boolean matchesFilter(IPTVItem item) {
        // Hiç filtre yoksa tümünü göster
        if (!hasActiveFilters()) {
            return true;
        }

        boolean matches = true;

        // Kullanıcı sayısı filtresi
        if (!selectedMaxUsers.isEmpty()) {
            boolean maxUserMatch = selectedMaxUsers.contains(item.getMaxUsers());
            matches = matches && maxUserMatch;
        }

        // Bitiş tarihi filtresi
        if (!selectedYears.isEmpty() && item.getExpirationDate() != null) {
            boolean yearMatch = false;
            String itemDate = item.getExpirationDate();
            for (String year : selectedYears) {
                if (itemDate.contains(year)) {
                    yearMatch = true;
                    break;
                }
            }
            matches = matches && yearMatch;
        }

        // Durum filtreleri (EN AZ BİRİ seçiliyse)
        if (showFullyActive || showPartiallyActive || showInactive) {
            boolean statusMatch = false;
            if (showFullyActive && item.isFullyActive()) statusMatch = true;
            if (showPartiallyActive && item.isPartiallyActive() && !item.isFullyActive()) statusMatch = true;
            if (showInactive && !item.isPartiallyActive()) statusMatch = true;
            matches = matches && statusMatch;
        }

        // M3U/API filtreleri
        if (showM3uActive) {
            matches = matches && item.isM3uActive();
        }
        if (showApiActive) {
            matches = matches && item.isApiActive();
        }

        // Aktif bağlantı filtresi
        if (!selectedActiveConnections.isEmpty()) {
            boolean activeMatch = selectedActiveConnections.contains(item.getActiveUsers());
            matches = matches && activeMatch;
        }

        // Trial filtresi
        if (showTrialOnly != null) {
            matches = matches && (item.isTrial() == showTrialOnly);
        }

        // Domain filtresi
        if (!selectedDomains.isEmpty() && item.getPanelUrl() != null) {
            boolean domainMatch = false;
            String itemDomain = extractDomainFromUrl(item.getPanelUrl());
            for (String domain : selectedDomains) {
                if (itemDomain.equals(domain)) {
                    domainMatch = true;
                    break;
                }
            }
            matches = matches && domainMatch;
        }

        return matches;
    }

    private String extractDomainFromUrl(String panelUrl) {
        try {
            String url = panelUrl;
            if (!url.startsWith("http")) {
                url = "http://" + url;
            }

            String host = url.replaceFirst("http://", "").replaceFirst("https://", "");
            if (host.contains("/")) {
                host = host.substring(0, host.indexOf("/"));
            }

            if (host.contains(":")) {
                host = host.substring(0, host.indexOf(":"));
            }

            return host;
        } catch (Exception e) {
            return panelUrl;
        }
    }

    // Aktif filtre sayısını döndür
    public int getActiveFilterCount() {
        int count = 0;
        count += selectedMaxUsers.size();
        count += selectedYears.size();
        if (showFullyActive) count++;
        if (showPartiallyActive) count++;
        if (showInactive) count++;
        if (showM3uActive) count++;
        if (showApiActive) count++;
        count += selectedActiveConnections.size();
        if (showTrialOnly != null) count++;
        count += selectedDomains.size();
        return count;
    }
}