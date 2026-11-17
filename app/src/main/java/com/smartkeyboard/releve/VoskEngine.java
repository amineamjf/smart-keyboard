package com.smartkeyboard.releve;

import android.content.Context;
import android.content.res.AssetManager;

import org.json.JSONObject;
import org.vosk.LibVosk;
import org.vosk.LogLevel;
import org.vosk.Model;
import org.vosk.Recognizer;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/** Reconnaissance francaise hors ligne (Vosk, modele fr embarque) limitee aux nombres et aux ordres. */
public final class VoskEngine {
    private static Model model;
    private static boolean failed = false;

    private VoskEngine() {
    }

    private static final String[] WORDS = {
            "zéro", "un", "une", "deux", "trois", "quatre", "cinq", "six", "sept", "huit", "neuf", "dix", "onze",
            "douze", "treize", "quatorze", "quinze", "seize", "vingt", "vingts", "trente", "quarante", "cinquante",
            "soixante", "septante", "huitante", "nonante", "cent", "cents", "mille", "et",
            "stop", "rechercher", "recherche", "options", "option", "ok", "efface", "effacer", "dernier", "ajouter",
            "apprendre", "photo", "annuler", "placer", "journal", "local", "inhabité", "compteur", "bloqué",
            "inaccessible", "noter", "note", "absent", "abonné", "analyser", "analyse", "consommation",
            "réaliser", "à", "[unk]"};

    public static synchronized boolean available(Context c) {
        try {
            return c.getAssets().list("model-fr") != null && c.getAssets().list("model-fr").length > 0;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void copy(AssetManager am, String src, File dst) throws Exception {
        String[] kids = am.list(src);
        if (kids != null && kids.length > 0) {
            dst.mkdirs();
            for (String k : kids) copy(am, src + "/" + k, new File(dst, k));
        } else {
            try (InputStream in = am.open(src); OutputStream out = new FileOutputStream(dst)) {
                byte[] b = new byte[64 * 1024];
                int r;
                while ((r = in.read(b)) > 0) out.write(b, 0, r);
            }
        }
    }

    /** Charge le modele (copie depuis l'application la premiere fois). A appeler hors du thread principal. */
    public static synchronized boolean load(Context c) {
        if (model != null) return true;
        if (failed) return false;
        try {
            Context app = c.getApplicationContext();
            File dir = new File(app.getFilesDir(), "vosk-fr");
            File ok = new File(dir, ".ok");
            if (!ok.exists()) {
                deleteAll(dir);
                copy(app.getAssets(), "model-fr", dir);
                new FileOutputStream(ok).close();
            }
            LibVosk.setLogLevel(LogLevel.WARNINGS);
            model = new Model(dir.getAbsolutePath());
            return true;
        } catch (Throwable t) {
            failed = true;
            return false;
        }
    }

    private static void deleteAll(File f) {
        File[] k = f.listFiles();
        if (k != null) for (File x : k) deleteAll(x);
        f.delete();
    }

    /** Texte reconnu dans l'enonce (PCM 16 kHz), ou null si le modele est indisponible. */
    public static String recognize(Context c, short[] pcm) {
        if (!load(c)) return null;
        Recognizer r = null;
        try {
            StringBuilder g = new StringBuilder("[");
            for (int i = 0; i < WORDS.length; i++) {
                if (i > 0) g.append(',');
                g.append('"').append(WORDS[i]).append('"');
            }
            g.append(']');
            r = new Recognizer(model, 16000.0f, g.toString());
            r.acceptWaveForm(pcm, pcm.length);
            JSONObject o = new JSONObject(r.getFinalResult());
            return o.optString("text", "").replace("[unk]", "").replaceAll("\\s+", " ").trim();
        } catch (Throwable t) {
            return null;
        } finally {
            if (r != null) {
                try {
                    r.close();
                } catch (Throwable ignore) {
                }
            }
        }
    }

    /** Identifiant d'ordre (comme dans VoiceTrainActivity.COMMANDS) ou null. */
    public static String command(String text) {
        if (text == null) return null;
        String t = " " + FrenchNumbers.norm(text) + " ";
        boolean note = t.contains(" noter ") || t.contains(" note ");
        if (t.contains(" stop ")) return "stop";
        if (t.contains(" recherche")) return "rechercher";
        if (t.contains(" option")) return "options";
        if (t.contains(" efface")) return "efface";
        if (t.contains(" dernier ")) return "dernier";
        if (t.contains(" ajouter ")) return "ajouter";
        if (t.contains(" apprendre ")) return "apprendre";
        if (t.contains(" annuler ")) return "annuler";
        if (t.contains(" placer ")) return "placer";
        if (t.contains(" journal ")) return "journal";
        if (t.contains(" analys")) return "analyser_conso";
        if (t.contains(" realiser ")) return "a_realiser";
        if (t.contains(" photo ")) return "photo";
        if (t.contains(" absent ")) return "note_absent";
        if (t.contains(" inaccessible ")) return note ? "note_inaccessible" : "inaccessible";
        if (t.contains(" bloque ")) return note ? "note_bloque" : "bloque";
        if (t.contains(" local ") || t.contains(" inhabite ")) return note ? "note_local" : "local";
        if (t.trim().equals("ok")) return "ok";
        return null;
    }
}
