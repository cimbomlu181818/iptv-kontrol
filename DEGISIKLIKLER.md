# IPTV Link Kontrol 2.0 - Neler değişti?

## Düzeltilen hatalar
- Her link kontrolünden sonra 10 saniye bekleniyordu (sebepsiz). Kaldırıldı.
- Linkler tek tek kontrol ediliyordu. Artık aynı anda 1-20 link kontrol ediliyor (ana ekrandan ayarlanır).
- Sonuç ekranında her sonuç listeye iki kez ekleniyordu ("2/1 test edildi" gibi). Düzeltildi.
- Word (.docx) okuyucu metni iki kez okuyordu. Düzeltildi; Word içindeki tıklanabilir linkler de okunuyor.
- API kontrolü her zaman http:// ile yapılıyordu. Artık linkin kendi adresi (https dahil) kullanılıyor.
- "Aktif" sayılan hesabın süresi dolmuş ya da kullanıcı/şifre yanlış olabiliyordu. Artık hesap durumu, bitiş tarihi ve kimlik doğrulaması kontrol ediliyor (Tam aktif / Kısmi / Süresi dolmuş / Pasif).
- Sunucu adında "film" geçince tüm kanallar FİLMLER grubuna düşüyordu. Artık sadece linkin yoluna bakılıyor.
- Kanal adındaki virgül kanal adını bozuyordu. Düzeltildi.
- Aynı sunucuda port farklıysa paneller birleşiyordu. Artık host:port ayrı panel.
- Çok büyük M3U dosyaları tüm belleğe yükleniyordu. Artık akış halinde okunuyor.
- Kapalı bir panelin her linki için uzun süre bekleniyordu. Panel 2 kez üst üste açılamazsa kalan linkleri anında pasif sayılıyor.
- Çok link olunca ekranlar arası liste taşıma (Intent) sınırı aşılabiliyordu. Artık bellekte taşınıyor.
- Geri tuşu uygulamayı kapatıp yeniden başlatıyordu. Artık normal geri gidiyor.
- Android 7'de çökebilen String.join kullanımı kaldırıldı.

## Yeni özellikler
- Tamamen yeni arayüz (dış kütüphane yok: OkHttp, Jsoup, AppCompat, Material kaldırıldı).
- Kontrol sırasında Durdur / Yeniden / "Pasifleri tekrar dene".
- Filtre: durum, panel, maksimum bağlantı, bitiş yılı, deneme hesabı, arama kutusu.
- Sıralama: bitiş tarihi, kanal sayısı.
- Görünen linkleri tek dokunuşla kopyala veya paylaş.
- Sonuç kartından o hesabın kanal listesini doğrudan aç.
- Yazdığın linkler ve ayarlar uygulama kapanınca kaybolmaz.
- .txt, .m3u ve .docx dosyası okuma.
