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

    private static final String VERSION = "Version 6";
    private static final int REQ_IMPORT = 11;
    private TextView out;
    private TextView summary;
    private Button modeBtn;
    private Button photoBtn;
    private Button jumpBtn;
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
        jumpBtn.setOnClickListener(v -> askJump());
        root.addView(jumpBtn);

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
        final EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_NUMBER);
        et.setText(String.valueOf(ScanService.maxJump));
        new AlertDialog.Builder(this)
                .setTitle("Saut maximal entre ancien et nouvel index")
                .setView(et)
                .setPositiveButton("OK", (d, w) -> {
                    try {
                        ScanService.maxJump = Long.parseLong(et.getText().toString().trim());
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
                        + (Meters.lastDup > 0 ? " (" + Meters.lastDup + " doublons ignores)" : "");
            } catch (Exception e) {
                msg = "Echec de l'import: " + e.getMessage();
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
        jumpBtn.setText("Saut maximal d'index: " + ScanService.maxJump);

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
        summary.setText(t.toString() + "Tableau: " + Meters.ROWS.size() + " lignes | envoyes "
                + c[Meters.SENT] + " | simules " + c[Meters.SIM] + " | orange " + orange
                + " | erreurs " + c[Meters.ERROR] + " | non vus " + c[Meters.NEW]);

        StringBuilder o = new StringBuilder();
        o.append(logs.length() == 0 ? "Aucun LOG.\n" : logs.toString());
        o.append("\n");
        o.append(screens.length() == 0 ? "Aucun ecran capture." : screens.toString());
        out.setText(o.toString());
    }
}
