package com.smartkeyboard.releve;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;

/**
 * Saisie rapide: recherche d'un compteur du tableau (chiffres), saisie de son nouvel index et enregistrement
 * dans le tableau. N'ecrit jamais dans l'application d'origine et n'utilise pas la connexion de saisie.
 */
final class QuickPanel extends LinearLayout {

    interface Host {
        void close();

        /** Apres enregistrement: message a afficher puis retour au clavier. */
        void saved(String msg, Meters.Row r, boolean placeAfterLast);

        void openInApp(String key);
    }

    private final Host host;
    private final float d;
    private final int cBg, cCard, cText, cDim, cAccent, cGreen, cRed, cKey, cAmber;
    private final StringBuilder q = new StringBuilder();
    private final StringBuilder nb = new StringBuilder();
    private Meters.Row sel = null;
    private boolean placeAfter = true;
    private boolean confirmLower = false;

    QuickPanel(Context c, Host host, boolean light, Meters.Row preselect) {
        super(c);
        this.host = host;
        d = c.getResources().getDisplayMetrics().density;
        if (light) {
            cBg = 0xFFE3E6EB;
            cCard = 0xFFFFFFFF;
            cText = 0xFF14161B;
            cDim = 0xFF6B7385;
            cAccent = 0xFF2F6BFF;
            cGreen = 0xFF17803F;
            cRed = 0xFFC62828;
            cKey = 0xFFFFFFFF;
            cAmber = 0xFFC77000;
        } else {
            cBg = 0xFF1B1C1F;
            cCard = 0xFF2B2C30;
            cText = 0xFFF1F2F5;
            cDim = 0xFF9097A8;
            cAccent = 0xFF4C8DFF;
            cGreen = 0xFF2FA866;
            cRed = 0xFFE5565A;
            cKey = 0xFF3A3C42;
            cAmber = 0xFFE59A1F;
        }
        setOrientation(VERTICAL);
        setBackgroundColor(cBg);
        sel = preselect;
        rebuild();
    }

    int heightPx() {
        return Math.round(300 * d);
    }

    // ---------------------------------------------------------------- petits outils
    private int dp(float v) {
        return Math.round(v * d);
    }

    private GradientDrawable shape(int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private TextView tv(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(getContext());
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        t.setIncludeFontPadding(false);
        return t;
    }

    private TextView button(String label, int bg, int fg, float sp, final Runnable a) {
        TextView t = tv(label, sp, fg, true);
        t.setGravity(Gravity.CENTER);
        t.setBackground(shape(bg, 12));
        t.setClickable(true);
        t.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                a.run();
            }
        });
        return t;
    }

    private LinearLayout.LayoutParams w(int wd, int ht, float weight) {
        return new LinearLayout.LayoutParams(wd, ht, weight);
    }

    // ---------------------------------------------------------------- interface
    private void rebuild() {
        removeAllViews();
        // barre du haut
        LinearLayout top = new LinearLayout(getContext());
        top.setOrientation(HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(12), 0, dp(6), 0);
        String title = sel == null ? "Saisie rapide" : sel.compteur;
        top.addView(tv(title, 16, cText, true), w(0, -2, 1f));
        if (sel != null) {
            top.addView(button("\u2039 Chercher", cCard, cText, 13, new Runnable() {
                @Override
                public void run() {
                    sel = null;
                    nb.setLength(0);
                    confirmLower = false;
                    rebuild();
                }
            }), w(dp(92), dp(30), 0f));
        }
        TextView x = button("\u2715", cCard, cText, 15, new Runnable() {
            @Override
            public void run() {
                host.close();
            }
        });
        LinearLayout.LayoutParams xl = w(dp(40), dp(30), 0f);
        xl.setMargins(dp(8), 0, 0, 0);
        top.addView(x, xl);
        addView(top, w(-1, dp(40), 0f));

        LinearLayout body = new LinearLayout(getContext());
        body.setOrientation(HORIZONTAL);
        body.setPadding(dp(8), 0, dp(8), dp(6));
        body.addView(sel == null ? searchSide() : enterSide(), w(0, -1, 1.25f));
        body.addView(pad(), w(0, -1, 1f));
        addView(body, w(-1, 0, 1f));
    }

    private View searchSide() {
        LinearLayout col = new LinearLayout(getContext());
        col.setOrientation(VERTICAL);
        col.setPadding(0, 0, dp(8), 0);
        TextView disp = tv(q.length() == 0 ? "N\u00B0 du compteur" : q.toString(), q.length() == 0 ? 16 : 26,
                q.length() == 0 ? cDim : cText, q.length() != 0);
        disp.setGravity(Gravity.CENTER_VERTICAL);
        disp.setPadding(dp(12), 0, dp(12), 0);
        disp.setBackground(shape(cCard, 12));
        col.addView(disp, w(-1, dp(46), 0f));
        ArrayList<Meters.Row> rs = QuickLogic.search(Meters.ROWS, q.toString(), 3);
        if (q.length() > 0 && rs.isEmpty()) {
            TextView none = tv("Aucun compteur trouv\u00E9", 13, cDim, false);
            none.setPadding(dp(4), dp(10), 0, 0);
            col.addView(none, w(-1, -2, 0f));
        }
        for (final Meters.Row r : rs) {
            LinearLayout card = new LinearLayout(getContext());
            card.setOrientation(VERTICAL);
            card.setPadding(dp(12), dp(7), dp(12), dp(7));
            card.setBackground(shape(cCard, 12));
            String line1 = r.compteur + (Meters.isOffApp(r) ? "   \u2709" : "");
            card.addView(tv(line1, 16, cText, true));
            String nm = Meters.nameOf(r);
            if (!nm.isEmpty()) card.addView(tv(nm, 12, cDim, false));
            card.addView(tv("Ancien : " + (r.oldIdx.isEmpty() ? "-" : r.oldIdx)
                    + (r.newIdx.isEmpty() ? "" : "   Nouveau : " + r.newIdx), 12, cDim, false));
            card.setClickable(true);
            card.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                    sel = r;
                    nb.setLength(0);
                    confirmLower = false;
                    rebuild();
                }
            });
            LinearLayout.LayoutParams lp = w(-1, -2, 0f);
            lp.setMargins(0, dp(6), 0, 0);
            col.addView(card, lp);
        }
        return col;
    }

    private View enterSide() {
        LinearLayout col = new LinearLayout(getContext());
        col.setOrientation(VERTICAL);
        col.setPadding(0, 0, dp(8), 0);
        LinearLayout card = new LinearLayout(getContext());
        card.setOrientation(VERTICAL);
        card.setPadding(dp(12), dp(6), dp(12), dp(6));
        card.setBackground(shape(cCard, 12));
        String nm = Meters.nameOf(sel);
        if (!nm.isEmpty()) card.addView(tv(nm, 13, cText, true));
        String ad = Meters.addressOf(sel);
        if (!ad.isEmpty()) card.addView(tv(ad, 11, cDim, false));
        card.addView(tv("Ancien index : " + (sel.oldIdx.isEmpty() ? "-" : sel.oldIdx), 13, cDim, false));
        col.addView(card, w(-1, -2, 0f));

        TextView disp = tv(nb.length() == 0 ? "Nouvel index" : nb.toString(), nb.length() == 0 ? 16 : 28,
                nb.length() == 0 ? cDim : cGreen, nb.length() != 0);
        disp.setGravity(Gravity.CENTER_VERTICAL);
        disp.setPadding(dp(12), 0, dp(12), 0);
        disp.setBackground(shape(cCard, 12));
        LinearLayout.LayoutParams dl = w(-1, dp(46), 0f);
        dl.setMargins(0, dp(6), 0, 0);
        col.addView(disp, dl);

        final boolean inApp = !Meters.isOffApp(sel) && sel.status != Meters.NODATA;
        final TextView chk = tv((placeAfter ? "\u2611 " : "\u2610 ") + "Apr\u00E8s le dernier compteur lu", 12, cText, false);
        chk.setPadding(dp(4), dp(6), 0, dp(4));
        chk.setClickable(true);
        chk.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                placeAfter = !placeAfter;
                rebuild();
            }
        });
        if (inApp) {
            TextView hint = tv("\u2714 Ce compteur est dans l'application : ouvrez-le avec App \u2197 et saisissez l'index l\u00E0-bas.",
                    12, cGreen, true);
            hint.setPadding(dp(4), dp(6), 0, dp(4));
            col.addView(hint, w(-1, -2, 0f));
        } else {
            col.addView(chk, w(-1, -2, 0f));
        }

        if (confirmLower) {
            TextView warn = tv("\u26A0 Inf\u00E9rieur \u00E0 l'ancien index. Appuyez encore sur Enregistrer pour confirmer.",
                    11, cRed, true);
            warn.setPadding(dp(4), 0, 0, dp(2));
            col.addView(warn, w(-1, -2, 0f));
        }
        LinearLayout acts = new LinearLayout(getContext());
        acts.setOrientation(HORIZONTAL);
        TextView save = button(confirmLower ? "Confirmer" : "Enregistrer",
                confirmLower ? cAmber : (inApp ? 0xFFF4F5F7 : cGreen),
                confirmLower ? 0xFFFFFFFF : (inApp ? 0xFF14161B : 0xFFFFFFFF), inApp ? 13 : 15,
                new Runnable() {
                    @Override
                    public void run() {
                        save();
                    }
                });
        if (inApp && !confirmLower) {
            GradientDrawable sg = shape(0xFFF4F5F7, 12);
            sg.setStroke(dp(1), 0xFFB9BEC9);
            save.setBackground(sg);
        }
        acts.addView(save, w(0, -1, 1f));
        final String key = sel.key;
        final TextView open = button("App \u2197", inApp ? 0xFF1DB954 : cCard, inApp ? 0xFFFFFFFF : cText,
                inApp ? 16 : 12, new Runnable() {
            @Override
            public void run() {
                host.openInApp(key);
            }
        });
        LinearLayout.LayoutParams ol = inApp ? w(0, -1, 1.3f) : w(dp(62), -1, 0f);
        ol.setMargins(dp(6), 0, 0, 0);
        acts.addView(open, ol);
        if (inApp) {
            GradientDrawable og = shape(0xFF1DB954, 12);
            og.setStroke(dp(3), 0xFF9BFFC4);   // halo vert
            open.setBackground(og);
            glow(open, true);
        }
        col.addView(acts, w(-1, 0, 1f));
        return col;
    }

    /** Pulsation lumineuse douce du bouton App (le compteur existe dans l'application d'origine). */
    private void glow(final View v, final boolean down) {
        v.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!v.isAttachedToWindow()) return;
                v.animate().alpha(down ? 0.55f : 1f).setDuration(600).withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        glow(v, !down);
                    }
                }).start();
            }
        }, 30);
    }

    private void save() {
        if (sel == null) return;
        int c = QuickLogic.check(sel, nb.toString());
        if (c == QuickLogic.EMPTY) return;
        if (c == QuickLogic.LOWER && !confirmLower) {
            confirmLower = true;
            rebuild();
            return;
        }
        String stamp = new java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale.FRANCE)
                .format(new java.util.Date());
        QuickLogic.apply(sel, nb.toString(), stamp);
        host.saved("\u2713 " + sel.compteur + " : " + sel.newIdx + " enregistr\u00E9", sel, placeAfter);
    }

    private View pad() {
        LinearLayout col = new LinearLayout(getContext());
        col.setOrientation(VERTICAL);
        String[][] keys = {{"1", "2", "3"}, {"4", "5", "6"}, {"7", "8", "9"}, {"\u232B", "0", "C"}};
        for (String[] row : keys) {
            LinearLayout r = new LinearLayout(getContext());
            r.setOrientation(HORIZONTAL);
            for (final String k : row) {
                int bg = (k.equals("\u232B") || k.equals("C")) ? cCard : cKey;
                TextView t = button(k, bg, cText, 22, new Runnable() {
                    @Override
                    public void run() {
                        press(k);
                    }
                });
                LinearLayout.LayoutParams lp = w(0, -1, 1f);
                lp.setMargins(dp(2), dp(2), dp(2), dp(2));
                r.addView(t, lp);
            }
            col.addView(r, w(-1, 0, 1f));
        }
        return col;
    }

    private void press(String k) {
        StringBuilder b = sel == null ? q : nb;
        if (k.equals("\u232B")) {
            if (b.length() > 0) b.setLength(b.length() - 1);
        } else if (k.equals("C")) {
            b.setLength(0);
        } else if (b.length() < 12) {
            b.append(k);
        }
        confirmLower = false;
        rebuild();
    }
}
