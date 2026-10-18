package com.example.iptvcheckerapp;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
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
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Bir panelin kanal listesini Ana grup > Kategori > Kanal biçiminde gösterir ve arama yapar. */
public class ChannelsActivity extends Activity {

    private static final int MAX_TRIES = 20;
    private static final int MAX_HITS = 400;
    private static final long MAX_BYTES = 40L * 1024 * 1024;

    private static class Hit {
        String label;
        M3UParser.Category category;
        String channel;     // null ise kategori eşleşmesidir
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private volatile boolean cancelled = false;

    private TextView tvStatus;
    private EditText etSearch;
    private ExpandableListView expandable;
    private ListView resultsList;
    private M3UParser.Result data;
    private final List<Hit> hits = new ArrayList<>();
    private ArrayAdapter<String> hitsAdapter;
    private final List<String> hitLabels = new ArrayList<>();

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
        load(list);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cancelled = true;
        io.shutdownNow();
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
                for (int i = 0; i < max && !cancelled; i++) {
                    final Account a = list.get(i);
                    final int index = i + 1;
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            tvStatus.setText("⏳ " + index + "/" + max + " deneniyor: " + a.panelKey());
                        }
                    });
                    HttpURLConnection c = null;
                    try {
                        c = Http.open(a.original, 8000, 15000);
                        if (c.getResponseCode() != 200) continue;
                        final M3UParser.Result r = M3UParser.parse(c.getInputStream(), MAX_BYTES);
                        if (r.channelCount > 0) {
                            final String panel = a.panelKey();
                            handler.post(new Runnable() {
                                @Override
                                public void run() {
                                    show(r, panel);
                                }
                            });
                            return;
                        }
                    } catch (IOException e) {
                        // Bu link olmadı, sıradakini dene
                    } catch (OutOfMemoryError e) {
                        // Liste telefonun belleğine sığmadı: çökmek yerine açıklama göster
                        if (!cancelled) {
                            handler.post(new Runnable() {
                                @Override
                                public void run() {
                                    tvStatus.setText("❌ Liste çok büyük, telefonun belleği yetmedi");
                                }
                            });
                        }
                        return;
                    } finally {
                        if (c != null) c.disconnect();
                    }
                }
                if (!cancelled) {
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            tvStatus.setText("❌ Çalışan bir kanal listesi bulunamadı");
                        }
                    });
                }
            }
        });
    }

    private void show(M3UParser.Result r, String panel) {
        data = r;
        String text = "✅ " + r.groups.size() + " ana grup  •  " + r.categoryCount + " kategori  •  "
                + r.channelCount + " kanal   (" + panel + ")";
        if (r.truncated) text += "\n⚠️ Liste çok büyük, bir kısmı gösteriliyor";
        tvStatus.setText(text);
        expandable.setAdapter(new GroupsAdapter());
        if (!r.groups.isEmpty()) expandable.expandGroup(0);
    }

    // ------------------------------------------------------------------ arama

    private void runSearch() {
        String q = etSearch.getText().toString().trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty() || data == null) {
            resultsList.setVisibility(View.GONE);
            expandable.setVisibility(View.VISIBLE);
            return;
        }
        hits.clear();
        hitLabels.clear();
        boolean full = false;
        for (M3UParser.Group g : data.groups) {
            if (full) break;
            for (M3UParser.Category cat : g.categories) {
                if (full) break;
                if (cat.name.toLowerCase(Locale.ROOT).contains(q)) {
                    Hit h = new Hit();
                    h.category = cat;
                    h.label = "📁 " + cat.name + "  (" + cat.channels.size() + " kanal)\n   " + g.name;
                    hits.add(h);
                    hitLabels.add(h.label);
                }
                for (String ch : cat.channels) {
                    if (ch.toLowerCase(Locale.ROOT).contains(q)) {
                        Hit h = new Hit();
                        h.category = cat;
                        h.channel = ch;
                        h.label = "📺 " + ch + "\n   " + g.name + " › " + cat.name;
                        hits.add(h);
                        hitLabels.add(h.label);
                        if (hits.size() >= MAX_HITS) {
                            full = true;
                            break;
                        }
                    }
                }
            }
        }
        hitsAdapter.notifyDataSetChanged();
        expandable.setVisibility(View.GONE);
        resultsList.setVisibility(View.VISIBLE);
        if (hits.isEmpty()) {
            tvStatus.setText("Sonuç bulunamadı");
        } else {
            tvStatus.setText(hits.size() + (full ? "+" : "") + " sonuç");
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

        FrameLayout frame = new FrameLayout(this);
        expandable = new ExpandableListView(this);
        expandable.setBackground(Ui.rounded(this, Ui.WHITE, Ui.LINE, 12));
        expandable.setDividerHeight(0);
        expandable.setOnChildClickListener(new ExpandableListView.OnChildClickListener() {
            @Override
            public boolean onChildClick(ExpandableListView p, View v, int groupPos, int childPos, long id) {
                if (data == null) return true;
                showCategory(data.groups.get(groupPos).categories.get(childPos), null);
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
            return data.groups.get(g).categories.size();
        }

        @Override
        public Object getGroup(int g) {
            return data.groups.get(g);
        }

        @Override
        public Object getChild(int g, int c) {
            return data.groups.get(g).categories.get(c);
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
            M3UParser.Category cat = data.groups.get(g).categories.get(c);
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
