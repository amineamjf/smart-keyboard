package com.smartkeyboard.releve;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Locale;

/**
 * Seuil intelligent: la consommation "habituelle" de chaque compteur vient de son propre historique mensuel
 * (colonnes "Hist <mois>" du fichier importe, ce sont des index), comme SmartRV: mediane des 4 dernieres
 * consommations mensuelles, seuil = max(minimum, facteur x habituel). Sans historique (moins de 2 mois): aucune
 * alerte intelligente; seul le seuil fixe FACULTATIF (desactive par defaut) peut s'appliquer.
 */
final class Smart {
    /** Seuil = habituel x facteur / 10 (20 = deux fois l'habituel). */
    static int factorX10 = 20;
    /** Hausse minimale: jamais de seuil en dessous (evite d'alerter sur 1 m3 -> 3 m3). */
    static long minRise = 8;
    /** Alertes intelligentes actives (sinon seul le seuil fixe facultatif s'applique). */
    static boolean on = true;
    /** Nombre de derniers mois pris en compte pour la mediane, et historique minimal requis. */
    static int win = 4;
    static int minHist = 2;

    private static final String[] MONTHS = {"JANVIER", "FEVRIER", "MARS", "AVRIL", "MAI", "JUIN", "JUILLET",
            "AOUT", "SEPTEMBRE", "OCTOBRE", "NOVEMBRE", "DECEMBRE"};

    private Smart() {
    }

    static int monthOf(String normName) {
        for (int i = 0; i < MONTHS.length; i++) if (MONTHS[i].equals(normName.trim())) return i;
        return -1;
    }

    /** Consommation mensuelle habituelle (mediane des derniers mois), ou -1 si l'historique est insuffisant. */
    static double usual(Meters.Row r) {
        ArrayList<Double> rates = rates(r);
        if (rates == null) return -1;
        ArrayList<Double> s = new ArrayList<>(rates);
        Collections.sort(s);
        int n = s.size();
        return n % 2 == 1 ? s.get(n / 2) : (s.get(n / 2 - 1) + s.get(n / 2)) / 2;
    }

    private static String[] cacheSrc;
    private static int[] cacheCols = new int[0];

    /** Colonnes d'historique (paires colonne, mois), calculees une seule fois par en-tete: "Hist Juillet" ou "Juillet". */
    private static synchronized int[] histCols(String[] h) {
        if (cacheSrc == h) return cacheCols;
        ArrayList<Integer> l = new ArrayList<>();
        for (int i = 0; i < h.length; i++) {
            String k = Meters.norm(h[i]);
            if (k.startsWith("HIST ")) k = k.substring(5);
            int m = monthOf(k);
            if (m < 0) continue;
            l.add(i);
            l.add(m);
        }
        int[] a = new int[l.size()];
        for (int i = 0; i < a.length; i++) a[i] = l.get(i);
        cacheCols = a;
        cacheSrc = h;
        return a;
    }

    /** Derniers mois de consommation (ordre chronologique), ou null si l'historique est insuffisant. */
    static ArrayList<Double> rates(Meters.Row r) {
        String[] h = Meters.HEADS;
        String[] c = r == null ? null : r.cols;
        if (h == null || c == null) return null;
        int[] hc = histCols(h);
        if (hc.length < 4) return null;
        ArrayList<Integer> mo = new ArrayList<>();
        ArrayList<Double> val = new ArrayList<>();
        for (int q = 0; q + 1 < hc.length; q += 2) {
            int i = hc[q], m = hc[q + 1];
            if (i >= c.length || c[i] == null) continue;
            String v = c[i].trim().replace(',', '.');
            if (v.isEmpty()) continue;
            try {
                val.add(Double.parseDouble(v));
                mo.add(m);
            } catch (NumberFormatException ignored) {
            }
        }
        ArrayList<Double> rates = new ArrayList<>();
        for (int j = 1; j < val.size(); j++) {
            int gap = (mo.get(j) - mo.get(j - 1) + 12) % 12;
            if (gap == 0) gap = 12;
            double d = val.get(j) - val.get(j - 1);
            if (d < 0) { // compteur remplace ou remise a zero: on repart de zero apres
                rates.clear();
                continue;
            }
            rates.add(d / gap);
        }
        while (rates.size() > win) rates.remove(0);
        if (rates.size() < minHist) return null;
        return rates;
    }

    /** Serie brute de l'historique: paires {mois (0-11), index}, ordre des colonnes; vide si aucune colonne. */
    static ArrayList<double[]> series(Meters.Row r) {
        ArrayList<double[]> out = new ArrayList<>();
        String[] h = Meters.HEADS;
        String[] c = r == null ? null : r.cols;
        if (h == null || c == null) return out;
        int[] hc = histCols(h);
        for (int q = 0; q + 1 < hc.length; q += 2) {
            int i = hc[q];
            if (i >= c.length || c[i] == null) continue;
            String v = c[i].trim().replace(',', '.');
            if (v.isEmpty()) continue;
            try {
                out.add(new double[]{hc[q + 1], Double.parseDouble(v)});
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    /** Noms francais des mois (0-11). */
    static String monthName(int m) {
        String[] n = {"Janvier", "F\u00E9vrier", "Mars", "Avril", "Mai", "Juin", "Juillet", "Ao\u00FBt", "Septembre",
                "Octobre", "Novembre", "D\u00E9cembre"};
        return m >= 0 && m < 12 ? n[m] : "?";
    }

    /** Index attendu comme SmartRV: ancien index + consommation habituelle; -1 si pas assez d'historique. */
    static long expected(Meters.Row r) {
        try {
            if (r == null || r.oldIdx == null) return -1;
            String s = r.oldIdx.trim();
            if (s.isEmpty()) return -1;
            double u = usual(r);
            if (u < 0) return -1;
            return Math.round(Double.parseDouble(s.replace(',', '.')) + u);
        } catch (Throwable t) {
            return -1;
        }
    }

    /** Plus forte hausse acceptee pour ce compteur. */
    static long limit(Meters.Row r) {
        long fixed = ScanService.maxJump > 0 ? ScanService.maxJump : Long.MAX_VALUE;
        double u = on ? usual(r) : -1;
        if (u < 0) return fixed;
        double thr = Math.max((double) minRise, u * factorX10 / 10.0);
        // alerte des que conso >= seuil  <=>  conso > ceil(seuil) - 1
        return Math.min(fixed, (long) Math.ceil(thr) - 1);
    }

    static boolean high(Meters.Row r, long cons) {
        return cons >= 0 && cons > limit(r);
    }

    static String describe(Meters.Row r) {
        double u = usual(r);
        if (u < 0) return ScanService.maxJump > 0 ? "sans historique, seuil fixe +" + ScanService.maxJump
                : "sans historique: pas de seuil";
        return String.format(Locale.FRANCE, "habituel %.1f/mois, alerte des +%d", u, limit(r) + 1);
    }
}
