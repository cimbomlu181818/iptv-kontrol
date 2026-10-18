package com.example.iptvcheckerapp;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;

/** Küçük HTTP yardımcıları (HttpURLConnection). Android'e bağlı değildir. */
public final class Http {

    private Http() {}

    public static final String USER_AGENT = "Mozilla/5.0 (Linux; Android 13) IPTVKontrol/2.0";
    private static final Charset UTF8 = Charset.forName("UTF-8");

    /**
     * Bağlantıyı açar ve yönlendirmeleri (http'den https'e olanlar dahil) en fazla 5 kez takip eder.
     * Dönen bağlantının yanıt kodu okunmuştur; işi bitince disconnect() çağrılmalıdır.
     */
    public static HttpURLConnection open(String url, int connectMs, int readMs) throws IOException {
        String current = url;
        for (int redirects = 0; ; redirects++) {
            URL u = new URL(current);
            HttpURLConnection c = (HttpURLConnection) u.openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(connectMs);
            c.setReadTimeout(readMs);
            c.setRequestProperty("User-Agent", USER_AGENT);
            c.setRequestProperty("Accept", "*/*");
            int code;
            try {
                code = c.getResponseCode();
            } catch (IOException e) {
                c.disconnect();
                throw e;
            }
            boolean redirect = code == 301 || code == 302 || code == 303 || code == 307 || code == 308;
            if (redirect && redirects < 5) {
                String loc = c.getHeaderField("Location");
                c.disconnect();
                if (loc == null || loc.isEmpty()) {
                    throw new IOException("Yönlendirme adresi boş");
                }
                current = new URL(u, loc).toString();
                continue;
            }
            return c;
        }
    }

    /** En fazla maxBytes kadar okur, UTF-8 metin olarak döndürür. */
    public static String readText(InputStream in, int maxBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16 * 1024];
        int total = 0;
        int n;
        while (total < maxBytes && (n = in.read(buf, 0, Math.min(buf.length, maxBytes - total))) != -1) {
            out.write(buf, 0, n);
            total += n;
        }
        String s = new String(out.toByteArray(), UTF8);
        if (!s.isEmpty() && s.charAt(0) == '\uFEFF') s = s.substring(1);
        return s;
    }

    /** Belirli bir bayttan sonra okumayı bitiren (EOF gibi davranan) akış. */
    public static class LimitedInputStream extends InputStream {
        private final InputStream in;
        private long remaining;
        private boolean truncated = false;

        public LimitedInputStream(InputStream in, long limit) {
            this.in = in;
            this.remaining = limit;
        }

        public boolean wasTruncated() {
            return truncated;
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0) {
                truncated = true;
                return -1;
            }
            int b = in.read();
            if (b >= 0) remaining--;
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (remaining <= 0) {
                truncated = true;
                return -1;
            }
            int n = in.read(b, off, (int) Math.min(len, remaining));
            if (n > 0) remaining -= n;
            return n;
        }

        @Override
        public void close() throws IOException {
            in.close();
        }
    }
}
