package com.example.iptvcheckerapp;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Collections;
import java.util.List;

/** "Listeme ekle" ile biriktirilen hesapları gösterir; tek tek veya hepsini silebilirsin. */
public class SavedListActivity extends Activity {

    private LinearLayout listBox;
    private TextView tvCount;
    private Button btnDeleteAll;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        refresh();
    }

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
        TextView title = Ui.text(this, "Kayıtlı listem", 20, Ui.TEXT, true);
        title.setPadding(Ui.dp(this, 12), 0, 0, 0);
        head.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(head, Ui.lp(this, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, 8));

        tvCount = Ui.text(this, "", 13, Ui.SUB, false);
        root.addView(tvCount, Ui.lp(this, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 2, 0, 0, 6));

        btnDeleteAll = Ui.button(this, "🗑 Tümünü sil", Ui.WHITE, Ui.RED);
        btnDeleteAll.setTextSize(12);
        btnDeleteAll.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmDeleteAll();
            }
        });
        root.addView(btnDeleteAll, Ui.lp(this, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, 8));

        ScrollView scroll = new ScrollView(this);
        listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        listBox.setPadding(0, 0, 0, Ui.dp(this, 16));
        scroll.addView(listBox, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
    }

    private void refresh() {
        listBox.removeAllViews();
        List<SavedLinks.Item> items = SavedLinks.items(this);
        tvCount.setText(items.size() + " kayıtlı link");
        btnDeleteAll.setEnabled(!items.isEmpty());
        btnDeleteAll.setAlpha(items.isEmpty() ? 0.45f : 1f);
        if (items.isEmpty()) {
            TextView empty = Ui.text(this, "Listende kayıtlı link yok.\nSonuç ekranında \"Listeme ekle\" ile link ekleyebilirsin.", 14, Ui.SUB, false);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, Ui.dp(this, 40), 0, 0);
            listBox.addView(empty, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return;
        }
        for (final SavedLinks.Item it : items) {
            LinearLayout card = Ui.card(this);
            card.setOrientation(LinearLayout.HORIZONTAL);
            card.setGravity(Gravity.CENTER_VERTICAL);

            LinearLayout info = new LinearLayout(this);
            info.setOrientation(LinearLayout.VERTICAL);
            if (it.hasInfo) {
                info.addView(Ui.text(this, it.panel, 14, Ui.TEXT, true));
                info.addView(Ui.text(this, "Kullanıcı adı: " + it.user + "  •  Şifre: " + it.pass, 12, Ui.SUB, false),
                        Ui.lp(this, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 3, 0, 0));
                info.addView(Ui.text(this, "Bitiş: " + it.expiry + "  •  En fazla kullanıcı: " + it.max, 12, Ui.SUB, false),
                        Ui.lp(this, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 2, 0, 0));
            } else {
                info.addView(Ui.text(this, it.key, 13, Ui.TEXT, true));
                info.addView(Ui.text(this, "Eski kayıt (bilgisi yok)", 12, Ui.SUB, false),
                        Ui.lp(this, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 3, 0, 0));
            }
            card.addView(info, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            Button del = Ui.button(this, "🗑", Ui.WHITE, Ui.RED);
            del.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    confirmDelete(it);
                }
            });
            card.addView(del, Ui.lp(this, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 8, 0, 0, 0));

            listBox.addView(card, Ui.lp(this, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, 8));
        }
    }

    private void confirmDelete(final SavedLinks.Item it) {
        String what = it.hasInfo ? it.panel + "\n" + it.user : it.key;
        new AlertDialog.Builder(this)
                .setTitle("Silinsin mi?")
                .setMessage(what)
                .setPositiveButton("Sil", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        SavedLinks.remove(SavedListActivity.this, Collections.singletonList(it.key));
                        refresh();
                    }
                })
                .setNegativeButton("İptal", null)
                .show();
    }

    private void confirmDeleteAll() {
        int n = SavedLinks.count(this);
        new AlertDialog.Builder(this)
                .setTitle("Hepsi silinsin mi?")
                .setMessage(n + " linkin hepsi silinecek. Emin misin?")
                .setPositiveButton("Hepsini sil", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        SavedLinks.clear(SavedListActivity.this);
                        refresh();
                    }
                })
                .setNegativeButton("İptal", null)
                .show();
    }
}
