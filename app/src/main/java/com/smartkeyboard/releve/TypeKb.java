package com.smartkeyboard.releve;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;

/**
 * Clavier de saisie "style Samsung": arabe / francais / chiffres / symboles, bulle d'apercu,
 * appui long (variantes + tachkil), suppression continue, curseur par glissement sur la barre d'espace,
 * et bandeau de suggestions (completion, correction, apprentissage). Dessine tout sur un seul Canvas.
 */
final class TypeKb extends View {

    interface Host {
        InputConnection ic();

        void enter();

        void toReleve();

        void pickKeyboard();

        boolean light();
    }

    // ---------------------------------------------------------------- moteur partage
    private static final Suggest ENGINE = new Suggest();
    private static volatile boolean engineReady = false;
    private static volatile boolean tableLoaded = false;
    private static final Object LOCK = new Object();

    private static void initEngine(final Context app) {
        synchronized (LOCK) {
            if (engineReady) return;
            ENGINE.loadBase(Suggest.FR, Words.FR);
            ENGINE.loadBase(Suggest.AR, Words.AR);
            engineReady = true;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    for (String lang : new String[]{Suggest.FR, Suggest.AR}) {
                        java.io.File f = new java.io.File(app.getFilesDir(), "learn_" + lang + ".txt");
                        if (f.exists()) {
                            byte[] b = new byte[(int) Math.min(f.length(), 2000000)];
                            java.io.FileInputStream in = new java.io.FileInputStream(f);
                            int n = 0;
                            while (n < b.length) {
                                int r = in.read(b, n, b.length - n);
                                if (r < 0) break;
                                n += r;
                            }
                            in.close();
                            synchronized (LOCK) {
                                ENGINE.restore(lang, new String(b, 0, n, "UTF-8"));
                            }
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        }).start();
    }

    /** Ajoute les noms et adresses du tableau (une seule fois par session) pour les completer ensuite. */
    private static void loadTableWords(final Context app) {
        if (tableLoaded) return;
        tableLoaded = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    ScanService.ensureLoaded(app);
                    HashSet<String> words = new HashSet<>();
                    for (Meters.Row r : Meters.ROWS) {
                        addWords(words, r.client);
                        addWords(words, r.address);
                    }
                    synchronized (LOCK) {
                        ENGINE.learnBulk(Suggest.FR, words, 2);
                    }
                } catch (Throwable ignored) {
                }
            }
        }).start();
    }

    private static void addWords(HashSet<String> out, String text) {
        if (text == null) return;
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i <= text.length(); i++) {
            char c = i < text.length() ? text.charAt(i) : ' ';
            if (Character.isLetter(c) && c < 0x0600) {
                cur.append(c);
            } else {
                if (cur.length() >= 3) {
                    String w = cur.toString().toLowerCase(java.util.Locale.FRENCH);
                    out.add(Character.toUpperCase(w.charAt(0)) + w.substring(1));
                }
                cur.setLength(0);
            }
        }
    }

    private static final Handler SAVE_H = new Handler(Looper.getMainLooper());

    private void scheduleSave() {
        SAVE_H.removeCallbacks(saveRun);
        SAVE_H.postDelayed(saveRun, 6000);
    }

    private final Runnable saveRun = new Runnable() {
        @Override
        public void run() {
            final Context app = getContext().getApplicationContext();
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        for (String lang : new String[]{Suggest.FR, Suggest.AR}) {
                            String t;
                            synchronized (LOCK) {
                                t = ENGINE.export(lang);
                            }
                            java.io.File f = new java.io.File(app.getFilesDir(), "learn_" + lang + ".txt");
                            java.io.File tmp = new java.io.File(app.getFilesDir(), "learn_" + lang + ".tmp");
                            java.io.FileOutputStream o = new java.io.FileOutputStream(tmp);
                            o.write(t.getBytes("UTF-8"));
                            o.close();
                            tmp.renameTo(f);
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }).start();
        }
    };

    // ---------------------------------------------------------------- touches et pages
    private static final int T_CHAR = 0, T_SHIFT = 1, T_DEL = 2, T_SPACE = 3, T_ENTER = 4, T_PAGE = 5, T_LANG = 6;
    private static final int P_LET = 0, P_ALT = 1, P_SYM1 = 2, P_SYM2 = 3, P_NUM = 4;
    private static final int SH_OFF = 0, SH_ON = 1, SH_LOCK = 2;

    private static final class Key {
        int type = T_CHAR;
        String label = "";
        String out = "";
        float w = 1f;
        int target;
        float l, t, r, b;          // rectangle visible
        float hl, ht, hr, hb;      // zone de toucher (sans interstice)
    }

    private static final Typeface TF_MED = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    private static final Typeface TF_BOLD = Typeface.DEFAULT_BOLD;

    private final Host host;
    private final float d;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rf = new RectF();
    private final Path path = new Path();
    private final Handler h = new Handler(Looper.getMainLooper());

    private String lang = Suggest.AR;
    private int page = P_LET;
    private int shift = SH_OFF;
    private long lastShiftTap = 0;
    private final ArrayList<ArrayList<Key>> rows = new ArrayList<>();
    private boolean numberRow = true;
    private boolean noSuggest = false;
    private boolean wantAutoCap = true;
    private boolean hapticOn = true;

    // geometrie
    private final float stripH, barH, padX, gap;
    private float rowH, numH, rowsTop;

    // suggestions
    private final String[] sug = new String[3];
    private String curPrefix = "";
    private String curPrev = "";

    // palette
    private int cBg, cStrip, cKey, cSpecial, cPress, cText, cDim, cAccent, cPop;

    TypeKb(Context c, Host host) {
        super(c);
        this.host = host;
        d = c.getResources().getDisplayMetrics().density;
        stripH = 44 * d;
        barH = 22 * d;
        padX = 3 * d;
        gap = 3.5f * d;
        initEngine(c.getApplicationContext());
        loadTableWords(c.getApplicationContext());
        try {
            lang = c.getSharedPreferences("cfg", Context.MODE_PRIVATE).getString("typeLang", Suggest.AR);
        } catch (Throwable ignored) {
        }
        if (!Suggest.FR.equals(lang) && !Suggest.AR.equals(lang)) lang = Suggest.AR;
        applyPalette();
        buildRows();
    }

    private void applyPalette() {
        if (host.light()) {
            cBg = 0xFFE3E6EB;
            cStrip = 0xFFF1F3F6;
            cKey = 0xFFFFFFFF;
            cSpecial = 0xFFCBD0D8;
            cPress = 0xFFB4BBC7;
            cText = 0xFF14161B;
            cDim = 0xFF7A8294;
            cAccent = 0xFF2F6BFF;
            cPop = 0xFFFFFFFF;
        } else {
            cBg = 0xFF1B1C1F;
            cStrip = 0xFF25262A;
            cKey = 0xFF3A3C42;
            cSpecial = 0xFF2B2C30;
            cPress = 0xFF5B5E68;
            cText = 0xFFF1F2F5;
            cDim = 0xFF8B92A5;
            cAccent = 0xFF4C8DFF;
            cPop = 0xFF4A4D56;
        }
    }

    /** A appeler a chaque affichage du clavier dans un champ. */
    void start(EditorInfo ei) {
        applyPalette();
        noSuggest = false;
        wantAutoCap = false;
        page = P_LET;
        if (ei != null) {
            int cls = ei.inputType & InputType.TYPE_MASK_CLASS;
            int var = ei.inputType & InputType.TYPE_MASK_VARIATION;
            if (cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_PHONE
                    || cls == InputType.TYPE_CLASS_DATETIME) {
                page = P_NUM;
                noSuggest = true;
            } else if (cls == InputType.TYPE_CLASS_TEXT) {
                if (var == InputType.TYPE_TEXT_VARIATION_PASSWORD
                        || var == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                        || var == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
                        || var == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                        || var == InputType.TYPE_TEXT_VARIATION_URI
                        || (ei.inputType & InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) != 0) {
                    noSuggest = true;
                }
                // majuscule en debut de phrase (utile en francais): sauf champs techniques
                wantAutoCap = !noSuggest;
            }
        }
        shift = SH_OFF;
        buildRows();
        layoutKeys();
        autoShift();
        refreshSuggestions();
        invalidate();
    }

    // ---------------------------------------------------------------- disposition
    private static String[] split(String s) {
        String[] a = new String[s.length()];
        for (int i = 0; i < s.length(); i++) a[i] = String.valueOf(s.charAt(i));
        return a;
    }

    private static String[] cat(String[]... parts) {
        int n = 0;
        for (String[] a : parts) n += a.length;
        String[] r = new String[n];
        int i = 0;
        for (String[] a : parts) {
            for (String s : a) r[i++] = s;
        }
        return r;
    }

    private void buildRows() {
        rows.clear();
        boolean ar = Suggest.AR.equals(lang);
        numberRow = page != P_NUM;
        if (numberRow) addRow(split("1234567890"), false);
        if (page == P_NUM) {
            addRow(new String[]{"1", "2", "3", "~del"}, false);
            addRow(new String[]{"4", "5", "6", "."}, false);
            addRow(new String[]{"7", "8", "9", ","}, false);
            addRow(new String[]{"~abc", "0", "~space", "~enter"}, false);
            return;
        }
        String[] r1, r2, r3;
        boolean rtl = false;
        if (page == P_LET && ar) {
            r1 = split("\u0636\u0635\u062B\u0642\u0641\u063A\u0639\u0647\u062E\u062D\u062C\u062F");
            r2 = split("\u0634\u0633\u064A\u0628\u0644\u0627\u062A\u0646\u0645\u0643\u0637\u0630");
            r3 = cat(new String[]{"~alt"}, new String[]{"\u0626", "\u0621", "\u0624", "\u0631", "\u0644\u0627",
                    "\u0649", "\u0629", "\u0648", "\u0632", "\u0638"}, new String[]{"~del"});
            rtl = true;
        } else if (page == P_ALT) {
            r1 = split("\u067E\u0686\u06A4\u06AD\u06AF\u0698\u06A8\u0671");
            r2 = new String[]{"\u064E", "\u064F", "\u0650", "\u064B", "\u064C", "\u064D", "\u0652", "\u0651",
                    "\u0640", "\u061B"};
            r3 = new String[]{"~abc2", "\u060C", "\u061F", "\u061B", "\u00AB", "\u00BB", "!", "?", "~del"};
            rtl = true;
        } else if (page == P_SYM1) {
            r1 = split("@#\u20AC_&-+()/");
            r2 = new String[]{"*", "\"", "'", ":", ";", "!", "?", "%", "\u060C", "\u061F"};
            r3 = new String[]{"~sym2", "<", ">", "=", "$", "\u00A3", "\u00A5", "\u00A7", "\u00B0", "~del"};
        } else if (page == P_SYM2) {
            r1 = split("[]{}#%^*+=");
            r2 = split("_\\|~<>\u20AC\u00A3\u00A5\u2022");
            r3 = new String[]{"~sym1", "\u00AB", "\u00BB", "\u2026", "\u00B0", "\u00A7", "&", "@", "~del"};
        } else {
            r1 = split("azertyuiop");
            r2 = split("qsdfghjklm");
            r3 = new String[]{"~shift", "w", "x", "c", "v", "b", "n", "'", "~del"};
        }
        addRow(r1, rtl);
        addRow(r2, rtl);
        addRow(r3, rtl);
        String abc = page == P_LET ? "~sym" : "~abc";
        addRow(new String[]{abc, "~lang", ar ? "\u060C" : ",", "~space", ".", "~enter"}, false);
    }

    private void addRow(String[] toks, boolean rtl) {
        ArrayList<Key> row = new ArrayList<>();
        for (String t : toks) row.add(makeKey(t));
        if (rtl) java.util.Collections.reverse(row);
        rows.add(row);
    }

    private Key makeKey(String t) {
        Key k = new Key();
        boolean ar = Suggest.AR.equals(lang);
        if (t.startsWith("~")) {
            switch (t) {
                case "~shift": k.type = T_SHIFT; k.w = 1.5f; break;
                case "~del": k.type = T_DEL; k.w = 1.5f; break;
                case "~space": k.type = T_SPACE; k.w = page == P_NUM ? 1.3f : 4.4f; break;
                case "~enter": k.type = T_ENTER; k.w = 1.5f; break;
                case "~lang": k.type = T_LANG; k.w = 1.05f; break;
                case "~sym": k.type = T_PAGE; k.target = P_SYM1; k.label = "?123"; k.w = 1.45f; break;
                case "~sym1": k.type = T_PAGE; k.target = P_SYM1; k.label = "1/2"; k.w = 1.4f; break;
                case "~sym2": k.type = T_PAGE; k.target = P_SYM2; k.label = "2/2"; k.w = 1.4f; break;
                case "~abc": k.type = T_PAGE; k.target = P_LET; k.label = ar ? "\u0623\u0628\u062C" : "ABC"; k.w = 1.45f; break;
                case "~alt": k.type = T_PAGE; k.target = P_ALT; k.label = "\u06AD"; k.w = 1.4f; break;
                case "~abc2": k.type = T_PAGE; k.target = P_LET; k.label = "\u0639"; k.w = 1.4f; break;
                default: break;
            }
        } else {
            k.label = t;
            k.out = t;
        }
        return k;
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = View.MeasureSpec.getSize(wSpec);
        setMeasuredDimension(w, Math.round(totalH()));
    }

    private float totalH() {
        return stripH + 40 * d + 4 * 49 * d + barH;
    }

    @Override
    protected void onSizeChanged(int w, int hh, int ow, int oh) {
        layoutKeys();
    }

    private void layoutKeys() {
        float W = getWidth();
        if (W <= 0) return;
        numH = 40 * d;
        float rowsArea = totalH() - stripH - barH;
        rowsTop = stripH;
        int n = rows.size();
        for (int ri = 0; ri < n; ri++) {
            float top, hh;
            if (page == P_NUM) {
                hh = rowsArea / n;
                top = rowsTop + ri * hh;
            } else if (ri == 0) {
                hh = numH;
                top = rowsTop;
            } else {
                hh = (rowsArea - numH) / (n - 1);
                top = rowsTop + numH + (ri - 1) * hh;
            }
            ArrayList<Key> row = rows.get(ri);
            float sum = 0;
            for (Key k : row) sum += k.w;
            float x = padX;
            float inner = W - 2 * padX;
            for (Key k : row) {
                float wd = inner * k.w / sum;
                k.hl = x;
                k.hr = x + wd;
                k.ht = top;
                k.hb = top + hh;
                k.l = x + gap / 2;
                k.r = x + wd - gap / 2;
                k.t = top + gap / 2;
                k.b = top + hh - gap / 2;
                x += wd;
            }
        }
    }

    // ---------------------------------------------------------------- dessin
    private void text(Canvas c, String s, float cx, float cy, float size, int color, boolean bold) {
        p.setStyle(Paint.Style.FILL);
        p.setTextAlign(Paint.Align.CENTER);
        p.setColor(color);
        p.setTextSize(size);
        p.setTypeface(bold ? TF_BOLD : TF_MED);
        Paint.FontMetrics fm = p.getFontMetrics();
        c.drawText(s, cx, cy - (fm.ascent + fm.descent) / 2f, p);
    }

    private void fit(Canvas c, String s, float cx, float cy, float size, int color, boolean bold, float maxW) {
        p.setTextSize(size);
        p.setTypeface(bold ? TF_BOLD : TF_MED);
        float wd = p.measureText(s);
        if (wd > maxW && wd > 0) size = size * maxW / wd;
        text(c, s, cx, cy, size, color, bold);
    }

    private String shown(Key k) {
        if (k.type == T_CHAR && !Suggest.AR.equals(lang) && page == P_LET && shift != SH_OFF) {
            return k.label.toUpperCase(java.util.Locale.FRENCH);
        }
        return k.label;
    }

    private void rrect(Canvas c, float l, float t, float r, float b, float rad, int color) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(color);
        rf.set(l, t, r, b);
        c.drawRoundRect(rf, rad, rad, p);
    }

    private void line(Canvas c, float x1, float y1, float x2, float y2, float w, int color) {
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(w);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setColor(color);
        c.drawLine(x1, y1, x2, y2, p);
    }

    private void drawIcon(Canvas c, Key k, float cx, float cy, int col) {
        float s = 9 * d;
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2f * d);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setColor(col);
        path.reset();
        if (k.type == T_SHIFT) {
            path.moveTo(cx, cy - s);
            path.lineTo(cx + s, cy);
            path.lineTo(cx + s * 0.45f, cy);
            path.lineTo(cx + s * 0.45f, cy + s * 0.85f);
            path.lineTo(cx - s * 0.45f, cy + s * 0.85f);
            path.lineTo(cx - s * 0.45f, cy);
            path.lineTo(cx - s, cy);
            path.close();
            if (shift != SH_OFF) {
                p.setStyle(Paint.Style.FILL);
                p.setColor(shift == SH_LOCK ? cAccent : col);
            }
            c.drawPath(path, p);
            if (shift == SH_LOCK) {
                line(c, cx - s * 0.45f, cy + s * 1.15f, cx + s * 0.45f, cy + s * 1.15f, 2f * d, cAccent);
            }
        } else if (k.type == T_DEL) {
            path.moveTo(cx - s * 1.1f, cy);
            path.lineTo(cx - s * 0.4f, cy - s * 0.8f);
            path.lineTo(cx + s * 1.1f, cy - s * 0.8f);
            path.lineTo(cx + s * 1.1f, cy + s * 0.8f);
            path.lineTo(cx - s * 0.4f, cy + s * 0.8f);
            path.close();
            c.drawPath(path, p);
            line(c, cx - s * 0.1f, cy - s * 0.35f, cx + s * 0.6f, cy + s * 0.35f, 1.8f * d, col);
            line(c, cx + s * 0.6f, cy - s * 0.35f, cx - s * 0.1f, cy + s * 0.35f, 1.8f * d, col);
        } else if (k.type == T_ENTER) {
            line(c, cx + s * 0.9f, cy - s * 0.8f, cx + s * 0.9f, cy + s * 0.2f, 2.2f * d, col);
            line(c, cx + s * 0.9f, cy + s * 0.2f, cx - s * 0.9f, cy + s * 0.2f, 2.2f * d, col);
            line(c, cx - s * 0.9f, cy + s * 0.2f, cx - s * 0.3f, cy - s * 0.4f, 2.2f * d, col);
            line(c, cx - s * 0.9f, cy + s * 0.2f, cx - s * 0.3f, cy + s * 0.8f, 2.2f * d, col);
        } else if (k.type == T_LANG) {
            c.drawCircle(cx, cy, s * 0.95f, p);
            line(c, cx - s * 0.95f, cy, cx + s * 0.95f, cy, 1.5f * d, col);
            rf.set(cx - s * 0.45f, cy - s * 0.95f, cx + s * 0.45f, cy + s * 0.95f);
            c.drawOval(rf, p);
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        float W = getWidth(), H = getHeight();
        c.drawColor(cBg);
        drawStrip(c, W);
        for (ArrayList<Key> row : rows) {
            for (Key k : row) drawKey(c, k);
        }
        // petit bouton "trois points" (retour au clavier Releve), tout en bas a gauche
        float by = H - barH / 2f;
        p.setStyle(Paint.Style.FILL);
        p.setColor(cDim);
        for (int i = 0; i < 3; i++) c.drawCircle(padX + 14 * d + i * 7 * d, by, 1.9f * d, p);
        text(c, Suggest.AR.equals(lang) ? "\u0627\u0644\u0639\u0631\u0628\u064A\u0629" : "Fran\u00E7ais",
                W / 2f, by, 10.5f * d, cDim, false);
        // fenetres: bulles et menus d'appui long
        for (int i = 0; i < ptrs.length; i++) {
            Ptr q = ptrs[i];
            if (q == null || q.key == null) continue;
            if (q.popup != null) drawPopup(c, q, W);
            else if (q.key.type == T_CHAR && !q.moved) drawBubble(c, q.key, W);
        }
    }

    private void drawStrip(Canvas c, float W) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(cStrip);
        c.drawRect(0, 0, W, stripH, p);
        float cw = W / 3f;
        boolean any = false;
        for (int i = 0; i < 3; i++) {
            if (sug[i] != null) any = true;
        }
        if (!any) return;
        // le meilleur au centre, puis gauche, puis droite
        int[] slot = {1, 0, 2};
        for (int i = 0; i < 3; i++) {
            String s = sug[i];
            if (s == null) continue;
            int sl = slot[i];
            float cx = cw * sl + cw / 2f;
            fit(c, s, cx, stripH / 2f, 18 * d, i == 0 ? cText : cDim, i == 0, cw - 16 * d);
            if (i > 0) {
                line(c, cw * sl + (sl == 0 ? cw : 0), stripH * 0.28f, cw * sl + (sl == 0 ? cw : 0), stripH * 0.72f,
                        1f * d, cSpecial);
            }
        }
        line(c, cw, stripH * 0.28f, cw, stripH * 0.72f, 1f * d, cSpecial);
        line(c, cw * 2, stripH * 0.28f, cw * 2, stripH * 0.72f, 1f * d, cSpecial);
    }

    private boolean isPressed(Key k) {
        for (Ptr q : ptrs) {
            if (q != null && q.key == k) return true;
        }
        return false;
    }

    private void drawKey(Canvas c, Key k) {
        boolean special = k.type != T_CHAR && k.type != T_SPACE;
        boolean pr = isPressed(k);
        int bg = special ? cSpecial : cKey;
        if (k.type == T_ENTER) bg = cAccent;
        if (k.type == T_SHIFT && shift != SH_OFF) bg = cKey;
        if (pr) bg = k.type == T_ENTER ? 0xFF2D5FCC : cPress;
        float rad = 9 * d;
        rrect(c, k.l, k.t, k.r, k.b, rad, bg);
        if (!pr && !host.light()) {
            // fin liser\u00E9 haut pour le relief
            line(c, k.l + rad, k.t + 0.5f * d, k.r - rad, k.t + 0.5f * d, 1f * d, 0x14FFFFFF);
        }
        float cx = (k.l + k.r) / 2f, cy = (k.t + k.b) / 2f;
        if (k.type == T_SHIFT || k.type == T_DEL || k.type == T_ENTER || k.type == T_LANG) {
            int col = k.type == T_ENTER ? 0xFFFFFFFF : cText;
            drawIcon(c, k, cx, cy, col);
            if (k.type == T_LANG) {
                text(c, Suggest.AR.equals(lang) ? "FR" : "\u0639", cx + 11 * d, cy + 11 * d, 9 * d, cDim, true);
            }
        } else if (k.type == T_SPACE) {
            if (page == P_NUM) {
                float s2 = 9 * d;
                line(c, cx - s2, cy - s2 * 0.2f, cx - s2, cy + s2 * 0.5f, 2f * d, cDim);
                line(c, cx - s2, cy + s2 * 0.5f, cx + s2, cy + s2 * 0.5f, 2f * d, cDim);
                line(c, cx + s2, cy + s2 * 0.5f, cx + s2, cy - s2 * 0.2f, 2f * d, cDim);
            }
            // sinon la barre d'espace reste vide (comme Samsung)
        } else if (k.type == T_PAGE) {
            text(c, k.label, cx, cy, 14 * d, cText, true);
        } else {
            boolean digit = k.out.length() == 1 && Character.isDigit(k.out.charAt(0)) && page != P_NUM;
            float size = digit ? 17 * d : (k.label.length() > 1 && !isHarakat(k) ? 21 * d : 24 * d);
            if (page == P_NUM) size = 26 * d;
            String s = shown(k);
            if (isHarakat(k)) {
                s = "\u25CC" + k.label;
                size = 22 * d;
            }
            fit(c, s, cx, cy, size, cText, false, (k.r - k.l) - 4 * d);
        }
    }

    private static boolean isHarakat(Key k) {
        if (k.type != T_CHAR || k.label.length() != 1) return false;
        char ch = k.label.charAt(0);
        return (ch >= 0x064B && ch <= 0x0652) || ch == 0x0670;
    }

    private void drawBubble(Canvas c, Key k, float W) {
        float bw = Math.max(52 * d, (k.r - k.l) * 1.35f), bh = 58 * d;
        float cx = (k.l + k.r) / 2f;
        float l = Math.max(2 * d, Math.min(W - bw - 2 * d, cx - bw / 2f));
        float t = Math.max(0, k.t - bh - 6 * d);
        // ombre simple
        rrect(c, l + 0, t + 3 * d, l + bw, t + bh + 3 * d, 14 * d, 0x40000000);
        rrect(c, l, t, l + bw, t + bh, 14 * d, cPop);
        String s = shown(k);
        if (isHarakat(k)) s = "\u25CC" + k.label;
        text(c, s, l + bw / 2f, t + bh / 2f, 32 * d, host.light() ? 0xFF14161B : 0xFFF1F2F5, false);
    }

    // ---------------------------------------------------------------- appui long
    private static final HashMap<String, String[]> VAR = new HashMap<>();

    static {
        VAR.put("\u0627", new String[]{"\u0623", "\u0625", "\u0622", "\u0671"});
        VAR.put("\u0628", new String[]{"\u067E"});
        VAR.put("\u062C", new String[]{"\u0686"});
        VAR.put("\u0641", new String[]{"\u06A4"});
        VAR.put("\u0643", new String[]{"\u06AD", "\u06A9", "\u06AA", "\u06AF"});
        VAR.put("\u0642", new String[]{"\u06A8", "\u06AF"});
        VAR.put("\u064A", new String[]{"\u0649", "\u0626"});
        VAR.put("\u0648", new String[]{"\u0624"});
        VAR.put("\u0647", new String[]{"\u0629"});
        VAR.put("\u0632", new String[]{"\u0698"});
        VAR.put("\u0621", new String[]{"\u0654"});
        VAR.put("e", new String[]{"\u00E9", "\u00E8", "\u00EA", "\u00EB", "\u20AC"});
        VAR.put("a", new String[]{"\u00E0", "\u00E2", "\u00E6", "\u00E4"});
        VAR.put("u", new String[]{"\u00F9", "\u00FB", "\u00FC"});
        VAR.put("i", new String[]{"\u00EE", "\u00EF"});
        VAR.put("o", new String[]{"\u00F4", "\u0153", "\u00F6"});
        VAR.put("c", new String[]{"\u00E7"});
        VAR.put("y", new String[]{"\u00FF"});
        VAR.put("n", new String[]{"\u00F1"});
        VAR.put(".", new String[]{",", "?", "!", ":", ";", "'", "\"", "\u2026"});
        VAR.put(",", new String[]{".", "?", "!", ":", ";", "'", "\"", "\u2026"});
        VAR.put("\u060C", new String[]{"\u061F", "\u061B", ".", "!", ":", "\"", "\u00AB", "\u00BB"});
        VAR.put("-", new String[]{"_", "\u2013", "\u2014"});
        VAR.put("'", new String[]{"\u2019", "`", "\""});
        VAR.put("?", new String[]{"\u061F", "\u00BF"});
        VAR.put("!", new String[]{"\u00A1"});
        VAR.put("%", new String[]{"\u2030"});
        VAR.put("0", new String[]{"\u00B0"});
    }

    private static final String[] TASH = {"\u064E", "\u064F", "\u0650", "\u0652", "\u0651", "\u064B", "\u064C", "\u064D"};

    /** Lignes du menu d'appui long (variantes, puis lettre + marques de tachkil pour l'arabe). */
    private String[][] popupRows(Key k) {
        if (k.type != T_CHAR || k.out.isEmpty()) return null;
        String base = k.out;
        boolean arLetter = base.length() >= 1 && base.charAt(0) >= 0x0621 && base.charAt(0) <= 0x064A && page != P_NUM;
        String[] v = VAR.get(base);
        if (v == null && !arLetter) return null;
        ArrayList<String[]> out = new ArrayList<>();
        if (v != null) {
            String[] vv = new String[v.length];
            for (int i = 0; i < v.length; i++) {
                vv[i] = (!arLetter && shift != SH_OFF) ? v[i].toUpperCase(java.util.Locale.FRENCH) : v[i];
            }
            out.add(vv);
        }
        if (arLetter && base.length() == 1) {
            String[] t1 = new String[5];
            for (int i = 0; i < 5; i++) t1[i] = base + TASH[i];
            out.add(t1);
            String[] t2 = new String[3];
            for (int i = 0; i < 3; i++) t2[i] = base + TASH[5 + i];
            out.add(t2);
        }
        return out.isEmpty() ? null : out.toArray(new String[0][]);
    }

    private static final class Popup {
        String[][] rows;
        float l, t, cw, chh;        // origine et taille d'une cellule
        int selR = 0, selC = 0;
        float width() {
            int m = 0;
            for (String[] r : rows) m = Math.max(m, r.length);
            return m * cw;
        }
    }

    private void drawPopup(Canvas c, Ptr q, float W) {
        Popup pp = q.popup;
        float wd = pp.width() + 12 * d, ht = pp.rows.length * pp.chh + 12 * d;
        rrect(c, pp.l + 0, pp.t + 4 * d, pp.l + wd, pp.t + ht + 4 * d, 16 * d, 0x40000000);
        rrect(c, pp.l, pp.t, pp.l + wd, pp.t + ht, 16 * d, cPop);
        for (int r = 0; r < pp.rows.length; r++) {
            for (int i = 0; i < pp.rows[r].length; i++) {
                float cl = pp.l + 6 * d + i * pp.cw, ct = pp.t + 6 * d + r * pp.chh;
                boolean sel = r == pp.selR && i == pp.selC;
                if (sel) rrect(c, cl + 1.5f * d, ct + 1.5f * d, cl + pp.cw - 1.5f * d, ct + pp.chh - 1.5f * d, 10 * d, cAccent);
                int col = sel ? 0xFFFFFFFF : (host.light() ? 0xFF14161B : 0xFFF1F2F5);
                fit(c, pp.rows[r][i], cl + pp.cw / 2f, ct + pp.chh / 2f, 26 * d, col, false, pp.cw - 6 * d);
            }
        }
    }

    private Popup buildPopup(Key k, String[][] rs) {
        Popup pp = new Popup();
        pp.rows = rs;
        pp.cw = 46 * d;
        pp.chh = 52 * d;
        float wd = pp.width() + 12 * d, ht = rs.length * pp.chh + 12 * d;
        float cx = (k.l + k.r) / 2f;
        float W = getWidth();
        pp.l = Math.max(2 * d, Math.min(W - wd - 2 * d, cx - wd / 2f));
        pp.t = Math.max(0, k.t - ht - 6 * d);
        return pp;
    }

    // ---------------------------------------------------------------- touches
    private static final class Ptr {
        int id;
        Key key;
        float downX, downY, x, y;
        boolean moved;
        boolean stripDown;
        int stripCell = -1;
        boolean dotsDown;
        Popup popup;
        boolean spaceSwiped;
        float spaceAcc;
        Runnable longRun;
    }

    private final Ptr[] ptrs = new Ptr[10];

    private Key keyAt(float x, float y) {
        Key best = null;
        float bd = Float.MAX_VALUE;
        for (ArrayList<Key> row : rows) {
            for (Key k : row) {
                if (y >= k.ht && y < k.hb && x >= k.hl && x < k.hr) return k;
                float dx = x < k.hl ? k.hl - x : (x > k.hr ? x - k.hr : 0);
                float dy = y < k.ht ? k.ht - y : (y > k.hb ? y - k.hb : 0);
                float dd = dx * dx + dy * dy;
                if (dd < bd) {
                    bd = dd;
                    best = k;
                }
            }
        }
        return best;
    }

    private void haptic() {
        if (hapticOn) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int act = e.getActionMasked();
        int idx = e.getActionIndex();
        switch (act) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
                down(e.getPointerId(idx), e.getX(idx), e.getY(idx));
                break;
            case MotionEvent.ACTION_MOVE:
                for (int i = 0; i < e.getPointerCount(); i++) move(e.getPointerId(i), e.getX(i), e.getY(i));
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
                up(e.getPointerId(idx), e.getX(idx), e.getY(idx), false);
                break;
            case MotionEvent.ACTION_CANCEL:
                for (int i = 0; i < ptrs.length; i++) {
                    if (ptrs[i] != null) up(i, ptrs[i].x, ptrs[i].y, true);
                }
                break;
            default:
                break;
        }
        invalidate();
        return true;
    }

    private void down(final int id, float x, float y) {
        if (id < 0 || id >= ptrs.length) return;
        final Ptr q = new Ptr();
        q.id = id;
        q.downX = x;
        q.downY = y;
        q.x = x;
        q.y = y;
        ptrs[id] = q;
        float H = getHeight();
        if (y < stripH) {
            q.stripDown = true;
            q.stripCell = (int) (x / (getWidth() / 3f));
            return;
        }
        if (y > H - barH) {
            q.dotsDown = true;
            return;
        }
        final Key k = keyAt(x, y);
        q.key = k;
        if (k == null) return;
        haptic();
        if (k.type == T_DEL) {
            delCount = 0;
            delActive = true;
            doDelete(false);
            h.removeCallbacks(delRepeat);
            h.postDelayed(delRepeat, 380);
            return;
        }
        q.longRun = new Runnable() {
            @Override
            public void run() {
                if (ptrs[id] != q || q.key == null) return;
                if (q.key.type == T_SHIFT) {
                    shift = SH_LOCK;
                    q.key = null;
                    rebuildLabels();
                    invalidate();
                    return;
                }
                if (q.key.type == T_LANG) {
                    q.key = null;
                    host.pickKeyboard();
                    invalidate();
                    return;
                }
                String[][] rs = popupRows(q.key);
                if (rs != null) {
                    q.popup = buildPopup(q.key, rs);
                    haptic();
                    invalidate();
                }
            }
        };
        h.postDelayed(q.longRun, 360);
    }

    private void move(int id, float x, float y) {
        if (id < 0 || id >= ptrs.length || ptrs[id] == null) return;
        Ptr q = ptrs[id];
        q.x = x;
        q.y = y;
        if (Math.abs(x - q.downX) > 10 * d || Math.abs(y - q.downY) > 10 * d) q.moved = true;
        if (q.popup != null) {
            Popup pp = q.popup;
            int r = (int) Math.floor((y - pp.t - 6 * d) / pp.chh);
            int cI = (int) Math.floor((x - pp.l - 6 * d) / pp.cw);
            if (r >= 0 && r < pp.rows.length && cI >= 0 && cI < pp.rows[r].length) {
                pp.selR = r;
                pp.selC = cI;
            }
            return;
        }
        if (q.key != null && q.key.type == T_SPACE) {
            float dx = x - q.downX - q.spaceAcc;
            if (Math.abs(dx) > 14 * d) {
                q.spaceSwiped = true;
                h.removeCallbacks(q.longRun);
                int steps = (int) (dx / (14 * d));
                q.spaceAcc += steps * 14 * d;
                moveCursor(steps);
            }
        }
        if (q.key != null && q.key.type != T_DEL && q.key.type != T_SPACE) {
            // le doigt a glisse vers une autre touche: on suit la touche sous le doigt
            Key nk = keyAt(x, y);
            if (nk != q.key && nk != null && q.popup == null && y > stripH) {
                if (q.longRun != null) h.removeCallbacks(q.longRun);
                q.key = nk;
                q.moved = false;
                q.downX = x;
                q.downY = y;
                haptic();
                final Ptr qq = q;
                final int pid = id;
                q.longRun = new Runnable() {
                    @Override
                    public void run() {
                        if (ptrs[pid] != qq || qq.key == null) return;
                        String[][] rs = popupRows(qq.key);
                        if (rs != null) {
                            qq.popup = buildPopup(qq.key, rs);
                            invalidate();
                        }
                    }
                };
                h.postDelayed(q.longRun, 360);
            }
        }
    }

    private void up(int id, float x, float y, boolean cancel) {
        if (id < 0 || id >= ptrs.length || ptrs[id] == null) return;
        Ptr q = ptrs[id];
        ptrs[id] = null;
        if (q.longRun != null) h.removeCallbacks(q.longRun);
        if (cancel) {
            delActive = false;
            h.removeCallbacks(delRepeat);
            return;
        }
        if (q.stripDown) {
            if (y < stripH) {
                int cell = (int) (x / (getWidth() / 3f));
                if (cell == q.stripCell) pickSuggestion(cell);
            }
            return;
        }
        if (q.dotsDown) {
            if (y > getHeight() - barH && x < 90 * d) host.toReleve();
            return;
        }
        Key k = q.key;
        if (k == null) return;
        if (k.type == T_DEL) {
            delActive = false;
            h.removeCallbacks(delRepeat);
            return;
        }
        if (q.popup != null) {
            String s = q.popup.rows[q.popup.selR][q.popup.selC];
            commit(s, true);
            return;
        }
        switch (k.type) {
            case T_CHAR: {
                String s = k.out;
                if (!Suggest.AR.equals(lang) && page == P_LET && shift != SH_OFF) s = s.toUpperCase(java.util.Locale.FRENCH);
                commit(s, true);
                if (shift == SH_ON && page == P_LET) {
                    shift = SH_OFF;
                    rebuildLabels();
                }
                break;
            }
            case T_SPACE:
                if (!q.spaceSwiped) pressSpace();
                break;
            case T_ENTER:
                finishWord();
                host.enter();
                clearSuggestions();
                autoShiftSoon();
                break;
            case T_SHIFT: {
                long now = System.currentTimeMillis();
                if (shift == SH_OFF) shift = (now - lastShiftTap < 350) ? SH_LOCK : SH_ON;
                else shift = SH_OFF;
                lastShiftTap = now;
                rebuildLabels();
                break;
            }
            case T_PAGE:
                page = k.target;
                shift = SH_OFF;
                buildRows();
                layoutKeys();
                if (page == P_LET) autoShift();
                break;
            case T_LANG:
                lang = Suggest.AR.equals(lang) ? Suggest.FR : Suggest.AR;
                try {
                    getContext().getSharedPreferences("cfg", Context.MODE_PRIVATE).edit().putString("typeLang", lang).apply();
                } catch (Throwable ignored) {
                }
                page = P_LET;
                shift = SH_OFF;
                buildRows();
                layoutKeys();
                autoShift();
                refreshSuggestions();
                break;
            default:
                break;
        }
    }

    private void rebuildLabels() {
        invalidate();
    }

    // ---------------------------------------------------------------- saisie
    private boolean delActive = false;
    private int delCount = 0;
    private final Runnable delRepeat = new Runnable() {
        @Override
        public void run() {
            if (!delActive) return;
            delCount++;
            doDelete(delCount > 14);
            h.postDelayed(this, delCount < 6 ? 85 : (delCount < 14 ? 55 : 110));
            invalidate();
        }
    };

    private void doDelete(boolean word) {
        InputConnection ic = host.ic();
        if (ic == null) return;
        CharSequence sel = ic.getSelectedText(0);
        if (sel != null && sel.length() > 0) {
            ic.commitText("", 1);
        } else if (word) {
            CharSequence before = ic.getTextBeforeCursor(40, 0);
            int n = 0;
            if (before != null) {
                int i = before.length();
                while (i > 0 && Character.isWhitespace(before.charAt(i - 1))) i--;
                while (i > 0 && !Character.isWhitespace(before.charAt(i - 1))) i--;
                n = before.length() - i;
            }
            ic.deleteSurroundingText(Math.max(1, n), 0);
        } else {
            ic.deleteSurroundingText(1, 0);
        }
        refreshSuggestions();
        autoShiftSoon();
        invalidate();
    }

    private void moveCursor(int steps) {
        InputConnection ic = host.ic();
        if (ic == null || steps == 0) return;
        int code = steps > 0 ? KeyEvent.KEYCODE_DPAD_RIGHT : KeyEvent.KEYCODE_DPAD_LEFT;
        for (int i = 0; i < Math.abs(steps); i++) {
            ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, code));
            ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, code));
        }
        refreshSuggestions();
    }

    private long lastSpace = 0;

    private void pressSpace() {
        InputConnection ic = host.ic();
        if (ic == null) return;
        long now = System.currentTimeMillis();
        boolean dbl = false;
        if (now - lastSpace < 450) {
            CharSequence b = ic.getTextBeforeCursor(2, 0);
            if (b != null && b.length() == 2 && b.charAt(1) == ' ' && Character.isLetterOrDigit(b.charAt(0))) dbl = true;
        }
        lastSpace = now;
        if (dbl) {
            ic.deleteSurroundingText(1, 0);
            ic.commitText(Suggest.AR.equals(lang) ? ". " : ". ", 1);
            clearSuggestions();
            autoShiftSoon();
            return;
        }
        finishWord();
        spaceFromPick = false;
        ic.commitText(" ", 1);
        refreshSuggestions();
        autoShiftSoon();
    }

    private boolean spaceFromPick = false;

    private void commit(String s, boolean learnOnSep) {
        InputConnection ic = host.ic();
        if (ic == null) return;
        if (spaceFromPick && s.length() == 1 && ".,!?;:\u060C\u061B\u061F".indexOf(s.charAt(0)) >= 0) {
            CharSequence b = ic.getTextBeforeCursor(1, 0);
            if (b != null && b.length() == 1 && b.charAt(0) == ' ') ic.deleteSurroundingText(1, 0);
        }
        spaceFromPick = false;
        if (learnOnSep && s.length() == 1 && !Suggest.isWordChar(s.charAt(0))) finishWord();
        ic.commitText(s, 1);
        refreshSuggestions();
        autoShiftSoon();
    }

    /** Memorise le mot qui vient d'etre termine (avant espace ou ponctuation). */
    private void finishWord() {
        if (noSuggest) return;
        InputConnection ic = host.ic();
        if (ic == null) return;
        CharSequence b = ic.getTextBeforeCursor(60, 0);
        if (b == null) return;
        String[] wp = lastWords(b.toString());
        if (wp[0].length() >= 2) {
            synchronized (LOCK) {
                ENGINE.learn(lang, wp[0], wp[1]);
            }
            scheduleSave();
        }
    }

    private static boolean isLet(char ch) {
        return Suggest.isWordChar(ch) && ch != '\'' && ch != '\u2019' && ch != '-';
    }

    /** [mot en cours (fin du texte), mot precedent]. */
    private static String[] lastWords(String t) {
        int e = t.length();
        int i = e;
        while (i > 0 && isLet(t.charAt(i - 1))) i--;
        String cur = t.substring(i, e);
        int j = i;
        while (j > 0 && (t.charAt(j - 1) == ' ' || t.charAt(j - 1) == '\u00A0')) j--;
        int k = j;
        while (k > 0 && isLet(t.charAt(k - 1))) k--;
        String prev = (j < i && k < j) ? t.substring(k, j) : "";
        return new String[]{cur, prev};
    }

    // ---------------------------------------------------------------- suggestions
    private void clearSuggestions() {
        sug[0] = sug[1] = sug[2] = null;
        curPrefix = "";
        curPrev = "";
        invalidate();
    }

    private void refreshSuggestions() {
        sug[0] = sug[1] = sug[2] = null;
        curPrefix = "";
        curPrev = "";
        if (noSuggest || !engineReady) {
            invalidate();
            return;
        }
        InputConnection ic = host.ic();
        if (ic == null) {
            invalidate();
            return;
        }
        CharSequence b = ic.getTextBeforeCursor(60, 0);
        if (b == null) {
            invalidate();
            return;
        }
        String[] wp = lastWords(b.toString());
        curPrefix = wp[0];
        curPrev = wp[1];
        ArrayList<String> r;
        synchronized (LOCK) {
            r = ENGINE.predict(lang, curPrefix, curPrev, 3);
        }
        for (int i = 0; i < Math.min(3, r.size()); i++) sug[i] = r.get(i);
        invalidate();
    }

    private void pickSuggestion(int cell) {
        // cellule 1 = centre = meilleure proposition (sug[0]); 0 = gauche (sug[1]); 2 = droite (sug[2])
        int idx = cell == 1 ? 0 : (cell == 0 ? 1 : 2);
        String s = sug[idx];
        if (s == null) return;
        InputConnection ic = host.ic();
        if (ic == null) return;
        haptic();
        String prefix = curPrefix, prev = curPrev;
        ic.beginBatchEdit();
        if (!prefix.isEmpty()) ic.deleteSurroundingText(prefix.length(), 0);
        ic.commitText(s + " ", 1);
        ic.endBatchEdit();
        spaceFromPick = true;
        if (!noSuggest) {
            synchronized (LOCK) {
                ENGINE.learn(lang, s, prev);
            }
            scheduleSave();
        }
        refreshSuggestions();
        autoShiftSoon();
    }

    // ---------------------------------------------------------------- majuscules automatiques
    private void autoShiftSoon() {
        h.post(new Runnable() {
            @Override
            public void run() {
                autoShift();
            }
        });
    }

    private void autoShift() {
        if (Suggest.AR.equals(lang) || page != P_LET || !wantAutoCap || shift == SH_LOCK) return;
        InputConnection ic = host.ic();
        if (ic == null) return;
        CharSequence b = ic.getTextBeforeCursor(3, 0);
        boolean cap;
        if (b == null || b.length() == 0) {
            cap = true;
        } else {
            String t = b.toString();
            int n = t.length();
            char last = t.charAt(n - 1);
            cap = last == '\n' || (last == ' ' && n >= 2 && (t.charAt(n - 2) == '.' || t.charAt(n - 2) == '!' || t.charAt(n - 2) == '?'));
        }
        int ns = cap ? SH_ON : SH_OFF;
        if (ns != shift) {
            shift = ns;
            invalidate();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        h.removeCallbacksAndMessages(null);
        delActive = false;
        for (int i = 0; i < ptrs.length; i++) ptrs[i] = null;
    }
}
