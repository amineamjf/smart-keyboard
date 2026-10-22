package com.smartkeyboard.releve;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.inputmethodservice.InputMethodService;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Toast;

public class KeyboardService extends InputMethodService {

    private final StringBuilder buf = new StringBuilder();

    @Override
    public View onCreateInputView() {
        float d = getResources().getDisplayMetrics().density;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setBackgroundColor(0xFF222222);

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        String[][] rows = {
            {"1", "2", "3"}, {"4", "5", "6"}, {"7", "8", "9"}, {"<", "0", "OK"}
        };
        for (String[] row : rows) {
            LinearLayout r = new LinearLayout(this);
            r.setOrientation(LinearLayout.HORIZONTAL);
            for (String k : row) {
                r.addView(key(k), new LinearLayout.LayoutParams(0, (int) (64 * d), 1f));
            }
            left.addView(r, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);

        Button search = new Button(this);
        search.setText("Recherche");
        search.setTextSize(12);
        search.setOnClickListener(v -> {
            if (ScanService.instance == null) {
                Toast.makeText(this, "Service Releve Scan inactif", Toast.LENGTH_SHORT).show();
            } else {
                requestHideSelf(0);
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    if (ScanService.instance != null) ScanService.instance.goSearch();
                }, 150);
            }
        });
        right.addView(search, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) (128 * d)));

        Button abc = new Button(this);
        abc.setText("ABC");
        abc.setOnClickListener(v -> {
            InputMethodManager imm =
                (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            imm.showInputMethodPicker();
        });
        right.addView(abc, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) (128 * d)));

        root.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 3f));
        root.addView(right, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return root;
    }

    private Button key(final String k) {
        Button b = new Button(this);
        b.setText(k.equals("<") ? "\u232B" : k.equals("OK") ? "\u2713" : k);
        b.setTextSize(26);
        b.setOnClickListener(v -> press(k));
        return b;
    }

    private void press(String k) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        if (k.equals("<")) {
            ic.deleteSurroundingText(1, 0);
            if (buf.length() > 0) buf.setLength(buf.length() - 1);
        } else if (k.equals("OK")) {
            ClipboardManager cm =
                (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("index", buf.toString()));
        } else {
            ic.commitText(k, 1);
            buf.append(k);
        }
    }

    @Override
    public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        buf.setLength(0);
    }
}
