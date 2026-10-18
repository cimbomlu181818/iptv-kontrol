package com.example.iptvcheckerapp;

import android.util.JsonReader;
import android.util.JsonToken;

import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Panelin API'sinden (player_api.php) kategorili kanal listesi kurar. M3U listesi kategori
 * bilgisi içermediğinde (ya da hiç açılmadığında) yedek yol olarak kullanılır.
 *
 * Her bölüm için iki istek yapılır (önce kategoriler, sonra yayınlar). Hepsi istenirse altı,
 * sadece biri istenirse iki istek gider.
 * JSON akış halinde okunur, bu yüzden büyük listeler belleği şişirmez.
 */
public final class ApiCatalog {

    private ApiCatalog() {}

    /** İlerleme bilgisi: adım numarası (1..steps), adımdaki indirme durumu ve bulunan kanal sayısı. */
    public interface Progress {
        void onProgress(int step, int steps, String label, long bytes, long total, int channels);
    }

    /** Kullanıcı ekrandan çıktığında işi bırakmak için. */
    public interface Control {
        boolean cancelled();
    }

    private static final long MAX_BYTES = 60L * 1024 * 1024;
    private static final long MAX_CATEGORY_BYTES = 8L * 1024 * 1024;

    // { kategori eylemi, yayın eylemi, ana grup, etiket }
    private static final String[][] PARTS = {
            {"get_live_categories", "get_live_streams", M3UParser.LIVE, "Canlı yayınlar"},
            {"get_vod_categories", "get_vod_streams", M3UParser.MOVIES, "Filmler"},
            {"get_series_categories", "get_series", M3UParser.SERIES, "Diziler"},
    };

    /** Üç bölümün de indirildiği klasik yol. */
    public static M3UParser.Result load(Account a, Collection<HttpURLConnection> track,
                                        Control ctl, Progress p) throws IOException {
        return load(a, track, ctl, p, new boolean[]{true, true, true});
    }

    /**
     * Sadece istenen bölümleri indirir. wanted sırası: [0] canlı, [1] film, [2] dizi.
     * İstenmeyen bölüm için hiç istek yapılmaz.
     */
    public static M3UParser.Result load(Account a, Collection<HttpURLConnection> track,
                                        Control ctl, Progress p, boolean[] wanted) throws IOException {
        int selected = 0;
        for (int i = 0; i < PARTS.length; i++) {
            if (i < wanted.length && wanted[i]) selected++;
        }
        final int steps = Math.max(1, selected * 2);

        M3UParser.Builder b = new M3UParser.Builder();
        boolean truncated = false;
        IOException firstError = null;
        int stepNo = 0;

        for (int i = 0; i < PARTS.length; i++) {
            if (ctl.cancelled()) break;
            if (i >= wanted.length || !wanted[i]) continue;
            String[] part = PARTS[i];
            String main = part[2];
            String label = part[3];

            Map<String, String> cats = new LinkedHashMap<>();
            try {
                readCategories(a, part[0], track, ++stepNo, steps, label, p, b.count(), cats);
            } catch (IOException e) {
                if (AccountChecker.isUnreachable(e)) throw e;   // sunucu kapalı: devam etmenin anlamı yok
                if (firstError == null) firstError = e;
            }
            for (String name : cats.values()) b.declare(main, name);
            if (ctl.cancelled()) break;

            try {
                if (readStreams(a, part[1], track, ++stepNo, steps, label, p, ctl, cats, main, b)) truncated = true;
            } catch (IOException e) {
                if (AccountChecker.isUnreachable(e)) throw e;
                if (b.count() > 0) truncated = true;          // yarıda kesildi, eldeki kısım gösterilir
                if (firstError == null) firstError = e;
            }
            if (b.isFull()) {
                truncated = true;
                break;
            }
        }
        if (b.count() == 0 && firstError != null) throw firstError;
        M3UParser.Result r = b.build(truncated);
        r.fromApi = true;
        return r;
    }

    // ------------------------------------------------------------------ istekler

    private static HttpURLConnection openAction(Account a, String action,
                                                Collection<HttpURLConnection> track) throws IOException {
        HttpURLConnection c = Http.open(a.apiUrl() + "&action=" + action, 8000, 30000, track);
        int code;
        try {
            code = c.getResponseCode();
        } catch (IOException e) {
            Http.release(c, track);
            throw e;
        }
        if (code != 200) {
            Http.release(c, track);
            throw new IOException("HTTP " + code);
        }
        return c;
    }

    private static void report(Progress p, int step, int steps, String label, long bytes, long total, int channels) {
        if (p != null) p.onProgress(step, steps, label, bytes, total, channels);
    }

    /** Kategori kimliği > kategori adı eşlemesini okur. */
    private static void readCategories(Account a, String action, Collection<HttpURLConnection> track,
                                       int step, int steps, String label, Progress p, int channels,
                                       Map<String, String> out) throws IOException {
        HttpURLConnection c = null;
        try {
            c = openAction(a, action, track);
            report(p, step, steps, label + " (kategoriler)", 0, c.getContentLengthLong(), channels);
            Http.LimitedInputStream limited = new Http.LimitedInputStream(c.getInputStream(), MAX_CATEGORY_BYTES);
            JsonReader r = new JsonReader(new InputStreamReader(limited, "UTF-8"));
            r.setLenient(true);
            try {
                if (r.peek() != JsonToken.BEGIN_ARRAY) return;
                r.beginArray();
                while (r.hasNext()) {
                    if (r.peek() != JsonToken.BEGIN_OBJECT) {
                        r.skipValue();
                        continue;
                    }
                    String id = null;
                    String name = null;
                    r.beginObject();
                    while (r.hasNext()) {
                        String key = r.nextName();
                        if ("category_id".equals(key)) id = readString(r);
                        else if ("category_name".equals(key)) name = readString(r);
                        else r.skipValue();
                    }
                    r.endObject();
                    if (id != null && name != null && !name.trim().isEmpty()) out.put(id, name.trim());
                }
                r.endArray();
            } catch (IllegalStateException e) {
                throw new IOException("Geçersiz JSON");
            }
        } finally {
            Http.release(c, track);
        }
    }

    /**
     * Yayınları okuyup Builder'a ekler. Liste yarıda kesildiyse (boyut/kanal sınırı) true döner.
     */
    private static boolean readStreams(Account a, String action, Collection<HttpURLConnection> track,
                                       int step, int steps, String label, Progress p, Control ctl,
                                       Map<String, String> cats, String main,
                                       M3UParser.Builder b) throws IOException {
        HttpURLConnection c = null;
        try {
            c = openAction(a, action, track);
            long total = c.getContentLengthLong();
            report(p, step, steps, label, 0, total, b.count());
            Http.LimitedInputStream limited = new Http.LimitedInputStream(c.getInputStream(), MAX_BYTES);
            JsonReader r = new JsonReader(new InputStreamReader(limited, "UTF-8"));
            r.setLenient(true);
            long lastNotify = System.nanoTime();
            int n = 0;
            try {
                if (r.peek() != JsonToken.BEGIN_ARRAY) return false;
                r.beginArray();
                while (r.hasNext()) {
                    if (ctl.cancelled()) return false;
                    if (r.peek() != JsonToken.BEGIN_OBJECT) {
                        r.skipValue();
                        continue;
                    }
                    String name = null;
                    String cid = null;
                    String sid = null;
                    r.beginObject();
                    while (r.hasNext()) {
                        String key = r.nextName();
                        if ("name".equals(key)) name = readString(r);
                        else if ("category_id".equals(key)) cid = readString(r);
                        else if ("stream_id".equals(key) && M3UParser.LIVE.equals(main)) sid = readString(r);
                        else r.skipValue();
                    }
                    r.endObject();

                    if (name == null || name.trim().isEmpty()) name = "Adsız kanal";
                    String cat;
                    if (cid == null || cid.isEmpty()) {
                        cat = M3UParser.GENERAL;
                    } else {
                        cat = cats.get(cid);
                        if (cat == null) cat = "Kategori " + cid;
                    }
                    if (!b.add(main, cat, name.trim(), sid)) return true;       // kanal sınırı doldu

                    if ((++n & 255) == 0) {
                        long now = System.nanoTime();
                        if (now - lastNotify > 100000000L) {
                            lastNotify = now;
                            report(p, step, steps, label, limited.bytesRead(), total, b.count());
                        }
                    }
                }
                r.endArray();
                report(p, step, steps, label, limited.bytesRead(), total, b.count());
                return limited.wasTruncated();
            } catch (IllegalStateException e) {
                throw new IOException("Geçersiz JSON");
            }
        } finally {
            Http.release(c, track);
        }
    }

    /** Metin ya da sayı değerini metin olarak okur; başka türdeki (null, liste...) değerleri atlar. */
    private static String readString(JsonReader r) throws IOException {
        JsonToken t = r.peek();
        if (t == JsonToken.STRING || t == JsonToken.NUMBER) return r.nextString();
        r.skipValue();
        return null;
    }
}
