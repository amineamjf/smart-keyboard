package com.smartkeyboard.releve;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Page "Plus d'options": cartes sombres avec icones vectorielles (dessin canvas) et interrupteurs. */
final class OptUi {
    private OptUi() {
    }

    static final int BG = 0xFF0F1115, CARD = 0xFF181B22, CARD_DOWN = 0xFF20242D, BORDER = 0xFF242934,
            TXT = 0xFFF0F2F7, SUB = 0xFF7D8596, SEC = 0xFF6A7183, LINE = 0xFF22262F,
            BLUE = 0xFF5B9CFF, AMBER = 0xFFFFB547, GREY = 0xFF9AA4BC, TEAL = 0xFF2DD4BF,
            GREEN = 0xFF3DDC97, RED = 0xFFFF6B6B;

    static final int ROUTE = 0, AFTER = 1, UNDO = 2, LEARN = 3, SIGMA = 4, CHART = 5, PLUS = 6, DL = 7,
            CLOCK = 8, KB = 9, EYE = 10, BOLT = 11, BACK = 12, PLAY = 13, CHECK = 14, CROSS = 15,
            UP = 16, DOWN = 17, HASH = 18, RIGHT = 19, SUN = 20, CAMERA = 21, SEARCH = 22;

    /** Icone "ligne" sur une grille de 24 unites (meme trace que la maquette). */
    static final class Icon extends View {
        private final int id;
        private final int color;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        Icon(Context c, int id, int color) {
            super(c);
            this.id = id;
            this.color = color;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
        }

        private void ln(Canvas c, float s, float a, float b, float x, float y) {
            c.drawLine(a * s, b * s, x * s, y * s, p);
        }

        private void poly(Canvas c, float s, float... v) {
            path.reset();
            path.moveTo(v[0] * s, v[1] * s);
            for (int i = 2; i + 1 < v.length; i += 2) path.lineTo(v[i] * s, v[i + 1] * s);
            c.drawPath(path, p);
        }

        private void circ(Canvas c, float s, float x, float y, float r) {
            c.drawCircle(x * s, y * s, r * s, p);
        }

        private void dot(Canvas c, float s, float x, float y) {
            p.setStyle(Paint.Style.FILL);
            c.drawCircle(x * s, y * s, 1.1f * s, p);
            p.setStyle(Paint.Style.STROKE);
        }

        @Override
        protected void onDraw(Canvas c) {
            float s = Math.min(getWidth(), getHeight()) / 24f;
            p.setColor(color);
            p.setStrokeWidth(2f * s);
            switch (id) {
                case ROUTE:
                    ln(c, s, 8, 6, 21, 6);
                    ln(c, s, 8, 12, 21, 12);
                    ln(c, s, 8, 18, 21, 18);
                    circ(c, s, 3.5f, 6, 1);
                    circ(c, s, 3.5f, 12, 1);
                    circ(c, s, 3.5f, 18, 1);
                    break;
                case AFTER:
                case DL:
                    ln(c, s, 12, 3, 12, 15);
                    poly(c, s, 7, 10, 12, 15, 17, 10);
                    ln(c, s, 5, 21, 19, 21);
                    break;
                case UNDO:
                    poly(c, s, 9, 14, 4, 9, 9, 4);
                    path.reset();
                    path.moveTo(4 * s, 9 * s);
                    path.lineTo(14.5f * s, 9 * s);
                    path.arcTo(new RectF(9 * s, 9 * s, 20 * s, 20 * s), -90, 180);
                    path.lineTo(11 * s, 20 * s);
                    c.drawPath(path, p);
                    break;
                case LEARN:
                    poly(c, s, 16, 3, 21, 3, 21, 8);
                    ln(c, s, 4, 20, 21, 3);
                    poly(c, s, 21, 16, 21, 21, 16, 21);
                    ln(c, s, 15, 15, 21, 21);
                    ln(c, s, 4, 4, 9, 9);
                    break;
                case SIGMA:
                    poly(c, s, 18, 7, 18, 4, 6, 4, 12, 12, 6, 20, 18, 20, 18, 17);
                    break;
                case CHART:
                    poly(c, s, 3, 3, 3, 21, 21, 21);
                    ln(c, s, 7, 15, 7, 18);
                    ln(c, s, 12, 9, 12, 18);
                    ln(c, s, 17, 5, 17, 18);
                    break;
                case PLUS:
                    ln(c, s, 12, 5, 12, 19);
                    ln(c, s, 5, 12, 19, 12);
                    break;
                case CLOCK:
                    circ(c, s, 12, 12, 9);
                    poly(c, s, 12, 7, 12, 12, 15, 14);
                    break;
                case KB:
                    c.drawRoundRect(new RectF(2 * s, 6 * s, 22 * s, 18 * s), 2.5f * s, 2.5f * s, p);
                    dot(c, s, 6, 10);
                    dot(c, s, 10, 10);
                    dot(c, s, 14, 10);
                    dot(c, s, 18, 10);
                    ln(c, s, 7, 14, 17, 14);
                    break;
                case EYE:
                    path.reset();
                    path.moveTo(2 * s, 12 * s);
                    path.cubicTo(2 * s, 12 * s, 5.6f * s, 5 * s, 12 * s, 5 * s);
                    path.cubicTo(18.4f * s, 5 * s, 22 * s, 12 * s, 22 * s, 12 * s);
                    path.cubicTo(22 * s, 12 * s, 18.4f * s, 19 * s, 12 * s, 19 * s);
                    path.cubicTo(5.6f * s, 19 * s, 2 * s, 12 * s, 2 * s, 12 * s);
                    path.close();
                    c.drawPath(path, p);
                    circ(c, s, 12, 12, 3);
                    break;
                case BOLT:
                    poly(c, s, 13, 2, 4, 14, 11, 14, 10, 22, 19, 10, 12, 10, 13, 2);
                    break;
                case BACK:
                    ln(c, s, 19, 12, 5, 12);
                    poly(c, s, 11, 6, 5, 12, 11, 18);
                    break;
                case RIGHT:
                    ln(c, s, 5, 12, 19, 12);
                    poly(c, s, 13, 6, 19, 12, 13, 18);
                    break;
                case PLAY:
                    poly(c, s, 7, 4.5f, 19, 12, 7, 19.5f, 7, 4.5f);
                    break;
                case CHECK:
                    poly(c, s, 4, 12.5f, 9.5f, 18, 20, 6.5f);
                    break;
                case CROSS:
                    ln(c, s, 6, 6, 18, 18);
                    ln(c, s, 18, 6, 6, 18);
                    break;
                case UP:
                    ln(c, s, 12, 19, 12, 5);
                    poly(c, s, 6, 11, 12, 5, 18, 11);
                    break;
                case DOWN:
                    ln(c, s, 12, 5, 12, 19);
                    poly(c, s, 6, 13, 12, 19, 18, 13);
                    break;
                case HASH:
                    ln(c, s, 4, 9, 20, 9);
                    ln(c, s, 4, 15, 20, 15);
                    ln(c, s, 10, 3, 8, 21);
                    ln(c, s, 16, 3, 14, 21);
                    break;
                case SUN:
                    circ(c, s, 12, 12, 4);
                    ln(c, s, 12, 2, 12, 4);
                    ln(c, s, 12, 20, 12, 22);
                    ln(c, s, 2, 12, 4, 12);
                    ln(c, s, 20, 12, 22, 12);
                    ln(c, s, 4.9f, 4.9f, 6.3f, 6.3f);
                    ln(c, s, 17.7f, 17.7f, 19.1f, 19.1f);
                    ln(c, s, 4.9f, 19.1f, 6.3f, 17.7f);
                    ln(c, s, 17.7f, 6.3f, 19.1f, 4.9f);
                    break;
                case SEARCH:
                    circ(c, s, 10.5f, 10.5f, 6.5f);
                    ln(c, s, 15.5f, 15.5f, 21, 21);
                    break;
                case CAMERA:
                    c.drawRoundRect(new RectF(2 * s, 7 * s, 22 * s, 20 * s), 3 * s, 3 * s, p);
                    circ(c, s, 12, 13.5f, 3.5f);
                    poly(c, s, 8, 7, 9.5f, 4, 14.5f, 4, 16, 7);
                    break;
                default:
                    break;
            }
        }
    }

    /** Interrupteur (piste 38x22, pastille blanche). */
    static final class Switch extends View {
        private final boolean on;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        Switch(Context c, boolean on) {
            super(c);
            this.on = on;
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            p.setColor(on ? GREEN : 0xFF3A4050);
            c.drawRoundRect(new RectF(0, 0, w, h), h / 2, h / 2, p);
            p.setColor(0xFFFFFFFF);
            float r = h * 0.36f, m = h * 0.14f + r;
            c.drawCircle(on ? w - m : m, h / 2, r, p);
        }
    }

    private static GradientDrawable box(int fill, int stroke, float radiusPx, int strokePx) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(radiusPx);
        if (strokePx > 0) g.setStroke(strokePx, stroke);
        return g;
    }

    /** Page defilante: titres de section + cartes sur 2 colonnes. */
    static final class Page {
        final Context ctx;
        final float d;
        final LinearLayout box;
        private LinearLayout row;

        Page(Context c) {
            ctx = c;
            d = c.getResources().getDisplayMetrics().density;
            box = new LinearLayout(c);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setBackgroundColor(BG);
            box.setPadding(dp(10), dp(2), dp(10), dp(10));
        }

        int dp(float v) {
            return Math.round(v * d);
        }

        void section(String t) {
            row = null;
            TextView v = new TextView(ctx);
            v.setText(t.toUpperCase(java.util.Locale.FRANCE));
            v.setTextSize(11.5f);
            v.setTypeface(Typeface.DEFAULT_BOLD);
            v.setLetterSpacing(0.14f);
            v.setTextColor(SEC);
            v.setPadding(dp(8), dp(14), 0, dp(7));
            box.addView(v, new LinearLayout.LayoutParams(-1, -2));
        }

        /** Carte de resultat (texte libre); un toucher la ferme. */
        void note(String title, String text, final Runnable onTap) {
            row = null;
            LinearLayout c = new LinearLayout(ctx);
            c.setOrientation(LinearLayout.VERTICAL);
            c.setPadding(dp(14), dp(12), dp(14), dp(12));
            c.setBackground(box(0xFF14233A, BLUE, dp(18), dp(1)));
            TextView t = new TextView(ctx);
            t.setText(title);
            t.setTextSize(14);
            t.setTypeface(Typeface.DEFAULT_BOLD);
            t.setTextColor(BLUE);
            c.addView(t);
            TextView b = new TextView(ctx);
            b.setText(text);
            b.setTextSize(13);
            b.setTextColor(TXT);
            b.setLineSpacing(0, 1.15f);
            b.setPadding(0, dp(6), 0, dp(4));
            c.addView(b);
            TextView h = new TextView(ctx);
            h.setText("Toucher pour fermer");
            h.setTextSize(11);
            h.setTextColor(SUB);
            c.addView(h);
            c.setOnClickListener(v -> {
                if (onTap != null) onTap.run();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.setMargins(dp(4), dp(8), dp(4), dp(4));
            box.addView(c, lp);
        }

        /** Carte pleine largeur. */
        void full(int icon, int accent, String title, String sub, int sw, Runnable a) {
            row = null;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.setMargins(dp(4), dp(4), dp(4), dp(4));
            box.addView(make(icon, accent, title, sub, sw, a), lp);
        }

        /** Carte demi-largeur (2 par ligne). sw: -1 aucun, 0 coupe, 1 actif. */
        void card(int icon, int accent, String title, String sub, int sw, Runnable a) {
            if (row == null || row.getChildCount() >= 2) {
                row = new LinearLayout(ctx);
                row.setOrientation(LinearLayout.HORIZONTAL);
                box.addView(row, new LinearLayout.LayoutParams(-1, -2));
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1f);
            lp.setMargins(dp(4), dp(4), dp(4), dp(4));
            row.addView(make(icon, accent, title, sub, sw, a), lp);
        }

        private View make(int icon, int accent, String title, String sub, int sw, final Runnable a) {
            LinearLayout c = new LinearLayout(ctx);
            c.setOrientation(LinearLayout.HORIZONTAL);
            c.setGravity(Gravity.CENTER_VERTICAL);
            c.setPadding(dp(10), dp(9), dp(10), dp(9));
            c.setMinimumHeight(dp(62));
            StateListDrawable sl = new StateListDrawable();
            sl.addState(new int[]{android.R.attr.state_pressed}, box(CARD_DOWN, BORDER, dp(18), dp(1)));
            sl.addState(new int[]{}, box(CARD, BORDER, dp(18), dp(1)));
            c.setBackground(sl);

            LinearLayout ib = new LinearLayout(ctx);
            ib.setGravity(Gravity.CENTER);
            ib.setBackground(box((accent & 0x00FFFFFF) | 0x22000000, 0, dp(14), 0));
            ib.addView(new Icon(ctx, icon, accent), new LinearLayout.LayoutParams(dp(22), dp(22)));
            c.addView(ib, new LinearLayout.LayoutParams(dp(42), dp(42)));

            LinearLayout tx = new LinearLayout(ctx);
            tx.setOrientation(LinearLayout.VERTICAL);
            tx.setPadding(dp(11), 0, dp(4), 0);
            TextView t = new TextView(ctx);
            t.setText(title);
            t.setTextSize(14);
            t.setTypeface(Typeface.DEFAULT_BOLD);
            t.setTextColor(TXT);
            t.setIncludeFontPadding(false);
            t.setMaxLines(2);
            tx.addView(t);
            if (sub != null && !sub.isEmpty()) {
                TextView s = new TextView(ctx);
                s.setText(sub);
                s.setTextSize(11.5f);
                s.setTextColor(SUB);
                s.setIncludeFontPadding(false);
                s.setPadding(0, dp(3), 0, 0);
                s.setMaxLines(2);
                tx.addView(s);
            }
            c.addView(tx, new LinearLayout.LayoutParams(0, -2, 1f));
            if (sw >= 0) {
                c.addView(new Switch(ctx, sw == 1), new LinearLayout.LayoutParams(dp(38), dp(22)));
            }
            c.setClickable(true);
            c.setOnClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                if (a != null) a.run();
            });
            return c;
        }
    }

    /** Barre d'onglets: fleche retour + 4 onglets soulignes. */
    static View tabs(Context ctx, int sel, String[] names, final Runnable back, final Runnable[] go) {
        final float d = ctx.getResources().getDisplayMetrics().density;
        LinearLayout bar = new LinearLayout(ctx);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(BG);
        bar.setPadding(Math.round(10 * d), 0, Math.round(6 * d), 0);
        FrameBack b = new FrameBack(ctx);
        b.addView(new Icon(ctx, BACK, 0xFFAAB2C5), new android.widget.FrameLayout.LayoutParams(
                Math.round(22 * d), Math.round(22 * d), Gravity.CENTER));
        b.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            back.run();
        });
        bar.addView(b, new LinearLayout.LayoutParams(Math.round(44 * d), Math.round(44 * d)));
        for (int i = 0; i < names.length; i++) {
            final int k = i;
            LinearLayout col = new LinearLayout(ctx);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.CENTER_HORIZONTAL);
            TextView t = new TextView(ctx);
            t.setText(names[i]);
            t.setTextSize(14);
            t.setTypeface(Typeface.DEFAULT_BOLD);
            t.setTextColor(i == sel ? 0xFFFFFFFF : SUB);
            t.setGravity(Gravity.CENTER);
            col.addView(t, new LinearLayout.LayoutParams(-2, Math.round(41 * d)));
            View u = new View(ctx);
            u.setBackground(box(i == sel ? BLUE : 0, 0, Math.round(2 * d), 0));
            col.addView(u, new LinearLayout.LayoutParams(-1, Math.round(3 * d)));
            col.setPadding(Math.round(8 * d), 0, Math.round(8 * d), 0);
            col.setOnClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                go[k].run();
            });
            bar.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        LinearLayout out = new LinearLayout(ctx);
        out.setOrientation(LinearLayout.VERTICAL);
        out.addView(bar, new LinearLayout.LayoutParams(-1, -2));
        View line = new View(ctx);
        line.setBackgroundColor(LINE);
        out.addView(line, new LinearLayout.LayoutParams(-1, Math.max(1, Math.round(d))));
        return out;
    }

    private static final class FrameBack extends android.widget.FrameLayout {
        FrameBack(Context c) {
            super(c);
            setClickable(true);
        }
    }
}
