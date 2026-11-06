package com.smartkeyboard.releve;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;

/**
 * Un seul ecran pour ajouter un compteur: on tape son numero (ou contrat, ou nom).
 * S'il est dans le tableau, ses informations s'affichent (numero, contrat, client, adresse, tournee,
 * ancien index) et on saisit seulement l'index. Sinon on le cree depuis zero.
 * Dans les deux cas il rejoint la liste "a transmettre" (hors application).
 */
public class AddMeterActivity extends Activity {

    private EditText etSearch;
    private EditText etTournee;
    private EditText etIdx;
    private TextView info;
    private Button next;
    private final ArrayList<Meters.Row> found = new ArrayList<>();
    private int cur = 0;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Runnable doSearch = this::search;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        ScanService.ensureLoaded(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFFF5F7FB);
        root.setPadding(dp(20), dp(28), dp(20), dp(20));

        TextView title = new TextView(this);
        title.setText("Ajouter un compteur");
        title.setTextSize(22);
        title.setTextColor(0xFF121521);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setPadding(0, 0, 0, dp(14));
        root.addView(title);

        etSearch = new EditText(this);
        etSearch.setHint("N° compteur, contrat ou nom du client");
        etSearch.setInputType(InputType.TYPE_CLASS_TEXT);
        etSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int c, int d) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int c, int d) {
            }

            @Override
            public void afterTextChanged(Editable e) {
                h.removeCallbacks(doSearch);
                h.postDelayed(doSearch, 350);
            }
        });
        styleField(etSearch);
        root.addView(etSearch, fieldLp());

        info = new TextView(this);
        info.setTextSize(15);
        info.setTextColor(0xFF121521);
        info.setBackground(shape(0xFFFFFFFF, 20));
        info.setElevation(dp(2));
        info.setPadding(dp(16), dp(14), dp(16), dp(14));
        info.setText("Tapez le numéro: s'il est dans le tableau, ses informations s'affichent.");
        root.addView(info);

        next = new Button(this);
        next.setText("Suivant ›");
        styleButton(next, 0xFFE9EFFF, 0xFF3366FF);
        next.setVisibility(View.GONE);
        next.setOnClickListener(v -> {
            if (found.isEmpty()) return;
            cur = (cur + 1) % found.size();
            showCurrent();
        });
        root.addView(next);

        etTournee = new EditText(this);
        etTournee.setHint("Tournée");
        etTournee.setInputType(InputType.TYPE_CLASS_TEXT);
        String t = getIntent().getStringExtra("tournee");
        etTournee.setText(t == null ? "" : t);
        styleField(etTournee);
        root.addView(etTournee, fieldLp());

        etIdx = new EditText(this);
        etIdx.setHint("Nouvel index");
        etIdx.setInputType(InputType.TYPE_CLASS_NUMBER);
        styleField(etIdx);
        root.addView(etIdx, fieldLp());

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(16), 0, 0);
        Button cancel = new Button(this);
        cancel.setText("Annuler");
        styleButton(cancel, 0xFFE9EFFF, 0xFF3366FF);
        cancel.setOnClickListener(v -> finish());
        Button save = new Button(this);
        save.setText("Enregistrer");
        styleButton(save, 0xFF3366FF, 0xFFFFFFFF);
        save.setOnClickListener(v -> save());
        LinearLayout.LayoutParams lpL = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lpL.rightMargin = dp(8);
        LinearLayout.LayoutParams lpR = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lpR.leftMargin = dp(8);
        row.addView(cancel, lpL);
        row.addView(save, lpR);
        root.addView(row);

        setContentView(root);
        etSearch.requestFocus();
    }

    // ---------- style ----------
    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private android.graphics.drawable.GradientDrawable shape(int color, int radiusDp) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private void styleField(EditText e) {
        android.graphics.drawable.GradientDrawable g = shape(0xFFFFFFFF, 16);
        g.setStroke(dp(1), 0xFFDDE3F0);
        e.setBackground(g);
        e.setTextColor(0xFF121521);
        e.setHintTextColor(0xFF98A2B3);
        e.setPadding(dp(16), dp(14), dp(16), dp(14));
    }

    private LinearLayout.LayoutParams fieldLp() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(8);
        p.bottomMargin = dp(4);
        return p;
    }

    private void styleButton(Button b, int bg, int fg) {
        b.setAllCaps(false);
        b.setTextColor(fg);
        b.setTextSize(15);
        b.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        b.setBackground(shape(bg, 26));
        b.setStateListAnimator(null);
        b.setPadding(dp(14), dp(14), dp(14), dp(14));
    }

    private void search() {
        found.clear();
        cur = 0;
        String q = etSearch.getText().toString().trim();
        if (q.length() >= 3) {
            boolean digits = q.matches("[\\d\\s]+");
            String k = Meters.key(q);
            String qn = Meters.norm(q);
            for (Meters.Row r : Meters.ROWS) {
                if (r.extra) continue;
                boolean m = false;
                if (digits) {
                    if (k.length() >= 4 && (r.key.endsWith(k)
                            || Meters.key(Meters.contractOf(r)).equals(k))) m = true;
                } else if (qn.length() >= 3 && Meters.norm(Meters.nameOf(r)).contains(qn)) {
                    m = true;
                }
                if (m) found.add(r);
                if (found.size() >= 8) break;
            }
        }
        showCurrent();
    }

    private void showCurrent() {
        String q = etSearch.getText().toString().trim();
        if (found.isEmpty()) {
            next.setVisibility(View.GONE);
            info.setText(q.length() < 3
                    ? "Tapez le numéro: s'il est dans le tableau, ses informations s'affichent."
                    : "Absent du tableau: il sera créé depuis zéro. Entrez la tournée et l'index.");
            return;
        }
        Meters.Row r = found.get(cur);
        StringBuilder sb = new StringBuilder();
        sb.append("N° compteur: ").append(r.compteur).append('\n');
        String c = Meters.contractOf(r);
        if (!c.isEmpty()) sb.append("Contrat: ").append(c).append('\n');
        String n = Meters.nameOf(r);
        if (!n.isEmpty()) sb.append("Client: ").append(n).append('\n');
        String a = Meters.addressOf(r);
        if (!a.isEmpty()) sb.append("Adresse: ").append(a).append('\n');
        String tn = Meters.tourneeOf(r);
        if (!tn.isEmpty()) sb.append("Tournée: ").append(tn).append('\n');
        if (!r.oldIdx.isEmpty()) sb.append("Ancien index: ").append(r.oldIdx).append('\n');
        if (!r.newIdx.isEmpty()) sb.append("Index déjà saisi: ").append(r.newIdx).append('\n');
        sb.append(found.size() > 1 ? "(" + (cur + 1) + "/" + found.size() + " correspondances)" : "Trouvé dans le tableau");
        info.setText(sb.toString());
        next.setVisibility(found.size() > 1 ? View.VISIBLE : View.GONE);
        if (!tn.isEmpty()) etTournee.setText(tn);
    }

    private void save() {
        String q = etSearch.getText().toString().trim();
        String idx = Meters.cleanNumber(etIdx.getText().toString());
        if (!found.isEmpty()) {
            Meters.Row r = found.get(cur);
            if (idx.isEmpty()) {
                Toast.makeText(this, "Entrez le nouvel index", Toast.LENGTH_SHORT).show();
                return;
            }
            r.newIdx = idx;
            if (r.status != Meters.UNLISTED) {
                r.status = Meters.NODATA;
                r.note = Meters.OFFAPP + " " + new java.text.SimpleDateFormat("dd/MM/yyyy HH:mm",
                        java.util.Locale.FRANCE).format(new java.util.Date());
            }
            ScanService.saveTable(this);
            Toast.makeText(this, "Enregistré: " + r.compteur + " à transmettre", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        if (q.isEmpty()) {
            Toast.makeText(this, "Entrez le numéro du compteur", Toast.LENGTH_SHORT).show();
            return;
        }
        String tournee = etTournee.getText().toString().trim();
        Meters.Row old = Meters.find(Meters.key(q));
        if (old != null && old.status == Meters.UNLISTED) {
            if (!idx.isEmpty()) old.newIdx = idx;
            if (!tournee.isEmpty()) old.tournee = tournee;
            ScanService.saveTable(this);
            Toast.makeText(this, "Compteur déjà ajouté: mis à jour", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        Meters.addUnlisted(q, idx, tournee);
        ScanService.saveTable(this);
        Toast.makeText(this, "Enregistré: à transmettre", Toast.LENGTH_SHORT).show();
        finish();
    }
}
