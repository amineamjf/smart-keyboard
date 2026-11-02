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
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.InputStream;
import java.util.List;

public class MainActivity extends Activity {

    private static final String VERSION = "Version 91";
    private static final int REQ_IMPORT = 11;
    private TextView out;
    private TextView summary;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            refresh();
            ui.postDelayed(this, 1500);
        }
    };

    // ---------- interface ----------
    private static final int BLUE = 0xFF1F5FD0, GREEN = 0xFF17803F, RED = 0xFFC62828, AMBER = 0xFFC77000,
            INK = 0xFF0B0C0E, GRAY = 0xFF5B6573, PALE = 0xFFE8EFFC, CHIP = 0xFFEEF0F3, PAGE = 0xFFF3F5F8;
    private static final int REQ_LOGDIR = 12;

    private TextView chipService, chipMode, chipRows;
    private TextView accVal, kbVal, tabVal, autoLine, logSub, logVal;
    private TextView modeVal, photoVal, jumpVal, dropVal;
    private LinearLayout advBox;

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private android.graphics.drawable.GradientDrawable shape(int color, int radiusDp) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private TextView tv(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        return t;
    }

    private LinearLayout.LayoutParams lp(int w, int h, int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.topMargin = dp(top);
        return p;
    }

    private TextView chip(String s) {
        TextView t = tv(s, 12, 0xFFFFFFFF, false);
        t.setPadding(dp(12), dp(6), dp(12), dp(6));
        t.setBackground(shape(0x33FFFFFF, 20));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2);
        p.rightMargin = dp(8);
        t.setLayoutParams(p);
        return t;
    }

    private TextView valueChip(String s, Runnable r) {
        TextView t = tv(s, 14, INK, true);
        t.setPadding(dp(12), dp(6), dp(12), dp(6));
        t.setBackground(shape(CHIP, 10));
        if (r != null) t.setOnClickListener(v -> r.run());
        return t;
    }

    private LinearLayout card(String title) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(16), dp(16), dp(16), dp(10));
        c.setBackground(shape(0xFFFFFFFF, 18));
        c.setElevation(dp(2));
        TextView h = tv(title.toUpperCase(), 12, GRAY, true);
        h.setLetterSpacing(0.08f);
        h.setPadding(0, 0, 0, dp(6));
        c.addView(h);
        return c;
    }

    private LinearLayout row(String icon, String title, TextView sub, View right, Runnable r) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(8), 0, dp(8));
        TextView ic = tv(icon, 18, INK, false);
        ic.setGravity(android.view.Gravity.CENTER);
        ic.setBackground(shape(PALE, 12));
        row.addView(ic, new LinearLayout.LayoutParams(dp(40), dp(40)));
        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.VERTICAL);
        mid.setPadding(dp(12), 0, dp(8), 0);
        mid.addView(tv(title, 15, INK, true));
        if (sub != null) mid.addView(sub);
        row.addView(mid, new LinearLayout.LayoutParams(0, -2, 1f));
        if (right != null) row.addView(right);
        if (r != null) {
            android.util.TypedValue ta = new android.util.TypedValue();
            getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ta, true);
            row.setBackgroundResource(ta.resourceId);
            row.setOnClickListener(v -> r.run());
        }
        return row;
    }

    private TextView subText(String s) {
        return tv(s, 12, GRAY, false);
    }

    private TextView pill(String label, int bg, int fg, Runnable r) {
        TextView t = tv(label, 15, fg, true);
        t.setGravity(android.view.Gravity.CENTER);
        t.setPadding(dp(14), dp(14), dp(14), dp(14));
        t.setBackground(shape(bg, 14));
        t.setOnClickListener(v -> r.run());
        t.setLayoutParams(lp(-1, -2, 8));
        return t;
    }

    private void needService(Runnable r) {
        if (ScanService.instance == null) {
            Toast.makeText(this, "Service inactif: activez l'accessibilite", Toast.LENGTH_SHORT).show();
            return;
        }
        r.run();
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        ScanService.ensureLoaded(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(PAGE);

        // en-tete
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.VERTICAL);
        top.setBackgroundColor(BLUE);
        top.setPadding(dp(20), dp(34), dp(20), dp(18));
        top.addView(tv("Relev\u00E9 Clavier", 22, 0xFFFFFFFF, true));
        top.addView(tv(VERSION, 13, 0xCCFFFFFF, false));
        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setPadding(0, dp(12), 0, 0);
        chipService = chip("");
        chipMode = chip("");
        chipRows = chip("");
        chips.addView(chipService);
        chips.addView(chipMode);
        chips.addView(chipRows);
        top.addView(chips);
        root.addView(top);

        // demarrage
        LinearLayout c1 = card("D\u00E9marrage");
        accVal = tv("", 13, GREEN, true);
        kbVal = tv("", 13, GREEN, true);
        tabVal = tv("", 13, GREEN, true);
        c1.addView(row("\u267F", "Service d'accessibilit\u00E9", subText("N\u00E9cessaire pour lire l'\u00E9cran"), accVal,
                () -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))));
        c1.addView(row("\u2328", "Clavier", subText("Choisir \u00AB Relev\u00E9 Clavier \u00BB"), kbVal,
                () -> startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))));
        c1.addView(row("\uD83D\uDCCB", "Tableau", subText("Excel / CSV de la tourn\u00E9e"), tabVal, this::pickFile));
        c1.addView(pill("Importer un nouveau tableau", PALE, BLUE, this::pickFile));
        c1.addView(pill("Voir le tableau", PALE, BLUE, () ->
                startActivity(new Intent(this, TableActivity.class))));
        root.addView(c1, lp(-1, -2, 12));
        setSide(c1);

        // automatisation
        LinearLayout c2 = card("Automatisation");
        modeVal = valueChip("", () -> needService(() -> {
            ScanService.live = !ScanService.live;
            refresh();
        }));
        c2.addView(row("\u26A1", "Mode", subText("Simulation = aucun envoi r\u00E9el"), modeVal, null));
        autoLine = subText("");
        autoLine.setPadding(0, dp(4), 0, dp(2));
        c2.addView(autoLine);
        c2.addView(pill("D\u00E9marrer l'automatisation", GREEN, 0xFFFFFFFF, () -> needService(() -> {
            ScanService.instance.startAuto();
            refresh();
        })));
        c2.addView(pill("Arr\u00EAter", 0xFFFBE4E4, RED, () -> {
            if (ScanService.instance != null) {
                ScanService.instance.stopAll();
                ScanService.instance.autoStop("AUTO arrete (bouton)");
            }
            refresh();
        }));
        root.addView(c2, lp(-1, -2, 12));
        setSide(c2);

        // lectures de l'application
        LinearLayout c3 = card("Lectures de l'application");
        logSub = subText("");
        logVal = tv("", 13, GREEN, true);
        c3.addView(row("\uD83D\uDCC2", "Dossier du journal", logSub, logVal, this::pickLogDir));
        c3.addView(pill("Lire le journal maintenant", BLUE, 0xFFFFFFFF, () -> LogAuto.run(this, true)));
        c3.addView(pill("Choisir le dossier logs", PALE, BLUE, this::pickLogDir));
        c3.addView(pill("Choisir des fichiers .txt / exporter", PALE, BLUE, () ->
                startActivity(new Intent(this, TableActivity.class))));
        root.addView(c3, lp(-1, -2, 12));
        setSide(c3);

        // reglages
        LinearLayout c4 = card("R\u00E9glages");
        jumpVal = valueChip("", () -> askLimit(true));
        dropVal = valueChip("", () -> askLimit(false));
        photoVal = valueChip("", () -> {
            ScanService.photoMode = (ScanService.photoMode + 1) % 3;
            ScanService.saveCfg(this);
            refresh();
        });
        c4.addView(row("\u2197", "Hausse maximale", subText("Au-del\u00E0: \u00E0 valider \u00E0 la main"), jumpVal, () -> askLimit(true)));
        c4.addView(row("\u2198", "Baisse maximale", subText("Au-del\u00E0: \u00E0 valider \u00E0 la main"), dropVal, () -> askLimit(false)));
        c4.addView(row("\uD83D\uDCF7", "Si photo demand\u00E9e", subText("Toucher pour changer"), photoVal, () -> {
            ScanService.photoMode = (ScanService.photoMode + 1) % 3;
            ScanService.saveCfg(this);
            refresh();
        }));
        root.addView(c4, lp(-1, -2, 12));
        setSide(c4);

        // avance
        LinearLayout c5 = card("Avanc\u00E9");
        advBox = new LinearLayout(this);
        advBox.setOrientation(LinearLayout.VERTICAL);
        advBox.setVisibility(View.GONE);
        TextView advArrow = tv("\u203A", 22, GRAY, false);
        c5.addView(row("\u2699", "Journal technique",
                subText("Copier \u00B7 Vider \u00B7 Remettre les statuts \u00E0 z\u00E9ro"), advArrow, () -> {
                    boolean open = advBox.getVisibility() != View.VISIBLE;
                    advBox.setVisibility(open ? View.VISIBLE : View.GONE);
                    advArrow.setText(open ? "\u25BE" : "\u203A");
                }));
        advBox.addView(pill("Remettre les statuts \u00E0 z\u00E9ro", PALE, BLUE, () -> {
            Meters.resetStatuses();
            ScanService.saveTable(this);
            refresh();
        }));
        advBox.addView(pill("Copier le journal", PALE, BLUE, () -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("scan", out.getText()));
            Toast.makeText(this, "Journal copi\u00E9", Toast.LENGTH_SHORT).show();
        }));
        advBox.addView(pill("Vider le journal", 0xFFFBE4E4, RED, () -> {
            synchronized (ScanService.DUMPS) {
                ScanService.DUMPS.clear();
            }
            refresh();
        }));
        summary = new TextView(this);
        summary.setTextSize(13);
        summary.setPadding(0, dp(12), 0, dp(6));
        advBox.addView(summary);
        out = new TextView(this);
        out.setTextSize(11);
        out.setTextIsSelectable(true);
        advBox.addView(out);
        c5.addView(advBox);
        root.addView(c5, lp(-1, -2, 12));
        setSide(c5);
        root.setPadding(0, 0, 0, dp(24));

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(PAGE);
        sv.addView(root);
        setContentView(sv);
        if (getIntent() != null && getIntent().getBooleanExtra("pickTable", false)) {
            sv.post(this::pickFile);
        }
    }

    /** Marges laterales d'une carte (14 dp). */
    private void setSide(View c) {
        LinearLayout.LayoutParams p = (LinearLayout.LayoutParams) c.getLayoutParams();
        p.leftMargin = dp(14);
        p.rightMargin = dp(14);
        c.setLayoutParams(p);
    }

    private void pickLogDir() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(i, REQ_LOGDIR);
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
        if (req == REQ_LOGDIR) {
            if (res == RESULT_OK && data != null && data.getData() != null) {
                LogAuto.saveTree(this, data.getData());
                LogAuto.run(this, true);
                refresh();
            }
            return;
        }
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
        boolean svc = ScanService.instance != null;
        chipService.setText(svc ? "\u25CF Accessibilit\u00E9 active" : "\u25CB Accessibilit\u00E9 inactive");
        chipMode.setText(live ? "\u25CF Mode r\u00E9el" : "Simulation");
        chipRows.setText(Meters.ROWS.size() + " compteurs");
        accVal.setText(svc ? "\u2713 Actif" : "\u00C0 activer");
        accVal.setTextColor(svc ? GREEN : AMBER);
        String defIme = Settings.Secure.getString(getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
        boolean kbOn = defIme != null && defIme.startsWith(getPackageName() + "/");
        kbVal.setText(kbOn ? "\u2713 Actif" : "\u00C0 choisir");
        kbVal.setTextColor(kbOn ? GREEN : AMBER);
        boolean hasTab = !Meters.ROWS.isEmpty();
        tabVal.setText(hasTab ? "\u2713 " + Meters.ROWS.size() + " lignes" : "\u00C0 importer");
        tabVal.setTextColor(hasTab ? GREEN : AMBER);
        modeVal.setText(live ? "R\u00C9EL" : "SIMULATION");
        modeVal.setTextColor(live ? GREEN : AMBER);
        autoLine.setText("Automatisation : " + (ScanService.autoRunning ? "EN COURS" : "arr\u00EAt\u00E9e"));
        boolean hasDir = LogAuto.tree(this) != null;
        long lr = LogAuto.lastRunMs();
        logSub.setText(hasDir
                ? "Lu automatiquement \u00B7 " + (lr > 0 ? "derni\u00E8re lecture il y a "
                + Math.max(0, (System.currentTimeMillis() - lr) / 60000) + " min" : "pas encore lu")
                : "\u00C0 choisir une seule fois");
        logVal.setText(hasDir ? "\u2713" : "\u00C0 choisir");
        logVal.setTextColor(hasDir ? GREEN : AMBER);
        String[] pm = {"Auto", "Sauter", "Arr\u00EAter"};
        photoVal.setText(pm[ScanService.photoMode]);
        jumpVal.setText("+" + ScanService.maxJump + " m\u00B3");
        dropVal.setText("\u2212" + ScanService.maxDrop + " m\u00B3");

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
