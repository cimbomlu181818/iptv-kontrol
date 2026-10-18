package com.example.iptvcheckerapp;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** .docx dosyasındaki metni (ve link adreslerini) okur. Android'e bağlı değildir. */
public final class DocxReader {

    private DocxReader() {}

    private static final int MAX_ENTRY_BYTES = 40 * 1024 * 1024;
    private static final Pattern REL_TARGET = Pattern.compile("Target=\"([^\"]+)\"");

    public static String read(InputStream in) throws IOException {
        StringBuilder out = new StringBuilder();
        ZipInputStream zis = new ZipInputStream(in);
        ZipEntry entry;
        while ((entry = zis.getNextEntry()) != null) {
            String name = entry.getName();
            if (name.equals("word/document.xml")) {
                out.append(xmlToText(readEntry(zis))).append('\n');
            } else if (name.equals("word/_rels/document.xml.rels")) {
                // Metne gömülü (tıklanabilir) linkler burada durur
                Matcher m = REL_TARGET.matcher(readEntry(zis));
                while (m.find()) {
                    String target = unescape(m.group(1));
                    if (target.startsWith("http")) out.append(target).append('\n');
                }
            }
        }
        return out.toString();
    }

    private static String readEntry(ZipInputStream zis) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[16 * 1024];
        int total = 0;
        int n;
        while ((n = zis.read(buf)) != -1) {
            total += n;
            if (total > MAX_ENTRY_BYTES) break;
            bos.write(buf, 0, n);
        }
        return new String(bos.toByteArray(), "UTF-8");
    }

    static String xmlToText(String xml) {
        String s = xml;
        s = s.replaceAll("</w:p>", "\n");
        s = s.replaceAll("<w:br\\s*/?>", "\n");
        s = s.replaceAll("<w:tab\\s*/?>", "\t");
        s = s.replaceAll("<[^>]+>", "");
        return unescape(s);
    }

    private static String unescape(String s) {
        s = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&apos;", "'");
        // Sayısal karakter kodları (&#38; gibi)
        Matcher m = Pattern.compile("&#(x?[0-9a-fA-F]+);").matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String code = m.group(1);
            String rep;
            try {
                int cp = code.startsWith("x") ? Integer.parseInt(code.substring(1), 16) : Integer.parseInt(code);
                rep = new String(Character.toChars(cp));
            } catch (Exception e) {
                rep = m.group();
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(rep));
        }
        m.appendTail(sb);
        return sb.toString().replace("&amp;", "&");
    }
}
