package com.example.iptvcheckerapp;

/** Metin yardımcıları. Android'e bağlı değildir. */
public final class TextUtil {
    private TextUtil() {}

    /**
     * Arama için metni sadeleştirir: büyük/küçük harf farkını ve Türkçe karakter farkını yok sayar.
     * Örnek: "İSTANBUL", "istanbul" ve "ıstanbul" aynı sonucu verir; "TÜRKÇE" ile "turkce" eşleşir.
     * (Java'nın toLowerCase işlemi "İ" harfini "i" + birleşik nokta yaptığı için eski arama
     * "İSTANBUL" gibi adları "istanbul" yazınca bulamıyordu.)
     */
    public static String norm(String s) {
        if (s == null) return "";
        int n = s.length();
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            if (c < 128) {
                // Hızlı yol: düz ASCII
                if (c >= 'A' && c <= 'Z') c = (char) (c + 32);
                sb.append(c);
                continue;
            }
            char l = Character.toLowerCase(c);
            switch (l) {
                case '\u0131': case '\u00ee': case '\u00ed': case '\u00ec': case '\u00ef': l = 'i'; break;
                case '\u00e7': case '\u0107': case '\u010d': l = 'c'; break;
                case '\u011f': l = 'g'; break;
                case '\u00f6': case '\u00f3': case '\u00f4': case '\u00f2': case '\u00f5': l = 'o'; break;
                case '\u015f': case '\u0161': l = 's'; break;
                case '\u00fc': case '\u00fb': case '\u00fa': case '\u00f9': l = 'u'; break;
                case '\u00e2': case '\u00e1': case '\u00e0': case '\u00e4': case '\u00e3': l = 'a'; break;
                case '\u00e9': case '\u00e8': case '\u00ea': case '\u00eb': l = 'e'; break;
                case '\u00f1': l = 'n'; break;
                case '\u0307': continue;     // birleşik nokta: atla
                default: break;
            }
            sb.append(l);
        }
        return sb.toString();
    }
}
