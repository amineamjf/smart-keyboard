package com.smartkeyboard.releve;

import android.app.Activity;
import android.os.Bundle;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/** Saisie d'un compteur absent de l'application d'origine: tournee, numero, index. */
public class AddMeterActivity extends Activity {

    private EditText etTournee;
    private EditText etMeter;
    private EditText etIdx;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        ScanService.ensureLoaded(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 24, 32, 24);

        TextView title = new TextView(this);
        title.setText("Compteur non r\u00E9pertori\u00E9");
        title.setTextSize(18);
        title.setPadding(0, 0, 0, 12);
        root.addView(title);

        etTournee = new EditText(this);
        etTournee.setHint("Tourn\u00E9e");
        etTournee.setInputType(InputType.TYPE_CLASS_TEXT);
        String t = getIntent().getStringExtra("tournee");
        etTournee.setText(t == null ? "" : t);
        root.addView(etTournee);

        etMeter = new EditText(this);
        etMeter.setHint("N\u00B0 compteur");
        etMeter.setInputType(InputType.TYPE_CLASS_TEXT);
        root.addView(etMeter);

        etIdx = new EditText(this);
        etIdx.setHint("Index");
        etIdx.setInputType(InputType.TYPE_CLASS_NUMBER);
        root.addView(etIdx);

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
        root.addView(row);

        setContentView(root);
        etMeter.requestFocus();
    }

    private void save() {
        String meter = etMeter.getText().toString().trim();
        if (meter.isEmpty()) {
            Toast.makeText(this, "Entrez le num\u00E9ro du compteur", Toast.LENGTH_SHORT).show();
            return;
        }
        String tournee = etTournee.getText().toString().trim();
        String idx = Meters.cleanNumber(etIdx.getText().toString());
        Meters.Row old = Meters.find(Meters.key(meter));
        if (old != null && old.status == Meters.UNLISTED) {
            // d\u00E9j\u00E0 ajout\u00E9 \u00E0 la main: on met \u00E0 jour l'index et la tourn\u00E9e
            if (!idx.isEmpty()) old.newIdx = idx;
            if (!tournee.isEmpty()) old.tournee = tournee;
            ScanService.saveTable(this);
            Toast.makeText(this, "Compteur d\u00E9j\u00E0 ajout\u00E9: mis \u00E0 jour", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        if (old != null) {
            Toast.makeText(this, "Ce compteur existe d\u00E9j\u00E0 dans le tableau", Toast.LENGTH_LONG).show();
            return;
        }
        Meters.addUnlisted(meter, idx, tournee);
        ScanService.saveTable(this);
        Toast.makeText(this, "Enregistr\u00E9: non r\u00E9pertori\u00E9", Toast.LENGTH_SHORT).show();
        finish();
    }
}
