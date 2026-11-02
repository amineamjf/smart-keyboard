package com.smartkeyboard.releve;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;

/**
 * Jugement de vraisemblance d'une lecture par sa consommation (nouvel - ancien),
 * compare aux limites reglees et a la mediane de la meme tournee. Aucune ecriture, seulement un avis.
 */
final class Verdict {
    static final int OK = 0, HIGH = 1, ZERO = 2, OUT = 3, NEG = 4, NONE = 5;

    private static boolean num(String s) {
        return s != null && s.matches("\\d{1,9}");
    }

    /** tournee -> consommation mediane (au moins 8 lectures positives dans la tournee). */
    static HashMap<String, Long> medians(Iterable<Meters.Row> rows) {
        HashMap<String, ArrayList<Long>> by = new HashMap<>();
        for (Meters.Row r : rows) {
            if (r.extra || !num(r.oldIdx) || !num(r.newIdx)) continue;
            long o = Long.parseLong(r.oldIdx), n = Long.parseLong(r.newIdx);
            if (n <= o) continue;
            String t = Meters.tourneeOf(r);
            if (t == null) t = "";
            ArrayList<Long> l = by.get(t);
            if (l == null) {
                l = new ArrayList<>();
                by.put(t, l);
            }
            l.add(n - o);
        }
        HashMap<String, Long> med = new HashMap<>();
        for (java.util.Map.Entry<String, ArrayList<Long>> e : by.entrySet()) {
            ArrayList<Long> l = e.getValue();
            if (l.size() < 8) continue;
            Collections.sort(l);
            med.put(e.getKey(), l.get(l.size() / 2));
        }
        return med;
    }

    static int judge(Meters.Row r, HashMap<String, Long> med, long maxJump, long maxDrop) {
        if (!num(r.oldIdx) || !num(r.newIdx)) return NONE;
        long o = Long.parseLong(r.oldIdx), n = Long.parseLong(r.newIdx);
        if (n < o) return NEG;
        if (n > o + maxJump) return OUT;
        String t = Meters.tourneeOf(r);
        Long m = med == null ? null : med.get(t == null ? "" : t);
        long c = n - o;
        if (m != null && m > 0) {
            if (c == 0 && m >= 10) return ZERO;
            if (c >= 3 * m && c - m >= 20) return HIGH;
        }
        return OK;
    }
}
