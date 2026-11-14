package com.smartkeyboard.releve;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

/**
 * Transforme un nombre dit en darija (texte issu de la reconnaissance vocale) en chiffres.
 *
 * Regles (celles de la lecture a voix haute d'un index):
 * - les mots relies par "و" (et) forment UN nombre dont les valeurs s'additionnent:
 *   "ثلتمية وربعة وثلاثين" = 300 + 4 + 30 = 334; "ألفين وطناش" = 2000 + 12 = 2012;
 * - sans "و", un nouveau morceau commence et ses chiffres se placent a cote du precedent:
 *   "طناش | خمسة وربعين" = 12 puis 45 = 1245; "صفر" seul = 0;
 * - "ألف/آلاف" apres une valeur la multiplie par 1000: "تلات آلاف" = 3000, "خمسة وعشرين ألف" = 25000.
 * Un mot inconnu fait echouer l'analyse: on ne devine jamais un chiffre. Aucune dependance Android.
 */
public final class DarijaNumbers {

    public static final class Result {
        /** Chiffres reconnus, ou null si l'analyse a echoue. */
        public String digits;
        /** Raison de l'echec (en francais), vide si ok. */
        public String error = "";
        /** Texte normalise, utile pour le diagnostic. */
        public String normalized = "";
        /** Vrai si un "و" manque peut-etre (ex: "300" suivi de "45" sans "و"): a verifier. */
        public boolean risky;
        /** Indice de l'alternative retenue (pour best()). */
        public int altIndex = -1;

        public boolean ok() {
            return digits != null;
        }
    }

    private static final HashMap<String, Integer> NUM = new HashMap<>();
    private static final HashSet<String> THOUSAND = new HashSet<>();

    /** Normalisation des graphies arabes: voyelles, formes de alif, ta marbuta, chiffres indiens. */
    public static String norm(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '٠' && c <= '٩') c = (char) ('0' + (c - '٠'));
            else if (c >= '۰' && c <= '۹') c = (char) ('0' + (c - '۰'));
            if ((c >= 'ً' && c <= 'ٟ') || c == 'ٰ' || c == 'ـ') continue;
            switch (c) {
                case 'أ': case 'إ': case 'آ': case 'ٱ': c = 'ا'; break; // alif
                case 'ى': c = 'ي'; break;                                              // alif maqsura
                case 'ة': c = 'ه'; break;                                              // ta marbuta
                case 'ئ': c = 'ي'; break;                                              // hamza sur ya
                case 'ؤ': c = 'و'; break;                                              // hamza sur waw
                default: break;
            }
            if (Character.isLetterOrDigit(c)) b.append(Character.toLowerCase(c));
            else b.append(' ');
        }
        return b.toString().replaceAll("\\s+", " ").trim();
    }

    private static void add(int v, String... words) {
        for (String w : words) NUM.put(norm(w), v);
    }

    static {
        add(0, "صفر", "زيرو", "سيفر");
        add(1, "واحد", "وحد", "واحدة", "وحدة");
        add(2, "جوج", "زوج", "اثنين", "اتنين", "ثنين", "اثنان", "اثنتين");
        add(3, "تلاتة", "ثلاثة", "تلات", "ثلاث", "تلاته");
        add(4, "ربعة", "أربعة", "ربع", "أربع");
        add(5, "خمسة", "خمس");
        add(6, "ستة", "ست");
        add(7, "سبعة", "سبع");
        add(8, "تمنية", "ثمانية", "تمانية", "تمان", "ثمان", "ثماني", "تمنيه");
        add(9, "تسعة", "تسع");
        add(10, "عشرة", "عشر");
        add(11, "حداش", "احدعش", "حداشر", "احداشر", "أحد عشر");
        add(12, "طناش", "اطناش", "اثناعشر", "اتناعشر", "طنعش", "إطناش");
        add(13, "تلطاش", "تلتاش", "تلتطاش", "تلاطاش", "ثلاثةعشر");
        add(14, "ربعطاش", "اربعطاش", "ربعتاش", "اربعتاش", "أربعطاش");
        add(15, "خمسطاش", "خمستاش");
        add(16, "سطاش", "ستطاش", "ستاش");
        add(17, "سبعطاش", "سبعتاش");
        add(18, "تمنطاش", "تمنتاش", "ثمانطاش", "تمانطاش");
        add(19, "تسعطاش", "تسعتاش");
        add(20, "عشرين");
        add(30, "تلاتين", "ثلاثين", "تلاثين");
        add(40, "ربعين", "أربعين");
        add(50, "خمسين");
        add(60, "ستين");
        add(70, "سبعين");
        add(80, "تمانين", "ثمانين", "تمنين");
        add(90, "تسعين");
        add(100, "مية", "مئة", "مائة", "ميه");
        add(200, "ميتين", "مئتين", "مائتين", "ميتان", "مئتان", "مائتان");
        add(300, "تلتمية", "تلاتمية", "ثلثمية", "ثلتمية", "ثلاثمائة", "ثلاثمئة", "تلتميه", "تلاتميه");
        add(400, "ربعمية", "أربعمائة", "أربعمئة", "ربعميه");
        add(500, "خمسمية", "خمسمائة", "خمسمئة", "خمسميه");
        add(600, "ستمية", "ستمائة", "ستميه", "ستمئة");
        add(700, "سبعمية", "سبعمائة", "سبعميه", "سبعمئة");
        add(800, "تمنمية", "ثمانمائة", "ثمنمية", "تمنميه", "ثمانمئة", "ثمانميه");
        add(900, "تسعمية", "تسعمائة", "تسعميه", "تسعمئة");
        add(2000, "ألفين", "ألفان");
        THOUSAND.add(norm("ألف"));
        THOUSAND.add(norm("آلاف"));
        THOUSAND.add(norm("الاف"));
    }

    private static boolean isWord(String t) {
        return NUM.containsKey(t) || THOUSAND.contains(t) || t.matches("\\d+");
    }

    /** Analyse un texte reconnu. */
    public static Result parse(String text) {
        Result res = new Result();
        res.normalized = norm(text);
        if (res.normalized.isEmpty()) {
            res.error = "rien entendu";
            return res;
        }
        // "و" colle au mot suivant ("وربعين"): on le separe sauf si le mot existe tel quel ("واحد")
        ArrayList<String> toks = new ArrayList<>();
        for (String t : res.normalized.split(" ")) {
            if (t.equals("و")) {
                toks.add("&");
            } else if (isWord(t)) {
                toks.add(t);
            } else if (t.startsWith("و") && t.length() > 1 && isWord(t.substring(1))) {
                toks.add("&");
                toks.add(t.substring(1));
            } else {
                toks.add(t);
            }
        }
        StringBuilder out = new StringBuilder();
        ArrayList<String> unknown = new ArrayList<>();
        long sum = 0;
        long lastFlushed = -1;
        boolean open = false, conj = false;
        for (String t : toks) {
            if (t.equals("&")) {
                conj = open;
                continue;
            }
            if (t.matches("\\d+")) {
                if (open) {
                    out.append(sum);
                    lastFlushed = sum;
                    open = false;
                }
                out.append(t);
                conj = false;
                continue;
            }
            if (THOUSAND.contains(t)) {
                if (open && !conj) {
                    sum *= 1000;                     // "تلات آلاف", "خمسة وعشرين ألف"
                } else {
                    if (!open) {
                        open = true;
                        sum = 0;
                    }
                    sum += 1000;                     // "ألف وميتين"
                }
                conj = false;
                continue;
            }
            Integer v = NUM.get(t);
            if (v == null) {
                unknown.add(t);
                conj = false;
                continue;
            }
            if (open && conj) {
                sum += v;
            } else {
                if (open) {
                    out.append(sum);
                    lastFlushed = sum;
                }
                if (lastFlushed >= 100 && lastFlushed % 100 == 0 && v < 100) res.risky = true;
                open = true;
                sum = v;
            }
            conj = false;
        }
        if (open) out.append(sum);
        if (!unknown.isEmpty()) {
            res.error = "mots non reconnus: " + String.join(" ", unknown);
            return res;
        }
        if (out.length() == 0) {
            res.error = "aucun nombre";
            return res;
        }
        if (out.length() > 9) {
            res.error = "nombre trop long (" + out.length() + " chiffres)";
            return res;
        }
        res.digits = out.toString();
        return res;
    }

    /** Premier resultat valide parmi les alternatives de la reconnaissance (meilleure d'abord). */
    public static Result best(List<String> alternatives) {
        Result first = null;
        for (int i = 0; i < alternatives.size(); i++) {
            Result r = parse(alternatives.get(i));
            r.altIndex = i;
            if (first == null) first = r;
            if (r.ok()) return r;
        }
        return first == null ? parse("") : first;
    }
}
