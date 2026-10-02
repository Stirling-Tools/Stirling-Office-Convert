package stirling.software.officeconvert.topdf.text;

import java.io.IOException;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.zip.Deflater;
import java.util.zip.ZipOutputStream;

import stirling.software.officeconvert.memory.Admission;

public final class TextPackage {

    static final long BODY_LIMIT = 400L << 20;

    static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";

    private static final String FONT = "Liberation Mono";

    private static final String TYPES = "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
            + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
            + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
            + "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument"
            + ".wordprocessingml.document.main+xml\"/>"
            + "<Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument"
            + ".wordprocessingml.styles+xml\"/>"
            + "<Override PartName=\"/word/settings.xml\" ContentType=\"application/vnd.openxmlformats-officedocument"
            + ".wordprocessingml.settings+xml\"/></Types>";

    private static final String ROOT_RELS = "<Relationships xmlns=\"" + Parts.PACKAGE_RELS + "\">"
            + "<Relationship Id=\"rId1\" Type=\"" + Parts.RELS + "/officeDocument\" Target=\"word/document.xml\"/>"
            + "</Relationships>";

    private static final String DOCUMENT_RELS = "<Relationships xmlns=\"" + Parts.PACKAGE_RELS + "\">"
            + "<Relationship Id=\"rId1\" Type=\"" + Parts.RELS + "/styles\" Target=\"styles.xml\"/>"
            + "<Relationship Id=\"rId2\" Type=\"" + Parts.RELS + "/settings\" Target=\"settings.xml\"/>"
            + "</Relationships>";

    private static final String STYLES = "<w:styles xmlns:w=\"" + W + "\"><w:docDefaults><w:rPrDefault><w:rPr>"
            + "<w:rFonts w:ascii=\"" + FONT + "\" w:hAnsi=\"" + FONT + "\" w:eastAsia=\"" + FONT + "\" w:cs=\""
            + FONT + "\"/><w:sz w:val=\"20\"/><w:szCs w:val=\"20\"/><w:lang w:val=\"en-US\" w:eastAsia=\"en-US\""
            + " w:bidi=\"ar-SA\"/></w:rPr></w:rPrDefault><w:pPrDefault><w:pPr><w:widowControl w:val=\"0\"/>"
            + "<w:spacing w:before=\"0\" w:after=\"0\" w:line=\"227\" w:lineRule=\"exact\"/></w:pPr></w:pPrDefault>"
            + "</w:docDefaults><w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\">"
            + "<w:name w:val=\"Normal\"/></w:style></w:styles>";

    private static final String SETTINGS = "<w:settings xmlns:w=\"" + W + "\"><w:defaultTabStop w:val=\"709\"/>"
            + "</w:settings>";

    private static final String SECTION = "<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/><w:pgMar w:top=\"1120\""
            + " w:right=\"1134\" w:bottom=\"1134\" w:left=\"1134\" w:header=\"0\" w:footer=\"0\" w:gutter=\"0\"/>"
            + "</w:sectPr>";

    private TextPackage() {}

    public static long estimate(long bytes) {
        long v = Admission.BASE_BYTES + Math.max(0, bytes) * 2;
        return v < 0 ? Long.MAX_VALUE : v;
    }

    public static Converted write(Path source, OutputStream out, int maxPages) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(out, "out");
        TextEncoding encoding = TextEncoding.detect(source);
        ZipOutputStream zip = new ZipOutputStream(Parts.keepOpen(out));
        zip.setLevel(Deflater.BEST_SPEED);
        Parts.put(zip, "[Content_Types].xml", TYPES);
        Parts.put(zip, "_rels/.rels", ROOT_RELS);
        Parts.put(zip, "word/_rels/document.xml.rels", DOCUMENT_RELS);
        Parts.put(zip, "word/styles.xml", STYLES);
        Parts.put(zip, "word/settings.xml", SETTINGS);
        long budget = maxPages > 0 ? (maxPages + 1L) * TextBody.LINES_PER_PAGE : Long.MAX_VALUE;
        TextBody body;
        try (Reader in = encoding.open(source)) {
            Writer w = Parts.open(zip, "word/document.xml");
            w.write("<w:document xmlns:w=\"" + W + "\"><w:body>");
            body = new TextBody(w, budget, BODY_LIMIT);
            body.read(new TextScanner(in));
            w.write(SECTION + "</w:body></w:document>");
            w.flush();
        }
        zip.closeEntry();
        zip.finish();
        zip.flush();
        List<String> warnings = new ArrayList<>();
        if (body.cut()) {
            warnings.add(maxPages > 0 && body.lines() >= budget
                    ? "Only the start of the text was converted: the rest is past the page limit of " + maxPages + " pages"
                    : "Only the start of the text was converted: the file is too large");
        }
        return new Converted(warnings, body.cut());
    }
}
