package com.smartkeyboard.releve;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public class MainActivity extends Activity {

    private TextView out;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24, 48, 24, 24);

        Button open = new Button(this);
        open.setText("1. Ouvrir accessibilite");
        open.setOnClickListener(v ->
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));

        Button refresh = new Button(this);
        refresh.setText("2. Actualiser");
        refresh.setOnClickListener(v -> refresh());

        Button copy = new Button(this);
        copy.setText("3. Copier le resultat");
        copy.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager)
                getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("scan", out.getText()));
        });

        out = new TextView(this);
        out.setTextSize(11);
        out.setTextIsSelectable(true);
        ScrollView sv = new ScrollView(this);
        sv.addView(out);

        root.addView(open);
        root.addView(refresh);
        root.addView(copy);
        root.addView(sv);
        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        StringBuilder sb = new StringBuilder();
        synchronized (ScanService.DUMPS) {
            for (String d : ScanService.DUMPS) sb.append(d).append("\n");
        }
        if (sb.length() == 0) sb.append("Aucun ecran capture.");
        out.setText(sb.toString());
    }
}
