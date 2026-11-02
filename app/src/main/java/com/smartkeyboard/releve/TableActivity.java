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
    private static final int REQ_UNL = 14;
    private static final int REQ_CLO = 15;
    private static final int REQ_LOG = 16;
    private static final int REQ_LOGF = 17;
    private static final int F_ALL = 0, F_SENT = 1, F_ORANGE = 2, F_ERR = 3, F_NEW = 4, F_UNL = 5, F_OUT = 6;

    private final ArrayList<Meters.Row> shown = new ArrayList<>();
    private int filter = F_ALL;
    private String query = "";
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
            case Meters.UNLISTED: return 0xFFE1CCF5;
            default: return 0xFFEDEFF3;
        }
    }

    private boolean pass(Meters.Row r) {
        if (!query.isEmpty()) {
            String hay = (r.compteur + " " + r.client + " " + r.tournee + " " + r.address + " "
                    + r.contrat + " " + r.note).toLowerCase();
            if (!hay.contains(query)) return false;
        }
        return passFilter(r, filter);
    }

    private boolean passFilter(Meters.Row r, int f) {
        switch (f) {
            case F_SENT: return r.status == Meters.SENT;
            case F_ORANGE: return r.status == Meters.EXTRA || r.status == Meters.NODATA
                    || r.status == Meters.PHOTO || r.status == Meters.SUSPECT
                    || r.status == Meters.UNKNOWN;
            case F_ERR: return r.status == Meters.ERROR;
            case F_NEW: return r.status == Meters.NEW || r.status == Meters.SIM;
            case F_UNL: return Meters.isOffApp(r);
            case F_OUT:
                return r.oldIdx.matches("\\d{1,9}") && r.newIdx.matches("\\d{1,9}")
                        && ScanService.outOfRange(Long.parseLong(r.oldIdx), Long.parseLong(r.newIdx));
            default: return true;
        }
    }

    private void reload() {
        shown.clear();
        for (Meters.Row r : Meters.ROWS) if (pass(r)) shown.add(r);
        head.setText(shown.size() + " / " + Meters.ROWS.size() + " lignes");
        adapter.notifyDataSetChanged();
        updateChips();
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

    /** Choix des tournees de la journee: aucune cochee = toutes. */
    private void pickTournees() {
        final ArrayList<String> all = Meters.distinctTournees();
        if (all.isEmpty()) {
            Toast.makeText(this, "Aucune tournee dans le tableau (colonne Tournee)", Toast.LENGTH_LONG).show();
            return;
        }
        final String[] names = all.toArray(new String[0]);
        final boolean[] on = new boolean[names.length];
        for (int i = 0; i < names.length; i++) on[i] = Meters.ACTIVE_T.contains(names[i]);
        new AlertDialog.Builder(this)
                .setTitle("Tournees de la journee (aucune cochee = toutes)")
                .setMultiChoiceItems(names, on, (d, which, checked) -> on[which] = checked)
                .setPositiveButton("OK", (d, w) -> {
                    Meters.ACTIVE_T.clear();
                    for (int i = 0; i < names.length; i++) if (on[i]) Meters.ACTIVE_T.add(names[i]);
                    ScanService.saveTournees(this);
                    Toast.makeText(this, Meters.ACTIVE_T.isEmpty() ? "Toutes les tournees"
                            : Meters.ACTIVE_T.size() + " tournee(s) choisie(s)", Toast.LENGTH_SHORT).show();
                })
                .setNeutralButton("TOUTES", (d, w) -> {
                    Meters.ACTIVE_T.clear();
                    ScanService.saveTournees(this);
                })
                .setNegativeButton("Annuler", null)
                .show();
    }


    // ---------- interface ----------
    private TextView[] chipViews;
    private String[] chipNames;
    private int[] chipFilters;

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private android.graphics.drawable.GradientDrawable shape(int color, int radiusDp) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private TextView label(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        return t;
    }

    private TextView headBtn(String s, Runnable r) {
        TextView t = label(s, 13, 0xFFFFFFFF, true);
        t.setPadding(dp(12), dp(8), dp(12), dp(8));
        t.setBackground(shape(0x33FFFFFF, 18));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2);
        p.leftMargin = dp(8);
        t.setLayoutParams(p);
        t.setOnClickListener(v -> r.run());
        return t;
    }

    private int countFor(int f) {
        int n = 0;
        for (Meters.Row r : Meters.ROWS) if (passFilter(r, f)) n++;
        return n;
    }

    private void updateChips() {
        if (chipViews == null) return;
        for (int k = 0; k < chipViews.length; k++) {
            boolean on = chipFilters[k] == filter;
            String txt = chipNames[k] + (chipFilters[k] == F_ALL ? "" : " " + countFor(chipFilters[k]));
            chipViews[k].setText(txt);
            chipViews[k].setTextColor(on ? 0xFFFFFFFF : 0xFF0B0C0E);
            chipViews[k].setBackground(shape(on ? 0xFF1F5FD0 : 0xFFFFFFFF, 20));
        }
    }

    private static int stripe(int status) {
        switch (status) {
            case Meters.SENT: return 0xFF17803F;
            case Meters.SIM: return 0xFF1F5FD0;
            case Meters.EXTRA:
            case Meters.NODATA:
            case Meters.UNKNOWN:
            case Meters.PHOTO:
            case Meters.SUSPECT: return 0xFFC77000;
            case Meters.ERROR: return 0xFFC62828;
            case Meters.UNLISTED: return 0xFF7B4DC2;
            default: return 0xFF9AA1AD;
        }
    }

    private View rowView(Meters.Row r) {
        LinearLayout outer = new LinearLayout(this);
        outer.setPadding(dp(14), dp(5), dp(14), dp(5));
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setBackground(shape(0xFFFFFFFF, 14));
        card.setElevation(dp(1));
        View bar = new View(this);
        bar.setBackgroundColor(r.warn.isEmpty() ? stripe(r.status) : 0xFFC77000);
        card.addView(bar, new LinearLayout.LayoutParams(dp(6), ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(12), dp(10), dp(8), dp(10));
        body.addView(label((r.photos.isEmpty() ? "" : "\uD83D\uDCF7 ") + r.compteur
                + (r.client.isEmpty() ? "" : "  \u00B7  " + r.client), 15, 0xFF0B0C0E, true));
        body.addView(label("Ancien " + (r.oldIdx.isEmpty() ? "?" : r.oldIdx)
                + (r.tournee.isEmpty() ? "" : "  \u00B7  " + r.tournee), 12, 0xFF5B6573, false));

        String tagText;
        int tagBg, tagFg;
        boolean on = r.oldIdx.matches("\\d{1,9}"), nn = r.newIdx.matches("\\d{1,9}");
        if (Meters.isOffApp(r)) {
            tagText = "Hors application \u00B7 \u00E0 transmettre";
            tagBg = 0xFFE9DDF8;
            tagFg = 0xFF5B2FA0;
        } else if (on && nn) {
            long o = Long.parseLong(r.oldIdx), n = Long.parseLong(r.newIdx);
            if (n < o) {
                tagText = "Nouveau < ancien (\u2212" + (o - n) + ")";
                tagBg = 0xFFF8DCDC;
                tagFg = 0xFF8E1B1B;
            } else if (ScanService.outOfRange(o, n)) {
                tagText = "Consommation " + (n - o) + " \u00B7 hors limites";
                tagBg = 0xFFFDECCF;
                tagFg = 0xFF9A5600;
            } else {
                tagText = "Consommation " + (n - o) + " \u00B7 normal";
                tagBg = 0xFFE3F3E8;
                tagFg = 0xFF17803F;
            }
        } else {
            tagText = Meters.statusLabel(r.status);
            tagBg = 0xFFE9ECF0;
            tagFg = 0xFF4A5260;
        }
        TextView tag = label(tagText, 11, tagFg, true);
        tag.setPadding(dp(8), dp(2), dp(8), dp(2));
        tag.setBackground(shape(tagBg, 8));
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-2, -2);
        tp.topMargin = dp(6);
        body.addView(tag, tp);
        if (!r.warn.isEmpty()) body.addView(label("\u26A0 " + r.warn, 12, 0xFF9A5600, false));
        if (!Meters.noAnomaly(r.anomaly)) body.addView(label("Anomalie: " + r.anomaly, 12, 0xFF5B6573, false));
        if (!Meters.noAnnotation(r.annotation)) {
            body.addView(label("Annotation: " + r.annotation, 12, 0xFF5B6573, false));
        }
        if (!r.note.isEmpty()) {
            String nt = r.note.length() > 90 ? r.note.substring(0, 90) + "\u2026" : r.note;
            body.addView(label(nt, 11, 0xFF8A93A0, false));
        }
        card.addView(body, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setGravity(android.view.Gravity.CENTER_VERTICAL | android.view.Gravity.END);
        right.setPadding(0, dp(8), dp(14), dp(8));
        right.addView(label(r.newIdx.isEmpty() ? "\u2014" : r.newIdx, 18,
                r.newIdx.isEmpty() ? 0xFF9AA1AD : 0xFF0B7A2E, true));
        right.addView(label(Meters.statusLabel(r.status), 10, 0xFF5B6573, false));
        card.addView(right, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        outer.addView(card, new LinearLayout.LayoutParams(-1, -2));
        return outer;
    }

    // ---------- actions (export / import) ----------
    private String today() {
        return new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.FRANCE).format(new java.util.Date());
    }

    private void createXlsx(String name, int req) {
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        i.putExtra(Intent.EXTRA_TITLE, name + "-" + today() + ".xlsx");
        startActivityForResult(i, req);
    }

    private void exportXlsx() {
        createXlsx("rapport-releve", REQ_XLSX);
    }

    private void exportUnl() {
        if (Meters.countOffApp() == 0) {
            Toast.makeText(this, "Aucun compteur non r\u00E9pertori\u00E9", Toast.LENGTH_SHORT).show();
            return;
        }
        createXlsx("non-repertories", REQ_UNL);
    }

    private void exportClosed() {
        Meters.loadClosed(new java.io.File(getFilesDir(), "closed.tsv"));
        if (Meters.CLOSED.isEmpty()) {
            Toast.makeText(this, "Aucune lecture: importez d'abord le journal (Outils > Dossier logs)",
                    Toast.LENGTH_LONG).show();
            return;
        }
        createXlsx("lectures-application", REQ_CLO);
    }

    private void exportCsv() {
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("text/csv");
        i.putExtra(Intent.EXTRA_TITLE, "rapport-releve.csv");
        startActivityForResult(i, REQ_EXPORT);
    }

    private void pickLogDir() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(i, REQ_LOG);
    }

    private void pickLogFiles() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(i, REQ_LOGF);
    }

    private void importTable() {
        Intent i = new Intent(this, MainActivity.class);
        i.putExtra("pickTable", true);
        startActivity(i);
    }

    private TextView sectionTitle(String s) {
        TextView t = label(s.toUpperCase(), 12, 0xFF5B6573, true);
        t.setLetterSpacing(0.08f);
        t.setPadding(dp(4), dp(14), 0, dp(4));
        return t;
    }

    private View toolRow(final android.app.Dialog d, String icon, String title, String sub, final Runnable r) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(dp(4), dp(8), dp(4), dp(8));
        TextView ic = label(icon, 18, 0xFF0B0C0E, false);
        ic.setGravity(android.view.Gravity.CENTER);
        ic.setBackground(shape(0xFFE8EFFC, 12));
        row.addView(ic, new LinearLayout.LayoutParams(dp(40), dp(40)));
        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.VERTICAL);
        mid.setPadding(dp(12), 0, 0, 0);
        mid.addView(label(title, 15, 0xFF0B0C0E, true));
        mid.addView(label(sub, 12, 0xFF5B6573, false));
        row.addView(mid, new LinearLayout.LayoutParams(0, -2, 1f));
        android.util.TypedValue ta = new android.util.TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ta, true);
        row.setBackgroundResource(ta.resourceId);
        row.setOnClickListener(v -> {
            d.dismiss();
            r.run();
        });
        return row;
    }

    /** Feuille d'outils: importer le tableau, importer les lectures, exporter. */
    private void showTools() {
        final android.app.Dialog d = new android.app.Dialog(this);
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(10), dp(16), dp(20));
        box.setBackground(shape(0xFFFFFFFF, 22));
        box.addView(sectionTitle("Tableau"));
        box.addView(toolRow(d, "\uD83D\uDCCB", "Importer le tableau (Excel / CSV)",
                "Remplace le tableau de la tourn\u00E9e", this::importTable));
        box.addView(sectionTitle("Lectures de l'application"));
        box.addView(toolRow(d, "\uD83D\uDCC2", "Dossier du journal (une seule fois)",
                "Lecture automatique ensuite", this::pickLogDir));
        box.addView(toolRow(d, "\uD83D\uDDD2", "Choisir des fichiers du journal (.txt)",
                "Un ou plusieurs fichiers", this::pickLogFiles));
        box.addView(sectionTitle("Exporter"));
        box.addView(toolRow(d, "\uD83D\uDCCA", "Rapport Excel (.xlsx)",
                "Tous les compteurs avec statuts", this::exportXlsx));
        box.addView(toolRow(d, "\uD83D\uDCE4", "Lectures de l'application (.xlsx)",
                "Ce qui a \u00E9t\u00E9 lu dans l'application", this::exportClosed));
        box.addView(toolRow(d, "\u2709", "Non r\u00E9pertori\u00E9s (.xlsx)",
                Meters.countOffApp() + " compteur(s) \u00E0 transmettre", this::exportUnl));
        box.addView(toolRow(d, "\uD83D\uDCC4", "Rapport CSV", "Format texte simple", this::exportCsv));
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(box);
        d.setContentView(sv);
        android.view.Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setGravity(android.view.Gravity.BOTTOM);
        }
        d.show();
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        ScanService.ensureLoaded(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFFF3F5F8);

        // en-tete
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(android.view.Gravity.CENTER_VERTICAL);
        top.setBackgroundColor(0xFF1F5FD0);
        top.setPadding(dp(20), dp(30), dp(14), dp(16));
        LinearLayout tt = new LinearLayout(this);
        tt.setOrientation(LinearLayout.VERTICAL);
        tt.addView(label("Tableau", 22, 0xFFFFFFFF, true));
        head = label("", 13, 0xCCFFFFFF, false);
        tt.addView(head);
        top.addView(tt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        top.addView(headBtn("Tourn\u00E9es", this::pickTournees));
        top.addView(headBtn("Outils \u25BE", this::showTools));
        root.addView(top);

        // filtres
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setPadding(dp(14), dp(12), dp(6), 0);
        chipNames = new String[]{"Tous", "Envoy\u00E9s", "\u00C0 v\u00E9rifier", "Erreurs", "Non vus",
                "Hors application", "Hors limites"};
        chipFilters = new int[]{F_ALL, F_SENT, F_ORANGE, F_ERR, F_NEW, F_UNL, F_OUT};
        chipViews = new TextView[chipNames.length];
        for (int k = 0; k < chipNames.length; k++) {
            final int fk = chipFilters[k];
            TextView c = label(chipNames[k], 13, 0xFF0B0C0E, false);
            c.setPadding(dp(14), dp(8), dp(14), dp(8));
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-2, -2);
            cp.rightMargin = dp(8);
            c.setLayoutParams(cp);
            c.setOnClickListener(v -> {
                filter = fk;
                reload();
            });
            chipViews[k] = c;
            bar.addView(c);
        }
        hs.addView(bar);
        root.addView(hs);

        // recherche
        LinearLayout sbox = new LinearLayout(this);
        sbox.setOrientation(LinearLayout.HORIZONTAL);
        sbox.setGravity(android.view.Gravity.CENTER_VERTICAL);
        sbox.setBackground(shape(0xFFFFFFFF, 14));
        sbox.setElevation(dp(1));
        sbox.setPadding(dp(14), 0, dp(10), 0);
        sbox.addView(label("\uD83D\uDD0D", 16, 0xFF5B6573, false));
        final EditText search = new EditText(this);
        search.setHint("Rechercher: compteur, client, contrat, tourn\u00E9e...");
        search.setSingleLine(true);
        search.setTextSize(14);
        search.setBackground(null);
        search.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            public void afterTextChanged(android.text.Editable e) {
                query = e.toString().trim().toLowerCase();
                reload();
            }
        });
        sbox.addView(search, new LinearLayout.LayoutParams(0, dp(46), 1f));
        LinearLayout.LayoutParams sp0 = new LinearLayout.LayoutParams(-1, -2);
        sp0.setMargins(dp(14), dp(12), dp(14), dp(4));
        root.addView(sbox, sp0);

        ListView lv = new ListView(this);
        lv.setDivider(null);
        lv.setDividerHeight(0);
        lv.setClipToPadding(false);
        lv.setPadding(0, dp(4), 0, dp(16));
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
                return rowView(shown.get(i));
            }
        };
        lv.setAdapter(adapter);
        lv.setOnItemClickListener((parent, view, pos, id) -> edit(shown.get(pos)));
        root.addView(lv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        if (getIntent() != null && getIntent().getBooleanExtra("pickTournee", false)) {
            root.post(this::pickTournees);
        }
        reload();
    }

    /** Corriger le nouvel index d'une ligne, puis la remettre "non vu" pour une nouvelle passe. */
    private void edit(final Meters.Row r) {
        final EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_NUMBER);
        et.setText(r.newIdx);
        if (r.status == Meters.UNLISTED) {
            new AlertDialog.Builder(this)
                    .setTitle(r.compteur + (r.tournee.isEmpty() ? "" : "  (" + r.tournee + ")"))
                    .setMessage("Compteur non r\u00E9pertori\u00E9: index")
                    .setView(et)
                    .setPositiveButton("Enregistrer", (d, w) -> {
                        r.newIdx = Meters.cleanNumber(et.getText().toString());
                        ScanService.saveTable(this);
                        reload();
                    })
                    .setNeutralButton("Supprimer", (d, w) -> {
                        Meters.remove(r);
                        ScanService.saveTable(this);
                        reload();
                    })
                    .setNegativeButton("Annuler", null)
                    .show();
            return;
        }
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
                .setNeutralButton("Hors application : \u00E0 transmettre", (d, w) -> {
                    String v = Meters.cleanNumber(et.getText().toString());
                    if (v.isEmpty()) {
                        Toast.makeText(this, "Saisissez d'abord le nouvel index", Toast.LENGTH_LONG).show();
                        return;
                    }
                    r.newIdx = v;
                    r.status = Meters.NODATA;
                    r.note = Meters.OFFAPP + " " + new java.text.SimpleDateFormat("dd/MM/yyyy HH:mm",
                            java.util.Locale.FRANCE).format(new java.util.Date());
                    ScanService.saveTable(this);
                    reload();
                    Toast.makeText(this, "Ajout\u00E9 \u00E0 la liste \u00E0 transmettre", Toast.LENGTH_LONG).show();
                })
                .setNegativeButton("Marquer envoy\u00E9", (d, w) -> {
                    r.status = Meters.SENT;
                    r.note = "marque a la main";
                    ScanService.saveTable(this);
                    reload();
                })
                .show();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_LOG || req == REQ_LOGF) {
            if (res != RESULT_OK || data == null) return;
            final java.util.ArrayList<Uri> files = new java.util.ArrayList<>();
            if (req == REQ_LOGF) {
                if (data.getClipData() != null) {
                    for (int q = 0; q < data.getClipData().getItemCount(); q++) {
                        files.add(data.getClipData().getItemAt(q).getUri());
                    }
                } else if (data.getData() != null) {
                    files.add(data.getData());
                }
                if (files.isEmpty()) return;
            } else if (data.getData() == null) {
                return;
            }
            final Uri tree = data.getData();
            if (req == REQ_LOG && tree != null) LogAuto.saveTree(this, tree);
            Toast.makeText(this, "Lecture du journal...", Toast.LENGTH_SHORT).show();
            new Thread(() -> {
                final LogImport.Result rr = req == REQ_LOGF
                        ? LogImport.runFiles(getContentResolver(), files, new java.io.File(getFilesDir(), "closed.tsv"))
                        : LogImport.run(getContentResolver(), tree, new java.io.File(getFilesDir(), "closed.tsv"));
                if (!rr.blocked) ScanService.saveTable(TableActivity.this);
                runOnUiThread(() -> {
                    String m = rr.msg.isEmpty() ? "" : rr.msg + "\n\n";
                    m += "Lignes de cloture lues: " + rr.lines + "\nClients distincts: " + rr.clients
                            + "\nRattaches au tableau: " + rr.matched + "\nNon rattaches: " + rr.unmatched
                            + "\nIndex identiques: " + rr.same + "\nIndex differents (non ecrases): " + rr.diff
                            + "\nIndex remplis: " + rr.filled
                            + "\nLectures annulees dans l'application: " + rr.cancels
                            + "\nSans index (anomalie / pas de lecture): " + rr.noIdx
                            + "\nIndex 0 non ecrits: " + rr.zero;
                    new AlertDialog.Builder(TableActivity.this).setTitle("Journal de l'application")
                            .setMessage(m).setPositiveButton("OK", null).show();
                    reload();
                });
            }).start();
            return;
        }
        if ((req != REQ_EXPORT && req != REQ_XLSX && req != REQ_UNL && req != REQ_CLO) || res != RESULT_OK || data == null
                || data.getData() == null) return;
        Uri uri = data.getData();
        try (OutputStream os = getContentResolver().openOutputStream(uri)) {
            if (req == REQ_CLO) {
                os.write(XlsxReport.buildClosed());
            } else if (req == REQ_UNL) {
                os.write(XlsxReport.buildUnlisted());
            } else if (req == REQ_XLSX) {
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
