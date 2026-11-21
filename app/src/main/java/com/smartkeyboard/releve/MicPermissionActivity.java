package com.smartkeyboard.releve;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Bundle;

/** Fenetre transparente: demande l'autorisation du micro puis se ferme (depuis la touche micro du clavier). */
public class MicPermissionActivity extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        try {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                finish();
                return;
            }
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 11);
        } catch (Throwable t) {
            finish();
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        finish();
    }
}
