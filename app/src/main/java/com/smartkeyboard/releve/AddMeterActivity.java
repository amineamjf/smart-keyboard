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
        root.setBackgroundColor(0xFFF4F6FB);

        TextView title = new TextView(this);
        title.setText("Ajouter un compteur");
        title.setTextSize(19);
        title.setTextColor(0xFFFFFFFF);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setBackgroundColor(0xFF1E3A8A);
        title.setPadding(dp(18), dp(34), dp(18), dp(14));
        root.addView(title);
        final LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(16), dp(14), dp(16), dp(14));
        root.addView(form);

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
        form.addView(etSearch);

        info = new TextView(this);
        info.setTextSize(15);
        info.setPadding(0, 12, 0, 12);
        info.setText("Tapez le numéro: s'il est dans le tableau, ses informations s'affichent.");
        form.addView(info);

        next = new Button(this);
        next.setText("Suivant ›");
        next.setVisibility(View.GONE);
        next.setOnClickListener(v -> {
            if (found.isEmpty()) return;
            cur = (cur + 1) % found.size();
            showCurrent();
        });
        form.addView(next);

        etTournee = new EditText(this);
        etTournee.setHint("Tournée");
        etTournee.setInputType(InputType.TYPE_CLASS_TEXT);
        String t = getIntent().getStringExtra("tournee");
        etTournee.setText(t == null ? "" : t);
        form.addView(etTournee);

        etIdx = new EditText(this);
        etIdx.setHint("Nouvel index");
        etIdx.setInputType(InputType.TYPE_CLASS_NUMBER);
        form.addView(etIdx);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, 16, 0, 0);
        Button cancel = new Button(this);
        cancel.setText("Annuler");
        cancel.setOnClickListener(v -> finish());
        Button save = new Button(this);
        save.setText("Enregistrer");
        save.setOnClickListener(v -> save());
        row.addView(cancel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(save, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        form.addView(row);

        styleButtons(cancel, save);
        setContentView(root);
        etSearch.requestFocus();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void styleButtons(Button cancel, Button save) {
        info.setBackground(card());
        info.setPadding(dp(14), dp(12), dp(14), dp(12));
        info.setTextColor(0xFF0F1E4A);
        paint(save, 0xFF1D4ED8, 0xFFFFFFFF);
        paint(cancel, 0xFFE2E8F5, 0xFF0F1E4A);
        paint(next, 0xFFEAF0FF, 0xFF1D4ED8);
    }

    private android.graphics.drawable.GradientDrawable card() {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(0xFFFFFFFF);
        g.setCornerRadius(dp(16));
        g.setStroke(dp(1), 0xFFE6EAF2);
        return g;
    }

    private void paint(Button b, int bg, int fg) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(bg);
        g.setCornerRadius(dp(14));
        b.setBackground(g);
        b.setTextColor(fg);
        b.setAllCaps(false);
        b.setStateListAnimator(null);
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
