package com.example.iptvcheckerapp;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** Kullanıcının "Listeme ekle" ile biriktirdiği linkler. Uygulama kapanınca silinmez. */
public final class SavedLinks {
    private static final String PREFS = "saved_links_prefs";
    private static final String KEY = "links";

    private SavedLinks() {}

    private static LinkedHashSet<String> load(Context c) {
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String raw = p.getString(KEY, "");
        LinkedHashSet<String> set = new LinkedHashSet<>();
        for (String line : raw.split("\n")) {
            String t = line.trim();
            if (!t.isEmpty()) set.add(t);
        }
        return set;
    }

    private static void store(Context c, LinkedHashSet<String> set) {
        StringBuilder sb = new StringBuilder();
        for (String s : set) sb.append(s).append('\n');
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, sb.toString()).apply();
    }

    /** Linkleri ekler. Listede zaten olanlar tekrar eklenmez. Gerçekten eklenen sayıyı döndürür. */
    public static int add(Context c, List<String> links) {
        LinkedHashSet<String> set = load(c);
        int before = set.size();
        for (String l : links) {
            String t = l == null ? "" : l.trim();
            if (!t.isEmpty()) set.add(t);
        }
        store(c, set);
        return set.size() - before;
    }

    public static int count(Context c) {
        return load(c).size();
    }

    public static List<String> all(Context c) {
        return new ArrayList<>(load(c));
    }

    public static void clear(Context c) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply();
    }
}