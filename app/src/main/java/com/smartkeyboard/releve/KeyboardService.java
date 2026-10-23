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
import android.text.style.RelativeSizeSpan;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Toast;

public class KeyboardService extends InputMethodService {

    public static KeyboardService instance;
    private final StringBuilder buf = new StringBuilder();
    private static final long AUTO_DELAY_MS = 500;
    private final Handler autoHandler = new Handler(Looper.getMainLooper());
    private final Runnable autoSubmit = () -> {
        if (buf.length() > 0 && ScanService.instance != null) press("OK");
    };

    // Affichage automatique du clavier (retour de l'appareil photo, etc.)
    private boolean searching = false;
    private final Handler showHandler = new Handler(Looper.getMainLooper());

    // Couleurs
    private static final int C_BG = 0xFFE4E7EF;
    private static final int C_KEY = 0xFFDDE0E8;
    private static final int C_KEY_SH = 0xFFB9BECB;
    private static final int C_DARK = 0xFFC8CBD5;
    private static final int C_DARK_SH = 0xFFA3A8B6;
    private static final int C_TEXT = 0xFF14213D;
    private static final int C_GREEN = 0xFF2E8F5B;
    private static final int C_GREEN_SH = 0xFF1F6B42;
    private static final int C_TEAL = 0xFF0B6E8A;
    private static final int C_TEAL_SH = 0xFF084F64;
    private static final int C_LIGHT = 0xFFEBEDF3;
    private static final int C_LIGHT_SH = 0xFFBFC3CF;
    private static final int C_RED = 0xFFC93636;
    private static final int C_RED_SH = 0xFF8F2222;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
    }

    @Override
    public void onDestroy() {
        instance = null;
        super.onDestroy();
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
        g.setCornerRadius(dp(14));
        return g;
    }

    private Drawable layers(int face, int shadow) {
        LayerDrawable ld = new LayerDrawable(new Drawable[]{rounded(shadow), rounded(face)});
        ld.setLayerInset(1, 0, 0, 0, dp(3));
        return ld;
    }

    private Drawable keyBg(int face, int shadow) {
        StateListDrawable sl = new StateListDrawable();
        sl.addState(new int[]{android.R.attr.state_pressed}, layers(darken(face), shadow));
        sl.addState(new int[]{}, layers(face, shadow));
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
        b.setTypeface(Typeface.DEFAULT_BOLD);
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

    private View buildMain() {
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
                () -> press("OK")), lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 2f));

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
        right.addView(makeKey("ACTUALISER", C_LIGHT, C_LIGHT_SH, C_TEXT, 12, null),
                lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        root.addView(left, new LinearLayout.LayoutParams(0, totalH, 3f));
        root.addView(right, new LinearLayout.LayoutParams(0, totalH, 1.35f));
        return root;
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
    }
}
