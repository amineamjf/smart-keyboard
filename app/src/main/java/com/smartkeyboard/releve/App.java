package com.smartkeyboard.releve;

import android.app.Application;
import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Enregistre la cause d'un plantage dans un fichier, pour pouvoir la lire au prochain lancement. */
public class App extends Application {

    public static File crashFile(android.content.Context c) {
        return new File(c.getFilesDir(), "crash.txt");
    }

    @Override
    public void onCreate() {
        super.onCreate();
        final Thread.UncaughtExceptionHandler old = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                StringWriter sw = new StringWriter();
                e.printStackTrace(new PrintWriter(sw));
                String s = new SimpleDateFormat("dd/MM HH:mm:ss", Locale.FRANCE).format(new Date())
                        + " thread=" + t.getName() + "\n" + sw;
                if (s.length() > 6000) s = s.substring(0, 6000);
                FileOutputStream fo = new FileOutputStream(crashFile(this));
                fo.write(s.getBytes("UTF-8"));
                fo.close();
            } catch (Throwable ignored) {
            }
            if (old != null) old.uncaughtException(t, e);
        });
    }
}
