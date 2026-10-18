package com.example.iptvcheckerapp;

import java.util.Locale;

/** Tek bir IPTV hesabı (linkten çıkarılan bilgiler). Android'e bağlı değildir. */
public class Account {
    public final String original;    // Linkin tam hali (M3U kontrolünde aynen kullanılır)
    public final String scheme;      // http veya https
    public final String host;        // küçük harfe çevrilmiş
    public final int port;           // -1 = linkte port yok
    public final String pathPrefix;  // get.php'den önceki yol ("" ya da "/c" gibi)
    public final String username;    // linkteki ham hali
    public final String password;    // linkteki ham hali

    public Account(String original, String scheme, String host, int port,
                   String pathPrefix, String username, String password) {
        this.original = original;
        this.scheme = scheme;
        this.host = host.toLowerCase(Locale.ROOT);
        this.port = port;
        this.pathPrefix = pathPrefix == null ? "" : pathPrefix;
        this.username = username;
        this.password = password;
    }

    private int effectivePort() {
        if (port > 0) return port;
        return "https".equals(scheme) ? 443 : 80;
    }

    /** Panel adı: host veya host:port (varsayılan port yazılmaz). */
    public String panelKey() {
        int def = "https".equals(scheme) ? 443 : 80;
        if (port > 0 && port != def) return host + ":" + port;
        return host;
    }

    public String baseUrl() {
        StringBuilder sb = new StringBuilder();
        sb.append(scheme).append("://").append(host);
        if (port > 0) sb.append(':').append(port);
        return sb.toString();
    }

    public String apiUrl() {
        return baseUrl() + pathPrefix + "/player_api.php?username=" + username + "&password=" + password;
    }

    /**
     * Kanal listesi (M3U) adresi. Link type=m3u ise sunucu kategori (group-title) bilgisini
     * göndermez ve her şey "GENEL" altında toplanır; bu yüzden her zaman m3u_plus istenir.
     */
    public String listUrl() {
        return baseUrl() + pathPrefix + "/get.php?username=" + username + "&password=" + password
                + "&type=m3u_plus&output=ts";
    }

    /** Aynı hesabı tekrar saymamak için anahtar. */
    public String dedupeKey() {
        return host + ":" + effectivePort() + "|" + username + "|" + password;
    }
}
