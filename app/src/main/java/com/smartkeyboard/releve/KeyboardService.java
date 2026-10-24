package com.smartkeyboard.releve;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.inputmethodservice.InputMethodService;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.view.HapticFeedbackConstants;
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
    private TextView strip;
    private String stripText = "";

    private boolean searching = false;
    private final Handler showHandler = new Handler(Looper.getMainLooper());

    // Couleurs
    private static final int C_BG = 0xFF17181A;
    private static final int C_KEY = 0xFF38393D;
    private static final int C_KEY_SH = 0xFF2B2C2F;
    private static final int C_DARK = 0xFF2A2B2E;
    private static final int C_DARK_SH = 0xFF202124;
    private static final int C_TEXT = 0xFFF1F1F4;
    private static final int C_GREEN = 0xFF2FA866;
    private static final int C_GREEN_SH = 0xFF1F7D4B;
    private static final int C_TEAL = 0xFF3D7BF0;
    private static final int C_TEAL_SH = 0xFF2A5CBF;
    private static final int C_LIGHT = 0xFF303135;
    private static final int C_LIGHT_SH = 0xFF222326;
    private static final int C_RED = 0xFFD9484C;
    private static final int C_RED_SH = 0xFFA33236;
    private static final int C_AMBER = 0xFFE59A1F;
    private static final int C_AMBER_SH = 0xFFB87710;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
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
        g.setCornerRadius(dp(12));
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

    private Button digit(final String k) {
        return makeKey(k, C_KEY, C_KEY_SH, C_TEXT, 28, () -> press(k));
    }

    // ---------- Clavier principal ----------

    @Override
    public View onCreateInputView() {
        return buildMain();
    }

    /** Demande l'affichage du clavier (utilise par l'automatisation apres la photo). */
    public void showNow() {
        try {
            requestShowSelf(0);
        } catch (Exception e) {
            // ignore
        }
    }

    private CharSequence stripSpan(String t) {
        if (t == null || t.isEmpty()) return "-";
        SpannableString sp = new SpannableString(t);
        int i = t.indexOf("Ancien");
        if (i >= 0) {
            sp.setSpan(new ForegroundColorSpan(0xFFFFC857), i, t.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return sp;
    }

    public void setStrip(String t) {
        stripText = t == null ? "" : t;
        if (strip != null) strip.setText(stripSpan(stripText));
    }

    private void setStripOn(boolean on) {
        stripOn = on;
        try {
            getSharedPreferences("cfg", Context.MODE_PRIVATE).edit().putBoolean("strip", on).apply();
        } catch (Exception e) {
            // ignore
        }
    }

    private View buildMain() {
        return wrapWithStrip(buildKeys(), ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private View wrapWithStrip(View keys, int keysHeight) {
        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setBackgroundColor(C_BG);
        if (stripOn) {
            strip = new TextView(this);
            strip.setTextColor(C_TEXT);
            strip.setTextSize(15);
            strip.setTypeface(Typeface.DEFAULT_BOLD);
            strip.setGravity(Gravity.CENTER_VERTICAL | Gravity.CENTER_HORIZONTAL);
            strip.setSingleLine(true);
            strip.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            strip.setBackgroundColor(0xFF222326);
            strip.setPadding(dp(8), 0, dp(8), 0);
            strip.setText(stripSpan(stripText));
            outer.addView(strip, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(32)));
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
        right.addView(makeKey(valider, C_GREEN, C_GREEN_SH, 0xFFFFFFFF, 13,
                () -> press("OK")), lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.3f));

        right.addView(makeKey("ANOMALIE", C_AMBER, C_AMBER_SH, 0xFFFFFFFF, 13, () -> {
            autoHandler.removeCallbacks(autoSubmit);
            setInputView(buildAnomalyMenu());
        }), lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.7f));

        right.addView(makeKey("\uD83D\uDD0D RECHERCHER", C_TEAL, C_TEAL_SH, 0xFFFFFFFF, 12,
                () -> search()), lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.HORIZONTAL);
        mid.addView(makeKey("PLUS\nD'OPTIONS", C_LIGHT, C_LIGHT_SH, C_TEXT, 11,
                () -> setInputView(buildOptions())),
                lp(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        mid.addView(makeKey("STOP", C_RED, C_RED_SH, 0xFFFFFFFF, 13,
                () -> {
                    if (ScanService.instance != null) ScanService.instance.stopAll();
                }), lp(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.7f));
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

    // ---------- Clavier lettres (francais / arabe) ----------

    private boolean arabic = false;
    private boolean caps = false;

    private View buildLetters() {
        return wrapWithStrip(buildLettersKeys(), dp(5 * 54));
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
            r.addView(makeKey(t, C_KEY, C_KEY_SH, C_TEXT, 20, () -> typeLetter(t)),
                    lp(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        }
        return r;
    }

    private View buildLettersKeys() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(C_BG);
        root.setPadding(dp(6), dp(6), dp(6), dp(6));

        String[][] rows;
        String[] extra;
        if (arabic) {
            rows = new String[][]{
                {"\u0636", "\u0635", "\u062B", "\u0642", "\u0641", "\u063A", "\u0639", "\u0647", "\u062E", "\u062D", "\u062C"},
                {"\u0634", "\u0633", "\u064A", "\u0628", "\u0644", "\u0627", "\u062A", "\u0646", "\u0645", "\u0643", "\u0630"},
                {"\u0626", "\u0621", "\u0624", "\u0631", "\u0649", "\u0629", "\u0648", "\u0632", "\u0638", "\u0637", "\u062F"}};
            extra = new String[]{"\u060C", "\u061F", ".", "-", "'"};
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

        LinearLayout r4 = new LinearLayout(this);
        r4.setOrientation(LinearLayout.HORIZONTAL);
        if (!arabic) {
            r4.addView(makeKey(caps ? "\u21E7 MAJ" : "\u21E7", caps ? C_TEAL : C_DARK,
                    caps ? C_TEAL_SH : C_DARK_SH, caps ? 0xFFFFFFFF : C_TEXT, 14, () -> {
                        caps = !caps;
                        setInputView(buildLetters());
                    }), lp(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.4f));
        }
        for (String k : extra) {
            final String t = k;
            r4.addView(makeKey(t, C_KEY, C_KEY_SH, C_TEXT, 20, () -> typeLetter(t)),
                    lp(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        }
        r4.addView(makeKey("\u232B", C_DARK, C_DARK_SH, C_TEXT, 20, () -> deleteLetter()),
                lp(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.4f));
        root.addView(r4, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout r5 = new LinearLayout(this);
        r5.setOrientation(LinearLayout.HORIZONTAL);
        r5.addView(makeKey("123", C_TEAL, C_TEAL_SH, 0xFFFFFFFF, 16,
                () -> setInputView(buildMain())),
                lp(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.4f));
        r5.addView(makeKey(arabic ? "FR" : "\u0639\u0631", C_LIGHT, C_LIGHT_SH, C_TEXT, 16, () -> {
            arabic = !arabic;
            caps = false;
            setInputView(buildLetters());
        }), lp(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.4f));
        r5.addView(makeKey(arabic ? "\u0645\u0633\u0627\u0641\u0629" : "espace", C_KEY, C_KEY_SH, C_TEXT, 14,
                () -> typeLetter(" ")), lp(0, ViewGroup.LayoutParams.MATCH_PARENT, 4f));
        r5.addView(makeKey("\uD83D\uDD0D", C_TEAL, C_TEAL_SH, 0xFFFFFFFF, 18, () -> search()),
                lp(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.4f));
        root.addView(r5, new LinearLayout.LayoutParams(
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
        root.addView(makeKey("\u2190 RETOUR", C_LIGHT, C_LIGHT_SH, C_TEXT, 15,
                () -> setInputView(buildMain())),
                lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.8f));
        return wrapWithStrip(root, dp(4 * 64));
    }

    // ---------- Page "Plus d'options" ----------

    private View buildOptions() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(C_BG);
        root.setPadding(dp(6), dp(6), dp(6), dp(6));

        int h = dp(60);
        root.addView(makeKey("ABC Changer de clavier", C_LIGHT, C_LIGHT_SH, C_TEXT, 15, () -> {
            InputMethodManager imm =
                    (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            imm.showInputMethodPicker();
        }), lp(ViewGroup.LayoutParams.MATCH_PARENT, h, 0f));

        root.addView(makeKey("AUTO demarrer l'automatisation", C_GREEN, C_GREEN_SH, 0xFFFFFFFF, 15, () -> {
            if (ScanService.instance == null) {
                Toast.makeText(this, "Service d'accessibilite desactive", Toast.LENGTH_SHORT).show();
            } else {
                setInputView(buildMain());
                ScanService.instance.startAuto();
            }
        }), lp(ViewGroup.LayoutParams.MATCH_PARENT, h, 0f));

        root.addView(makeKey(stripOn ? "BANDEAU : ACTIVE (toucher pour masquer)"
                        : "BANDEAU : MASQUE (toucher pour afficher)",
                C_LIGHT, C_LIGHT_SH, C_TEXT, 14, () -> {
                    setStripOn(!stripOn);
                    setInputView(buildOptions());
                }), lp(ViewGroup.LayoutParams.MATCH_PARENT, h, 0f));

        root.addView(makeKey("\u2190 RETOUR", C_TEAL, C_TEAL_SH, 0xFFFFFFFF, 15,
                () -> setInputView(buildMain())),
                lp(ViewGroup.LayoutParams.MATCH_PARENT, h, 0f));
        return root;
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
        if (k.equals("<")) {
            ic.deleteSurroundingText(1, 0);
            if (buf.length() > 0) buf.setLength(buf.length() - 1);
        } else if (k.equals("OK")) {
            String v = buf.toString();
            ClipboardManager cm =
                    (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("index", v));
            if (v.length() > 0) {
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
        autoHandler.removeCallbacks(autoSubmit);
        buf.setLength(0);
        if (stripOn && ScanService.instance != null) ScanService.instance.refreshStrip();
    }
}
