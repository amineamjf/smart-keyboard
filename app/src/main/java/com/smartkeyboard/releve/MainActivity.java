package com.smartkeyboard.releve;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.InputStream;
import java.util.List;

public class MainActivity extends Activity {

    private static final String VERSION = "Version 31";
    private static final int REQ_IMPORT = 11;
    private TextView out;
    private TextView summary;
    private Button modeBtn;
    private Button photoBtn;
    private Button jumpBtn;
    private Button dropBtn;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            refresh();
            ui.postDelayed(this, 1500);
        }
    };

    private Button btn(String label, Runnable r) {
        Button b = new Button(this);
        b.setText(label);
        b.setOnClickListener(v -> r.run());
        return b;
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        ScanService.ensureLoaded(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24, 48, 24, 24);

        root.addView(btn("1. Ouvrir accessibilite", () ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))));

        root.addView(btn("2. Importer le tableau (Excel / CSV)", this::pickFile));

        root.addView(btn("3. Voir le tableau (couleurs)", () ->
                startActivity(new Intent(this, TableActivity.class))));

        modeBtn = new Button(this);
        modeBtn.setOnClickListener(v -> {
            if (ScanService.instance == null) {
                Toast.makeText(this, "Service inactif", Toast.LENGTH_SHORT).show();
                return;
            }
            ScanService.live = !ScanService.live;
            refresh();
        });
        root.addView(modeBtn);

        photoBtn = new Button(this);
        photoBtn.setOnClickListener(v -> {
            ScanService.photoMode = (ScanService.photoMode + 1) % 3;
            ScanService.saveCfg(this);
            refresh();
        });
        root.addView(photoBtn);

        jumpBtn = new Button(this);
        jumpBtn.setOnClickListener(v -> askLimit(true));
        root.addView(jumpBtn);

        dropBtn = new Button(this);
        dropBtn.setOnClickListener(v -> askLimit(false));
        root.addView(dropBtn);

        root.addView(btn("4. DEMARRER L'AUTOMATISATION", () -> {
            if (ScanService.instance == null) {
                Toast.makeText(this, "Service inactif", Toast.LENGTH_SHORT).show();
                return;
            }
            ScanService.instance.startAuto();
            refresh();
        }));

        root.addView(btn("ARRETER", () -> {
            if (ScanService.instance != null) {
                ScanService.instance.stopAll();
                ScanService.instance.autoStop("AUTO arrete (bouton)");
            }
            refresh();
        }));

        root.addView(btn("Test: glisser vers le suivant", () -> {
            if (ScanService.instance != null) ScanService.instance.testSwipe(true);
        }));
        root.addView(btn("Test: glisser vers le precedent", () -> {
            if (ScanService.instance != null) ScanService.instance.testSwipe(false);
        }));

        root.addView(btn("Remettre les statuts a zero", () -> {
            Meters.resetStatuses();
            ScanService.saveTable(this);
            refresh();
        }));

        root.addView(btn("Copier le journal", () -> {
            ClipboardManager cm = (ClipboardManager)
                    getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("scan", out.getText()));
        }));

        root.addView(btn("Vider le journal", () -> {
            synchronized (ScanService.DUMPS) {
                ScanService.DUMPS.clear();
            }
            refresh();
        }));

        summary = new TextView(this);
        summary.setTextSize(13);
        summary.setPadding(0, 16, 0, 8);
        root.addView(summary);

        out = new TextView(this);
        out.setTextSize(11);
        out.setTextIsSelectable(true);
        root.addView(out);

        ScrollView sv = new ScrollView(this);
        sv.addView(root);
        setContentView(sv);
    }

    private void askJump() {
        askLimit(true);
    }

    /** up=true: hausse maximale acceptee; false: baisse maximale acceptee. */
    private void askLimit(final boolean up) {
        final EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_NUMBER);
        et.setText(String.valueOf(up ? ScanService.maxJump : ScanService.maxDrop));
        new AlertDialog.Builder(this)
                .setTitle(up ? "Hausse maximale (nouvel - ancien), en m3"
                        : "Baisse maximale (ancien - nouvel), en m3")
                .setView(et)
                .setPositiveButton("OK", (d, w) -> {
                    try {
                        long v = Long.parseLong(et.getText().toString().trim());
                        if (up) ScanService.maxJump = v;
                        else ScanService.maxDrop = v;
                        ScanService.saveCfg(this);
                    } catch (Exception ignored) {
                    }
                    refresh();
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    private void pickFile() {
        Runnable go = () -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            startActivityForResult(i, REQ_IMPORT);
        };
        if (Meters.ROWS.isEmpty()) {
            go.run();
        } else {
            new AlertDialog.Builder(this)
                    .setTitle("Remplacer le tableau actuel ?")
                    .setMessage(Meters.ROWS.size() + " lignes et leurs statuts seront remplaces.")
                    .setPositiveButton("Remplacer", (d, w) -> go.run())
                    .setNegativeButton("Annuler", null)
                    .show();
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_IMPORT || res != RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        new Thread(() -> {
            String msg;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                List<List<String[]>> sheets = TableReader.read(in);
                final int n = Meters.importSheets(sheets);
                ScanService.saveTable(this);
                msg = n + " compteurs importes"
                        + (Meters.lastDup > 0 ? " (" + Meters.lastDup + " doublons ignores)" : "")
                        + "\n" + Meters.lastReport;
            } catch (Throwable e) {
                msg = "Echec de l'import: " + e;
                try {
                    java.io.StringWriter sw = new java.io.StringWriter();
                    e.printStackTrace(new java.io.PrintWriter(sw));
                    String st = "IMPORT ERREUR\n" + sw;
                    java.io.FileOutputStream fo = new java.io.FileOutputStream(App.crashFile(this));
                    fo.write(st.substring(0, Math.min(6000, st.length())).getBytes("UTF-8"));
                    fo.close();
                } catch (Throwable ignored) {
                }
            }
            final String m = msg;
            ui.post(() -> {
                Toast.makeText(this, m, Toast.LENGTH_LONG).show();
                refresh();
            });
        }).start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        ui.post(ticker);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(ticker);
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
        boolean live = ScanService.live;
        modeBtn.setText(live
                ? "MODE REEL ACTIF (toucher pour passer en simulation)"
                : "Mode simulation (toucher pour activer le reel)");
        String[] pm = {"AUTO (prend la photo)", "SAUTER (compteur saute)", "ARRETER l'automatisation"};
        photoBtn.setText("Si photo demandee: " + pm[ScanService.photoMode]);
        jumpBtn.setText("Hausse maximale acceptee: +" + ScanService.maxJump + " m3 (toucher pour changer)");
        dropBtn.setText("Baisse maximale acceptee: -" + ScanService.maxDrop + " m3 (toucher pour changer)");

        int[] c = Meters.counts();
        int orange = c[Meters.EXTRA] + c[Meters.NODATA] + c[Meters.PHOTO]
                + c[Meters.SUSPECT] + c[Meters.UNKNOWN];
        StringBuilder t = new StringBuilder();
        t.append(VERSION).append("\n");
        t.append("Service: ").append(ScanService.instance == null ? "INACTIF" : "actif").append("\n");
        t.append(live ? "MODE: REEL (les envois sont actifs)\n"
                : "MODE: SIMULATION (aucun envoi)\n");
        t.append("Automatisation: ").append(ScanService.autoRunning ? "EN COURS" : "arretee")
                .append("\n");
        if (!Meters.lastReport.isEmpty()) t.append("Import: ").append(Meters.lastReport).append("\n");
        summary.setText(t.toString() + "Tableau: " + Meters.ROWS.size() + " lignes | envoyes "
                + c[Meters.SENT] + " | simules " + c[Meters.SIM] + " | orange " + orange
                + " | erreurs " + c[Meters.ERROR] + " | non vus " + c[Meters.NEW]);

        StringBuilder o = new StringBuilder();
        try {
            java.io.File cf = App.crashFile(this);
            if (cf.exists() && cf.length() > 0) {
                java.io.FileInputStream fi = new java.io.FileInputStream(cf);
                byte[] bb = new byte[(int) Math.min(cf.length(), 6000)];
                int rd = fi.read(bb);
                fi.close();
                o.append("=== DERNIER PLANTAGE ===\n").append(new String(bb, 0, Math.max(rd, 0), "UTF-8"))
                        .append("\n=== FIN ===\n\n");
            }
        } catch (Throwable ignored) {
        }
        o.append(logs.length() == 0 ? "Aucun LOG.\n" : logs.toString());
        o.append("\n");
        o.append(screens.length() == 0 ? "Aucun ecran capture." : screens.toString());
        out.setText(o.toString());
    }
}
