package com.example.iptvcheckerapp;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Ana ekran: linkleri ekle, panelleri seç, kontrolü başlat. */
public class MainActivity extends Activity {

    private static final int REQ_FILE = 101;
    private static final String PREFS = "iptv_prefs";
    private static final int MAX_PANELS_SHOWN = 300;
    private static final int MAX_SAVED_CHARS = 1500000;

    private EditText etLinks;
    private TextView tvFile, tvFound, tvThreads;
    private LinearLayout panelBox;
    private CheckBox cbAll, cbM3u;
    private SeekBar sbThreads;
    private Button btnStart, btnChannels;

    private final List<Account> fileAccounts = new ArrayList<>();
    private List<Account> allAccounts = new ArrayList<>();
    private final LinkedHashMap<String, Integer> panelCounts = new LinkedHashMap<>();
    private final Set<String> deselected = new HashSet<>();
    private String lastPanelSignature = "";
    private boolean updatingUi = false;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    // Link ayrıştırma ayrı bir iş parçacığında yapılır (büyük yapıştırmalar ekranı dondurmasın)
    private final ExecutorService parser = Executors.newSingleThreadExecutor();
    private int parseSeq = 0;
    private final Runnable parseTask = new Runnable() {
        @Override
        public void run() {
            reparse();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        restorePrefs();
        reparse();
    }

    // ------------------------------------------------------------------ arayüz

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Ui.BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 16);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = Ui.text(this, "IPTV Link Kontrol", 24, Ui.TEXT, true);
        root.addView(title);
        TextView sub = Ui.text(this, "Linklerin çalışıp çalışmadığını, bitiş tarihini ve kanal sayısını gösterir.",
                13, Ui.SUB, false);
        root.addView(sub, Ui.lp(this, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 2, 0, 14));

        // ---- 1. Linkler
        LinearLayout c1 = Ui.card(this);
        c1.addView(Ui.text(this, "1. Linkleri ekle", 16, Ui.TEXT, true));

        etLinks = new EditText(this);
        etLinks.setHint("Linkleri buraya yapıştır (her satıra bir link ya da hepsi birden)…");
        etLinks.setHintTextColor(Ui.GRAY);
        etLinks.setTextColor(Ui.TEXT);
        etLinks.setTextSize(13);
        etLinks.setMinLines(5);
        etLinks.setMaxLines(9);
        etLinks.setGravity(Gravity.TOP | Gravity.START);
        etLinks.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | InputType.TYPE_TEXT_VARIATION_URI);
        etLinks.setBackground(Ui.rounded(this, 0xFFF8F9FC, Ui.LINE, 10));
        int ep = Ui.dp(this, 10);
        etLinks.setPadding(ep, ep, ep, ep);
        etLinks.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent ev) {
                if (v.canScrollVertically(1) || v.canScrollVertically(-1)) {
                    v.getParent().requestDisallowInterceptTouchEvent(true);
                    if (ev.getAction() == MotionEvent.ACTION_UP) {
                        v.getParent().requestDisallowInterceptTouchEvent(false);
                    }
                }
                return false;
            }
        });
        etLinks.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {}

            @Override
            public void afterTextChanged(Editable s) {
                handler.removeCallbacks(parseTask);
                handler.postDelayed(parseTask, 400);
            }
        });
        c1.addView(etLinks, Ui.lp(this, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 8, 0, 8));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Button bPaste = Ui.button(this, "📋 Yapıştır", Ui.WHITE, Ui.TEXT);
        Button bFile = Ui.button(this, "📁 Dosya seç", Ui.WHITE, Ui.TEXT);
        Button bClear = Ui.button(this, "🗑 Temizle", Ui.WHITE, Ui.RED);
        row.addView(bPaste, Ui.weight(this, 0, 4));
        row.addView(bFile, Ui.weight(this, 4, 4));
        row.addView(bClear, Ui.weight(this, 4, 0));
        c1.addView(row);

        tvFile = Ui.text(this, "", 12, Ui.SUB, false);
        tvFile.setVisibility(View.GONE);
        c1.addView(tvFile, Ui.lp(this, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 8, 0, 0));
        tvFound = Ui.text(this, "", 14, Ui.PRIMARY, true);
        c1.addView(tvFound, Ui.lp(this, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 8, 0, 0));
        root.addView(c1, Ui.lp(this, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, 12));

        // ---- 2. Paneller
        LinearLayout c2 = Ui.card(this);
        c2.addView(Ui.text(this, "2. Panelleri seç", 16, Ui.TEXT, true));
        cbAll = new CheckBox(this);
        cbAll.setText("Tüm paneller");
        cbAll.setTextColor(Ui.TEXT);
        cbAll.setChecked(true);
        c2.addView(cbAll);
        panelBox = new LinearLayout(this);
        panelBox.setOrientation(LinearLayout.VERTICAL);
        c2.addView(panelBox);
        root.addView(c2, Ui.lp(this, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, 12));

        // ---- 3. Ayarlar
        LinearLayout c3 = Ui.card(this);
        c3.addView(Ui.text(this, "3. Ayarlar", 16, Ui.TEXT, true));
        tvThreads = Ui.text(this, "", 14, Ui.TEXT, false);
        c3.addView(tvThreads, Ui.lp(this, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 8, 0, 0));
        sbThreads = new SeekBar(this);
        sbThreads.setMax(19);          // 1 - 20 arası
        sbThreads.setProgress(7);      // varsayılan 8
        sbThreads.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean user) {
                updateThreadsText();
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {}

            @Override
            public void onStopTrackingTouch(SeekBar s) {}
        });
        c3.addView(sbThreads);
        TextView hint = Ui.text(this, "Sayı büyüdükçe kontrol hızlanır. İnternet yavaşsa 4-8 arası dene.", 12, Ui.SUB, false);
        c3.addView(hint);
        cbM3u = new CheckBox(this);
        cbM3u.setText("M3U listesini de kontrol et (kanal sayısını gösterir; her hesap için liste indirilir, mobil veride kota harcar)");
        cbM3u.setTextColor(Ui.TEXT);
        cbM3u.setChecked(true);
        c3.addView(cbM3u, Ui.lp(this, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 8, 0, 0));
        root.addView(c3, Ui.lp(this, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, 14));

        btnStart = Ui.button(this, "▶  Kontrolü Başlat", Ui.PRIMARY, Ui.WHITE);
        btnStart.setTextSize(16);
        root.addView(btnStart, Ui.lp(this, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, 8));
        btnChannels = Ui.button(this, "📺  Kanal Listesine Bak", Ui.WHITE, Ui.PRIMARY);
        root.addView(btnChannels, Ui.lp(this, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, 8));

        TextView format = Ui.text(this, "Desteklenen link biçimi:\nhttp://adres:port/get.php?username=…&password=…\nDosya olarak .txt, .m3u ve .docx okunur.",
                12, Ui.SUB, false);
        root.addView(format, Ui.lp(this, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 6, 0, 0));

        setContentView(scroll);

        // ---- Düğme olayları
        bPaste.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pasteFromClipboard();
            }
        });
        bFile.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickFile();
            }
        });
        bClear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                etLinks.setText("");
                fileAccounts.clear();
                tvFile.setVisibility(View.GONE);
                deselected.clear();
                reparse();
            }
        });
        cbAll.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                if (updatingUi) return;
                if (checked) deselected.clear();
                else deselected.addAll(panelCounts.keySet());
                lastPanelSignature = "";
                rebuildPanels();
                updateInfo();
            }
        });
        btnStart.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startCheck();
            }
        });
        btnChannels.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openChannels();
            }
        });
        updateThreadsText();
    }

    private void updateThreadsText() {
        tvThreads.setText("Aynı anda kontrol edilecek link sayısı: " + (sbThreads.getProgress() + 1));
    }

    // ------------------------------------------------------------------ linkler ve paneller

    private void reparse() {
        final String text = etLinks.getText().toString();
        final int seq = ++parseSeq;
        try {
            parser.execute(new Runnable() {
                @Override
                public void run() {
                    final List<Account> manual = LinkParser.extract(text);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (seq != parseSeq) return;     // daha yeni bir ayrıştırma başladı
                            applyParsed(manual);
                        }
                    });
                }
            });
        } catch (RuntimeException e) {
            // Ekran kapanmış, yapılacak bir şey yok
        }
    }

    private void applyParsed(List<Account> manual) {
        LinkedHashMap<String, Account> merged = new LinkedHashMap<>();
        for (Account a : fileAccounts) merged.put(a.dedupeKey(), a);
        for (Account a : manual) {
            if (!merged.containsKey(a.dedupeKey())) merged.put(a.dedupeKey(), a);
        }
        allAccounts = new ArrayList<>(merged.values());

        panelCounts.clear();
        for (Account a : allAccounts) {
            String key = a.panelKey();
            Integer n = panelCounts.get(key);
            panelCounts.put(key, n == null ? 1 : n + 1);
        }
        deselected.retainAll(panelCounts.keySet());
        rebuildPanels();
        updateInfo();
    }

    private void rebuildPanels() {
        StringBuilder sig = new StringBuilder();
        for (Map.Entry<String, Integer> e : panelCounts.entrySet()) {
            sig.append(e.getKey()).append('=').append(e.getValue())
                    .append(deselected.contains(e.getKey()) ? "-" : "+").append(';');
        }
        if (sig.toString().equals(lastPanelSignature)) return;
        lastPanelSignature = sig.toString();

        updatingUi = true;
        panelBox.removeAllViews();
        if (panelCounts.isEmpty()) {
            TextView empty = Ui.text(this, "Henüz panel yok. Önce link ekle.", 13, Ui.SUB, false);
            panelBox.addView(empty);
        }
        int shown = 0;
        for (Map.Entry<String, Integer> e : panelCounts.entrySet()) {
            if (shown >= MAX_PANELS_SHOWN) {
                TextView more = Ui.text(this, "… ve " + (panelCounts.size() - shown)
                        + " panel daha (seçili durumdakiler dahil edilir)", 12, Ui.SUB, false);
                panelBox.addView(more);
                break;
            }
            final String key = e.getKey();
            CheckBox cb = new CheckBox(this);
            cb.setText(key + "   (" + e.getValue() + " link)");
            cb.setTextColor(Ui.TEXT);
            cb.setTextSize(14);
            cb.setChecked(!deselected.contains(key));
            cb.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton b, boolean checked) {
                    if (updatingUi) return;
                    if (checked) deselected.remove(key);
                    else deselected.add(key);
                    lastPanelSignature = "";
                    updateInfo();
                    lastPanelSignature = currentSignature();
                }
            });
            panelBox.addView(cb);
            shown++;
        }
        updatingUi = false;
    }

    private String currentSignature() {
        StringBuilder sig = new StringBuilder();
        for (Map.Entry<String, Integer> e : panelCounts.entrySet()) {
            sig.append(e.getKey()).append('=').append(e.getValue())
                    .append(deselected.contains(e.getKey()) ? "-" : "+").append(';');
        }
        return sig.toString();
    }

    private List<Account> selectedAccounts() {
        List<Account> list = new ArrayList<>();
        for (Account a : allAccounts) {
            if (!deselected.contains(a.panelKey())) list.add(a);
        }
        return list;
    }

    private void updateInfo() {
        int selected = selectedAccounts().size();
        tvFound.setText(allAccounts.size() + " link  •  " + panelCounts.size() + " panel  •  seçili: " + selected + " link");

        updatingUi = true;
        cbAll.setChecked(!panelCounts.isEmpty() && deselected.isEmpty());
        cbAll.setText("Tüm paneller (" + panelCounts.size() + ")");
        updatingUi = false;

        boolean enabled = selected > 0;
        btnStart.setEnabled(enabled);
        btnChannels.setEnabled(enabled);
        btnStart.setAlpha(enabled ? 1f : 0.45f);
        btnChannels.setAlpha(enabled ? 1f : 0.45f);
    }

    // ------------------------------------------------------------------ pano ve dosya

    private void pasteFromClipboard() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null || !cm.hasPrimaryClip() || cm.getPrimaryClip() == null
                || cm.getPrimaryClip().getItemCount() == 0) {
            Toast.makeText(this, "Panoda metin yok", Toast.LENGTH_SHORT).show();
            return;
        }
        CharSequence t = cm.getPrimaryClip().getItemAt(0).coerceToText(this);
        if (t == null || t.length() == 0) {
            Toast.makeText(this, "Panoda metin yok", Toast.LENGTH_SHORT).show();
            return;
        }
        String current = etLinks.getText().toString();
        etLinks.setText(current.isEmpty() ? t.toString() : current + "\n" + t);
    }

    private void pickFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        try {
            startActivityForResult(i, REQ_FILE);
        } catch (Exception e) {
            Toast.makeText(this, "Dosya seçici açılamadı", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_FILE || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        final String name = fileNameOf(uri);
        tvFile.setVisibility(View.VISIBLE);
        tvFile.setText("📄 " + name + " okunuyor…");
        io.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    String lower = name.toLowerCase(Locale.ROOT);
                    if (lower.endsWith(".doc")) {
                        throw new Exception("Eski .doc biçimi okunamıyor. Dosyayı .docx olarak kaydedip tekrar dene.");
                    }
                    InputStream in = getContentResolver().openInputStream(uri);
                    if (in == null) throw new Exception("Dosya açılamadı");
                    String text;
                    try {
                        text = lower.endsWith(".docx") ? DocxReader.read(in) : Http.readText(in, 30 * 1024 * 1024);
                    } finally {
                        in.close();
                    }
                    final List<Account> found = LinkParser.extract(text);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            fileAccounts.clear();
                            fileAccounts.addAll(found);
                            tvFile.setText("📄 " + name + "  —  " + found.size() + " link");
                            if (found.isEmpty()) {
                                Toast.makeText(MainActivity.this, "Bu dosyada geçerli link bulunamadı", Toast.LENGTH_LONG).show();
                            }
                            reparse();
                        }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            tvFile.setText("📄 " + name + "  —  okunamadı");
                            Toast.makeText(MainActivity.this, "Dosya okunamadı: " + e.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }
        });
    }

    private String fileNameOf(Uri uri) {
        String name = null;
        Cursor cursor = null;
        try {
            cursor = getContentResolver().query(uri, null, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) name = cursor.getString(idx);
            }
        } catch (Exception ignored) {
            // ad alınamazsa yolun son parçası kullanılır
        } finally {
            if (cursor != null) cursor.close();
        }
        if (name == null) name = uri.getLastPathSegment();
        return name == null ? "dosya" : name;
    }

    // ------------------------------------------------------------------ başlat

    private void startCheck() {
        List<Account> sel = selectedAccounts();
        if (sel.isEmpty()) {
            Toast.makeText(this, "Önce link ekleyip en az bir panel seç", Toast.LENGTH_SHORT).show();
            return;
        }
        Session.accounts = sel;
        Session.checkM3u = cbM3u.isChecked();
        Session.threads = sbThreads.getProgress() + 1;
        startActivity(new Intent(this, ResultsActivity.class));
    }

    private void openChannels() {
        List<Account> sel = selectedAccounts();
        if (sel.isEmpty()) {
            Toast.makeText(this, "Önce link ekleyip en az bir panel seç", Toast.LENGTH_SHORT).show();
            return;
        }
        Session.channelAccounts = sel;
        startActivity(new Intent(this, ChannelsActivity.class));
    }

    // ------------------------------------------------------------------ kayıt

    private void restorePrefs() {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        String text = p.getString("links", "");
        if (text != null && !text.isEmpty()) etLinks.setText(text);
        sbThreads.setProgress(Math.max(0, Math.min(19, p.getInt("threads", 8) - 1)));
        cbM3u.setChecked(p.getBoolean("m3u", true));
        updateThreadsText();
    }

    @Override
    protected void onPause() {
        super.onPause();
        String text = etLinks.getText().toString();
        if (text.length() > MAX_SAVED_CHARS) text = text.substring(0, MAX_SAVED_CHARS);
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString("links", text)
                .putInt("threads", sbThreads.getProgress() + 1)
                .putBoolean("m3u", cbM3u.isChecked())
                .apply();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(parseTask);
        io.shutdownNow();
        parser.shutdownNow();
    }
}
