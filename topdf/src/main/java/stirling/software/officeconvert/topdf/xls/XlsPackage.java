package stirling.software.officeconvert.topdf.xls;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.poi.EncryptedDocumentException;
import org.apache.poi.OldFileFormatException;
import org.apache.poi.hpsf.SummaryInformation;
import org.apache.poi.hssf.record.chart.ChartRecord;
import org.apache.poi.hssf.record.ContinueRecord;
import org.apache.poi.hssf.record.DrawingGroupRecord;
import org.apache.poi.hssf.record.DrawingRecord;
import org.apache.poi.hssf.record.Record;
import org.apache.poi.hssf.record.RecordBase;
import org.apache.poi.hssf.record.SupBookRecord;
import org.apache.poi.hssf.usermodel.HSSFPatriarch;
import org.apache.poi.hssf.usermodel.HSSFPictureData;
import org.apache.poi.hssf.usermodel.HSSFSheet;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.poifs.filesystem.DirectoryEntry;
import org.apache.poi.poifs.filesystem.DirectoryNode;
import org.apache.poi.poifs.filesystem.Entry;
import org.apache.poi.ss.util.CellRangeAddress;

import stirling.software.officeconvert.memory.Admission;
import stirling.software.officeconvert.topdf.io.ActiveContent;

/** A legacy Excel 97-2003 workbook (.xls, .xlt) rewritten as the SpreadsheetML package the XLSX renderer draws. Cells
 * keep their cached values (formulas are never evaluated), macros and embedded objects are never opened, and nothing
 * the workbook links to is followed. */
public final class XlsPackage {

    /** What the rewrite left out: warnings for the result, and whether content is missing. */
    public record Outcome(List<String> warnings, boolean lost) {
        public Outcome {
            warnings = List.copyOf(warnings);
        }
    }

    public static final String PASSWORD = "The document is password protected; remove the password and try again";

    private static final String CT = "application/vnd.openxmlformats-officedocument.";

    private static final int MAX_SHEETS = 4096;

    private static final int MAX_PICTURE_BYTES = 64 << 20;

    private XlsPackage() {}

    /** Whether an OLE2 file holds an Excel workbook stream (BIFF8, or an older "Book" stream that is refused later). */
    public static boolean isWorkbook(DirectoryNode root) {
        return root.hasEntryCaseInsensitive("Workbook") || root.hasEntryCaseInsensitive("Book");
    }

    /** The heap reading a workbook of this many bytes may need, for the shared memory gate. */
    public static long estimate(long bytes) {
        long v = Admission.BASE_BYTES + Math.max(0, bytes) * 12;
        return v < 0 ? Long.MAX_VALUE : v;
    }

    public static Outcome write(DirectoryNode root, OutputStream out) throws IOException {
        try (HSSFWorkbook wb = open(root)) {
            return new XlsPackage.Writer(wb, root, new Parts(out)).write();
        }
    }

    private static HSSFWorkbook open(DirectoryNode root) throws IOException {
        try {
            return new HSSFWorkbook(root, false);
        } catch (EncryptedDocumentException e) {
            throw new IOException(PASSWORD, e);
        } catch (OldFileFormatException e) {
            throw new IOException("The file is an Excel 5.0/95 or older workbook, which is not supported; save it as"
                    + " .xlsx", e);
        } catch (RuntimeException e) {
            throw new IOException("The Excel 97-2003 workbook could not be read: "
                    + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()), e);
        }
    }

    private static final class Writer {

        private final HSSFWorkbook wb;

        private final DirectoryNode root;

        private final Parts parts;

        private final List<String> warnings = new ArrayList<>();

        private final Map<Integer, String> media = new HashMap<>();

        private final Set<String> mediaTypes = new LinkedHashSet<>();

        private final BlipGuard guard = new BlipGuard();

        private boolean lost;

        private int charts;

        private int skipped;

        private int pict;

        private boolean drawingsRefused;

        private ChartStream chartStream;

        private int chartParts;

        Writer(HSSFWorkbook wb, DirectoryNode root, Parts parts) {
            this.wb = wb;
            this.root = root;
            this.parts = parts;
        }

        Outcome write() throws IOException {
            Strings strings = new Strings(wb);
            int sheets = Math.min(MAX_SHEETS, wb.getNumberOfSheets());
            boolean drawings = guard.safe(drawingGroup());
            if (!drawings) {
                drawingsRefused = true;
            }
            ChartFonts fonts = new ChartFonts(wb);
            chartStream = ChartStream.read(root, fonts, fonts.autoColors(false), fonts.autoColors(true));
            StringBuilder book = new StringBuilder(Xml.HEAD).append("<workbook xmlns=\"").append(Xml.MAIN)
                    .append("\" xmlns:r=\"").append(Xml.REL).append("\"><fileVersion appName=\"xl\"/>");
            if (wb.getInternalWorkbook().isUsing1904DateWindowing()) {
                book.append("<workbookPr date1904=\"1\"/>");
            }
            book.append("<sheets>");
            StringBuilder names = new StringBuilder();
            StringBuilder rels = new StringBuilder();
            StringBuilder types = new StringBuilder();
            for (int i = 0; i < sheets; i++) {
                SheetPart.stopIfInterrupted();
                HSSFSheet sheet = wb.getSheetAt(i);
                String name = wb.getSheetName(i);
                String state = wb.isSheetVeryHidden(i) ? " state=\"veryHidden\""
                        : wb.isSheetHidden(i) ? " state=\"hidden\"" : "";
                book.append("<sheet name=\"").append(Xml.attr(name)).append("\" sheetId=\"").append(i + 1).append('"')
                        .append(state).append(" r:id=\"rId").append(i + 1).append("\"/>");
                if (chartStream.chartSheets.contains(i)) {
                    rels.append("<Relationship Id=\"rId").append(i + 1).append("\" Type=\"").append(Xml.REL)
                            .append("/chartsheet\" Target=\"chartsheets/sheet").append(i + 1).append(".xml\"/>");
                    chartSheet(sheet, i + 1, types);
                    continue;
                }
                rels.append("<Relationship Id=\"rId").append(i + 1).append("\" Type=\"").append(Xml.REL)
                        .append("/worksheet\" Target=\"worksheets/sheet").append(i + 1).append(".xml\"/>");
                types.append("<Override PartName=\"/xl/worksheets/sheet").append(i + 1).append(".xml\" ContentType=\"")
                        .append(CT).append("spreadsheetml.worksheet+xml\"/>");
                SheetPart part = new SheetPart(sheet);
                String drawing = drawings ? drawing(sheet, part, i + 1, types) : null;
                int drawn = drawing == null ? 0 : chartStream.embedded.getOrDefault(i, List.of()).size();
                charts += Math.max(0, charts(sheet) - drawn);
                boolean complete;
                try (Parts.Part p = parts.open("xl/worksheets/sheet" + (i + 1) + ".xml")) {
                    complete = part.write(parts, p, strings, drawing);
                }
                if (!complete) {
                    lost = true;
                    warnings.add("Sheet " + name + " is too large; only its first rows were converted");
                }
                if (part.skippedRows > 0) {
                    lost = true;
                    warnings.add("Sheet " + name + ": " + part.skippedRows + " rows could not be read and were left out");
                }
                if (drawing != null) {
                    parts.put("xl/worksheets/_rels/sheet" + (i + 1) + ".xml.rels", Xml.HEAD + "<Relationships xmlns=\""
                            + Xml.PKG_REL + "\"><Relationship Id=\"" + drawing + "\" Type=\"" + Xml.REL
                            + "/drawing\" Target=\"../drawings/drawing" + (i + 1) + ".xml\"/></Relationships>");
                }
                names.append(names(i, name, sheet));
            }
            if (wb.getNumberOfSheets() > sheets) {
                lost = true;
                warnings.add("Only the first " + sheets + " sheets were converted");
            }
            book.append("</sheets>");
            if (!names.isEmpty()) {
                book.append("<definedNames>").append(names).append("</definedNames>");
            }
            book.append("</workbook>");
            parts.put("xl/workbook.xml", book.toString());
            parts.put("xl/styles.xml", StylesPart.write(wb));
            strings.write(parts);
            int n = sheets;
            rels.append("<Relationship Id=\"rId").append(n + 1).append("\" Type=\"").append(Xml.REL)
                    .append("/styles\" Target=\"styles.xml\"/><Relationship Id=\"rId").append(n + 2)
                    .append("\" Type=\"").append(Xml.REL).append("/sharedStrings\" Target=\"sharedStrings.xml\"/>");
            parts.put("xl/_rels/workbook.xml.rels", Xml.HEAD + "<Relationships xmlns=\"" + Xml.PKG_REL + "\">" + rels
                    + "</Relationships>");
            parts.put("docProps/core.xml", core());
            parts.put("_rels/.rels", Xml.HEAD + "<Relationships xmlns=\"" + Xml.PKG_REL + "\"><Relationship Id=\"rId1\""
                    + " Type=\"" + Xml.REL + "/officeDocument\" Target=\"xl/workbook.xml\"/><Relationship Id=\"rId2\""
                    + " Type=\"http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties\""
                    + " Target=\"docProps/core.xml\"/></Relationships>");
            parts.put("[Content_Types].xml", contentTypes(types));
            parts.finish();
            return new Outcome(finalWarnings(), lost);
        }

        private List<String> finalWarnings() {
            List<String> out = new ArrayList<>(ActiveContent.describe(activeContent()));
            if (drawingsRefused) {
                lost = true;
                out.add("Pictures and shapes were left out: a picture in the workbook would inflate past the"
                        + " converter's limit");
            }
            if (charts > 0) {
                out.add("Some charts could not be drawn (" + charts + " left out)");
            }
            if (pict > 0) {
                out.add("Macintosh PICT pictures are not supported (" + pict + " left out)");
            }
            if (skipped > 0) {
                out.add("Some drawing objects could not be drawn (" + skipped + " left out)");
            }
            out.addAll(warnings);
            return out;
        }

        private Map<ActiveContent.Kind, Set<String>> activeContent() {
            Map<ActiveContent.Kind, Set<String>> found = new EnumMap<>(ActiveContent.Kind.class);
            for (Entry e : root) {
                String name = e.getName();
                if (name.equalsIgnoreCase("_VBA_PROJECT_CUR") || name.equalsIgnoreCase("_VBA_PROJECT")) {
                    found.computeIfAbsent(ActiveContent.Kind.MACRO, k -> new LinkedHashSet<>()).add(name);
                } else if (name.equalsIgnoreCase("ObjectPool") || name.equalsIgnoreCase("ObjPool")) {
                    if (e instanceof DirectoryEntry d) {
                        for (Entry o : d) {
                            found.computeIfAbsent(ActiveContent.Kind.OLE_OBJECT, k -> new LinkedHashSet<>())
                                    .add(o.getName());
                        }
                    }
                } else if (name.startsWith("MBD") && e instanceof DirectoryEntry) {
                    found.computeIfAbsent(ActiveContent.Kind.EMBEDDED_PACKAGE, k -> new LinkedHashSet<>()).add(name);
                }
            }
            int links = 0;
            for (Record r : wb.getInternalWorkbook().getRecords()) {
                if (r instanceof SupBookRecord s && s.isExternalReferences()) {
                    found.computeIfAbsent(ActiveContent.Kind.EXTERNAL_WORKBOOK, k -> new LinkedHashSet<>())
                            .add("link" + links++);
                }
            }
            return found;
        }

        private byte[] drawingGroup() {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            for (Record r : wb.getInternalWorkbook().getRecords()) {
                if (r instanceof DrawingGroupRecord d) {
                    out.writeBytes(d.getRawData());
                }
            }
            return out.toByteArray();
        }

        private static byte[] sheetDrawing(HSSFSheet sheet) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            boolean drawing = false;
            for (RecordBase r : sheet.getSheet().getRecords()) {
                if (r instanceof DrawingRecord d) {
                    out.writeBytes(d.getRecordData());
                    drawing = true;
                } else if (drawing && r instanceof ContinueRecord c) {
                    out.writeBytes(c.getData());
                }
            }
            return drawing ? out.toByteArray() : null;
        }

        private static int charts(HSSFSheet sheet) {
            int n = 0;
            for (RecordBase r : sheet.getSheet().getRecords()) {
                if (r instanceof ChartRecord) {
                    n++;
                }
            }
            return n;
        }

        private void chartSheet(HSSFSheet sheet, int number, StringBuilder types) throws IOException {
            types.append("<Override PartName=\"/xl/chartsheets/sheet").append(number).append(".xml\" ContentType=\"")
                    .append(CT).append("spreadsheetml.chartsheet+xml\"/>");
            BiffChart chart = chartStream.sheetCharts.get(number - 1);
            StringBuilder xml = new StringBuilder(Xml.HEAD).append("<chartsheet xmlns=\"").append(Xml.MAIN)
                    .append("\" xmlns:r=\"").append(Xml.REL).append("\"><sheetViews><sheetView workbookViewId=\"0\"/>")
                    .append("</sheetViews>").append(new SheetPart(sheet).chartSetup());
            if (chart != null) {
                String target = chartPart(chart, types);
                xml.append("<drawing r:id=\"rId1\"/>");
                parts.put("xl/chartsheets/_rels/sheet" + number + ".xml.rels", Xml.HEAD + "<Relationships xmlns=\""
                        + Xml.PKG_REL + "\"><Relationship Id=\"rId1\" Type=\"" + Xml.REL
                        + "/drawing\" Target=\"../drawings/drawing" + number + ".xml\"/></Relationships>");
                parts.put("xl/drawings/drawing" + number + ".xml", DrawingPart.absoluteChart("rId1"));
                parts.put("xl/drawings/_rels/drawing" + number + ".xml.rels", Xml.HEAD + "<Relationships xmlns=\""
                        + Xml.PKG_REL + "\"><Relationship Id=\"rId1\" Type=\"" + Xml.REL + "/chart\" Target=\""
                        + target + "\"/></Relationships>");
                types.append("<Override PartName=\"/xl/drawings/drawing").append(number)
                        .append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.drawing+xml\"/>");
            } else {
                charts++;
            }
            parts.put("xl/chartsheets/sheet" + number + ".xml", xml.append("</chartsheet>").toString());
        }

        private String chartPart(BiffChart chart, StringBuilder types) throws IOException {
            String name = "chart" + ++chartParts + ".xml";
            parts.put("xl/charts/" + name, ChartXml.write(chart));
            types.append("<Override PartName=\"/xl/charts/").append(name).append("\" ContentType=\"")
                    .append("application/vnd.openxmlformats-officedocument.drawingml.chart+xml\"/>");
            return "../charts/" + name;
        }

        private String drawing(HSSFSheet sheet, SheetPart geometry, int number, StringBuilder types)
                throws IOException {
            byte[] escher = sheetDrawing(sheet);
            if (escher == null) {
                return null;
            }
            if (!guard.safe(escher)) {
                drawingsRefused = true;
                return null;
            }
            DrawingPart d = new DrawingPart(wb, geometry, media);
            List<ChartStream.Embedded> embedded = chartStream.embedded.getOrDefault(number - 1, List.of());
            List<String> chartTargets = new ArrayList<>();
            try {
                HSSFPatriarch patriarch = sheet.getDrawingPatriarch();
                boolean shapes = patriarch != null && d.read(patriarch, this::picture);
                for (ChartStream.Embedded e : embedded) {
                    int[] a = e.anchor();
                    if (a[0] == a[4] && a[1] == a[5] || a[2] == a[6] && a[3] == a[7]) {
                        continue;
                    }
                    chartTargets.add(chartPart(e.chart(), types));
                    d.chart(e.anchor(), "rIdChart" + chartTargets.size());
                }
                if (!shapes && embedded.isEmpty()) {
                    return null;
                }
            } catch (DrawingPart.Stop e) {
                throw (IOException) e.getCause();
            } catch (RuntimeException e) {
                warnings.add("The drawing on sheet " + sheet.getSheetName() + " could not be read");
                return null;
            } finally {
                skipped += d.skipped;
                pict += d.pict;
            }
            StringBuilder rels = new StringBuilder(Xml.HEAD).append("<Relationships xmlns=\"").append(Xml.PKG_REL)
                    .append("\">");
            List<String> targets = d.relationships();
            for (int k = 0; k < targets.size(); k++) {
                rels.append("<Relationship Id=\"rId").append(k + 1).append("\" Type=\"").append(Xml.REL)
                        .append("/image\" Target=\"").append(targets.get(k)).append("\"/>");
            }
            for (int k = 0; k < chartTargets.size(); k++) {
                rels.append("<Relationship Id=\"rIdChart").append(k + 1).append("\" Type=\"").append(Xml.REL)
                        .append("/chart\" Target=\"").append(chartTargets.get(k)).append("\"/>");
            }
            parts.put("xl/drawings/drawing" + number + ".xml", d.xml());
            parts.put("xl/drawings/_rels/drawing" + number + ".xml.rels", rels.append("</Relationships>").toString());
            types.append("<Override PartName=\"/xl/drawings/drawing").append(number)
                    .append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.drawing+xml\"/>");
            return "rIdDrawing";
        }

        private String picture(int index, HSSFPictureData data) {
            String ext = data.suggestFileExtension();
            byte[] bytes = data.getData();
            if (bytes == null || bytes.length == 0 || bytes.length > MAX_PICTURE_BYTES || parts.full(bytes.length)) {
                skipped++;
                return null;
            }
            if (ext.equals("dib")) {
                bytes = bitmapFile(bytes);
                ext = "bmp";
            }
            if (ext.isEmpty()) {
                ext = "bin";
            }
            String name = "image" + (media.size() + 1) + "." + ext;
            try {
                parts.put("xl/media/" + name, bytes);
            } catch (IOException e) {
                throw new DrawingPart.Stop(e);
            }
            mediaTypes.add(ext);
            media.put(index, "../media/" + name);
            return "../media/" + name;
        }

        // A DIB is a BMP file without its 14-byte file header
        private static byte[] bitmapFile(byte[] dib) {
            int header = dib.length >= 4 ? dib[0] & 0xFF | (dib[1] & 0xFF) << 8 : 40;
            int bits = dib.length >= 16 ? dib[14] & 0xFF : 24;
            int colors = dib.length >= 36 ? dib[32] & 0xFF | (dib[33] & 0xFF) << 8 : 0;
            if (colors == 0 && bits <= 8) {
                colors = 1 << bits;
            }
            int offset = 14 + header + colors * 4;
            int size = dib.length + 14;
            byte[] out = new byte[size];
            out[0] = 'B';
            out[1] = 'M';
            put32(out, 2, size);
            put32(out, 10, offset);
            System.arraycopy(dib, 0, out, 14, dib.length);
            return out;
        }

        private static void put32(byte[] b, int at, int v) {
            b[at] = (byte) v;
            b[at + 1] = (byte) (v >> 8);
            b[at + 2] = (byte) (v >> 16);
            b[at + 3] = (byte) (v >> 24);
        }

        private String names(int index, String sheetName, HSSFSheet sheet) {
            StringBuilder b = new StringBuilder();
            String area = null;
            try {
                area = wb.getPrintArea(index);
            } catch (RuntimeException ignored) {
                area = null;
            }
            if (area != null && !area.isEmpty()) {
                b.append("<definedName name=\"_xlnm.Print_Area\" localSheetId=\"").append(index).append("\">")
                        .append(Xml.attr(area)).append("</definedName>");
            }
            List<String> titles = new ArrayList<>();
            String quoted = "'" + sheetName.replace("'", "''") + "'!";
            try {
                CellRangeAddress rows = sheet.getRepeatingRows();
                CellRangeAddress cols = sheet.getRepeatingColumns();
                if (cols != null && cols.getFirstColumn() >= 0) {
                    titles.add(quoted + "$" + column(cols.getFirstColumn()) + ":$" + column(cols.getLastColumn()));
                }
                if (rows != null && rows.getFirstRow() >= 0) {
                    titles.add(quoted + "$" + (rows.getFirstRow() + 1) + ":$" + (rows.getLastRow() + 1));
                }
            } catch (RuntimeException ignored) {
                titles.clear();
            }
            if (!titles.isEmpty()) {
                b.append("<definedName name=\"_xlnm.Print_Titles\" localSheetId=\"").append(index).append("\">")
                        .append(Xml.attr(String.join(",", titles))).append("</definedName>");
            }
            return b.toString();
        }

        private static String column(int c) {
            return org.apache.poi.ss.util.CellReference.convertNumToColString(Math.max(0, Math.min(255, c)));
        }

        private String core() {
            StringBuilder b = new StringBuilder(Xml.HEAD).append("<cp:coreProperties xmlns:cp=\"http://schemas."
                    + "openxmlformats.org/package/2006/metadata/core-properties\" xmlns:dc=\"http://purl.org/dc/elements/"
                    + "1.1/\">");
            SummaryInformation info = null;
            try {
                info = wb.getSummaryInformation();
            } catch (RuntimeException ignored) {
                info = null;
            }
            if (info != null) {
                element(b, "dc:title", info.getTitle());
                element(b, "dc:subject", info.getSubject());
                element(b, "dc:creator", info.getAuthor());
                element(b, "cp:keywords", info.getKeywords());
            }
            return b.append("</cp:coreProperties>").toString();
        }

        private static void element(StringBuilder b, String tag, String value) {
            if (value != null && !value.isBlank()) {
                b.append('<').append(tag).append('>').append(Xml.attr(value)).append("</").append(tag).append('>');
            }
        }

        private String contentTypes(StringBuilder overrides) {
            StringBuilder b = new StringBuilder(Xml.HEAD).append("<Types xmlns=\"http://schemas.openxmlformats.org/"
                    + "package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/"
                    + "vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\""
                    + "application/xml\"/>");
            for (String ext : mediaTypes) {
                b.append("<Default Extension=\"").append(ext).append("\" ContentType=\"").append(switch (ext) {
                    case "png" -> "image/png";
                    case "jpeg" -> "image/jpeg";
                    case "emf" -> "image/x-emf";
                    case "wmf" -> "image/x-wmf";
                    case "bmp" -> "image/bmp";
                    case "tif" -> "image/tiff";
                    default -> "application/octet-stream";
                }).append("\"/>");
            }
            return b.append("<Override PartName=\"/xl/workbook.xml\" ContentType=\"").append(CT)
                    .append("spreadsheetml.sheet.main+xml\"/><Override PartName=\"/xl/styles.xml\" ContentType=\"")
                    .append(CT).append("spreadsheetml.styles+xml\"/><Override PartName=\"/xl/sharedStrings.xml\""
                            + " ContentType=\"").append(CT).append("spreadsheetml.sharedStrings+xml\"/>")
                    .append("<Override PartName=\"/docProps/core.xml\" ContentType=\"application/"
                            + "vnd.openxmlformats-package.core-properties+xml\"/>")
                    .append(overrides).append("</Types>").toString();
        }
    }

}
