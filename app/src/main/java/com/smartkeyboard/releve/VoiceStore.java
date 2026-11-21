package com.smartkeyboard.releve;

import android.content.Context;

import java.io.File;

/** Acces unique au modele vocal appris (fichier prive de l'application, aucun reseau). */
public final class VoiceStore {
    private static VoiceModel model;

    private VoiceStore() {
    }

    public static synchronized VoiceModel get(Context c) {
        if (model == null) {
            model = new VoiceModel();
            try {
                model.load(file(c));
            } catch (Throwable t) {
                model.clear();
            }
        }
        return model;
    }

    /** Apres une restauration: oublie le modele en memoire, il sera relu depuis le fichier. */
    public static synchronized void reset() {
        model = null;
    }

    public static File file(Context c) {
        return new File(c.getFilesDir(), "voice_model.bin");
    }

    public static synchronized void save(Context c) {
        try {
            if (model != null) model.save(file(c));
        } catch (Throwable t) {
            // l'enregistrement du modele ne doit jamais faire planter l'application
        }
    }
}
