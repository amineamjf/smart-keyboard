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
    public static boolean showOld = true, showNew = true, showNum = true;
    private TextView strip;
    private String stripText = "";

    private boolean searching = false;
    private final Handler showHandler = new Handler(Looper.getMainLooper());

    // Couleurs (theme clair par defaut: lisible en plein soleil)
    public static boolean lightTheme = true;
    private int C_BG, C_KEY, C_KEY_SH, C_DARK, C_DARK_SH, C_TEXT, C_LIGHT, C_LIGHT_SH;
    private int C_GREEN, C_GREEN_SH, C_TEAL, C_TEAL_SH, C_RED, C_RED_SH, C_AMBER, C_AMBER_SH;
    private int C_STRIP_BG, C_STRIP_TXT, C_OLD, C_EDGE;

    private void applyTheme() {
        if (lightTheme) {
            C_BG = 0xFFD5D9DF; C_KEY = 0xFFFFFFFF; C_KEY_SH = 0xFFB8BDC6;
            C_DARK = 0xFFB9BFC9; C_DARK_SH = 0xFF9AA1AD; C_TEXT = 0xFF0B0C0E;
            C_LIGHT = 0xFFE9ECF0; C_LIGHT_SH = 0xFFBFC4CC;
            C_GREEN = 0xFF17803F; C_GREEN_SH = 0xFF0F5F2D;
            C_TEAL = 0xFF1F5FD0; C_TEAL_SH = 0xFF1747A0;
            C_RED = 0xFFC62828; C_RED_SH = 0xFF8E1B1B;
            C_AMBER = 0xFFC77000; C_AMBER_SH = 0xFF9A5600;
            C_STRIP_BG = 0xFFF4F5F7; C_STRIP_TXT = 0xFF0B0C0E; C_OLD = 0xFFB35000; C_EDGE = 0xFF9AA1AD;
        } else {
            C_BG = 0xFF17181A; C_KEY = 0xFF38393D; C_KEY_SH = 0xFF2B2C2F;
            C_DARK = 0xFF2A2B2E; C_DARK_SH = 0xFF202124; C_TEXT = 0xFFF1F1F4;
            C_LIGHT = 0xFF303135; C_LIGHT_SH = 0xFF222326;
            C_GREEN = 0xFF2FA866; C_GREEN_SH = 0xFF1F7D4B;
            C_TEAL = 0xFF3D7BF0; C_TEAL_SH = 0xFF2A5CBF;
            C_RED = 0xFFD9484C; C_RED_SH = 0xFFA33236;
            C_AMBER = 0xFFE59A1F; C_AMBER_SH = 0xFFB87710;
            C_STRIP_BG = 0xFF222326; C_STRIP_TXT = 0xFFF1F1F4; C_OLD = 0xFFFFC857; C_EDGE = 0x00000000;
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

    private int radiusDp = 12;

    private LinearLayout.LayoutParams lpT(int w, int h, float weight) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h, weight);
        int m = dp(1.5f);
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
            sp.setSpan(new ForegroundColorSpan(C_OLD), i, t.length(),
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
            strip.setTextColor(C_STRIP_TXT);
            strip.setTextSize(15);
            strip.setTypeface(Typeface.DEFAULT_BOLD);
            strip.setGravity(Gravity.CENTER_VERTICAL | Gravity.CENTER_HORIZONTAL);
            strip.setSingleLine(true);
            strip.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            strip.setBackgroundColor(C_STRIP_BG);
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
        r5.addView(makeKey("123", C_TEAL, C_TEAL_SH, 0xFFFFFFFF, 16,
                () -> setInputView(buildMain())),
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
                () -> typeLetter(" ")), lpT(0, ViewGroup.LayoutParams.MATCH_PARENT, 4f));
        r5.addView(makeKey("\uD83D\uDD0D", C_TEAL, C_TEAL_SH, 0xFFFFFFFF, 18, () -> search()),
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
                r.addView(makeKey(t, C_KEY, C_KEY_SH, C_TEXT, 19, () -> typeLetter(t)),
                        lpT(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
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
        rb.addView(makeKey("ABC", C_TEAL, C_TEAL_SH, 0xFFFFFFFF, 16,
                () -> setInputView(buildLetters())),
                lpT(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.4f));
        rb.addView(makeKey("123", C_LIGHT, C_LIGHT_SH, C_TEXT, 16,
                () -> setInputView(buildMain())),
                lpT(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.2f));
        rb.addView(makeKey(symPage == 0 ? "1/2" : "2/2", C_LIGHT, C_LIGHT_SH, C_TEXT, 15, () -> {
            symPage = 1 - symPage;
            setInputView(buildSymbols());
        }), lpT(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.2f));
        rb.addView(makeKey("espace", C_KEY, C_KEY_SH, C_TEXT, 14, () -> typeLetter(" ")),
                lpT(0, ViewGroup.LayoutParams.MATCH_PARENT, 3f));
        rb.addView(makeKey("\u232B", C_DARK, C_DARK_SH, C_TEXT, 20, () -> deleteLetter()),
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
        root.addView(makeKey("\u2190 RETOUR", C_LIGHT, C_LIGHT_SH, C_TEXT, 15,
                () -> setInputView(buildMain())),
                lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.8f));
        return wrapWithStrip(root, dp(4 * 64));
    }

    // ---------- Page "Plus d'options" ----------

    private Button optBtn(String label, int face, int shadow, int tc, Runnable a) {
        return makeKey(label, face, shadow, tc, 14, a);
    }

    private void saveBool(String k, boolean v) {
        try {
            getSharedPreferences("cfg", Context.MODE_PRIVATE).edit().putBoolean(k, v).apply();
        } catch (Exception e) {
            // ignore
        }
    }

    private View buildOptions() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(C_BG);
        root.setPadding(dp(6), dp(6), dp(6), dp(6));

        int h = dp(52);

        root.addView(optBtn("AUTO : TOUS LES COMPTEURS (avec Nouvel index)",
                C_GREEN, C_GREEN_SH, 0xFFFFFFFF, () -> {
                    if (ScanService.instance == null) {
                        Toast.makeText(this, "Service d'accessibilite desactive", Toast.LENGTH_SHORT).show();
                    } else {
                        setInputView(buildMain());
                        ScanService.instance.startAuto(false);
                    }
                }), lp(ViewGroup.LayoutParams.MATCH_PARENT, h, 0f));

        final int nSus = Meters.count(Meters.SUSPECT);
        root.addView(optBtn("CONFIRMER LES SUSPECTS : envoyer (" + nSus + ")",
                C_AMBER, C_AMBER_SH, 0xFFFFFFFF, () -> {
                    if (ScanService.instance == null) {
                        Toast.makeText(this, "Service d'accessibilite desactive", Toast.LENGTH_SHORT).show();
                    } else {
                        setInputView(buildMain());
                        ScanService.instance.startAuto(true);
                    }
                }), lp(ViewGroup.LayoutParams.MATCH_PARENT, h, 0f));

        root.addView(optBtn("REJETER LES SUSPECTS (" + nSus + ")",
                C_RED, C_RED_SH, 0xFFFFFFFF, () -> {
                    if (ScanService.instance == null) {
                        Toast.makeText(this, "Service d'accessibilite desactive", Toast.LENGTH_SHORT).show();
                    } else {
                        int n = ScanService.instance.rejectSuspects();
                        Toast.makeText(this, n + " suspects rejetes", Toast.LENGTH_SHORT).show();
                        setInputView(buildOptions());
                    }
                }), lp(ViewGroup.LayoutParams.MATCH_PARENT, h, 0f));

        root.addView(optBtn(stripOn ? "BANDEAU : ACTIVE (toucher pour masquer)"
                        : "BANDEAU : MASQUE (toucher pour afficher)",
                C_LIGHT, C_LIGHT_SH, C_TEXT, () -> {
                    setStripOn(!stripOn);
                    setInputView(buildOptions());
                }), lp(ViewGroup.LayoutParams.MATCH_PARENT, h, 0f));

        root.addView(optBtn("Bandeau - numero du compteur : " + (showNum ? "OUI" : "NON"),
                C_LIGHT, C_LIGHT_SH, C_TEXT, () -> {
                    showNum = !showNum;
                    saveBool("showNum", showNum);
                    setInputView(buildOptions());
                }), lp(ViewGroup.LayoutParams.MATCH_PARENT, h, 0f));
        root.addView(optBtn("Bandeau - ancien index : " + (showOld ? "OUI" : "NON"),
                C_LIGHT, C_LIGHT_SH, C_TEXT, () -> {
                    showOld = !showOld;
                    saveBool("showOld", showOld);
                    setInputView(buildOptions());
                }), lp(ViewGroup.LayoutParams.MATCH_PARENT, h, 0f));
        root.addView(optBtn("Bandeau - nouvel index : " + (showNew ? "OUI" : "NON"),
                C_LIGHT, C_LIGHT_SH, C_TEXT, () -> {
                    showNew = !showNew;
                    saveBool("showNew", showNew);
                    setInputView(buildOptions());
                }), lp(ViewGroup.LayoutParams.MATCH_PARENT, h, 0f));

        root.addView(optBtn(lightTheme ? "THEME : CLAIR (toucher pour le sombre)"
                        : "THEME : SOMBRE (toucher pour le clair)",
                C_LIGHT, C_LIGHT_SH, C_TEXT, () -> {
                    lightTheme = !lightTheme;
                    saveBool("light", lightTheme);
                    applyTheme();
                    setInputView(buildOptions());
                }), lp(ViewGroup.LayoutParams.MATCH_PARENT, h, 0f));

        root.addView(optBtn("ABC Changer de clavier", C_LIGHT, C_LIGHT_SH, C_TEXT, () -> {
            InputMethodManager imm =
                    (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            imm.showInputMethodPicker();
        }), lp(ViewGroup.LayoutParams.MATCH_PARENT, h, 0f));

        root.addView(optBtn("\u2190 RETOUR", C_TEAL, C_TEAL_SH, 0xFFFFFFFF,
                () -> setInputView(buildMain())),
                lp(ViewGroup.LayoutParams.MATCH_PARENT, h, 0f));

        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.setBackgroundColor(C_BG);
        sv.addView(root);
        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setBackgroundColor(C_BG);
        outer.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(4 * 64 + 32)));
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
