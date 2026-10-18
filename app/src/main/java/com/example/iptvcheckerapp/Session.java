package com.example.iptvcheckerapp;

import java.util.ArrayList;
import java.util.List;

/** Ekranlar arasında büyük listeleri taşımak için basit bellek içi tutucu. */
public final class Session {
    private Session() {}

    /** Kontrol edilecek hesaplar (seçili paneller). */
    public static List<Account> accounts = new ArrayList<>();
    public static boolean checkM3u = true;
    public static int threads = 8;

    /** Kanal listesi için denenecek hesaplar. */
    public static List<Account> channelAccounts = new ArrayList<>();
}
