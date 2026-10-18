package com.example.iptvcheckerapp;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Kullanıcının "Listeme ekle" ile biriktirdiği hesaplar ve bilgileri. Uygulama kapanınca silinmez. */
public final class SavedLinks {
    private static final String PREFS = "saved_links_prefs";
    private static final String KEY = "links";
    private static final String SEP = "\t";
    private static final String LINE = "━━━━━━━━━━━━━━━━━━━━";

    private SavedLinks() {}

    /** Kayıt: anahtar -> satır. Aynı hesap tekrar eklenirse bilgisi güncellenir, sırası değişmez. */
    private static LinkedHashMap<String, String> load(Context c) {
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String raw = p.getString(KEY, "");
        LinkedHashMap<String, String> map = new LinkedHashMap<>();
        for (String line : raw.split("\n")) {
            if (line.trim().isEmpty()) continue;
            String[] f = line.split(SEP, -1);
            map.put(f[0], line);
        }
        return map;
    }

    private static void store(Context c, LinkedHashMap<String, String> map) {
        StringBuilder sb = new StringBuilder();
        for (String line : map.values()) sb.append(line).append('\n');
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, sb.toString()).apply();
    }

    private static String clean(String s) {
        if (s == null) return "";
        return s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim();
    }

    private static String line(CheckResult r) {
        Account a = r.account;
        String max = r.maxConnections > 0 ? String.valueOf(r.maxConnections) : "Bilinmiyor";
        String exp = r.expiryText();
        if (exp == null) exp = r.apiOk ? "Süresiz" : "Bilinmiyor";
        return clean(a.dedupeKey()) + SEP
                + clean(a.baseUrl() + a.pathPrefix) + SEP
                + clean(a.username) + SEP
                + clean(a.password) + SEP
                + clean(max) + SEP
                + clean(exp) + SEP
                + clean(a.listUrl());
    }

    /** Sonuçları ekler. Listede olmayan (yeni) hesap sayısını döndürür. */
    public static int add(Context c, List<CheckResult> results) {
        LinkedHashMap<String, String> map = load(c);
        int before = map.size();
        for (CheckResult r : results) {
            String l = line(r);
            map.put(l.split(SEP, -1)[0], l);
        }
        store(c, map);
        return map.size() - before;
    }

    public static int count(Context c) {
        return load(c).size();
    }

    public static boolean isEmpty(Context c) {
        return load(c).isEmpty();
    }

    /** Dışa aktarılacak metin: her hesap ayrı bir blok. */
    public static String exportText(Context c) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : load(c).entrySet()) {
            String[] f = e.getValue().split(SEP, -1);
            sb.append(LINE).append('\n');
            if (f.length >= 7) {
                sb.append("Panel adresi: ").append(f[1]).append('\n');
                sb.append("Kullanıcı adı: ").append(f[2]).append('\n');
                sb.append("Şifre: ").append(f[3]).append('\n');
                sb.append("En fazla aynı anda kullanıcı: ").append(f[4]).append('\n');
                sb.append("Bitiş tarihi: ").append(f[5]).append('\n');
                sb.append("M3U linki: ").append(f[6]).append('\n');
            } else {
                sb.append(f[0]).append('\n');
            }
        }
        if (sb.length() > 0) sb.append(LINE).append('\n');
        return sb.toString();
    }

    /** Listede görünen tek kayıt. */
    public static final class Item {
        public final String key;
        public final boolean hasInfo;
        public final String panel, user, pass, max, expiry, m3u;

        Item(String line) {
            String[] f = line.split(SEP, -1);
            key = f[0];
            hasInfo = f.length >= 7;
            panel = hasInfo ? f[1] : "";
            user = hasInfo ? f[2] : "";
            pass = hasInfo ? f[3] : "";
            max = hasInfo ? f[4] : "";
            expiry = hasInfo ? f[5] : "";
            m3u = hasInfo ? f[6] : "";
        }
    }

    public static List<Item> items(Context c) {
        List<Item> out = new ArrayList<>();
        for (String line : load(c).values()) out.add(new Item(line));
        return out;
    }

    /** Verilen anahtarlı kayıtları listeden siler. */
    public static void remove(Context c, Collection<String> keys) {
        LinkedHashMap<String, String> map = load(c);
        for (String k : keys) map.remove(k);
        store(c, map);
    }

    public static void clear(Context c) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply();
    }
}