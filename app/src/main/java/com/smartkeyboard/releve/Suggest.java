package com.smartkeyboard.releve;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;

/**
 * Moteur de suggestions 100 % local: completion par prefixe, correction (distance d'edition),
 * apprentissage des mots tapes, mot suivant (bigrammes). Aucun acces reseau, aucune dependance Android.
 */
final class Suggest {
    static final String FR = "fr", AR = "ar";

    /** Un dictionnaire (une langue): mots de base tries par cle normalisee + mots appris. */
    private static final class Dict {
        final String lang;
        String[] word = new String[0];
        String[] key = new String[0];
        int[] w = new int[0];
        final HashMap<String, L> learned = new HashMap<>();         // cle normalisee -> mot appris
        final HashMap<String, Integer> bigram = new HashMap<>();    // "prec\tsuiv" -> compte
        HashSet<String> known = new HashSet<>();                    // cles de tous les mots connus

        Dict(String lang) {
            this.lang = lang;
        }
    }

    /** Mot appris: forme affichee, cle normalisee (calculee une fois) et compteur. */
    private static final class L {
        final String word;
        final String key;
        int cnt;

        L(String word, String key, int cnt) {
            this.word = word;
            this.key = key;
            this.cnt = cnt;
        }
    }

    private final HashMap<String, Dict> dicts = new HashMap<>();

    Suggest() {
        dicts.put(FR, new Dict(FR));
        dicts.put(AR, new Dict(AR));
    }

    // ------------------------------------------------------------ normalisation

    static String norm(String lang, String s) {
        if (s == null) return "";
        if (AR.equals(lang)) {
            StringBuilder b = new StringBuilder(s.length());
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if ((c >= 0x064B && c <= 0x0652) || c == 0x0670 || c == 0x0640) continue;
                switch (c) {
                    case '\u0623': case '\u0625': case '\u0622': case '\u0671': c = '\u0627'; break;
                    case '\u0649': c = '\u064A'; break;
                    case '\u0629': c = '\u0647'; break;
                    case '\u0624': c = '\u0648'; break;
                    case '\u0626': c = '\u064A'; break;
                    case '\u06AD': case '\u06A9': case '\u06AA': c = '\u0643'; break;
                    case '\u06A4': c = '\u0641'; break;
                    case '\u067E': c = '\u0628'; break;
                    case '\u0686': c = '\u062C'; break;
                    case '\u06AF': c = '\u0642'; break;
                    default: break;
                }
                b.append(c);
            }
            return b.toString();
        }
        String l = s.toLowerCase(Locale.FRENCH).replace("\u0153", "oe").replace("\u00E6", "ae");
        String d = Normalizer.normalize(l, Normalizer.Form.NFD);
        StringBuilder b = new StringBuilder(d.length());
        for (int i = 0; i < d.length(); i++) {
            char c = d.charAt(i);
            if (Character.getType(c) == Character.NON_SPACING_MARK) continue;
            if (c == '\u2019') c = '\'';
            b.append(c);
        }
        return b.toString();
    }

    /** Un caractere appartient-il a un mot de cette langue? */
    static boolean isWordChar(char c) {
        if (c >= 0x0600 && c <= 0x06FF) {
            // lettres arabes et marques de tachkil; on exclut la ponctuation arabe (\u060C \u061B \u061F et chiffres)
            if (c == 0x060C || c == 0x061B || c == 0x061F) return false;
            if (c >= 0x0660 && c <= 0x0669) return false;
            return true;
        }
        return Character.isLetter(c) || c == '\'' || c == '\u2019' || c == '-';
    }

    // ------------------------------------------------------------ chargement

    /** Charge les mots de base, du plus courant au moins courant. */
    void loadBase(String lang, String spaceSeparated) {
        Dict d = dicts.get(lang);
        if (d == null || spaceSeparated == null) return;
        String[] parts = spaceSeparated.trim().split("\\s+");
        ArrayList<String> ws = new ArrayList<>();
        HashSet<String> seen = new HashSet<>();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            if (seen.add(p)) ws.add(p);
        }
        final int n = ws.size();
        Integer[] idx = new Integer[n];
        final String[] ks = new String[n];
        for (int i = 0; i < n; i++) {
            idx[i] = i;
            ks[i] = norm(lang, ws.get(i));
        }
        Arrays.sort(idx, new Comparator<Integer>() {
            @Override
            public int compare(Integer a, Integer b) {
                int c = ks[a].compareTo(ks[b]);
                return c != 0 ? c : Integer.compare(a, b);
            }
        });
        d.word = new String[n];
        d.key = new String[n];
        d.w = new int[n];
        for (int j = 0; j < n; j++) {
            int i = idx[j];
            d.word[j] = ws.get(i);
            d.key[j] = ks[i];
            d.w[j] = Math.max(40, (int) (60000.0 / (1.0 + i / 25.0)));   // loi de Zipf approchee
            d.known.add(ks[i]);
        }
    }

    // ------------------------------------------------------------ apprentissage

    /** Memorise un mot tape (et le couple precedent -> mot). */
    void learn(String lang, String word, String prev) {
        Dict d = dicts.get(lang);
        if (d == null || word == null) return;
        String w = trimWord(word);
        if (w.length() < 2 || w.length() > 30 || allDigits(w)) return;
        String k = norm(lang, w);
        L l = d.learned.get(k);
        if (l != null) {
            l.cnt++;
        } else if (!inBase(d, k)) {
            d.learned.put(k, new L(w, k, 1));
        } else {
            // mot de base: on ne le recopie que s'il est tape souvent (pour le faire monter)
            d.learned.put(k, new L(baseForm(d, k), k, 1));
        }
        d.known.add(k);
        if (prev != null && !prev.isEmpty()) {
            String pk = norm(lang, trimWord(prev));
            if (pk.length() >= 1) {
                String bk = pk + "\t" + k;
                Integer bc = d.bigram.get(bk);
                d.bigram.put(bk, (bc == null ? 0 : bc) + 1);
            }
        }
        if (d.learned.size() > 6000) prune(d);
    }

    /** Ajoute en masse (noms et adresses du tableau): poids initial modeste. */
    void learnBulk(String lang, Iterable<String> words, int count) {
        Dict d = dicts.get(lang);
        if (d == null) return;
        for (String s : words) {
            String w = trimWord(s);
            if (w.length() < 3 || w.length() > 30 || allDigits(w)) continue;
            String k = norm(lang, w);
            if (d.learned.containsKey(k) || inBase(d, k)) continue;
            d.learned.put(k, new L(w, k, count));
            d.known.add(k);
        }
    }

    private static String trimWord(String s) {
        int a = 0, b = s.length();
        while (a < b && !isWordChar(s.charAt(a))) a++;
        while (b > a && !isWordChar(s.charAt(b - 1))) b--;
        return s.substring(a, b);
    }

    private static boolean allDigits(String s) {
        for (int i = 0; i < s.length(); i++) if (!Character.isDigit(s.charAt(i))) return false;
        return true;
    }

    private boolean inBase(Dict d, String key) {
        int lo = lowerBound(d.key, key);
        return lo < d.key.length && d.key[lo].equals(key);
    }

    private String baseForm(Dict d, String key) {
        int lo = lowerBound(d.key, key);
        return lo < d.key.length && d.key[lo].equals(key) ? d.word[lo] : key;
    }

    /** Forme affichable d'un mot connu (appris ou de base), ou null. */
    private String formOf(Dict d, String key) {
        L l = d.learned.get(key);
        if (l != null) return l.word;
        int lo = lowerBound(d.key, key);
        return lo < d.key.length && d.key[lo].equals(key) ? d.word[lo] : null;
    }

    private void prune(Dict d) {
        ArrayList<L> es = new ArrayList<>(d.learned.values());
        Collections.sort(es, new Comparator<L>() {
            @Override
            public int compare(L a, L b) {
                return Integer.compare(b.cnt, a.cnt);
            }
        });
        d.learned.clear();
        for (int i = 0; i < Math.min(4000, es.size()); i++) d.learned.put(es.get(i).key, es.get(i));
        if (d.bigram.size() > 12000) d.bigram.clear();
    }

    // ------------------------------------------------------------ prediction

    /**
     * Propositions (au plus n). prefix = debut du mot en cours (vide = prediction du mot suivant),
     * prev = mot precedent (peut etre vide).
     */
    ArrayList<String> predict(String lang, String prefix, String prev, int n) {
        ArrayList<String> out = new ArrayList<>();
        Dict d = dicts.get(lang);
        if (d == null || n <= 0) return out;
        if (prefix == null) prefix = "";
        String pk = prev == null || prev.isEmpty() ? "" : norm(lang, trimWord(prev));

        if (prefix.isEmpty()) {
            if (pk.isEmpty()) return out;
            // mot suivant: bigrammes appris
            ArrayList<Object[]> c = new ArrayList<>();
            String head = pk + "\t";
            for (Map.Entry<String, Integer> e : d.bigram.entrySet()) {
                if (e.getKey().startsWith(head)) {
                    String nk = e.getKey().substring(head.length());
                    String form = formOf(d, nk);
                    if (form != null) c.add(new Object[]{form, e.getValue()});
                }
            }
            sortByScore(c);
            for (Object[] o : c) {
                out.add((String) o[0]);
                if (out.size() >= n) break;
            }
            return out;
        }

        String key = norm(lang, prefix);
        if (key.isEmpty()) return out;
        HashMap<String, Double> score = new HashMap<>();   // mot -> score
        // 1) completions de base (plage triee)
        int lo = lowerBound(d.key, key);
        int seen = 0;
        for (int i = lo; i < d.key.length && d.key[i].startsWith(key) && seen < 400; i++, seen++) {
            addScore(score, d.word[i], d.w[i] + bigramBoost(d, pk, d.key[i]));
        }
        // 2) completions apprises
        for (L l : d.learned.values()) {
            if (l.key.startsWith(key)) addScore(score, l.word, 400.0 + 900.0 * l.cnt + bigramBoost(d, pk, l.key));
        }
        // petit bonus aux mots courts (plus probables) et a l'egalite exacte
        for (Map.Entry<String, Double> e : score.entrySet()) {
            int len = e.getKey().length();
            double extra = norm(lang, e.getKey()).equals(key) ? 1.5 : 1.0 - Math.min(0.5, (len - key.length()) * 0.04);
            e.setValue(e.getValue() * extra);
        }
        // 3) corrections si le mot n'existe pas ou si peu de choix
        boolean knownWord = d.known.contains(key);
        if (key.length() >= 3 && (!knownWord || score.size() < n)) {
            int maxDist = key.length() <= 4 ? 1 : 2;
            for (int i = 0; i < d.key.length; i++) {
                String k2 = d.key[i];
                if (Math.abs(k2.length() - key.length()) > maxDist) continue;
                if (k2.startsWith(key)) continue;
                int dist = editDistance(key, k2, maxDist);
                if (dist <= maxDist) addScore(score, d.word[i], d.w[i] / (1.0 + 2.5 * dist) * 0.8);
            }
            for (L l : d.learned.values()) {
                String k2 = l.key;
                if (Math.abs(k2.length() - key.length()) > maxDist || k2.startsWith(key)) continue;
                int dist = editDistance(key, k2, maxDist);
                if (dist <= maxDist) addScore(score, l.word, (400.0 + 900.0 * l.cnt) / (1.0 + 2.5 * dist));
            }
        }
        ArrayList<Object[]> c = new ArrayList<>();
        for (Map.Entry<String, Double> e : score.entrySet()) c.add(new Object[]{e.getKey(), e.getValue()});
        sortByScore(c);
        for (Object[] o : c) {
            out.add(applyCase(prefix, (String) o[0]));
            if (out.size() >= n) break;
        }
        return out;
    }

    private static double bigramBoost(Dict d, String pk, String k) {
        if (pk.isEmpty()) return 0;
        Integer b = d.bigram.get(pk + "\t" + k);
        return b == null ? 0 : 3000.0 * b;
    }

    private static void addScore(HashMap<String, Double> m, String w, double s) {
        Double o = m.get(w);
        if (o == null || o < s) m.put(w, s);
    }

    private static void sortByScore(ArrayList<Object[]> c) {
        Collections.sort(c, new Comparator<Object[]>() {
            @Override
            public int compare(Object[] a, Object[] b) {
                double x = ((Number) a[1]).doubleValue(), y = ((Number) b[1]).doubleValue();
                int r = Double.compare(y, x);
                return r != 0 ? r : ((String) a[0]).compareTo((String) b[0]);
            }
        });
    }

    /** Reprend la casse de ce que l'utilisateur a tape (Majuscule initiale / TOUT EN MAJUSCULES). */
    static String applyCase(String typed, String w) {
        if (typed.length() >= 2 && typed.equals(typed.toUpperCase(Locale.FRENCH))
                && !typed.equals(typed.toLowerCase(Locale.FRENCH))) {
            return w.toUpperCase(Locale.FRENCH);
        }
        if (!typed.isEmpty() && Character.isUpperCase(typed.charAt(0)) && !w.isEmpty()
                && !Character.isUpperCase(w.charAt(0))) {
            return Character.toUpperCase(w.charAt(0)) + w.substring(1);
        }
        return w;
    }

    private static int lowerBound(String[] a, String k) {
        int lo = 0, hi = a.length;
        while (lo < hi) {
            int m = (lo + hi) >>> 1;
            if (a[m].compareTo(k) < 0) lo = m + 1;
            else hi = m;
        }
        return lo;
    }

    /** Distance de Damerau-Levenshtein (transposition comprise), avec coupure au-dela de max. */
    static int editDistance(String a, String b, int max) {
        int la = a.length(), lb = b.length();
        if (Math.abs(la - lb) > max) return max + 1;
        int[][] dp = new int[la + 1][lb + 1];
        for (int i = 0; i <= la; i++) dp[i][0] = i;
        for (int j = 0; j <= lb; j++) dp[0][j] = j;
        for (int i = 1; i <= la; i++) {
            int rowMin = Integer.MAX_VALUE;
            for (int j = 1; j <= lb; j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                int v = Math.min(Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1), dp[i - 1][j - 1] + cost);
                if (i > 1 && j > 1 && a.charAt(i - 1) == b.charAt(j - 2) && a.charAt(i - 2) == b.charAt(j - 1)) {
                    v = Math.min(v, dp[i - 2][j - 2] + 1);
                }
                dp[i][j] = v;
                if (v < rowMin) rowMin = v;
            }
            if (rowMin > max) return max + 1;
        }
        return dp[la][lb];
    }

    /** Le mot (tel que tape) existe-t-il dans le vocabulaire? */
    boolean isKnown(String lang, String word) {
        Dict d = dicts.get(lang);
        return d != null && d.known.contains(norm(lang, trimWord(word)));
    }

    // ------------------------------------------------------------ persistance (texte)

    /** Mots appris et bigrammes, en texte (une ligne par element). */
    String export(String lang) {
        Dict d = dicts.get(lang);
        if (d == null) return "";
        StringBuilder b = new StringBuilder();
        for (L l : d.learned.values()) {
            b.append("W\t").append(l.word).append('\t').append(l.cnt).append('\n');
        }
        int n = 0;
        for (Map.Entry<String, Integer> e : d.bigram.entrySet()) {
            if (e.getValue() < 2 && n > 3000) continue;
            b.append("B\t").append(e.getKey()).append('\t').append(e.getValue()).append('\n');
            n++;
        }
        return b.toString();
    }

    void restore(String lang, String text) {
        Dict d = dicts.get(lang);
        if (d == null || text == null) return;
        for (String line : text.split("\n")) {
            String[] p = line.split("\t");
            try {
                if (p.length == 3 && "W".equals(p[0])) {
                    String k = norm(lang, p[1]);
                    d.learned.put(k, new L(p[1], k, Integer.parseInt(p[2].trim())));
                    d.known.add(k);
                } else if (p.length == 4 && "B".equals(p[0])) {
                    d.bigram.put(p[1] + "\t" + p[2], Integer.parseInt(p[3].trim()));
                }
            } catch (NumberFormatException ignored) {
            }
        }
    }

    int learnedCount(String lang) {
        Dict d = dicts.get(lang);
        return d == null ? 0 : d.learned.size();
    }
}
