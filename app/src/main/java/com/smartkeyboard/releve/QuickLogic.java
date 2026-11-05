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
    /** Etat d'un compteur: aucun index / index saisi (tableau) / deja dans le journal (cloture). */
    static final int S_NONE = 0, S_ENTERED = 1, S_CLOSED = 2;

    /** Le journal de l'application (lu automatiquement) dit-il que ce compteur est deja saisi? */
    static Meters.Closed closedOf(Meters.Row r) {
        Meters.Closed c = Meters.CLOSED.get(Meters.contractOf(r));
        if (c == null) c = Meters.CLOSED.get(r.key);
        if (c == null || c.idx == null || c.idx.isEmpty()) return null;
        return c;
    }

    static int state(Meters.Row r) {
        if (closedOf(r) != null) return S_CLOSED;
        if (r.status == Meters.SENT || (!r.newIdx.isEmpty() && !Meters.isOffApp(r))) return S_ENTERED;
        return S_NONE;
    }

    private static String digits(String s) {
        if (s == null) return "";
        StringBuilder b = null;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch >= '0' && ch <= '9') {
                if (b != null) b.append(ch);
            } else if (b == null) {
                b = new StringBuilder(s.length());
                b.append(s, 0, i);
            }
        }
        return b == null ? s : b.toString();
    }

    // chiffres des numeros calcules une seule fois (la recherche est relancee a chaque touche)
    private static List<Meters.Row> cacheList = null;
    private static int cacheSize = -1;
    private static String cacheFirst = "", cacheLast = "";
    private static String[] cacheKey = new String[0], cacheCon = new String[0];

    private static synchronized void ensureCache(List<Meters.Row> rows) {
        int n = rows.size();
        String f0 = n == 0 ? "" : String.valueOf(rows.get(0).key), l0 = n == 0 ? "" : String.valueOf(rows.get(n - 1).key);
        if (rows == cacheList && n == cacheSize && f0.equals(cacheFirst) && l0.equals(cacheLast)) return;
        cacheFirst = f0;
        cacheLast = l0;
        String[] k = new String[n], c = new String[n];
        int i = 0;
        for (Meters.Row r : rows) {
            if (i >= n) break;
            k[i] = digits(r.key);
            c[i] = digits(Meters.contractOf(r));
            i++;
        }
        cacheKey = k;
        cacheCon = c;
        cacheList = rows;
        cacheSize = n;
    }

    /** A appeler apres modification des numeros du tableau (import, ajout). */
    static synchronized void invalidate() {
        cacheList = null;
    }

    /** Compteurs dont le numero (ou le contrat) se termine par / contient les chiffres tapes. */
    static ArrayList<Meters.Row> search(List<Meters.Row> rows, String q, int max) {
        final ArrayList<Meters.Row> out = new ArrayList<>();
        final ArrayList<Integer> rank = new ArrayList<>();
        String d = digits(q);
        if (d.isEmpty()) return out;
        final ArrayList<Object[]> hit = new ArrayList<>();
        int order = 0;
        ensureCache(rows);
        final String[] ckA = cacheKey, ccA = cacheCon;
        for (Meters.Row r : rows) {
            order++;
            if (r.extra) continue;
            int ix = order - 1;
            if (ix >= ckA.length) break;
            String k = ckA[ix];
            String c = ccA[ix];
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
