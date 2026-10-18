package com.example.iptvcheckerapp;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Ekranları kodla çizerken kullanılan küçük yardımcılar. */
public final class Ui {
    private Ui() {}

    public static final int PRIMARY = 0xFF1F6FEB;
    public static final int BG = 0xFFF3F5F9;
    public static final int TEXT = 0xFF1B2330;
    public static final int SUB = 0xFF6B7686;
    public static final int GREEN = 0xFF1E9E5A;
    public static final int ORANGE = 0xFFE59A00;
    public static final int RED = 0xFFD64545;
    public static final int GRAY = 0xFF9AA4B2;
    public static final int LINE = 0xFFDDE2EA;
    public static final int WHITE = 0xFFFFFFFF;

    public static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    public static GradientDrawable rounded(Context c, int fill, int stroke, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(c, radiusDp));
        if (stroke != 0) g.setStroke(dp(c, 1), stroke);
        return g;
    }

    public static LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    public static LinearLayout.LayoutParams lp(Context c, int w, int h, float l, float t, float r, float b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(dp(c, l), dp(c, t), dp(c, r), dp(c, b));
        return p;
    }

    public static LinearLayout.LayoutParams weight(Context c, float l, float r) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        p.setMargins(dp(c, l), 0, dp(c, r), 0);
        return p;
    }

    public static LinearLayout card(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setBackground(rounded(c, WHITE, LINE, 14));
        int p = dp(c, 14);
        l.setPadding(p, p, p, p);
        return l;
    }

    public static TextView text(Context c, String s, int sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    public static Button button(Context c, String label, int fill, int textColor) {
        Button b = new Button(c);
        b.setAllCaps(false);
        b.setText(label);
        b.setTextSize(14);
        b.setTextColor(textColor);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setStateListAnimator(null);
        b.setFocusable(false);
        GradientDrawable shape = rounded(c, fill, fill == WHITE ? LINE : 0, 10);
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33000000), shape, null));
        int ph = dp(c, 12);
        int pv = dp(c, 10);
        b.setPadding(ph, pv, ph, pv);
        b.setGravity(Gravity.CENTER);
        return b;
    }

    public static TextView chip(Context c, String label) {
        TextView t = new TextView(c);
        t.setText(label);
        t.setTextSize(13);
        t.setGravity(Gravity.CENTER);
        int ph = dp(c, 12);
        int pv = dp(c, 7);
        t.setPadding(ph, pv, ph, pv);
        setChipSelected(c, t, false);
        return t;
    }

    public static void setChipSelected(Context c, TextView t, boolean selected) {
        if (selected) {
            t.setBackground(rounded(c, PRIMARY, PRIMARY, 18));
            t.setTextColor(WHITE);
        } else {
            t.setBackground(rounded(c, WHITE, LINE, 18));
            t.setTextColor(TEXT);
        }
    }
}
