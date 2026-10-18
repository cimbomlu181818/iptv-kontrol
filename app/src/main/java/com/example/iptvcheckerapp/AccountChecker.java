package com.example.iptvcheckerapp;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.SSLException;

/**
 * Bir hesabı kontrol eder: önce panel API'si (hesap durumu, bitiş tarihi, bağlantı sayısı),
 * sonra isteğe bağlı olarak M3U listesi (çalışıyor mu, kaç kanal var).
 * Birden çok iş parçacığından aynı anda kullanılabilir. Android'e bağlı değildir.
 */
public class AccountChecker {

    private static final int CONNECT_MS = 8000;
    private static final int API_READ_MS = 10000;
    private static final int M3U_READ_MS = 12000;
    private static final int API_MAX_BYTES = 1000000;
    private static final long M3U_MAX_BYTES = 20L * 1024 * 1024;   // en fazla bu kadar indirilir (kota tasarrufu)
    private static final long M3U_MAX_MS = 20000;
    private static final int DEAD_HOST_LIMIT = 2;

    private final boolean checkM3u;
    // Şu anda açık olan bağlantılar: Durdur'a basılınca hemen kapatılır
    private final Set<HttpURLConnection> active = ConcurrentHashMap.newKeySet();
    private volatile boolean cancelled = false;
    // Bu oturumda üst üste bağlantı kurulamayan paneller (aynı panele tekrar tekrar zaman kaybetmemek için)
    private final Set<String> deadHosts = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, AtomicInteger> failures = new ConcurrentHashMap<>();

    public AccountChecker(boolean checkM3u) {
        this.checkM3u = checkM3u;
    }

    /** Devam eden tüm kontrolleri iptal eder; açık bağlantılar kapatılır (bekleme süresi kalmaz). */
    public void cancel() {
        cancelled = true;
        Http.disconnectAsync(active);
    }

    public CheckResult check(Account a) {
        CheckResult r = new CheckResult(a);
        if (cancelled) return r;
        String panel = a.panelKey();

        if (deadHosts.contains(panel)) {
            r.hostUnreachable = true;
            r.apiMessage = "Panele ulaşılamıyor";
            r.m3uMessage = "Panele ulaşılamıyor";
            return r;
        }

        checkApi(a, r);
        if (cancelled) return r;

        if (r.hostUnreachable) {
            AtomicInteger n = failures.get(panel);
            if (n == null) {
                failures.putIfAbsent(panel, new AtomicInteger(0));
                n = failures.get(panel);
            }
            if (n.incrementAndGet() >= DEAD_HOST_LIMIT) deadHosts.add(panel);
            r.m3uMessage = "Sunucuya ulaşılamadı";
            return r;
        }
        failures.remove(panel);

        // Panel kullanıcı adı/şifrenin yanlış olduğunu söylediyse M3U indirmenin anlamı yok
        if (checkM3u && !r.authFailed) {
            r.m3uChecked = true;
            checkM3uList(a, r);
        }
        return r;
    }

    // ---------------------------------------------------------------- API

    private void checkApi(Account a, CheckResult r) {
        HttpURLConnection c = null;
        long t0 = System.currentTimeMillis();
        try {
            c = Http.open(a.apiUrl(), CONNECT_MS, API_READ_MS, active);
            int code = c.getResponseCode();
            r.apiReachable = true;
            r.responseMs = System.currentTimeMillis() - t0;
            if (code != 200) {
                r.apiMessage = "HTTP " + code;
                return;
            }
            String body = Http.readText(c.getInputStream(), API_MAX_BYTES).trim();
            if (!body.startsWith("{")) {
                r.apiMessage = "API yanıtı geçersiz";
                return;
            }
            JSONObject json = new JSONObject(body);
            JSONObject ui = json.optJSONObject("user_info");
            if (ui == null) {
                r.apiMessage = "Hesap bilgisi alınamadı";
                return;
            }
            parseUserInfo(ui, r);
        } catch (JSONException e) {
            r.apiMessage = "API yanıtı geçersiz";
        } catch (IOException e) {
            r.apiMessage = describe(e);
            r.hostUnreachable = isUnreachable(e);
            r.apiReachable = false;
        } finally {
            Http.release(c, active);
        }
    }

    private void parseUserInfo(JSONObject ui, CheckResult r) {
        String auth = str(ui, "auth");
        String status = str(ui, "status");
        String message = str(ui, "message");
        r.accountStatus = status;
        r.maxConnections = toInt(str(ui, "max_connections"));
        r.activeConnections = toInt(str(ui, "active_cons"));
        String trial = str(ui, "is_trial");
        r.trial = "1".equals(trial) || "true".equalsIgnoreCase(trial);

        String exp = str(ui, "exp_date");
        if (exp != null && exp.matches("\\d+")) {
            try {
                long sec = Long.parseLong(exp);
                if (sec > 0) {
                    r.hasExpiry = true;
                    r.expiresAtMs = sec * 1000L;
                }
            } catch (NumberFormatException ignored) {
                // Tarih okunamadı, süresiz kabul edilir
            }
        }
        boolean timeExpired = r.hasExpiry && r.expiresAtMs < System.currentTimeMillis();

        // auth=0: kullanıcı adı/şifre yanlış
        if ("0".equals(auth) || "false".equalsIgnoreCase(auth)) {
            r.authFailed = true;
            r.apiMessage = (message != null && !message.isEmpty())
                    ? message : "Kullanıcı adı veya şifre hatalı";
            return;
        }

        boolean statusActive = status == null || status.isEmpty() || "active".equalsIgnoreCase(status);
        if (!statusActive) {
            r.expired = true;
            r.apiMessage = "Hesap durumu: " + status;
            return;
        }
        if (timeExpired) {
            r.expired = true;
            r.apiMessage = "Süresi dolmuş";
            return;
        }
        r.apiOk = true;
        r.apiMessage = "Aktif";
    }

    // ---------------------------------------------------------------- M3U

    private void checkM3uList(Account a, CheckResult r) {
        HttpURLConnection c = null;
        try {
            c = Http.open(a.original, CONNECT_MS, M3U_READ_MS, active);
            int code = c.getResponseCode();
            if (code != 200) {
                r.m3uMessage = "HTTP " + code;
                return;
            }
            Http.LimitedInputStream limited = new Http.LimitedInputStream(c.getInputStream(), M3U_MAX_BYTES);
            BufferedReader br = new BufferedReader(new InputStreamReader(limited, "UTF-8"), 64 * 1024);
            long deadline = System.currentTimeMillis() + M3U_MAX_MS;
            int count = 0;
            int lineNo = 0;
            boolean sawHeader = false;
            boolean partial = false;
            String line;
            while ((line = br.readLine()) != null) {
                lineNo++;
                if (lineNo <= 5 && (line.contains("#EXTM3U") || line.contains("#EXTINF"))) sawHeader = true;
                if (!sawHeader && lineNo > 5) break;        // M3U değil (HTML hata sayfası gibi)
                if (line.startsWith("#EXTINF")) count++;
                if ((lineNo & 1023) == 0 && System.currentTimeMillis() > deadline) {
                    partial = true;
                    break;
                }
            }
            if (limited.wasTruncated()) partial = true;
            if (count > 0) {
                r.m3uOk = true;
                r.channelCount = count;
                r.channelCountPartial = partial;
                r.m3uMessage = count + (partial ? "+" : "") + " kanal";
            } else {
                r.m3uMessage = sawHeader ? "Liste boş" : "M3U değil (geçersiz içerik)";
            }
        } catch (IOException e) {
            r.m3uMessage = describe(e);
        } finally {
            Http.release(c, active);
        }
    }

    // ---------------------------------------------------------------- yardımcılar

    private static String str(JSONObject o, String key) {
        if (o == null || !o.has(key) || o.isNull(key)) return null;
        Object v = o.opt(key);
        return v == null ? null : String.valueOf(v).trim();
    }

    private static int toInt(String s) {
        if (s == null) return -1;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    static boolean isUnreachable(IOException e) {
        if (e instanceof UnknownHostException || e instanceof ConnectException
                || e instanceof NoRouteToHostException) return true;
        if (e instanceof SocketTimeoutException) {
            String m = e.getMessage();
            return m != null && m.toLowerCase().contains("connect");
        }
        return false;
    }

    static String describe(IOException e) {
        if (e instanceof UnknownHostException) return "Sunucu bulunamadı (DNS)";
        if (e instanceof ConnectException) return "Bağlantı reddedildi";
        if (e instanceof NoRouteToHostException) return "Sunucuya yol yok";
        if (e instanceof SocketTimeoutException) {
            return isUnreachable(e) ? "Bağlantı zaman aşımı" : "Yanıt zaman aşımı";
        }
        if (e instanceof SSLException) return "SSL/güvenlik hatası";
        String m = e.getMessage();
        return (m == null || m.isEmpty()) ? "Bağlantı hatası" : "Hata: " + m;
    }
}
