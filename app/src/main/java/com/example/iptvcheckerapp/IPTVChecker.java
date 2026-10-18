package com.example.iptvcheckerapp;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.json.JSONObject;
import java.util.concurrent.TimeUnit;

public class IPTVChecker {

    private final OkHttpClient client;

    public IPTVChecker() {
        this.client = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)    // 10 saniye
                .readTimeout(10, TimeUnit.SECONDS)       // 10 saniye
                .writeTimeout(10, TimeUnit.SECONDS)      // 10 saniye
                .followRedirects(true)
                .followSslRedirects(true)
                .build();
    }

    public IPTVItem checkIPTVLine(String line) {
        IPTVItem item = new IPTVItem(line);

        android.util.Log.d("IPTVChecker", "════════════════════════════════");
        android.util.Log.d("IPTVChecker", "YENİ KONTROL BAŞLADI");
        android.util.Log.d("IPTVChecker", "Link: " + line);

        // M3U linkini parse et
        if (!parseM3ULink(item, line)) {
            android.util.Log.e("IPTVChecker", "❌ Parse başarısız!");
            item.setStatusMessage("❌ Geçersiz M3U formatı");
            return item;
        }

        android.util.Log.d("IPTVChecker", "✅ Parse başarılı");
        android.util.Log.d("IPTVChecker", "Panel: " + item.getPanelUrl());
        android.util.Log.d("IPTVChecker", "Username: " + item.getUsername());
        android.util.Log.d("IPTVChecker", "Password: " + item.getPassword());

        try {
            // 1. M3U link kontrolü
            android.util.Log.d("IPTVChecker", "--- M3U Kontrolü Başlıyor ---");
            checkM3uStatus(item);

            // 2. Xtream Codes API'den kullanıcı bilgilerini çek
            android.util.Log.d("IPTVChecker", "--- API Kontrolü Başlıyor ---");
            fetchUserInfoFromApi(item);

        } catch (Exception e) {
            android.util.Log.e("IPTVChecker", "❌ Genel Hata: " + e.getMessage());
            e.printStackTrace();
            item.setStatusMessage("❌ Hata: " + e.getMessage());
        }

        android.util.Log.d("IPTVChecker", "KONTROL BİTTİ");
        android.util.Log.d("IPTVChecker", "════════════════════════════════");

        return item;
    }

    private boolean parseM3ULink(IPTVItem item, String line) {
        try {
            android.net.Uri uri = android.net.Uri.parse(line);
            String username = uri.getQueryParameter("username");
            String password = uri.getQueryParameter("password");

            android.util.Log.d("IPTVChecker", "Parse - Username: " + username);
            android.util.Log.d("IPTVChecker", "Parse - Password: " + password);

            if (username == null || password == null) {
                android.util.Log.e("IPTVChecker", "Username veya password null!");
                return false;
            }

            String baseUrl = extractBaseUrl(line);
            android.util.Log.d("IPTVChecker", "Parse - Base URL: " + baseUrl);

            item.setPanelUrl(baseUrl);
            item.setUsername(username);
            item.setPassword(password);

            return true;
        } catch (Exception e) {
            android.util.Log.e("IPTVChecker", "Parse exception: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    private String extractBaseUrl(String m3uLink) {
        try {
            android.net.Uri uri = android.net.Uri.parse(m3uLink);
            String host = uri.getHost();
            int port = uri.getPort();

            android.util.Log.d("IPTVChecker", "Extract - Host: " + host);
            android.util.Log.d("IPTVChecker", "Extract - Port: " + port);

            if (port == -1) {
                port = 80;
            }

            return host + ":" + port;
        } catch (Exception e) {
            android.util.Log.e("IPTVChecker", "Base URL çıkarma hatası: " + e.getMessage());
            return "unknown";
        }
    }

    private void checkM3uStatus(IPTVItem item) {
        try {
            String m3uUrl = item.getOriginalLine();
            android.util.Log.d("IPTVChecker", "M3U URL: " + m3uUrl);

            Request request = new Request.Builder()
                    .url(m3uUrl)
                    .get()
                    .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .addHeader("Accept", "*/*")
                    .build();

            long startTime = System.currentTimeMillis();

            try (Response response = client.newCall(request).execute()) {
                long responseTime = System.currentTimeMillis() - startTime;
                item.setResponseTime(responseTime + " ms");

                android.util.Log.d("IPTVChecker", "M3U Response Code: " + response.code());
                android.util.Log.d("IPTVChecker", "M3U Response Time: " + responseTime + " ms");

                // 10 saniyeden uzun süren istekleri başarısız say
                if (responseTime > 10000) {
                    item.setM3uActive(false);
                    item.setStatusMessage("⏰ Zaman aşımı (10sn)");
                    android.util.Log.w("IPTVChecker", "⚠️ İstek 10 saniyeyi aştı: " + responseTime + " ms");
                    return;
                }

                boolean isSuccess = response.isSuccessful();
                item.setM3uActive(isSuccess);

                if (isSuccess && response.body() != null) {
                    String content = response.body().string();

                    android.util.Log.d("IPTVChecker", "M3U Content Length: " + content.length());
                    android.util.Log.d("IPTVChecker", "M3U İlk 200 karakter: " +
                            content.substring(0, Math.min(200, content.length())));

                    // M3U kontrolü
                    if (content.contains("#EXTM3U") || content.contains("#EXTINF")) {
                        int channelCount = content.split("#EXTINF").length - 1;
                        item.setChannelCount(Math.max(0, channelCount));
                        item.setStatusMessage("✅ M3U Aktif - " + channelCount + " kanal");
                        android.util.Log.d("IPTVChecker", "✅ Kanal sayısı: " + channelCount);
                    } else if (content.contains("http://") || content.contains("https://")) {
                        int linkCount = content.split("http").length - 1;
                        item.setChannelCount(linkCount);
                        item.setStatusMessage("✅ Stream Aktif - " + linkCount + " link");
                        android.util.Log.d("IPTVChecker", "✅ Link sayısı: " + linkCount);
                    } else {
                        item.setStatusMessage("⚠️ İçerik belirsiz (" + content.length() + " byte)");
                        android.util.Log.w("IPTVChecker", "⚠️ Beklenmeyen içerik formatı");
                    }
                } else {
                    item.setStatusMessage("❌ M3U Pasif - HTTP " + response.code());
                    android.util.Log.e("IPTVChecker", "❌ HTTP Hatası: " + response.code());
                }
            }

        } catch (Exception e) {
            item.setM3uActive(false);
            android.util.Log.e("IPTVChecker", "❌ M3U Exception: " + e.getMessage());
            e.printStackTrace();

            String errorMsg = e.getMessage();
            if (errorMsg != null) {
                if (errorMsg.contains("timeout") || errorMsg.contains("Timeout")) {
                    item.setStatusMessage("⏰ Zaman aşımı (10sn)");
                } else if (errorMsg.contains("connect") || errorMsg.contains("Connection")) {
                    item.setStatusMessage("🔌 Bağlantı hatası");
                } else {
                    item.setStatusMessage("❌ Hata: " + errorMsg);
                }
            } else {
                item.setStatusMessage("❌ Bilinmeyen hata");
            }
        }
    }

    private void fetchUserInfoFromApi(IPTVItem item) {
        try {
            String baseUrl = item.getPanelUrl();

            // Port ayrıştırma
            String host;
            int port;

            if (baseUrl.contains(":")) {
                String[] parts = baseUrl.split(":");
                host = parts[0];
                try {
                    port = Integer.parseInt(parts[1]);
                } catch (Exception e) {
                    port = 80;
                }
            } else {
                host = baseUrl;
                port = 80;
            }

            android.util.Log.d("IPTVChecker", "API Host: " + host);
            android.util.Log.d("IPTVChecker", "API Port: " + port);

            // API URL oluştur
            String apiUrl = "http://" + host + ":" + port +
                    "/player_api.php?username=" + item.getUsername() +
                    "&password=" + item.getPassword();

            android.util.Log.d("IPTVChecker", "API URL: " + apiUrl);

            Request request = new Request.Builder()
                    .url(apiUrl)
                    .get()
                    .addHeader("User-Agent", "Mozilla/5.0")
                    .build();

            long startTime = System.currentTimeMillis();

            try (Response response = client.newCall(request).execute()) {
                long responseTime = System.currentTimeMillis() - startTime;

                // 10 saniye kontrolü
                if (responseTime > 10000) {
                    item.setApiActive(false);
                    android.util.Log.w("IPTVChecker", "⚠️ API isteği 10 saniyeyi aştı: " + responseTime + " ms");
                    return;
                }

                android.util.Log.d("IPTVChecker", "API Response Code: " + response.code());
                android.util.Log.d("IPTVChecker", "API Response Time: " + responseTime + " ms");

                if (response.isSuccessful() && response.body() != null) {
                    String jsonResponse = response.body().string();

                    android.util.Log.d("IPTVChecker", "API Yanıt Uzunluğu: " + jsonResponse.length());
                    android.util.Log.d("IPTVChecker", "API Yanıt: " + jsonResponse);

                    JSONObject json = new JSONObject(jsonResponse);
                    item.setApiActive(true);

                    // user_info kontrolü
                    if (json.has("user_info")) {
                        android.util.Log.d("IPTVChecker", "✅ user_info bulundu");
                        JSONObject userInfo = json.getJSONObject("user_info");

                        // Max connections
                        if (userInfo.has("max_connections")) {
                            String maxConn = userInfo.getString("max_connections");
                            android.util.Log.d("IPTVChecker", "Max connections: " + maxConn);
                            try {
                                item.setMaxUsers(Integer.parseInt(maxConn));
                            } catch (Exception e) {
                                item.setMaxUsers(0);
                            }
                        }

                        // Active connections
                        if (userInfo.has("active_cons")) {
                            String activeCons = userInfo.getString("active_cons");
                            android.util.Log.d("IPTVChecker", "Active connections: " + activeCons);
                            try {
                                item.setActiveUsers(Integer.parseInt(activeCons));
                            } catch (Exception e) {
                                item.setActiveUsers(0);
                            }
                        }

                        // Expiration date
                        if (userInfo.has("exp_date")) {
                            String expDate = userInfo.getString("exp_date");
                            android.util.Log.d("IPTVChecker", "Exp date: " + expDate);
                            if (!expDate.equals("null") && !expDate.isEmpty() && !expDate.equals("0")) {
                                try {
                                    long timestamp = Long.parseLong(expDate);
                                    java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("dd.MM.yyyy HH:mm");
                                    item.setExpirationDate(sdf.format(new java.util.Date(timestamp * 1000)));
                                } catch (Exception e) {
                                    item.setExpirationDate(expDate);
                                }
                            }
                        }

                        // Status
                        if (userInfo.has("status")) {
                            String status = userInfo.getString("status");
                            android.util.Log.d("IPTVChecker", "Status: " + status);
                            item.setStatus(status);
                        }

                        // Trial
                        if (userInfo.has("is_trial")) {
                            String trial = userInfo.getString("is_trial");
                            android.util.Log.d("IPTVChecker", "Is trial: " + trial);
                            item.setTrial(trial.equals("1"));
                        }

                    } else {
                        android.util.Log.w("IPTVChecker", "⚠️ user_info bulunamadı");
                        android.util.Log.d("IPTVChecker", "JSON keys: " + json.keys().toString());
                    }

                } else {
                    item.setApiActive(false);
                    android.util.Log.e("IPTVChecker", "❌ API Hatası: " + response.code() + " - " + response.message());
                }
            }

        } catch (Exception e) {
            item.setApiActive(false);
            android.util.Log.e("IPTVChecker", "❌ API Exception: " + e.getMessage());
            e.printStackTrace();

            if (e.getMessage() != null && e.getMessage().contains("timeout")) {
                android.util.Log.w("IPTVChecker", "⚠️ API zaman aşımı");
            }
        }
    }
}