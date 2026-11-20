package com.smartkeyboard.releve;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Locale;

/**
 * Seuil intelligent: la consommation "habituelle" de chaque compteur vient de son propre historique mensuel
 * (colonnes "Hist <mois>" du fichier importe, ce sont des index). Sans historique, on garde la hausse max fixe.
 */
final class Smart {
    /** Seuil = habituel x facteur / 10 (20 = deux fois l'habituel). */
    static int factorX10 = 20;
    /** Hausse minimale: jamais de seuil en dessous (evite d'alerter sur 1 m3 -> 3 m3). */
    static long minRise = 5;

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
            if (d < 0) continue; // compteur remplace ou remise a zero
            rates.add(d / gap);
        }
        while (rates.size() > 6) rates.remove(0);
        if (rates.size() < 2) return -1;
        Collections.sort(rates);
        int n = rates.size();
        return n % 2 == 1 ? rates.get(n / 2) : (rates.get(n / 2 - 1) + rates.get(n / 2)) / 2;
    }

    /** Plus forte hausse acceptee pour ce compteur. */
    static long limit(Meters.Row r) {
        double u = usual(r);
        if (u < 0) return ScanService.maxJump;
        return Math.max(minRise, (long) Math.ceil(u * factorX10 / 10.0));
    }

    static boolean high(Meters.Row r, long cons) {
        return cons >= 0 && cons > limit(r);
    }

    static String describe(Meters.Row r) {
        double u = usual(r);
        if (u < 0) return "limite +" + ScanService.maxJump;
        return String.format(Locale.FRANCE, "habituel %.1f/mois, limite +%d", u, limit(r));
    }
}
