package com.smartkeyboard.releve;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.OutputStream;
import java.util.ArrayList;

/** Liste des compteurs du tableau, colores selon leur statut. */
public class TableActivity extends Activity {

    private static final int REQ_EXPORT = 12;
    private static final int REQ_XLSX = 13;
    private static final int F_ALL = 0, F_SENT = 1, F_ORANGE = 2, F_ERR = 3, F_NEW = 4;

    private final ArrayList<Meters.Row> shown = new ArrayList<>();
    private int filter = F_ALL;
    private BaseAdapter adapter;
    private TextView head;

    private static int color(int status) {
        switch (status) {
            case Meters.SENT: return 0xFFD6F0E0;
            case Meters.SIM: return 0xFFDCE8FB;
            case Meters.EXTRA:
            case Meters.NODATA:
            case Meters.UNKNOWN: return 0xFFFFE3B8;
            case Meters.PHOTO:
            case Meters.SUSPECT: return 0xFFFFCF99;
            case Meters.ERROR: return 0xFFF7C6C6;
            default: return 0xFFEDEFF3;
        }
    }

    private boolean pass(Meters.Row r) {
        switch (filter) {
            case F_SENT: return r.status == Meters.SENT;
            case F_ORANGE: return r.status == Meters.EXTRA || r.status == Meters.NODATA
                    || r.status == Meters.PHOTO || r.status == Meters.SUSPECT
                    || r.status == Meters.UNKNOWN;
            case F_ERR: return r.status == Meters.ERROR;
            case F_NEW: return r.status == Meters.NEW || r.status == Meters.SIM;
            default: return true;
        }
    }

    private void reload() {
        shown.clear();
        for (Meters.Row r : Meters.ROWS) if (pass(r)) shown.add(r);
        head.setText(shown.size() + " / " + Meters.ROWS.size() + " lignes");
        adapter.notifyDataSetChanged();
    }

    private Button fbtn(String label, final int f) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setOnClickListener(v -> {
            filter = f;
            reload();
        });
        return b;
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        ScanService.ensureLoaded(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(12, 36, 12, 12);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.addView(fbtn("Tous", F_ALL));
        bar.addView(fbtn("Envoyes", F_SENT));
        bar.addView(fbtn("Orange", F_ORANGE));
        bar.addView(fbtn("Erreurs", F_ERR));
        bar.addView(fbtn("Non vus", F_NEW));
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.addView(bar);
        root.addView(hs);

        Button exp = new Button(this);
        Button expx = new Button(this);
        expx.setText("Exporter le rapport Excel (.xlsx)");
        expx.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            String d = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.FRANCE)
                    .format(new java.util.Date());
            i.putExtra(Intent.EXTRA_TITLE, "rapport-releve-" + d + ".xlsx");
            startActivityForResult(i, REQ_XLSX);
        });
        root.addView(expx);

        exp.setText("Exporter le rapport (CSV)");
        exp.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("text/csv");
            i.putExtra(Intent.EXTRA_TITLE, "rapport-releve.csv");
            startActivityForResult(i, REQ_EXPORT);
        });
        root.addView(exp);

        head = new TextView(this);
        head.setPadding(8, 8, 8, 8);
        root.addView(head);

        ListView lv = new ListView(this);
        adapter = new BaseAdapter() {
            @Override
            public int getCount() {
                return shown.size();
            }

            @Override
            public Object getItem(int i) {
                return shown.get(i);
            }

            @Override
            public long getItemId(int i) {
                return i;
            }

            @Override
            public View getView(int i, View v, ViewGroup p) {
                TextView t = v instanceof TextView ? (TextView) v : new TextView(TableActivity.this);
                Meters.Row r = shown.get(i);
                t.setPadding(16, 12, 16, 12);
                t.setTextSize(13);
                t.setTextColor(Color.BLACK);
                t.setBackgroundColor(r.warn.isEmpty() ? color(r.status) : 0xFFFFE0B2);
                StringBuilder sb = new StringBuilder();
                sb.append(r.compteur);
                if (!r.client.isEmpty()) sb.append("  -  ").append(r.client);
                sb.append("\nIndex: ").append(r.oldIdx.isEmpty() ? "?" : r.oldIdx)
                        .append(" > ").append(r.newIdx.isEmpty() ? "-" : r.newIdx);
                if (!Meters.noAnomaly(r.anomaly)) sb.append("   Anomalie: ").append(r.anomaly);
                if (!Meters.noAnnotation(r.annotation)) {
                    sb.append("   Annotation: ").append(r.annotation);
                }
                if (!r.warn.isEmpty()) sb.append("\n\u26A0 ").append(r.warn);
                sb.append("\n").append(Meters.statusLabel(r.status));
                if (!r.note.isEmpty()) sb.append(" - ").append(r.note);
                t.setText(sb.toString());
                return t;
            }
        };
        lv.setAdapter(adapter);
        lv.setDividerHeight(4);
        lv.setOnItemClickListener((parent, view, pos, id) -> edit(shown.get(pos)));
        root.addView(lv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        reload();
    }

    /** Corriger le nouvel index d'une ligne, puis la remettre "non vu" pour une nouvelle passe. */
    private void edit(final Meters.Row r) {
        final EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_NUMBER);
        et.setText(r.newIdx);
        new AlertDialog.Builder(this)
                .setTitle(r.compteur)
                .setMessage("Nouvel index (la ligne sera remise a 'Non vu')")
                .setView(et)
                .setPositiveButton("Enregistrer", (d, w) -> {
                    r.newIdx = Meters.cleanNumber(et.getText().toString());
                    r.status = Meters.NEW;
                    r.note = "";
                    ScanService.saveTable(this);
                    reload();
                })
                .setNeutralButton("Marquer envoye", (d, w) -> {
                    r.status = Meters.SENT;
                    r.note = "marque a la main";
                    ScanService.saveTable(this);
                    reload();
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if ((req != REQ_EXPORT && req != REQ_XLSX) || res != RESULT_OK || data == null
                || data.getData() == null) return;
        Uri uri = data.getData();
        try (OutputStream os = getContentResolver().openOutputStream(uri)) {
            if (req == REQ_XLSX) {
                os.write(XlsxReport.build(ScanService.maxJump, ScanService.maxDrop));
            } else {
                os.write(Meters.csv().getBytes("UTF-8"));
            }
            Toast.makeText(this, "Rapport exporte", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Echec: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }
}
