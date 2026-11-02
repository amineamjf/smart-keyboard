package com.smartkeyboard.releve;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Analyse (sans rien d'Android) des lignes du journal de l'application d'origine.
 * Une ligne TRACE_CLOTURE_RELEVE avec un index le fixe; une ligne sans index (anomalie, pas de lecture)
 * et une ligne TRACE_ANNULER l'effacent. Seul l'etat final de chaque client compte.
 */
final class LogParse {
    static final class Ev {
        String t = "", client = "", idx = "", code = "";
        boolean cancel;
        int seq;
    }

    private static final Pattern RTOK = Pattern.compile("R_(\\d+)_\\d+");
    private static final Pattern DMY = Pattern.compile("(\\d{2})/(\\d{2})/(\\d{4}) (\\d{2}:\\d{2}:\\d{2})");

    final ArrayList<Ev> events = new ArrayList<>();
    int lines, cancels, noIdx;
    /** clients dont une lecture a ete annulee et qui n'ont plus de lecture valide a la fin. */
    final java.util.HashSet<String> cancelledClients = new java.util.HashSet<>();
    private int seq = 0;

    static String keyOf(String s) {
        if (s == null) return "";
        String d = s.replaceAll("\\D", "");
        int i = 0;
        while (i < d.length() - 1 && d.charAt(i) == '0') i++;
        return d.substring(i);
    }

    private static String toIso(String s) {
        if (s == null) return "";
        Matcher m = DMY.matcher(s.trim());
        if (m.matches()) return m.group(3) + "-" + m.group(2) + "-" + m.group(1) + " " + m.group(4);
        return s.trim();
    }

    void addLine(String line) {
        if (line == null) return;
        if (line.startsWith("TRACE_ANNULER")) {
            String[] p = line.split("\t", -1);
            if (p.length < 3) return;
            Matcher m = RTOK.matcher(p[p.length - 1]);
            if (!m.find()) return;
            Ev e = new Ev();
            e.cancel = true;
            e.client = keyOf(m.group(1));
            e.t = toIso(p[1]);
            e.seq = seq++;
            if (!e.client.isEmpty()) events.add(e);
            return;
        }
        if (!line.startsWith("TRACE_CLOTURE_RELEVE")) return;
        String[] p = line.split("\t", -1);
        if (p.length < 10) return;
        Ev e = new Ev();
        e.client = keyOf(p[3]);
        e.t = toIso(p[1]);
        if (e.t.isEmpty()) e.t = p[7].trim();
        String v = p[p.length - 1].startsWith("R_") ? p[p.length - 2] : p[9];
        v = v.trim();
        e.idx = v.matches("\\d{1,9}") ? String.valueOf(Long.parseLong(v)) : "";
        e.code = p[6].trim();
        e.seq = seq++;
        if (e.client.isEmpty() || e.t.isEmpty()) return;
        lines++;
        events.add(e);
    }

    /** client -> {date/heure, index, code releve}. */
    HashMap<String, String[]> resolve() {
        ArrayList<Ev> ev = new ArrayList<>(events);
        Collections.sort(ev, (a, b) -> {
            int c = a.t.compareTo(b.t);
            return c != 0 ? c : Integer.compare(a.seq, b.seq);
        });
        HashMap<String, String[]> last = new HashMap<>();
        cancelledClients.clear();
        java.util.HashSet<String> everCancelled = new java.util.HashSet<>();
        cancels = 0;
        noIdx = 0;
        for (Ev e : ev) {
            if (e.cancel) {
                if (last.remove(e.client) != null) cancels++;
                everCancelled.add(e.client);
            } else if (e.idx.isEmpty()) {
                last.remove(e.client);
                noIdx++;
            } else {
                last.put(e.client, new String[]{e.t, e.idx, e.code});
            }
        }
        for (String c : everCancelled) if (!last.containsKey(c)) cancelledClients.add(c);
        return last;
    }
}
