package com.smartkeyboard.releve;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.widget.Toast;
import java.io.File;
import java.util.ArrayList;

/**
 * Lecture automatique (lecture seule) des journaux de l'application d'origine, dans le dossier "logs"
 * que l'utilisateur a choisi une fois. Remplit seulement les index vides du tableau.
 */
final class LogAuto {
    private static long lastRun = 0;
    private static boolean running = false;
    private static final long EVERY_MS = 10 * 60 * 1000L;

    static long lastRunMs() {
        return lastRun;
    }

    static void saveTree(Context c, Uri tree) {
        try {
            c.getContentResolver().takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Throwable ignored) {
        }
        c.getSharedPreferences("logauto", Context.MODE_PRIVATE).edit().putString("tree", tree.toString()).apply();
    }

    static Uri tree(Context c) {
        SharedPreferences sp = c.getSharedPreferences("logauto", Context.MODE_PRIVATE);
        String s = sp.getString("tree", "");
        return s.isEmpty() ? null : Uri.parse(s);
    }

    /** force: ignore l'intervalle de 10 minutes et affiche toujours un message. */
    static void run(final Context ctx, final boolean force) {
        final Context c = ctx.getApplicationContext();
        final Handler ui = new Handler(Looper.getMainLooper());
        final Uri tree = tree(c);
        if (tree == null) {
            if (force) {
                ui.post(() -> Toast.makeText(c, "Choisissez d'abord le dossier logs: Tableau > Importer ... (dossier logs)",
                        Toast.LENGTH_LONG).show());
            }
            return;
        }
        long now = System.currentTimeMillis();
        if (running || (!force && now - lastRun < EVERY_MS)) return;
        running = true;
        lastRun = now;
        new Thread(() -> {
            String msg = null;
            try {
                ScanService.ensureLoaded(c);
                ContentResolver cr = c.getContentResolver();
                ArrayList<Uri> uris = new ArrayList<>();
                String docId = DocumentsContract.getTreeDocumentId(tree);
                Uri kids = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId);
                long limit = System.currentTimeMillis() - 3L * 24 * 3600 * 1000;
                try (Cursor q = cr.query(kids, new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_MIME_TYPE,
                        DocumentsContract.Document.COLUMN_LAST_MODIFIED}, null, null, null)) {
                    while (q != null && q.moveToNext()) {
                        if (DocumentsContract.Document.MIME_TYPE_DIR.equals(q.getString(1))) continue;
                        long lm = q.isNull(2) ? 0 : q.getLong(2);
                        if (lm != 0 && lm < limit) continue;
                        uris.add(DocumentsContract.buildDocumentUriUsingTree(tree, q.getString(0)));
                    }
                }
                LogImport.Result rr = LogImport.runFiles(cr, uris, new File(c.getFilesDir(), "closed.tsv"), true);
                if (!rr.blocked && rr.filled > 0) {
                    ScanService.saveTable(c);
                    msg = rr.filled + " index remplis depuis le journal de l'application";
                    ui.post(() -> {
                        if (ScanService.instance != null) ScanService.instance.refreshStrip();
                    });
                } else if (force) {
                    msg = rr.blocked ? rr.msg : "Journal lu: aucun index a remplir (" + rr.same + " identiques, "
                            + rr.diff + " differents, " + rr.lines + " lignes)";
                }
            } catch (Throwable e) {
                if (force) msg = "Lecture du journal impossible: " + e.getMessage();
            }
            running = false;
            final String m = msg;
            if (m != null) ui.post(() -> Toast.makeText(c, m, Toast.LENGTH_LONG).show());
        }).start();
    }
}
