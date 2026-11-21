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
        String[] h = Meters.HEADS;
        String[] c = r == null ? null : r.cols;
        if (h == null || c == null) return -1;
        ArrayList<Integer> mo = new ArrayList<>();
        ArrayList<Double> val = new ArrayList<>();
        for (int i = 0; i < h.length && i < c.length; i++) {
            String k = Meters.norm(h[i]);
            if (!k.startsWith("HIST ")) continue;
            int m = monthOf(k.substring(5));
            if (m < 0 || c[i] == null) continue;
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
        while (rates.size() > 4) rates.remove(0);
        if (rates.size() < 2) return -1;
        Collections.sort(rates);
        int n = rates.size();
        return n % 2 == 1 ? rates.get(n / 2) : (rates.get(n / 2 - 1) + rates.get(n / 2)) / 2;
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
        double u = usual(r);
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
