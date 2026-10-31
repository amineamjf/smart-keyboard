package com.smartkeyboard.releve;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

/**
 * Lit (en lecture seule) les journaux TRACE_CLOTURE_RELEVE de l'application d'origine:
 * chaque ligne contient le numero client, l'heure de cloture et l'index saisi.
 * On rattache chaque ligne a une ligne du tableau par le numero de contrat/client.
 * Securite: rien n'est ecrit dans le tableau tant que les index du journal ne concordent pas
 * avec ceux deja saisis (au moins 5 comparaisons et 80% d'accord).
 */
final class LogImport {
    static final class Result {
        int lines, clients, matched, unmatched, same, diff, filled;
        boolean blocked;
        String msg = "";
    }

    private static String keyOf(String s) {
        if (s == null) return "";
        String d = s.replaceAll("\\D", "");
        int i = 0;
        while (i < d.length() - 1 && d.charAt(i) == '0') i++;
        return d.substring(i);
    }

    private static boolean sameNum(String a, String b) {
        if (a == null || b == null) return false;
        if (!a.matches("\\d{1,9}") || !b.matches("\\d{1,9}")) return false;
        return Long.parseLong(a) == Long.parseLong(b);
    }

    static Result run(ContentResolver cr, Uri tree, File closedFile) {
        Result res = new Result();
        // client -> {iso, index, date lisible}
        HashMap<String, String[]> last = new HashMap<>();
        try {
            String docId = DocumentsContract.getTreeDocumentId(tree);
            Uri kids = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId);
            try (Cursor c = cr.query(kids, new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null)) {
                while (c != null && c.moveToNext()) {
                    String id = c.getString(0), mime = c.getString(1);
                    if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) continue;
                    Uri u = DocumentsContract.buildDocumentUriUsingTree(tree, id);
                    try (BufferedReader br = new BufferedReader(
                            new InputStreamReader(cr.openInputStream(u), "UTF-8"))) {
                        String line;
                        while ((line = br.readLine()) != null) {
                            if (!line.startsWith("TRACE_CLOTURE_RELEVE")) continue;
                            String[] p = line.split("\t", -1);
                            if (p.length < 10) continue;
                            String client = keyOf(p[3]);
                            String iso = p[7].trim();
                            String v = p[p.length - 1].startsWith("R_") ? p[p.length - 2] : p[9];
                            String idx = v.replaceAll("\\D", "");
                            if (client.isEmpty() || !idx.matches("\\d{1,9}") || iso.isEmpty()) continue;
                            res.lines++;
                            String[] old = last.get(client);
                            if (old == null || iso.compareTo(old[0]) >= 0) {
                                last.put(client, new String[]{iso, String.valueOf(Long.parseLong(idx)), p[1].trim()});
                            }
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
        } catch (Exception e) {
            res.blocked = true;
            res.msg = "Lecture du dossier impossible: " + e.getMessage();
            return res;
        }
        res.clients = last.size();
        if (last.isEmpty()) {
            res.blocked = true;
            res.msg = "Aucune ligne TRACE_CLOTURE_RELEVE trouvee dans ce dossier.";
            return res;
        }

        // numero de contrat -> ligne du tableau (ambigu = ignore)
        HashMap<String, Meters.Row> byContract = new HashMap<>();
        HashSet<String> dups = new HashSet<>();
        for (Meters.Row r : Meters.ROWS) {
            if (r.extra) continue;
            String k = keyOf(Meters.contractOf(r));
            if (k.isEmpty()) continue;
            if (byContract.containsKey(k)) dups.add(k);
            else byContract.put(k, r);
        }
        for (Map.Entry<String, String[]> e : last.entrySet()) {
            Meters.Row r = dups.contains(e.getKey()) ? null : byContract.get(e.getKey());
            if (r == null) {
                res.unmatched++;
                continue;
            }
            res.matched++;
            if (r.newIdx.matches("\\d{1,9}")) {
                if (sameNum(r.newIdx, e.getValue()[1])) res.same++;
                else res.diff++;
            }
        }
        int compared = res.same + res.diff;
        if (compared < 5 || res.same < 0.8 * compared) {
            res.blocked = true;
            res.msg = compared < 5
                    ? "Pas assez de lignes a comparer (" + compared + ") pour verifier que le journal correspond au tableau: rien n'a ete ecrit."
                    : "Le journal ne concorde pas avec le tableau (" + res.same + " identiques / " + res.diff
                    + " differents): rien n'a ete ecrit.";
            return res;
        }

        Meters.CLOSED.clear();
        for (Map.Entry<String, String[]> e : last.entrySet()) {
            String client = e.getKey();
            String idx = e.getValue()[1];
            Meters.Row r = dups.contains(client) ? null : byContract.get(client);
            Meters.Closed cl = new Meters.Closed();
            cl.key = client;
            cl.idx = idx;
            cl.date = e.getValue()[2];
            if (r == null) {
                cl.note = "client " + client + " absent du tableau (ou ambigu)";
            } else {
                cl.meter = r.compteur;
                cl.name = r.client;
                cl.address = r.address;
                cl.tournee = Meters.tourneeOf(r);
                if (r.oldIdx.matches("\\d{1,9}") && Long.parseLong(idx) >= Long.parseLong(r.oldIdx)) {
                    cl.conso = String.valueOf(Long.parseLong(idx) - Long.parseLong(r.oldIdx));
                }
                if (r.newIdx.isEmpty()) {
                    cl.note = "rempli depuis le journal";
                    r.newIdx = idx;
                    r.note = "complete depuis le journal de l'application (" + cl.date + ")"
                            + (r.note.isEmpty() ? "" : " | " + r.note);
                    if (r.status == Meters.NEW || r.status == Meters.ERROR) r.status = Meters.SENT;
                    res.filled++;
                } else if (sameNum(r.newIdx, idx)) {
                    cl.note = "identique";
                } else {
                    cl.note = "different du tableau (tableau: " + r.newIdx + ")";
                    r.note = "journal de l'application: " + idx + " (non ecrase)"
                            + (r.note.isEmpty() ? "" : " | " + r.note);
                }
            }
            Meters.CLOSED.put(client, cl);
        }
        Meters.saveClosed(closedFile);
        return res;
    }
}
