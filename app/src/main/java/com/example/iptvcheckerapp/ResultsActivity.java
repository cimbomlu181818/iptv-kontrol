package com.example.iptvcheckerapp;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Kontrol sonuçları: canlı ilerleme, filtre, sıralama, kopyalama ve paylaşma. */
public class ResultsActivity extends Activity {

    private static final int MAX_SHARE_CHARS = 300000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<CheckResult> all = new ArrayList<>();
    private final List<CheckResult> shown = new ArrayList<>();
    private final Set<CheckResult> expanded = new HashSet<>();

    private ExecutorService pool;
    private int generation = 0;
    private boolean checking = false;
    private boolean refreshPending = false;
    private int total = 0;
    private int done = 0;
    private long startMs = 0;
    private long endMs = 0;

    // Filtre durumu
    private int mode = 0;                       // 0 hepsi, 1 tam, 2 kısmi, 3 pasif, 4 süresi dolmuş
    private String query = "";
    private final Set<String> fPanels = new HashSet<>();
    private final Set<String> fYears = new HashSet<>();
    private final Set<String> fMax = new HashSet<>();
    private int fTrial = 0;                     // 0 hepsi, 1 sadece deneme, 2 deneme hariç
    private int sortMode = 0;                   // 0 geliş sırası, 1 bitiş tarihi, 2 kanal sayısı

    // Ekran parçaları
    private TextView tvSummary, tvCounts, tvTime, tvFilterInfo, tvEmpty;
    private ProgressBar bar;
    private Button btnStop, btnCopy, btnShare, btnRetry;
    private final TextView[] chips = new TextView[5];
    private ListView listView;
    private RowAdapter adapter;

    private final Runnable refreshTask = new Runnable() {
        @Override
        public void run() {
            refresh();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        if (Session.accounts == null || Session.accounts.isEmpty()) {
            Toast.makeText(this, "Kontrol edilecek link yok", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        run(new ArrayList<>(Session.accounts));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        generation++;
        if (pool != null) pool.shutdownNow();
        handler.removeCallbacks(refreshTask);
    }

    // ------------------------------------------------------------------ kontrol

    private void run(List<Account> list) {
        generation++;
        final int gen = generation;
        if (pool != null) pool.shutdownNow();

        total = all.size() + list.size();
        done = all.size();
        startMs = System.currentTimeMillis();
        checking = true;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        final AccountChecker checker = new AccountChecker(Session.checkM3u);
        int threads = Math.max(1, Math.min(Session.threads, list.size()));
        pool = Executors.newFixedThreadPool(threads);
        for (final Account account : list) {
            pool.execute(new Runnable() {
                @Override
                public void run() {
                    CheckResult r;
                    try {
                        r = checker.check(account);
                    } catch (Throwable t) {
                        r = new CheckResult(account);
                        r.apiMessage = "Beklenmeyen hata";
                    }
                    final CheckResult result = r;
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            onResult(gen, result);
                        }
                    });
                }
            });
        }
        pool.shutdown();
        refresh();
    }

    private void onResult(int gen, CheckResult r) {
        if (gen != generation) return;
        all.add(r);
        done++;
        if (done >= total) finishChecking();
        scheduleRefresh();
    }

    private void finishChecking() {
        checking = false;
        endMs = System.currentTimeMillis();
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (done >= total) {
            Toast.makeText(this, "Kontrol tamamlandı", Toast.LENGTH_SHORT).show();
        }
    }

    private void stopChecking() {
        generation++;                // durdurulmadan önce başlamış kontrollerin geç gelen sonuçları yok sayılır
        if (pool != null) pool.shutdownNow();
        checking = false;
        endMs = System.currentTimeMillis();
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        refresh();
    }

    private void restartAll() {
        all.clear();
        expanded.clear();
        run(new ArrayList<>(Session.accounts));
    }

    private void retryDead() {
        if (checking) return;
        List<Account> redo = new ArrayList<>();
        List<CheckResult> remove = new ArrayList<>();
        for (CheckResult r : all) {
            if (r.getState() == CheckResult.State.DEAD) {
                redo.add(r.account);
                remove.add(r);
            }
        }
        if (redo.isEmpty()) {
            Toast.makeText(this, "Tekrar denenecek pasif link yok", Toast.LENGTH_SHORT).show();
            return;
        }
        all.removeAll(remove);
        expanded.removeAll(remove);
        run(redo);
    }

    // ------------------------------------------------------------------ yenileme

    private void scheduleRefresh() {
        if (refreshPending) return;
        refreshPending = true;
        handler.postDelayed(refreshTask, 250);
    }

    private boolean matches(CheckResult r) {
        CheckResult.State s = r.getState();
        if (mode == 1 && s != CheckResult.State.FULL) return false;
        if (mode == 2 && s != CheckResult.State.PARTIAL) return false;
        if (mode == 3 && s != CheckResult.State.DEAD) return false;
        if (mode == 4 && s != CheckResult.State.EXPIRED) return false;
        if (!query.isEmpty()) {
            String hay = (r.account.username + " " + r.account.panelKey()).toLowerCase(Locale.ROOT);
            if (!hay.contains(query)) return false;
        }
        if (!fPanels.isEmpty() && !fPanels.contains(r.account.panelKey())) return false;
        if (!fMax.isEmpty() && !fMax.contains(String.valueOf(r.maxConnections))) return false;
        if (!fYears.isEmpty()) {
            String y = r.expiryYear();
            if (!fYears.contains(y == null ? "Süresiz" : y)) return false;
        }
        if (fTrial == 1 && !r.trial) return false;
        if (fTrial == 2 && r.trial) return false;
        return true;
    }

    private void refresh() {
        refreshPending = false;
        shown.clear();
        int full = 0, part = 0, exp = 0, dead = 0;
        for (CheckResult r : all) {
            switch (r.getState()) {
                case FULL: full++; break;
                case PARTIAL: part++; break;
                case EXPIRED: exp++; break;
                default: dead++; break;
            }
            if (matches(r)) shown.add(r);
        }
        if (sortMode == 1) {
            Collections.sort(shown, new Comparator<CheckResult>() {
                @Override
                public int compare(CheckResult a, CheckResult b) {
                    long x = a.hasExpiry ? a.expiresAtMs : Long.MAX_VALUE;
                    long y = b.hasExpiry ? b.expiresAtMs : Long.MAX_VALUE;
                    return Long.compare(y, x);
                }
            });
        } else if (sortMode == 2) {
            Collections.sort(shown, new Comparator<CheckResult>() {
                @Override
                public int compare(CheckResult a, CheckResult b) {
                    return Integer.compare(b.channelCount, a.channelCount);
                }
            });
        }
        adapter.notifyDataSetChanged();

        tvSummary.setText((checking ? "Kontrol ediliyor…  " : "Kontrol bitti  ") + done + " / " + total);
        tvCounts.setText("✅ Tam: " + full + "    ⚠️ Kısmi: " + part + "    ⌛ Süresi dolmuş: " + exp + "    ❌ Pasif: " + dead);
        bar.setMax(Math.max(1, total));
        bar.setProgress(done);
        bar.setVisibility(checking ? View.VISIBLE : View.GONE);
        long sec = ((checking ? System.currentTimeMillis() : endMs) - startMs) / 1000;
        tvTime.setText(checking ? "Geçen süre: " + formatDuration(sec)
                : (done < total ? "Durduruldu • " : "") + "Süre: " + formatDuration(sec));

        btnStop.setText(checking ? "⏹ Durdur" : "🔄 Yeniden");
        btnCopy.setText("📋 Kopyala (" + shown.size() + ")");
        btnShare.setText("📤 Paylaş");
        btnRetry.setEnabled(!checking && dead > 0);
        btnRetry.setAlpha(!checking && dead > 0 ? 1f : 0.45f);

        String fi = filterSummary();
        tvFilterInfo.setVisibility(fi.isEmpty() ? View.GONE : View.VISIBLE);
        tvFilterInfo.setText(fi);

        tvEmpty.setVisibility(shown.isEmpty() ? View.VISIBLE : View.GONE);
        if (shown.isEmpty()) {
            tvEmpty.setText(all.isEmpty() ? "İlk sonuçlar bekleniyor…" : "Bu filtreye uyan link yok");
        }
        for (int i = 0; i < chips.length; i++) Ui.setChipSelected(this, chips[i], mode == i);
    }

    private String filterSummary() {
        List<String> parts = new ArrayList<>();
        if (!fPanels.isEmpty()) parts.add("Panel: " + fPanels.size());
        if (!fMax.isEmpty()) parts.add("Bağlantı: " + fMax.size());
        if (!fYears.isEmpty()) parts.add("Yıl: " + fYears.size());
        if (fTrial == 1) parts.add("Sadece deneme");
        if (fTrial == 2) parts.add("Deneme hariç");
        if (sortMode == 1) parts.add("Sıra: bitiş tarihi");
        if (sortMode == 2) parts.add("Sıra: kanal sayısı");
        if (parts.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("🔍 ");
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) sb.append("  •  ");
            sb.append(parts.get(i));
        }
        return sb.toString();
    }

    private static String formatDuration(long sec) {
        if (sec < 60) return sec + " sn";
        return (sec / 60) + " dk " + (sec % 60) + " sn";
    }

    // ------------------------------------------------------------------ arayüz

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        int pad = Ui.dp(this, 12);
        root.setPadding(pad, pad, pad, 0);

        // Başlık satırı
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        Button back = Ui.button(this, "←", Ui.WHITE, Ui.TEXT);
        back.setTextSize(18);
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        head.addView(back, Ui.lp(Ui.dp(this, 48), ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView title = Ui.text(this, "Sonuçlar", 20, Ui.TEXT, true);
        title.setPadding(Ui.dp(this, 12), 0, 0, 0);
        head.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        btnStop = Ui.button(this, "⏹ Durdur", Ui.RED, Ui.WHITE);
        btnStop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (checking) stopChecking();
                else restartAll();
            }
        });
        head.addView(btnStop);
        root.addView(head, Ui.lp(this, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, 8));

        // Özet kartı
        LinearLayout sum = Ui.card(this);
        tvSummary = Ui.text(this, "", 16, Ui.TEXT, true);
        sum.addView(tvSummary);
        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        sum.addView(bar, Ui.lp(this, ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 10), 0, 6, 0, 6));
        tvCounts = Ui.text(this, "", 12, Ui.TEXT, false);
        sum.addView(tvCounts);
        tvTime = Ui.text(this, "", 12, Ui.SUB, false);
        sum.addView(tvTime, Ui.lp(this, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 2, 0, 0));
        root.addView(sum, Ui.lp(this, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, 8));

        // Filtre çipleri
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout chipRow = new LinearLayout(this);
        chipRow.setOrientation(LinearLayout.HORIZONTAL);
        String[] names = {"Hepsi", "✅ Tam aktif", "⚠️ Kısmi", "❌ Pasif", "⌛ Süresi dolmuş"};
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            TextView chip = Ui.chip(this, names[i]);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    mode = idx;
                    refresh();
                }
            });
            chips[i] = chip;
            chipRow.addView(chip, Ui.lp(this, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 6, 0));
        }
        TextView filterChip = Ui.chip(this, "⚙ Filtre");
        filterChip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showFilterMenu();
            }
        });
        chipRow.addView(filterChip, Ui.lp(this, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 6, 0));
        TextView sortChip = Ui.chip(this, "↕ Sırala");
        sortChip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showSortDialog();
            }
        });
        chipRow.addView(sortChip);
        hs.addView(chipRow);
        root.addView(hs, Ui.lp(this, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, 8));

        // Arama kutusu
        EditText search = new EditText(this);
        search.setHint("Ara: kullanıcı adı veya panel");
        search.setHintTextColor(Ui.GRAY);
        search.setTextColor(Ui.TEXT);
        search.setTextSize(14);
        search.setSingleLine(true);
        search.setBackground(Ui.rounded(this, Ui.WHITE, Ui.LINE, 10));
        int sp = Ui.dp(this, 10);
        search.setPadding(sp, sp, sp, sp);
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {}

            @Override
            public void afterTextChanged(Editable s) {
                query = s.toString().trim().toLowerCase(Locale.ROOT);
                refresh();
            }
        });
        root.addView(search, Ui.lp(this, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, 6));

        tvFilterInfo = Ui.text(this, "", 12, Ui.PRIMARY, true);
        tvFilterInfo.setVisibility(View.GONE);
        root.addView(tvFilterInfo, Ui.lp(this, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 2, 0, 0, 6));

        // Eylem düğmeleri
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        btnCopy = Ui.button(this, "📋 Kopyala", Ui.WHITE, Ui.TEXT);
        btnShare = Ui.button(this, "📤 Paylaş", Ui.WHITE, Ui.TEXT);
        btnRetry = Ui.button(this, "🔁 Pasifleri tekrar dene", Ui.WHITE, Ui.TEXT);
        btnCopy.setTextSize(12);
        btnShare.setTextSize(12);
        btnRetry.setTextSize(12);
        btnCopy.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copyShown();
            }
        });
        btnShare.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                shareShown();
            }
        });
        btnRetry.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                retryDead();
            }
        });
        actions.addView(btnCopy, Ui.weight(this, 0, 3));
        actions.addView(btnShare, Ui.weight(this, 3, 3));
        actions.addView(btnRetry, Ui.weight(this, 3, 0));
        root.addView(actions, Ui.lp(this, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, 4));

        // Liste
        android.widget.FrameLayout listFrame = new android.widget.FrameLayout(this);
        listView = new ListView(this);
        listView.setDivider(null);
        listView.setDividerHeight(0);
        listView.setClipToPadding(false);
        listView.setPadding(0, 0, 0, Ui.dp(this, 16));
        adapter = new RowAdapter();
        listView.setAdapter(adapter);
        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                if (position < 0 || position >= shown.size()) return;
                CheckResult r = shown.get(position);
                if (!expanded.remove(r)) expanded.add(r);
                adapter.notifyDataSetChanged();
            }
        });
        listFrame.addView(listView, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        tvEmpty = Ui.text(this, "", 14, Ui.SUB, false);
        tvEmpty.setGravity(Gravity.CENTER);
        listFrame.addView(tvEmpty, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        root.addView(listFrame, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
    }

    // ------------------------------------------------------------------ filtre ve sıralama

    private void showFilterMenu() {
        final String[] items = {"Panel", "Maksimum bağlantı sayısı", "Bitiş yılı", "Deneme hesabı", "Tüm filtreleri temizle"};
        new AlertDialog.Builder(this)
                .setTitle("Filtre")
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        if (which == 0) pickMulti("Panel", panelOptions(), fPanels);
                        else if (which == 1) pickMulti("Maksimum bağlantı", maxOptions(), fMax);
                        else if (which == 2) pickMulti("Bitiş yılı", yearOptions(), fYears);
                        else if (which == 3) pickTrial();
                        else clearFilters();
                    }
                })
                .setNegativeButton("Kapat", null)
                .show();
    }

    private List<String> panelOptions() {
        TreeSet<String> set = new TreeSet<>();
        for (CheckResult r : all) set.add(r.account.panelKey());
        return new ArrayList<>(set);
    }

    private List<String> maxOptions() {
        TreeSet<Integer> set = new TreeSet<>();
        for (CheckResult r : all) if (r.maxConnections > 0) set.add(r.maxConnections);
        List<String> out = new ArrayList<>();
        for (Integer i : set) out.add(String.valueOf(i));
        return out;
    }

    private List<String> yearOptions() {
        TreeSet<String> set = new TreeSet<>();
        boolean unlimited = false;
        for (CheckResult r : all) {
            String y = r.expiryYear();
            if (y != null) set.add(y);
            else if (r.apiOk) unlimited = true;
        }
        List<String> out = new ArrayList<>(set);
        if (unlimited) out.add("Süresiz");
        return out;
    }

    private void pickMulti(String title, final List<String> options, final Set<String> selected) {
        if (options.isEmpty()) {
            Toast.makeText(this, "Henüz seçenek yok", Toast.LENGTH_SHORT).show();
            return;
        }
        final boolean[] checked = new boolean[options.size()];
        for (int i = 0; i < checked.length; i++) checked[i] = selected.contains(options.get(i));
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMultiChoiceItems(options.toArray(new String[0]), checked,
                        new DialogInterface.OnMultiChoiceClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int which, boolean isChecked) {
                                checked[which] = isChecked;
                            }
                        })
                .setPositiveButton("Uygula", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        selected.clear();
                        for (int i = 0; i < checked.length; i++) if (checked[i]) selected.add(options.get(i));
                        refresh();
                    }
                })
                .setNeutralButton("Temizle", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        selected.clear();
                        refresh();
                    }
                })
                .setNegativeButton("İptal", null)
                .show();
    }

    private void pickTrial() {
        final String[] items = {"Hepsi", "Sadece deneme hesapları", "Deneme hariç"};
        new AlertDialog.Builder(this)
                .setTitle("Deneme hesabı")
                .setSingleChoiceItems(items, fTrial, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        fTrial = which;
                        d.dismiss();
                        refresh();
                    }
                })
                .show();
    }

    private void showSortDialog() {
        final String[] items = {"Geliş sırası", "Bitiş tarihi (en uzun önce)", "Kanal sayısı (çok olan önce)"};
        new AlertDialog.Builder(this)
                .setTitle("Sırala")
                .setSingleChoiceItems(items, sortMode, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        sortMode = which;
                        d.dismiss();
                        refresh();
                    }
                })
                .show();
    }

    private void clearFilters() {
        fPanels.clear();
        fYears.clear();
        fMax.clear();
        fTrial = 0;
        mode = 0;
        sortMode = 0;
        refresh();
    }

    // ------------------------------------------------------------------ kopyala / paylaş

    private String shownLinks() {
        StringBuilder sb = new StringBuilder();
        for (CheckResult r : shown) sb.append(r.account.original).append('\n');
        return sb.toString();
    }

    private void copyText(String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("IPTV linkleri", text));
    }

    private void copyShown() {
        if (shown.isEmpty()) {
            Toast.makeText(this, "Kopyalanacak link yok", Toast.LENGTH_SHORT).show();
            return;
        }
        copyText(shownLinks());
        Toast.makeText(this, shown.size() + " link kopyalandı", Toast.LENGTH_SHORT).show();
    }

    private void shareShown() {
        if (shown.isEmpty()) {
            Toast.makeText(this, "Paylaşılacak link yok", Toast.LENGTH_SHORT).show();
            return;
        }
        String text = shownLinks();
        if (text.length() > MAX_SHARE_CHARS) {
            copyText(text);
            Toast.makeText(this, "Liste çok uzun; panoya kopyalandı, istediğin yere yapıştır", Toast.LENGTH_LONG).show();
            return;
        }
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_TEXT, text);
        startActivity(Intent.createChooser(i, "Linkleri paylaş"));
    }

    // ------------------------------------------------------------------ liste

    private static class Holder {
        View stripe;
        TextView user, state, panel, info, reason;
        LinearLayout expandBox;
        TextView detail;
        Button copy, channels;
    }

    private class RowAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return shown.size();
        }

        @Override
        public Object getItem(int position) {
            return shown.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Holder h;
            if (convertView == null) {
                convertView = createRow();
                h = (Holder) convertView.getTag();
            } else {
                h = (Holder) convertView.getTag();
            }
            bindRow(h, shown.get(position));
            return convertView;
        }
    }

    private View createRow() {
        Context c = this;
        Holder h = new Holder();

        LinearLayout wrap = new LinearLayout(c);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(0, Ui.dp(c, 4), 0, Ui.dp(c, 4));

        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setBackground(Ui.rounded(c, Ui.WHITE, Ui.LINE, 12));
        wrap.addView(card, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        h.stripe = new View(c);
        card.addView(h.stripe, Ui.lp(c, Ui.dp(c, 5), ViewGroup.LayoutParams.MATCH_PARENT, 8, 10, 0, 10));

        LinearLayout body = new LinearLayout(c);
        body.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(c, 10);
        body.setPadding(p, p, p, p);
        card.addView(body, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout top = new LinearLayout(c);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        h.user = Ui.text(c, "", 15, Ui.TEXT, true);
        h.user.setSingleLine(true);
        h.user.setEllipsize(android.text.TextUtils.TruncateAt.END);
        top.addView(h.user, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        h.state = Ui.text(c, "", 10, Ui.WHITE, true);
        h.state.setPadding(Ui.dp(c, 8), Ui.dp(c, 3), Ui.dp(c, 8), Ui.dp(c, 3));
        top.addView(h.state);
        body.addView(top);

        h.panel = Ui.text(c, "", 12, Ui.SUB, false);
        body.addView(h.panel);
        h.info = Ui.text(c, "", 12, Ui.TEXT, false);
        body.addView(h.info, Ui.lp(c, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 3, 0, 0));
        h.reason = Ui.text(c, "", 12, Ui.RED, false);
        body.addView(h.reason, Ui.lp(c, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 3, 0, 0));

        h.expandBox = new LinearLayout(c);
        h.expandBox.setOrientation(LinearLayout.VERTICAL);
        h.detail = Ui.text(c, "", 12, Ui.TEXT, false);
        h.detail.setTypeface(Typeface.MONOSPACE);
        h.expandBox.addView(h.detail, Ui.lp(c, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 8, 0, 8));
        LinearLayout btns = new LinearLayout(c);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        h.copy = Ui.button(c, "📋 Linki kopyala", Ui.WHITE, Ui.TEXT);
        h.channels = Ui.button(c, "📺 Kanallar", Ui.WHITE, Ui.PRIMARY);
        h.copy.setTextSize(12);
        h.channels.setTextSize(12);
        btns.addView(h.copy, Ui.weight(c, 0, 4));
        btns.addView(h.channels, Ui.weight(c, 4, 0));
        h.expandBox.addView(btns);
        body.addView(h.expandBox);

        wrap.setTag(h);
        return wrap;
    }

    private void bindRow(Holder h, final CheckResult r) {
        Context c = this;
        int color;
        CheckResult.State s = r.getState();
        if (s == CheckResult.State.FULL) color = Ui.GREEN;
        else if (s == CheckResult.State.PARTIAL) color = Ui.ORANGE;
        else if (s == CheckResult.State.EXPIRED) color = Ui.GRAY;
        else color = Ui.RED;

        h.stripe.setBackground(Ui.rounded(c, color, 0, 3));
        h.user.setText(r.account.username);
        h.state.setText(r.stateLabel());
        h.state.setBackground(Ui.rounded(c, color, 0, 6));
        h.panel.setText(r.account.panelKey());

        String info = r.infoLine();
        h.info.setVisibility(info.isEmpty() ? View.GONE : View.VISIBLE);
        h.info.setText(info);

        String reason = "";
        if (!r.isUsable()) {
            reason = r.apiMessage == null ? "" : r.apiMessage;
            if (r.m3uMessage != null && !r.m3uMessage.isEmpty() && !r.m3uMessage.equals(reason)) {
                reason = reason.isEmpty() ? r.m3uMessage : reason + "  •  " + r.m3uMessage;
            }
        }
        h.reason.setVisibility(reason.isEmpty() ? View.GONE : View.VISIBLE);
        h.reason.setText(reason);

        boolean open = expanded.contains(r);
        h.expandBox.setVisibility(open ? View.VISIBLE : View.GONE);
        if (open) {
            h.detail.setText(r.detailLine() + "\n\nKullanıcı: " + r.account.username
                    + "\nŞifre: " + r.account.password + "\nLink: " + r.account.original);
            h.copy.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    copyText(r.account.original);
                    Toast.makeText(ResultsActivity.this, "Link kopyalandı", Toast.LENGTH_SHORT).show();
                }
            });
            h.channels.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    List<Account> one = new ArrayList<>();
                    one.add(r.account);
                    Session.channelAccounts = one;
                    startActivity(new Intent(ResultsActivity.this, ChannelsActivity.class));
                }
            });
        }
    }
}
