package com.smartkeyboard.releve;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Cree un vrai fichier .xlsx colore et mis en forme (sans bibliotheque externe). */
public final class XlsxReport {

    private static final Charset UTF8 = Charset.forName("UTF-8");

    // Styles (index dans cellXfs)
    private static final int S_HEAD = 1, S_TXT = 2, S_TXT_B = 3, S_NUM = 4, S_NUM_B = 5,
            S_TITLE = 6, S_SUB = 7, S_CONS_BAD = 15, S_CONS = 16, S_CONS_B = 17,
            S_LABEL = 18, S_VALUE = 19, S_LINK = 20, S_LINK_B = 21, S_WARN = 22;

    private static long limUp = 50, limDown = 10;

    private XlsxReport() {
    }

    private static String esc(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '&') sb.append("&amp;");
            else if (c == '<') sb.append("&lt;");
            else if (c == '>') sb.append("&gt;");
            else if (c == '"') sb.append("&quot;");
            else if (c < 0x20 && c != '\t' && c != '\n' && c != '\r') sb.append(' ');
            else sb.append(c);
        }
        return sb.toString();
    }

    private static String colName(int i) {
        StringBuilder sb = new StringBuilder();
        i++;
        while (i > 0) {
            int m = (i - 1) % 26;
            sb.insert(0, (char) ('A' + m));
            i = (i - 1) / 26;
        }
        return sb.toString();
    }

    private static void txt(StringBuilder sb, int col, int row, int style, String v) {
        String ref = colName(col) + row;
        if (v == null || v.isEmpty()) {
            sb.append("<c r=\"").append(ref).append("\" s=\"").append(style).append("\"/>");
        } else {
            sb.append("<c r=\"").append(ref).append("\" s=\"").append(style)
                    .append("\" t=\"inlineStr\"><is><t xml:space=\"preserve\">")
                    .append(esc(v)).append("</t></is></c>");
        }
    }

    private static void num(StringBuilder sb, int col, int row, int style, long v) {
        sb.append("<c r=\"").append(colName(col)).append(row).append("\" s=\"").append(style)
                .append("\"><v>").append(v).append("</v></c>");
    }

    private static boolean isNum(String s) {
        return s != null && s.matches("\\d{1,15}");
    }

    private static int statusStyle(int st) {
        switch (st) {
            case Meters.SENT: return 8;
            case Meters.SUSPECT: return 9;
            case Meters.ERROR: return 10;
            case Meters.PHOTO:
            case Meters.UNKNOWN: return 12;
            case Meters.SIM: return 13;
            case Meters.EXTRA: return 14;
            default: return 11;
        }
    }

    private static final String[] HEAD = {"N° Compteur", "Client / Nom", "Adresse",
            "GPS (carte)", "N° Contrat", "Ancien index", "Nouvel index", "Consommation (m³)",
            "Anomalie", "Annotation", "Statut", "Note"};
    private static final double[] WIDTH = {20, 30, 66, 24, 20, 14, 14, 16, 24, 24, 20, 44};

    /** Cellule avec lien cliquable vers la carte (formule HYPERLINK, lue par Excel, LibreOffice, Sheets). */
    private static void link(StringBuilder sb, int col, int row, int style, String url, String label) {
        sb.append("<c r=\"").append(colName(col)).append(row).append("\" s=\"").append(style)
                .append("\" t=\"str\"><f>HYPERLINK(\"").append(esc(url)).append("\",\"")
                .append(esc(label)).append("\")</f><v>").append(esc(label)).append("</v></c>");
    }

    private static String sheetReport() {
        int n = Meters.ROWS.size();
        int lastRow = 4 + Math.max(n, 1);
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>");
        sb.append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">");
        sb.append("<sheetPr><pageSetUpPr fitToPage=\"1\"/></sheetPr>");
        sb.append("<sheetViews><sheetView workbookViewId=\"0\" showGridLines=\"0\" tabSelected=\"1\">")
                .append("<pane ySplit=\"4\" topLeftCell=\"A5\" activePane=\"bottomLeft\" state=\"frozen\"/>")
                .append("<selection pane=\"bottomLeft\" activeCell=\"A5\" sqref=\"A5\"/></sheetView></sheetViews>");
        sb.append("<sheetFormatPr defaultRowHeight=\"18\"/>");
        sb.append("<cols>");
        for (int i = 0; i < WIDTH.length; i++) {
            sb.append("<col min=\"").append(i + 1).append("\" max=\"").append(i + 1)
                    .append("\" width=\"").append(WIDTH[i]).append("\" customWidth=\"1\"/>");
        }
        sb.append("</cols><sheetData>");

        String date = new SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.FRANCE).format(new Date());
        int sent = Meters.count(Meters.SENT);
        sb.append("<row r=\"1\" ht=\"34\" customHeight=\"1\">");
        txt(sb, 0, 1, S_TITLE, "Rapport de relève des compteurs");
        for (int c = 1; c < HEAD.length; c++) txt(sb, c, 1, S_TITLE, "");
        sb.append("</row>");
        sb.append("<row r=\"2\" ht=\"20\" customHeight=\"1\">");
        txt(sb, 0, 2, S_SUB, "Généré le " + date + "   |   " + n + " compteurs   |   "
                + sent + " envoyés   |   " + Meters.count(Meters.SUSPECT) + " suspects");
        for (int c = 1; c < HEAD.length; c++) txt(sb, c, 2, S_SUB, "");
        sb.append("</row>");
        sb.append("<row r=\"3\" ht=\"6\" customHeight=\"1\"/>");
        sb.append("<row r=\"4\" ht=\"32\" customHeight=\"1\">");
        for (int c = 0; c < HEAD.length; c++) txt(sb, c, 4, S_HEAD, HEAD[c]);
        sb.append("</row>");

        int r = 5;
        for (int i = 0; i < n; i++, r++) {
            Meters.Row m = Meters.ROWS.get(i);
            boolean band = (i % 2) == 1;
            int t = band ? S_TXT_B : S_TXT;
            int nu = band ? S_NUM_B : S_NUM;
            sb.append("<row r=\"").append(r).append("\" ht=\"20\" customHeight=\"1\">");
            txt(sb, 0, r, t, m.compteur);
            txt(sb, 1, r, t, Meters.nameOf(m));
            txt(sb, 2, r, t, Meters.addressOf(m));
            String[] ll = Meters.gpsOf(m);
            if (ll != null) {
                link(sb, 3, r, band ? S_LINK_B : S_LINK,
                        "https://www.google.com/maps?q=" + ll[0] + "," + ll[1], ll[0] + ", " + ll[1]);
            } else {
                txt(sb, 3, r, t, "");
            }
            txt(sb, 4, r, t, Meters.contractOf(m));
            boolean oNum = isNum(m.oldIdx), nNum = isNum(m.newIdx);
            if (oNum) num(sb, 5, r, nu, Long.parseLong(m.oldIdx)); else txt(sb, 5, r, nu, m.oldIdx);
            if (nNum) num(sb, 6, r, nu, Long.parseLong(m.newIdx)); else txt(sb, 6, r, nu, m.newIdx);
            if (oNum && nNum) {
                long o = Long.parseLong(m.oldIdx), v = Long.parseLong(m.newIdx);
                int cs = (v > o + limUp || v < o - limDown)
                        ? S_CONS_BAD : (band ? S_CONS_B : S_CONS);
                num(sb, 7, r, cs, v - o);
            } else {
                txt(sb, 7, r, nu, "");
            }
            txt(sb, 8, r, t, m.anomaly);
            txt(sb, 9, r, t, m.annotation);
            txt(sb, 10, r, m.warn.isEmpty() ? statusStyle(m.status) : S_WARN,
                    Meters.statusLabel(m.status) + (m.warn.isEmpty() ? "" : " \u2013 compteur diff\u00E9rent"));
            txt(sb, 11, r, t, m.note);
            sb.append("</row>");
        }
        sb.append("</sheetData>");
        sb.append("<autoFilter ref=\"A4:L").append(lastRow).append("\"/>");
        sb.append("<mergeCells count=\"2\"><mergeCell ref=\"A1:L1\"/><mergeCell ref=\"A2:L2\"/></mergeCells>");
        sb.append("<pageMargins left=\"0.4\" right=\"0.4\" top=\"0.5\" bottom=\"0.5\" header=\"0.3\" footer=\"0.3\"/>");
        sb.append("<pageSetup paperSize=\"9\" orientation=\"landscape\" fitToWidth=\"1\" fitToHeight=\"0\"/>");
        sb.append("</worksheet>");
        return sb.toString();
    }

    private static String sheetSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>");
        sb.append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">");
        sb.append("<sheetViews><sheetView workbookViewId=\"0\" showGridLines=\"0\"/></sheetViews>");
        sb.append("<sheetFormatPr defaultRowHeight=\"20\"/>");
        sb.append("<cols><col min=\"1\" max=\"1\" width=\"34\" customWidth=\"1\"/>")
                .append("<col min=\"2\" max=\"2\" width=\"16\" customWidth=\"1\"/></cols><sheetData>");
        sb.append("<row r=\"1\" ht=\"34\" customHeight=\"1\">");
        txt(sb, 0, 1, S_TITLE, "Résumé");
        txt(sb, 1, 1, S_TITLE, "");
        sb.append("</row>");
        sb.append("<row r=\"2\" ht=\"26\" customHeight=\"1\">");
        txt(sb, 0, 2, S_HEAD, "Statut");
        txt(sb, 1, 2, S_HEAD, "Nombre");
        sb.append("</row>");
        int[] sts = {Meters.SENT, Meters.SUSPECT, Meters.ERROR, Meters.PHOTO, Meters.UNKNOWN,
                Meters.SIM, Meters.EXTRA, Meters.NODATA, Meters.NEW};
        int r = 3;
        for (int st : sts) {
            sb.append("<row r=\"").append(r).append("\" ht=\"22\" customHeight=\"1\">");
            txt(sb, 0, r, statusStyle(st), Meters.statusLabel(st));
            num(sb, 1, r, S_VALUE, Meters.count(st));
            sb.append("</row>");
            r++;
        }
        sb.append("<row r=\"").append(r).append("\" ht=\"24\" customHeight=\"1\">");
        txt(sb, 0, r, S_LABEL, "Total des compteurs");
        num(sb, 1, r, S_VALUE, Meters.ROWS.size());
        sb.append("</row>");
        sb.append("</sheetData>");
        sb.append("<pageMargins left=\"0.7\" right=\"0.7\" top=\"0.75\" bottom=\"0.75\" header=\"0.3\" footer=\"0.3\"/>");
        sb.append("</worksheet>");
        return sb.toString();
    }

    private static String fill(String rgb) {
        return "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FF" + rgb
                + "\"/><bgColor indexed=\"64\"/></patternFill></fill>";
    }

    private static String xf(int numFmt, int font, int fill, int border, String h, boolean wrap) {
        return "<xf numFmtId=\"" + numFmt + "\" fontId=\"" + font + "\" fillId=\"" + fill
                + "\" borderId=\"" + border + "\" xfId=\"0\" applyNumberFormat=\"1\" applyFont=\"1\""
                + " applyFill=\"1\" applyBorder=\"1\" applyAlignment=\"1\"><alignment horizontal=\""
                + h + "\" vertical=\"center\"" + (wrap ? " wrapText=\"1\"" : "") + "/></xf>";
    }

    private static String styles() {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>");
        sb.append("<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">");
        sb.append("<fonts count=\"8\">");
        sb.append("<font><sz val=\"11\"/><color rgb=\"FF1F2937\"/><name val=\"Calibri\"/></font>");
        sb.append("<font><b/><sz val=\"11\"/><color rgb=\"FFFFFFFF\"/><name val=\"Calibri\"/></font>");
        sb.append("<font><b/><sz val=\"18\"/><color rgb=\"FF1F4E79\"/><name val=\"Calibri\"/></font>");
        sb.append("<font><b/><sz val=\"11\"/><color rgb=\"FF1F2937\"/><name val=\"Calibri\"/></font>");
        sb.append("<font><i/><sz val=\"10\"/><color rgb=\"FF6B7280\"/><name val=\"Calibri\"/></font>");
        sb.append("<font><b/><sz val=\"11\"/><color rgb=\"FF9C0006\"/><name val=\"Calibri\"/></font>");
        sb.append("<font><b/><sz val=\"11\"/><color rgb=\"FF1F2937\"/><name val=\"Calibri\"/></font>");
        sb.append("<font><u/><sz val=\"11\"/><color rgb=\"FF0563C1\"/><name val=\"Calibri\"/></font>");
        sb.append("</fonts>");
        sb.append("<fills count=\"13\"><fill><patternFill patternType=\"none\"/></fill>")
                .append("<fill><patternFill patternType=\"gray125\"/></fill>")
                .append(fill("1F4E79")).append(fill("F3F6FA")).append(fill("C6EFCE"))
                .append(fill("FFEB9C")).append(fill("FFC7CE")).append(fill("E7E6E6"))
                .append(fill("F8CBAD")).append(fill("DDEBF7")).append(fill("E4DFEC"))
                .append(fill("DCE6F1")).append(fill("FFE0B2")).append("</fills>");
        sb.append("<borders count=\"2\"><border><left/><right/><top/><bottom/><diagonal/></border>")
                .append("<border>");
        for (String side : new String[]{"left", "right", "top", "bottom"}) {
            sb.append("<").append(side).append(" style=\"thin\"><color rgb=\"FFD0D5DD\"/></")
                    .append(side).append(">");
        }
        sb.append("<diagonal/></border></borders>");
        sb.append("<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>");
        sb.append("<cellXfs count=\"23\">");
        sb.append(xf(0, 0, 0, 0, "left", false));            // 0
        sb.append(xf(0, 1, 2, 1, "center", true));           // 1 en-tete
        sb.append(xf(0, 0, 0, 1, "left", false));            // 2 texte
        sb.append(xf(0, 0, 3, 1, "left", false));            // 3 texte alterne
        sb.append(xf(1, 0, 0, 1, "right", false));           // 4 nombre
        sb.append(xf(1, 0, 3, 1, "right", false));           // 5 nombre alterne
        sb.append(xf(0, 2, 11, 0, "left", false));           // 6 titre
        sb.append(xf(0, 4, 11, 0, "left", false));           // 7 sous-titre
        sb.append(xf(0, 3, 4, 1, "center", false));          // 8 vert
        sb.append(xf(0, 3, 5, 1, "center", false));          // 9 jaune
        sb.append(xf(0, 3, 6, 1, "center", false));          // 10 rouge
        sb.append(xf(0, 3, 7, 1, "center", false));          // 11 gris
        sb.append(xf(0, 3, 8, 1, "center", false));          // 12 orange
        sb.append(xf(0, 3, 9, 1, "center", false));          // 13 bleu
        sb.append(xf(0, 3, 10, 1, "center", false));         // 14 violet
        sb.append(xf(1, 5, 6, 1, "right", false));           // 15 conso hors limites
        sb.append(xf(1, 6, 0, 1, "right", false));           // 16 conso
        sb.append(xf(1, 6, 3, 1, "right", false));           // 17 conso alternee
        sb.append(xf(0, 3, 0, 1, "left", false));            // 18 libelle
        sb.append(xf(1, 3, 0, 1, "center", false));          // 19 valeur
        sb.append(xf(0, 7, 0, 1, "left", false));            // 20 lien
        sb.append(xf(0, 7, 3, 1, "left", false));            // 21 lien alterne
        sb.append(xf(0, 3, 12, 1, "center", true));         // 22 orange doux (compteur different)
        sb.append("</cellXfs>");
        sb.append("<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles>");
        sb.append("</styleSheet>");
        return sb.toString();
    }

    private static void put(ZipOutputStream z, String name, String content) throws Exception {
        z.putNextEntry(new ZipEntry(name));
        z.write(content.getBytes(UTF8));
        z.closeEntry();
    }

    public static byte[] build(long maxUp, long maxDown) throws Exception {
        limUp = maxUp;
        limDown = maxDown;
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ZipOutputStream z = new ZipOutputStream(bos);
        put(z, "[Content_Types].xml",
                "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                + "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>"
                + "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>"
                + "<Override PartName=\"/xl/worksheets/sheet2.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>"
                + "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>"
                + "</Types>");
        put(z, "_rels/.rels",
                "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>"
                + "</Relationships>");
        put(z, "xl/workbook.xml",
                "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" "
                + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">"
                + "<bookViews><workbookView activeTab=\"0\"/></bookViews>"
                + "<sheets><sheet name=\"Rapport\" sheetId=\"1\" r:id=\"rId1\"/>"
                + "<sheet name=\"Resume\" sheetId=\"2\" r:id=\"rId2\"/></sheets>"
                + "<definedNames><definedName name=\"_xlnm._FilterDatabase\" localSheetId=\"0\" hidden=\"1\">Rapport!$A$4:$L$"
                + (4 + Math.max(Meters.ROWS.size(), 1)) + "</definedName></definedNames>"
                + "</workbook>");
        put(z, "xl/_rels/workbook.xml.rels",
                "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/>"
                + "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet2.xml\"/>"
                + "<Relationship Id=\"rId3\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>"
                + "</Relationships>");
        put(z, "xl/styles.xml", styles());
        put(z, "xl/worksheets/sheet1.xml", sheetReport());
        put(z, "xl/worksheets/sheet2.xml", sheetSummary());
        z.finish();
        z.close();
        return bos.toByteArray();
    }
}
