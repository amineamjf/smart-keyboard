package com.smartkeyboard.releve;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Rapport Excel identique a celui de SmartRV (V406): feuille "Analyse" (tableau de bord: tuiles, anneau, barres,
 * classement, avancement par tournee) puis feuille "Releve" (19 colonnes SmartRV + "Ordre de lecture" + "Niveau"),
 * suivies des index des derniers mois. Memes couleurs, memes largeurs, memes graphiques.
 */
final class SmartRvReport {
    private static final String NAVY = "1F3864", BLUE = "2F5597", BORD = "B4C6E7";

    /** Niveaux: nom, couleur de l'anneau, texte, fond de cellule (comme SmartRV). */
    private static final String[][] LV = {
            {"Très élevée", "FFD966", "7F6000", "FFE699"}, {"Élevée", "FFF2CC", "9C6500", "FFF2CC"},
            {"Normale", "A9D18E", "375623", "E2EFDA"}, {"Faible", "F4C7A8", "C55A11", "FCE4D6"},
            {"Nul", "F2A57A", "C55A11", "F8CBAD"}, {"Négatif", "E8908A", "9C0006", "F4B6B0"},
            {"Anomalie", "B4A7D6", "5B3F8C", "E4DFEC"}, {"Inhabité", "BFBFBF", "404040", "EDEDED"},
            {"Non relevé", "D9D9D9", "7F7F7F", "F2F2F2"}};

    private static String[] lv(String name) {
        for (String[] l : LV) if (l[0].equals(name)) return l;
        return LV[8];
    }

    // ---------------------------------------------------------------- styles
    private final ArrayList<String> fonts = new ArrayList<>();
    private final ArrayList<String> fills = new ArrayList<>();
    private final ArrayList<String> borders = new ArrayList<>();
    private final ArrayList<String> xfs = new ArrayList<>();
    private final HashMap<String, Integer> xfIdx = new HashMap<>();

    SmartRvReport() {
        fonts.add("<font><sz val=\"10\"/><name val=\"Calibri\"/></font>");
        fills.add("<fill><patternFill patternType=\"none\"/></fill>");
        fills.add("<fill><patternFill patternType=\"gray125\"/></fill>");
        borders.add("<border><left/><right/><top/><bottom/><diagonal/></border>");
        xfs.add("<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>");
    }

    private static int idx(ArrayList<String> l, String x) {
        int i = l.indexOf(x);
        if (i >= 0) return i;
        l.add(x);
        return l.size() - 1;
    }

    /** Style de cellule. fill/border/fmt peuvent etre null. h: left|center. */
    int st(String fill, boolean bold, boolean italic, boolean ul, int sz, String color, String h,
                   boolean wrap, String border, String fmt) {
        String key = fill + "|" + bold + "|" + italic + "|" + ul + "|" + sz + "|" + color + "|" + h + "|" + wrap + "|"
                + border + "|" + fmt;
        Integer c = xfIdx.get(key);
        if (c != null) return c;
        int f = idx(fonts, "<font>" + (bold ? "<b/>" : "") + (italic ? "<i/>" : "") + (ul ? "<u/>" : "")
                + "<sz val=\"" + sz + "\"/><color rgb=\"FF" + (color == null ? "000000" : color)
                + "\"/><name val=\"Calibri\"/></font>");
        int fi = fill == null ? 0 : idx(fills, "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FF" + fill
                + "\"/><bgColor indexed=\"64\"/></patternFill></fill>");
        int bi = border == null ? 0 : idx(borders, "<border><left style=\"thin\"><color rgb=\"FF" + border
                + "\"/></left><right style=\"thin\"><color rgb=\"FF" + border + "\"/></right><top style=\"thin\"><color rgb=\"FF"
                + border + "\"/></top><bottom style=\"thin\"><color rgb=\"FF" + border
                + "\"/></bottom><diagonal/></border>");
        int nf = 0;
        if ("#,##0".equals(fmt)) nf = 3;
        else if ("0%".equals(fmt)) nf = 9;
        else if ("#,##0.##".equals(fmt)) nf = 164;
        xfs.add("<xf numFmtId=\"" + nf + "\" fontId=\"" + f + "\" fillId=\"" + fi + "\" borderId=\"" + bi
                + "\" xfId=\"0\" applyNumberFormat=\"1\" applyFont=\"1\" applyFill=\"1\" applyBorder=\"1\" applyAlignment=\"1\">"
                + "<alignment horizontal=\"" + (h == null ? "general" : h) + "\" vertical=\"center\""
                + (wrap ? " wrapText=\"1\"" : "") + "/></xf>");
        xfIdx.put(key, xfs.size() - 1);
        return xfs.size() - 1;
    }

    String stylesXml() {
        StringBuilder b = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
                + "<numFmts count=\"1\"><numFmt numFmtId=\"164\" formatCode=\"#,##0.##\"/></numFmts>");
        b.append("<fonts count=\"").append(fonts.size()).append("\">");
        for (String f : fonts) b.append(f);
        b.append("</fonts><fills count=\"").append(fills.size()).append("\">");
        for (String f : fills) b.append(f);
        b.append("</fills><borders count=\"").append(borders.size()).append("\">");
        for (String f : borders) b.append(f);
        b.append("</borders><cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>");
        b.append("<cellXfs count=\"").append(xfs.size()).append("\">");
        for (String x : xfs) b.append(x);
        b.append("</cellXfs><cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles></styleSheet>");
        return b.toString();
    }

    // ---------------------------------------------------------------- feuille
    static final class Sheet {
        final TreeMap<Integer, TreeMap<Integer, Object[]>> cells = new TreeMap<>();
        final ArrayList<int[]> merges = new ArrayList<>();
        final HashMap<Integer, Double> rowH = new HashMap<>();
        double[] cols = new double[0];

        void put(int r, int c, Object v, int style) {
            TreeMap<Integer, Object[]> row = cells.get(r);
            if (row == null) {
                row = new TreeMap<>();
                cells.put(r, row);
            }
            row.put(c, new Object[]{v, style});
        }

        void span(int r, int c1, int c2, Object v, int style) {
            put(r, c1, v, style);
            for (int c = c1 + 1; c <= c2; c++) put(r, c, "", style);
            if (c2 > c1) merges.add(new int[]{r, c1, r, c2});
        }

        int maxCol() {
            int m = 0;
            for (TreeMap<Integer, Object[]> r : cells.values()) m = Math.max(m, r.lastKey());
            return m;
        }
    }

    static String col(int i) {
        StringBuilder s = new StringBuilder();
        i++;
        while (i > 0) {
            int m = (i - 1) % 26;
            s.insert(0, (char) ('A' + m));
            i = (i - 1) / 26;
        }
        return s.toString();
    }

    static String esc(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '&') b.append("&amp;");
            else if (ch == '<') b.append("&lt;");
            else if (ch == '>') b.append("&gt;");
            else if (ch == '"') b.append("&quot;");
            else if (ch < 0x20 && ch != '\n' && ch != '\t' && ch != '\r') continue;
            else b.append(ch);
        }
        return b.toString();
    }

    private static void writeCells(StringBuilder sb, Sheet sh, int r) {
        TreeMap<Integer, Object[]> row = sh.cells.get(r);
        if (row == null) return;
        for (java.util.Map.Entry<Integer, Object[]> e : row.entrySet()) {
            String ref = col(e.getKey()) + (r + 1);
            Object v = e.getValue()[0];
            int s = (Integer) e.getValue()[1];
            if (v instanceof Number) {
                double d = ((Number) v).doubleValue();
                String t = d == Math.rint(d) && Math.abs(d) < 1e15 ? String.valueOf((long) d) : String.valueOf(d);
                sb.append("<c r=\"").append(ref).append("\" s=\"").append(s).append("\"><v>").append(t).append("</v></c>");
            } else if (v instanceof Object[]) { // lien: {url, libelle}
                Object[] l = (Object[]) v;
                sb.append("<c r=\"").append(ref).append("\" s=\"").append(s).append("\" t=\"str\"><f>HYPERLINK(\"")
                        .append(esc((String) l[0])).append("\",\"").append(esc((String) l[1])).append("\")</f><v>")
                        .append(esc((String) l[1])).append("</v></c>");
            } else {
                String t = v == null ? "" : String.valueOf(v);
                if (t.isEmpty()) sb.append("<c r=\"").append(ref).append("\" s=\"").append(s).append("\"/>");
                else sb.append("<c r=\"").append(ref).append("\" s=\"").append(s)
                        .append("\" t=\"inlineStr\"><is><t xml:space=\"preserve\">").append(esc(t)).append("</t></is></c>");
            }
        }
    }

    static String sheetXml(Sheet sh, String viewXml, String tail, int lastRow, int lastCol) {
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" "
                + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">");
        sb.append("<sheetPr><pageSetUpPr fitToPage=\"1\"/></sheetPr>");
        sb.append("<dimension ref=\"A1:").append(col(lastCol)).append(lastRow + 1).append("\"/>");
        sb.append(viewXml);
        sb.append("<sheetFormatPr defaultRowHeight=\"15\"/><cols>");
        for (int i = 0; i < sh.cols.length; i++) {
            sb.append("<col min=\"").append(i + 1).append("\" max=\"").append(i + 1).append("\" width=\"")
                    .append(sh.cols[i] + 0.71).append("\" customWidth=\"1\"/>");
        }
        sb.append("</cols><sheetData>");
        for (int r = 0; r <= lastRow; r++) {
            Double h = sh.rowH.get(r);
            boolean has = sh.cells.containsKey(r);
            if (!has && h == null) continue;
            sb.append("<row r=\"").append(r + 1).append("\"");
            if (h != null) sb.append(" ht=\"").append(h).append("\" customHeight=\"1\"");
            sb.append(">");
            writeCells(sb, sh, r);
            sb.append("</row>");
        }
        sb.append("</sheetData>");
        sb.append(tail);
        return sb.toString();
    }

    // ---------------------------------------------------------------- donnees
    private static boolean isNum(String s) {
        return s != null && s.matches("-?\\d{1,15}");
    }

    private static final class Info {
        Long cur, prv, conso;
        String level, etat, type;
        boolean inhab, reordered, added;
        Meters.Row r;
    }

    private static String fmt1(double x) {
        return String.valueOf(Math.round(x * 10) / 10.0).replace(".0", "").replace('.', ',');
    }

    private static String levelOf(Info in, long faibleMax) {
        Meters.Row r = in.r;
        boolean hasAno = !Meters.noAnomaly(r.anomaly);
        if (hasAno && !in.inhab) return "Anomalie";
        if (in.inhab) return "Inhabité";
        if (in.conso == null) return "Non relevé";
        long c = in.conso;
        if (c < 0) return "Négatif";
        if (c == 0) return "Nul";
        if ("ELEC".equals(in.type)) return c > 200 ? (c > 400 ? "Très élevée" : "Élevée") : "Normale";
        if (Smart.high(r, c)) {
            long thr = Smart.limit(r) + 1;
            if (thr <= 0 || thr > Long.MAX_VALUE / 4) thr = Long.MAX_VALUE / 4;
            return c >= 2 * thr ? "Très élevée" : "Élevée";
        }
        if (faibleMax > 0 && c <= faibleMax) return "Faible";
        return "Normale";
    }

    private static String why(Info in) {
        Meters.Row r = in.r;
        if ("Négatif".equals(in.level)) return "Nouvel index inférieur à l’ancien index.";
        if ("Anomalie".equals(in.level)) return r.anomaly == null ? "" : r.anomaly.replace('_', ' ');
        if ("ELEC".equals(in.type)) return "Au-dessus du seuil (200 kWh).";
        java.util.ArrayList<Double> rates = Smart.on ? Smart.rates(r) : null;
        long c = in.conso == null ? 0 : in.conso;
        if (rates == null || (ScanService.maxJump > 0 && c > ScanService.maxJump
                && c <= Smart.limit(r) + (ScanService.maxJump <= Smart.limit(r) ? 1 : 0) && ScanService.maxJump <= Smart.limit(r))) {
            return ScanService.maxJump > 0 ? "Au-dessus du seuil fixe (" + ScanService.maxJump + " m³)." : "";
        }
        double base = Smart.usual(r);
        StringBuilder t = new StringBuilder("Habituellement ~ " + fmt1(base) + " m³/mois (derniers mois : ");
        for (int i = 0; i < rates.size(); i++) t.append(i > 0 ? ", " : "").append(fmt1(rates.get(i)));
        t.append(")");
        if (base > 0) t.append(" — ce relevé = ×").append(fmt1(c / base));
        return t.append(".").toString();
    }

    // ---------------------------------------------------------------- construction
    static byte[] build() throws Exception {
        return new SmartRvReport().make();
    }

    private static final String[] HEAD = {"N°", "Type", "N° Client / Contrat", "N° Compteur", "Tournée", "Client",
            "Adresse", "Ancien Index", "Nouvel Index", "Consommation", "État", "Anomalie", "Annotation",
            "Date/Heure Relevé", "GPS", "Photo", "Ajouté", "Réordonné", "Saisi par", "Ordre de lecture", "Niveau"};
    private static final double[] WCH = {6, 10, 22, 20, 14, 30, 44, 15, 15, 17, 26, 30, 28, 21, 26, 20, 12, 16, 14, 15, 16};

    private static String headColor(String title) {
        switch (title) {
            case "N°": case "Type": case "N° Client / Contrat": case "N° Compteur": case "Tournée": case "Client":
            case "Adresse": case "Téléphone":
                return NAVY;
            case "Ancien Index": case "Nouvel Index": case "Consommation":
                return BLUE;
            case "État": case "Anomalie": case "Annotation": case "Niveau":
                return "C55A11";
            case "Date/Heure Relevé": case "GPS": case "Photo": case "Ajouté": case "Réordonné": case "Saisi par":
            case "Ordre de lecture":
                return "0F766E";
            default:
                return "595959";
        }
    }

    private byte[] make() throws Exception {
        List<Meters.Row> rows = new ArrayList<>(Meters.ROWS);
        int n = rows.size();
        Info[] info = new Info[n];
        ArrayList<Long> eau = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Meters.Row r = rows.get(i);
            Info in = new Info();
            in.r = r;
            in.cur = isNum(r.newIdx) ? Long.valueOf(r.newIdx) : null;
            in.prv = isNum(r.oldIdx) ? Long.valueOf(r.oldIdx) : null;
            in.conso = in.cur != null && in.prv != null ? Long.valueOf(in.cur - in.prv) : null;
            String typ = Meters.colValue(r, "TYPE");
            in.type = typ.isEmpty() ? "EAU" : typ.toUpperCase(Locale.ROOT);
            in.inhab = !Meters.noAnomaly(r.anomaly) && Meters.norm(r.anomaly).contains("INHABIT");
            String reo = Meters.norm(Meters.colValue(r, "REORDONNE*"));
            in.reordered = reo.equals("OUI") || reo.contains("REORDONN");
            String aj = Meters.norm(Meters.colValue(r, "AJOUTE*"));
            in.added = r.status == Meters.UNLISTED || r.status == Meters.EXTRA || aj.equals("OUI") || aj.contains("AJOUT");
            if (in.conso != null && in.conso > 0 && !"ELEC".equals(in.type)) eau.add(in.conso);
            info[i] = in;
        }
        Collections.sort(eau);
        double med = eau.isEmpty() ? 0 : (eau.size() % 2 == 1 ? eau.get((eau.size() - 1) / 2)
                : (eau.get(eau.size() / 2 - 1) + eau.get(eau.size() / 2)) / 2.0);
        long faibleMax = med >= 2 ? (long) Math.floor(med / 2) : 0;
        LinkedHashMap<String, Integer> cnt = new LinkedHashMap<>();
        for (String[] l : LV) cnt.put(l[0], 0);
        for (Info in : info) {
            in.level = levelOf(in, faibleMax);
            cnt.put(in.level, cnt.get(in.level) + 1);
            String base;
            if (!Meters.noAnomaly(in.r.anomaly)) base = "ANOMALIE";
            else if (in.conso == null) base = "NON RELEVÉ";
            else if (in.conso < 0) base = "CONSOMMATION NÉGATIVE";
            else if ("Élevée".equals(in.level) || "Très élevée".equals(in.level)) base = "CONSOMMATION ÉLEVÉE";
            else base = "NORMAL";
            in.etat = base;
        }

        // colonnes d'origine du fichier importe qui ne font pas partie du modele SmartRV (Hist <mois>, telephone...):
        // reprises telles quelles a la suite, rien de l'original n'est perdu
        java.util.HashSet<String> known = new java.util.HashSet<>();
        for (String h : HEAD) known.add(Meters.norm(h));
        known.add(Meters.norm("Statut"));
        ArrayList<Integer> histCols = new ArrayList<>();
        ArrayList<String> histNames = new ArrayList<>();
        String[] hh = Meters.HEADS;
        if (hh != null) {
            java.util.HashSet<String> seen = new java.util.HashSet<>();
            for (int i = 0; i < hh.length; i++) {
                String nm = hh[i] == null ? "" : hh[i].trim();
                String kn = Meters.norm(nm);
                if (nm.isEmpty() || known.contains(kn) || !seen.add(kn)) continue;
                histCols.add(i);
                histNames.add(nm);
            }
        }
        int nHist = histCols.size();
        int totalCols = HEAD.length + nHist;

        // ================= feuille "Relevé"
        Sheet rv = new Sheet();
        double[] cw = new double[totalCols];
        for (int i = 0; i < totalCols; i++) cw[i] = i < WCH.length ? WCH[i] : 14;
        rv.cols = cw;
        rv.rowH.put(0, 32.0);
        String[] titles = new String[totalCols];
        for (int c = 0; c < totalCols; c++) titles[c] = c < HEAD.length ? HEAD[c] : histNames.get(c - HEAD.length);
        for (int c = 0; c < totalCols; c++) {
            rv.put(0, c, titles[c], st(headColor(titles[c]), true, false, false, 10, "FFFFFF", "center", true, BORD, null));
        }
        for (int i = 0; i < n; i++) {
            Info in = info[i];
            Meters.Row r = in.r;
            int rr = i + 1;
            String zebra = (i % 2 == 0) ? "FFFFFF" : "F5F8FC";
            String[] L = lv(in.level);
            String address = Meters.addressOf(r);
            String gps = Meters.colValue(r, "GPS*");
            String gpsTxt = gps.startsWith("📍") ? gps.substring(2).trim() : gps;
            boolean hasPhoto = !r.photos.isEmpty();
            String anomaly = in.inhab ? "RAS" : (Meters.noAnomaly(r.anomaly) ? "RAS" : r.anomaly);
            String annot = in.inhab ? "03_LOCAL INHABITIE" : (r.annotation.isEmpty() ? "Aucune" : r.annotation);
            String etatTxt = in.inhab ? "LOCAL INHABITÉ" : in.etat;
            String[] EL = lv(in.inhab ? "Inhabité" : etatLevel(in.etat));
            String dateTxt = Meters.colValue(r, "DATE HEURE*");
            String saisi = Meters.colValue(r, "SAISI*");
            Object[] vals = new Object[totalCols];
            vals[0] = (long) (i + 1);
            vals[1] = in.type;
            vals[2] = r.contrat;
            vals[3] = r.compteur;
            vals[4] = Meters.tourneeOf(r);
            vals[5] = r.client;
            vals[6] = address;
            vals[7] = in.prv != null ? (Object) in.prv : r.oldIdx;
            vals[8] = in.cur != null ? (Object) in.cur : r.newIdx;
            vals[9] = in.conso != null ? (Object) in.conso : "";
            vals[10] = etatTxt;
            vals[11] = anomaly;
            vals[12] = annot;
            vals[13] = (r.readAt != null && !r.readAt.isEmpty() && !"?".equals(r.readAt)) ? r.readAt : dateTxt;
            vals[14] = gps.isEmpty() ? "" : (gpsTxt.split(",").length == 2
                    ? (Object) new Object[]{"https://www.google.com/maps?q=" + gpsTxt.split(",")[0].trim() + ","
                    + gpsTxt.split(",")[1].trim(), "📍 " + gpsTxt} : gps);
            vals[15] = hasPhoto ? "📷 " + r.photos : "";
            vals[16] = in.added ? "🆕 AJOUTÉ" : "Non";
            vals[17] = in.reordered ? "🔀 RÉORDONNÉ" : "Non";
            vals[18] = saisi;
            vals[19] = "";
            vals[20] = in.level;
            for (int h = 0; h < nHist; h++) {
                String hv = histCols.get(h) < r.cols.length && r.cols[histCols.get(h)] != null
                        ? r.cols[histCols.get(h)].trim() : "";
                vals[HEAD.length + h] = isNum(hv) ? (Object) Long.valueOf(hv) : hv;
            }
            for (int c = 0; c < totalCols; c++) {
                String t = c < HEAD.length ? HEAD[c] : "";
                boolean left = t.equals("Client") || t.equals("Adresse") || t.equals("Tournée");
                int s;
                if (in.reordered && !t.equals("État") && !t.equals("Niveau") && !t.equals("Consommation")) {
                    boolean leftB = c == 5 || c == 6 || c == 11 || c == 12;
                    boolean reoCell = c == 17;
                    s = st(reoCell ? "EA580C" : "FED7AA", c == 10 || c == 17, false, false, 10,
                            reoCell ? "FFFFFF" : "7C2D12", leftB ? "left" : "center", true, "B7C3D0", null);
                } else {
                    String fill = zebra, color = "1F2937", fmt = null;
                    boolean bold = false, ul = false;
                    if (t.equals("Consommation") || t.equals("Niveau")) {
                        fill = L[3];
                        color = L[2];
                        bold = true;
                        if (t.equals("Consommation")) fmt = "#,##0.##";
                    }
                    if (t.equals("État")) {
                        fill = EL[3];
                        color = EL[2];
                        bold = true;
                    }
                    if (t.equals("Ancien Index") || t.equals("Nouvel Index")) {
                        fmt = "#,##0";
                        bold = t.equals("Nouvel Index");
                    }
                    if (t.equals("N° Compteur") || t.equals("N° Client / Contrat")) {
                        bold = true;
                        color = NAVY;
                    }
                    if (t.equals("Anomalie") && !"RAS".equals(anomaly)) {
                        fill = "E4DFEC";
                        bold = true;
                        color = "5B3F8C";
                    }
                    if (t.equals("Annotation") && annot.toUpperCase(Locale.ROOT).contains("INHABIT")) {
                        fill = "EDEDED";
                        bold = true;
                        color = "404040";
                    }
                    if ((t.equals("GPS") || t.equals("Photo")) && vals[c] instanceof Object[]) {
                        bold = true;
                        ul = true;
                        color = "0563C1";
                    }
                    if (t.equals("Photo") && hasPhoto) {
                        bold = true;
                        ul = true;
                        color = "0563C1";
                    }
                    s = st(fill, bold, false, ul, 10, color, left ? "left" : "center", left, BORD, fmt);
                }
                rv.put(rr, c, vals[c], s);
            }
            rv.rowH.put(rr, hasPhoto ? 62.0 : 22.0);
        }
        int lastRowRv = Math.max(0, n);

        // ================= feuille "Analyse"
        Sheet an = new Sheet();
        an.cols = new double[]{2, 22, 12, 12, 12, 12, 12, 3, 22, 18, 12, 20, 58};
        int TH = st(BLUE, true, false, false, 11, "FFFFFF", "center", false, BLUE, null);
        an.span(0, 1, 12, "TABLEAU DE BORD — ANALYSE DE CONSOMMATION",
                st(NAVY, true, false, false, 18, "FFFFFF", "center", false, null, null));
        an.rowH.put(0, 32.0);
        int lus = 0, nPhoto = 0;
        for (Info in : info) {
            if (in.cur != null) lus++;
            if (!in.r.photos.isEmpty()) nPhoto++;
        }
        long eauSum = 0;
        for (long v : eau) eauSum += v;
        double eauMax = eau.isEmpty() ? 0 : eau.get(eau.size() - 1);
        Object[][] t1 = {{"Compteurs suivis", (double) n, NAVY, 1, 2}, {"Très élevées", (double) cnt.get("Très élevée"), "BF9000", 3, 4},
                {"Élevées", (double) cnt.get("Élevée"), "D4A72C", 5, 6}, {"Normales", (double) cnt.get("Normale"), "548235", 8, 9},
                {"Faibles / nulles", (double) (cnt.get("Faible") + cnt.get("Nul")), "C55A11", 10, 12}};
        Object[][] t2 = {{"Négatifs", (double) cnt.get("Négatif"), "C00000", 1, 2}, {"Anomalies", (double) cnt.get("Anomalie"), "7030A0", 3, 4},
                {"Inhabités (03)", (double) cnt.get("Inhabité"), "595959", 5, 6}, {"Non relevés", (double) cnt.get("Non relevé"), "7F7F7F", 8, 9},
                {"Avancement", n > 0 ? (double) lus / n : 0.0, BLUE, 10, 12}};
        tiles(an, 2, t1, false);
        tiles(an, 5, t2, true);
        an.rowH.put(2, 18.0);
        an.rowH.put(3, 38.0);
        an.rowH.put(5, 18.0);
        an.rowH.put(6, 38.0);
        java.text.SimpleDateFormat dfd = new java.text.SimpleDateFormat("dd/MM/yyyy", Locale.FRANCE);
        java.text.SimpleDateFormat dft = new java.text.SimpleDateFormat("HH:mm:ss", Locale.FRANCE);
        java.util.Date now = new java.util.Date();
        an.span(7, 1, 12, "Conso eau des relevés : " + String.format(Locale.FRANCE, "%,d", eauSum).replace(' ', ' ')
                + " m³   |   Médiane : " + fmt1(med) + " m³   |   Maximum : " + fmt1(eauMax) + " m³   |   Photos : "
                + nPhoto + "   |   Exporté le " + dfd.format(now) + " à " + dft.format(now),
                st(null, false, true, false, 9, NAVY, "left", false, null, null));
        int H = 9;
        an.put(H, 1, "Répartition par niveau", st(null, true, false, false, 12, NAVY, "left", false, null, null));
        an.put(H + 1, 1, "Niveau", TH);
        an.put(H + 1, 2, "Compteurs", TH);
        for (int k = 0; k < LV.length; k++) {
            String[] l = LV[k];
            an.put(H + 2 + k, 1, l[0], st(l[3], true, false, false, 11, l[2], "left", false, BORD, null));
            an.put(H + 2 + k, 2, (double) cnt.get(l[0]), st(null, true, false, false, 11, "000000", "center", false, BORD, "#,##0"));
        }
        int doughR1 = H + 3, doughR2 = H + 2 + LV.length;
        an.put(H, 8, "À surveiller — classement par gravité", st(null, true, false, false, 12, NAVY, "left", false, null, null));
        an.span(H + 1, 8, 9, "Client · N° compteur", TH);
        an.put(H + 1, 10, "m³", TH);
        an.put(H + 1, 11, "Niveau", TH);
        an.put(H + 1, 12, "Pourquoi", TH);
        final HashMap<String, Integer> grp = new HashMap<>();
        grp.put("Très élevée", 0);
        grp.put("Élevée", 1);
        grp.put("Négatif", 2);
        grp.put("Anomalie", 3);
        ArrayList<Integer> rk = new ArrayList<>();
        for (int k = 0; k < n; k++) if (grp.containsKey(info[k].level)) rk.add(k);
        final Info[] fi = info;
        Collections.sort(rk, (p, q) -> {
            int gp = grp.get(fi[p].level), gq = grp.get(fi[q].level);
            if (gp != gq) return gp - gq;
            long cp = fi[p].conso == null ? -1000000000L : fi[p].conso;
            long cq = fi[q].conso == null ? -1000000000L : fi[q].conso;
            return "Négatif".equals(fi[p].level) ? Long.compare(cp, cq) : Long.compare(cq, cp);
        });
        int LIM = 30, total = rk.size();
        List<Integer> top = rk.subList(0, Math.min(LIM, rk.size()));
        int r0 = H + 2;
        ArrayList<String> barCats = new ArrayList<>();
        ArrayList<Long> barVals = new ArrayList<>();
        for (int j = 0; j < top.size(); j++) {
            int k = top.get(j);
            Info in = info[k];
            String[] L = lv(in.level);
            int r = r0 + j;
            String nm = (in.r.client.isEmpty() ? "—" : in.r.client) + " · " + in.r.compteur;
            if (nm.length() > 42) nm = nm.substring(0, 41) + "…";
            an.span(r, 8, 9, nm, st(null, false, false, false, 10, "000000", null, false, BORD, null));
            int sx = st(L[3], true, false, false, 10, L[2], "center", false, BORD, in.conso == null ? null : "#,##0.##");
            if (in.conso == null) an.put(r, 10, "", sx);
            else an.put(r, 10, (double) in.conso, sx);
            an.put(r, 11, in.level, st(L[3], true, false, false, 10, L[2], "center", false, BORD, null));
            an.put(r, 12, why(in), st(null, false, false, false, 9, "595959", null, false, BORD, null));
            if (j < 15) {
                String cn = in.r.client.isEmpty() ? "—" : in.r.client;
                barCats.add((cn.length() > 24 ? cn.substring(0, 23) + "…" : cn) + " · " + in.r.compteur);
                barVals.add(in.conso);
            }
        }
        int rEnd = r0 + top.size();
        if (top.isEmpty()) {
            an.span(r0, 8, 12, "Rien à surveiller : aucune consommation anormale.",
                    st(null, false, false, false, 10, "166534", null, false, BORD, null));
            rEnd = r0 + 1;
        }
        int barN = Math.min(15, top.size());
        int barR1 = r0 + 1, barR2 = r0 + barN;
        String note = (total > LIM ? "+ " + (total - LIM) + " autre(s) cas : voir la feuille « Relevé ».  " : "")
                + "Très élevée = au moins 2× le seuil d’alerte. Seuil : "
                + (ScanService.maxJump > 0 ? "> " + ScanService.maxJump + " m³ ou " : "")
                + (Smart.on ? "≥ ×" + fmt1(Smart.factorX10 / 10.0) + " la conso habituelle du compteur (min. " + Smart.minRise + " m³)"
                : "seuil fixe seul") + ".";
        an.span(rEnd, 8, 12, note, st(null, false, true, false, 9, "595959", null, false, null, null));
        LinkedHashMap<String, int[]> byTour = new LinkedHashMap<>();
        for (Info in : info) {
            String t = Meters.tourneeOf(in.r).trim();
            if (t.isEmpty()) t = "(sans tournée)";
            int[] a = byTour.get(t);
            if (a == null) {
                a = new int[2];
                byTour.put(t, a);
            }
            a[0]++;
            if (in.cur != null) a[1]++;
        }
        ArrayList<java.util.Map.Entry<String, int[]>> tk = new ArrayList<>(byTour.entrySet());
        Collections.sort(tk, (p, q) -> q.getValue()[0] - p.getValue()[0]);
        int r1 = rEnd + 2;
        an.put(r1, 8, "Avancement par tournée", st(null, true, false, false, 12, NAVY, "left", false, null, null));
        an.span(r1 + 1, 8, 9, "Tournée", TH);
        an.put(r1 + 1, 10, "Relevés", TH);
        an.put(r1 + 1, 11, "Avancement", TH);
        for (int j = 0; j < Math.min(15, tk.size()); j++) {
            String t = tk.get(j).getKey();
            int[] o = tk.get(j).getValue();
            double pc = o[0] > 0 ? (double) o[1] / o[0] : 0;
            String[] cc = pc >= 0.8 ? new String[]{"E2EFDA", "375623"} : (pc >= 0.5 ? new String[]{"FFF2CC", "9C6500"}
                    : new String[]{"FCE4D6", "C55A11"});
            int r = r1 + 2 + j;
            an.span(r, 8, 9, t, st(null, false, false, false, 10, "000000", null, false, BORD, null));
            an.put(r, 10, o[1] + " / " + o[0], st(null, false, false, false, 10, "000000", "center", false, BORD, null));
            an.put(r, 11, pc, st(cc[0], true, false, false, 10, cc[1], "center", false, BORD, "0%"));
        }
        int last = Math.max(rEnd, Math.max(r1 + 1 + Math.min(15, tk.size()), H + 2 + LV.length));
        int doughRow0 = H + 13, barRow0 = H + 13 + 22;
        int lastRowAn = Math.max(last, barRow0 + 16);

        // ----- XML
        String anTail = "<mergeCells count=\"" + an.merges.size() + "\">" + mergeXml(an) + "</mergeCells>"
                + "<pageMargins left=\"0.7\" right=\"0.7\" top=\"0.75\" bottom=\"0.75\" header=\"0.3\" footer=\"0.3\"/>"
                + "<pageSetup orientation=\"landscape\" fitToWidth=\"1\" fitToHeight=\"0\"/>"
                + "<drawing r:id=\"rIdSrpDr\"/></worksheet>";
        String anXml = sheetXml(an, "<sheetViews><sheetView showGridLines=\"0\" tabSelected=\"1\" workbookViewId=\"0\"/></sheetViews>",
                anTail, lastRowAn, 12);
        String lastColRv = col(totalCols - 1);
        String rvTail = "<autoFilter ref=\"A1:" + lastColRv + (n + 1) + "\"/>"
                + "<pageMargins left=\"0.7\" right=\"0.7\" top=\"0.75\" bottom=\"0.75\" header=\"0.3\" footer=\"0.3\"/></worksheet>";
        String rvXml = sheetXml(rv, "<sheetViews><sheetView workbookViewId=\"0\"><pane ySplit=\"1\" topLeftCell=\"A2\" "
                + "activePane=\"bottomLeft\" state=\"frozen\"/><selection pane=\"bottomLeft\" activeCell=\"A2\" sqref=\"A2\"/>"
                + "</sheetView></sheetViews>", rvTail, lastRowRv, totalCols - 1);
        rvXml = rvXml.replace("<sheetPr><pageSetUpPr fitToPage=\"1\"/></sheetPr>", "");

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ZipOutputStream z = new ZipOutputStream(bos);
        put(z, "[Content_Types].xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                + "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>"
                + "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>"
                + "<Override PartName=\"/xl/worksheets/sheet2.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>"
                + "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>"
                + "<Override PartName=\"/xl/drawings/drawingSrp1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.drawing+xml\"/>"
                + "<Override PartName=\"/xl/charts/chartSrp1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.drawingml.chart+xml\"/>"
                + (barN > 0 ? "<Override PartName=\"/xl/charts/chartSrp2.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.drawingml.chart+xml\"/>" : "")
                + "</Types>");
        put(z, "_rels/.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>"
                + "</Relationships>");
        put(z, "xl/workbook.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">"
                + "<bookViews><workbookView activeTab=\"0\"/></bookViews><sheets>"
                + "<sheet name=\"Analyse\" sheetId=\"1\" r:id=\"rId1\"/><sheet name=\"Relevé\" sheetId=\"2\" r:id=\"rId2\"/></sheets>"
                + "<definedNames><definedName name=\"_xlnm._FilterDatabase\" localSheetId=\"1\" hidden=\"1\">'Relevé'!$A$1:$"
                + lastColRv + "$" + (n + 1) + "</definedName></definedNames></workbook>");
        put(z, "xl/_rels/workbook.xml.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/>"
                + "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet2.xml\"/>"
                + "<Relationship Id=\"rId3\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>"
                + "</Relationships>");
        put(z, "xl/worksheets/_rels/sheet1.xml.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rIdSrpDr\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/drawing\" Target=\"../drawings/drawingSrp1.xml\"/>"
                + "</Relationships>");
        put(z, "xl/styles.xml", stylesXml());
        put(z, "xl/worksheets/sheet1.xml", anXml);
        put(z, "xl/worksheets/sheet2.xml", rvXml);
        put(z, "xl/drawings/drawingSrp1.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<xdr:wsDr xmlns:xdr=\"http://schemas.openxmlformats.org/drawingml/2006/spreadsheetDrawing\" xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\">"
                + anchor(2, "rId1", doughRow0, 4320000, 3240000, "Anneau niveaux")
                + (barN > 0 ? anchor(3, "rId2", barRow0, 6840000, Math.max(2160000, 260000 * barN + 900000), "Barres écarts") : "")
                + "</xdr:wsDr>");
        put(z, "xl/drawings/_rels/drawingSrp1.xml.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/chart\" Target=\"../charts/chartSrp1.xml\"/>"
                + (barN > 0 ? "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/chart\" Target=\"../charts/chartSrp2.xml\"/>" : "")
                + "</Relationships>");
        ArrayList<Long> dv = new ArrayList<>();
        ArrayList<String> dc = new ArrayList<>();
        for (String[] l : LV) {
            dv.add((long) cnt.get(l[0]));
            dc.add(l[0]);
        }
        put(z, "xl/charts/chartSrp1.xml", doughnutXml(dc, dv, doughR1, doughR2));
        if (barN > 0) put(z, "xl/charts/chartSrp2.xml", barXml(barCats, barVals, barR1, barR2));
        z.finish();
        z.close();
        return bos.toByteArray();
    }

    private static String etatLevel(String etat) {
        switch (etat) {
            case "NORMAL": return "Normale";
            case "CONSOMMATION ÉLEVÉE": return "Élevée";
            case "CONSOMMATION NÉGATIVE": return "Négatif";
            case "ANOMALIE": return "Anomalie";
            default: return "Non relevé";
        }
    }

    private void tiles(Sheet an, int rowL, Object[][] T, boolean lastIsPct) {
        for (int k = 0; k < T.length; k++) {
            Object[] t = T[k];
            String color = (String) t[2];
            int c1 = (Integer) t[3], c2 = (Integer) t[4];
            an.span(rowL, c1, c2, t[0], st(color, true, false, false, 10, "FFFFFF", "center", false, null, null));
            boolean pct = lastIsPct && k == T.length - 1;
            an.span(rowL + 1, c1, c2, t[1], st("F2F2F2", true, false, false, 26, color, "center", false, null, pct ? "0%" : "#,##0"));
        }
    }

    static String mergeXml(Sheet sh) {
        StringBuilder b = new StringBuilder();
        for (int[] m : sh.merges) {
            b.append("<mergeCell ref=\"").append(col(m[1])).append(m[0] + 1).append(":").append(col(m[3])).append(m[2] + 1).append("\"/>");
        }
        return b.toString();
    }

    static void put(ZipOutputStream z, String name, String content) throws Exception {
        z.putNextEntry(new ZipEntry(name));
        z.write(content.getBytes(StandardCharsets.UTF_8));
        z.closeEntry();
    }

    // ---------------------------------------------------------------- graphiques
    static final String NS = "xmlns:c=\"http://schemas.openxmlformats.org/drawingml/2006/chart\" "
            + "xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" "
            + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"";
    static final String TAIL = "<c:plotVisOnly val=\"1\"/><c:dispBlanksAs val=\"gap\"/></c:chart><c:spPr><a:solidFill>"
            + "<a:srgbClr val=\"FFFFFF\"/></a:solidFill><a:ln><a:solidFill><a:srgbClr val=\"BFC8D8\"/></a:solidFill></a:ln></c:spPr></c:chartSpace>";

    static String strCache(List<String> a) {
        StringBuilder b = new StringBuilder("<c:strCache><c:ptCount val=\"" + a.size() + "\"/>");
        for (int i = 0; i < a.size(); i++) b.append("<c:pt idx=\"").append(i).append("\"><c:v>").append(esc(a.get(i))).append("</c:v></c:pt>");
        return b.append("</c:strCache>").toString();
    }

    static String numCache(List<Long> a, String f) {
        StringBuilder b = new StringBuilder("<c:numCache><c:formatCode>" + (f == null ? "General" : f) + "</c:formatCode><c:ptCount val=\""
                + a.size() + "\"/>");
        for (int i = 0; i < a.size(); i++) {
            if (a.get(i) != null) b.append("<c:pt idx=\"").append(i).append("\"><c:v>").append(a.get(i)).append("</c:v></c:pt>");
        }
        return b.append("</c:numCache>").toString();
    }

    static String title(String t) {
        return "<c:title><c:tx><c:rich><a:bodyPr/><a:lstStyle/><a:p><a:pPr><a:defRPr sz=\"1300\" b=\"1\"/></a:pPr><a:r>"
                + "<a:rPr lang=\"fr-FR\" sz=\"1300\" b=\"1\"/><a:t>" + esc(t) + "</a:t></a:r></a:p></c:rich></c:tx>"
                + "<c:overlay val=\"0\"/></c:title><c:autoTitleDeleted val=\"0\"/>";
    }

    private static String doughnutXml(List<String> cats, List<Long> vals, int r1, int r2) {
        String q = "'Analyse'!";
        StringBuilder dpt = new StringBuilder();
        for (int i = 0; i < LV.length; i++) {
            dpt.append("<c:dPt><c:idx val=\"").append(i).append("\"/><c:bubble3D val=\"0\"/><c:spPr><a:solidFill><a:srgbClr val=\"")
                    .append(LV[i][1]).append("\"/></a:solidFill><a:ln w=\"12700\"><a:solidFill><a:srgbClr val=\"FFFFFF\"/></a:solidFill></a:ln></c:spPr></c:dPt>");
        }
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><c:chartSpace " + NS + "><c:roundedCorners val=\"0\"/><c:chart>"
                + title("Répartition des niveaux")
                + "<c:plotArea><c:layout/><c:doughnutChart><c:varyColors val=\"1\"/><c:ser><c:idx val=\"0\"/><c:order val=\"0\"/><c:tx><c:strRef><c:f>"
                + q + "$C$" + (r1 - 1) + "</c:f>" + strCache(java.util.Collections.singletonList("Compteurs")) + "</c:strRef></c:tx>" + dpt
                + "<c:dLbls><c:numFmt formatCode=\"0;;;\" sourceLinked=\"0\"/><c:spPr><a:noFill/><a:ln><a:noFill/></a:ln></c:spPr>"
                + "<c:showLegendKey val=\"0\"/><c:showVal val=\"1\"/><c:showCatName val=\"0\"/><c:showSerName val=\"0\"/><c:showPercent val=\"0\"/>"
                + "<c:showBubbleSize val=\"0\"/><c:showLeaderLines val=\"0\"/></c:dLbls>"
                + "<c:cat><c:strRef><c:f>" + q + "$B$" + r1 + ":$B$" + r2 + "</c:f>" + strCache(cats) + "</c:strRef></c:cat>"
                + "<c:val><c:numRef><c:f>" + q + "$C$" + r1 + ":$C$" + r2 + "</c:f>" + numCache(vals, null) + "</c:numRef></c:val></c:ser>"
                + "<c:firstSliceAng val=\"0\"/><c:holeSize val=\"55\"/></c:doughnutChart></c:plotArea>"
                + "<c:legend><c:legendPos val=\"b\"/><c:overlay val=\"0\"/></c:legend>" + TAIL;
    }

    private static String barXml(List<String> cats, List<Long> vals, int r1, int r2) {
        String q = "'Analyse'!";
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><c:chartSpace " + NS + "><c:roundedCorners val=\"0\"/><c:chart>"
                + title("Principaux écarts de consommation (m³)")
                + "<c:plotArea><c:layout/><c:barChart><c:barDir val=\"bar\"/><c:grouping val=\"clustered\"/><c:varyColors val=\"0\"/>"
                + "<c:ser><c:idx val=\"0\"/><c:order val=\"0\"/><c:tx><c:strRef><c:f>" + q + "$K$" + (r1 - 1) + "</c:f>"
                + strCache(java.util.Collections.singletonList("m³")) + "</c:strRef></c:tx>"
                + "<c:spPr><a:solidFill><a:srgbClr val=\"" + BLUE + "\"/></a:solidFill></c:spPr><c:invertIfNegative val=\"1\"/>"
                + "<c:dLbls><c:numFmt formatCode=\"#,##0.#\" sourceLinked=\"0\"/><c:spPr><a:noFill/><a:ln><a:noFill/></a:ln></c:spPr>"
                + "<c:showLegendKey val=\"0\"/><c:showVal val=\"1\"/><c:showCatName val=\"0\"/><c:showSerName val=\"0\"/><c:showPercent val=\"0\"/>"
                + "<c:showBubbleSize val=\"0\"/></c:dLbls>"
                + "<c:cat><c:strRef><c:f>" + q + "$I$" + r1 + ":$I$" + r2 + "</c:f>" + strCache(cats) + "</c:strRef></c:cat>"
                + "<c:val><c:numRef><c:f>" + q + "$K$" + r1 + ":$K$" + r2 + "</c:f>" + numCache(vals, "#,##0.##") + "</c:numRef></c:val>"
                + "<c:extLst><c:ext uri=\"{6F2FDCE9-48DA-4B69-8628-5D25D57E5C99}\" xmlns:c14=\"http://schemas.microsoft.com/office/drawing/2007/8/2/chart\">"
                + "<c14:invertSolidFillFmt><c14:spPr><a:solidFill><a:srgbClr val=\"C00000\"/></a:solidFill></c14:spPr></c14:invertSolidFillFmt></c:ext></c:extLst></c:ser>"
                + "<c:gapWidth val=\"45\"/><c:axId val=\"10\"/><c:axId val=\"100\"/></c:barChart>"
                + "<c:catAx><c:axId val=\"10\"/><c:scaling><c:orientation val=\"maxMin\"/></c:scaling><c:delete val=\"0\"/><c:axPos val=\"l\"/>"
                + "<c:numFmt formatCode=\"General\" sourceLinked=\"0\"/><c:majorTickMark val=\"none\"/><c:minorTickMark val=\"none\"/>"
                + "<c:tickLblPos val=\"low\"/><c:crossAx val=\"100\"/><c:crosses val=\"autoZero\"/><c:auto val=\"1\"/><c:lblAlgn val=\"ctr\"/>"
                + "<c:lblOffset val=\"100\"/><c:noMultiLvlLbl val=\"1\"/></c:catAx>"
                + "<c:valAx><c:axId val=\"100\"/><c:scaling><c:orientation val=\"minMax\"/></c:scaling><c:delete val=\"0\"/><c:axPos val=\"t\"/>"
                + "<c:majorGridlines><c:spPr><a:ln><a:solidFill><a:srgbClr val=\"E5E7EB\"/></a:solidFill></a:ln></c:spPr></c:majorGridlines>"
                + "<c:numFmt formatCode=\"#,##0\" sourceLinked=\"0\"/><c:majorTickMark val=\"none\"/><c:minorTickMark val=\"none\"/>"
                + "<c:tickLblPos val=\"nextTo\"/><c:crossAx val=\"10\"/><c:crosses val=\"autoZero\"/><c:crossBetween val=\"between\"/></c:valAx></c:plotArea>"
                + TAIL;
    }

    static String anchor(int id, String rId, int row0, int cx, int cy, String name) {
        return "<xdr:oneCellAnchor><xdr:from><xdr:col>1</xdr:col><xdr:colOff>0</xdr:colOff><xdr:row>" + row0
                + "</xdr:row><xdr:rowOff>0</xdr:rowOff></xdr:from><xdr:ext cx=\"" + cx + "\" cy=\"" + cy
                + "\"/><xdr:graphicFrame macro=\"\"><xdr:nvGraphicFramePr><xdr:cNvPr id=\"" + id + "\" name=\"" + esc(name)
                + "\"/><xdr:cNvGraphicFramePr/></xdr:nvGraphicFramePr><xdr:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"0\" cy=\"0\"/></xdr:xfrm>"
                + "<a:graphic><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/chart\"><c:chart "
                + "xmlns:c=\"http://schemas.openxmlformats.org/drawingml/2006/chart\" "
                + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" r:id=\"" + rId
                + "\"/></a:graphicData></a:graphic></xdr:graphicFrame><xdr:clientData/></xdr:oneCellAnchor>";
    }
}
