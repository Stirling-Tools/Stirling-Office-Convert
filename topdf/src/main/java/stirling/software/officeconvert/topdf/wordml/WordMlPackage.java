package stirling.software.officeconvert.topdf.wordml;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import stirling.software.officeconvert.memory.Admission;
import stirling.software.officeconvert.topdf.flat.Sniff;
import stirling.software.officeconvert.topdf.io.SecureXml;
import stirling.software.officeconvert.topdf.xls.Parts;

public final class WordMlPackage {

    public record Outcome(List<String> warnings, boolean lost) {
        public Outcome {
            warnings = List.copyOf(warnings);
        }
    }

    private static final String RELS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/";

    private static final String[][] CORE = {{"Title", "dc:title"}, {"Subject", "dc:subject"},
        {"Author", "dc:creator"}, {"Keywords", "cp:keywords"}, {"Description", "dc:description"},
        {"LastAuthor", "cp:lastModifiedBy"}, {"Created", "dcterms:created"}, {"LastSaved", "dcterms:modified"}};

    private WordMlPackage() {}

    public static boolean is(Path file) {
        return Sniff.root(file, Names.W2003, "wordDocument");
    }

    public static long estimate(long bytes) {
        long v = Admission.BASE_BYTES + Math.max(0, bytes) * 4;
        return v < 0 ? Long.MAX_VALUE : v;
    }

    public static Outcome write(Path source, OutputStream out) throws IOException {
        Parts zip = new Parts(out);
        Media media = new Media(zip);
        Transform t;
        try (InputStream in = Files.newInputStream(source)) {
            XMLStreamReader r = SecureXml.reader(in);
            try {
                t = new Transform(r, media);
                t.run();
            } finally {
                r.close();
            }
        } catch (XMLStreamException e) {
            throw new IOException("The Word 2003 XML document is not well-formed: " + e.getMessage(), e);
        }
        if (!t.body) {
            throw new IOException("The Word 2003 XML document has no body");
        }
        assemble(t, zip);
        List<String> warnings = new ArrayList<>();
        if (media.dropped) {
            warnings.add("Some pictures could not be read and were left out");
        }
        if (t.lost) {
            warnings.add("Some headers and footers were left out");
        }
        return new Outcome(warnings, media.dropped || t.lost);
    }

    private static void assemble(Transform t, Parts zip) throws IOException {
        StringBuilder types = new StringBuilder(Esc.HEAD)
                .append("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">")
                .append("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.")
                .append("relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/>");
        for (String[] d : new String[][] {{"png", "image/png"}, {"jpeg", "image/jpeg"}, {"gif", "image/gif"},
            {"bmp", "image/bmp"}, {"tiff", "image/tiff"}, {"emf", "image/x-emf"}, {"wmf", "image/x-wmf"},
            {"pict", "image/pict"}}) {
            types.append("<Default Extension=\"").append(d[0]).append("\" ContentType=\"").append(d[1]).append("\"/>");
        }
        Part doc = t.document;
        put(zip, types, doc);
        if (!t.defaultFonts.isEmpty()) {
            t.styles.xml.insert(0, "<w:docDefaults><w:rPrDefault><w:rPr>" + t.defaultFonts
                    + "</w:rPr></w:rPrDefault></w:docDefaults>");
        }
        for (Part p : List.of(t.styles, t.numbering, t.settings, t.fonts, t.footnotes, t.endnotes, t.comments)) {
            if (!p.xml.isEmpty() || p == t.styles || p == t.settings) {
                doc.relate(relType(p), p.name.substring("word/".length()));
                put(zip, types, p);
            }
        }
        for (Part p : t.headers) {
            put(zip, types, p);
        }
        zip.put("word/_rels/document.xml.rels", doc.relsXml());
        String core = core(t.properties);
        StringBuilder rels = new StringBuilder(Esc.HEAD).append(
                "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">")
                .append("<Relationship Id=\"rId1\" Type=\"").append(RELS).append("officeDocument\"")
                .append(" Target=\"word/document.xml\"/>");
        if (core != null) {
            zip.put("docProps/core.xml", core);
            types.append("<Override PartName=\"/docProps/core.xml\" ContentType=\"application/vnd.openxmlformats-")
                    .append("package.core-properties+xml\"/>");
            rels.append("<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/package/2006/")
                    .append("relationships/metadata/core-properties\" Target=\"docProps/core.xml\"/>");
        }
        zip.put("_rels/.rels", rels.append("</Relationships>").toString());
        zip.put("[Content_Types].xml", types.append("</Types>").toString());
        zip.finish();
    }

    private static String relType(Part p) {
        return p.root.equals("fonts") ? "fontTable" : p.root;
    }

    private static void put(Parts zip, StringBuilder types, Part p) throws IOException {
        types.append("<Override PartName=\"/").append(p.name).append("\" ContentType=\"").append(p.contentType)
                .append("\"/>");
        try (Parts.Part out = zip.open(p.name)) {
            out.write(Esc.HEAD);
            out.write("<w:" + p.root + Names.NAMESPACES + ">");
            out.write(p.xml.toString());
            out.write("</w:" + p.root + ">");
        }
        p.xml.setLength(0);
        p.xml.trimToSize();
        if (p.hasRels() && !p.name.equals("word/document.xml")) {
            int slash = p.name.lastIndexOf('/');
            zip.put(p.name.substring(0, slash) + "/_rels/" + p.name.substring(slash + 1) + ".rels", p.relsXml());
        }
    }

    private static String core(Map<String, String> props) {
        StringBuilder b = new StringBuilder();
        for (String[] c : CORE) {
            String v = props.get(c[0]);
            if (v != null && !v.isBlank()) {
                String tag = c[1];
                b.append('<').append(tag);
                if (tag.startsWith("dcterms:")) {
                    b.append(" xsi:type=\"dcterms:W3CDTF\"");
                }
                b.append('>').append(Esc.attr(v)).append("</").append(tag).append('>');
            }
        }
        if (b.isEmpty()) {
            return null;
        }
        return Esc.HEAD + "<cp:coreProperties xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/"
                + "core-properties\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:dcterms=\"http://purl.org/dc/"
                + "terms/\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">" + b + "</cp:coreProperties>";
    }
}
