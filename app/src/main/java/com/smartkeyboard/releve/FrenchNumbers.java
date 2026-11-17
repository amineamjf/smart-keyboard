package com.smartkeyboard.releve;

import java.util.HashMap;
import java.util.Map;

/** Convertit des nombres dits en francais ("deux mille cinq cent trente") en chiffres. */
public final class FrenchNumbers {
    private static final Map<String, Integer> V = new HashMap<>();

    static {
        String[] u = {"zero", "un", "deux", "trois", "quatre", "cinq", "six", "sept", "huit", "neuf", "dix",
                "onze", "douze", "treize", "quatorze", "quinze", "seize"};
        for (int i = 0; i < u.length; i++) V.put(u[i], i);
        V.put("une", 1);
        V.put("vingt", 20);
        V.put("vingts", 20);
        V.put("trente", 30);
        V.put("quarante", 40);
        V.put("cinquante", 50);
        V.put("soixante", 60);
        V.put("septante", 70);
        V.put("huitante", 80);
        V.put("octante", 80);
        V.put("nonante", 90);
    }

    private FrenchNumbers() {
    }

    static String norm(String t) {
        String s = t.toLowerCase(java.util.Locale.ROOT).replace('é', 'e').replace('è', 'e')
                .replace('-', ' ').replace('\'', ' ');
        return s.trim();
    }

    /** Renvoie les chiffres, ou null si le texte n'est pas un nombre francais clair. */
    public static String parse(String text) {
        if (text == null) return null;
        String n = norm(text);
        if (n.isEmpty()) return null;
        java.util.List<String> tk = new java.util.ArrayList<>();
        for (String w : n.split("\\s+")) if (!w.isEmpty() && !w.equals("et")) tk.add(w);
        if (tk.isEmpty()) return null;
        // suite de chiffres isoles: "un deux trois" -> 123
        boolean digits = tk.size() >= 2;
        for (String w : tk) {
            Integer v = V.get(w);
            if (v == null || v > 9) {
                digits = false;
                break;
            }
        }
        if (digits) {
            StringBuilder sb = new StringBuilder();
            for (String w : tk) sb.append(V.get(w));
            return sb.toString();
        }
        long total = 0, cur = 0;
        boolean any = false;
        for (int i = 0; i < tk.size(); i++) {
            String w = tk.get(i);
            if (w.equals("quatre") && i + 1 < tk.size() && (tk.get(i + 1).equals("vingt") || tk.get(i + 1).equals("vingts"))) {
                cur += 80;
                i++;
                any = true;
            } else if (w.equals("cent") || w.equals("cents")) {
                cur = (cur == 0 ? 1 : cur) * 100;
                any = true;
            } else if (w.equals("mille")) {
                total += (cur == 0 ? 1 : cur) * 1000;
                cur = 0;
                any = true;
            } else if (V.containsKey(w)) {
                cur += V.get(w);
                any = true;
            } else {
                return null;
            }
        }
        if (!any) return null;
        return String.valueOf(total + cur);
    }
}
