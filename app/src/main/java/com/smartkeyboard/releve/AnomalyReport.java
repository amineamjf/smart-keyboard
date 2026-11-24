package com.smartkeyboard.releve;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipOutputStream;

import static com.smartkeyboard.releve.SmartRvReport.col;
import static com.smartkeyboard.releve.SmartRvReport.esc;

/**
 * Rapport « Verification des anomalies » (Excel): feuille de donnees + tableau de bord, dans le style du
 * rapport de remplacements (bandeau bleu, en-tetes bleus, cartes de chiffres, anneau et barres).
 * Colonne « Confirmation » laissee vide: la decision reste a l'utilisateur.
 */
final class AnomalyReport {
    private static final String NAVY = "0F4C9A", BLUE = "1565D8", GREEN = "2E9E6B", BG = "F4F8FB", LINE = "D6DEEB";
    private static final String[] VERDICTS = {AnomalyEngine.V_NEG, AnomalyEngine.V_SWAP, AnomalyEngine.V_HIGH2,
            AnomalyEngine.V_HIGH, AnomalyEngine.V_RECENT, AnomalyEngine.V_LOW, AnomalyEngine.V_ZERO,
            AnomalyEngine.V_BAD, AnomalyEngine.V_SHORT, AnomalyEngine.V_UNK, AnomalyEngine.V_OK};
    /** fond, texte, couleur d'anneau. */
    private static final String[][] COL = {{"FDE3E3", "B3261E", "E57373"}, {"FDE3E3", "B3261E", "EF9A9A"},
            {"FFE4CC", "B34700", "FFA45C"}, {"FFF1D6", "8A5200", "FFD27A"}, {"FFF1D6", "8A5200", "FFE29A"},
            {"E1ECFF", "0F2F8A", "7FA8F0"}, {"EDE7F6", "5B3FA0", "B39DDB"}, {"FCE8F3", "9C1F6B", "E59AC4"},
            {"ECEFF4", "4A5572", "B8C0D0"}, {"F8D7DA", "8E1B14", "C0504D"}, {"E3F5EB", "12703A", "7ACB9E"}};

    private AnomalyReport() {
    }

    private static String[] colorOf(String verdict) {
        for (int i = 0; i < VERDICTS.length; i++) if (VERDICTS[i].equals(verdict)) return COL[i];
        return COL[8];
    }

    private static String prioText(int p) {
        return p == 1 ? "Urgent" : (p == 2 ? "À revoir" : "Normal");
    }

    private static String f1(double x) {
        if (Double.isNaN(x)) return "";
        return String.valueOf(Math.round(x * 10) / 10.0).replace(".0", "").replace('.', ',');
    }

    static byte[] build(List<String[]> pairs) throws Exception {
        return new AnomalyReport().make(pairs);
    }

    private byte[] make(List<String[]> pairs) throws Exception {
        List<Integer> months = AnomalyEngine.lastMonths();
        HashMap<String, Meters.Row> byC = AnomalyEngine.indexByContrat();
        ArrayList<AnomalyEngine.Res> all = new ArrayList<>();
        for (String[] p : pairs) all.add(AnomalyEngine.analysePair(p[0], p[1], months, byC));
        // tri: urgent d'abord, puis tournee, puis client
        Collections.sort(all, (a, b) -> {
            if (a.prio != b.prio) return a.prio - b.prio;
            String ta = a.row == null ? "~" : Meters.tourneeOf(a.row), tb = b.row == null ? "~" : Meters.tourneeOf(b.row);
            return ta.compareTo(tb);
        });
        SmartRvReport x = new SmartRvReport();
        SmartRvReport.Sheet v = new SmartRvReport.Sheet();
        int nm = months.size();
        ArrayList<String> heads = new ArrayList<>();
        Collections.addAll(heads, "N°", "Contrat", "N° Compteur", "Tournée", "Client", "Adresse", "GPS");
        int cMonth0 = heads.size();
        for (int m : months) heads.add("Conso " + Smart.monthName(m));
        Collections.addAll(heads, "Moy.", "Ratio", "Début hausse", "Verdict", "Priorité", "Ancien index",
                "Nouvel index", "Index attendu (min – max)", "Remarque", "Confirmation");
        int n = heads.size();
        int cVerdict = cMonth0 + nm + 3;
        int cConf = n - 1;
        double[] w = new double[n];
        double[] base = {5, 11, 17, 16, 24, 30, 20};
        for (int i = 0; i < n; i++) w[i] = i < base.length ? base[i] : 10;
        for (int i = 0; i < nm; i++) w[cMonth0 + i] = 12;
        w[cMonth0 + nm] = 8;
        w[cMonth0 + nm + 1] = 8;
        w[cMonth0 + nm + 2] = 13;
        w[cVerdict] = 24;
        w[cVerdict + 1] = 10;
        w[cVerdict + 2] = 11;
        w[cVerdict + 3] = 11;
        w[cVerdict + 4] = 20;
        w[cVerdict + 5] = 30;
        w[cConf] = 16;
        v.cols = w;
        String title = "Vérification des anomalies  —  " + new java.text.SimpleDateFormat("dd/MM/yyyy", Locale.FRANCE)
                .format(new java.util.Date());
        v.span(0, 0, n - 1, title, x.st(NAVY, true, false, false, 15, "FFFFFF", "center", false, null, null));
        v.rowH.put(0, 30.0);
        int hs = x.st(BLUE, true, false, false, 11, "FFFFFF", "center", true, null, null);
        for (int i = 0; i < n; i++) v.put(1, i, heads.get(i), hs);
        v.rowH.put(1, 32.0);
        int r = 2;
        LinkedHashMap<String, Integer> byVerdict = new LinkedHashMap<>();
        for (String vd : VERDICTS) byVerdict.put(vd, 0);
        LinkedHashMap<String, Integer> byTour = new LinkedHashMap<>();
        int urgent = 0, review = 0;
        for (int k = 0; k < all.size(); k++) {
            AnomalyEngine.Res a = all.get(k);
            byVerdict.put(a.verdict, byVerdict.get(a.verdict) + 1);
            String tour = a.row == null ? "—" : Meters.tourneeOf(a.row).trim();
            if (tour.isEmpty()) tour = "(sans tournée)";
            if (a.row != null) byTour.put(tour, byTour.containsKey(tour) ? byTour.get(tour) + 1 : 1);
            if (a.prio == 1) urgent++;
            else if (a.prio == 2) review++;
            boolean unk = a.row == null;
            String zebra = unk ? "FDEDEE" : (k % 2 == 1 ? "F7FAFD" : null);
            int sC = x.st(zebra, false, false, false, 11, "000000", "center", false, LINE, null);
            int sL = x.st(zebra, false, false, false, 11, "000000", "left", true, LINE, null);
            int sN = x.st(zebra, false, false, false, 11, "000000", "center", false, LINE, "#,##0.##");
            String[] gps = unk ? null : Meters.gpsOf(a.row);
            Object gv = gps == null ? "—" : new Object[]{"https://www.google.com/maps?q=" + gps[0] + "," + gps[1],
                    gps[0] + ", " + gps[1]};
            int sG = gps == null ? sC : x.st(zebra, false, false, true, 11, "0563C1", "center", false, LINE, null);
            v.put(r, 0, (double) (k + 1), sC);
            v.put(r, 1, a.contrat, sC);
            v.put(r, 2, a.compteur, sC);
            v.put(r, 3, unk ? "—" : tour, sC);
            v.put(r, 4, unk ? "Non trouvé dans le tableau" : Meters.nameOf(a.row), sL);
            v.put(r, 5, unk ? "" : Meters.addressOf(a.row), sL);
            v.put(r, 6, gv, sG);
            for (int i = 0; i < nm; i++) {
                double c = a.monthCons[i];
                v.put(r, cMonth0 + i, Double.isNaN(c) ? (Object) "" : (Object) c, sN);
            }
            v.put(r, cMonth0 + nm, Double.isNaN(a.mean) ? (Object) "" : (Object) (Math.round(a.mean * 10) / 10.0), sN);
            v.put(r, cMonth0 + nm + 1, Double.isNaN(a.ratio) ? (Object) "" : (Object) a.ratio, sN);
            v.put(r, cMonth0 + nm + 2, a.start.isEmpty() ? "—" : a.start, sC);
            String[] cc = colorOf(a.verdict);
            v.put(r, cVerdict, a.verdict, x.st(cc[0], true, false, false, 11, cc[1], "center", false, LINE, null));
            v.put(r, cVerdict + 1, prioText(a.prio), sC);
            v.put(r, cVerdict + 2, a.old == null ? (Object) "" : (Object) (double) a.old, sN);
            v.put(r, cVerdict + 3, a.cur == null ? (Object) "" : (Object) (double) a.cur, sN);
            v.put(r, cVerdict + 4, a.expMin == null ? "" : a.expMin + " – " + a.expMax, sC);
            v.put(r, cVerdict + 5, a.remark, sL);
            v.put(r, cConf, "", x.st(zebra, false, false, false, 11, "000000", "center", false, LINE, null));
            v.rowH.put(r, 30.0);
            r++;
        }
        int lastRow = Math.max(2, r - 1);
        // ----------------------------------------------------------- tableau de bord (une colonne, adapte au telephone)
        SmartRvReport.Sheet d = new SmartRvReport.Sheet();
        d.cols = new double[]{2, 9.5, 9.5, 9.5, 9.5, 9.5, 9.5, 2};
        int bgS = x.st(BG, false, false, false, 10, "000000", null, false, null, null);
        int T0 = 60;
        int nv = 0;
        for (String vd : VERDICTS) if (byVerdict.get(vd) > 0) nv++;
        int nt = Math.min(12, byTour.size());
        int endRow = T0 + 2 + nv + 3 + nt + 3;
        for (int rr = 0; rr < endRow; rr++) for (int cc = 0; cc < 8; cc++) d.put(rr, cc, "", bgS);
        int banner = x.st(NAVY, true, false, false, 14, "FFFFFF", "center", false, null, null);
        d.span(0, 1, 6, "TABLEAU DE BORD \u2014 V\u00C9RIFICATION DES ANOMALIES", banner);
        for (int cc = 1; cc <= 6; cc++) d.put(1, cc, "", banner);
        d.merges.add(new int[]{0, 1, 1, 6});
        d.rowH.put(0, 18.0);
        d.rowH.put(1, 18.0);
        d.span(2, 1, 6, new java.text.SimpleDateFormat("dd/MM/yyyy", Locale.FRANCE).format(new java.util.Date())
                + "  \u00B7  " + Meters.ROWS.size() + " compteurs dans le tableau",
                x.st(BG, false, true, false, 10, "55637D", "center", false, null, null));
        Object[][] cards = {{"\u00C0 v\u00E9rifier", (double) all.size(), "E3F5EB", "12703A", 1, 2},
                {"Urgents", (double) urgent, "FDE3E3", "B3261E", 3, 4}, {"\u00C0 revoir", (double) review, "FFF1D6", "8A5200", 5, 6}};
        for (Object[] c : cards) {
            int c1 = (Integer) c[4], c2 = (Integer) c[5];
            int sl = x.st((String) c[2], true, false, false, 10, (String) c[3], "center", false, "FFFFFF", null);
            int sv = x.st((String) c[2], true, false, false, 26, (String) c[3], "center", false, "FFFFFF", "#,##0");
            d.span(4, c1, c2, c[0], sl);
            d.span(5, c1, c2, c[1], sv);
            d.put(6, c1, "", sv);
            d.put(6, c2, "", sv);
            d.merges.add(new int[]{5, c1, 6, c2});
        }
        d.rowH.put(4, 20.0);
        d.rowH.put(5, 22.0);
        d.rowH.put(6, 22.0);
        d.span(T0, 1, 6, "D\u00E9tail des donn\u00E9es", x.st(BG, true, false, false, 11, NAVY, "left", false, null, null));
        int th = x.st(BLUE, true, false, false, 10, "FFFFFF", "left", false, null, null);
        int thc = x.st(BLUE, true, false, false, 10, "FFFFFF", "center", false, null, null);
        int tc = x.st("FFFFFF", false, false, false, 10, "000000", "left", false, null, null);
        int tn = x.st("FFFFFF", false, false, false, 10, "000000", "center", false, null, "#,##0");
        d.span(T0 + 1, 1, 4, "Verdict", th);
        for (int cc = 2; cc <= 4; cc++) d.put(T0 + 1, cc, "", th);
        d.put(T0 + 1, 5, "Nb", thc);
        ArrayList<String> vc = new ArrayList<>();
        ArrayList<Long> vv = new ArrayList<>();
        ArrayList<String> vcol = new ArrayList<>();
        int row = T0 + 2;
        for (int i = 0; i < VERDICTS.length; i++) {
            int cnt = byVerdict.get(VERDICTS[i]);
            if (cnt == 0) continue;
            d.span(row, 1, 4, VERDICTS[i], tc);
            for (int cc = 2; cc <= 4; cc++) d.put(row, cc, "", tc);
            d.put(row, 5, (double) cnt, tn);
            vc.add(VERDICTS[i]);
            vv.add((long) cnt);
            vcol.add(COL[i][2]);
            row++;
        }
        int vR1 = T0 + 3, vR2 = row; // 1-based
        int T1 = row + 1;
        d.span(T1, 1, 4, "Tourn\u00E9e", th);
        for (int cc = 2; cc <= 4; cc++) d.put(T1, cc, "", th);
        d.put(T1, 5, "Nb", thc);
        ArrayList<java.util.Map.Entry<String, Integer>> tl = new ArrayList<>(byTour.entrySet());
        Collections.sort(tl, (p, q) -> q.getValue() - p.getValue());
        ArrayList<String> tcats = new ArrayList<>();
        ArrayList<Long> tvals = new ArrayList<>();
        int tr = T1 + 1;
        for (int i = 0; i < nt; i++) {
            d.span(tr, 1, 4, tl.get(i).getKey(), tc);
            for (int cc = 2; cc <= 4; cc++) d.put(tr, cc, "", tc);
            d.put(tr, 5, (double) tl.get(i).getValue(), tn);
            tcats.add(tl.get(i).getKey());
            tvals.add((long) tl.get(i).getValue());
            tr++;
        }
        int tR1 = T1 + 2, tR2 = tr;
        int lastDash = Math.max(endRow, tr + 2);

        String vtail = "<autoFilter ref=\"A2:" + col(n - 1) + lastRow + "\"/>"
                + "<mergeCells count=\"" + v.merges.size() + "\">" + SmartRvReport.mergeXml(v) + "</mergeCells>"
                + "<conditionalFormatting sqref=\"" + col(cMonth0) + "3:" + col(cMonth0 + nm - 1) + lastRow + "\">"
                + "<cfRule type=\"colorScale\" priority=\"1\"><colorScale><cfvo type=\"min\"/><cfvo type=\"max\"/>"
                + "<color rgb=\"FFFFFFFF\"/><color rgb=\"FF9DC3FF\"/></colorScale></cfRule></conditionalFormatting>"
                + "<dataValidations count=\"1\"><dataValidation type=\"list\" allowBlank=\"1\" showErrorMessage=\"1\" sqref=\""
                + col(cConf) + "3:" + col(cConf) + lastRow + "\"><formula1>\"Confirmé,Corrigé,À vérifier,Photo demandée\"</formula1>"
                + "</dataValidation></dataValidations>"
                + "<pageMargins left=\"0.4\" right=\"0.4\" top=\"0.6\" bottom=\"0.6\" header=\"0.3\" footer=\"0.3\"/>"
                + "<pageSetup orientation=\"landscape\" fitToWidth=\"1\" fitToHeight=\"0\"/></worksheet>";
        String vXml = SmartRvReport.sheetXml(v, "<sheetViews><sheetView showGridLines=\"0\" workbookViewId=\"0\"><pane xSplit=\"2\" ySplit=\"2\" "
                + "topLeftCell=\"C3\" activePane=\"bottomRight\" state=\"frozen\"/></sheetView></sheetViews>", vtail, lastRow - 1 + 0, n - 1);
        String dtail = "<mergeCells count=\"" + d.merges.size() + "\">" + SmartRvReport.mergeXml(d) + "</mergeCells>"
                + "<pageMargins left=\"0.5\" right=\"0.5\" top=\"0.5\" bottom=\"0.5\" header=\"0.3\" footer=\"0.3\"/>"
                + "<pageSetup orientation=\"portrait\" fitToWidth=\"1\" fitToHeight=\"1\"/><drawing r:id=\"rIdAnDr\"/></worksheet>";
        String dXml = SmartRvReport.sheetXml(d, "<sheetViews><sheetView showGridLines=\"0\" tabSelected=\"1\" workbookViewId=\"0\"/></sheetViews>",
                dtail, lastDash + 8, 10);

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ZipOutputStream z = new ZipOutputStream(bos);
        SmartRvReport.put(z, "[Content_Types].xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                + "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>"
                + "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>"
                + "<Override PartName=\"/xl/worksheets/sheet2.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>"
                + "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>"
                + "<Override PartName=\"/xl/drawings/drawingAn1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.drawing+xml\"/>"
                + "<Override PartName=\"/xl/charts/chartAn1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.drawingml.chart+xml\"/>"
                + "<Override PartName=\"/xl/charts/chartAn2.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.drawingml.chart+xml\"/>"
                + "<Override PartName=\"/xl/charts/chartAn3.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.drawingml.chart+xml\"/>"
                + "</Types>");
        SmartRvReport.put(z, "_rels/.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>"
                + "</Relationships>");
        SmartRvReport.put(z, "xl/workbook.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">"
                + "<bookViews><workbookView activeTab=\"0\"/></bookViews><sheets>"
                + "<sheet name=\"Tableau de bord\" sheetId=\"1\" r:id=\"rId1\"/><sheet name=\"Vérification\" sheetId=\"2\" r:id=\"rId2\"/></sheets>"
                + "<definedNames><definedName name=\"_xlnm._FilterDatabase\" localSheetId=\"1\" hidden=\"1\">'Vérification'!$A$2:$"
                + col(n - 1) + "$" + lastRow + "</definedName></definedNames></workbook>");
        SmartRvReport.put(z, "xl/_rels/workbook.xml.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/>"
                + "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet2.xml\"/>"
                + "<Relationship Id=\"rId3\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>"
                + "</Relationships>");
        SmartRvReport.put(z, "xl/worksheets/_rels/sheet1.xml.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rIdAnDr\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/drawing\" Target=\"../drawings/drawingAn1.xml\"/>"
                + "</Relationships>");
        SmartRvReport.put(z, "xl/styles.xml", x.stylesXml());
        SmartRvReport.put(z, "xl/worksheets/sheet1.xml", dXml);
        SmartRvReport.put(z, "xl/worksheets/sheet2.xml", vXml);
        SmartRvReport.put(z, "xl/drawings/drawingAn1.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<xdr:wsDr xmlns:xdr=\"http://schemas.openxmlformats.org/drawingml/2006/spreadsheetDrawing\" xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\">"
                + anchorTwo(2, "rId1", 8, 24, "Verdicts")
                + anchorTwo(3, "rId2", 25, 41, "Tournees")
                + anchorTwo(4, "rId3", 42, 58, "Types")
                + "</xdr:wsDr>");
        SmartRvReport.put(z, "xl/drawings/_rels/drawingAn1.xml.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/chart\" Target=\"../charts/chartAn1.xml\"/>"
                + "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/chart\" Target=\"../charts/chartAn2.xml\"/>"
                + "<Relationship Id=\"rId3\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/chart\" Target=\"../charts/chartAn3.xml\"/>"
                + "</Relationships>");
        SmartRvReport.put(z, "xl/charts/chartAn1.xml", chart(true, false, "Répartition par verdict", "B", "F", vR1, vR2, vc, vv, vcol, null));
        SmartRvReport.put(z, "xl/charts/chartAn2.xml", chart(false, false, "Anomalies par tournée", "B", "F", tR1, tR2, tcats, tvals, null, BLUE));
        SmartRvReport.put(z, "xl/charts/chartAn3.xml", chart(false, true, "Type de problème", "B", "F", vR1, vR2, vc, vv, null, GREEN));
        z.finish();
        z.close();
        return bos.toByteArray();
    }

    /** Graphique ancre entre deux cellules (de la colonne B a G): suit la grille, jamais de chevauchement. */
    private static String anchorTwo(int id, String rId, int row0, int row1, String name) {
        return "<xdr:twoCellAnchor><xdr:from><xdr:col>1</xdr:col><xdr:colOff>0</xdr:colOff><xdr:row>" + row0
                + "</xdr:row><xdr:rowOff>0</xdr:rowOff></xdr:from><xdr:to><xdr:col>7</xdr:col><xdr:colOff>0</xdr:colOff><xdr:row>"
                + row1 + "</xdr:row><xdr:rowOff>0</xdr:rowOff></xdr:to><xdr:graphicFrame macro=\"\"><xdr:nvGraphicFramePr><xdr:cNvPr id=\""
                + id + "\" name=\"" + esc(name) + "\"/><xdr:cNvGraphicFramePr/></xdr:nvGraphicFramePr><xdr:xfrm><a:off x=\"0\" y=\"0\"/>"
                + "<a:ext cx=\"0\" cy=\"0\"/></xdr:xfrm><a:graphic><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/chart\">"
                + "<c:chart xmlns:c=\"http://schemas.openxmlformats.org/drawingml/2006/chart\" "
                + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" r:id=\"" + rId
                + "\"/></a:graphicData></a:graphic></xdr:graphicFrame><xdr:clientData/></xdr:twoCellAnchor>";
    }

    private static String anchorAt(int id, String rId, int col0, int row0, int cx, int cy, String name) {
        return SmartRvReport.anchor(id, rId, row0, cx, cy, name).replace("<xdr:col>1</xdr:col>", "<xdr:col>" + col0 + "</xdr:col>");
    }

    /** Anneau ou barres (verticales, ou horizontales si horiz). Donnees lues dans « Tableau de bord ». */
    private static String chart(boolean doughnut, boolean horiz, String title, String catCol, String valCol, int r1, int r2,
                                List<String> cats, List<Long> vals, List<String> colors, String barColor) {
        String q = "'Tableau de bord'!";
        String cat = "<c:cat><c:strRef><c:f>" + q + "$" + catCol + "$" + r1 + ":$" + catCol + "$" + r2 + "</c:f>"
                + SmartRvReport.strCache(cats) + "</c:strRef></c:cat>";
        String val = "<c:val><c:numRef><c:f>" + q + "$" + valCol + "$" + r1 + ":$" + valCol + "$" + r2 + "</c:f>"
                + SmartRvReport.numCache(vals, null) + "</c:numRef></c:val>";
        String ser = "<c:tx><c:strRef><c:f>" + q + "$" + valCol + "$" + (r1 - 1) + "</c:f>"
                + SmartRvReport.strCache(Collections.singletonList("Nb")) + "</c:strRef></c:tx>";
        String lbl = "<c:dLbls><c:spPr><a:noFill/><a:ln><a:noFill/></a:ln></c:spPr><c:showLegendKey val=\"0\"/><c:showVal val=\"1\"/>"
                + "<c:showCatName val=\"0\"/><c:showSerName val=\"0\"/><c:showPercent val=\"0\"/><c:showBubbleSize val=\"0\"/>"
                + (doughnut ? "<c:showLeaderLines val=\"0\"/>" : "") + "</c:dLbls>";
        StringBuilder b = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><c:chartSpace "
                + SmartRvReport.NS + "><c:roundedCorners val=\"0\"/><c:chart>" + SmartRvReport.title(title) + "<c:plotArea><c:layout/>");
        if (doughnut) {
            b.append("<c:doughnutChart><c:varyColors val=\"1\"/><c:ser><c:idx val=\"0\"/><c:order val=\"0\"/>").append(ser);
            for (int i = 0; i < colors.size(); i++) {
                b.append("<c:dPt><c:idx val=\"").append(i).append("\"/><c:bubble3D val=\"0\"/><c:spPr><a:solidFill><a:srgbClr val=\"")
                        .append(colors.get(i)).append("\"/></a:solidFill><a:ln w=\"12700\"><a:solidFill><a:srgbClr val=\"FFFFFF\"/>"
                        + "</a:solidFill></a:ln></c:spPr></c:dPt>");
            }
            b.append(lbl).append(cat).append(val).append("</c:ser><c:firstSliceAng val=\"0\"/><c:holeSize val=\"55\"/></c:doughnutChart>");
            b.append("</c:plotArea><c:legend><c:legendPos val=\"b\"/><c:overlay val=\"0\"/></c:legend>");
        } else {
            b.append("<c:barChart><c:barDir val=\"").append(horiz ? "bar" : "col").append("\"/><c:grouping val=\"clustered\"/>"
                    + "<c:varyColors val=\"0\"/><c:ser><c:idx val=\"0\"/><c:order val=\"0\"/>").append(ser)
                    .append("<c:spPr><a:solidFill><a:srgbClr val=\"").append(barColor).append("\"/></a:solidFill></c:spPr>"
                            + "<c:invertIfNegative val=\"0\"/>").append(lbl).append(cat).append(val)
                    .append("</c:ser><c:gapWidth val=\"45\"/><c:axId val=\"10\"/><c:axId val=\"100\"/></c:barChart>");
            b.append("<c:catAx><c:axId val=\"10\"/><c:scaling><c:orientation val=\"").append(horiz ? "maxMin" : "minMax")
                    .append("\"/></c:scaling><c:delete val=\"0\"/><c:axPos val=\"").append(horiz ? "l" : "b")
                    .append("\"/><c:numFmt formatCode=\"General\" sourceLinked=\"0\"/><c:majorTickMark val=\"none\"/>"
                            + "<c:minorTickMark val=\"none\"/><c:tickLblPos val=\"low\"/><c:crossAx val=\"100\"/><c:crosses val=\"autoZero\"/>"
                            + "<c:auto val=\"1\"/><c:lblAlgn val=\"ctr\"/><c:lblOffset val=\"100\"/><c:noMultiLvlLbl val=\"1\"/></c:catAx>");
            b.append("<c:valAx><c:axId val=\"100\"/><c:scaling><c:orientation val=\"minMax\"/></c:scaling><c:delete val=\"0\"/>"
                    + "<c:axPos val=\"").append(horiz ? "t" : "l").append("\"/><c:majorGridlines><c:spPr><a:ln><a:solidFill>"
                    + "<a:srgbClr val=\"E5E7EB\"/></a:solidFill></a:ln></c:spPr></c:majorGridlines><c:numFmt formatCode=\"#,##0\" "
                    + "sourceLinked=\"0\"/><c:majorTickMark val=\"none\"/><c:minorTickMark val=\"none\"/><c:tickLblPos val=\"nextTo\"/>"
                    + "<c:crossAx val=\"10\"/><c:crosses val=\"autoZero\"/><c:crossBetween val=\"between\"/></c:valAx></c:plotArea>");
        }
        b.append(SmartRvReport.TAIL);
        return b.toString();
    }
}
