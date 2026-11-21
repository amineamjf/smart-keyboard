package com.smartkeyboard.releve;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** Sauvegarde complete en un seul fichier .zip: tableau, lectures, A realiser, ordre, reglages. */
public final class Backup {

    private static final String MAGIC = "RELEVE-BACKUP 1";
    private static final long MAX_ENTRY = 64L * 1024 * 1024;
    private static final int MAX_ENTRIES = 20;

    private Backup() {
    }

    private static byte[] readFile(File f) throws Exception {
        if (!f.exists()) return new byte[0];
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }

    private static void put(ZipOutputStream z, String name, byte[] data) throws Exception {
        z.putNextEntry(new ZipEntry(name));
        z.write(data);
        z.closeEntry();
    }

    private static String esc(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\') sb.append("\\\\");
            else if (c == '\t') sb.append("\\t");
            else if (c == '\n') sb.append("\\n");
            else if (c == '\r') sb.append("\\r");
            else sb.append(c);
        }
        return sb.toString();
    }

    private static String unesc(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char d = s.charAt(++i);
                if (d == 't') sb.append('\t');
                else if (d == 'n') sb.append('\n');
                else if (d == 'r') sb.append('\r');
                else sb.append(d);
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static byte[] prefsBytes(Context c) throws Exception {
        SharedPreferences sp = c.getSharedPreferences("cfg", Context.MODE_PRIVATE);
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, ?> e : sp.getAll().entrySet()) {
            Object v = e.getValue();
            String t;
            if (v instanceof Boolean) t = "B";
            else if (v instanceof Integer) t = "I";
            else if (v instanceof Long) t = "L";
            else if (v instanceof Float) t = "F";
            else if (v instanceof String) t = "S";
            else continue;
            sb.append(esc(e.getKey())).append('\t').append(t).append('\t').append(esc(String.valueOf(v)))
                    .append('\n');
        }
        return sb.toString().getBytes("UTF-8");
    }

    /** Contenu actuel de l'application dans un fichier .zip. */
    public static byte[] build(Context c) throws Exception {
        final Context app = c.getApplicationContext();
        ScanService.ensureLoaded(app);
        Meters.loadClosed(new File(app.getFilesDir(), "closed.tsv"));
        Meters.loadTodo(new File(app.getFilesDir(), "todo.tsv"));
        File tmp = new File(app.getCacheDir(), "bk_part.tmp");
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ZipOutputStream z = new ZipOutputStream(bos);
        try {
            String info = MAGIC + "\n"
                    + "date=" + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.FRANCE)
                    .format(new java.util.Date()) + "\n"
                    + "compteurs=" + Meters.ROWS.size() + "\n"
                    + "lectures=" + Meters.CLOSED.size() + "\n"
                    + "a_realiser=" + Meters.TODO.size() + "\n";
            put(z, "info.txt", info.getBytes("UTF-8"));
            Meters.save(tmp);
            put(z, "table.tsv", readFile(tmp));
            Meters.saveClosed(tmp);
            put(z, "closed.tsv", readFile(tmp));
            Meters.saveTodo(tmp);
            put(z, "todo.tsv", readFile(tmp));
            put(z, "order.tsv", readFile(new File(app.getFilesDir(), "order.tsv")));
            put(z, "cfg.tsv", prefsBytes(app));
            File vm = VoiceStore.file(app);
            if (vm.isFile() && vm.length() > 0) put(z, "voice_model.bin", readFile(vm));
            z.finish();
        } finally {
            z.close();
            tmp.delete();
        }
        return bos.toByteArray();
    }

    private static void writeAtomic(File dest, byte[] data) throws Exception {
        File tmp = new File(dest.getPath() + ".rst");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(data);
            out.flush();
        }
        if (!tmp.renameTo(dest)) {
            dest.delete();
            if (!tmp.renameTo(dest)) throw new Exception("ecriture impossible: " + dest.getName());
        }
    }

    /** Restaure une sauvegarde. Retourne un resume. Une copie de l'etat actuel est gardee avant. */
    public static String restore(Context c, InputStream in) throws Exception {
        final Context app = c.getApplicationContext();
        HashMap<String, byte[]> parts = new HashMap<>();
        try (ZipInputStream zin = new ZipInputStream(in)) {
            ZipEntry e;
            int count = 0;
            while ((e = zin.getNextEntry()) != null) {
                if (++count > MAX_ENTRIES) throw new Exception("Fichier de sauvegarde invalide");
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[65536];
                long total = 0;
                int n;
                while ((n = zin.read(buf)) > 0) {
                    total += n;
                    if (total > MAX_ENTRY) throw new Exception("Fichier de sauvegarde trop gros");
                    bos.write(buf, 0, n);
                }
                parts.put(e.getName(), bos.toByteArray());
            }
        }
        byte[] info = parts.get("info.txt");
        if (info == null || parts.get("table.tsv") == null
                || !new String(info, "UTF-8").startsWith(MAGIC)) {
            throw new Exception("Ce fichier n'est pas une sauvegarde de Releve Clavier");
        }
        // copie de securite de l'etat actuel
        try {
            writeAtomic(new File(app.getFilesDir(), "avant-restauration.zip"), build(app));
        } catch (Throwable ignored) {
        }
        File dir = app.getFilesDir();
        writeAtomic(new File(dir, "table.tsv"), parts.get("table.tsv"));
        if (parts.get("closed.tsv") != null) writeAtomic(new File(dir, "closed.tsv"), parts.get("closed.tsv"));
        if (parts.get("todo.tsv") != null) writeAtomic(new File(dir, "todo.tsv"), parts.get("todo.tsv"));
        byte[] order = parts.get("order.tsv");
        if (order != null && order.length > 0) writeAtomic(new File(dir, "order.tsv"), order);
        byte[] voice = parts.get("voice_model.bin");
        if (voice != null && voice.length > 0) {
            writeAtomic(VoiceStore.file(app), voice);
            VoiceStore.reset();
        }
        // reglages
        byte[] cfg = parts.get("cfg.tsv");
        int nPrefs = 0;
        if (cfg != null) {
            SharedPreferences.Editor ed = app.getSharedPreferences("cfg", Context.MODE_PRIVATE).edit();
            for (String line : new String(cfg, "UTF-8").split("\n")) {
                String[] p = line.split("\t", -1);
                if (p.length < 3) continue;
                String k = unesc(p[0]);
                String v = unesc(p[2]);
                try {
                    if (p[1].equals("B")) ed.putBoolean(k, Boolean.parseBoolean(v));
                    else if (p[1].equals("I")) ed.putInt(k, Integer.parseInt(v));
                    else if (p[1].equals("L")) ed.putLong(k, Long.parseLong(v));
                    else if (p[1].equals("F")) ed.putFloat(k, Float.parseFloat(v));
                    else if (p[1].equals("S")) ed.putString(k, v);
                    else continue;
                    nPrefs++;
                } catch (NumberFormatException ignored) {
                }
            }
            ed.commit();
        }
        // memoire
        Meters.CLOSED.clear();
        Meters.TODO.clear();
        Meters.load(new File(dir, "table.tsv"));
        Meters.loadClosed(new File(dir, "closed.tsv"));
        Meters.loadTodo(new File(dir, "todo.tsv"));
        ScanService.reloadAfterRestore(app);
        return Meters.ROWS.size() + " compteurs, " + Meters.CLOSED.size() + " lectures de l'application, "
                + Meters.TODO.size() + " interventions à réaliser, " + nPrefs + " réglages"
                + (voice != null && voice.length > 0 ? ", modèle vocal" : "");
    }
}
