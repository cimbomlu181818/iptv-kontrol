package com.example.iptvcheckerapp;

import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.res.ColorStateList;
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
import android.widget.ArrayAdapter;
import android.widget.BaseExpandableListAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ExpandableListView;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Bir panelin kanal listesini Ana grup > Kategori > Kanal biçiminde gösterir ve arama yapar.
 * Liste inerken animasyonlu bir ilerleme kartı gösterilir (sunucu dosya boyutunu bildiriyorsa yüzde ile).
 */
public class ChannelsActivity extends Activity {

    private static final int MAX_TRIES = 20;
    private static final int MAX_HITS = 400;
    private static final long MAX_BYTES = 40L * 1024 * 1024;

    private static class Hit {
        String label;
        M3UParser.Category category;
        String channel;     // null ise kategori eşleşmesidir
    }

    /** Bir hesabı deneme sonucu: liste ya da neden alınamadığı. */
    private static class Attempt {
        M3UParser.Result result;
        String reason = "Liste alınamadı";
        boolean oom;
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ExecutorService searchIo = Executors.newSingleThreadExecutor();
    private volatile boolean cancelled = false;
    // Açık bağlantılar: ekrandan çıkınca hemen kapatılır
    private final Set<HttpURLConnection> active = ConcurrentHashMap.newKeySet();
    private volatile AccountChecker reasonChecker;

    // Ekran parçaları
    private TextView tvStatus;
    private EditText etSearch;
    private ExpandableListView expandable;
    private ListView resultsList;
    private LinearLayout chipRow;
    private TextView chipSort, chipEmpty;

    // İndirme kartı
    private LinearLayout progressCard;
    private TextView tvPulse, tvPercent, tvStage, tvDetail;
    private ProgressBar bar;
    private ObjectAnimator pulse;

    // Veri
    private M3UParser.Result data;
    private String summaryText = "";
    private final List<List<M3UParser.Category>> view = new ArrayList<>();
    private GroupsAdapter groupsAdapter;
    private int sortMode = 0;               // 0 varsayılan (Türkçe önce), 1 A-Z, 2 kanal sayısı
    private boolean hideEmpty = false;

    // Arama
    private final List<Hit> hits = new ArrayList<>();
    private ArrayAdapter<String> hitsAdapter;
    private final List<String> hitLabels = new ArrayList<>();
    private volatile int searchSeq = 0;

    private final Runnable searchTask = new Runnable() {
        @Override
        public void run() {
            runSearch();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        final List<Account> list = new ArrayList<>(Session.channelAccounts);
        if (list.isEmpty()) {
            Toast.makeText(this, "Link bulunamadı", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        startPulse();
        load(list);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cancelled = true;
        stopPulse();
        Http.disconnectAsync(active);
        AccountChecker rc = reasonChecker;
        if (rc != null) rc.cancel();
        io.shutdownNow();
        searchIo.shutdownNow();
        handler.removeCallbacks(searchTask);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    @Override
    public void onBackPressed() {
        if (etSearch != null && etSearch.getText().length() > 0) {
            etSearch.setText("");
            return;
        }
        super.onBackPressed();
    }

    // ------------------------------------------------------------------ yükleme

    private void load(final List<Account> list) {
        final int max = Math.min(MAX_TRIES, list.size());
        tvStatus.setText("⏳ Kanal listesi aranıyor…");
        io.execute(new Runnable() {
            @Override
            public void run() {
                // Başarısızlık nedenleri (aynı neden tekrar ederse sayılır)
                Map<String, Integer> reasons = new LinkedHashMap<>();
                int tried = 0;
                for (int i = 0; i < max && !cancelled; i++) {
                    final Account a = list.get(i);
                    final int index = i + 1;
                    tried++;
                    postStart(a.panelKey(), index, max);
                    Attempt at = tryAccount(a);
                    if (cancelled) return;
                    if (at.result != null) {
                        final M3UParser.Result r = at.result;
                        final String panel = a.panelKey();
                        handler.post(new Runnable() {
                            @Override
                            public void run() {
                                show(r, panel);
                            }
                        });
                        return;
                    }
                    if (at.oom) {
                        postFail("❌ Liste çok büyük, telefonun belleği yetmedi");
                        return;
                    }
                    Integer n = reasons.get(at.reason);
                    reasons.put(at.reason, n == null ? 1 : n + 1);
                }
                if (cancelled) return;
                postFail(buildFailMessage(reasons, tried, list.size()));
            }
        });
    }

    private String buildFailMessage(Map<String, Integer> reasons, int tried, int totalLinks) {
        if (tried <= 1 && reasons.size() == 1) {
            return "❌ Kanal listesi alınamadı: " + reasons.keySet().iterator().next();
        }
        StringBuilder sb = new StringBuilder("❌ Kanal listesi alınamadı (" + tried + " hesap denendi");
        if (totalLinks > tried) sb.append(", ").append(totalLinks).append(" linkin ilk ").append(tried).append("'i");
        sb.append(")");
        int shown = 0;
        for (Map.Entry<String, Integer> e : reasons.entrySet()) {
            if (shown++ >= 4) break;
            sb.append("\n• ").append(e.getKey());
            if (e.getValue() > 1) sb.append("  ×").append(e.getValue());
        }
        return sb.toString();
    }

    /** Tek bir hesabı dener: önce M3U (kategorili), gerekirse panel API'si, olmazsa nedenini bulur. */
    private Attempt tryAccount(final Account a) {
        final Attempt at = new Attempt();
        M3UParser.Result best = null;
        boolean unreachable = false;

        // Önce kategorili (m3u_plus) liste, olmazsa linkin kendi hali denenir
        String[] urls = a.listUrl().equals(a.original)
                ? new String[]{a.original}
                : new String[]{a.listUrl(), a.original};
        for (String url : urls) {
            if (cancelled) return at;
            HttpURLConnection c = null;
            try {
                c = Http.open(url, 8000, 20000, active);
                int code = c.getResponseCode();
                if (code != 200) {
                    at.reason = "Sunucu HTTP " + code + " yanıtı verdi";
                    continue;
                }
                final long len = c.getContentLengthLong();
                postDownloadStage("📥 Kanal listesi indiriliyor");
                M3UParser.Result r = M3UParser.parse(c.getInputStream(), MAX_BYTES, new M3UParser.Progress() {
                    @Override
                    public void onProgress(long bytes, int channels) {
                        postDownload(bytes, len, channels);
                    }
                });
                if (r.channelCount > 0) {
                    best = r;
                    break;
                }
                at.reason = "Liste boş geldi";
            } catch (IOException e) {
                if (cancelled) return at;
                at.reason = AccountChecker.describe(e);
                if (AccountChecker.isUnreachable(e)) {
                    unreachable = true;
                    break;
                }
            } catch (OutOfMemoryError e) {
                at.oom = true;
                return at;
            } finally {
                Http.release(c, active);
            }
        }

        // Liste kategorisizse ya da hiç alınamadıysa panelin API'sinden kategorili liste kur
        boolean needApi = !unreachable && (best == null || M3UParser.isUncategorized(best));
        if (needApi && !cancelled) {
            postApiStage();
            try {
                M3UParser.Result api = ApiCatalog.load(a, active, new ApiCatalog.Control() {
                    @Override
                    public boolean cancelled() {
                        return cancelled;
                    }
                }, new ApiCatalog.Progress() {
                    @Override
                    public void onProgress(int step, int steps, String label, long bytes, long total, int channels) {
                        postApiProgress(step, steps, label, bytes, total, channels);
                    }
                });
                if (api.channelCount > 0 && (best == null || !M3UParser.isUncategorized(api))) best = api;
            } catch (IOException e) {
                if (cancelled) return at;
                if (best == null) {
                    at.reason = AccountChecker.describe(e);
                    unreachable = AccountChecker.isUnreachable(e);
                }
            } catch (OutOfMemoryError e) {
                if (best == null) {
                    at.oom = true;
                    return at;
                }
            }
        }

        if (best != null) {
            at.result = best;
            return at;
        }

        // Neden alınamadığını hesabın durumundan öğren (yanlış şifre, süresi dolmuş, kapalı...)
        if (!unreachable && !cancelled) {
            postStage("🔎 Hesap durumu kontrol ediliyor");
            AccountChecker checker = new AccountChecker(false);
            reasonChecker = checker;
            CheckResult cr = checker.check(a);
            if (cancelled) return at;
            if (!cr.apiOk && cr.apiMessage != null && !cr.apiMessage.isEmpty()) {
                at.reason = cr.apiMessage;
            } else if (cr.apiOk) {
                at.reason = "Hesap aktif ama panel kanal listesi vermiyor";
            }
        }
        return at;
    }

    // ------------------------------------------------------------------ indirme kartı (arayüz iş parçacığı)

    private void postStart(final String panel, final int index, final int max) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                tvStatus.setText("⏳ " + index + "/" + max + " deneniyor: " + panel);
                tvStage.setText("🔌 Sunucuya bağlanılıyor…");
                tvDetail.setText(panel);
                setProgress(-1, "…");
            }
        });
    }

    private void postStage(final String stage) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                tvStage.setText(stage);
            }
        });
    }

    private void postDownloadStage(final String stage) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                tvStage.setText(stage);
                setProgress(-1, "0 KB");
            }
        });
    }

    /** İndirme durumu: dosya boyutu biliniyorsa yüzde, bilinmiyorsa indirilen miktar gösterilir. */
    private void postDownload(final long bytes, final long total, final int channels) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (data != null) return;
                if (total > 0) {
                    int permille = (int) Math.min(1000L, bytes * 1000L / total);
                    setProgress(permille, "%" + Math.min(100, permille / 10));
                    tvDetail.setText(formatSize(bytes) + " / " + formatSize(total) + "   •   " + channels + " kanal bulundu");
                } else {
                    setProgress(-1, formatSize(bytes));
                    tvDetail.setText("Sunucu dosya boyutunu bildirmedi   •   " + channels + " kanal bulundu");
                }
            }
        });
    }

    private void postApiStage() {
        handler.post(new Runnable() {
            @Override
            public void run() {
                tvStage.setText("🗂 Kategoriler panelin API'sinden alınıyor");
                setProgress(0, "%0");
            }
        });
    }

    private void postApiProgress(final int step, final int steps, final String label,
                                 final long bytes, final long total, final int channels) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (data != null) return;
                float within = total > 0 ? Math.min(1f, (float) bytes / total) : 0f;
                int permille = (int) (((step - 1) + within) / steps * 1000f);
                permille = Math.max(0, Math.min(1000, permille));
                setProgress(permille, "%" + (permille / 10));
                tvStage.setText("🗂 Kategoriler API'den alınıyor  •  adım " + step + "/" + steps);
                tvDetail.setText(label + (bytes > 0 ? "  •  " + formatSize(bytes) : "") + "   •   " + channels + " kanal bulundu");
            }
        });
    }

    private void postFail(final String message) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                stopPulse();
                progressCard.setVisibility(View.GONE);
                tvStatus.setText(message);
            }
        });
    }

    /** permille: 0..1000 ise belirli çubuk, eksi ise "belirsiz" (dönen) çubuk. */
    private void setProgress(int permille, String percentText) {
        if (permille < 0) {
            if (!bar.isIndeterminate()) bar.setIndeterminate(true);
        } else {
            if (bar.isIndeterminate()) bar.setIndeterminate(false);
            bar.setMax(1000);
            bar.setProgress(permille);
        }
        tvPercent.setText(percentText);
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024L * 1024) return String.format(Locale.US, "%.0f KB", bytes / 1024.0);
        return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private void startPulse() {
        if (pulse != null) return;
        pulse = ObjectAnimator.ofPropertyValuesHolder(tvPulse,
                PropertyValuesHolder.ofFloat("scaleX", 1f, 1.25f),
                PropertyValuesHolder.ofFloat("scaleY", 1f, 1.25f),
                PropertyValuesHolder.ofFloat("alpha", 0.55f, 1f));
        pulse.setDuration(700);
        pulse.setRepeatCount(ValueAnimator.INFINITE);
        pulse.setRepeatMode(ValueAnimator.REVERSE);
        pulse.start();
    }

    private void stopPulse() {
        if (pulse != null) {
            pulse.cancel();
            pulse = null;
        }
    }

    private void show(M3UParser.Result r, String panel) {
        data = r;
        String text = "✅ " + r.groups.size() + " ana grup  •  " + r.categoryCount + " kategori  •  "
                + r.channelCount + " kanal   (" + panel + ")";
        if (r.fromApi) text += "\nℹ️ Kategoriler panelin API'sinden alındı";
        if (r.truncated) text += "\n⚠️ Liste çok büyük, bir kısmı gösteriliyor";
        summaryText = text;
        tvStatus.setText(text);

        rebuildView();
        groupsAdapter = new GroupsAdapter();
        expandable.setAdapter(groupsAdapter);
        if (!r.groups.isEmpty()) expandable.expandGroup(0);

        // Kartı yavaşça kapat, listeyi yavaşça göster
        stopPulse();
        progressCard.animate().alpha(0f).setDuration(250).withEndAction(new Runnable() {
            @Override
            public void run() {
                progressCard.setVisibility(View.GONE);
            }
        });
        expandable.setAlpha(0f);
        expandable.setVisibility(View.VISIBLE);
        expandable.animate().alpha(1f).setDuration(300);
        chipRow.setVisibility(View.VISIBLE);
        updateChips();
    }

    // ------------------------------------------------------------------ sıralama ve boş kategoriler

    /** Ekranda gösterilecek kategori listesini (sıralama ve boş kategori ayarına göre) yeniden kurar. */
    private void rebuildView() {
        view.clear();
        if (data == null) return;
        final Collator collator = Collator.getInstance(new Locale("tr", "TR"));
        for (M3UParser.Group g : data.groups) {
            List<M3UParser.Category> list = new ArrayList<>();
            for (M3UParser.Category c : g.categories) {
                if (hideEmpty && c.channels.isEmpty()) continue;
                list.add(c);
            }
            if (sortMode == 1) {
                Collections.sort(list, new Comparator<M3UParser.Category>() {
                    @Override
                    public int compare(M3UParser.Category x, M3UParser.Category y) {
                        return collator.compare(x.name, y.name);
                    }
                });
            } else if (sortMode == 2) {
                Collections.sort(list, new Comparator<M3UParser.Category>() {
                    @Override
                    public int compare(M3UParser.Category x, M3UParser.Category y) {
                        return Integer.compare(y.channels.size(), x.channels.size());
                    }
                });
            }
            view.add(list);
        }
    }

    private void applyView() {
        rebuildView();
        if (groupsAdapter != null) groupsAdapter.notifyDataSetChanged();
        updateChips();
    }

    private void updateChips() {
        String[] names = {"Varsayılan", "A-Z", "Kanal sayısı"};
        chipSort.setText("↕ Sırala: " + names[sortMode]);
        chipEmpty.setText(hideEmpty ? "👁 Boş kategoriler gizli" : "👁 Boş kategoriler görünür");
        Ui.setChipSelected(this, chipEmpty, hideEmpty);
    }

    private void showSortDialog() {
        final String[] items = {"Varsayılan (Türkçe kategoriler önce)", "Ada göre (A-Z)", "Kanal sayısına göre (çok olan önce)"};
        new AlertDialog.Builder(this)
                .setTitle("Kategorileri sırala")
                .setSingleChoiceItems(items, sortMode, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        sortMode = which;
                        d.dismiss();
                        applyView();
                    }
                })
                .show();
    }

    // ------------------------------------------------------------------ arama

    private void runSearch() {
        final String raw = etSearch.getText().toString().trim();
        final M3UParser.Result snapshot = data;
        if (snapshot == null) return;           // liste henüz inmedi
        if (raw.isEmpty()) {
            searchSeq++;
            resultsList.setVisibility(View.GONE);
            expandable.setVisibility(View.VISIBLE);
            chipRow.setVisibility(View.VISIBLE);
            tvStatus.setText(summaryText);
            return;
        }
        final int seq = ++searchSeq;
        final String q = TextUtil.norm(raw);
        tvStatus.setText("🔎 Aranıyor…");
        try {
            searchIo.execute(new Runnable() {
                @Override
                public void run() {
                    final List<Hit> found = new ArrayList<>();
                    final List<String> labels = new ArrayList<>();
                    boolean full = false;
                    for (M3UParser.Group g : snapshot.groups) {
                        if (full) break;
                        for (M3UParser.Category cat : g.categories) {
                            if (full) break;
                            if (seq != searchSeq || cancelled) return;       // daha yeni bir arama başladı
                            if (TextUtil.norm(cat.name).contains(q)) {
                                Hit h = new Hit();
                                h.category = cat;
                                h.label = "📁 " + cat.name + "  (" + cat.channels.size() + " kanal)\n   " + g.name;
                                found.add(h);
                                labels.add(h.label);
                            }
                            for (String ch : cat.channels) {
                                if (TextUtil.norm(ch).contains(q)) {
                                    Hit h = new Hit();
                                    h.category = cat;
                                    h.channel = ch;
                                    h.label = "📺 " + ch + "\n   " + g.name + " › " + cat.name;
                                    found.add(h);
                                    labels.add(h.label);
                                    if (found.size() >= MAX_HITS) {
                                        full = true;
                                        break;
                                    }
                                }
                            }
                        }
                    }
                    final boolean isFull = full;
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (seq != searchSeq) return;
                            hits.clear();
                            hits.addAll(found);
                            hitLabels.clear();
                            hitLabels.addAll(labels);
                            hitsAdapter.notifyDataSetChanged();
                            expandable.setVisibility(View.GONE);
                            chipRow.setVisibility(View.GONE);
                            resultsList.setVisibility(View.VISIBLE);
                            tvStatus.setText(hits.isEmpty() ? "Sonuç bulunamadı"
                                    : hits.size() + (isFull ? "+" : "") + " sonuç");
                        }
                    });
                }
            });
        } catch (RuntimeException e) {
            // Ekran kapanmış, yapılacak bir şey yok
        }
    }

    private void showCategory(final M3UParser.Category cat, final String highlight) {
        final List<String> names = cat.channels;
        ArrayAdapter<String> ad = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, names) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View v = super.getView(position, convertView, parent);
                TextView t = (TextView) v.findViewById(android.R.id.text1);
                t.setTextSize(15);
                boolean hl = highlight != null && names.get(position).equals(highlight);
                t.setBackgroundColor(hl ? 0x3300A050 : 0x00000000);
                return v;
            }
        };
        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle(cat.name + " (" + names.size() + ")")
                .setAdapter(ad, null)
                .setPositiveButton("Kapat", null)
                .create();
        dlg.show();
        if (highlight != null) {
            int idx = names.indexOf(highlight);
            if (idx >= 0) dlg.getListView().setSelection(idx);
        }
    }

    // ------------------------------------------------------------------ arayüz

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        int pad = Ui.dp(this, 12);
        root.setPadding(pad, pad, pad, 0);

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
        TextView title = Ui.text(this, "Kanal Listesi", 20, Ui.TEXT, true);
        title.setPadding(Ui.dp(this, 12), 0, 0, 0);
        head.addView(title);
        root.addView(head, Ui.lp(this, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, 8));

        tvStatus = Ui.text(this, "", 13, Ui.TEXT, false);
        root.addView(tvStatus, Ui.lp(this, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 2, 0, 2, 8));

        etSearch = new EditText(this);
        etSearch.setHint("Kanal veya kategori ara…");
        etSearch.setHintTextColor(Ui.GRAY);
        etSearch.setTextColor(Ui.TEXT);
        etSearch.setTextSize(14);
        etSearch.setSingleLine(true);
        etSearch.setBackground(Ui.rounded(this, Ui.WHITE, Ui.LINE, 10));
        int sp = Ui.dp(this, 10);
        etSearch.setPadding(sp, sp, sp, sp);
        etSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {}

            @Override
            public void afterTextChanged(Editable s) {
                handler.removeCallbacks(searchTask);
                handler.postDelayed(searchTask, 250);
            }
        });
        root.addView(etSearch, Ui.lp(this, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, 8));

        // Sıralama ve boş kategori düğmeleri (liste yüklenince görünür)
        chipRow = new LinearLayout(this);
        chipRow.setOrientation(LinearLayout.HORIZONTAL);
        chipRow.setVisibility(View.GONE);
        chipSort = Ui.chip(this, "↕ Sırala");
        chipSort.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showSortDialog();
            }
        });
        chipRow.addView(chipSort, Ui.lp(this, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 6, 0));
        chipEmpty = Ui.chip(this, "👁 Boş kategoriler görünür");
        chipEmpty.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                hideEmpty = !hideEmpty;
                applyView();
            }
        });
        chipRow.addView(chipEmpty);
        root.addView(chipRow, Ui.lp(this, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, 8));

        FrameLayout frame = new FrameLayout(this);
        expandable = new ExpandableListView(this);
        expandable.setBackground(Ui.rounded(this, Ui.WHITE, Ui.LINE, 12));
        expandable.setDividerHeight(0);
        expandable.setVisibility(View.GONE);
        expandable.setOnChildClickListener(new ExpandableListView.OnChildClickListener() {
            @Override
            public boolean onChildClick(ExpandableListView p, View v, int groupPos, int childPos, long id) {
                if (data == null || groupPos >= view.size() || childPos >= view.get(groupPos).size()) return true;
                showCategory(view.get(groupPos).get(childPos), null);
                return true;
            }
        });
        frame.addView(expandable, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        resultsList = new ListView(this);
        resultsList.setBackground(Ui.rounded(this, Ui.WHITE, Ui.LINE, 12));
        resultsList.setVisibility(View.GONE);
        hitsAdapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, hitLabels);
        resultsList.setAdapter(hitsAdapter);
        resultsList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                if (position < 0 || position >= hits.size()) return;
                Hit h = hits.get(position);
                showCategory(h.category, h.channel);
            }
        });
        frame.addView(resultsList, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // İndirme kartı: titreşen simge, yüzde, çubuk, aşama ve ayrıntı
        progressCard = Ui.card(this);
        progressCard.setGravity(Gravity.CENTER_HORIZONTAL);
        tvPulse = Ui.text(this, "📺", 44, Ui.TEXT, false);
        tvPulse.setGravity(Gravity.CENTER);
        progressCard.addView(tvPulse, Ui.lp(this, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 8, 0, 4));
        tvPercent = Ui.text(this, "…", 30, Ui.PRIMARY, true);
        tvPercent.setGravity(Gravity.CENTER);
        progressCard.addView(tvPercent, Ui.lp(this, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, 6));
        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setIndeterminate(true);
        bar.setProgressTintList(ColorStateList.valueOf(Ui.PRIMARY));
        bar.setIndeterminateTintList(ColorStateList.valueOf(Ui.PRIMARY));
        progressCard.addView(bar, Ui.lp(this, ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 10), 0, 0, 0, 10));
        tvStage = Ui.text(this, "🔌 Sunucuya bağlanılıyor…", 14, Ui.TEXT, true);
        tvStage.setGravity(Gravity.CENTER);
        progressCard.addView(tvStage);
        tvDetail = Ui.text(this, "", 12, Ui.SUB, false);
        tvDetail.setGravity(Gravity.CENTER);
        progressCard.addView(tvDetail, Ui.lp(this, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 4, 0, 4));
        FrameLayout.LayoutParams cardParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP);
        frame.addView(progressCard, cardParams);

        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        fp.bottomMargin = Ui.dp(this, 12);
        root.addView(frame, fp);

        setContentView(root);
    }

    // ------------------------------------------------------------------ ana grup / kategori listesi

    private class GroupsAdapter extends BaseExpandableListAdapter {
        @Override
        public int getGroupCount() {
            return data.groups.size();
        }

        @Override
        public int getChildrenCount(int g) {
            return view.get(g).size();
        }

        @Override
        public Object getGroup(int g) {
            return data.groups.get(g);
        }

        @Override
        public Object getChild(int g, int c) {
            return view.get(g).get(c);
        }

        @Override
        public long getGroupId(int g) {
            return g;
        }

        @Override
        public long getChildId(int g, int c) {
            return c;
        }

        @Override
        public boolean hasStableIds() {
            return false;
        }

        @Override
        public boolean isChildSelectable(int g, int c) {
            return true;
        }

        @Override
        public View getGroupView(int g, boolean isExpanded, View convertView, ViewGroup parent) {
            TextView t = convertView instanceof TextView ? (TextView) convertView : new TextView(ChannelsActivity.this);
            M3UParser.Group grp = data.groups.get(g);
            t.setText(grp.name + "   •   " + grp.channelCount() + " kanal");
            t.setTextSize(15);
            t.setTypeface(Typeface.DEFAULT_BOLD);
            t.setTextColor(Ui.TEXT);
            int p = Ui.dp(ChannelsActivity.this, 14);
            t.setPadding(Ui.dp(ChannelsActivity.this, 40), p, p, p);
            t.setBackgroundColor(0xFFEAF1FD);
            return t;
        }

        @Override
        public View getChildView(int g, int c, boolean isLast, View convertView, ViewGroup parent) {
            TextView t = convertView instanceof TextView ? (TextView) convertView : new TextView(ChannelsActivity.this);
            M3UParser.Category cat = view.get(g).get(c);
            t.setText(cat.name + "   (" + cat.channels.size() + ")");
            t.setTextSize(14);
            t.setTypeface(Typeface.DEFAULT);
            t.setTextColor(Ui.TEXT);
            int p = Ui.dp(ChannelsActivity.this, 12);
            t.setPadding(Ui.dp(ChannelsActivity.this, 28), p, p, p);
            t.setBackgroundColor(0x00000000);
            return t;
        }
    }
}
