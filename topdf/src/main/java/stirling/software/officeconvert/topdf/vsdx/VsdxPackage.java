package stirling.software.officeconvert.topdf.vsdx;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;

import stirling.software.officeconvert.memory.Admission;
import stirling.software.officeconvert.topdf.io.BoundedZip;
import stirling.software.officeconvert.topdf.io.OfficeZip;
import stirling.software.officeconvert.topdf.io.SourceFile;
import stirling.software.officeconvert.topdf.xls.Parts;
import stirling.software.officeconvert.topdf.xls.Xml;

public final class VsdxPackage {

    public record Outcome(List<String> warnings, boolean lost) {
        public Outcome {
            warnings = List.copyOf(warnings);
        }
    }

    static final String A = "http://schemas.openxmlformats.org/drawingml/2006/main";

    static final String P = "http://schemas.openxmlformats.org/presentationml/2006/main";

    static final String R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";

    private static final String CT = "application/vnd.openxmlformats-officedocument.";

    private static final String RELS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/";

    private static final int MAX_TYPES_BYTES = 1 << 20;

    private static final int MAX_PAGES = 2000;

    private static final String CUT = "Some shapes nested too deeply or too many were left out";

    private static final long MIN_SIDE = 914_400L / 4;

    private static final long MAX_SIDE = 51_206_400L;

    private VsdxPackage() {}

    public static boolean is(Path file) {
        try (InputStream in = SourceFile.open(file)) {
            byte[] head = in.readNBytes(2);
            if (head.length < 2 || head[0] != 'P' || head[1] != 'K') {
                return false;
            }
        } catch (IOException e) {
            return false;
        }
        try (BoundedZip zip = BoundedZip.open(file)) {
            ZipEntry types = zip.entry("[Content_Types].xml");
            if (types == null || types.getSize() > MAX_TYPES_BYTES) {
                return false;
            }
            try (InputStream in = zip.open(types, MAX_TYPES_BYTES)) {
                String xml = new String(in.readNBytes(MAX_TYPES_BYTES), StandardCharsets.UTF_8);
                return xml.toLowerCase(Locale.ROOT).contains("application/vnd.ms-visio.");
            }
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    public static long estimate(long bytes) {
        long v = Admission.BASE_BYTES + Math.max(0, bytes) * 24;
        return v < 0 ? Long.MAX_VALUE : v;
    }

    public static Outcome write(Path source, OutputStream out) throws IOException {
        try (OfficeZip zip = OfficeZip.open(source)) {
            return write(new Drawing(zip), new Parts(out));
        }
    }

    private static Outcome write(Drawing drawing, Parts parts) throws IOException {
        List<String> warnings = new ArrayList<>();
        List<Drawing.Page> printed = new ArrayList<>();
        for (Drawing.Page p : drawing.pages) {
            if (!p.background()) {
                printed.add(p);
            }
        }
        if (printed.size() > MAX_PAGES) {
            warnings.add("Only the first " + MAX_PAGES + " pages were converted");
            printed = printed.subList(0, MAX_PAGES);
        }
        if (printed.isEmpty()) {
            throw new IOException("The Visio drawing has no pages to print");
        }
        double[] first = size(drawing, printed.get(0));
        long cx = clamp(first[0] * PageWriter.EMU);
        long cy = clamp(first[1] * PageWriter.EMU);
        Media media = new Media(parts);
        StringBuilder ids = new StringBuilder();
        StringBuilder presRels = new StringBuilder();
        StringBuilder types = new StringBuilder();
        int n = 0;
        for (Drawing.Page page : printed) {
            if (Thread.currentThread().isInterrupted()) {
                throw new java.io.InterruptedIOException("Conversion interrupted");
            }
            n++;
            Slide slide = new Slide();
            PageWriter writer = new PageWriter(drawing, media, slide);
            writer.page(page, transform(drawing, page, cx, cy), 0);
            if (writer.cut && !warnings.contains(CUT)) {
                warnings.add(CUT);
            }
            slide(parts, slide, n);
            presRels.append(rel("rId" + (n + 2), "slide", "slides/slide" + n + ".xml"));
            ids.append("<p:sldId id=\"").append(255 + n).append("\" r:id=\"rId").append(n + 2).append("\"/>");
            types.append(override("/ppt/slides/slide" + n + ".xml", CT + "presentationml.slide+xml"));
        }
        String ns = " xmlns:a=\"" + A + "\" xmlns:r=\"" + R + "\" xmlns:p=\"" + P + "\"";
        parts.put("ppt/presentation.xml", Xml.HEAD + "<p:presentation" + ns + "><p:sldMasterIdLst><p:sldMasterId"
                + " id=\"2147483648\" r:id=\"rId1\"/></p:sldMasterIdLst><p:sldIdLst>" + ids + "</p:sldIdLst><p:sldSz cx=\""
                + cx + "\" cy=\"" + cy + "\"/><p:notesSz cx=\"6858000\" cy=\"9144000\"/></p:presentation>");
        parts.put("ppt/_rels/presentation.xml.rels", rels(rel("rId1", "slideMaster", "slideMasters/slideMaster1.xml")
                + rel("rId2", "theme", "theme/theme1.xml") + presRels));
        Template.master(parts, ns, drawing.minorFont);
        StringBuilder ct = new StringBuilder(Xml.HEAD).append("<Types xmlns=\"http://schemas.openxmlformats.org/")
                .append("package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/")
                .append("vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"")
                .append("application/xml\"/>");
        for (Map.Entry<String, String> e : media.extensions.entrySet()) {
            ct.append("<Default Extension=\"").append(e.getKey()).append("\" ContentType=\"").append(e.getValue())
                    .append("\"/>");
        }
        ct.append(override("/ppt/presentation.xml", CT + "presentationml.presentation.main+xml"))
                .append(override("/ppt/slideMasters/slideMaster1.xml", CT + "presentationml.slideMaster+xml"))
                .append(override("/ppt/slideLayouts/slideLayout1.xml", CT + "presentationml.slideLayout+xml"))
                .append(override("/ppt/theme/theme1.xml", CT + "theme+xml")).append(types).append("</Types>");
        parts.put("[Content_Types].xml", ct.toString());
        parts.put("_rels/.rels", rels(rel("rId1", "officeDocument", "ppt/presentation.xml")));
        parts.finish();
        return new Outcome(warnings, !warnings.isEmpty());
    }

    private static double[] size(Drawing drawing, Drawing.Page page) {
        Sheet s = page.sheet();
        double scale = scale(drawing, s);
        double w = s == null ? 8.5 : drawing.cells.number(s, "PageWidth", 8.5);
        double h = s == null ? 11 : drawing.cells.number(s, "PageHeight", 11);
        return new double[] {Math.abs(w * scale), Math.abs(h * scale)};
    }

    private static double scale(Drawing drawing, Sheet s) {
        if (s == null) {
            return 1;
        }
        double page = drawing.cells.number(s, "PageScale", 1);
        double draw = drawing.cells.number(s, "DrawingScale", 1);
        double v = page > 0 && draw > 0 ? page / draw : 1;
        return v > 1e-6 && v < 1e6 ? v : 1;
    }

    private static Affine transform(Drawing drawing, Drawing.Page page, long cx, long cy) {
        double[] size = size(drawing, page);
        double scale = scale(drawing, page.sheet());
        double w = Math.max(1e-6, size[0] * PageWriter.EMU);
        double h = Math.max(1e-6, size[1] * PageWriter.EMU);
        double fit = Math.min(1, Math.min(cx / w, cy / h));
        if (!(fit > 0) || !Double.isFinite(fit)) {
            fit = 1;
        }
        double k = PageWriter.EMU * scale * fit;
        double offX = (cx - w * fit) / 2;
        double offY = (cy - h * fit) / 2;
        return new Affine(k, 0, 0, -k, offX, offY + h * fit);
    }

    private static long clamp(double emu) {
        if (!Double.isFinite(emu)) {
            return 914_400L * 11;
        }
        return Math.max(MIN_SIDE, Math.min(MAX_SIDE, Math.round(emu)));
    }

    private static void slide(Parts parts, Slide slide, int n) throws IOException {
        try (Parts.Part p = parts.open("ppt/slides/slide" + n + ".xml")) {
            p.write(Xml.HEAD);
            p.write("<p:sld xmlns:a=\"" + A + "\" xmlns:r=\"" + R + "\" xmlns:p=\"" + P + "\"><p:cSld><p:spTree>"
                    + "<p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr/>");
            p.write(slide.tree.toString());
            p.write("</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sld>");
        }
        StringBuilder r = new StringBuilder(rel("rId1", "slideLayout", "../slideLayouts/slideLayout1.xml"));
        for (Map.Entry<String, String> e : slide.images.entrySet()) {
            r.append(rel(e.getValue(), "image", e.getKey()));
        }
        parts.put("ppt/slides/_rels/slide" + n + ".xml.rels", rels(r.toString()));
    }

    static String rel(String id, String type, String target) {
        return "<Relationship Id=\"" + id + "\" Type=\"" + RELS + type + "\" Target=\"" + Xml.attr(target) + "\"/>";
    }

    static String rels(String body) {
        return Xml.HEAD + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + body + "</Relationships>";
    }

    private static String override(String part, String type) {
        return "<Override PartName=\"" + part + "\" ContentType=\"" + type + "\"/>";
    }
}
