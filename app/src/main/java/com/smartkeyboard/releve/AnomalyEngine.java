package com.smartkeyboard.releve;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

/**
 * Moteur d'anomalies: a partir du contrat et du numero de compteur, retrouve la ligne du tableau et analyse
 * l'historique mensuel (moyenne, debut de hausse, index attendu, verdict). Aucun texte libre: valeurs et
 * verdicts fixes. Le dernier mot reste a l'utilisateur (colonne Confirmation).
 */
final class AnomalyEngine {
    static final String V_NEG = "Index inférieur à l'ancien", V_SWAP = "Chiffres inversés ?",
            V_HIGH2 = "Hausse forte", V_HIGH = "Hausse", V_RECENT = "Hausse récente", V_LOW = "Baisse",
            V_ZERO = "Zéro répété", V_SHORT = "Historique insuffisant",
            V_BAD = "Historique incohérent", V_OK = "Normal", V_UNK = "Non reconnu";

    static final class Res {
        String contrat = "", compteur = "";
        Meters.Row row;
        String verdict = V_OK;
        int prio = 3; // 1 urgent, 2 a revoir, 3 normal
        double mean = Double.NaN;
        double ratio = Double.NaN;
        String start = "";
        Long old, cur, expMin, expMax;
        String remark = "";
        /** Consommation des derniers mois (meme ordre que monthLabels), NaN si inconnue. */
        double[] monthCons = new double[0];
    }

    private AnomalyEngine() {
    }

    private static Long toL(String s) {
        if (s == null) return null;
        String t = s.trim().replace(',', '.');
        if (t.isEmpty()) return null;
        try {
            return Math.round(Double.parseDouble(t));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Libelles des colonnes de consommation: les 3 derniers mois de l'historique du fichier (ordre chronologique). */
    static List<Integer> lastMonths() {
        ArrayList<Integer> ms = new ArrayList<>();
        for (Meters.Row r : Meters.ROWS) {
            ArrayList<double[]> s = Smart.series(r);
            if (s.size() >= 2) {
                for (double[] p : s) ms.add((int) p[0]);
                break;
            }
        }
        int from = Math.max(1, ms.size() - 3);
        return new ArrayList<>(ms.subList(from, ms.size()));
    }

    private static int[] swapped(long n) {
        String s = String.valueOf(Math.abs(n));
        ArrayList<Integer> l = new ArrayList<>();
        for (int i = 0; i + 1 < s.length(); i++) {
            if (s.charAt(i) == s.charAt(i + 1)) continue;
            char[] c = s.toCharArray();
            char t = c[i];
            c[i] = c[i + 1];
            c[i + 1] = t;
            try {
                l.add(Integer.parseInt(new String(c)));
            } catch (NumberFormatException ignored) {
            }
        }
        int[] a = new int[l.size()];
        for (int i = 0; i < a.length; i++) a[i] = l.get(i);
        return a;
    }

    private static double avg(List<Double> l, int to) {
        if (to <= 0) return 0;
        double t = 0;
        for (int i = 0; i < to; i++) t += l.get(i);
        return t / to;
    }

    private static double median(List<Double> l) {
        ArrayList<Double> s = new ArrayList<>(l);
        Collections.sort(s);
        int n = s.size();
        return n % 2 == 1 ? s.get(n / 2) : (s.get(n / 2 - 1) + s.get(n / 2)) / 2;
    }

    /** Analyse d'une ligne du tableau. */
    static Res analyse(Meters.Row r, List<Integer> monthCols) {
        Res res = new Res();
        res.row = r;
        res.compteur = r.compteur;
        res.contrat = r.contrat;
        res.old = toL(r.oldIdx);
        res.cur = toL(r.newIdx);
        ArrayList<double[]> ser = Smart.series(r);
        // consommation mensuelle brute entre releves successifs
        ArrayList<Double> consAll = new ArrayList<>();
        ArrayList<Integer> consMonth = new ArrayList<>();
        boolean bad = false;
        for (int j = 1; j < ser.size(); j++) {
            int gap = ((int) ser.get(j)[0] - (int) ser.get(j - 1)[0] + 12) % 12;
            if (gap == 0) gap = 12;
            double d = ser.get(j)[1] - ser.get(j - 1)[1];
            if (d < 0) {
                bad = true;
                continue;
            }
            consAll.add(d / gap);
            consMonth.add((int) ser.get(j)[0]);
        }
        res.monthCons = new double[monthCols.size()];
        for (int k = 0; k < res.monthCons.length; k++) {
            res.monthCons[k] = Double.NaN;
            for (int q = 0; q < consMonth.size(); q++) {
                if (consMonth.get(q).intValue() == monthCols.get(k)) res.monthCons[k] = consAll.get(q);
            }
        }
        ArrayList<Double> cons = consAll;
        if (cons.size() < 2) {
            res.verdict = bad ? V_BAD : V_SHORT;
            res.prio = 2;
            if (res.old != null) {
                res.expMin = res.old;
                res.expMax = res.old + Math.round(cons.isEmpty() ? 0 : cons.get(0));
            }
            return res;
        }
        double med = median(cons);
        double minC = Collections.min(cons), maxC = Collections.max(cons);
        res.mean = avg(cons, cons.size());
        Long old = res.old, cur = res.cur;
        double exp = old == null ? Double.NaN : old + med;
        if (old != null) {
            res.expMin = Math.round(old + minC);
            res.expMax = Math.round(old + maxC);
        }
        double last = cons.get(cons.size() - 1);
        for (int i = 1; i < cons.size(); i++) {
            double pm = avg(cons, i);
            if (pm == 0) pm = 0.5;
            if (cons.get(i) >= 1.8 * pm && cons.get(i) - pm >= 3) {
                res.start = Smart.monthName(consMonth.get(i));
                break;
            }
        }
        double ref = avg(cons, cons.size() - 1);
        if (cons.size() <= 1) ref = avg(cons, cons.size());
        if (cur != null && old != null) {
            long c = cur - old;
            res.ratio = Math.round(c / Math.max(ref, 1) * 10) / 10.0;
            if (c < 0) {
                res.verdict = V_NEG;
                res.prio = 1;
            } else {
                int[] sw = swapped(cur);
                boolean near = false;
                double bestDist = Math.abs(cur - exp);
                double minDist = Double.MAX_VALUE;
                for (int s : sw) {
                    double dd = Math.abs(s - exp);
                    if (dd <= Math.max(3, 0.6 * Math.abs(med))) near = true;
                    minDist = Math.min(minDist, dd);
                }
                if (sw.length == 0) minDist = bestDist;
                if (near && bestDist > minDist + 5) {
                    res.verdict = V_SWAP;
                    res.prio = 1;
                } else if (c >= 3 * Math.max(ref, 1) && c - ref >= 8) {
                    res.verdict = V_HIGH2;
                    res.prio = 1;
                } else if (c >= 2 * Math.max(ref, 1) && c - ref >= 5) {
                    res.verdict = V_HIGH;
                    res.prio = 2;
                } else if (ref >= 5 && c <= 0.3 * ref) {
                    res.verdict = V_LOW;
                    res.prio = 2;
                }
            }
        } else {
            if (cons.size() >= 3 && cons.get(cons.size() - 1) == 0 && cons.get(cons.size() - 2) == 0
                    && cons.get(cons.size() - 3) == 0) {
                res.verdict = V_ZERO;
                res.prio = 2;
            } else if (last >= 2 * Math.max(ref, 1) && last - ref >= 5) {
                res.verdict = V_RECENT;
                res.prio = 2;
            }
            res.ratio = Math.round(last / Math.max(ref, 1) * 10) / 10.0;
        }
        if (bad && V_OK.equals(res.verdict)) {
            res.verdict = V_BAD;
            res.prio = 2;
        }
        return res;
    }

    /** Resultat pour un couple (contrat, compteur) saisi par l'utilisateur. */
    static Res analysePair(String contrat, String compteur, List<Integer> monthCols, HashMap<String, Meters.Row> byContrat) {
        Meters.Row r = compteur.isEmpty() ? null : Meters.find(Meters.key(compteur));
        String remark = "";
        if (r != null && !contrat.isEmpty() && !r.contrat.isEmpty() && !Meters.key(r.contrat).equals(Meters.key(contrat))) {
            remark = "Contrat différent (tableau : " + r.contrat + ")";
        }
        if (r == null && !contrat.isEmpty()) {
            r = byContrat.get(Meters.key(contrat));
            if (r != null) remark = "Compteur différent (tableau : " + r.compteur + ")";
        }
        if (r == null) {
            Res u = new Res();
            u.contrat = contrat;
            u.compteur = compteur;
            u.verdict = V_UNK;
            u.prio = 1;
            u.monthCons = new double[monthCols.size()];
            for (int i = 0; i < u.monthCons.length; i++) u.monthCons[i] = Double.NaN;
            return u;
        }
        Res a = analyse(r, monthCols);
        a.remark = remark;
        if (!remark.isEmpty() && a.prio > 2) a.prio = 2;
        return a;
    }

    static HashMap<String, Meters.Row> indexByContrat() {
        HashMap<String, Meters.Row> m = new HashMap<>();
        for (Meters.Row r : Meters.ROWS) {
            if (!r.contrat.isEmpty()) m.putIfAbsent(Meters.key(r.contrat), r);
        }
        return m;
    }

    private static String clean(String s) {
        if (s == null) return "";
        String t = s.trim();
        if (t.endsWith(".0") && t.matches("\\d+\\.0")) t = t.substring(0, t.length() - 2);
        return t;
    }

    /** Lit le fichier de l'utilisateur: deux colonnes (contrat, compteur), avec ou sans en-tete. */
    static ArrayList<String[]> readInput(java.io.InputStream in) throws Exception {
        List<List<String[]>> sheets = TableReader.read(in);
        ArrayList<String[]> out = new ArrayList<>();
        if (sheets.isEmpty()) return out;
        List<String[]> rows = sheets.get(0);
        int cC = 0, cM = 1, start = 0;
        for (int i = 0; i < Math.min(5, rows.size()); i++) {
            String[] h = rows.get(i);
            int a = -1, b = -1;
            for (int j = 0; j < h.length; j++) {
                String n = Meters.norm(h[j] == null ? "" : h[j]);
                if (a < 0 && (n.contains("CONTRAT") || n.contains("CLIENT"))) a = j;
                else if (b < 0 && n.contains("COMPTEUR")) b = j;
            }
            if (a >= 0 && b >= 0) {
                cC = a;
                cM = b;
                start = i + 1;
                break;
            }
        }
        for (int i = start; i < rows.size(); i++) {
            String[] r = rows.get(i);
            String c = cC < r.length ? clean(r[cC]) : "";
            String m = cM < r.length ? clean(r[cM]) : "";
            if (c.isEmpty() && m.isEmpty()) continue;
            out.add(new String[]{c, m});
        }
        return out;
    }
}
