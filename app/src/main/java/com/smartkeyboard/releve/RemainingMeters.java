package com.smartkeyboard.releve;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;

/**
 * Compteurs restants ("A realiser") = lignes d'origine du tableau importe, moins celles deja cloturees.
 *
 * Les cloturees viennent de Meters.CLOSED, qui est rempli par DEUX sources aux cles differentes:
 * - la synchro de l'onglet Cloturee(s): cle = numero de compteur;
 * - le journal TRACE_CLOTURE_RELEVE: cle = numero de contrat/client (le numero de compteur n'est
 *   renseigne que si la ligne a ete rattachee au tableau sans ambiguite).
 * Chaque source est rapprochee par sa propre cle. Jamais par l'ordre des lignes, jamais par le statut.
 * Un contrat partage par plusieurs compteurs n'est jamais devine: ces lignes restent, signalees.
 *
 * Aucune dependance Android: le calcul est testable sur le poste de travail.
 */
public final class RemainingMeters {

    public static final String FLAG_AMBIGUOUS =
            "À vérifier : clôture ambiguë (contrat partagé par plusieurs compteurs)";
    public static final String FLAG_SENT =
            "À vérifier : marqué envoyé par Smart Keyboard mais absent de Clôturée(s)";

    public static final class Result {
        /** Lignes restantes, dans l'ordre du fichier d'origine. */
        public final ArrayList<Meters.Row> remaining = new ArrayList<>();
        /** Remarque eventuelle par ligne restante (identite de l'objet, pas de comparaison par valeur). */
        public final IdentityHashMap<Meters.Row, String> flags = new IdentityHashMap<>();
        public String[] heads = new String[0];
        /** Lignes d'origine uniques (hors compteurs ajoutes a la main ou absents du tableau). */
        public int dbRows;
        /** Doublons ecartes (meme numero de compteur). */
        public int duplicates;
        /** Enregistrements Cloturee(s) disponibles dans l'application. */
        public int closedEntries;
        /** Lignes d'origine retrouvees dans Cloturee(s). */
        public int closedMatched;
        /** Cles Cloturee(s) sans ligne d'origine correspondante (cloturees ailleurs / hors tableau). */
        public int closedNotInTable;
        /** Lignes restantes dont la cloture est ambigue (contrat partage). */
        public int ambiguous;
        /** Lignes restantes marquees "envoye" par l'application mais absentes de Cloturee(s). */
        public int sentNotClosed;
        public boolean noClosedData;
    }

    private RemainingMeters() {
    }

    /** Chiffres seulement, sans zeros de tete (meme regle que l'import du journal). */
    private static String digitsKey(String s) {
        if (s == null) return "";
        String d = s.replaceAll("\\D", "");
        int i = 0;
        while (i < d.length() - 1 && d.charAt(i) == '0') i++;
        return d.substring(i);
    }

    public static Result compute(List<Meters.Row> rows, Collection<Meters.Closed> closed, String[] heads) {
        Result res = new Result();
        res.heads = heads == null ? new String[0] : heads;
        res.closedEntries = closed == null ? 0 : closed.size();
        res.noClosedData = res.closedEntries == 0;

        // 1. lignes d'origine uniques, par numero de compteur
        ArrayList<Meters.Row> orig = new ArrayList<>();
        HashSet<String> seen = new HashSet<>();
        for (Meters.Row r : rows) {
            if (r.extra || r.status == Meters.UNLISTED) continue;
            String k = r.key == null || r.key.isEmpty() ? Meters.key(r.compteur) : r.key;
            if (k.isEmpty()) continue;
            if (!seen.add(k)) {
                res.duplicates++;
                continue;
            }
            orig.add(r);
        }
        res.dbRows = orig.size();

        // 2. cles de cloture, chaque source avec sa propre cle
        HashSet<String> meterKeys = new HashSet<>();
        HashSet<String> contractKeys = new HashSet<>();
        if (closed != null) {
            for (Meters.Closed c : closed) {
                String mk = c.meter == null || c.meter.trim().isEmpty() ? "" : Meters.key(c.meter);
                if (!mk.isEmpty()) {
                    meterKeys.add(mk);
                } else {
                    String ck = digitsKey(c.key);
                    if (!ck.isEmpty()) contractKeys.add(ck);
                }
            }
        }

        // 3. index des contrats parmi les lignes d'origine
        HashMap<String, ArrayList<Meters.Row>> byContract = new HashMap<>();
        HashSet<String> origKeys = new HashSet<>();
        for (Meters.Row r : orig) {
            origKeys.add(r.key == null || r.key.isEmpty() ? Meters.key(r.compteur) : r.key);
            String ck = digitsKey(Meters.contractOf(r));
            if (ck.isEmpty()) continue;
            ArrayList<Meters.Row> l = byContract.get(ck);
            if (l == null) {
                l = new ArrayList<>();
                byContract.put(ck, l);
            }
            l.add(r);
        }
        for (String mk : meterKeys) if (!origKeys.contains(mk)) res.closedNotInTable++;

        IdentityHashMap<Meters.Row, Boolean> closedByContract = new IdentityHashMap<>();
        IdentityHashMap<Meters.Row, Boolean> ambiguousRows = new IdentityHashMap<>();
        for (String ck : contractKeys) {
            ArrayList<Meters.Row> l = byContract.get(ck);
            if (l == null || l.isEmpty()) {
                res.closedNotInTable++;
            } else if (l.size() == 1) {
                closedByContract.put(l.get(0), Boolean.TRUE);
            } else {
                for (Meters.Row r : l) ambiguousRows.put(r, Boolean.TRUE);
            }
        }

        // 4. restants = origine - cloturees
        for (Meters.Row r : orig) {
            String k = r.key == null || r.key.isEmpty() ? Meters.key(r.compteur) : r.key;
            if (meterKeys.contains(k) || closedByContract.containsKey(r)) {
                res.closedMatched++;
                continue;
            }
            res.remaining.add(r);
            if (ambiguousRows.containsKey(r)) {
                res.flags.put(r, FLAG_AMBIGUOUS);
                res.ambiguous++;
            } else if (r.status == Meters.SENT) {
                res.flags.put(r, FLAG_SENT);
                res.sentNotClosed++;
            }
        }
        return res;
    }
}
