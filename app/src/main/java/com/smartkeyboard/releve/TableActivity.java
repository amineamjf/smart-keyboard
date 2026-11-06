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
    private static final int F_ALL = 0, F_SENT = 1, F_ORANGE = 2, F_ERR = 3, F_NEW = 4, F_UNL = 5, F_OUT = 6, F_PHOTO = 7, F_HIGH = 8, F_NEG = 9;

    private final ArrayList<Meters.Row> shown = new ArrayList<>();
    private java.util.HashMap<String, Long> medians = new java.util.HashMap<>();
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

    /** Consommation (nouveau - ancien) si les deux index sont des nombres, sinon Long.MIN_VALUE. */
    private static long consOf(Meters.Row r) {
        if (r.oldIdx.matches("\\d{1,9}") && r.newIdx.matches("\\d{1,9}")) {
            return Long.parseLong(r.newIdx) - Long.parseLong(r.oldIdx);
        }
        return Long.MIN_VALUE;
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
            case F_PHOTO: return !r.photos.isEmpty();
            case F_HIGH: {
                long cs = consOf(r);
                return cs != Long.MIN_VALUE && cs >= 0 && cs > ScanService.maxJump;
            }
            case F_NEG: {
                long cs = consOf(r);
                return cs != Long.MIN_VALUE && cs < 0;
            }
            case F_OUT:
                return r.oldIdx.matches("\\d{1,9}") && r.newIdx.matches("\\d{1,9}")
                        && ScanService.outOfRange(Long.parseLong(r.oldIdx), Long.parseLong(r.newIdx));
            default: return true;
        }
    }

    private void reload() {
        shown.clear();
        medians = Verdict.medians(Meters.ROWS);
        for (Meters.Row r : Meters.ROWS) if (pass(r)) shown.add(r);
        head.setText(shown.size() + " / " + Meters.ROWS.size() + " lignes");
        adapter.notifyDataSetChanged();
        updateChips();
    }

    private Button fbtn(String label, final int f) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setBackground(shape(0xFFE9EFFF, 20));
        b.setTextColor(0xFF3366FF);
        b.setStateListAnimator(null);
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
            String nmk = chipFilters[k] == F_HIGH ? chipNames[k] + " >" + ScanService.maxJump : chipNames[k];
            String txt = nmk + (chipFilters[k] == F_ALL ? "" : " " + countFor(chipFilters[k]));
            chipViews[k].setText(txt);
            chipViews[k].setTextColor(on ? 0xFFFFFFFF : 0xFF0B0C0E);
            chipViews[k].setBackground(shape(on ? 0xFF3366FF : 0xFFFFFFFF, 20));
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

    /** Ouvre l'application d'origine sur l'onglet Cloturee(s), a la carte du compteur: on touche la carte pour voir la photo. */
    private void openPhoto(Meters.Row r) {
        if (ScanService.instance == null) {
            Toast.makeText(this, "Activez d'abord le service d'accessibilit\u00E9", Toast.LENGTH_LONG).show();
            return;
        }
        try {
            Intent li = getPackageManager().getLaunchIntentForPackage("com.somei.smartmobility.srmrsk");
            if (li == null) {
                li = new Intent(Intent.ACTION_MAIN);
                li.addCategory(Intent.CATEGORY_LAUNCHER);
                li.setPackage("com.somei.smartmobility.srmrsk");
            }
            li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            startActivity(li);
        } catch (Throwable t) {
            Toast.makeText(this, "Impossible d'ouvrir l'application d'origine (" + t.getClass().getSimpleName() + ")",
                    Toast.LENGTH_LONG).show();
            return;
        }
        final String key = r.key;
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            if (ScanService.instance != null) ScanService.instance.gotoClosedCard(key);
        }, 900);
    }

    /** Seuil de consommation elevee (le meme que la hausse maximale), a regler par appui long sur le filtre. */
    private void askHigh() {
        final EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_NUMBER);
        et.setText(String.valueOf(ScanService.maxJump));
        new AlertDialog.Builder(this)
                .setTitle("Consommation \u00E9lev\u00E9e : au-del\u00E0 de (m\u00B3)")
                .setView(et)
                .setPositiveButton("OK", (d, w) -> {
                    try {
                        long v = Long.parseLong(et.getText().toString().trim());
                        if (v >= 0) {
                            ScanService.maxJump = v;
                            ScanService.saveCfg(this);
                            reload();
                        }
                    } catch (NumberFormatException ignored) {
                    }
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    /** Corriger l'ancien index d'un compteur apres verification avec l'application d'origine (journal Cloturee). */
    private void editOld(final Meters.Row r) {
        Meters.Closed c = Meters.CLOSED.get(r.key);
        StringBuilder info = new StringBuilder();
        info.append("Nouvel index (tableau) : ").append(r.newIdx.isEmpty() ? "-" : r.newIdx).append("\n");
        info.append("Ancien index actuel : ").append(r.oldIdx.isEmpty() ? "-" : r.oldIdx).append("\n");
        String prefill = r.oldIdx;
        boolean same = c != null && !c.idx.isEmpty() && c.idx.equals(r.newIdx);
        if (c == null || c.idx.isEmpty()) {
            info.append("\nPas de donn\u00E9e de l'application pour ce compteur (clavier > Outils > Analyser conso).\n");
        } else if (same) {
            info.append("\n\u2713 Le nouvel index est identique \u00E0 celui de l'application (").append(c.idx).append(").\n");
        } else {
            info.append("\n\u26A0 L'application a un autre nouvel index : ").append(c.idx)
                    .append(". Aucune proposition automatique.\n");
        }
        if (same && c.note.startsWith("consommation lue") && c.conso.matches("\\d{1,9}") && r.newIdx.matches("\\d{1,9}")) {
            long implied = Long.parseLong(r.newIdx) - Long.parseLong(c.conso);
            info.append("Consommation de l'application : ").append(c.conso).append("\n");
            if (implied >= 0) {
                info.append("Ancien index probable : ").append(implied).append("\n");
                prefill = String.valueOf(implied);
            }
        }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(10), dp(20), 0);
        TextView msg = label(info.toString(), 14, 0xFF1F2A24, false);
        box.addView(msg);
        final EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_NUMBER);
        et.setText(prefill);
        et.setSelection(et.getText().length());
        box.addView(et);
        new AlertDialog.Builder(this)
                .setTitle("Ancien index de " + noZeros(r.compteur))
                .setView(box)
                .setPositiveButton("Corriger", (d, w) -> {
                    String v = et.getText().toString().trim();
                    if (!v.matches("\\d{1,9}")) {
                        Toast.makeText(this, "Nombre invalide", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    String was = r.oldIdx;
                    r.oldIdx = v;
                    String nt = "ancien index corrige (etait " + was + ")";
                    r.note = r.note.isEmpty() ? nt : r.note + " | " + nt;
                    ScanService.saveTable(this);
                    reload();
                    Toast.makeText(this, "Ancien index corrig\u00E9 : " + v, Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    private int cardBgC = 0xFFEAF8F0, iconBgC = 0xFFCDEBDB, iconFgC = 0xFF1B6B43;

    private static String noZeros(String t) {
        if (t == null) return "";
        t = t.trim();
        int i = 0;
        while (i < t.length() - 1 && t.charAt(i) == '0') i++;
        return t.substring(i);
    }

    /** Une ligne de la carte: petite icone carree verte + texte (comme les cartes de l'application d'origine). */
    private View infoLine(String icon, String text, int textColor, boolean bold, float sp) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView ic = label(icon, 12, iconFgC, false);
        ic.setGravity(android.view.Gravity.CENTER);
        ic.setBackground(shape(iconBgC, 5));
        l.addView(ic, new LinearLayout.LayoutParams(dp(24), dp(24)));
        TextView t = label(text, (int) sp, textColor, bold);
        t.setPadding(dp(10), 0, 0, 0);
        l.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(4);
        l.setLayoutParams(lp);
        return l;
    }

    private View rowView(Meters.Row r) {
        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.HORIZONTAL);
        outer.setPadding(dp(8), dp(8), dp(10), dp(8));

        // bande bleue a gauche (comme l'application d'origine)
        final long csv = consOf(r);
        final boolean isNeg = csv != Long.MIN_VALUE && csv < 0;
        final boolean isHigh = csv != Long.MIN_VALUE && csv >= 0 && csv > ScanService.maxJump;
        if (isNeg) {
            cardBgC = 0xFFFBE7E7;
            iconBgC = 0xFFF4C9C9;
            iconFgC = 0xFF8E1B1B;
        } else if (isHigh) {
            cardBgC = 0xFFFDF1DE;
            iconBgC = 0xFFF8DDB0;
            iconFgC = 0xFF8A5200;
        } else {
            cardBgC = 0xFFEAF8F0;
            iconBgC = 0xFFCDEBDB;
            iconFgC = 0xFF1B6B43;
        }
        View bar = new View(this);
        bar.setBackgroundColor(isNeg ? 0xFFD96A6A : isHigh ? 0xFFE8A33D : 0xFF5B8DEF);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(dp(5), ViewGroup.LayoutParams.MATCH_PARENT);
        bl.rightMargin = dp(8);
        outer.addView(bar, bl);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(shape(cardBgC, 18));
        card.setPadding(dp(12), dp(10), dp(12), dp(12));

        boolean on = r.oldIdx.matches("\\d{1,9}"), nn = r.newIdx.matches("\\d{1,9}");
        int vd = Verdict.judge(r, medians, ScanService.maxJump, ScanService.maxDrop);
        long cons = on && nn ? Long.parseLong(r.newIdx) - Long.parseLong(r.oldIdx) : 0;
        Long md = medians.get(Meters.tourneeOf(r));

        // 1) numero du compteur (sans zeros de tete)  2) contrat  3) nom  4) adresse
        card.addView(infoLine("🔢", noZeros(r.compteur), 0xFF0B0C0E, true, 17));
        String ct = Meters.contractOf(r);
        if (!ct.isEmpty()) card.addView(infoLine("📄", ct, 0xFF1F2A24, false, 15));
        String nm = Meters.nameOf(r);
        if (!nm.isEmpty()) card.addView(infoLine("👤", nm, 0xFF0B0C0E, true, 15));
        String ad = Meters.addressOf(r);
        if (!ad.isEmpty()) card.addView(infoLine("📍", ad, 0xFF2B3A32, false, 13));

        // 5) anciens / nouveaux index, consommation
        card.addView(infoLine("◀", "Ancien index : " + (r.oldIdx.isEmpty() ? "?" : r.oldIdx),
                0xFF1F2A24, false, 15));
        card.addView(infoLine("▶", "Nouvel index : " + (r.newIdx.isEmpty() ? "—" : r.newIdx),
                r.newIdx.isEmpty() ? 0xFF7B8C83 : 0xFF0B7A2E, true, 15));
        if (on && nn) {
            int cc = (vd == Verdict.NEG || vd == Verdict.OUT) ? 0xFF8E1B1B
                    : (vd == Verdict.HIGH || vd == Verdict.ZERO) ? 0xFF9A5600 : 0xFF17803F;
            card.addView(infoLine("💧", "Consommation : " + cons, cc, true, 15));
        }
        if (!r.tournee.isEmpty() || !r.photos.isEmpty()) {
            card.addView(infoLine("🗺", (r.tournee.isEmpty() ? "" : "Tournée " + r.tournee)
                    + (r.photos.isEmpty() ? "" : "   📷 photo"), 0xFF3B5546, false, 12));
        }

        {
            final Meters.Row rr = r;
            LinearLayout acts = new LinearLayout(this);
            acts.setOrientation(LinearLayout.HORIZONTAL);
            if (!r.photos.isEmpty()) {
                TextView pb = label("\uD83D\uDCF7  Voir dans Cl\u00F4tur\u00E9es", 13, 0xFF0F4D2E, true);
                pb.setGravity(android.view.Gravity.CENTER);
                pb.setPadding(dp(12), dp(7), dp(12), dp(7));
                pb.setBackground(shape(0xFFCDEBDB, 12));
                pb.setClickable(true);
                pb.setOnClickListener(v -> openPhoto(rr));
                acts.addView(pb, new LinearLayout.LayoutParams(-2, -2));
            }
            if (isNeg || isHigh) {
                TextView eb = label("\u270E  Corriger l'ancien index", 13, isNeg ? 0xFF8E1B1B : 0xFF8A5200, true);
                eb.setGravity(android.view.Gravity.CENTER);
                eb.setPadding(dp(12), dp(7), dp(12), dp(7));
                eb.setBackground(shape(isNeg ? 0xFFF4C9C9 : 0xFFF8DDB0, 12));
                eb.setClickable(true);
                eb.setOnClickListener(v -> editOld(rr));
                LinearLayout.LayoutParams el = new LinearLayout.LayoutParams(-2, -2);
                if (acts.getChildCount() > 0) el.leftMargin = dp(8);
                acts.addView(eb, el);
            }
            if (acts.getChildCount() > 0) {
                LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(-2, -2);
                ap.topMargin = dp(8);
                card.addView(acts, ap);
            }
        }

        if (r.note.contains("corrige automatiquement")) {
            TextView ok = label("\u2713 Ancien index corrig\u00E9 \u00B7 conforme \u00E0 l'application", 12, 0xFF0F4D2E, true);
            ok.setPadding(dp(10), dp(3), dp(10), dp(3));
            ok.setBackground(shape(0xFFCDEBDB, 10));
            LinearLayout.LayoutParams op = new LinearLayout.LayoutParams(-2, -2);
            op.topMargin = dp(6);
            card.addView(ok, op);
        }

        // etiquette de verdict (jaune / rouge / vert...)
        String tagText;
        int tagBg, tagFg;
        if (r.note.contains("ANNULEE dans l'application")) {
            tagText = "Annulée dans l'application · à vérifier";
            tagBg = 0xFFE9ECF0;
            tagFg = 0xFF4A5260;
        } else if (Meters.isOffApp(r)) {
            tagText = "Hors application · à transmettre";
            tagBg = 0xFFE9DDF8;
            tagFg = 0xFF5B2FA0;
        } else if (vd == Verdict.NEG) {
            tagText = "Nouveau < ancien (−" + (Long.parseLong(r.oldIdx) - Long.parseLong(r.newIdx))
                    + ") · ancien index douteux";
            tagBg = 0xFFF8DCDC;
            tagFg = 0xFF8E1B1B;
        } else if (vd == Verdict.OUT) {
            tagText = "Consommation " + cons + " · hors limites";
            tagBg = 0xFFF8DCDC;
            tagFg = 0xFF8E1B1B;
        } else if (vd == Verdict.HIGH) {
            tagText = "Consommation " + cons + " · très élevée (médiane " + md + ")";
            tagBg = 0xFFFDECCF;
            tagFg = 0xFF9A5600;
        } else if (vd == Verdict.ZERO) {
            tagText = "Consommation 0 · inhabituelle (médiane " + md + ")";
            tagBg = 0xFFFDECCF;
            tagFg = 0xFF9A5600;
        } else if (vd == Verdict.OK) {
            tagText = "Consommation " + cons + " · normal";
            tagBg = 0xFFD3EFDF;
            tagFg = 0xFF17803F;
        } else {
            tagText = Meters.statusLabel(r.status);
            tagBg = 0xFFE3E9E6;
            tagFg = 0xFF4A5260;
        }
        TextView tag = label(tagText, 12, tagFg, true);
        tag.setPadding(dp(10), dp(3), dp(10), dp(3));
        tag.setBackground(shape(tagBg, 10));
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-2, -2);
        tp.topMargin = dp(8);
        card.addView(tag, tp);
        if (!r.warn.isEmpty()) {
            TextView w = label("⚠ " + r.warn, 12, 0xFF7A4300, true);
            w.setPadding(dp(10), dp(4), dp(10), dp(4));
            w.setBackground(shape(0xFFFBE3C4, 10));
            LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(-2, -2);
            wp.topMargin = dp(6);
            card.addView(w, wp);
        }
        if (!Meters.noAnomaly(r.anomaly)) card.addView(infoLine("⚠", "Anomalie : " + r.anomaly, 0xFF3B4650, false, 13));
        if (!Meters.noAnnotation(r.annotation)) {
            card.addView(infoLine("✎", "Annotation : " + r.annotation, 0xFF3B4650, false, 13));
        }
        if (!r.note.isEmpty()) {
            String n2 = r.note.length() > 90 ? r.note.substring(0, 90) + "…" : r.note;
            TextView nt = label(n2, 11, 0xFF6B7A72, false);
            nt.setPadding(0, dp(6), 0, 0);
            card.addView(nt);
        }
        outer.addView(card, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // pastille d'etat en haut a droite (comme le compteur vert de l'application d'origine)
        int sc = r.warn.isEmpty() ? stripe(r.status) : 0xFFC77000;
        String glyph = r.status == Meters.SENT ? "✓" : r.status == Meters.ERROR ? "✕"
                : (sc == 0xFFC77000 ? "!" : "●");
        if (isNeg) {
            sc = 0xFFD96A6A;
            glyph = "\u25BC";
        } else if (isHigh) {
            sc = 0xFFE8A33D;
            glyph = "\u25B2";
        }
        TextView badge = label(glyph, 14, 0xFFFFFFFF, true);
        badge.setGravity(android.view.Gravity.CENTER);
        badge.setBackground(shape(sc, 14));
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(dp(28), dp(28));
        gp.leftMargin = dp(6);
        outer.addView(badge, gp);
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

    /** Ancien index deduit = nouvel index - consommation lue dans la liste de l'application. */
    private ArrayList<Object[]> oldIndexFixes() {
        ArrayList<Object[]> out = new ArrayList<>();
        for (Meters.Row r : Meters.ROWS) {
            if (r.extra || r.newIdx.isEmpty() || r.oldIdx.isEmpty()) continue;
            Meters.Closed c = Meters.CLOSED.get(r.key);
            if (c == null || !c.note.startsWith("consommation lue") || c.conso.isEmpty()) continue;
            if (!r.newIdx.equals(c.idx)) continue; // le tableau a change depuis l'analyse
            try {
                long n = Long.parseLong(r.newIdx), o = Long.parseLong(r.oldIdx), k = Long.parseLong(c.conso);
                long implied = n - k;
                if (implied < 0 || implied == o) continue; // compteur remis a zero: on ne touche pas
                out.add(new Object[]{r, implied, k});
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    private boolean autoOldOn() {
        try {
            return getSharedPreferences("cfg", MODE_PRIVATE).getBoolean("autoOld", true);
        } catch (Throwable t) {
            return true;
        }
    }

    /** Corrige tout seul les anciens index quand le nouvel index est confirme par l'application (nouvel - consommation). */
    private int autoFixOld() {
        ArrayList<Object[]> fx = oldIndexFixes();
        int done = 0;
        for (Object[] f : fx) {
            Meters.Row r = (Meters.Row) f[0];
            long implied = (Long) f[1];
            String was = r.oldIdx;
            r.oldIdx = String.valueOf(implied);
            String nt = "ancien index corrige automatiquement (etait " + was + ") - conforme a l'application";
            r.note = r.note.isEmpty() ? nt : r.note + " | " + nt;
            done++;
        }
        if (done > 0) ScanService.saveTable(this);
        return done;
    }

    private void fixOldIndexes() {
        final ArrayList<Object[]> fx = oldIndexFixes();
        if (fx.isEmpty()) {
            Toast.makeText(this, Meters.CLOSED.isEmpty()
                    ? "Lancez d'abord \u00AB Analyser la consommation \u00BB (clavier > Outils)"
                    : "Aucun ancien index \u00E0 corriger", Toast.LENGTH_LONG).show();
            return;
        }
        // les plus gros ecarts d'abord
        java.util.Collections.sort(fx, (x, y) -> Long.compare(
                Math.abs(Long.parseLong(((Meters.Row) y[0]).oldIdx) - (Long) y[1]),
                Math.abs(Long.parseLong(((Meters.Row) x[0]).oldIdx) - (Long) x[1])));
        final int n = fx.size();
        final boolean[] on = new boolean[n];          // rien n'est coche par defaut
        final long[] val = new long[n];
        for (int i = 0; i < n; i++) val[i] = (Long) fx.get(i)[1];
        final android.app.Dialog d = new android.app.Dialog(this);
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(0xFFF3F5F8);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.VERTICAL);
        head.setBackground(shape(0xFF1F5FD0, 0));
        head.setPadding(dp(18), dp(16), dp(18), dp(14));
        head.addView(label("Anciens index \u00E0 corriger", 19, 0xFFFFFFFF, true));
        final TextView sub = label("", 12, 0xCCFFFFFF, false);
        head.addView(sub);
        page.addView(head);

        final Button go = new Button(this);
        final Runnable[] upd = new Runnable[1];
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(10), dp(8), dp(10), dp(8));
        final View[] cards = new View[n];
        final TextView[] newTv = new TextView[n];
        final TextView[] mark = new TextView[n];
        for (int i = 0; i < n; i++) {
            final int k = i;
            final Meters.Row r = (Meters.Row) fx.get(i)[0];
            final long conso = (Long) fx.get(i)[2];
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.HORIZONTAL);
            card.setGravity(android.view.Gravity.CENTER_VERTICAL);
            card.setPadding(dp(12), dp(10), dp(12), dp(10));
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-1, -2);
            cl.setMargins(0, dp(4), 0, dp(4));
            mark[i] = label("", 16, 0xFFFFFFFF, true);
            mark[i].setGravity(android.view.Gravity.CENTER);
            card.addView(mark[i], new LinearLayout.LayoutParams(dp(28), dp(28)));
            LinearLayout mid = new LinearLayout(this);
            mid.setOrientation(LinearLayout.VERTICAL);
            mid.setPadding(dp(12), 0, dp(8), 0);
            mid.addView(label(r.compteur, 16, 0xFF0B0C0E, true));
            String nm = r.client == null ? "" : r.client;
            if (!nm.isEmpty()) mid.addView(label(nm, 12, 0xFF5B6573, false));
            LinearLayout nums = new LinearLayout(this);
            nums.setOrientation(LinearLayout.HORIZONTAL);
            nums.setGravity(android.view.Gravity.CENTER_VERTICAL);
            nums.setPadding(0, dp(4), 0, 0);
            TextView o = label(r.oldIdx, 15, 0xFFC62828, true);
            o.setPaintFlags(o.getPaintFlags() | android.graphics.Paint.STRIKE_THRU_TEXT_FLAG);
            nums.addView(o);
            nums.addView(label("  \u2192  ", 15, 0xFF5B6573, false));
            newTv[i] = label(String.valueOf(val[i]), 17, 0xFF17803F, true);
            nums.addView(newTv[i]);
            mid.addView(nums);
            LinearLayout chips = new LinearLayout(this);
            chips.setOrientation(LinearLayout.HORIZONTAL);
            chips.setPadding(0, dp(5), 0, 0);
            TextView c1 = label("conso " + conso, 11, 0xFF1F5FD0, true);
            c1.setBackground(shape(0xFFE8EFFC, 10));
            c1.setPadding(dp(8), dp(2), dp(8), dp(2));
            chips.addView(c1);
            TextView c2 = label("nouveau " + r.newIdx, 11, 0xFF5B6573, true);
            c2.setBackground(shape(0xFFEEF0F3, 10));
            c2.setPadding(dp(8), dp(2), dp(8), dp(2));
            LinearLayout.LayoutParams l2 = new LinearLayout.LayoutParams(-2, -2);
            l2.setMargins(dp(6), 0, 0, 0);
            chips.addView(c2, l2);
            if (conso >= 100) {
                TextView c3 = label("conso \u00E9lev\u00E9e", 11, 0xFFC77000, true);
                c3.setBackground(shape(0xFFFFF1DB, 10));
                c3.setPadding(dp(8), dp(2), dp(8), dp(2));
                chips.addView(c3, l2);
            }
            mid.addView(chips);
            card.addView(mid, new LinearLayout.LayoutParams(0, -2, 1f));
            TextView ed = label("\u270E", 18, 0xFF1F5FD0, true);
            ed.setGravity(android.view.Gravity.CENTER);
            ed.setBackground(shape(0xFFE8EFFC, 12));
            card.addView(ed, new LinearLayout.LayoutParams(dp(40), dp(40)));
            ed.setOnClickListener(v -> {
                final EditText et = new EditText(this);
                et.setInputType(InputType.TYPE_CLASS_NUMBER);
                et.setText(String.valueOf(val[k]));
                new AlertDialog.Builder(this).setTitle("Ancien index de " + r.compteur).setView(et)
                        .setPositiveButton("OK", (dd, w) -> {
                            try {
                                long v2 = Long.parseLong(et.getText().toString().trim());
                                if (v2 >= 0) {
                                    val[k] = v2;
                                    newTv[k].setText(String.valueOf(v2));
                                    on[k] = true;
                                    upd[0].run();
                                }
                            } catch (NumberFormatException ignored) {
                            }
                        }).setNegativeButton("Annuler", null).show();
            });
            card.setOnClickListener(v -> {
                on[k] = !on[k];
                upd[0].run();
            });
            cards[i] = card;
            list.addView(card, cl);
        }
        upd[0] = () -> {
            int c = 0;
            for (int i = 0; i < n; i++) {
                if (on[i]) c++;
                cards[i].setBackground(shape(on[i] ? 0xFFE9F6EE : 0xFFFFFFFF, 16));
                mark[i].setText(on[i] ? "\u2713" : "");
                mark[i].setBackground(shape(on[i] ? 0xFF17803F : 0xFFD5DAE1, 14));
            }
            sub.setText(n + " propos\u00E9s \u2022 " + c + " s\u00E9lectionn\u00E9(s) \u2022 touchez une carte pour la choisir");
            go.setText(c == 0 ? "Choisissez des compteurs" : "Corriger la s\u00E9lection (" + c + ")");
            go.setEnabled(c > 0);
            go.setAlpha(c > 0 ? 1f : 0.45f);
        };
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(list);
        page.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(android.view.Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(0xFFFFFFFF);
        bar.setPadding(dp(10), dp(8), dp(10), dp(8));
        Button all = new Button(this);
        all.setText("Tout");
        all.setAllCaps(false);
        all.setOnClickListener(v -> {
            for (int i = 0; i < n; i++) on[i] = true;
            upd[0].run();
        });
        Button none = new Button(this);
        none.setText("Aucun");
        none.setAllCaps(false);
        none.setOnClickListener(v -> {
            for (int i = 0; i < n; i++) on[i] = false;
            upd[0].run();
        });
        bar.addView(all, new LinearLayout.LayoutParams(-2, -2));
        bar.addView(none, new LinearLayout.LayoutParams(-2, -2));
        go.setAllCaps(false);
        go.setTextColor(0xFFFFFFFF);
        go.setBackground(shape(0xFF17803F, 14));
        go.setOnClickListener(v -> {
            int done = 0;
            for (int i = 0; i < n; i++) {
                if (!on[i]) continue;
                Meters.Row r = (Meters.Row) fx.get(i)[0];
                String was = r.oldIdx;
                r.oldIdx = String.valueOf(val[i]);
                String nt = "ancien index corrige (etait " + was + ")";
                r.note = r.note.isEmpty() ? nt : r.note + " | " + nt;
                done++;
            }
            ScanService.saveTable(this);
            reload();
            d.dismiss();
            Toast.makeText(this, done + " ancien(s) index corrige(s)", Toast.LENGTH_LONG).show();
        });
        LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(0, dp(48), 1f);
        gl.setMargins(dp(8), 0, 0, 0);
        bar.addView(go, gl);
        page.addView(bar);
        upd[0].run();

        d.setContentView(page);
        android.view.Window w = d.getWindow();
        if (w != null) {
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        }
        d.show();
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
        box.addView(toolRow(d, "\u2211", "Corriger les anciens index",
                oldIndexFixes().size() + " \u00E0 corriger (d'apr\u00E8s la consommation)", this::fixOldIndexes));
        box.addView(toolRow(d, "\u2699", "Correction automatique des anciens index : " + (autoOldOn() ? "OUI" : "NON"),
                "D\u00E8s que le nouvel index est confirm\u00E9 par l'application", () -> {
                    boolean nv = !autoOldOn();
                    getSharedPreferences("cfg", MODE_PRIVATE).edit().putBoolean("autoOld", nv).apply();
                    Toast.makeText(this, nv ? "Correction automatique activ\u00E9e" : "Correction automatique d\u00E9sactiv\u00E9e",
                            Toast.LENGTH_SHORT).show();
                    if (nv) {
                        autoFixOld();
                        reload();
                    }
                }));
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
                "Hors application", "\u00C9lev\u00E9es", "N\u00E9gatifs", "\uD83D\uDCF7 Photos"};
        chipFilters = new int[]{F_ALL, F_SENT, F_ORANGE, F_ERR, F_NEW, F_UNL, F_HIGH, F_NEG, F_PHOTO};
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
            if (fk == F_HIGH) {
                c.setOnLongClickListener(v -> {
                    askHigh();
                    return true;
                });
            }
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
                            + "\nIndex 0 non ecrits: " + rr.zero
                            + "\nLectures annulees a verifier dans le tableau: " + rr.cancelMarked
                            + "\nAvis: " + rr.green + " normaux, " + rr.yellow + " a verifier, " + rr.red + " douteux";
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
        if (autoOldOn()) {
            int n = autoFixOld();
            if (n > 0) {
                Toast.makeText(this, n + " ancien(s) index corrig\u00E9(s) automatiquement (conforme \u00E0 l'application)",
                        Toast.LENGTH_LONG).show();
            }
        }
        reload();
    }
}
