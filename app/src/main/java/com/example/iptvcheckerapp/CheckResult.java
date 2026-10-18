package com.example.iptvcheckerapp;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/** Bir hesabın kontrol sonucu. Android'e bağlı değildir. */
public class CheckResult {

    public enum State { FULL, PARTIAL, EXPIRED, DEAD }

    public final Account account;

    // API (player_api.php) kontrolü
    public boolean apiReachable;     // Sunucu HTTP yanıtı verdi mi
    public boolean apiOk;            // Hesap geçerli, aktif ve süresi dolmamış
    public String apiMessage = "";   // Kısa açıklama
    public String accountStatus;     // API'nin söylediği durum ("Active", "Expired" ...)
    public boolean expired;          // Hesabın süresi dolmuş ya da devre dışı
    public boolean hasExpiry;        // Bitiş tarihi biliniyor mu
    public long expiresAtMs;         // Bitiş tarihi (ms)
    public int maxConnections = -1;
    public int activeConnections = -1;
    public boolean trial;

    // M3U kontrolü
    public boolean m3uChecked;
    public boolean m3uOk;
    public String m3uMessage = "";
    public int channelCount;
    public boolean channelCountPartial;

    public boolean hostUnreachable;  // Sunucuya hiç bağlanılamadı
    public boolean authFailed;       // Panel "kullanıcı adı/şifre hatalı" dedi (M3U indirilmez)
    public long responseMs;

    public CheckResult(Account account) {
        this.account = account;
    }

    public State getState() {
        boolean m3uGood = m3uChecked && m3uOk;
        if (apiOk && (!m3uChecked || m3uOk)) return State.FULL;
        if (apiOk || m3uGood) return State.PARTIAL;
        if (apiReachable && expired) return State.EXPIRED;
        return State.DEAD;
    }

    public boolean isUsable() {
        State s = getState();
        return s == State.FULL || s == State.PARTIAL;
    }

    public String stateLabel() {
        switch (getState()) {
            case FULL: return "TAM AKTİF";
            case PARTIAL: return "KISMİ";
            case EXPIRED: return "SÜRESİ DOLMUŞ";
            default: return "PASİF";
        }
    }

    public String expiryText() {
        if (!hasExpiry) return null;
        return new SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(new Date(expiresAtMs));
    }

    /** Bitiş yılı; tarih yoksa null. */
    public String expiryYear() {
        if (!hasExpiry) return null;
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(expiresAtMs);
        return String.valueOf(c.get(Calendar.YEAR));
    }

    /** Kalan gün sayısı (tarih yoksa Integer.MAX_VALUE). */
    public long daysLeft() {
        if (!hasExpiry) return Long.MAX_VALUE;
        return (expiresAtMs - System.currentTimeMillis()) / 86400000L;
    }

    /** Karttaki özet satırı: bağlantı, tarih, kanal sayısı, deneme. */
    public String infoLine() {
        StringBuilder sb = new StringBuilder();
        if (maxConnections > 0) {
            int act = activeConnections >= 0 ? activeConnections : 0;
            sb.append("👥 ").append(act).append("/").append(maxConnections);
        }
        if (apiReachable && apiOk || hasExpiry) {
            if (sb.length() > 0) sb.append("  •  ");
            if (hasExpiry) {
                long d = daysLeft();
                sb.append("📅 ").append(expiryText());
                if (d >= 0) sb.append(" (").append(d).append(" gün)");
            } else {
                sb.append("📅 Süresiz");
            }
        }
        if (m3uChecked && m3uOk && channelCount > 0) {
            if (sb.length() > 0) sb.append("  •  ");
            sb.append("📺 ").append(channelCount).append(channelCountPartial ? "+" : "").append(" kanal");
        }
        if (trial) {
            if (sb.length() > 0) sb.append("  •  ");
            sb.append("🧪 Deneme");
        }
        return sb.toString();
    }

    /** "API: ... | M3U: ..." açıklaması. */
    public String detailLine() {
        StringBuilder sb = new StringBuilder();
        sb.append("API: ").append(apiOk ? "✅ " : "❌ ");
        sb.append(apiMessage == null || apiMessage.isEmpty() ? (apiOk ? "Aktif" : "-") : apiMessage);
        sb.append("\nM3U: ");
        if (!m3uChecked) {
            if (hostUnreachable) {
                sb.append("❌ ").append(m3uMessage.isEmpty() ? "Sunucuya ulaşılamadı" : m3uMessage);
            } else if (authFailed) {
                sb.append("atlandı (kimlik doğrulama başarısız)");
            } else {
                sb.append("kontrol edilmedi");
            }
        } else {
            sb.append(m3uOk ? "✅ " : "❌ ");
            sb.append(m3uMessage == null || m3uMessage.isEmpty() ? (m3uOk ? "Çalışıyor" : "-") : m3uMessage);
        }
        return sb.toString();
    }
}
