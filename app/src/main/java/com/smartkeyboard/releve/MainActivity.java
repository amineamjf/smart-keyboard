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

    private static final String VERSION = "Version 4";
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

        Button clear = new Button(this);
        clear.setText("4. Vider");
        clear.setOnClickListener(v -> {
            synchronized (ScanService.DUMPS) {
                ScanService.DUMPS.clear();
            }
            refresh();
        });

        out = new TextView(this);
        out.setTextSize(11);
        out.setTextIsSelectable(true);
        ScrollView sv = new ScrollView(this);
        sv.addView(out);

        root.addView(open);
        root.addView(refresh);
        root.addView(copy);
        root.addView(clear);
        root.addView(sv);
        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        StringBuilder logs = new StringBuilder();
        StringBuilder screens = new StringBuilder();
        synchronized (ScanService.DUMPS) {
            for (String d : ScanService.DUMPS) {
                if (d.startsWith("LOG")) logs.append(d);
                else screens.append(d).append("\n");
            }
        }
        StringBuilder t = new StringBuilder();
        t.append(VERSION).append("\n");
        t.append("Service: ");
        t.append(ScanService.instance == null ? "INACTIF" : "actif").append("\n\n");
        t.append(logs.length() == 0 ? "Aucun LOG.\n" : logs.toString());
        t.append("\n");
        t.append(screens.length() == 0 ? "Aucun ecran capture." : screens.toString());
        out.setText(t.toString());
    }
}
