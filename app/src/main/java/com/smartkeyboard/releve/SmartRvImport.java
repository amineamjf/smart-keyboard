package com.smartkeyboard.releve;

import android.util.JsonReader;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Lit un export SmartRV (.json) et le convertit en feuille de tableau pour l'import habituel. */
final class SmartRvImport {
    private static final String[] MONTHS = {"Janvier", "Février", "Mars", "Avril", "Mai", "Juin", "Juillet",
            "Août", "Septembre", "Octobre", "Novembre", "Décembre"};

    private SmartRvImport() {
    }

    private static final class M {
        String gps = "", tour = "", meter = "", contract = "", name = "", address = "", phone = "", prev = "", cur = "", ano = "", old = "";
        double sort = Double.MAX_VALUE;
        final HashMap<Integer, String> hist = new HashMap<>();
    }

    private static String str(JsonReader r) throws java.io.IOException {
        switch (r.peek()) {
            case NULL:
                r.nextNull();
                return "";
            case BOOLEAN:
                return String.valueOf(r.nextBoolean());
            case NUMBER:
                String s = r.nextString();
                if (s.endsWith(".0")) s = s.substring(0, s.length() - 2);
                return s;
            case STRING:
                return r.nextString().trim();
            default:
                r.skipValue();
                return "";
        }
    }

    private static int monthIndex(String label) {
        String n = Meters.norm(label);
        for (int i = 0; i < MONTHS.length; i++) if (Meters.norm(MONTHS[i]).equals(n)) return i;
        return -1;
    }

    static List<List<String[]>> read(InputStream in) throws Exception {
        ArrayList<M> list = new ArrayList<>();
        JsonReader r = new JsonReader(new InputStreamReader(in, "UTF-8"));
        r.beginObject();
        while (r.hasNext()) {
            String k = r.nextName();
            if (!k.equals("meters")) {
                r.skipValue();
                continue;
            }
            r.beginArray();
            while (r.hasNext()) {
                M m = new M();
                r.beginObject();
                while (r.hasNext()) {
                    String f = r.nextName();
                    switch (f) {
                        case "tour": m.tour = str(r); break;
                        case "meter": m.meter = str(r); break;
                        case "contract": m.contract = str(r); break;
                        case "name": m.name = str(r); break;
                        case "address": m.address = str(r); break;
                        case "phone": m.phone = str(r); break;
                        case "gps": m.gps = str(r); break;
                        case "replacedFrom": m.old = str(r); break;
                        case "prev": m.prev = str(r); break;
                        case "current": m.cur = str(r); break;
                        case "anomaly": m.ano = str(r); break;
                        case "sortIndex":
                            try {
                                m.sort = Double.parseDouble(str(r));
                            } catch (NumberFormatException e) {
                                m.sort = Double.MAX_VALUE;
                            }
                            break;
                        case "history":
                            if (r.peek() != android.util.JsonToken.BEGIN_OBJECT) {
                                r.skipValue();
                                break;
                            }
                            r.beginObject();
                            while (r.hasNext()) {
                                String lab = r.nextName();
                                String v = str(r);
                                int mi = monthIndex(lab);
                                if (mi >= 0 && !v.isEmpty()) m.hist.put(mi, v);
                            }
                            r.endObject();
                            break;
                        default: r.skipValue();
                    }
                }
                r.endObject();
                if (!m.meter.isEmpty()) list.add(m);
            }
            r.endArray();
        }
        r.endObject();
        if (list.isEmpty()) throw new Exception("Aucun compteur dans ce fichier JSON");
        // ordre de la tournee = sortIndex (ordre de SmartRV)
        Collections.sort(list, (a, b) -> Double.compare(a.sort, b.sort));
        // mois presents, dans l'ordre chronologique (on demarre apres le plus grand trou)
        java.util.TreeSet<Integer> set = new java.util.TreeSet<>();
        for (M m : list) set.addAll(m.hist.keySet());
        ArrayList<Integer> ms = new ArrayList<>(set);
        if (ms.size() > 1) {
            int best = -1, start = 0;
            for (int i = 0; i < ms.size(); i++) {
                int nx = ms.get((i + 1) % ms.size());
                int gap = (nx - ms.get(i) + 12) % 12;
                if (gap > best) {
                    best = gap;
                    start = (i + 1) % ms.size();
                }
            }
            Collections.rotate(ms, -start);
        }
        ArrayList<String> head = new ArrayList<>();
        Collections.addAll(head, "Tournée", "N Compteur", "N Contrat", "Client", "Adresse", "Tel",
                "Ancien index", "Nouvel index", "Anomalie", "GPS");
        for (int mi : ms) head.add("Hist " + MONTHS[mi]);
        head.add("Ancien N\u00B0 Compteur");
        ArrayList<String[]> rows = new ArrayList<>();
        rows.add(head.toArray(new String[0]));
        for (M m : list) {
            String ano = m.ano;
            if (ano.equalsIgnoreCase("RAS") || ano.equalsIgnoreCase("Aucune")) ano = "";
            String[] row = new String[head.size()];
            row[0] = m.tour;
            row[1] = m.meter;
            row[2] = m.contract;
            row[3] = m.name;
            row[4] = m.address;
            row[5] = m.phone;
            row[6] = m.prev;
            row[7] = m.cur;
            row[8] = ano;
            row[9] = m.gps;
            for (int i = 0; i < ms.size(); i++) {
                String v = m.hist.get(ms.get(i));
                row[10 + i] = v == null ? "" : v;
            }
            row[10 + ms.size()] = m.old;
            rows.add(row);
        }
        ArrayList<List<String[]>> sheets = new ArrayList<>();
        sheets.add(rows);
        return sheets;
    }
}
