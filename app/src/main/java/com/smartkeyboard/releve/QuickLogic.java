package com.smartkeyboard.releve;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Logique pure de la saisie rapide (recherche d'un compteur du tableau, controle et enregistrement du nouvel index). */
final class QuickLogic {
    private QuickLogic() {
    }

    static final int OK = 0, EMPTY = 1, LOWER = 2;

    private static String digits(String s) {
        return s == null ? "" : s.replaceAll("\\D", "");
    }

    /** Compteurs dont le numero (ou le contrat) se termine par / contient les chiffres tapes. */
    static ArrayList<Meters.Row> search(List<Meters.Row> rows, String q, int max) {
        final ArrayList<Meters.Row> out = new ArrayList<>();
        final ArrayList<Integer> rank = new ArrayList<>();
        String d = digits(q);
        if (d.isEmpty()) return out;
        final ArrayList<Object[]> hit = new ArrayList<>();
        int order = 0;
        for (Meters.Row r : rows) {
            order++;
            if (r.extra) continue;
            String k = digits(r.key);
            String c = digits(Meters.contractOf(r));
            int rk = -1;
            if (k.endsWith(d)) rk = 0;
            else if (c.endsWith(d)) rk = 1;
            else if (k.contains(d)) rk = 2;
            else if (c.contains(d)) rk = 3;
            if (rk >= 0) hit.add(new Object[]{r, rk, order});
        }
        Collections.sort(hit, new Comparator<Object[]>() {
            @Override
            public int compare(Object[] a, Object[] b) {
                int x = (Integer) a[1], y = (Integer) b[1];
                return x != y ? Integer.compare(x, y) : Integer.compare((Integer) a[2], (Integer) b[2]);
            }
        });
        for (Object[] h : hit) {
            out.add((Meters.Row) h[0]);
            if (out.size() >= max) break;
        }
        return out;
    }

    /** OK, EMPTY (rien tape) ou LOWER (nouvel index inferieur a l'ancien: demander confirmation). */
    static int check(Meters.Row r, String newIdx) {
        String n = digits(newIdx);
        if (n.isEmpty()) return EMPTY;
        String o = digits(r.oldIdx);
        if (!o.isEmpty() && o.length() < 12 && n.length() < 12) {
            if (Long.parseLong(n) < Long.parseLong(o)) return LOWER;
        }
        return OK;
    }

    /** Enregistre dans le tableau seulement (jamais dans l'application d'origine): a transmettre a la main. */
    static void apply(Meters.Row r, String newIdx, String stamp) {
        r.newIdx = Meters.cleanNumber(digits(newIdx));
        r.status = Meters.NODATA;
        r.note = Meters.OFFAPP + " " + stamp + " (saisie rapide)";
    }
}
