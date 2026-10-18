package com.example.iptvcheckerapp;

import android.util.Log;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * DOCX dosyalarını okumak için basit bir sınıf
 * DOCX aslında bir ZIP arşividir ve içinde document.xml vardır
 */
public class WordReader {

    private static final String TAG = "WordReader";

    /**
     * DOCX dosyasından metin içeriği çıkarır
     * @param inputStream DOCX dosyasının input stream'i
     * @return Dosyanın metin içeriği
     */
    public static String readDocxFile(InputStream inputStream) {
        StringBuilder text = new StringBuilder();

        try {
            ZipInputStream zipInputStream = new ZipInputStream(inputStream);
            ZipEntry entry;

            // ZIP içindeki dosyaları tara
            while ((entry = zipInputStream.getNextEntry()) != null) {
                String entryName = entry.getName();
                Log.d(TAG, "ZIP entry bulundu: " + entryName);

                // document.xml dosyasını bul
                if (entryName.equals("word/document.xml")) {
                    Log.d(TAG, "document.xml bulundu, okunuyor...");

                    // XML içeriğini oku
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    byte[] buffer = new byte[1024];
                    int len;

                    while ((len = zipInputStream.read(buffer)) > 0) {
                        baos.write(buffer, 0, len);
                    }

                    String xmlContent = baos.toString("UTF-8");
                    Log.d(TAG, "XML uzunluğu: " + xmlContent.length());

                    // XML'den metni çıkar
                    text.append(extractTextFromXml(xmlContent));
                    // XML'den metni çıkar
                    String extractedText = extractTextFromXml(xmlContent);
// "M3U Link:" formatını düzelt (boşlukları normalize et)
                    extractedText = extractedText.replaceAll("M3U\\s+Link\\s*:", "M3U Link: ");
                    text.append(extractedText);
                }

                zipInputStream.closeEntry();
            }

            zipInputStream.close();

        } catch (Exception e) {
            Log.e(TAG, "DOCX okuma hatası: " + e.getMessage(), e);
            throw new RuntimeException("Word dosyası okunamadı: " + e.getMessage());
        }

        String result = text.toString();
        Log.d(TAG, "Toplam çıkarılan metin uzunluğu: " + result.length());
        return result;
    }

    /**
     * Word XML'inden metin içeriğini çıkarır
     * XML tag'lerini temizler ve sadece metni bırakır
     */
    private static String extractTextFromXml(String xml) {
        StringBuilder text = new StringBuilder();

        try {
            // <w:t> tag'leri arasındaki metinleri bul
            // Word XML'de metin <w:t>metin</w:t> şeklinde saklanır

            String[] parts = xml.split("<w:t");

            for (int i = 1; i < parts.length; i++) {
                String part = parts[i];

                // > ile başlayan ve </w:t> ile biten kısmı al
                int startIndex = part.indexOf('>');
                int endIndex = part.indexOf("</w:t>");

                if (startIndex != -1 && endIndex != -1 && endIndex > startIndex) {
                    String textContent = part.substring(startIndex + 1, endIndex);

                    // XML escape karakterlerini düzelt
                    textContent = unescapeXml(textContent);

                    text.append(textContent).append(" ");
                }
            }

            // Paragraf sonlarını korumak için <w:p> tag'lerini kontrol et
            String result = text.toString();

            // Fazla boşlukları temizle
            result = result.replaceAll("\\s+", " ").trim();

            return result;

        } catch (Exception e) {
            Log.e(TAG, "XML parse hatası: " + e.getMessage(), e);
            return "";
        }
    }

    /**
     * XML escape karakterlerini düzeltir
     */
    private static String unescapeXml(String text) {
        return text
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&amp;", "&");
    }
}