package com.smartkeyboard.releve;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Deux petites fenetres flottantes independantes (Ancien index / Nouvel index), deplacables au doigt,
 * posees par le service d'accessibilite au-dessus de l'application d'origine. Aucune autorisation en plus.
 * Tout est protege: en cas d'erreur la fenetre n'apparait simplement pas (le bandeau reste comme avant).
 */
final class FloatCards {
    static final int OLD = 0, NEW = 1, EXP = 2;
    /** 1 = ancien seul, 2 = nouveau seul, 3 = les deux. */
    private static final String K_ON = "fc_on", K_MODE = "fc_mode";

    private static final Handler UI = new Handler(Looper.getMainLooper());
    private static final View[] VIEW = new View[3];
    private static final TextView[] VAL = new TextView[3];
    private static final TextView[] LAB = new TextView[3];
    private static final WindowManager.LayoutParams[] LP = new WindowManager.LayoutParams[3];
    private static final boolean[] SHOWN = new boolean[3];
    private static boolean suspended = false;
    private static String tOld = "", tNew = "", tExp = "";
    private static int flagOld = 0;
    private static boolean sus = false;
    /** Echelle choisie au pincement (0,6 a 2,2), commune aux trois fenetres. */
    private static float scale = -1f;
    private static final float S_MIN = 0.6f, S_MAX = 2.2f;

    private static float scaleOf(Context c) {
        if (scale < 0) {
            try {
                scale = prefs(c).getFloat("fc_scale", 1f);
            } catch (Throwable t) {
                scale = 1f;
            }
            scale = Math.max(S_MIN, Math.min(S_MAX, scale));
        }
        return scale;
    }

    private static void applyScale(Context c) {
        float sc = scaleOf(c);
        float d = c.getResources().getDisplayMetrics().density;
        for (int i = 0; i < 3; i++) {
            if (VIEW[i] == null) continue;
            LAB[i].setTextSize(9 * sc);
            VAL[i].setTextSize(20 * sc);
            VIEW[i].setPadding((int) (7 * d * sc), (int) (3 * d * sc), (int) (8 * d * sc), (int) (3 * d * sc));
            VIEW[i].requestLayout();
        }
    }

    private FloatCards() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("cfg", Context.MODE_PRIVATE);
    }

    static boolean isOn(Context c) {
        try {
            return prefs(c).getBoolean(K_ON, false);
        } catch (Throwable t) {
            return false;
        }
    }

    static int mode(Context c) {
        try {
            int m = prefs(c).getInt(K_MODE, 7);
            return m < 1 || m > 7 ? 7 : m;
        } catch (Throwable t) {
            return 7;
        }
    }

    /** Vrai si cette fenetre est reellement affichee (le bandeau masque alors la meme cellule). */
    static boolean covers(int card) {
        return SHOWN[card] && VIEW[card] != null && VIEW[card].isAttachedToWindow();
    }

    /** Le service d'accessibilite (re)demarre: ses fenetres n'existent plus, on repart propre. */
    static void reset() {
        try {
            ScanService s = ScanService.instance;
            for (int i = 0; i < 3; i++) if (s != null && SHOWN[i]) remove(s, i);
        } catch (Throwable ignored) {
        }
        for (int i = 0; i < 3; i++) {
            SHOWN[i] = false;
            VIEW[i] = null;
        }
        suspended = false;
    }

    private static boolean ticking = false;
    /** Vrai quand un autre ecran que l'application d'origine est au premier plan (reglages, autre appli...). */
    private static boolean awayFromApp = false;
    private static final Runnable TICK = new Runnable() {
        @Override
        public void run() {
            ticking = false;
            try {
                ScanService s = ScanService.instance;
                if (s == null || !isOn(s)) return;
                int front = s.originalAppFront();
                if (front >= 0) awayFromApp = front == 0;
                boolean busyNow = ScanService.autoRunning || awayFromApp;
                if (busyNow != suspended) {
                    suspended = busyNow;
                    refresh();
                }
            } catch (Throwable ignored) {
            }
            startTick();
        }
    };

    private static void startTick() {
        if (ticking) return;
        ticking = true;
        UI.postDelayed(TICK, 300);
    }

    static String modeName(int m) {
        return m == 1 ? "Ancien seul" : (m == 2 ? "Nouveau seul" : (m == 3 ? "Ancien + Nouveau" : "Ancien + Nouveau + Attendu"));
    }

    /** Bouton du bandeau, appui court. Retourne le nouvel etat. */
    static boolean toggle(Context c) {
        boolean on = !isOn(c);
        try {
            prefs(c).edit().putBoolean(K_ON, on).apply();
        } catch (Throwable ignored) {
        }
        refresh();
        return on;
    }

    /** Bouton du bandeau, appui long: Ancien + Nouveau -> Ancien seul -> Nouveau seul. */
    static int cycleMode(Context c) {
        int m = mode(c);
        int nx = m == 7 ? 3 : (m == 3 ? 1 : (m == 1 ? 2 : 7));
        try {
            prefs(c).edit().putInt(K_MODE, nx).putBoolean(K_ON, true).apply();
        } catch (Throwable ignored) {
        }
        refresh();
        return nx;
    }

    /** Masque/reaffiche les fenetres (utilise par la surveillance de l'automatisation). */
    static void setSuspended(boolean s) {
        suspended = s;
        refresh();
    }

    /** Index attendu (ancien + consommation habituelle), comme SmartRV; vide = rien a afficher. */
    static void updateExpected(String text) {
        String t = text == null ? "" : text;
        if (t.equals(tExp)) return;
        boolean toggled = t.isEmpty() != tExp.isEmpty();
        tExp = t;
        if (toggled) refresh();
        else UI.post(FloatCards::paint);
    }

    static void update(String old, String nw, int oldFlag, boolean suspect) {
        String o = old == null ? "" : old;
        String n = nw == null ? "" : nw;
        if (o.equals(tOld) && n.equals(tNew) && oldFlag == flagOld && suspect == sus) return;
        tOld = o;
        tNew = n;
        flagOld = oldFlag;
        sus = suspect;
        UI.post(FloatCards::paint);
    }

    /** Aligne les fenetres sur l'etat voulu (on/off, mode, suspension). */
    static void refresh() {
        startTick();
        UI.post(() -> {
            try {
                ScanService s = ScanService.instance;
                boolean want = s != null && isOn(s) && !suspended;
                int m = s == null ? 3 : mode(s);
                for (int i = 0; i < 3; i++) {
                    boolean w = want && (m & (1 << i)) != 0 && (i != EXP || !tExp.isEmpty());
                    if (w && !SHOWN[i]) add(s, i);
                    else if (!w && SHOWN[i]) remove(s, i);
                }
                paint();
            } catch (Throwable ignored) {
            }
            notifyStrip();
        });
    }

    private static void notifyStrip() {
        try {
            KeyboardService k = KeyboardService.instance;
            if (k != null) k.relayoutStrip();
        } catch (Throwable ignored) {
        }
    }

    private static void add(final ScanService s, final int i) {
        try {
            final WindowManager wm = (WindowManager) s.getSystemService(Context.WINDOW_SERVICE);
            final float d = s.getResources().getDisplayMetrics().density;
            LinearLayout box = new LinearLayout(s);
            box.setOrientation(LinearLayout.HORIZONTAL);
            box.setGravity(Gravity.CENTER_VERTICAL);
            TextView lab = new TextView(s);
            lab.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            lab.setLetterSpacing(0.06f);
            lab.setGravity(Gravity.CENTER);
            lab.setIncludeFontPadding(false);
            lab.setText(i == OLD ? "old" : (i == NEW ? "new" : "exp"));
            lab.setPadding((int) (5 * d), (int) (1 * d), (int) (5 * d), (int) (1 * d));
            TextView val = new TextView(s);
            val.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
            val.setGravity(Gravity.CENTER);
            val.setIncludeFontPadding(false);
            box.addView(lab, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            vlp.leftMargin = (int) (6 * d);
            box.addView(val, vlp);
            VIEW[i] = box;
            LAB[i] = lab;
            VAL[i] = val;

            DisplayMetrics dm = new DisplayMetrics();
            wm.getDefaultDisplay().getRealMetrics(dm);
            final WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            SharedPreferences p = prefs(s);
            lp.x = p.getInt("fc_x" + i, (int) ((8 + i * 92) * d));
            lp.y = p.getInt("fc_y" + i, (int) (70 * d));
            lp.x = Math.max(0, Math.min(lp.x, dm.widthPixels - (int) (60 * d)));
            lp.y = Math.max(0, Math.min(lp.y, dm.heightPixels - (int) (60 * d)));
            LP[i] = lp;
            applyScale(s);

            final android.view.ScaleGestureDetector sg = new android.view.ScaleGestureDetector(s,
                    new android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
                        @Override
                        public boolean onScale(android.view.ScaleGestureDetector det) {
                            scale = Math.max(S_MIN, Math.min(S_MAX, scaleOf(s) * det.getScaleFactor()));
                            applyScale(s);
                            return true;
                        }

                        @Override
                        public void onScaleEnd(android.view.ScaleGestureDetector det) {
                            try {
                                prefs(s).edit().putFloat("fc_scale", scale).apply();
                            } catch (Throwable ignored) {
                            }
                        }
                    });
            box.setOnTouchListener(new View.OnTouchListener() {
                float rx, ry;
                int sx, sy;
                boolean rebase = false;

                @Override
                public boolean onTouch(View v, MotionEvent e) {
                    try {
                        sg.onTouchEvent(e);
                        if (e.getPointerCount() > 1 || sg.isInProgress()) {
                            rebase = true;
                            return true;
                        }
                        if (rebase && e.getActionMasked() == MotionEvent.ACTION_MOVE) {
                            rx = e.getRawX();
                            ry = e.getRawY();
                            sx = lp.x;
                            sy = lp.y;
                            rebase = false;
                        }
                        switch (e.getActionMasked()) {
                            case MotionEvent.ACTION_DOWN:
                                rx = e.getRawX();
                                ry = e.getRawY();
                                sx = lp.x;
                                sy = lp.y;
                                return true;
                            case MotionEvent.ACTION_MOVE: {
                                DisplayMetrics m2 = new DisplayMetrics();
                                wm.getDefaultDisplay().getRealMetrics(m2);
                                lp.x = Math.max(0, Math.min(m2.widthPixels - v.getWidth(),
                                        sx + (int) (e.getRawX() - rx)));
                                lp.y = Math.max(0, Math.min(m2.heightPixels - v.getHeight(),
                                        sy + (int) (e.getRawY() - ry)));
                                wm.updateViewLayout(v, lp);
                                return true;
                            }
                            case MotionEvent.ACTION_UP:
                            case MotionEvent.ACTION_CANCEL:
                                prefs(s).edit().putInt("fc_x" + i, lp.x).putInt("fc_y" + i, lp.y).apply();
                                return true;
                            default:
                                return true;
                        }
                    } catch (Throwable t) {
                        return true;
                    }
                }
            });
            wm.addView(box, lp);
            SHOWN[i] = true;
        } catch (Throwable t) {
            SHOWN[i] = false;
            VIEW[i] = null;
        }
    }

    private static void remove(ScanService s, int i) {
        try {
            if (VIEW[i] != null) {
                ((WindowManager) s.getSystemService(Context.WINDOW_SERVICE)).removeViewImmediate(VIEW[i]);
            }
        } catch (Throwable ignored) {
        }
        VIEW[i] = null;
        SHOWN[i] = false;
    }

    private static void paint() {
        try {
            for (int i = 0; i < 3; i++) {
                if (VIEW[i] == null || !SHOWN[i]) continue;
                String t = i == OLD ? tOld : (i == NEW ? tNew : tExp);
                int bg, tx;
                if (i == EXP) {
                    bg = 0xFFE6E9EC;
                    tx = 0xFF5B6770;
                } else if (i == OLD && flagOld == 2) {
                    bg = 0xFFD32F2F;
                    tx = 0xFFFFFFFF;
                } else if ((i == OLD && flagOld == 1) || (i == NEW && sus)) {
                    bg = 0xFFFFE0B2;
                    tx = 0xFF4A2200;
                } else if (i == OLD) {
                    bg = 0xFFDCEBFF;
                    tx = 0xFF0B3A6B;
                } else {
                    bg = 0xFFD9F5E3;
                    tx = 0xFF0B5A2B;
                }
                float dn = VIEW[i].getResources().getDisplayMetrics().density;
                GradientDrawable g = new GradientDrawable();
                g.setColor(bg);
                g.setCornerRadius(dn * 12);
                g.setStroke((int) Math.max(1, dn), 0x22000000);
                VIEW[i].setBackground(g);
                VIEW[i].setAlpha(0.96f);
                GradientDrawable pill = new GradientDrawable();
                pill.setColor((tx & 0x00FFFFFF) | 0x26000000);
                pill.setCornerRadius(dn * 6);
                LAB[i].setBackground(pill);
                LAB[i].setTextColor(tx);
                VAL[i].setTextColor(tx);
                VAL[i].setText(t.isEmpty() ? "-" : t);
            }
        } catch (Throwable ignored) {
        }
    }
}
