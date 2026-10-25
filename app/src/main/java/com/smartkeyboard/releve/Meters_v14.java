package com.smartkeyboard.releve;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

/** Tableau des compteurs importe depuis Excel / CSV, avec l'etat de chaque ligne. */
public final class Meters {

    public static final int NEW = 0;      // pas encore vu
    public static final int SIM = 1;      // simulation: aurait ete envoye
    public static final int SENT = 2;     // envoye
    public static final int EXTRA = 3;    // absent du tableau (vu sur l'ecran)
    public static final int NODATA = 4;   // rien a envoyer
    public static final int PHOTO = 5;    // photo requise, saute
    public static final int SUSPECT = 6;  // index suspect, non envoye
    public static final int UNKNOWN = 7;  // anomalie / annotation inconnue
    public static final int ERROR = 8;    // erreur, automatisation arretee

    public static final class Row {
        public String compteur = "";
        public String key = "";
        public String client = "";
        public String contrat = "";
        public String oldIdx = "";
        public String newIdx = "";
        public String anomaly = "";
        public String annotation = "";
        public String note = "";
        public int status = NEW;
        public boolean extra = false;
        public String[] cols = new String[0]; // cellules d'origine du fichier importe
        public String warn = "";             // ex: compteur different du tableau (remplace)
    }

    /** En-tetes du fichier importe (pour retrouver nom, adresse, contrat...). */
    public static String[] HEADS = new String[0];

    // Listes sures entre threads: l'import tourne en arriere-plan pendant que l'ecran se rafraichit
    public static final java.util.concurrent.CopyOnWriteArrayList<Row> ROWS =
            new java.util.concurrent.CopyOnWriteArrayList<>();
    public static int lastDup = 0;
    private static final java.util.concurrent.ConcurrentHashMap<String, Row> MAP =
            new java.util.concurrent.ConcurrentHashMap<>();

    private Meters() {
    }

    // ---------- Normalisation ----------

    /** Majuscules, sans accents, lettres et chiffres separes par un espace. */
    public static String norm(String s) {
        if (s == null) return "";
        String t = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        t = t.toUpperCase(Locale.ROOT);
        return t.replaceAll("[^A-Z0-9]+", " ").trim();
    }

    /** Cle de comparaison: lettres et chiffres seulement. */
    public static String key(String s) {
        String k = norm(s).replace(" ", "");
        // les zeros du debut ne comptent pas: "000123" et "123" sont le meme compteur
        int i = 0;
        while (i < k.length() - 1 && k.charAt(i) == '0') i++;
        return k.substring(i);
    }

    /** Enleve le numero au debut ("03-LOCAL INHABITE" devient "LOCAL INHABITE"). */
    public static String stripCode(String normalized) {
        return normalized.replaceFirst("^\\d{1,3} ", "");
    }

    public static boolean noAnomaly(String s) {
        String n = norm(s);
        return n.isEmpty() || n.equals("RAS") || n.equals("AUCUNE")
                || stripCode(n).equals("RELEVE SANS ANOMALIE");
    }

    public static boolean noAnnotation(String s) {
        String n = norm(s);
        return n.isEmpty() || n.equals("RAS") || n.equals("AUCUNE")
                || n.equals("AUCUNE ANNOTATION");
    }

    public static boolean isLocalInhabite(String s) {
        return stripCode(norm(s)).equals("LOCAL INHABITE");
    }

    /** Nettoie un nombre venant d'Excel: 1321.0 devient 1321, 5.2E11 devient 520000000000. */
    public static String cleanNumber(String s) {
        if (s == null) return "";
        String t = s.replace(' ', ' ').trim();
        if (t.isEmpty()) return "";
        try {
            if (t.matches("[-+]?\\d+(\\.\\d+)?[eE][-+]?\\d+")) {
                t = new BigDecimal(t).toPlainString();
            }
        } catch (Exception ignored) {
        }
        if (t.matches("[-+]?\\d+\\.0+")) t = t.substring(0, t.indexOf('.'));
        return t;
    }

    private static final HashMap<String, String> ALIAS = new HashMap<>();

    static {
        ALIAS.put("INACCESSIBLE", "COMPTEUR NON ACCESSIBL");
        ALIAS.put("LOCAL INABITIE", "LOCAL INHABITE");
        ALIAS.put("LOCAL INHABITIE", "LOCAL INHABITE");
        ALIAS.put("LOCAL INABITE", "LOCAL INHABITE");
        ALIAS.put("COMPTEUR INACCESSIBLE", "COMPTEUR NON ACCESSIBL");
        ALIAS.put("BLOQUE", "COMPTEUR BLOQUE");
        ALIAS.put("INTROUVABLE", "COMPTEUR INTROUVABLE");
        ALIAS.put("ILLISIBLE", "COMPTEUR ILLISIBLE");
        ALIAS.put("DEFECTUEUX", "COMPTEUR DEFECTUEUX");
    }

    /**
     * Cherche dans la liste des choix visibles celui qui correspond au texte du tableau.
     * Retourne -1 s'il n'y a pas de correspondance sure (aucune ou plusieurs).
     */
    private static String codeOf(String normalized) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(\\d{1,3}) ").matcher(normalized);
        return m.find() ? m.group(1) : "";
    }

    private static String firstWord(String s) {
        int i = s.indexOf(' ');
        return i < 0 ? s : s.substring(0, i);
    }

    public static int bestMatch(List<String> options, String excel) {
        String e = stripCode(norm(excel));
        if (e.isEmpty()) return -1;
        // Meme numero de code (ex: 03-...) et meme premier mot: on accepte meme si l'orthographe differe
        String ec = codeOf(norm(excel));
        if (!ec.isEmpty()) {
            int hit = -1, n = 0;
            for (int i = 0; i < options.size(); i++) {
                String on = norm(options.get(i));
                if (codeOf(on).equals(ec) && firstWord(stripCode(on)).equals(firstWord(e))) {
                    hit = i;
                    n++;
                }
            }
            if (n == 1) return hit;
        }
        String alias = ALIAS.get(e);
        if (alias != null) e = alias;
        int found = -1;
        int count = 0;
        for (int i = 0; i < options.size(); i++) {
            String o = stripCode(norm(options.get(i)));
            if (o.equals(e)) {
                found = i;
                count++;
            }
        }
        if (count == 1) return found;
        if (count > 1) return -1;
        for (int i = 0; i < options.size(); i++) {
            String o = stripCode(norm(options.get(i)));
            int mn = Math.min(o.length(), e.length());
            if (mn >= 12 && (o.startsWith(e) || e.startsWith(o))) {
                found = i;
                count++;
            }
        }
        return count == 1 ? found : -1;
    }

    // ---------- Acces au tableau ----------

    public static Row find(String key) {
        return key == null ? null : MAP.get(key);
    }

    public static Row addExtra(String display, String key) {
        Row r = new Row();
        r.compteur = display;
        r.key = key;
        r.extra = true;
        r.status = EXTRA;
        r.note = "absent du tableau";
        ROWS.add(r);
        MAP.put(key, r);
        return r;
    }

    public static void resetStatuses() {
        for (int i = ROWS.size() - 1; i >= 0; i--) {
            Row r = ROWS.get(i);
            if (r.extra) {
                MAP.remove(r.key);
                ROWS.remove(i);
            } else {
                r.status = NEW;
                r.note = "";
            }
        }
    }

    public static int[] counts() {
        int[] c = new int[9];
        for (Row r : ROWS) c[r.status]++;
        return c;
    }

    public static int count(int status) {
        int n = 0;
        for (Row r : ROWS) if (r.status == status) n++;
        return n;
    }

    public static String statusLabel(int s) {
        switch (s) {
            case SIM: return "Simule";
            case SENT: return "Envoye";
            case EXTRA: return "Absent du tableau";
            case NODATA: return "Sans donnee";
            case PHOTO: return "Photo requise";
            case SUSPECT: return "Index suspect";
            case UNKNOWN: return "Choix inconnu";
            case ERROR: return "Erreur";
            default: return "Non vu";
        }
    }

    // ---------- Import ----------

    private static int colOf(String[] header, String... names) {
        for (int i = 0; i < header.length; i++) {
            String n = norm(header[i]);
            for (String want : names) {
                if (want.endsWith("*")) {
                    if (n.startsWith(want.substring(0, want.length() - 1))) return i;
                } else if (n.equals(want)) {
                    return i;
                }
            }
        }
        return -1;
    }

    public static String lastReport = "";

    private static String cName(String[] head, int c) {
        return c < 0 || c >= head.length ? "INTROUVABLE" : "'" + (head[c] == null ? "" : head[c].trim()) + "'";
    }

    private static String cell(String[] row, int c) {
        if (c < 0 || c >= row.length || row[c] == null) return "";
        return row[c].trim();
    }

    /** Remplace le tableau par le contenu des feuilles. Retourne le nombre de lignes. */
    public static int importSheets(List<List<String[]>> sheets) throws Exception {
        for (List<String[]> rows : sheets) {
            for (int h = 0; h < rows.size() && h < 30; h++) {
                String[] head = rows.get(h);
                int cCompteur = colOf(head, "N COMPTEUR", "COMPTEUR", "NUM COMPTEUR");
                if (cCompteur < 0) continue;
                int cNew = colOf(head, "NOUVEL INDEX*", "NOUVEAU INDEX*", "INDEX NOUVEAU*",
                        "INDEX NOUVEL*", "INDEX ACTUEL*", "INDEX RELEVE*", "NEW INDEX*", "NOUV INDEX*");
                int cOld = colOf(head, "ANCIEN INDEX*", "INDEX ANCIEN*", "ANC INDEX*",
                        "INDEX PRECEDENT*", "DERNIER INDEX*", "OLD INDEX*", "PREV INDEX*",
                        "ENCIEN INDEX*", "ANSIEN INDEX*", "ANCIN INDEX*", "ANCIAN INDEX*");
                int cAno = colOf(head, "ANOMALIE");
                int cAnn = colOf(head, "ANNOTATION");
                int cCli = colOf(head, "CLIENT", "NOM DU CLIENT*", "NOM CLIENT*", "NOM*");
                int cCon = colOf(head, "N CLIENT*", "N CONTRAT*", "NUM CONTRAT*", "CONTRAT*");
                HEADS = new String[head.length];
                for (int j = 0; j < head.length; j++) HEADS[j] = head[j] == null ? "" : head[j].trim();
                ArrayList<Row> tmpRows = new ArrayList<>();
                HashMap<String, Row> tmpMap = new HashMap<>();
                int dup = 0;
                for (int i = h + 1; i < rows.size(); i++) {
                    String[] row = rows.get(i);
                    String comp = cell(row, cCompteur);
                    String k = key(comp);
                    if (k.isEmpty()) continue;
                    if (tmpMap.containsKey(k)) {
                        dup++;
                        continue;
                    }
                    Row r = new Row();
                    r.compteur = comp;
                    r.key = k;
                    r.client = cell(row, cCli);
                    r.contrat = cell(row, cCon);
                    r.oldIdx = cleanNumber(cell(row, cOld));
                    r.newIdx = cleanNumber(cell(row, cNew));
                    r.anomaly = cell(row, cAno);
                    r.annotation = cell(row, cAnn);
                    r.cols = new String[row.length];
                    for (int j = 0; j < row.length; j++) r.cols[j] = row[j] == null ? "" : row[j].trim();
                    tmpRows.add(r);
                    tmpMap.put(k, r);
                }
                if (tmpRows.isEmpty()) throw new Exception("Aucune ligne apres l'en-tete");
                StringBuilder rp = new StringBuilder();
                rp.append("Colonnes: compteur=").append(cName(head, cCompteur))
                        .append(" | ancien=").append(cName(head, cOld))
                        .append(" | nouvel=").append(cName(head, cNew))
                        .append(" | client=").append(cName(head, cCli))
                        .append(" | contrat=").append(cName(head, cCon))
                        .append(" | anomalie=").append(cName(head, cAno));
                Row r0 = tmpRows.get(0);
                rp.append("\n1er compteur: ").append(r0.compteur).append(" ancien=[").append(r0.oldIdx)
                        .append("] nouvel=[").append(r0.newIdx).append("]");
                if (cOld < 0 || cNew < 0) {
                    rp.append("\nATTENTION: colonne(s) introuvable(s). En-tetes du fichier: ");
                    for (int j = 0; j < head.length && j < 30; j++) {
                        rp.append(j > 0 ? " ; " : "").append(head[j] == null ? "" : head[j].trim());
                    }
                }
                lastReport = rp.toString();
                ROWS.clear();
                MAP.clear();
                ROWS.addAll(tmpRows);
                MAP.putAll(tmpMap);
                lastDup = dup;
                return ROWS.size();
            }
        }
        throw new Exception("Colonne 'N° Compteur' introuvable");
    }

    // ---------- Sauvegarde ----------

    private static String clean(String s) {
        return s == null ? "" : s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }

    private static String joinCells(String[] a) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; a != null && i < a.length; i++) {
            if (i > 0) sb.append('\u0001');
            sb.append(clean(a[i]).replace('\u0001', ' '));
        }
        return sb.toString();
    }

    private static String[] splitCells(String s) {
        if (s == null || s.isEmpty()) return new String[0];
        return s.split("\u0001", -1);
    }

    public static void save(File f) {
        try (Writer w = new OutputStreamWriter(new FileOutputStream(f), "UTF-8")) {
            w.write("#H\t" + joinCells(HEADS) + "\n");
            for (Row r : ROWS) {
                w.write(clean(r.compteur) + "\t" + clean(r.client) + "\t" + clean(r.contrat) + "\t"
                        + clean(r.oldIdx) + "\t" + clean(r.newIdx) + "\t" + clean(r.anomaly) + "\t"
                        + clean(r.annotation) + "\t" + r.status + "\t" + clean(r.note) + "\t"
                        + (r.extra ? "1" : "0") + "\t" + joinCells(r.cols) + "\t" + clean(r.warn) + "\n");
            }
        } catch (Exception ignored) {
        }
    }

    public static void load(File f) {
        if (!f.exists()) {
            ROWS.clear();
            MAP.clear();
            return;
        }
        ArrayList<Row> tmpRows = new ArrayList<>();
        HashMap<String, Row> tmpMap = new HashMap<>();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new FileInputStream(f), "UTF-8"))) {
            String line;
            HEADS = new String[0];
            while ((line = br.readLine()) != null) {
                String[] p = line.split("\t", -1);
                if (p.length >= 2 && p[0].equals("#H")) {
                    HEADS = splitCells(p[1]);
                    continue;
                }
                if (p.length < 10) continue;
                Row r = new Row();
                r.compteur = p[0];
                r.key = key(p[0]);
                r.client = p[1];
                r.contrat = p[2];
                r.oldIdx = p[3];
                r.newIdx = p[4];
                r.anomaly = p[5];
                r.annotation = p[6];
                try {
                    r.status = Integer.parseInt(p[7]);
                } catch (Exception e) {
                    r.status = NEW;
                }
                r.note = p[8];
                r.extra = "1".equals(p[9]);
                if (p.length > 10) r.cols = splitCells(p[10]);
                if (p.length > 11) r.warn = p[11];
                tmpRows.add(r);
                tmpMap.put(r.key, r);
            }
        } catch (Exception ignored) {
        }
        ROWS.clear();
        MAP.clear();
        ROWS.addAll(tmpRows);
        MAP.putAll(tmpMap);
    }

    /** Valeur d'une colonne du fichier d'origine, retrouvee par son en-tete (noms avec * = debut). */
    public static String colValue(Row r, String... names) {
        if (r == null || r.cols == null) return "";
        for (String want : names) {
            for (int i = 0; i < HEADS.length && i < r.cols.length; i++) {
                String n = norm(HEADS[i]);
                boolean ok = want.endsWith("*") ? n.startsWith(want.substring(0, want.length() - 1))
                        : n.equals(want);
                if (ok && r.cols[i] != null && !r.cols[i].trim().isEmpty()) return r.cols[i].trim();
            }
        }
        return "";
    }

    public static String nameOf(Row r) {
        if (!r.client.isEmpty()) return r.client;
        return colValue(r, "NOM*", "CLIENT*", "TITULAIRE*", "ABONNE*", "RAISON*");
    }

    public static String addressOf(Row r) {
        return colValue(r, "ADRESSE*", "ADR*", "RUE*", "LIEU*", "LOCALISATION*");
    }

    /** Coordonnees GPS {latitude, longitude} lues dans la colonne GPS du fichier, ou null. */
    public static String[] gpsOf(Row r) {
        String g = colValue(r, "GPS*", "COORD*", "POSITION*");
        if (g.isEmpty()) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(-?\\d{1,3}(?:[.,]\\d+)?)\\s*[,; ]\\s*(-?\\d{1,3}(?:[.,]\\d+)?)").matcher(g);
        if (!m.find()) return null;
        String a = m.group(1).replace(',', '.'), b = m.group(2).replace(',', '.');
        try {
            double la = Double.parseDouble(a), lo = Double.parseDouble(b);
            if (Math.abs(la) > 90 || Math.abs(lo) > 180 || (la == 0 && lo == 0)) return null;
        } catch (Exception e) {
            return null;
        }
        return new String[]{a, b};
    }

    private static java.util.HashSet<String> tokens(String s) {
        java.util.HashSet<String> t = new java.util.HashSet<>();
        for (String w : norm(s).split(" ")) if (w.length() > 0) t.add(w);
        return t;
    }

    private static boolean sameTokens(java.util.HashSet<String> a, java.util.HashSet<String> b) {
        return !a.isEmpty() && a.equals(b);
    }

    private static double overlap(java.util.HashSet<String> a, java.util.HashSet<String> b) {
        if (a.isEmpty() || b.isEmpty()) return 0;
        int n = 0;
        for (String x : a) if (b.contains(x)) n++;
        return (double) n / Math.min(a.size(), b.size());
    }

    /** Cherche un compteur du tableau par nom du client (et adresse si plusieurs): compteur remplace. */
    public static Row findByName(String name, String addr) {
        if (name == null || name.trim().isEmpty()) return null;
        java.util.HashSet<String> tn = tokens(name);
        if (tn.size() < 2) return null;
        ArrayList<Row> cand = new ArrayList<>();
        for (Row r : ROWS) {
            if (r.extra) continue;
            if (sameTokens(tn, tokens(nameOf(r)))) cand.add(r);
        }
        if (cand.size() == 1) return cand.get(0);
        if (cand.size() > 1 && addr != null && !addr.trim().isEmpty()) {
            java.util.HashSet<String> ta = tokens(addr);
            ArrayList<Row> good = new ArrayList<>();
            for (Row r : cand) if (overlap(ta, tokens(addressOf(r))) >= 0.6) good.add(r);
            if (good.size() == 1) return good.get(0);
        }
        return null;
    }

    public static String contractOf(Row r) {
        if (!r.contrat.isEmpty()) return r.contrat;
        return colValue(r, "CONTRAT*", "N CONTRAT*", "POLICE*", "ABONNEMENT*");
    }

    private static String q(String s) {
        return "\"" + (s == null ? "" : s.replace("\"", "\"\"")) + "\"";
    }

    public static String csv() {
        StringBuilder sb = new StringBuilder("﻿");
        sb.append("Compteur;Client;Ancien index;Nouvel index;Anomalie;Annotation;Statut;Note\n");
        for (Row r : ROWS) {
            sb.append(q(r.compteur)).append(';').append(q(r.client)).append(';')
                    .append(q(r.oldIdx)).append(';').append(q(r.newIdx)).append(';')
                    .append(q(r.anomaly)).append(';').append(q(r.annotation)).append(';')
                    .append(q(statusLabel(r.status))).append(';').append(q(r.note)).append('\n');
        }
        return sb.toString();
    }
}
