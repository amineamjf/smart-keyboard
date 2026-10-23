package com.smartkeyboard.releve;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.parsers.SAXParserFactory;
import org.xml.sax.Attributes;
import org.xml.sax.helpers.DefaultHandler;

/** Lit un fichier .xlsx, .csv ou texte tabule et retourne ses feuilles sous forme de lignes. */
public final class TableReader {

    private TableReader() {
    }

    public static List<List<String[]>> read(InputStream in) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        byte[] data = bo.toByteArray();
        List<List<String[]>> out = new ArrayList<>();
        if (data.length > 4 && data[0] == 'P' && data[1] == 'K') {
            readXlsx(data, out);
        } else if (data.length > 8 && (data[0] & 0xFF) == 0xD0 && (data[1] & 0xFF) == 0xCF
                && (data[2] & 0xFF) == 0x11 && (data[3] & 0xFF) == 0xE0) {
            readXls(data, out);
        } else if (looksHtml(data)) {
            out.add(readHtml(data));
        } else {
            out.add(readText(data));
        }
        return out;
    }

    // ---------- xls (Excel 97-2003) ----------

    private static void readXls(byte[] data, List<List<String[]>> out) throws Exception {
        jxl.WorkbookSettings ws = new jxl.WorkbookSettings();
        ws.setEncoding("Cp1252");
        ws.setGCDisabled(true);
        ws.setSuppressWarnings(true);
        jxl.Workbook wb = jxl.Workbook.getWorkbook(new ByteArrayInputStream(data), ws);
        try {
            for (int si = 0; si < wb.getNumberOfSheets(); si++) {
                jxl.Sheet sh = wb.getSheet(si);
                ArrayList<String[]> rows = new ArrayList<>();
                int nc = sh.getColumns();
                for (int r = 0; r < sh.getRows(); r++) {
                    String[] row = new String[nc];
                    for (int c = 0; c < nc; c++) {
                        jxl.Cell cell = sh.getCell(c, r);
                        String v;
                        if (cell.getType() == jxl.CellType.NUMBER
                                && cell instanceof jxl.NumberCell) {
                            double d = ((jxl.NumberCell) cell).getValue();
                            String raw = d == Math.rint(d) && Math.abs(d) < 1e15
                                    ? String.valueOf((long) d) : String.valueOf(d);
                            v = Meters.cleanNumber(raw);
                        } else {
                            v = cell.getContents();
                        }
                        row[c] = v == null ? "" : v;
                    }
                    rows.add(row);
                }
                out.add(rows);
            }
        } finally {
            wb.close();
        }
        if (out.isEmpty()) throw new Exception("Aucune feuille dans le fichier");
    }

    // ---------- html deguise en .xls ----------

    private static boolean looksHtml(byte[] data) {
        int n = Math.min(data.length, 2048);
        String head = new String(data, 0, n, java.nio.charset.StandardCharsets.ISO_8859_1)
                .toLowerCase(java.util.Locale.ROOT);
        return head.contains("<table") || head.contains("<html");
    }

    private static List<String[]> readHtml(byte[] data) {
        String s = new String(data, java.nio.charset.StandardCharsets.UTF_8);
        ArrayList<String[]> rows = new ArrayList<>();
        java.util.regex.Pattern trp = java.util.regex.Pattern.compile(
                "<tr[^>]*>(.*?)</tr>", java.util.regex.Pattern.CASE_INSENSITIVE
                        | java.util.regex.Pattern.DOTALL);
        java.util.regex.Pattern tdp = java.util.regex.Pattern.compile(
                "<t[dh][^>]*>(.*?)</t[dh]>", java.util.regex.Pattern.CASE_INSENSITIVE
                        | java.util.regex.Pattern.DOTALL);
        java.util.regex.Matcher tr = trp.matcher(s);
        while (tr.find()) {
            ArrayList<String> cells = new ArrayList<>();
            java.util.regex.Matcher td = tdp.matcher(tr.group(1));
            while (td.find()) {
                String v = td.group(1).replaceAll("<[^>]*>", "")
                        .replace("&nbsp;", " ").replace("&amp;", "&")
                        .replace("&lt;", "<").replace("&gt;", ">").trim();
                cells.add(v);
            }
            if (!cells.isEmpty()) rows.add(cells.toArray(new String[0]));
        }
        return rows;
    }

    // ---------- xlsx ----------

    private static void readXlsx(byte[] data, List<List<String[]>> out) throws Exception {
        byte[] sstData = null;
        TreeMap<String, byte[]> sheets = new TreeMap<>();
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(data))) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                String name = e.getName();
                boolean want = name.equals("xl/sharedStrings.xml")
                        || (name.startsWith("xl/worksheets/sheet") && name.endsWith(".xml"));
                if (!want) continue;
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] b = new byte[8192];
                int n;
                while ((n = zin.read(b)) > 0) bo.write(b, 0, n);
                if (name.equals("xl/sharedStrings.xml")) sstData = bo.toByteArray();
                else sheets.put(name, bo.toByteArray());
            }
        }
        List<String> sst = sstData == null ? new ArrayList<String>() : sharedStrings(sstData);
        for (Map.Entry<String, byte[]> en : sheets.entrySet()) {
            out.add(sheet(en.getValue(), sst));
        }
        if (out.isEmpty()) throw new Exception("Aucune feuille dans le fichier");
    }

    private static List<String> sharedStrings(byte[] data) throws Exception {
        final ArrayList<String> out = new ArrayList<>();
        SAXParserFactory f = SAXParserFactory.newInstance();
        f.newSAXParser().parse(new ByteArrayInputStream(data), new DefaultHandler() {
            StringBuilder cur = null;
            boolean inT = false;
            boolean inPh = false;

            @Override
            public void startElement(String uri, String ln, String qn, Attributes a) {
                if (qn.equals("si")) cur = new StringBuilder();
                else if (qn.equals("rPh")) inPh = true;
                else if (qn.equals("t") && cur != null && !inPh) inT = true;
            }

            @Override
            public void characters(char[] ch, int s, int l) {
                if (inT && cur != null) cur.append(ch, s, l);
            }

            @Override
            public void endElement(String uri, String ln, String qn) {
                if (qn.equals("t")) inT = false;
                else if (qn.equals("rPh")) inPh = false;
                else if (qn.equals("si")) {
                    out.add(cur == null ? "" : cur.toString());
                    cur = null;
                }
            }
        });
        return out;
    }

    private static int colIndex(String ref) {
        int c = 0;
        for (int i = 0; i < ref.length(); i++) {
            char ch = ref.charAt(i);
            if (ch >= 'A' && ch <= 'Z') c = c * 26 + (ch - 'A' + 1);
            else if (ch >= 'a' && ch <= 'z') c = c * 26 + (ch - 'a' + 1);
            else break;
        }
        return c - 1;
    }

    private static List<String[]> sheet(byte[] data, final List<String> sst) throws Exception {
        final ArrayList<String[]> rows = new ArrayList<>();
        SAXParserFactory f = SAXParserFactory.newInstance();
        f.newSAXParser().parse(new ByteArrayInputStream(data), new DefaultHandler() {
            ArrayList<String> cells = null;
            String ref = "";
            String type = "";
            StringBuilder txt = new StringBuilder();
            boolean inV = false;
            boolean inT = false;
            int nextCol = 0;

            @Override
            public void startElement(String uri, String ln, String qn, Attributes a) {
                if (qn.equals("row")) {
                    cells = new ArrayList<>();
                    nextCol = 0;
                } else if (qn.equals("c")) {
                    ref = a.getValue("r");
                    type = a.getValue("t");
                    if (type == null) type = "";
                    txt.setLength(0);
                } else if (qn.equals("v")) {
                    inV = true;
                } else if (qn.equals("t")) {
                    inT = true;
                }
            }

            @Override
            public void characters(char[] ch, int s, int l) {
                if (inV || inT) txt.append(ch, s, l);
            }

            @Override
            public void endElement(String uri, String ln, String qn) {
                if (qn.equals("v")) inV = false;
                else if (qn.equals("t")) inT = false;
                else if (qn.equals("c") && cells != null) {
                    int col = ref != null && ref.length() > 0 ? colIndex(ref) : nextCol;
                    if (col < 0) col = nextCol;
                    String val;
                    String raw = txt.toString();
                    if (type.equals("s")) {
                        try {
                            val = sst.get(Integer.parseInt(raw.trim()));
                        } catch (Exception e) {
                            val = "";
                        }
                    } else if (type.equals("inlineStr") || type.equals("str")) {
                        val = raw;
                    } else {
                        val = Meters.cleanNumber(raw);
                    }
                    while (cells.size() <= col) cells.add("");
                    cells.set(col, val);
                    nextCol = col + 1;
                } else if (qn.equals("row") && cells != null) {
                    rows.add(cells.toArray(new String[0]));
                    cells = null;
                }
            }
        });
        return rows;
    }

    // ---------- csv / texte ----------

    private static List<String[]> readText(byte[] data) {
        String s = new String(data, java.nio.charset.StandardCharsets.UTF_8);
        if (s.startsWith("﻿")) s = s.substring(1);
        int nl = s.indexOf('\n');
        String first = nl < 0 ? s : s.substring(0, nl);
        char delim = '\t';
        int tabs = count(first, '\t');
        int semi = count(first, ';');
        int comma = count(first, ',');
        if (tabs >= semi && tabs >= comma) delim = '\t';
        else if (semi >= comma) delim = ';';
        else delim = ',';

        ArrayList<String[]> rows = new ArrayList<>();
        ArrayList<String> cur = new ArrayList<>();
        StringBuilder f = new StringBuilder();
        boolean q = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (q) {
                if (c == '"') {
                    if (i + 1 < s.length() && s.charAt(i + 1) == '"') {
                        f.append('"');
                        i++;
                    } else {
                        q = false;
                    }
                } else {
                    f.append(c);
                }
            } else if (c == '"') {
                q = true;
            } else if (c == delim) {
                cur.add(f.toString());
                f.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < s.length() && s.charAt(i + 1) == '\n') i++;
                cur.add(f.toString());
                f.setLength(0);
                rows.add(cur.toArray(new String[0]));
                cur = new ArrayList<>();
            } else {
                f.append(c);
            }
        }
        if (f.length() > 0 || !cur.isEmpty()) {
            cur.add(f.toString());
            rows.add(cur.toArray(new String[0]));
        }
        return rows;
    }

    private static int count(String s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == c) n++;
        return n;
    }
}
