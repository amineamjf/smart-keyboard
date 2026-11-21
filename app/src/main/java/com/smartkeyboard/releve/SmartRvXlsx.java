package com.smartkeyboard.releve;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Rapport Excel au meme dessin que SmartRV: feuille "Synthese" puis feuille "Releve" (19 colonnes, lignes colorees par etat). */
final class SmartRvXlsx {
    private final ArrayList<String> fonts = new ArrayList<>();
    private final ArrayList<String> fills = new ArrayList<>();
    private final ArrayList<String> xfs = new ArrayList<>();
    private final HashMap<String, Integer> xfIdx = new HashMap<>();

    private SmartRvXlsx() {
        fonts.add("<font><sz val=\"10\"/><name val=\"Calibri\"/></font>");
        fills.add("<fill><patternFill patternType=\"none\"/></fill>");
        fills.add("<fill><patternFill patternType=\"gray125\"/></fill>");
    }

    private int idx(ArrayList<String> l, String x) {
        int i = l.indexOf(x);
        if (i >= 0) return i;
        l.add(x);
        return l.size() - 1;
    }

    /** Style: fond, gras, couleur de police, taille, alignement horizontal, souligne. */
    private int st(String fill, boolean bold, String color, int sz, String h, boolean ul) {
        String key = fill + "|" + bold + "|" + color + "|" + sz + "|" + h + "|" + ul;
        Integer c = xfIdx.get(key);
        if (c != null) return c;
        int f = idx(fonts, "<font>" + (bold ? "<b/>" : "") + (ul ? "<u/>" : "") + "<sz val=\"" + sz
                + "\"/><color rgb=\"FF" + color + "\"/><name val=\"Calibri\"/></font>");
        int fi = idx(fills, "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FF" + fill
                + "\"/><bgColor indexed=\"64\"/></patternFill></fill>");
        xfs.add("<xf numFmtId=\"0\" fontId=\"" + f + "\" fillId=\"" + fi
                + "\" borderId=\"1\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\" applyBorder=\"1\" applyAlignment=\"1\">"
                + "<alignment horizontal=\"" + h + "\" vertical=\"center\" wrapText=\"1\"/></xf>");
        xfIdx.put(key, xfs.size() - 1);
        return xfs.size() - 1;
    }

    private String styles() {
        StringBuilder b = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">");
        b.append("<fonts count=\"").append(fonts.size()).append("\">");
        for (String f : fonts) b.append(f);
        b.append("</fonts><fills count=\"").append(fills.size()).append("\">");
        for (String f : fills) b.append(f);
        b.append("</fills><borders count=\"2\"><border><left/><right/><top/><bottom/><diagonal/></border>"
                + "<border><left style=\"thin\"><color rgb=\"FFB7C3D0\"/></left><right style=\"thin\"><color rgb=\"FFB7C3D0\"/></right>"
                + "<top style=\"thin\"><color rgb=\"FFB7C3D0\"/></top><bottom style=\"thin\"><color rgb=\"FFB7C3D0\"/></bottom>"
                + "<diagonal/></border></borders>"
                + "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>");
        b.append("<cellXfs count=\"").append(xfs.size() + 1).append("\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>");
        for (String x : xfs) b.append(x);
        b.append("</cellXfs><cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles></styleSheet>");
        return b.toString();
    }

    private static String esc(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '&') b.append("&amp;");
            else if (c == '<') b.append("&lt;");
            else if (c == '>') b.append("&gt;");
            else if (c == '"') b.append("&quot;");
            else if (c < 32 && c != '\n' && c != '\t') b.append(' ');
            else b.append(c);
        }
        return b.toString();
    }

    private static String col(int c) {
        String s = "";
        c++;
        while (c > 0) {
            int m = (c - 1) % 26;
            s = (char) ('A' + m) + s;
            c = (c - 1) / 26;
        }
        return s;
    }

    private void txt(StringBuilder sb, int c, int r, int s, String v) {
        sb.append("<c r=\"").append(col(c)).append(r).append("\" s=\"").append(s + 1).append("\" t=\"inlineStr\"><is><t xml:space=\"preserve\">")
                .append(esc(v == null ? "" : v)).append("</t></is></c>");
    }

    private void num(StringBuilder sb, int c, int r, int s, long v) {
        sb.append("<c r=\"").append(col(c)).append(r).append("\" s=\"").append(s + 1).append("\"><v>").append(v).append("</v></c>");
    }

    private static boolean isNum(String s) {
        return s != null && s.matches("-?\\d{1,15}");
    }

    private static final String[] HEAD = {"N°", "Type", "N° Client / Contrat", "N° Compteur", "Tournée",
            "Client", "Adresse", "Ancien Index", "Nouvel Index", "Consommation", "État", "Anomalie", "Annotation",
            "Date/Heure Relevé", "GPS", "Photo", "Ajouté", "Réordonné", "Saisi par", "Habituel / mois"};
    private static final int[] WCH = {6, 10, 22, 20, 14, 30, 44, 15, 15, 17, 26, 30, 28, 20, 26, 20, 12, 16, 14, 16};

    static final String S_NORMAL = "NORMAL", S_HIGH = "CONSOMMATION ÉLEVÉE", S_NEG = "CONSOMMATION NÉGATIVE",
            S_ANO = "ANOMALIE", S_UNREAD = "NON RELEVÉ";

    private static String etat(Meters.Row r, long cons) {
        if (!r.anomaly.isEmpty() && !r.anomaly.equalsIgnoreCase("RAS") && !r.anomaly.equalsIgnoreCase("Aucune")) return S_ANO;
        if (cons == Long.MIN_VALUE) return S_UNREAD;
        if (cons < 0) return S_NEG;
        if (Smart.high(r, cons)) return S_HIGH;
        return S_NORMAL;
    }

    static byte[] build() throws Exception {
        return new SmartRvXlsx().make();
    }

    private byte[] make() throws Exception {
        List<Meters.Row> rows = new ArrayList<>(Meters.ROWS);
        int n = rows.size();
        String[] et = new String[n];
        long[] cs = new long[n];
        int cNormal = 0, cHigh = 0, cNeg = 0, cAno = 0, cUnread = 0, cAdded = 0;
        LinkedHashMap<String, int[]> byTour = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            Meters.Row r = rows.get(i);
            cs[i] = isNum(r.oldIdx) && isNum(r.newIdx) ? Long.parseLong(r.newIdx) - Long.parseLong(r.oldIdx) : Long.MIN_VALUE;
            et[i] = etat(r, cs[i]);
            if (et[i].equals(S_NORMAL)) cNormal++;
            else if (et[i].equals(S_HIGH)) cHigh++;
            else if (et[i].equals(S_NEG)) cNeg++;
            else if (et[i].equals(S_ANO)) cAno++;
            else cUnread++;
            if (r.status == Meters.UNLISTED || r.status == Meters.EXTRA) cAdded++;
            String t = Meters.tourneeOf(r).trim();
            int[] a = byTour.get(t.isEmpty() ? "—" : t);
            if (a == null) {
                a = new int[2];
                byTour.put(t.isEmpty() ? "—" : t, a);
            }
            a[0]++;
            if (!et[i].equals(S_UNREAD)) a[1]++;
        }

        // ----- feuille "Releve"
        StringBuilder sh = new StringBuilder();
        sh.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">");
        sh.append("<sheetViews><sheetView workbookViewId=\"0\" rightToLeft=\"0\"><pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/></sheetView></sheetViews>");
        sh.append("<sheetFormatPr defaultRowHeight=\"22\"/><cols>");
        for (int i = 0; i < WCH.length; i++) {
            sh.append("<col min=\"").append(i + 1).append("\" max=\"").append(i + 1).append("\" width=\"").append(WCH[i]).append("\" customWidth=\"1\"/>");
        }
        sh.append("</cols><sheetData><row r=\"1\" ht=\"30\" customHeight=\"1\">");
        int hs = st("17365D", true, "FFFFFF", 11, "center", false);
        for (int c = 0; c < HEAD.length; c++) txt(sh, c, 1, hs, HEAD[c]);
        sh.append("</row>");
        for (int i = 0; i < n; i++) {
            Meters.Row r = rows.get(i);
            int rr = i + 2;
            String e = et[i];
            String fill = (i % 2 == 1) ? "F7FAFC" : "FFFFFF";
            String stFill = "E5E7EB";
            if (e.equals(S_NORMAL)) {
                fill = "DCFCE7";
                stFill = "86EFAC";
            } else if (e.equals(S_HIGH)) {
                fill = "FEF3C7";
                stFill = "FDE68A";
            } else if (e.equals(S_NEG)) {
                fill = "FECACA";
                stFill = "F87171";
            } else if (e.equals(S_ANO)) {
                fill = "FDE2E2";
                stFill = "FCA5A5";
            } else {
                stFill = "D1D5DB";
            }
            boolean added = r.status == Meters.UNLISTED || r.status == Meters.EXTRA;
            String rowFill = added ? "FCD34D" : fill;
            String fontC = added ? "78350F" : "111827";
            int sc = st(rowFill, false, fontC, 10, "center", false);
            int sl = st(rowFill, false, fontC, 10, "left", false);
            int sb = st(rowFill, true, fontC, 10, "center", false);
            String address = r.address.isEmpty() ? Meters.colValue(r, "ADRESSE*", "ADRESS*") : r.address;
            String gps = Meters.colValue(r, "GPS*");
            boolean hasPhoto = !r.photos.isEmpty();
            sh.append("<row r=\"").append(rr).append("\" ht=\"").append(hasPhoto ? 62 : 26).append("\" customHeight=\"1\">");
            num(sh, 0, rr, sc, i + 1);
            // valeurs d'origine du fichier SmartRV importe, reprises telles quelles (aller-retour fidele)
            String typ = Meters.colValue(r, "TYPE");
            txt(sh, 1, rr, sc, typ.isEmpty() ? "EAU" : typ);
            txt(sh, 2, rr, sc, r.contrat);
            txt(sh, 3, rr, sc, r.compteur);
            txt(sh, 4, rr, sc, Meters.tourneeOf(r));
            txt(sh, 5, rr, sl, r.client);
            txt(sh, 6, rr, sl, address);
            if (isNum(r.oldIdx)) num(sh, 7, rr, sc, Long.parseLong(r.oldIdx)); else txt(sh, 7, rr, sc, r.oldIdx);
            if (isNum(r.newIdx)) num(sh, 8, rr, sc, Long.parseLong(r.newIdx)); else txt(sh, 8, rr, sc, r.newIdx);
            if (cs[i] != Long.MIN_VALUE) num(sh, 9, rr, sc, cs[i]); else txt(sh, 9, rr, sc, "");
            txt(sh, 10, rr, st(stFill, true, "111827", 10, "center", false), e);
            boolean ano = e.equals(S_ANO);
            txt(sh, 11, rr, ano ? st("FCA5A5", true, "7F1D1D", 10, "left", false) : sl, ano ? r.anomaly : "RAS");
            txt(sh, 12, rr, sl, r.annotation.isEmpty() ? "Aucune" : r.annotation);
            txt(sh, 13, rr, sc, Meters.colValue(r, "DATE HEURE*"));
            if (!gps.isEmpty() && gps.split(",").length == 2) {
                String[] g = gps.split(",");
                String url = "https://www.google.com/maps?q=" + g[0].trim() + "," + g[1].trim();
                String label = "📍 " + gps;
                sh.append("<c r=\"").append(col(14)).append(rr).append("\" s=\"").append(st(rowFill, true, "0563C1", 10, "center", true) + 1)
                        .append("\" t=\"str\"><f>HYPERLINK(\"").append(esc(url)).append("\",\"").append(esc(label))
                        .append("\")</f><v>").append(esc(label)).append("</v></c>");
            } else {
                txt(sh, 14, rr, sc, gps);
            }
            txt(sh, 15, rr, hasPhoto ? st(rowFill, true, "0563C1", 10, "center", true) : sc,
                    hasPhoto ? "📷 " + r.photos : "");
            txt(sh, 16, rr, added ? st("F59E0B", true, "FFFFFF", 10, "center", false) : sc,
                    added ? "🆕 AJOUTÉ" : "Non");
            String reo = Meters.colValue(r, "REORDONNE*");
            txt(sh, 17, rr, sc, reo.isEmpty() ? "Non" : reo);
            txt(sh, 18, rr, sc, Meters.colValue(r, "SAISI*"));
            double u = Smart.usual(r);
            txt(sh, 19, rr, sc, u < 0 ? "" : String.format(java.util.Locale.FRANCE, "%.1f", u));
            sh.append("</row>");
        }
        sh.append("</sheetData><autoFilter ref=\"A1:").append(col(HEAD.length - 1)).append(Math.max(1, n + 1)).append("\"/></worksheet>");

        // ----- feuille "Synthese"
        StringBuilder sy = new StringBuilder();
        sy.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">");
        sy.append("<sheetViews><sheetView workbookViewId=\"0\" tabSelected=\"1\"/></sheetViews><sheetFormatPr defaultRowHeight=\"20\"/>");
        sy.append("<cols><col min=\"1\" max=\"1\" width=\"32\" customWidth=\"1\"/><col min=\"2\" max=\"2\" width=\"32\" customWidth=\"1\"/><col min=\"3\" max=\"3\" width=\"14\" customWidth=\"1\"/></cols><sheetData>");
        String date = new java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale.FRANCE).format(new java.util.Date());
        List<Object[]> sum = new ArrayList<>();
        sum.add(new Object[]{"RELEVÉ — SYNTHÈSE DU RELEVÉ", "", "T"});
        sum.add(new Object[]{"Date d’export", date, ""});
        sum.add(new Object[]{"Total compteurs", n, ""});
        sum.add(new Object[]{"Compteurs EAU", n, ""});
        sum.add(new Object[]{S_NORMAL, cNormal, "86EFAC"});
        sum.add(new Object[]{S_HIGH, cHigh, "FDE68A"});
        sum.add(new Object[]{S_NEG, cNeg, "FCA5A5"});
        sum.add(new Object[]{S_ANO, cAno, "FCA5A5"});
        sum.add(new Object[]{S_UNREAD, cUnread, "D1D5DB"});
        sum.add(new Object[]{"Compteurs ajoutés manuellement", cAdded, ""});
        sum.add(new Object[]{"", "", ""});
        sum.add(new Object[]{"LÉGENDE", "", "B"});
        sum.add(new Object[]{"Vert", S_NORMAL, ""});
        sum.add(new Object[]{"Jaune", S_HIGH, ""});
        sum.add(new Object[]{"Rouge", "CONSOMMATION NÉGATIVE / ANOMALIE", ""});
        sum.add(new Object[]{"Gris", S_UNREAD, ""});
        sum.add(new Object[]{"Jaune foncé (ligne entière)", "COMPTEUR AJOUTÉ MANUELLEMENT", ""});
        sum.add(new Object[]{"", "", ""});
        sum.add(new Object[]{"PAR TOURNÉE", "Compteurs", "Relevés"});
        for (java.util.Map.Entry<String, int[]> e : byTour.entrySet()) {
            sum.add(new Object[]{e.getKey(), e.getValue()[0], e.getValue()[1], "R"});
        }
        for (int r = 0; r < sum.size(); r++) {
            Object[] row = sum.get(r);
            int rr = r + 1;
            boolean title = r == 0;
            boolean sec = row[0].equals("LÉGENDE") || row[0].equals("PAR TOURNÉE");
            String tag = String.valueOf(row[2]);
            String fill = title ? "17365D" : sec ? "E6EAF3" : (tag.matches("[0-9A-F]{6}") ? tag : "FFFFFF");
            sy.append("<row r=\"").append(rr).append("\" ht=\"").append(title ? 30 : 22).append("\" customHeight=\"1\">");
            int s0 = st(fill, true, title ? "FFFFFF" : "111827", title ? 14 : 11, "left", false);
            int s1 = st(fill, sec, "111827", 11, "center", false);
            txt(sy, 0, rr, s0, String.valueOf(row[0]));
            if (row[1] instanceof Integer) num(sy, 1, rr, s1, (Integer) row[1]); else txt(sy, 1, rr, s1, String.valueOf(row[1]));
            if (row.length > 3) num(sy, 2, rr, s1, (Integer) row[2]);
            else if (row[0].equals("PAR TOURNÉE")) txt(sy, 2, rr, s1, String.valueOf(row[2]));
            sy.append("</row>");
        }
        sy.append("</sheetData></worksheet>");

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
                + "</Types>");
        put(z, "_rels/.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>"
                + "</Relationships>");
        put(z, "xl/workbook.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">"
                + "<bookViews><workbookView activeTab=\"0\"/></bookViews><sheets>"
                + "<sheet name=\"Synthèse\" sheetId=\"1\" r:id=\"rId1\"/><sheet name=\"Relevé\" sheetId=\"2\" r:id=\"rId2\"/></sheets>"
                + "<definedNames><definedName name=\"_xlnm._FilterDatabase\" localSheetId=\"1\" hidden=\"1\">'Relevé'!$A$1:$"
                + col(HEAD.length - 1) + "$" + Math.max(1, n + 1) + "</definedName></definedNames></workbook>");
        put(z, "xl/_rels/workbook.xml.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/>"
                + "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet2.xml\"/>"
                + "<Relationship Id=\"rId3\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>"
                + "</Relationships>");
        put(z, "xl/styles.xml", styles());
        put(z, "xl/worksheets/sheet1.xml", sy.toString());
        put(z, "xl/worksheets/sheet2.xml", sh.toString());
        z.finish();
        z.close();
        return bos.toByteArray();
    }

    private static void put(ZipOutputStream z, String name, String content) throws Exception {
        z.putNextEntry(new ZipEntry(name));
        z.write(content.getBytes(StandardCharsets.UTF_8));
        z.closeEntry();
    }
}
