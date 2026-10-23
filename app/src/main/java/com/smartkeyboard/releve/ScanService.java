    // ---------- Bandeau au-dessus du clavier (compteur + ancien index) ----------

    private boolean stripPending = false;
    private final Runnable stripUpdate = new Runnable() {
        @Override
        public void run() {
            stripPending = false;
            KeyboardService k = KeyboardService.instance;
            if (k == null || !KeyboardService.stripOn) return;
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null || !APP.equals(String.valueOf(root.getPackageName()))) return;
            if (findId(root, "modelInputIdx", 0) == null) return;
            ensureLoaded(ScanService.this);
            String label = meterLabel(root);
            int i = label.indexOf(':');
            String shown = i >= 0 ? label.substring(i + 1).trim() : label;
            String id = readMeterId(root);
            Meters.Row r = id.isEmpty() ? null : Meters.find(id);
            String txt;
            if (r != null) {
                String name = shown.isEmpty() ? r.compteur : shown;
                txt = name + "   |   Ancien : " + (r.oldIdx.isEmpty() ? "-" : r.oldIdx);
            } else if (!shown.isEmpty()) {
                txt = shown + "   |   (absent du tableau)";
            } else {
                txt = "";
            }
            k.setStrip(txt);
        }
    };

    public void refreshStrip() {
        if (stripPending) return;
        stripPending = true;
        handler.postDelayed(stripUpdate, 120);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        CharSequence p = event.getPackageName();
        if (p == null || !APP.equals(p.toString())) return;
        if (KeyboardService.stripOn && !stripPending) {
            stripPending = true;
            handler.postDelayed(stripUpdate, 300);
        }
        if (!pending) {
