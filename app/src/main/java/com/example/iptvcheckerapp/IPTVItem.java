package com.example.iptvcheckerapp;

import java.io.Serializable;

public class IPTVItem implements Serializable {
    private String originalLine;
    private String panelUrl;
    private String username;
    private String password;

    // Sonuçlar
    private boolean panelActive;
    private boolean apiActive;
    private boolean m3uActive;
    private String statusMessage;
    private int activeUsers;
    private int maxUsers;
    private String expirationDate;
    private int channelCount;
    private int vodCount;
    private String responseTime;
    private String status;
    private boolean isTrial;
    private String createdAt;

    public IPTVItem(String originalLine) {
        this.originalLine = originalLine;
    }

    // Getter ve Setter metodları
    public String getOriginalLine() { return originalLine; }
    public String getPanelUrl() { return panelUrl; }
    public void setPanelUrl(String panelUrl) { this.panelUrl = panelUrl; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public boolean isPanelActive() { return panelActive; }
    public void setPanelActive(boolean panelActive) { this.panelActive = panelActive; }
    public boolean isApiActive() { return apiActive; }
    public void setApiActive(boolean apiActive) { this.apiActive = apiActive; }
    public boolean isM3uActive() { return m3uActive; }
    public void setM3uActive(boolean m3uActive) { this.m3uActive = m3uActive; }
    public String getStatusMessage() { return statusMessage; }
    public void setStatusMessage(String statusMessage) { this.statusMessage = statusMessage; }
    public int getActiveUsers() { return activeUsers; }
    public void setActiveUsers(int activeUsers) { this.activeUsers = activeUsers; }
    public int getMaxUsers() { return maxUsers; }
    public void setMaxUsers(int maxUsers) { this.maxUsers = maxUsers; }
    public String getExpirationDate() { return expirationDate; }
    public void setExpirationDate(String expirationDate) { this.expirationDate = expirationDate; }
    public int getChannelCount() { return channelCount; }
    public void setChannelCount(int channelCount) { this.channelCount = channelCount; }
    public int getVodCount() { return vodCount; }
    public void setVodCount(int vodCount) { this.vodCount = vodCount; }
    public String getResponseTime() { return responseTime; }
    public void setResponseTime(String responseTime) { this.responseTime = responseTime; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public boolean isTrial() { return isTrial; }
    public void setTrial(boolean trial) { isTrial = trial; }
    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }

    public String getFormattedStatus() {
        int activeCount = 0;
        if (m3uActive) activeCount++;
        if (apiActive) activeCount++;
        return activeCount + "/2 sistem çalışıyor";
    }

    public boolean isFullyActive() {
        return m3uActive && apiActive;
    }

    public boolean isPartiallyActive() {
        return m3uActive || apiActive;
    }

    public String getUserInfo() {
        StringBuilder info = new StringBuilder();
        if (activeUsers > 0 && maxUsers > 0) {
            info.append("👥 ").append(activeUsers).append("/").append(maxUsers);
        } else if (maxUsers > 0) {
            info.append("👥 0/").append(maxUsers);
        }
        if (expirationDate != null && !expirationDate.isEmpty()) {
            if (info.length() > 0) info.append(" | ");
            info.append("📅 ").append(expirationDate);
        }
        if (isTrial) {
            if (info.length() > 0) info.append(" | ");
            info.append("🧪 Trial");
        }
        if (status != null && !status.isEmpty()) {
            if (info.length() > 0) info.append(" | ");
            info.append("🔸 ").append(status);
        }
        return info.toString();
    }
}