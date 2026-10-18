package com.example.iptvcheckerapp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Bir metnin içinden IPTV (Xtream Codes) linklerini bulur. Android'e bağlı değildir.
 *
 * Eski sürüm tek bir büyük düzenli ifade (regex) kullanıyordu ve boşluksuz uzun metinlerde
 * karesel (O(n²)) yavaşlıyordu. Bu sürüm metni tek geçişte tarar: önce "get.php?" ya da
 * "player_api.php?" geçen yeri bulur, sonra yalnızca en fazla MAX_BACK karakter geriye bakarak
 * linkin başlangıcını çıkarır. Süre metin uzunluğuyla doğru orantılıdır (doğrusal).
 */
public final class LinkParser {

    private LinkParser() {}

    private static final int MAX_BACK = 512;                 // linkin başlangıcı için geriye bakma sınırı
    private static final int MAX_QUERY = 2048;               // "?" sonrası en fazla bu kadar karakter okunur
    private static final String GET = "get.php";
    private static final String API = "player_api.php";
    private static final Pattern INVISIBLE = Pattern.compile("[\\u200B\\u200C\\u200D\\u2060\\uFEFF]");

    /** Metindeki tüm geçerli hesapları, tekrarsız ve bulunma sırasıyla döndürür. */
    public static List<Account> extract(String text) {
        Map<String, Account> found = new LinkedHashMap<>();
        if (text == null || text.isEmpty()) return new ArrayList<>();
        // Görünmez karakterleri (Word'den gelebilir) temizle
        String clean = INVISIBLE.matcher(text).replaceAll("");
        int n = clean.length();
        int lastEnd = 0;     // en son kabul edilen linkin bittiği yer
        int runEnd = 0;      // '?' işaretini içeren boşluksuz parçanın bittiği yer (önbellek)

        int q = clean.indexOf('?');
        while (q >= 0) {
            if (q >= lastEnd) {
                int fileStart = fileStartBefore(clean, q);
                if (fileStart > 0 && clean.charAt(fileStart - 1) == '/') {
                    if (q >= runEnd) {
                        runEnd = q + 1;
                        while (runEnd < n && !isStop(clean.charAt(runEnd))) runEnd++;
                    }
                    int end = Math.min(runEnd, q + 1 + MAX_QUERY);
                    if (end > q + 1) {
                        int start = findStart(clean, fileStart, Math.max(lastEnd, fileStart - MAX_BACK));
                        if (start >= 0) {
                            Account a = parse(clean.substring(start, end));
                            if (a != null) {
                                if (!found.containsKey(a.dedupeKey())) found.put(a.dedupeKey(), a);
                                lastEnd = end;
                            }
                        }
                    }
                }
            }
            q = clean.indexOf('?', q + 1);
        }
        return new ArrayList<>(found.values());
    }

    /** '?' işaretinden hemen önce get.php / player_api.php varsa dosya adının başlangıcını döndürür, yoksa -1. */
    private static int fileStartBefore(String s, int q) {
        if (q >= GET.length() && s.regionMatches(true, q - GET.length(), GET, 0, GET.length())) {
            return q - GET.length();
        }
        if (q >= API.length() && s.regionMatches(true, q - API.length(), API, 0, API.length())) {
            return q - API.length();
        }
        return -1;
    }

    /** Linkin ilk karakterinin yerini bulur (http(s):// varsa onunla başlar). Bulamazsa -1. */
    private static int findStart(String s, int fileStart, int limit) {
        // Boşluk/tırnak gibi ayırıcıya kadar (en fazla limit'e kadar) geri git
        int tok = fileStart;
        while (tok > limit && !isStop(s.charAt(tok - 1))) tok--;
        if (tok >= fileStart) return -1;

        // Varsa, dosya adına en yakın "://" den sonrası host bölgesidir
        int p = -1;
        for (int x = fileStart - 3; x >= tok; x--) {      // en fazla MAX_BACK karakter geriye bakar
            if (s.charAt(x) == ':' && s.charAt(x + 1) == '/' && s.charAt(x + 2) == '/') {
                p = x;
                break;
            }
        }
        int region = (p >= 0) ? p + 3 : tok;

        // host[:port] kısmı, bölgedeki ilk '/' işaretinden hemen öncesidir
        int slash = s.indexOf('/', region);
        if (slash < 0 || slash >= fileStart) return -1;
        int i = slash;
        int j = i;
        while (j > region && isDigit(s.charAt(j - 1))) j--;
        if (j < i && j > region && s.charAt(j - 1) == ':') i = j - 1;   // port kısmını atla
        int h = i;
        while (h > region && isHostChar(s.charAt(h - 1))) h--;
        while (h < i && !isAlnum(s.charAt(h))) h++;                      // host harf/rakamla başlamalı
        if (h >= i) return -1;

        // host'un hemen önünde "http://" ya da "https://" varsa onu da al
        if (h >= 3 && s.startsWith("://", h - 3)) {
            int pp = h - 3;
            int k = pp;
            while (k > tok && isAsciiLetter(s.charAt(k - 1))) k--;
            if (pp - k >= 5 && s.regionMatches(true, pp - 5, "https", 0, 5)) return pp - 5;
            if (pp - k >= 4 && s.regionMatches(true, pp - 4, "http", 0, 4)) return pp - 4;
        }
        return h;
    }

    private static boolean isStop(char c) {
        return Character.isWhitespace(c) || c == '\u00a0' || c == '"' || c == '\'' || c == '<' || c == '>';
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isAsciiLetter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    private static boolean isAlnum(char c) {
        return isDigit(c) || isAsciiLetter(c);
    }

    private static boolean isHostChar(char c) {
        return isAlnum(c) || c == '.' || c == '_' || c == '-';
    }

    /** Tek bir linki çözer; geçersizse null döner. */
    public static Account parse(String raw) {
        if (raw == null) return null;
        String u = raw.trim().replace("&amp;", "&").replace("\\/", "/");
        // Sondaki noktalama işaretlerini at
        while (!u.isEmpty() && ".,;:)]}".indexOf(u.charAt(u.length() - 1)) >= 0) {
            u = u.substring(0, u.length() - 1);
        }
        if (u.length() < 10) return null;

        String scheme = "http";
        String rest = u;
        boolean hasScheme = false;
        int sep = u.indexOf("://");
        if (sep > 0) {
            scheme = u.substring(0, sep).toLowerCase(Locale.ROOT);
            rest = u.substring(sep + 3);
            hasScheme = true;
        }
        if (!scheme.equals("http") && !scheme.equals("https")) return null;

        int q = rest.indexOf('?');
        if (q < 0) return null;
        String beforeQuery = rest.substring(0, q);
        String query = rest.substring(q + 1);
        int hash = query.indexOf('#');
        if (hash >= 0) query = query.substring(0, hash);

        int slash = beforeQuery.indexOf('/');
        String authority = slash < 0 ? beforeQuery : beforeQuery.substring(0, slash);
        String path = slash < 0 ? "" : beforeQuery.substring(slash);

        int lastSlash = path.lastIndexOf('/');
        if (lastSlash < 0) return null;
        String file = path.substring(lastSlash + 1).toLowerCase(Locale.ROOT);
        if (!file.equals("get.php") && !file.equals("player_api.php")) return null;
        String prefix = path.substring(0, lastSlash);

        String host = authority;
        int port = -1;
        int colon = authority.lastIndexOf(':');
        if (colon >= 0) {
            host = authority.substring(0, colon);
            try {
                port = Integer.parseInt(authority.substring(colon + 1));
            } catch (NumberFormatException e) {
                return null;
            }
            if (port <= 0 || port > 65535) return null;
        }
        if (host.isEmpty()) return null;

        String user = null;
        String pass = null;
        for (String part : query.split("&")) {
            int eq = part.indexOf('=');
            if (eq <= 0) continue;
            String key = part.substring(0, eq).toLowerCase(Locale.ROOT);
            String val = part.substring(eq + 1);
            if (key.equals("username")) user = val;
            else if (key.equals("password")) pass = val;
        }
        if (user == null || user.isEmpty() || pass == null || pass.isEmpty()) return null;

        String original = hasScheme ? u : "http://" + u;
        return new Account(original, scheme, host, port, prefix, user, pass);
    }
}
