package com.smartkeyboard.releve;

import android.content.Context;

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

    private static final String[] URLS = {
            "https://github.com/amineamjf/smart-keyboard/releases/download/models/vosk-model-small-fr-0.22.zip",
            "https://alphacephei.com/vosk/models/vosk-model-small-fr-0.22.zip"};

    public static volatile boolean downloading = false;
    public static volatile int percent = 0;

    public interface Progress {
        void on(int percent);
    }

    public static boolean installed(Context c) {
        return new File(c.getApplicationContext().getFilesDir(), "vosk-fr/.ok").exists();
    }

    /** Telecharge le modele francais (une seule fois) et le decompresse. A appeler hors du thread principal. */
    public static synchronized String download(Context c, Progress cb) {
        if (installed(c)) return null;
        downloading = true;
        percent = 0;
        File dir = new File(c.getApplicationContext().getFilesDir(), "vosk-fr");
        File tmp = new File(c.getApplicationContext().getFilesDir(), "vosk-fr.zip.part");
        String err = "telechargement impossible";
        try {
            boolean got = false;
            for (String u : URLS) {
                try {
                    java.net.HttpURLConnection h = (java.net.HttpURLConnection) new java.net.URL(u).openConnection();
                    h.setConnectTimeout(15000);
                    h.setReadTimeout(30000);
                    h.setInstanceFollowRedirects(true);
                    if (h.getResponseCode() != 200) {
                        err = "serveur: " + h.getResponseCode();
                        h.disconnect();
                        continue;
                    }
                    long total = h.getContentLengthLong();
                    long done = 0;
                    try (InputStream in = h.getInputStream(); OutputStream out = new FileOutputStream(tmp)) {
                        byte[] b = new byte[64 * 1024];
                        int r;
                        int last = -1;
                        while ((r = in.read(b)) > 0) {
                            out.write(b, 0, r);
                            done += r;
                            int p = total > 0 ? (int) (done * 95 / total) : 0;
                            if (p != last) {
                                last = p;
                                percent = p;
                                if (cb != null) cb.on(p);
                            }
                        }
                    }
                    h.disconnect();
                    got = true;
                    break;
                } catch (Throwable t) {
                    err = String.valueOf(t.getMessage());
                }
            }
            if (!got) return err;
            deleteAll(dir);
            dir.mkdirs();
            try (java.util.zip.ZipInputStream z = new java.util.zip.ZipInputStream(new java.io.BufferedInputStream(
                    new java.io.FileInputStream(tmp)))) {
                java.util.zip.ZipEntry e;
                byte[] b = new byte[64 * 1024];
                String root = dir.getCanonicalPath() + File.separator;
                while ((e = z.getNextEntry()) != null) {
                    String name = e.getName();
                    int sl = name.indexOf('/');
                    if (sl < 0) continue;
                    name = name.substring(sl + 1); // on retire le dossier racine du zip
                    if (name.isEmpty() || e.isDirectory()) continue;
                    File f = new File(dir, name);
                    if (!f.getCanonicalPath().startsWith(root)) continue; // zip malveillant
                    f.getParentFile().mkdirs();
                    try (OutputStream out = new FileOutputStream(f)) {
                        int r;
                        while ((r = z.read(b)) > 0) out.write(b, 0, r);
                    }
                }
            }
            if (!new File(dir, "am").exists() && !new File(dir, "conf").exists()) {
                deleteAll(dir);
                return "archive du modele invalide";
            }
            new FileOutputStream(new File(dir, ".ok")).close();
            percent = 100;
            if (cb != null) cb.on(100);
            return null;
        } catch (Throwable t) {
            deleteAll(dir);
            return String.valueOf(t.getMessage());
        } finally {
            tmp.delete();
            downloading = false;
        }
    }

    /** Charge le modele. A appeler hors du thread principal. */
    public static synchronized boolean load(Context c) {
        if (model != null) return true;
        if (failed || !installed(c)) return false;
        try {
            File dir = new File(c.getApplicationContext().getFilesDir(), "vosk-fr");
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
