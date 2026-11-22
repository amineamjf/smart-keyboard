package com.smartkeyboard.releve;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.inputmethodservice.InputMethodService;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.view.Gravity;
import android.widget.TextView;
import android.widget.Toast;

public class KeyboardService extends InputMethodService {

    public static KeyboardService instance;
    /** Heure du dernier appui sur une touche du pave (le service evite de lire l'ecran pendant la frappe). */
    public static volatile long lastKeyT = 0;
    /** Vrai tant que le panneau de saisie rapide est affiche (le service ne lit pas l'ecran pendant ce temps). */
    public static volatile boolean quickOpen = false;
    private final StringBuilder buf = new StringBuilder();
    private static final long AUTO_DELAY_MS = 500;
    private final Handler autoHandler = new Handler(Looper.getMainLooper());
    private final Runnable autoSubmit = () -> {
        if (buf.length() > 0 && ScanService.instance != null
                && ScanService.instance.autoOk(buf.toString())) press("OK");
    };

    // Affichage automatique du clavier (retour de l'appareil photo, etc.)
    // Bandeau (compteur + ancien index) au-dessus du clavier
    public static boolean stripOn = true;
    public static boolean showOld = true, showNew = true, showNum = true;
    private LinearLayout strip;
    private TextView stMsg;
    private LinearLayout cOld, cNum, cNew;
    private TextView tOldL, tOldV, tNumL, tNumV, tNewL, tNewV;
    private boolean dataMode = false;
    private String dNum = "", dOld = "", dNew = "", dNote = "";
    private boolean dSus = false;
    private int oldFlag = 0; // 0 normal, 1 ancien douteux (orange), 2 contradiction (rouge)
    private LinearLayout cCam;
    private TextView tCam;

    public void setOldFlag(int f) {
        oldFlag = f;
    }
    private String stripText = "";

    private boolean searching = false;
    private final Handler showHandler = new Handler(Looper.getMainLooper());

    // Couleurs (theme clair par defaut: lisible en plein soleil)
    public static boolean lightTheme = true;
    private int C_BG, C_KEY, C_KEY_SH, C_DARK, C_DARK_SH, C_TEXT, C_LIGHT, C_LIGHT_SH;
    private int C_GREEN, C_GREEN_SH, C_TEAL, C_TEAL_SH, C_RED, C_RED_SH, C_AMBER, C_AMBER_SH;
    private int C_STRIP_BG, C_STRIP_TXT, C_OLD, C_EDGE;
    private int S_NUM_BG, S_NUM_TX, S_NUM_ED, S_OLD_BG, S_OLD_TX, S_NEW_BG, S_NEW_TX, S_SUS_BG, S_SUS_TX;
    public static boolean discreet = false;

    private void applyTheme() {
        if (lightTheme) {
            C_BG = 0xFFE8ECF4; C_KEY = 0xFFFFFFFF; C_KEY_SH = 0xFFC9D1E0;
            C_DARK = 0xFFD3DAE8; C_DARK_SH = 0xFFB3BCD0; C_TEXT = 0xFF0F1E4A;
            C_LIGHT = 0xFFEEF2F9; C_LIGHT_SH = 0xFFC9D1E0;
            C_GREEN = 0xFF15803D; C_GREEN_SH = 0xFF0F5F2D;
            C_TEAL = 0xFF1D4ED8; C_TEAL_SH = 0xFF1E3A8A;
            C_RED = 0xFFC62828; C_RED_SH = 0xFF8E1B1B;
            C_AMBER = 0xFFC77000; C_AMBER_SH = 0xFF9A5600;
            C_STRIP_BG = 0xFFF4F6FB; C_STRIP_TXT = 0xFF0F1E4A; C_OLD = 0xFFB35000; C_EDGE = 0xFFD5DBE8;
            S_NUM_BG = 0xFFA8F0FF; S_NUM_TX = 0xFF06223A; S_NUM_ED = 0;
            S_OLD_BG = 0xFF2563EB; S_OLD_TX = 0xFFFFFFFF;
            S_NEW_BG = 0xFF16A34A; S_NEW_TX = 0xFFFFFFFF;
            S_SUS_BG = 0xFFF59E0B; S_SUS_TX = 0xFF1F1200;
        } else {
            C_BG = 0xFF17181A; C_KEY = 0xFF38393D; C_KEY_SH = 0xFF2B2C2F;
            C_DARK = 0xFF2A2B2E; C_DARK_SH = 0xFF202124; C_TEXT = 0xFFF1F1F4;
            C_LIGHT = 0xFF303135; C_LIGHT_SH = 0xFF222326;
            C_GREEN = 0xFF2FA866; C_GREEN_SH = 0xFF1F7D4B;
            C_TEAL = 0xFF3D7BF0; C_TEAL_SH = 0xFF2A5CBF;
            C_RED = 0xFFD9484C; C_RED_SH = 0xFFA33236;
            C_AMBER = 0xFFE59A1F; C_AMBER_SH = 0xFFB87710;
            C_STRIP_BG = 0xFF222326; C_STRIP_TXT = 0xFFF1F1F4; C_OLD = 0xFFFFC857; C_EDGE = 0x00000000;
            S_NUM_BG = 0xFFA8F0FF; S_NUM_TX = 0xFF06223A; S_NUM_ED = 0;
            S_OLD_BG = 0xFF2563EB; S_OLD_TX = 0xFFFFFFFF;
            S_NEW_BG = 0xFF1FA34A; S_NEW_TX = 0xFFFFFFFF;
            S_SUS_BG = 0xFFF59E0B; S_SUS_TX = 0xFF1F1200;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        try {
            lightTheme = getSharedPreferences("cfg", Context.MODE_PRIVATE).getBoolean("light", true);
        } catch (Exception e) {
            lightTheme = true;
        }
        applyTheme();
        try {
            android.content.SharedPreferences sp = getSharedPreferences("cfg", Context.MODE_PRIVATE);
            showOld = sp.getBoolean("showOld", true);
            showNew = sp.getBoolean("showNew", true);
            showNum = sp.getBoolean("showNum", true);
        } catch (Exception e) {
            // ignore
        }
        try {
            discreet = getSharedPreferences("cfg", Context.MODE_PRIVATE).getBoolean("discreet", false);
        } catch (Exception e) {
            discreet = false;
        }
        try {
            stripOn = getSharedPreferences("cfg", Context.MODE_PRIVATE).getBoolean("strip", true);
        } catch (Exception e) {
            stripOn = true;
        }
    }

    @Override
    public void onDestroy() {
        instance = null;
        super.onDestroy();
    }

    /** Nombre actuellement tape sur le clavier. */
    public String typedValue() {
        return buf.toString();
    }

    public boolean typeText(String s) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return false;
        boolean ok = ic.commitText(s, 1);
        if (ok) {
            buf.setLength(0);
            buf.append(s);
        }
        return ok;
    }

    // ---------- Outils de design ----------

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private int darken(int c) {
        return Color.rgb((int) (Color.red(c) * 0.86f),
                (int) (Color.green(c) * 0.86f),
                (int) (Color.blue(c) * 0.86f));
    }

    private GradientDrawable rounded(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        if (lightTheme) g.setStroke(dp(1), C_EDGE);
        return g;
    }

    private Drawable layers(int face, int shadow) {
        LayerDrawable ld = new LayerDrawable(new Drawable[]{rounded(shadow), rounded(face)});
        ld.setLayerInset(1, 0, 0, 0, dp(3));
        return ld;
    }

    private int lighten(int c) {
        return Color.rgb(Math.min(255, Color.red(c) + 38),
                Math.min(255, Color.green(c) + 38),
                Math.min(255, Color.blue(c) + 38));
    }

    private Drawable keyBg(int face, int shadow) {
        StateListDrawable sl = new StateListDrawable();
        sl.addState(new int[]{android.R.attr.state_pressed}, rounded(lighten(face)));
        sl.addState(new int[]{}, rounded(face));
        return sl;
    }

    private int radiusDp = 14;

    private LinearLayout.LayoutParams lpT(int w, int h, float weight) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h, weight);
        int m = dp(2.5f);
        p.setMargins(m, m, m, m);
        return p;
    }

    private LinearLayout.LayoutParams lp(int w, int h, float weight) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h, weight);
        int m = dp(3);
        p.setMargins(m, m, m, m);
        return p;
    }

    private Button makeKey(CharSequence label, int face, int shadow, int textColor,
                           float sp, final Runnable action) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(textColor);
        b.setTextSize(sp);
        b.setTypeface(sp >= 20 ? Typeface.DEFAULT : Typeface.DEFAULT_BOLD);
        b.setBackground(keyBg(face, shadow));
        b.setStateListAnimator(null);
        b.setPadding(0, 0, 0, 0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            if (action != null) action.run();
        });
        return b;
    }

    /** Bouton moderne: degrade doux, grands coins, une seule ligne qui s'adapte a la largeur. */
    private Button modernKey(CharSequence label, final int top, final int bottom, final Runnable action) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(0xFFFFFFFF);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        b.setSingleLine(true);
        b.setGravity(Gravity.CENTER);
        b.setIncludeFontPadding(false);
        b.setPadding(dp(6), 0, dp(6), 0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setStateListAnimator(null);
        b.setAutoSizeTextTypeUniformWithConfiguration(9, 15, 1,
                android.util.TypedValue.COMPLEX_UNIT_SP);
        GradientDrawable up = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{top, bottom});
        up.setCornerRadius(dp(16));
        GradientDrawable down = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{darken(top), darken(bottom)});
        down.setCornerRadius(dp(16));
        StateListDrawable sl = new StateListDrawable();
        sl.addState(new int[]{android.R.attr.state_pressed}, down);
        sl.addState(new int[]{}, up);
        b.setBackground(sl);
        b.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            if (action != null) action.run();
        });
        return b;
    }

    private Button digit(final String k) {
        return makeKey(k, C_KEY, C_KEY_SH, C_TEXT, 28, () -> press(k));
    }

    // ---------- Clavier principal ----------

    @Override
    public View onCreateInputView() {
        return (typeMode || discreet) ? buildLetters() : buildMain();
    }

    /** Demande l'affichage du clavier (utilise par l'automatisation apres la photo). */
    public void showNow() {
        try {
            requestShowSelf(0);
        } catch (Exception e) {
            // ignore
        }
    }

    private static String noZeros(String t) {
        if (t == null) return "";
        t = t.trim();
        int i = 0;
        while (i < t.length() - 1 && t.charAt(i) == '0') i++;
        return t.substring(i);
    }

    /** Affichage simple (message): "absent du tableau", etc. */
    public void setStrip(String t) {
        stripText = t == null ? "" : t;
        dataMode = false;
        renderStrip();
    }

    /** Affichage detaille: numero au centre, ancien a gauche, nouveau a droite. */
    public void setStripData(String num, String old, String nw, boolean suspect, String note) {
        dataMode = true;
        dNum = noZeros(num);
        dOld = old == null ? "" : old;
        dNew = nw == null ? "" : nw;
        dSus = suspect;
        dNote = note == null ? "" : note;
        renderStrip();
    }

    /** Fond de cellule avec un leger degrade vertical (haut plus clair), sans contour. */
    private GradientDrawable cellBg(int c, int edge) {
        int top = android.graphics.Color.rgb(
                Math.min(255, android.graphics.Color.red(c) + (255 - android.graphics.Color.red(c)) / 5),
                Math.min(255, android.graphics.Color.green(c) + (255 - android.graphics.Color.green(c)) / 5),
                Math.min(255, android.graphics.Color.blue(c) + (255 - android.graphics.Color.blue(c)) / 5));
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{top, c});
        g.setCornerRadius(dp(10));
        return g;
    }

    private GradientDrawable cellBgFlat(int c, int edge) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(c);
        g.setCornerRadius(dp(10));
        if (edge != 0) g.setStroke(dp(1.5f), edge);
        return g;
    }

    private LinearLayout makeCell(TextView lab, TextView val) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setGravity(Gravity.CENTER);
        lab.setTextSize(8);
        lab.setSingleLine(true);
        lab.setGravity(Gravity.CENTER);
        val.setTypeface(Typeface.DEFAULT_BOLD);
        val.setSingleLine(true);
        val.setGravity(Gravity.CENTER);
        c.addView(lab, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        c.addView(val, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        c.setPadding(dp(2), 0, dp(2), 0);
        return c;
    }

    private void buildStrip() {
        strip = new LinearLayout(this);
        strip.setOrientation(LinearLayout.HORIZONTAL);
        strip.setBackgroundColor(C_STRIP_BG);
        strip.setPadding(dp(4), dp(3), dp(4), dp(3));

        stMsg = new TextView(this);
        stMsg.setTextColor(C_STRIP_TXT);
        stMsg.setTextSize(15);
        stMsg.setTypeface(Typeface.DEFAULT_BOLD);
        stMsg.setGravity(Gravity.CENTER);
        stMsg.setSingleLine(true);
        stMsg.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);

        tOldL = new TextView(this);
        tOldV = new TextView(this);
        tNumL = new TextView(this);
        tNumV = new TextView(this);
        tNewL = new TextView(this);
        tNewV = new TextView(this);
        cOld = makeCell(tOldL, tOldV);
        cNum = makeCell(tNumL, tNumV);
        cNew = makeCell(tNewL, tNewV);
        cOld.setBackground(cellBg(S_OLD_BG, 0));
        cNum.setBackground(cellBg(S_NUM_BG, 0));
        cNum.setOnClickListener(v -> gotoAlertMeter());
        // glisser le doigt sur le bandeau (droite ou gauche) = meme glissade dans l'application
        android.view.View.OnTouchListener sw = new android.view.View.OnTouchListener() {
            float dx0, dy0;
            boolean done;

            @Override
            public boolean onTouch(View v, android.view.MotionEvent e) {
                switch (e.getActionMasked()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        dx0 = e.getRawX();
                        dy0 = e.getRawY();
                        done = false;
                        return false;
                    case android.view.MotionEvent.ACTION_MOVE:
                        if (!done) {
                            float dx = e.getRawX() - dx0, dy = e.getRawY() - dy0;
                            if (Math.abs(dx) > dp(40) && Math.abs(dx) > 2 * Math.abs(dy)) {
                                done = true;
                                if (ScanService.instance != null) ScanService.instance.swipeFinger(dx > 0);
                            }
                        }
                        return done;
                    case android.view.MotionEvent.ACTION_UP:
                    case android.view.MotionEvent.ACTION_CANCEL:
                        boolean r = done;
                        done = false;
                        return r;
                    default:
                        return done;
                }
            }
        };
        cNum.setOnTouchListener(sw);
        cOld.setOnTouchListener(sw);
        cNew.setOnTouchListener(sw);
        tOldL.setTextColor(S_OLD_TX);
        tOldV.setTextColor(S_OLD_TX);
        tNumL.setTextColor(S_NUM_TX);
        tNumV.setTextColor(S_NUM_TX);
        tOldV.setTextSize(20);
        // chiffres plus etroits: le numero du compteur peut etre plus grand dans la meme largeur
        tNumV.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));

        // ancien et nouveau: 1 cm de large chacun; le numero prend tout le reste, sans contour
        strip.addView(cOld, lpT(mm(15), ViewGroup.LayoutParams.MATCH_PARENT, 0f));
        strip.addView(cNum, lpT(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        strip.addView(cNew, lpT(mm(15), ViewGroup.LayoutParams.MATCH_PARENT, 0f));
        cNum.setPadding(0, 0, 0, 0);
        tOldV.setIncludeFontPadding(false);
        tNumV.setIncludeFontPadding(false);
        tNewV.setIncludeFontPadding(false);
        strip.addView(stMsg, lpT(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        // icone appareil photo (a droite): visible seulement quand une valeur hors limites attend la photo
        tCam = new TextView(this);
        tCam.setText("\uD83D\uDCF7");
        tCam.setTextSize(24);
        tCam.setGravity(Gravity.CENTER);
        tCam.setTextColor(0xFFFFFFFF);
        tCam.setIncludeFontPadding(false);
        cCam = new LinearLayout(this);
        cCam.setGravity(Gravity.CENTER);
        GradientDrawable cg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFFFFA726, 0xFFF57C00});
        cg.setCornerRadius(dp(22));
        cCam.setBackground(cg);
        cCam.addView(tCam, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        cCam.setOnClickListener(v -> {
            if (ScanService.instance != null) ScanService.instance.openCameraNow();
        });
        LinearLayout.LayoutParams clp = lpT(mm(12), ViewGroup.LayoutParams.MATCH_PARENT, 0f);
        clp.leftMargin = dp(4);
        strip.addView(cCam, clp);
        cCam.setVisibility(View.GONE);
        // bouton tout a gauche du bandeau: affiche/masque les fenetres flottantes
        btnFloat = new TextView(this);
        btnFloat.setText("");
        btnFloat.setTextSize(10);
        btnFloat.setGravity(Gravity.CENTER);
        btnFloat.setIncludeFontPadding(false);
        btnFloat.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            boolean on = FloatCards.toggle(this);
            if (on && ScanService.instance == null) {
                flashStrip("Service d'accessibilit\u00E9 d\u00E9sactiv\u00E9");
            } else {
                flashStrip(on ? "Fen\u00EAtres : " + FloatCards.modeName(FloatCards.mode(this)) : "Fen\u00EAtres masqu\u00E9es");
            }
            refreshFloatBtn();
        });
        btnFloat.setOnLongClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            int m = FloatCards.cycleMode(this);
            flashStrip("Fen\u00EAtres : " + FloatCards.modeName(m));
            refreshFloatBtn();
            return true;
        });
        LinearLayout.LayoutParams blp = lpT(mm(3), ViewGroup.LayoutParams.MATCH_PARENT, 0f);
        blp.rightMargin = dp(3);
        strip.addView(btnFloat, 0, blp);
        refreshFloatBtn();
        renderStrip();
    }

    /**
     * Agrandit le numero du compteur au maximum dans sa cellule. Quand l'ancien ou le nouvel index
     * est masque, la cellule du numero prend leur place et le texte grandit en consequence.
     */
    private int mm(float v) {
        return (int) android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_MM, v,
                getResources().getDisplayMetrics());
    }

    private static final int STRIP_H_DP = 54;

    private int oldW = 0, newW = 0;

    /** Largeur d'une cellule ancien/nouveau: juste ce qu'il faut pour le nombre (9 a 15 mm). */
    private int sideWidth(String text) {
        float cellH = dp(STRIP_H_DP) - dp(6) - dp(3);
        float px = Math.min(cellH / 1.18f, dp(26));
        android.graphics.Paint pt = new android.graphics.Paint();
        pt.setTypeface(Typeface.DEFAULT_BOLD);
        pt.setTextSize(px);
        int w = (int) Math.ceil(pt.measureText(text)) + dp(14);
        return Math.max(mm(9), Math.min(mm(15), w));
    }

    private void applyWidth(View cell, int w) {
        ViewGroup.LayoutParams lp = cell.getLayoutParams();
        if (lp != null && lp.width != w) {
            lp.width = w;
            cell.setLayoutParams(lp);
        }
    }

    /** Cellule "ancien"/"nouveau" du bandeau: masquee quand la fenetre flottante correspondante est affichee. */
    private boolean sOld() {
        return showOld && !FloatCards.covers(FloatCards.OLD);
    }

    private boolean sNew() {
        return showNew && !FloatCards.covers(FloatCards.NEW);
    }

    private TextView btnFloat;

    /** Appele quand les fenetres flottantes apparaissent ou disparaissent. */
    void relayoutStrip() {
        showHandler.post(() -> {
            renderStrip();
            refreshFloatBtn();
        });
    }

    private void refreshFloatBtn() {
        try {
            if (btnFloat == null) return;
            boolean on = FloatCards.isOn(this);
            btnFloat.setBackground(cellBg(on ? 0xFF2E7D32 : 0xFF455A64, 0));
            btnFloat.setTextColor(0xFFFFFFFF);
        } catch (Throwable ignored) {
        }
    }

    private void fitNumber() {
        float total = getResources().getDisplayMetrics().widthPixels - dp(8);
        float avail = total - (sOld() ? oldW + dp(3) : 0) - (sNew() ? newW + dp(3) : 0) - dp(4) - (btnFloat != null ? mm(3) + dp(3) : 0);
        float cellH = dp(STRIP_H_DP) - dp(6) - dp(3);
        float labH = tNumL.getVisibility() == View.VISIBLE ? tNumL.getTextSize() * 1.3f : 0f;
        float px = Math.max(dp(14), (cellH - labH) / 1.18f);
        android.graphics.Paint pt = new android.graphics.Paint(tNumV.getPaint());
        float min = dp(12);
        while (px > min) {
            pt.setTextSize(px);
            if (pt.measureText(dNum) <= avail) break;
            px -= 1f;
        }
        tNumV.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px);
    }

    /** Ancien / nouveau index: tient dans 1 cm de large, avec la hauteur du bandeau. */
    private void fitSide(TextView tv, String text, int cellW) {
        float avail = cellW - dp(4);
        float cellH = dp(STRIP_H_DP) - dp(6) - dp(3);
        float labH = 0f;
        float px = Math.min((cellH - labH) / 1.18f, dp(26));
        android.graphics.Paint pt = new android.graphics.Paint(tv.getPaint());
        float min = dp(9);
        while (px > min) {
            pt.setTextSize(px);
            if (pt.measureText(text) <= avail) break;
            px -= 1f;
        }
        tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px);
    }

    // ---- Bouton VALIDER <-> STOP (pendant l'automatisation) ----
    private Button validBtn;
    private CharSequence validLabel, stopLabel;
    private boolean validStopShown = false;
    private final Handler validHandler = new Handler(Looper.getMainLooper());
    private final Runnable validTick = new Runnable() {
        @Override public void run() {
            Button b = validBtn;
            if (b == null || !b.isAttachedToWindow()) return;
            applyValidState(ScanService.autoRunning);
            validHandler.postDelayed(this, 300);
        }
    };

    private void applyValidState(boolean stop) {
        Button b = validBtn;
        if (b == null || validLabel == null) return;
        if (stop == validStopShown && b.getText().length() > 0) return;
        validStopShown = stop;
        try {
            if (stop) {
                b.setText(stopLabel);
                b.setBackground(keyBg(C_RED, C_RED_SH));
                b.animate().cancel();
                b.setAlpha(1f);
                pulse(b, true);
            } else {
                b.animate().cancel();
                b.setAlpha(1f);
                b.setText(validLabel);
                b.setBackground(keyBg(C_GREEN, C_GREEN_SH));
            }
        } catch (Throwable t) {
            // ignore
        }
    }

    /** Pulsation douce du bouton STOP tant que l'automatisation tourne. */
    private void pulse(final Button b, final boolean down) {
        if (!validStopShown || !b.isAttachedToWindow()) return;
        b.animate().alpha(down ? 0.72f : 1f).setDuration(650)
                .withEndAction(() -> pulse(b, !down)).start();
    }

    private String flashMsg = "";
    private long flashUntil = 0;

    /** Petit message de succes dans le bandeau (ex: compteur deplace). */
    public void flashStrip(String m) {
        flashMsg = m == null ? "" : m;
        flashUntil = System.currentTimeMillis() + 3500;
        renderStrip();
        showHandler.postDelayed(() -> renderStrip(), 3600);
    }

    private void renderStrip() {
        if (strip == null) return;
        try {
            boolean flash = System.currentTimeMillis() < flashUntil;
            if (!dataMode) {
                cOld.setVisibility(View.GONE);
                cNum.setVisibility(View.GONE);
                cNew.setVisibility(View.GONE);
                if (cCam != null) cCam.setVisibility(View.GONE);
                stMsg.setVisibility(View.VISIBLE);
                stMsg.setText(flash ? flashMsg : (stripText.isEmpty() ? "-" : stripText));
                stopGlow();
                return;
            }
            stMsg.setVisibility(View.GONE);

            boolean alert = !dAlert.isEmpty();
            if (!alert || flash) stopGlow();
            boolean cam = !alert && camHint;
            tNumL.setTextSize(alert || flash ? 11 : 9);
            cNum.setVisibility(showNum || alert || flash ? View.VISIBLE : View.GONE);
            if (cCam != null) {
                boolean was = cCam.getVisibility() == View.VISIBLE;
                cCam.setVisibility(cam ? View.VISIBLE : View.GONE);
                if (cam && !was) {
                    android.animation.ObjectAnimator pulse = android.animation.ObjectAnimator.ofFloat(
                            cCam, "alpha", 1f, 0.55f, 1f);
                    pulse.setDuration(900);
                    pulse.setRepeatCount(3);
                    pulse.start();
                } else if (!cam) {
                    cCam.setAlpha(1f);
                }
            }
            if (flash) {
                tNumL.setText(flashMsg);
                cNum.setBackground(cellBg(S_NEW_BG, 0));
                tNumL.setTextColor(S_NEW_TX);
                tNumV.setTextColor(S_NEW_TX);
            } else if (alert) {
                tNumL.setText("\u26A0 SAUT\u00C9: " + dAlert + "  (toucher)");
                tNumL.setTextColor(0xFF4A2200);
                tNumV.setTextColor(0xFF4A2200);
                startGlow();
            } else {
                tNumL.setText(dNote);
                cNum.setBackground(cellBg(S_NUM_BG, 0));
                tNumL.setTextColor(S_NUM_TX);
                tNumV.setTextColor(S_NUM_TX);
            }
            tNumL.setVisibility((alert || flash || !dNote.isEmpty()) ? View.VISIBLE : View.GONE);
            tOldL.setVisibility(View.GONE);
            tNewL.setVisibility(View.GONE);
            tNumV.setText(dNum);
            oldW = sOld() ? sideWidth(dOld.isEmpty() ? "-" : dOld) : 0;
            newW = sNew() ? sideWidth(dNew.isEmpty() ? "-" : dNew) : 0;
            applyWidth(cOld, oldW > 0 ? oldW : mm(9));
            applyWidth(cNew, newW > 0 ? newW : mm(9));
            fitNumber();

            cOld.setVisibility(sOld() ? View.VISIBLE : View.GONE);
            tOldL.setText("Ancien");
            cOld.setBackground(cellBg(oldFlag == 2 ? 0xFFD32F2F : oldFlag == 1 ? S_SUS_BG : S_OLD_BG, 0));
            int otc = oldFlag == 2 ? 0xFFFFFFFF : oldFlag == 1 ? S_SUS_TX : S_OLD_TX;
            tOldV.setTextColor(otc);
            tOldV.setText(dOld.isEmpty() ? "-" : dOld);
            fitSide(tOldV, dOld.isEmpty() ? "-" : dOld, oldW);

            cNew.setVisibility(sNew() ? View.VISIBLE : View.GONE);
            cNew.setBackground(cellBg(dSus ? S_SUS_BG : S_NEW_BG, 0));
            int tc = dSus ? S_SUS_TX : S_NEW_TX;
            tNewL.setTextColor(tc);
            tNewV.setTextColor(tc);
            tNewL.setText(dSus ? "Nouveau \u26A0" : "Nouveau");
            tNewV.setText(dNew.isEmpty() ? "-" : dNew);
            fitSide(tNewV, dNew.isEmpty() ? "-" : dNew, newW);
            FloatCards.update(dOld, dNew, oldFlag, dSus);
        } catch (Throwable t) {
            // l'affichage ne doit jamais faire planter le clavier
        }
    }

    private String dAlert = "";
    private String dAlertNum = "";

    private android.animation.ValueAnimator glowAnim;

    /** Compteur du tableau saute par l'application: le bandeau pulse doucement en orange tant que l'alerte dure. */
    private void startGlow() {
        if (glowAnim != null) return;
        cNum.setBackground(cellBg(0xFFFFD2A1, 0));
        final android.animation.ArgbEvaluator ev = new android.animation.ArgbEvaluator();
        glowAnim = android.animation.ValueAnimator.ofFloat(0f, 1f);
        glowAnim.setDuration(1300);
        glowAnim.setRepeatCount(android.animation.ValueAnimator.INFINITE);
        glowAnim.setRepeatMode(android.animation.ValueAnimator.REVERSE);
        glowAnim.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
        glowAnim.addUpdateListener(a -> {
            float f = (Float) a.getAnimatedValue();
            int fill = (Integer) ev.evaluate(f, 0xFFFFD2A1, 0xFFFF9E3D);
            int edge = (Integer) ev.evaluate(f, 0x66FFB066, 0xFFFFE6C8);
            GradientDrawable g = cellBg(fill, 0);
            g.setStroke(dp(2), edge);
            cNum.setBackground(g);
        });
        glowAnim.start();
    }

    private void stopGlow() {
        if (glowAnim != null) {
            glowAnim.cancel();
            glowAnim = null;
        }
    }

    /** Alerte du bandeau: compteur du tableau non propose par l'application d'origine. */
    public void setStripAlert(String text, String num) {
        dAlert = text == null ? "" : text;
        dAlertNum = num == null ? "" : num;
    }

    private boolean camHint = false;

    /** Valeur hors limites: le bandeau propose d'ouvrir l'appareil photo. */
    public void setCamHint(boolean on) {
        if (camHint == on) return;
        camHint = on;
        renderStrip();
    }

    // ---------- Voix hors ligne (touche micro) ----------

    private VoiceInput voice;
    private int guardIdle = 0;
    private long guardStart = 0;

    private final Runnable guardWatch = new Runnable() {
        @Override
        public void run() {
            if (voice == null || !voice.isRunning()) return;
            boolean busyNow = ScanService.instance != null && ScanService.instance.isBusyNow();
            long age = android.os.SystemClock.uptimeMillis() - guardStart;
            if (!busyNow && age > 3000) guardIdle++;
            else guardIdle = 0;
            if (guardIdle >= 2) {
                voice.cancel();
                return;
            }
            validHandler.postDelayed(this, 1000);
        }
    };

    /** Touche micro: un appui = ecouter un chiffre ou un ordre; un appui pendant l'action = arret immediat. */
    private void onMic() {
        if (voice == null) voice = new VoiceInput(this);
        if (voice.isRunning()) {
            voice.cancel();
            if (ScanService.instance != null && ScanService.instance.isBusyNow()) ScanService.instance.stopAll();
            flashStrip("\u23F9 Arr\u00EAt");
            return;
        }
        if (!voice.hasPermission()) {
            try {
                Intent pi = new Intent(this, MicPermissionActivity.class);
                pi.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(pi);
                flashStrip("Autorisez le micro, puis rappuyez sur \uD83C\uDFA4");
            } catch (Throwable t) {
                flashStrip("Micro non autoris\u00E9 (R\u00E9glages Android > Applications > Relev\u00E9 > Autorisations)");
            }
            return;
        }
        // Rien appris en darija: le francais prend le relais tout seul (ou se telecharge une seule fois)
        final boolean untrained = VoiceStore.get(this).total() == 0;
        if (untrained && !VoskEngine.installed(this)) {
            startModelDownload();
            return;
        }
        if (VoskEngine.installed(this) && (untrained || getSharedPreferences("cfg", Context.MODE_PRIVATE)
                .getBoolean("voice_fr", false))) {
            flashStrip("\u2026");
            preloadVosk();
            voice.once(pcm -> {
                flashStrip("\u2026");
                new Thread(() -> handleFr(pcm)).start();
                return false;
            }, () -> flashStrip("\uD83C\uDFA4 PARLEZ"));
            return;
        }
        if (VoiceStore.get(this).total() == 0) {
            flashStrip("Rien appris: Outils > Voix hors ligne");
            return;
        }
        flashStrip("\u2026");
        voice.once(pcm -> {
            flashStrip("\u2026");
            new Thread(() -> handleVoice(pcm)).start();
            return false;
        }, () -> flashStrip("\uD83C\uDFA4 PARLEZ"));
    }

    private boolean frMode = true;
    private static volatile boolean voskLoading = false;

    private CharSequence micLabel() {
        try {
            frMode = (getSharedPreferences("cfg", Context.MODE_PRIVATE).getBoolean("voice_fr", false)
                    || VoiceStore.get(this).total() == 0) && VoskEngine.installed(this);
        } catch (Throwable ignored) {
        }
        android.text.SpannableString sp = new android.text.SpannableString("\uD83C\uDFA4\n" + (frMode ? "FR" : "DA"));
        sp.setSpan(new android.text.style.RelativeSizeSpan(0.55f), 2, sp.length(),
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return sp;
    }

    /** Premier usage du francais: telechargement unique du modele (internet requis une seule fois). */
    private void startModelDownload() {
        if (VoskEngine.downloading) {
            flashStrip("T\u00E9l\u00E9chargement du mod\u00E8le : " + VoskEngine.percent + " %");
            return;
        }
        flashStrip("T\u00E9l\u00E9chargement du mod\u00E8le fran\u00E7ais (une seule fois)\u2026");
        final Context c = getApplicationContext();
        new Thread(() -> {
            String err = VoskEngine.download(c, p -> showHandler.post(
                    () -> flashStrip("T\u00E9l\u00E9chargement du mod\u00E8le : " + p + " %")));
            showHandler.post(() -> flashStrip(err == null
                    ? "Mod\u00E8le pr\u00EAt : appuyez sur \uD83C\uDFA4"
                    : "T\u00E9l\u00E9chargement impossible (" + err + ") \u2014 v\u00E9rifiez internet"));
        }).start();
    }

    /** Charge le modele francais en arriere-plan (la premiere fois, il est copie depuis l'application). */
    private void preloadVosk() {
        if (voskLoading) return;
        voskLoading = true;
        final Context c = getApplicationContext();
        new Thread(() -> {
            try {
                VoskEngine.load(c);
            } finally {
                voskLoading = false;
            }
        }).start();
    }

    /** Francais: Vosk limite aux nombres et aux ordres. Calcul hors du thread principal. */
    private void handleFr(short[] pcm) {
        try {
            if (pcm == null || pcm.length == 0) {
                showHandler.post(() -> flashStrip("Rien entendu (parlez apr\u00E8s PARLEZ)"));
                return;
            }
            saveDebugWav(pcm);
            final String text = VoskEngine.recognize(this, pcm);
            final String dg = String.format(java.util.Locale.US, " [%.1fs]", pcm.length / 16000.0);
            if (text == null) {
                final String le = VoskEngine.lastError;
                showHandler.post(() -> flashStrip("Mod\u00E8le FR indisponible : " + le));
                return;
            }
            final String cmd = VoskEngine.command(text);
            final String num = cmd == null ? FrenchNumbers.parse(text) : null;
            showHandler.post(() -> {
                if (cmd != null) {
                    flashStrip("\u25B6 " + text);
                    runVoiceCommand(cmd);
                } else if (num != null) {
                    for (int i = 0; i < num.length(); i++) press(String.valueOf(num.charAt(i)));
                    flashStrip("\uD83C\uDFA4 " + num + "  (" + text + ")");
                } else {
                    flashStrip(text.isEmpty() ? "Rien compris" + dg : "Entendu : " + text + " \u2014 r\u00E9p\u00E9tez");
                }
            });
        } catch (Throwable t) {
            showHandler.post(() -> flashStrip("Erreur voix"));
        }
    }

    /** Calcul lourd sur un thread; toute action sur l'ecran repasse par le thread principal. */
    private void handleVoice(short[] pcm) {
        try {
            if (pcm == null || pcm.length == 0) {
                showHandler.post(() -> flashStrip("Rien entendu (parlez apr\u00E8s PARLEZ)"));
                return;
            }
            VoiceModel m = VoiceStore.get(this);
            saveDebugWav(pcm);
            java.util.List<VoiceCore.Span> base = VoiceCore.split(pcm, 220, 90);
            diag = String.format(java.util.Locale.US, " [%.1fs, %d seg]", pcm.length / 16000.0, base.size());
            if (base.isEmpty()) {
                showHandler.post(() -> flashStrip("Rien entendu"));
                return;
            }
            String hint = "";
            float[][] whole = null;
            VoiceModel.Match nm = null, cm = null;
            java.util.List<VoiceCore.Span> one = VoiceCore.split(pcm, 700, 90);
            if (!one.isEmpty()) {
                whole = VoiceCore.mfcc(pcm, one.get(0).from, one.get(one.size() - 1).to);
                nm = m.classifyNumber(whole);
                if (base.size() <= 4) cm = m.classifyCommand(whole);
            }
            final float[][] feat = whole;
            boolean nOk = nm != null && nm.sure();
            boolean cOk = cm != null && cm.sure();
            if (nOk && (!cOk || nm.score() <= cm.score())) {
                final String digits = nm.label.substring(VoiceModel.NUM.length());
                showHandler.post(() -> {
                    pendFeat = feat;
                    pendTime = System.currentTimeMillis();
                    for (int i = 0; i < digits.length(); i++) press(String.valueOf(digits.charAt(i)));
                    flashStrip("\uD83C\uDFA4 " + digits + diag);
                });
                return;
            }
            if (cOk) {
                final String id = cm.label.substring(VoiceModel.CMD.length());
                showHandler.post(() -> runVoiceCommand(id));
                return;
            }
            if (cm != null && !cm.label.isEmpty()) {
                hint = cm.label.substring(VoiceModel.CMD.length())
                        + (cm.unknownGate ? " (1 seul exemple)" : " " + Math.round(cm.score() * 100) / 100.0);
            }
            // On essaie plusieurs decoupages en mots et on garde le plus convaincant.
            int[] gaps = {220, 160, 120, 300, 90};
            String bestDigits = null;
            double bestCost = Double.MAX_VALUE;
            boolean bestOk = false;
            int lastCount = -1;
            for (int g : gaps) {
                java.util.List<VoiceCore.Span> spans = VoiceCore.split(pcm, g, g < 120 ? 60 : 90);
                if (spans.isEmpty() || spans.size() == lastCount && g != gaps[0]) continue;
                lastCount = spans.size();
                StringBuilder joined = new StringBuilder();
                boolean sure = true;
                double cost = 0;
                for (VoiceCore.Span sp : spans) {
                    VoiceModel.Match r = m.classify(VoiceCore.mfcc(pcm, sp.from, sp.to));
                    if (r.label.isEmpty()) {
                        sure = false;
                        cost += 5;
                        continue;
                    }
                    joined.append(r.label).append(' ');
                    if (!r.sure()) sure = false;
                    cost += Math.max(r.score(), 0.1) + (r.ratio() < 1.06 ? 1 : 0);
                }
                DarijaNumbers.Result nr = DarijaNumbers.parse(joined.toString().trim());
                boolean ok = nr != null && nr.ok() && !nr.risky && sure;
                if (nr == null || !nr.ok()) cost += 10;
                if (ok && !bestOk || ok == bestOk && cost < bestCost) {
                    bestOk = ok;
                    bestCost = cost;
                    bestDigits = nr != null && nr.ok() ? nr.digits : null;
                }
                if (ok) break;
            }
            final String digits = bestDigits;
            final boolean good = bestOk;
            final String h = hint;
            final java.util.List<String> near = feat == null ? new java.util.ArrayList<String>()
                    : m.nearestCommands(feat, 3);
            showHandler.post(() -> {
                pendFeat = feat;
                pendTime = System.currentTimeMillis();
                if (good && digits != null) {
                    for (int i = 0; i < digits.length(); i++) press(String.valueOf(digits.charAt(i)));
                    flashStrip("\uD83C\uDFA4 " + digits);
                } else {
                    flashStrip("Incertain: " + (digits != null ? digits : "?")
                            + (h.isEmpty() ? "" : " | ordre? " + h) + diag);
                    showPickPanel(digits, near, feat);
                }
            });
        } catch (Throwable t) {
            showHandler.post(() -> flashStrip("Erreur voix"));
        }
    }

    private volatile String diag = "";
    private static int dbgCount = 0;

    /** Garde les 8 dernieres captures du clavier (WAV) pour pouvoir verifier ce que le micro a vraiment entendu. */
    private void saveDebugWav(short[] pcm) {
        try {
            java.io.File dir = getExternalFilesDir("voice_dbg");
            if (dir == null) dir = new java.io.File(getFilesDir(), "voice_dbg");
            dir.mkdirs();
            java.io.File f = new java.io.File(dir, "capture_" + (dbgCount++ % 8) + ".wav");
            java.io.DataOutputStream o = new java.io.DataOutputStream(
                    new java.io.BufferedOutputStream(new java.io.FileOutputStream(f)));
            int len = pcm.length * 2;
            o.writeBytes("RIFF");
            o.writeInt(Integer.reverseBytes(36 + len));
            o.writeBytes("WAVEfmt ");
            o.writeInt(Integer.reverseBytes(16));
            o.writeShort(Short.reverseBytes((short) 1));
            o.writeShort(Short.reverseBytes((short) 1));
            o.writeInt(Integer.reverseBytes(16000));
            o.writeInt(Integer.reverseBytes(32000));
            o.writeShort(Short.reverseBytes((short) 2));
            o.writeShort(Short.reverseBytes((short) 16));
            o.writeBytes("data");
            o.writeInt(Integer.reverseBytes(len));
            for (short v : pcm) o.writeShort(Short.reverseBytes(v));
            o.close();
        } catch (Throwable ignored) {
        }
    }

    private float[][] pendFeat;
    private long pendTime;

    /** Apprentissage par l'usage: le nombre valide a la main est associe a ce que l'utilisateur vient de dire. */
    private void learnTyped(String v) {
        try {
            float[][] f = pendFeat;
            pendFeat = null;
            if (f == null || System.currentTimeMillis() - pendTime > 60000) return;
            if (v.length() == 0 || v.length() > 12) return;
            for (int i = 0; i < v.length(); i++) if (!Character.isDigit(v.charAt(i))) return;
            VoiceStore.get(this).add(VoiceModel.NUM + v, f);
            final Context c = getApplicationContext();
            new Thread(() -> VoiceStore.save(c)).start();
        } catch (Throwable ignored) {
        }
    }

    private void learnCommand(String id, float[][] f) {
        try {
            if (f == null) return;
            VoiceStore.get(this).add(VoiceModel.CMD + id, f);
            final Context c = getApplicationContext();
            new Thread(() -> VoiceStore.save(c)).start();
        } catch (Throwable ignored) {
        }
    }

    private String commandName(String label) {
        for (String[] c : VoiceTrainActivity.COMMANDS) if ((VoiceModel.CMD + c[0]).equals(label)) return c[1];
        return label.substring(VoiceModel.CMD.length());
    }

    /** Quand la reconnaissance hesite: l'utilisateur choisit ce qu'il voulait dire, et l'application l'apprend. */
    private void showPickPanel(final String digits, final java.util.List<String> near, final float[][] feat) {
        try {
            final LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setBackgroundColor(C_STRIP_BG);
            box.setPadding(dp(8), dp(8), dp(8), dp(8));
            TextView t = new TextView(this);
            t.setText("Qu'avez-vous dit ?  (l'application apprendra)");
            t.setTextColor(C_STRIP_TXT);
            t.setTextSize(14);
            t.setTypeface(Typeface.DEFAULT_BOLD);
            t.setGravity(Gravity.CENTER);
            box.addView(t);
            if (digits != null && digits.length() > 0) {
                Button b = new Button(this);
                b.setAllCaps(false);
                b.setTextSize(18);
                b.setText("\u2714  " + digits);
                b.setOnClickListener(v -> {
                    setInputView(buildMain());
                    pendFeat = feat;
                    pendTime = System.currentTimeMillis();
                    for (int i = 0; i < digits.length(); i++) press(String.valueOf(digits.charAt(i)));
                });
                box.addView(b);
            }
            for (final String lab : near) {
                Button b = new Button(this);
                b.setAllCaps(false);
                b.setTextSize(16);
                b.setText("\u25B6  " + commandName(lab));
                b.setOnClickListener(v -> {
                    final String id = lab.substring(VoiceModel.CMD.length());
                    learnCommand(id, feat);
                    setInputView(buildMain());
                    runVoiceCommand(id);
                });
                box.addView(b);
            }
            Button x = new Button(this);
            x.setAllCaps(false);
            x.setText("\u2716  Aucun (je tape \u00E0 la main)");
            x.setOnClickListener(v -> setInputView(buildMain()));
            box.addView(x);
            setInputView(box);
        } catch (Throwable t) {
            // le clavier reste utilisable
        }
    }

    private void runVoiceCommand(String id) {
        ScanService sv = ScanService.instance;
        if (sv == null) {
            flashStrip("Service d'accessibilit\u00E9 d\u00E9sactiv\u00E9");
            return;
        }
        if (id.equals("stop")) {
            sv.stopAll();
            flashStrip("\u23F9 Stop");
            return;
        }
        switch (id) {
            case "note_local":
            case "note_bloque":
            case "note_inaccessible":
            case "note_absent": {
                int k = id.equals("note_local") ? 0 : id.equals("note_bloque") ? 1
                        : id.equals("note_inaccessible") ? 2 : 3;
                String nm = sv.noteAnomaly(k);
                flashStrip(nm);
                return;
            }
            case "placer": {
                String pm = sv.moveCurrentAfterLast();
                setInputView(buildMain());
                flashStrip(pm);
                return;
            }
            case "annuler":
                flashStrip(sv.undoMove());
                return;
            case "ok":
                press("OK");
                return;
            case "efface":
                press("<");
                return;
            case "rechercher":
                search();
                return;
            case "dernier":
                openLastSent();
                return;
            case "options":
                setInputView(buildOptions());
                return;
            case "ajouter":
                setInputView(buildMain());
                sv.openAddMeter();
                return;
            case "apprendre":
                hideThen(() -> {
                    if (ScanService.instance != null) ScanService.instance.learnOrder();
                });
                return;
            case "analyser_conso":
                hideThen(() -> {
                    if (ScanService.instance != null) ScanService.instance.analyzeConso();
                });
                return;
            case "a_realiser":
                hideThen(() -> {
                    if (ScanService.instance != null) ScanService.instance.readTodo(false);
                });
                return;
            case "journal":
                LogAuto.run(this, true);
                return;
            default:
                break;
        }
        if (id.equals("local")) runAnomaly(0);
        else if (id.equals("bloque")) runAnomaly(1);
        else if (id.equals("inaccessible")) runAnomaly(2);
        else if (id.equals("photo")) sv.openCameraNow();
        else return;
        // pendant l'action, on ecoute encore pour entendre "stop"
        guardIdle = 0;
        guardStart = android.os.SystemClock.uptimeMillis();
        validHandler.postDelayed(() -> {
            if (voice == null || voice.isRunning()) return;
            voice.guard(pcm2 -> {
                try {
                    if (pcm2 != null && pcm2.length > 0) {
                        java.util.List<VoiceCore.Span> one = VoiceCore.split(pcm2, 700, 90);
                        if (!one.isEmpty()) {
                            VoiceModel.Match cm = VoiceStore.get(this).classifyCommand(
                                    VoiceCore.mfcc(pcm2, one.get(0).from, one.get(one.size() - 1).to));
                            if (cm.sure() && cm.label.equals(VoiceModel.CMD + "stop")) {
                                if (ScanService.instance != null) ScanService.instance.stopAll();
                                flashStrip("\u23F9 Stop");
                                return false;
                            }
                        }
                    }
                } catch (Throwable t) {
                    // on continue d'ecouter
                }
                return true;
            }, 60000);
            validHandler.postDelayed(guardWatch, 1000);
        }, 500);
    }

    private void gotoAlertMeter() {
        // compteur du tableau non propose par l'application: saisie rapide de son index (sans le chercher)
        if (!dAlertNum.isEmpty()) {
            Meters.Row ar = Meters.find(dAlertNum);
            if (ar != null && !ar.extra) {
                setInputView(buildQuick(ar));
                return;
            }
        }
        if (dAlertNum.isEmpty() && dNote.startsWith("\u26A0 compteur different")
                && ScanService.instance != null) {
            Toast.makeText(this, ScanService.instance.confirmReplacement(), Toast.LENGTH_LONG).show();
            return;
        }
        if (dAlertNum.isEmpty()) return;
        if (ScanService.instance == null) {
            Toast.makeText(this, "Service d'accessibilite desactive", Toast.LENGTH_SHORT).show();
            return;
        }
        gotoKeyInApp(dAlertNum);
    }

    /** Ouvre un compteur dans l'application d'origine (recherche), clavier masque pendant ce temps. */
    private void gotoKeyInApp(final String num) {
        if (ScanService.instance == null) {
            Toast.makeText(this, "Service d'accessibilite desactive", Toast.LENGTH_SHORT).show();
            return;
        }
        searching = true;
        showHandler.postDelayed(() -> searching = false, 4000);
        requestHideSelf(0);
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (ScanService.instance != null) ScanService.instance.gotoMeter(num);
        }, 150);
    }

    private void setStripOn(boolean on) {
        stripOn = on;
        try {
            getSharedPreferences("cfg", Context.MODE_PRIVATE).edit().putBoolean("strip", on).apply();
        } catch (Exception e) {
            // ignore
        }
    }

    private boolean typeMode = false;
    private boolean shownType = false;
    private TypeKb typeKb = null;
    private static final String REL_APP = "com.somei.smartmobility.srmrsk";

    private final TypeKb.Host typeHost = new TypeKb.Host() {
        @Override
        public InputConnection ic() {
            return getCurrentInputConnection();
        }

        @Override
        public void enter() {
            pressEnter();
        }

        @Override
        public void toReleve() {
            typeMode = false;
            setInputView(buildMain());
        }

        @Override
        public void pickKeyboard() {
            try {
                InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                imm.showInputMethodPicker();
            } catch (Throwable ignored) {
            }
        }

        @Override
        public boolean light() {
            return lightTheme;
        }
    };

    /** Clavier de saisie (arabe / francais / chiffres) style Samsung. */
    private View buildLetters() {
        quickOpen = false;
        typeMode = true;
        shownType = true;
        strip = null;
        typeKb = new TypeKb(this, typeHost);
        typeKb.start(getCurrentInputEditorInfo());
        return typeKb;
    }

    private View buildMain() {
        quickOpen = false;
        typeMode = false;
        shownType = false;
        typeKb = null;
        View keys = wrapWithStrip(buildKeys(), ViewGroup.LayoutParams.WRAP_CONTENT);
        // tout petit bouton (3 points) en bas a gauche: passe au clavier de saisie
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(C_BG);
        box.addView(keys, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        TextView dots = new TextView(this);
        dots.setText("\u2022 \u2022 \u2022");
        dots.setTextSize(9);
        dots.setTextColor((C_TEXT & 0x00FFFFFF) | 0x77000000);
        dots.setGravity(Gravity.CENTER_VERTICAL);
        dots.setPadding(dp(14), 0, 0, 0);
        dots.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            setInputView(buildLetters());
        });
        bar.addView(dots, new LinearLayout.LayoutParams(dp(96), dp(20)));
        // petite icone de recherche: saisie rapide d'un compteur du tableau
        LinearLayout sb = new LinearLayout(this);
        sb.setGravity(Gravity.CENTER);
        sb.addView(new OptUi.Icon(this, OptUi.SEARCH, (C_TEXT & 0x00FFFFFF) | 0x99000000),
                new LinearLayout.LayoutParams(dp(13), dp(13)));
        sb.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            setInputView(buildQuick(null));
        });
        bar.addView(sb, new LinearLayout.LayoutParams(dp(56), dp(20)));
        box.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(20)));
        return box;
    }

    /** Ouvre la saisie rapide sur un compteur precis (ex. compteur absent de l'application d'origine). */
    void openQuickFor(final Meters.Row r) {
        showHandler.post(() -> {
            try {
                setInputView(buildQuick(r));
                flashStrip("Non r\u00E9pertori\u00E9 : saisissez son index ici");
            } catch (Throwable ignored) {
            }
        });
    }

    private final QuickPanel.Host quickHost = new QuickPanel.Host() {
        @Override
        public void close() {
            setInputView(buildMain());
        }

        @Override
        public void saved(String msg, Meters.Row r, boolean placeAfterLast) {
            String extra = "";
            if (placeAfterLast && ScanService.instance != null) {
                String m = ScanService.instance.placeAfterLast(r);
                if (!m.isEmpty()) extra = " (" + m + ")";
            }
            ScanService.saveTable(KeyboardService.this);
            Toast.makeText(KeyboardService.this, msg + extra, Toast.LENGTH_LONG).show();
            setInputView(buildMain());
        }

        @Override
        public void openInApp(String key) {
            setInputView(buildMain());
            gotoKeyInApp(key);
        }
    };

    /** Saisie rapide: n'utilise ni la connexion de saisie ni l'envoi automatique (rien n'est ecrit dans l'application). */
    private View buildQuick(Meters.Row pre) {
        quickOpen = true;
        typeMode = false;
        shownType = false;
        typeKb = null;
        strip = null;
        autoHandler.removeCallbacks(autoSubmit);
        buf.setLength(0);
        QuickPanel qp = new QuickPanel(this, quickHost, lightTheme, pre);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(C_BG);
        box.addView(qp, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, qp.heightPx()));
        return box;
    }

    private View wrapWithStrip(View keys, int keysHeight) {
        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setBackgroundColor(C_BG);
        if (stripOn && !discreet && stripAllowed) {
            buildStrip();
            outer.addView(strip, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(STRIP_H_DP)));
        } else {
            strip = null;
        }
        outer.addView(keys, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, keysHeight));
        return outer;
    }

    private View buildKeys() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setBackgroundColor(C_BG);
        root.setPadding(dp(6), dp(6), dp(6), dp(6));

        int totalH = dp(4 * 64);

        // Colonne gauche : chiffres
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);

        String[][] rows = {{"1", "2", "3"}, {"4", "5", "6"}, {"7", "8", "9"}};
        for (String[] row : rows) {
            LinearLayout r = new LinearLayout(this);
            r.setOrientation(LinearLayout.HORIZONTAL);
            for (String k : row) {
                r.addView(digit(k), lp(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
            }
            left.addView(r, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        }
        LinearLayout last = new LinearLayout(this);
        last.setOrientation(LinearLayout.HORIZONTAL);
        last.addView(makeKey("\u2715\nEffacer tout", C_DARK, C_DARK_SH, C_TEXT, 12,
                () -> clearAll()), lp(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        last.addView(digit("0"), lp(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        last.addView(makeKey("\u232B\nEffacer", C_DARK, C_DARK_SH, C_TEXT, 12,
                () -> press("<")), lp(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        left.addView(last, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // Colonne droite : actions
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);

        SpannableString valider = new SpannableString("\u2192\nVALIDER\nL'INDEX");
        valider.setSpan(new RelativeSizeSpan(2f), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        validLabel = valider;
        SpannableString stopTxt = new SpannableString("\u25A0\nSTOP\nAUTO");
        stopTxt.setSpan(new RelativeSizeSpan(2f), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        stopLabel = stopTxt;
        validBtn = makeKey(valider, C_GREEN, C_GREEN_SH, 0xFFFFFFFF, 13, () -> {
            if (ScanService.autoRunning) {
                if (ScanService.instance != null) ScanService.instance.stopAll();
                applyValidState(false);
            } else {
                press("OK");
            }
        });
        validStopShown = false;
        applyValidState(ScanService.autoRunning);
        right.addView(validBtn, lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.6f));
        validHandler.removeCallbacks(validTick);
        validHandler.postDelayed(validTick, 300);

        right.addView(makeKey("ANOMALIE", C_AMBER, C_AMBER_SH, 0xFFFFFFFF, 13, () -> {
            autoHandler.removeCallbacks(autoSubmit);
            setInputView(buildAnomalyMenu());
        }), lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.7f));

        LinearLayout srow = new LinearLayout(this);
        srow.setOrientation(LinearLayout.HORIZONTAL);
        srow.addView(modernKey("Rechercher", 0xFF4F8BFF, 0xFF2563EB, () -> search()),
                lp(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        srow.addView(modernKey("\u21A9 Dernier", 0xFF8B7BFF, 0xFF5B47D6, () -> openLastSent()),
                lp(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.62f));
        right.addView(srow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.HORIZONTAL);
        mid.addView(makeKey("\u2699\nOPTIONS", C_LIGHT, C_LIGHT_SH, C_TEXT, 11,
                () -> setInputView(buildOptions())),
                lp(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        final Button micKey = makeKey(micLabel(), C_LIGHT, C_LIGHT_SH, C_TEXT, 18, () -> onMic());
        micKey.setOnLongClickListener(v -> {
            boolean want;
            try {
                want = !getSharedPreferences("cfg", Context.MODE_PRIVATE).getBoolean("voice_fr", false);
                getSharedPreferences("cfg", Context.MODE_PRIVATE).edit().putBoolean("voice_fr", want).apply();
            } catch (Throwable ignored) {
                want = false;
            }
            if (want && !VoskEngine.installed(this)) {
                // le fran\u00E7ais n'est activ\u00E9 qu'apr\u00E8s le t\u00E9l\u00E9chargement; en attendant, la voix apprise (DA) reste active
                startModelDownload();
            } else {
                flashStrip(want ? "Voix : FRAN\u00C7AIS (nombres et ordres)" : "Voix : DARIJA (appris)");
                if (want) preloadVosk();
            }
            micKey.setText(micLabel());
            return true;
        });
        mid.addView(micKey, lp(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.6f));
        right.addView(mid, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // Bouton libre pour plus tard (aucune action pour l'instant)
        right.addView(makeKey("ABC\nFR / \u0639", C_LIGHT, C_LIGHT_SH, C_TEXT, 12,
                () -> setInputView(buildLetters())),
                lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        root.addView(left, new LinearLayout.LayoutParams(0, totalH, 3f));
        root.addView(right, new LinearLayout.LayoutParams(0, totalH, 1.35f));
        return root;
    }

    /** Appui long sur @ (page symboles): bascule entre clavier complet et clavier discret. */
    private void toggleDiscreet() {
        discreet = !discreet;
        try {
            getSharedPreferences("cfg", Context.MODE_PRIVATE).edit().putBoolean("discreet", discreet).apply();
        } catch (Exception e) {
            // ignore
        }
        if (discreet) {
            setInputView(buildLetters());
        } else {
            setInputView(buildMain());
        }
    }

    private void openDateSettings() {
        try {
            Intent i = new Intent(Settings.ACTION_DATE_SETTINGS);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Throwable t) {
            try {
                Intent i = new Intent(Settings.ACTION_SETTINGS);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            } catch (Throwable t2) {
                Toast.makeText(this, "Parametres indisponibles", Toast.LENGTH_SHORT).show();
            }
        }
    }

    // ---------- Clavier lettres (francais / arabe) ----------

    private boolean arabic = false;
    private boolean caps = false;

    private View buildLettersOld() {
        radiusDp = 7;
        View v;
        try {
            v = buildLettersKeys();
        } finally {
            radiusDp = 12;
        }
        return wrapWithStrip(v, dp((arabic ? 6 : 5) * 46 + 8));
    }

    private void typeLetter(String t) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        ic.commitText(t, 1);
        if (caps) {
            caps = false;
            setInputView(buildLetters());
        }
    }

    /** Touche Entree: action de l'editeur (Suivant/OK/Chercher) si le champ en demande une, sinon retour a la ligne. */
    private void pressEnter() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        EditorInfo ei = getCurrentInputEditorInfo();
        int action = ei == null ? EditorInfo.IME_ACTION_NONE : (ei.imeOptions & EditorInfo.IME_MASK_ACTION);
        boolean noAction = ei != null && (ei.imeOptions & EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0;
        if (ei != null && !noAction && action != EditorInfo.IME_ACTION_NONE
                && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            ic.performEditorAction(action);
        } else {
            ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER));
            ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER));
        }
    }

    private void deleteLetter() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        ic.deleteSurroundingText(1, 0);
    }

    private LinearLayout letterRow(String[] keys) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        for (String k : keys) {
            final String t = (!arabic && caps) ? k.toUpperCase() : k;
            r.addView(makeKey(t, C_KEY, C_KEY_SH, C_TEXT, 19, () -> typeLetter(t)),
                    lpT(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        }
        return r;
    }

    private View buildLettersKeys() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(C_BG);
        root.setPadding(dp(3), dp(4), dp(3), dp(4));

        String[][] rows;
        String[] extra;
        if (arabic) {
            rows = new String[][]{
                {"\u0636", "\u0635", "\u062B", "\u0642", "\u0641", "\u063A", "\u0639", "\u0647", "\u062E", "\u062D", "\u062C"},
                {"\u0634", "\u0633", "\u064A", "\u0628", "\u0644", "\u0627", "\u062A", "\u0646", "\u0645", "\u0643", "\u0630"},
                {"\u0626", "\u0621", "\u0624", "\u0631", "\u0649", "\u0629", "\u0648", "\u0632", "\u0638", "\u0637", "\u062F"}};
            extra = new String[]{"\u0623", "\u0625", "\u0622", "\u060C", "\u061F", "."};
        } else {
            rows = new String[][]{
                {"a", "z", "e", "r", "t", "y", "u", "i", "o", "p"},
                {"q", "s", "d", "f", "g", "h", "j", "k", "l", "m"},
                {"w", "x", "c", "v", "b", "n", "\u00E9", "\u00E8", "\u00E0", "\u00E7"}};
            extra = new String[]{"\u00F9", "\u00EA", "'", "-", "."};
        }
        for (String[] row : rows) {
            root.addView(letterRow(row), new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        }

        if (arabic) {
            // Toutes les voyelles breves (tashkil): fatha, tanwin, damma, kasra, shadda, soukoun, etc.
            String[] tash = {"\u064E", "\u064B", "\u064F", "\u064C", "\u0650", "\u064D",
                    "\u0651", "\u0652", "\u0670", "\u0653", "\u0640"};
            LinearLayout rt = new LinearLayout(this);
            rt.setOrientation(LinearLayout.HORIZONTAL);
            for (String k : tash) {
                final String t = k;
                String shown = k.equals("\u0640") ? "\u0640\u0640" : "\u0640" + k;
                rt.addView(makeKey(shown, C_LIGHT, C_LIGHT_SH, C_TEXT, 18, () -> typeLetter(t)),
                        lpT(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
            }
            root.addView(rt, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        }

        LinearLayout r4 = new LinearLayout(this);
        r4.setOrientation(LinearLayout.HORIZONTAL);
        if (!arabic) {
            r4.addView(makeKey(caps ? "\u21E7 MAJ" : "\u21E7", caps ? C_TEAL : C_DARK,
                    caps ? C_TEAL_SH : C_DARK_SH, caps ? 0xFFFFFFFF : C_TEXT, 14, () -> {
                        caps = !caps;
                        setInputView(buildLetters());
                    }), lpT(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.4f));
        }
        for (String k : extra) {
            final String t = k;
            r4.addView(makeKey(t, C_KEY, C_KEY_SH, C_TEXT, 19, () -> typeLetter(t)),
                    lpT(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        }
        r4.addView(makeKey("\u232B", C_DARK, C_DARK_SH, C_TEXT, 20, () -> deleteLetter()),
                lpT(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.4f));
        root.addView(r4, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout r5 = new LinearLayout(this);
        r5.setOrientation(LinearLayout.HORIZONTAL);
        r5.addView(makeKey(discreet ? "?123" : "123", discreet ? C_DARK : C_TEAL,
                discreet ? C_DARK_SH : C_TEAL_SH, discreet ? C_TEXT : 0xFFFFFFFF, 16,
                () -> {
                    if (discreet) {
                        symPage = 0;
                        setInputView(buildSymbols());
                    } else {
                        setInputView(buildMain());
                    }
                }),
                lpT(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.4f));
        r5.addView(makeKey("?!#", C_LIGHT, C_LIGHT_SH, C_TEXT, 15, () -> {
            symPage = 0;
            setInputView(buildSymbols());
        }), lpT(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.2f));
        r5.addView(makeKey(arabic ? "FR" : "\u0639\u0631", C_LIGHT, C_LIGHT_SH, C_TEXT, 16, () -> {
            arabic = !arabic;
            caps = false;
            setInputView(buildLetters());
        }), lpT(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.4f));
        r5.addView(makeKey(arabic ? "\u0645\u0633\u0627\u0641\u0629" : "espace", C_KEY, C_KEY_SH, C_TEXT, 14,
                () -> typeLetter(" ")), lpT(0, ViewGroup.LayoutParams.MATCH_PARENT, 3f));
        if (discreet) {
            r5.addView(makeKey(",", C_KEY, C_KEY_SH, C_TEXT, 19, () -> typeLetter(",")),
                    lpT(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.2f));
        } else {
            r5.addView(makeKey("\uD83D\uDD0D", C_TEAL, C_TEAL_SH, 0xFFFFFFFF, 18, () -> search()),
                    lpT(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.2f));
        }
        r5.addView(makeKey("\u21B5", discreet ? C_DARK : C_GREEN, discreet ? C_DARK_SH : C_GREEN_SH,
                discreet ? C_TEXT : 0xFFFFFFFF, 22, () -> pressEnter()),
                lpT(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.4f));
        root.addView(r5, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        return root;
    }

    // ---------- Page des symboles ----------

    private int symPage = 0;

    private View buildSymbols() {
        radiusDp = 7;
        View v;
        try {
            v = buildSymbolsKeys();
        } finally {
            radiusDp = 12;
        }
        return wrapWithStrip(v, dp(6 * 46 + 8));
    }

    private View buildSymbolsKeys() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(C_BG);
        root.setPadding(dp(3), dp(4), dp(3), dp(4));

        String[][] rows;
        if (symPage == 0) {
            rows = new String[][]{
                {"1", "2", "3", "4", "5", "6", "7", "8", "9", "0"},
                {"@", "#", "$", "_", "&", "-", "+", "(", ")", "/"},
                {"*", "\"", "'", ":", ";", "!", "?", ",", ".", "%"}};
        } else {
            rows = new String[][]{
                {"~", "`", "|", "\u2022", "\u221A", "\u03C0", "\u00F7", "\u00D7", "\u00B6", "\u2206"},
                {"\u00A3", "\u00A2", "\u20AC", "\u00A5", "^", "\u00B0", "=", "{", "}", "\\"},
                {"\u00A9", "\u00AE", "\u2122", "[", "]", "<", ">", "\u00AB", "\u00BB", "\u2026"}};
        }
        for (String[] row : rows) {
            LinearLayout r = new LinearLayout(this);
            r.setOrientation(LinearLayout.HORIZONTAL);
            for (String k : row) {
                final String t = k;
                Button kb = makeKey(t, C_KEY, C_KEY_SH, C_TEXT, 19, () -> typeLetter(t));
                if (t.equals("@")) {
                    kb.setOnLongClickListener(v -> {
                        toggleDiscreet();
                        return true;
                    });
                }
                r.addView(kb, lpT(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
            }
            root.addView(r, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        }

        // Ligne arabe: chiffres indiens, signes arabes et lettres supplementaires
        String[] ar = symPage == 0
                ? new String[]{"\u0660", "\u0661", "\u0662", "\u0663", "\u0664", "\u0665", "\u0666", "\u0667", "\u0668", "\u0669"}
                : new String[]{"\u060C", "\u061B", "\u061F", "\u067E", "\u0686", "\u06A4", "\u06AF", "\u0671", "\u00AB", "\u00BB"};
        LinearLayout ra = new LinearLayout(this);
        ra.setOrientation(LinearLayout.HORIZONTAL);
        for (String k : ar) {
            final String t = k;
            ra.addView(makeKey(t, C_LIGHT, C_LIGHT_SH, C_TEXT, 19, () -> typeLetter(t)),
                    lpT(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        }
        root.addView(ra, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout rb = new LinearLayout(this);
        rb.setOrientation(LinearLayout.HORIZONTAL);
        rb.addView(makeKey("ABC", discreet ? C_DARK : C_TEAL, discreet ? C_DARK_SH : C_TEAL_SH,
                discreet ? C_TEXT : 0xFFFFFFFF, 16,
                () -> setInputView(buildLetters())),
                lpT(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.4f));
        if (!discreet) {
            rb.addView(makeKey("123", C_LIGHT, C_LIGHT_SH, C_TEXT, 16,
                    () -> setInputView(buildMain())),
                    lpT(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.2f));
        }
        rb.addView(makeKey(symPage == 0 ? "1/2" : "2/2", C_LIGHT, C_LIGHT_SH, C_TEXT, 15, () -> {
            symPage = 1 - symPage;
            setInputView(buildSymbols());
        }), lpT(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.2f));
        rb.addView(makeKey("espace", C_KEY, C_KEY_SH, C_TEXT, 14, () -> typeLetter(" ")),
                lpT(0, ViewGroup.LayoutParams.MATCH_PARENT, 2.4f));
        rb.addView(makeKey("\u232B", C_DARK, C_DARK_SH, C_TEXT, 20, () -> deleteLetter()),
                lpT(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.2f));
        rb.addView(makeKey("\u21B5", discreet ? C_DARK : C_GREEN, discreet ? C_DARK_SH : C_GREEN_SH,
                discreet ? C_TEXT : 0xFFFFFFFF, 22, () -> pressEnter()),
                lpT(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.4f));
        root.addView(rb, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return root;
    }

    // ---------- Menu des anomalies ----------

    private void runAnomaly(int kind) {
        if (ScanService.instance == null) {
            Toast.makeText(this, "Service d'accessibilite desactive", Toast.LENGTH_SHORT).show();
            return;
        }
        setInputView(buildMain());
        ScanService.instance.startManualAnomaly(kind);
    }

    private View buildAnomalyMenu() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(C_BG);
        root.setPadding(dp(6), dp(6), dp(6), dp(6));

        root.addView(makeKey("LOCAL INHABIT\u00C9", C_AMBER, C_AMBER_SH, 0xFFFFFFFF, 17,
                () -> runAnomaly(0)),
                lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(makeKey("COMPTEUR BLOQU\u00C9", C_AMBER, C_AMBER_SH, 0xFFFFFFFF, 17,
                () -> runAnomaly(1)),
                lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(makeKey("COMPTEUR INACCESSIBLE", C_AMBER, C_AMBER_SH, 0xFFFFFFFF, 17,
                () -> runAnomaly(2)),
                lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(makeKey("NOTER SEULEMENT \u25B8", C_LIGHT, C_LIGHT_SH, C_TEXT, 14,
                () -> setInputView(buildNoteMenu())),
                lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.8f));
        root.addView(makeKey("\u2190 RETOUR", C_LIGHT, C_LIGHT_SH, C_TEXT, 15,
                () -> setInputView(buildMain())),
                lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.8f));
        return wrapWithStrip(root, dp(5 * 60));
    }

    /** Note l'anomalie dans notre base (tableau et Excel) sans rien faire dans l'application d'origine. */
    private void noteAnomaly(int kind) {
        setInputView(buildMain());
        if (ScanService.instance == null) {
            flashStrip("Service d'accessibilit\u00E9 d\u00E9sactiv\u00E9");
            return;
        }
        String m = ScanService.instance.noteAnomaly(kind);
        Toast.makeText(this, m, Toast.LENGTH_SHORT).show();
        flashStrip(m);
    }

    private View buildNoteMenu() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(C_BG);
        root.setPadding(dp(6), dp(6), dp(6), dp(6));
        String[] names = {"NOTER: LOCAL INHABIT\u00C9", "NOTER: COMPTEUR BLOQU\u00C9",
                "NOTER: COMPTEUR INACCESSIBLE", "NOTER: ABONN\u00C9 ABSENT"};
        for (int i = 0; i < names.length; i++) {
            final int kind = i;
            root.addView(makeKey(names[i], C_AMBER, C_AMBER_SH, 0xFFFFFFFF, 15, () -> noteAnomaly(kind)),
                    lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        }
        root.addView(makeKey("\u2190 RETOUR", C_LIGHT, C_LIGHT_SH, C_TEXT, 15,
                () -> setInputView(buildAnomalyMenu())),
                lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.8f));
        return wrapWithStrip(root, dp(5 * 60));
    }

    // ---------- Page "Plus d'options" ----------

    private static final long[] UP_PRESETS = {10, 20, 30, 50, 100, 200, 500, 1000, 5000};
    private static final long[] DOWN_PRESETS = {0, 2, 5, 10, 20, 50, 100, 1000};

    private static long nextPreset(long cur, long[] presets) {
        for (long p : presets) if (p > cur) return p;
        return presets[0];
    }

    /** Majuscules -> "Majuscule initiale" (les textes deja en minuscules restent tels quels). */
    private static String nice(String t) {
        if (t == null || t.isEmpty()) return "";
        boolean hasLower = false;
        for (int i = 0; i < t.length(); i++) {
            if (Character.isLowerCase(t.charAt(i))) {
                hasLower = true;
                break;
            }
        }
        if (hasLower) return t;
        String l = t.toLowerCase(java.util.Locale.FRANCE);
        int k = 0;
        while (k < l.length() && !Character.isLetter(l.charAt(k))) k++;
        if (k >= l.length()) return l;
        return l.substring(0, k) + Character.toUpperCase(l.charAt(k)) + l.substring(k + 1);
    }

    /** Pictogramme d'une tuile: symbole place au debut du libelle, sinon deduit des mots-cles. */
    private static String glyphFor(String label) {
        if (label == null || label.isEmpty()) return "\u2022";
        int cp = label.codePointAt(0);
        if (!Character.isLetterOrDigit(cp)) return new String(Character.toChars(cp));
        String u = label.toUpperCase(java.util.Locale.FRANCE);
        if (u.startsWith("CONFIRMER")) return "\u2714";
        if (u.startsWith("REJETER")) return "\u2716";
        if (u.startsWith("HAUSSE")) return "\u25B2";
        if (u.startsWith("BAISSE")) return "\u25BC";
        if (u.startsWith("VITESSE")) return "\u26A1";
        if (u.startsWith("SUIVRE")) return "\u21E2";
        if (u.startsWith("BANDEAU: N")) return "#";
        if (u.startsWith("BANDEAU: ANCIEN")) return "\u2190";
        if (u.startsWith("BANDEAU: NOUVEL")) return "\u2192";
        if (u.startsWith("BANDEAU")) return "\u25AD";
        if (u.startsWith("THEME") || u.startsWith("TH\u00C8ME")) return "\u25D0";
        if (u.startsWith("TOURN")) return "\u2630";
        if (u.startsWith("PHOTO")) return "\u25C9";
        if (u.startsWith("ABC")) return "\u2328";
        return new String(Character.toChars(label.codePointAt(0)));
    }

    /** Tuile "icone": carre arrondi colore + nom dessous (+ valeur pour les reglages). */
    private View optBtn(String label, int face, int shadow, int tc, Runnable a) {
        String title = label, value = null;
        int p = label.lastIndexOf(" : ");
        if (p > 0) {
            title = label.substring(0, p);
            value = label.substring(p + 3);
            int q = value.indexOf(" (toucher");
            if (q >= 0) value = value.substring(0, q);
        }
        String glyph = glyphFor(label);
        String t0 = title.trim();
        if (!t0.isEmpty() && !Character.isLetterOrDigit(t0.codePointAt(0))) {
            t0 = t0.substring(Character.charCount(t0.codePointAt(0))).trim();
        }
        if (t0.startsWith("ABC ")) t0 = t0.substring(4);
        title = nice(t0);
        if (label.startsWith("\u25B6")) return wideBtn(title, face, shadow, tc, a);
        boolean pos = false;
        boolean neutral = face == C_LIGHT;
        if (value != null) {
            value = nice(value.trim());
            pos = value.equals("Oui") || value.equals("Active") || value.equals("Rapide");
            if (pos) {
                face = C_GREEN;
                shadow = C_GREEN_SH;
                tc = 0xFFFFFFFF;
                neutral = false;
            }
        }
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        cell.setPadding(dp(2), dp(6), dp(2), dp(4));
        TextView ic = new TextView(this);
        ic.setText(glyph);
        ic.setGravity(Gravity.CENTER);
        ic.setTextSize(21);
        ic.setIncludeFontPadding(false);
        ic.setTextColor(neutral ? C_TEXT : 0xFFFFFFFF);
        int f1 = neutral ? C_LIGHT : face, f2 = neutral ? C_LIGHT_SH : shadow;
        GradientDrawable up = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{f1, f2});
        up.setCornerRadius(dp(16));
        GradientDrawable down = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{darken(f1), darken(f2)});
        down.setCornerRadius(dp(16));
        StateListDrawable sl = new StateListDrawable();
        sl.addState(new int[]{android.R.attr.state_pressed}, down);
        sl.addState(new int[]{}, up);
        ic.setBackground(sl);
        cell.addView(ic, new LinearLayout.LayoutParams(dp(52), dp(52)));
        TextView cap = new TextView(this);
        cap.setText(title);
        cap.setTextSize(11);
        cap.setGravity(Gravity.CENTER);
        cap.setMaxLines(2);
        cap.setIncludeFontPadding(false);
        cap.setTextColor(C_TEXT);
        cap.setPadding(0, dp(5), 0, 0);
        cell.addView(cap, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        if (value != null) {
            TextView vt = new TextView(this);
            vt.setText(value);
            vt.setTextSize(11);
            vt.setTypeface(Typeface.DEFAULT_BOLD);
            vt.setGravity(Gravity.CENTER);
            vt.setIncludeFontPadding(false);
            vt.setTextColor(pos ? C_GREEN : (C_TEXT & 0x00FFFFFF) | 0xAA000000);
            vt.setPadding(0, dp(1), 0, 0);
            cell.addView(vt, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        final Runnable act = a;
        cell.setClickable(true);
        cell.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            if (act != null) act.run();
        });
        ic.setDuplicateParentStateEnabled(true);
        return cell;
    }

    /** Grand bouton pleine largeur (action principale). */
    private View wideBtn(String title, int face, int shadow, int tc, Runnable a) {
        Button b = new Button(this);
        b.setText("\u25B6  " + title);
        b.setAllCaps(false);
        b.setTextColor(tc);
        b.setTextSize(15);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setGravity(Gravity.CENTER);
        b.setIncludeFontPadding(false);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setStateListAnimator(null);
        GradientDrawable up = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{face, shadow});
        up.setCornerRadius(dp(16));
        GradientDrawable down = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{darken(face), darken(shadow)});
        down.setCornerRadius(dp(16));
        StateListDrawable sl = new StateListDrawable();
        sl.addState(new int[]{android.R.attr.state_pressed}, down);
        sl.addState(new int[]{}, up);
        b.setBackground(sl);
        b.setTag("full");
        final Runnable act = a;
        b.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            if (act != null) act.run();
        });
        return b;
    }

    /** Titre de section dans les options. */
    private TextView section(String t) {
        TextView v = new TextView(this);
        v.setText(t.toUpperCase(java.util.Locale.FRANCE));
        v.setTextSize(11);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setLetterSpacing(0.1f);
        v.setTextColor((C_TEXT & 0x00FFFFFF) | 0x99000000);
        v.setPadding(dp(8), dp(10), 0, dp(2));
        v.setTag("full");
        return v;
    }

    private void addSection(LinearLayout g, String t) {
        g.addView(section(t), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    /** Conteneur en grille: 4 icones par ligne; titres et boutons larges prennent toute la ligne. */
    private class TileGrid extends LinearLayout {
        private LinearLayout row;

        TileGrid(Context c) {
            super(c);
            setOrientation(VERTICAL);
        }

        @Override
        public void addView(View child, ViewGroup.LayoutParams params) {
            if ("full".equals(child.getTag())) {
                row = null;
                boolean wide = child instanceof Button;
                LinearLayout.LayoutParams lf = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, wide ? dp(52) : ViewGroup.LayoutParams.WRAP_CONTENT);
                if (wide) lf.setMargins(dp(4), dp(6), dp(4), dp(4));
                super.addView(child, lf);
                return;
            }
            if (row == null || row.getChildCount() >= 4) {
                row = new LinearLayout(KeyboardService.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                super.addView(row, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            row.addView(child, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
    }

    private void saveBool(String k, boolean v) {
        try {
            getSharedPreferences("cfg", Context.MODE_PRIVATE).edit().putBoolean(k, v).apply();
        } catch (Exception e) {
            // ignore
        }
    }

    private int optTab = 0;
    private String routeReport = "";

    private boolean noSvc() {
        if (ScanService.instance == null) {
            Toast.makeText(this, "Service d'accessibilit\u00E9 d\u00E9sactiv\u00E9", Toast.LENGTH_SHORT).show();
            return true;
        }
        return false;
    }

    /** Lance une action du service apres avoir masque le clavier (flux de recherche). */
    private void hideThen(final Runnable r) {
        if (noSvc()) return;
        searching = true;
        showHandler.postDelayed(() -> searching = false, 6000);
        requestHideSelf(0);
        new Handler(Looper.getMainLooper()).postDelayed(r, 200);
    }

    private View buildOptions() {
        final OptUi.Page pg = new OptUi.Page(this);
        final Runnable again = () -> setInputView(buildOptions());
        final int OFF = OptUi.GREY;

        if (optTab == 0) {
            pg.section("Envoi automatique");
            pg.full(OptUi.PLAY, OptUi.GREEN, "Lancer l'envoi automatique", "Envoie tous les index pr\u00EAts", -1, () -> {
                if (noSvc()) return;
                setInputView(buildMain());
                ScanService.instance.startAuto(false);
            });
            final int nSus = Meters.count(Meters.SUSPECT);
            pg.card(OptUi.CHECK, OptUi.AMBER, "Confirmer les suspects", nSus + " \u00E0 envoyer", -1, () -> {
                if (noSvc()) return;
                setInputView(buildMain());
                ScanService.instance.startAuto(true);
            });
            pg.card(OptUi.CROSS, OptUi.RED, "Rejeter les suspects", nSus + " \u00E0 rejeter", -1, () -> {
                if (noSvc()) return;
                int n = ScanService.instance.rejectSuspects();
                Toast.makeText(this, n + " suspects rejet\u00E9s", Toast.LENGTH_SHORT).show();
                again.run();
            });
            pg.section("Compteurs \u00E0 sauter");
            pg.card(OptUi.UP, ScanService.skipHigh ? OptUi.AMBER : OptUi.GREEN, "\u00C9cart hors limites",
                    ScanService.skipHigh ? "SAUT\u00C9 (\u00E0 confirmer) \u2022 toucher"
                            : "AUTOMATIS\u00C9 quand m\u00EAme \u2022 toucher", -1, () -> {
                ScanService.skipHigh = !ScanService.skipHigh;
                ScanService.saveCfg(this);
                again.run();
            });
            final java.util.TreeMap<String, String> anoms = new java.util.TreeMap<>();
            for (Meters.Row r : Meters.ROWS) {
                if (!Meters.noAnomaly(r.anomaly) && anoms.size() < 12) anoms.put(Meters.norm(r.anomaly), r.anomaly);
            }
            for (final java.util.Map.Entry<String, String> e : anoms.entrySet()) {
                final boolean sk = ScanService.SKIP_ANOMS.contains(e.getKey());
                pg.card(OptUi.CROSS, sk ? OptUi.RED : OptUi.GREEN, e.getValue(),
                        (sk ? "SAUT\u00C9 par l'auto" : "photo + automatis\u00E9") + " \u2022 toucher", -1, () -> {
                    if (sk) ScanService.SKIP_ANOMS.remove(e.getKey());
                    else ScanService.SKIP_ANOMS.add(e.getKey());
                    ScanService.saveCfg(this);
                    again.run();
                });
            }
            final int nSk = ScanService.countSkipped();
            pg.card(OptUi.PLAY, OptUi.AMBER, "Relancer sur les saut\u00E9s",
                    nSk + " compteur(s) saut\u00E9(s) \u2022 apr\u00E8s correction", -1, () -> {
                if (noSvc()) return;
                int n = ScanService.requeueSkipped();
                if (n == 0) {
                    Toast.makeText(this, "Aucun compteur saut\u00E9", Toast.LENGTH_SHORT).show();
                    again.run();
                    return;
                }
                ScanService.saveTable(this);
                setInputView(buildMain());
                ScanService.instance.startAuto(false);
            });
            pg.section("Limites et vitesse");
            pg.card(OptUi.UP, OptUi.GREY, "Seuil fixe (facultatif)", (ScanService.maxJump > 0
                    ? "alerte aussi au-del\u00E0 de +" + ScanService.maxJump + " m\u00B3" : "D\u00C9SACTIV\u00C9: seuil propre \u00E0 chaque compteur")
                    + " \u2022 toucher", -1, () -> {
                long[] fx = {0, 25, 50, 100, 200, 500};
                long nx = fx[0];
                for (int i = 0; i < fx.length; i++) if (fx[i] > ScanService.maxJump) {
                    nx = fx[i];
                    break;
                }
                ScanService.maxJump = nx;
                ScanService.saveCfg(this);
                again.run();
            });
            pg.card(OptUi.UP, OptUi.AMBER, "Seuil intelligent", "\u00D7" + (Smart.factorX10 / 10.0)
                    + " l'habituel \u2022 toucher", -1, () -> {
                int[] f = {15, 20, 25, 30, 40};
                int nx = f[0];
                for (int i = 0; i < f.length; i++) if (f[i] > Smart.factorX10) {
                    nx = f[i];
                    break;
                }
                Smart.factorX10 = nx;
                ScanService.saveCfg(this);
                again.run();
            });
            pg.card(OptUi.UP, OptUi.AMBER, "Hausse minimale", "jamais sous +" + Smart.minRise + " m\u00B3 \u2022 toucher",
                    -1, () -> {
                long[] f = {3, 5, 8, 10, 15};
                long nx = f[0];
                for (int i = 0; i < f.length; i++) if (f[i] > Smart.minRise) {
                    nx = f[i];
                    break;
                }
                Smart.minRise = nx;
                ScanService.saveCfg(this);
                again.run();
            });
            pg.card(OptUi.DOWN, OptUi.AMBER, "Baisse max", "-" + ScanService.maxDrop + " m\u00B3 \u2022 toucher", -1, () -> {
                ScanService.maxDrop = nextPreset(ScanService.maxDrop, DOWN_PRESETS);
                ScanService.saveCfg(this);
                again.run();
            });
            pg.card(OptUi.BOLT, ScanService.fast ? OptUi.GREEN : OFF, "Vitesse auto",
                    ScanService.fast ? "Rapide" : "Normale", ScanService.fast ? 1 : 0, () -> {
                        ScanService.fast = !ScanService.fast;
                        saveBool("fast", ScanService.fast);
                        again.run();
                    });
            pg.card(OptUi.ROUTE, ScanService.followDb ? OptUi.GREEN : OFF, "Suivre l'ordre de la base",
                    ScanService.followDb ? "Activ\u00E9" : "D\u00E9sactiv\u00E9", ScanService.followDb ? 1 : 0, () -> {
                        ScanService.followDb = !ScanService.followDb;
                        saveBool("follow", ScanService.followDb);
                        again.run();
                    });
        } else if (optTab == 1) {
            pg.section("Bandeau");
            pg.card(OptUi.EYE, stripOn ? OptUi.GREEN : OFF, "Bandeau", stripOn ? "Activ\u00E9" : "Masqu\u00E9",
                    stripOn ? 1 : 0, () -> {
                        setStripOn(!stripOn);
                        again.run();
                    });
            pg.card(OptUi.HASH, showNum ? OptUi.GREEN : OFF, "N\u00B0 compteur", showNum ? "Affich\u00E9" : "Masqu\u00E9",
                    showNum ? 1 : 0, () -> {
                        showNum = !showNum;
                        saveBool("showNum", showNum);
                        again.run();
                    });
            pg.card(OptUi.BACK, showOld ? OptUi.GREEN : OFF, "Ancien index", showOld ? "Affich\u00E9" : "Masqu\u00E9",
                    showOld ? 1 : 0, () -> {
                        showOld = !showOld;
                        saveBool("showOld", showOld);
                        again.run();
                    });
            pg.card(OptUi.RIGHT, showNew ? OptUi.GREEN : OFF, "Nouvel index", showNew ? "Affich\u00E9" : "Masqu\u00E9",
                    showNew ? 1 : 0, () -> {
                        showNew = !showNew;
                        saveBool("showNew", showNew);
                        again.run();
                    });
            pg.section("Apparence");
            pg.card(OptUi.SUN, OptUi.AMBER, "Th\u00E8me", lightTheme ? "Clair" : "Sombre", lightTheme ? 1 : 0, () -> {
                lightTheme = !lightTheme;
                saveBool("light", lightTheme);
                applyTheme();
                again.run();
            });
        } else if (optTab == 2) {
            if (!routeReport.isEmpty()) {
                pg.note("Analyse de la tourn\u00E9e", routeReport, () -> {
                    routeReport = "";
                    again.run();
                });
            }
            pg.section("Tourn\u00E9e");
            pg.card(OptUi.ROUTE, OptUi.BLUE, "Tourn\u00E9es",
                    Meters.ACTIVE_T.isEmpty() ? "Toutes" : Meters.ACTIVE_T.size() + " choisie(s)", -1, () -> {
                        try {
                            Intent ti = new Intent(this, TableActivity.class);
                            ti.putExtra("pickTournee", true);
                            ti.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            startActivity(ti);
                        } catch (Throwable t) {
                            Toast.makeText(this, "Impossible d'ouvrir le choix des tourn\u00E9es", Toast.LENGTH_SHORT).show();
                        }
                    });
            pg.card(OptUi.AFTER, OptUi.AMBER, "Placer apr\u00E8s", "Apr\u00E8s le dernier", -1, () -> {
                if (noSvc()) return;
                String pm = ScanService.instance.moveCurrentAfterLast();
                Toast.makeText(this, pm, Toast.LENGTH_LONG).show();
                setInputView(buildMain());
                flashStrip(pm);
            });
            pg.card(OptUi.UNDO, OptUi.GREY, "Annuler", "Dernier d\u00E9placement", -1, () -> {
                if (ScanService.instance != null) {
                    Toast.makeText(this, ScanService.instance.undoMove(), Toast.LENGTH_SHORT).show();
                }
            });
            pg.card(OptUi.LEARN, OptUi.GREY, "Apprendre l'ordre", "Suivre la base", -1,
                    () -> hideThen(() -> {
                        if (ScanService.instance != null) ScanService.instance.learnOrder();
                    }));
            pg.section("Compteurs & journal");
            pg.card(OptUi.CHART, OptUi.GREY, "Analyser tourn\u00E9e", "R\u00E9sum\u00E9", -1, () -> {
                if (noSvc()) return;
                try {
                    routeReport = ScanService.instance.analyzeRoute();
                } catch (Throwable t) {
                    routeReport = "Analyse impossible: " + t;
                }
                again.run();
            });
            pg.card(OptUi.SIGMA, OptUi.TEAL, "Analyser conso", "Liste Cl\u00F4tur\u00E9es", -1,
                    () -> hideThen(() -> {
                        if (ScanService.instance != null) ScanService.instance.analyzeConso();
                    }));
            pg.card(OptUi.DL, OptUi.TEAL, "\u00C0 r\u00E9aliser", "Lire la liste", -1,
                    () -> hideThen(() -> {
                        if (ScanService.instance != null) ScanService.instance.readTodo(false);
                    }));
            pg.card(OptUi.PLUS, OptUi.AMBER, "Ajouter", "Nouveau compteur", -1, () -> {
                if (noSvc()) return;
                setInputView(buildMain());
                ScanService.instance.openAddMeter();
            });
            pg.card(OptUi.DL, OptUi.GREY, "Lire le journal", "Importer", -1, () -> LogAuto.run(this, true));
            pg.section("Syst\u00E8me");
            pg.card(OptUi.CLOCK, OptUi.GREY, "Date et heure", "R\u00E9glages", -1, () -> openDateSettings());
            pg.card(OptUi.KB, OptUi.GREY, "Changer de clavier", "S\u00E9lecteur", -1, () -> {
                InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                imm.showInputMethodPicker();
            });
            pg.card(OptUi.EYE, stripOn ? OptUi.GREEN : OFF, "Bandeau", stripOn ? "Activ\u00E9" : "Masqu\u00E9",
                    stripOn ? 1 : 0, () -> {
                        setStripOn(!stripOn);
                        again.run();
                    });
            pg.card(OptUi.BOLT, ScanService.fast ? OptUi.GREEN : OFF, "Vitesse",
                    ScanService.fast ? "Rapide" : "Normale", ScanService.fast ? 1 : 0, () -> {
                        ScanService.fast = !ScanService.fast;
                        saveBool("fast", ScanService.fast);
                        again.run();
                    });
        } else {
            pg.section("Photos");
            pg.full(OptUi.CAMERA, ScanService.photoProof ? OptUi.GREEN : OFF, "Copie des photos avec bandeau",
                    ScanService.photoProof ? "Activ\u00E9" : "D\u00E9sactiv\u00E9", ScanService.photoProof ? 1 : 0, () -> {
                        ScanService.photoProof = !ScanService.photoProof;
                        saveBool("photoProof4", ScanService.photoProof);
                        again.run();
                    });
        }

        final Runnable[] go = new Runnable[4];
        for (int i = 0; i < 4; i++) {
            final int ti = i;
            go[i] = () -> {
                optTab = ti;
                again.run();
            };
        }
        View tabs = OptUi.tabs(this, optTab, new String[]{"Auto", "Affichage", "Outils", "Photos"},
                () -> setInputView(buildMain()), go);

        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.setBackgroundColor(OptUi.BG);
        sv.setVerticalScrollBarEnabled(false);
        sv.addView(pg.box);
        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setBackgroundColor(OptUi.BG);
        outer.addView(tabs, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        outer.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(2 * 96 + 36)));
        return outer;
    }

    // ---------- Actions ----------

    private void search() {
        if (ScanService.instance == null) {
            Toast.makeText(this, "Service d'accessibilite desactive", Toast.LENGTH_SHORT).show();
            return;
        }
        searching = true;
        showHandler.postDelayed(() -> searching = false, 4000);
        requestHideSelf(0);
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (ScanService.instance != null) ScanService.instance.goSearch();
        }, 150);
    }

    /** Bouton DERNIER: va chercher le dernier compteur envoye dans l'onglet Cloturee(s). */
    private void openLastSent() {
        if (ScanService.instance == null) {
            Toast.makeText(this, "Service d'accessibilite desactive", Toast.LENGTH_SHORT).show();
            return;
        }
        searching = true;
        showHandler.postDelayed(() -> searching = false, 6000);
        requestHideSelf(0);
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (ScanService.instance != null) ScanService.instance.toggleLast();
        }, 150);
    }

    private void clearAll() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        autoHandler.removeCallbacks(autoSubmit);
        ic.deleteSurroundingText(buf.length(), 0);
        buf.setLength(0);
    }

    private void press(String k) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        autoHandler.removeCallbacks(autoSubmit);
        lastKeyT = android.os.SystemClock.uptimeMillis();
        if (k.equals("<")) {
            ic.deleteSurroundingText(1, 0);
            if (buf.length() > 0) buf.setLength(buf.length() - 1);
        } else if (k.equals("OK")) {
            String v = buf.toString();
            // plus d'ecriture dans le presse-papiers: elle ecrasait ce que l'utilisateur copie
            if (v.length() > 0) {
                learnTyped(v);
                if (ScanService.instance == null) {
                    Toast.makeText(this, "Service d'accessibilite desactive", Toast.LENGTH_SHORT).show();
                } else {
                    ScanService.instance.submit(v);
                }
            }
        } else {
            ic.commitText(k, 1);
            buf.append(k);
            autoHandler.postDelayed(autoSubmit, AUTO_DELAY_MS);
        }
    }

    @Override
    public boolean onEvaluateInputViewShown() {
        super.onEvaluateInputViewShown();
        return true;
    }

    // Quand un champ texte prend le focus (ex. retour de l'appareil photo),
    // on demande l'affichage du clavier, sauf pendant le flux Recherche.
    @Override
    public void onStartInput(EditorInfo attribute, boolean restarting) {
        super.onStartInput(attribute, restarting);
        if (!searching && attribute != null && attribute.inputType != 0) {
            showHandler.postDelayed(() -> {
                if (!searching && !isInputViewShown()) requestShowSelf(0);
            }, 400);
        }
    }

    @Override
    public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        try {
            if (getSharedPreferences("cfg", Context.MODE_PRIVATE).getBoolean("voice_fr", false)
                    && VoskEngine.installed(this)) preloadVosk();
        } catch (Throwable ignored) {
        }
        validHandler.removeCallbacks(validTick);
        validHandler.postDelayed(validTick, 300);
        autoHandler.removeCallbacks(autoSubmit);
        buf.setLength(0);
        if (quickOpen && !restarting) {
            quickOpen = false;
            try {
                setInputView(onCreateInputView());
            } catch (Throwable ignored) {
            }
        }
        String pk = info == null || info.packageName == null ? "" : info.packageName;
        boolean allowed = stripAllowedFor(pk);
        // application des releves (et tableurs): pave Releve; ailleurs: clavier de saisie
        if (!restarting) typeMode = !allowed;
        if (allowed != stripAllowed || shownType != typeMode) {
            stripAllowed = allowed;
            try {
                setInputView(onCreateInputView());
            } catch (Throwable ignored) {
            }
        } else if (shownType && typeKb != null) {
            typeKb.start(info);
        }
        if (stripOn && ScanService.instance != null) ScanService.instance.refreshStrip();
        LogAuto.run(this, false);
    }

    private boolean stripAllowed = true;

    /** Le bandeau n'apparait que dans l'application des releves et dans Excel (ou un tableur). */
    private static boolean stripAllowedFor(String pk) {
        if (pk == null || pk.isEmpty()) return true;
        String l = pk.toLowerCase();
        return l.equals("com.somei.smartmobility.srmrsk") || l.contains("excel") || l.contains("sheets")
                || l.contains("wps") || l.contains("moffice") || l.contains("spreadsheet");
    }
}
