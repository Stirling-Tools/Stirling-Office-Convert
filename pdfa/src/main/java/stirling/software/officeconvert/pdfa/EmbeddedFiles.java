package stirling.software.officeconvert.pdfa;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;

final class EmbeddedFiles {

    private static final COSName FILE_ATTACHMENT = COSName.getPDFName("FileAttachment");

    private static final COSName AF = COSName.getPDFName("AF");

    private static final COSName AF_RELATIONSHIP = COSName.getPDFName("AFRelationship");

    private static final COSName UNSPECIFIED = COSName.getPDFName("Unspecified");

    private static final Map<String, String> MIME = Map.ofEntries(Map.entry("pdf", "application/pdf"),
            Map.entry("xml", "application/xml"), Map.entry("txt", "text/plain"), Map.entry("csv", "text/csv"),
            Map.entry("json", "application/json"), Map.entry("png", "image/png"), Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"), Map.entry("htm", "text/html"), Map.entry("html", "text/html"),
            Map.entry("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
            Map.entry("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
            Map.entry("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation"));

    private final PDDocument doc;

    private final PdfALevel level;

    private final Report report;

    private final List<COSDictionary> associated = new ArrayList<>();

    private final Set<COSDictionary> seen = Collections.newSetFromMap(new IdentityHashMap<>());

    private EmbeddedFiles(PDDocument doc, PdfALevel level, Report report) {
        this.doc = doc;
        this.level = level;
        this.report = report;
    }

    static void run(PDDocument doc, PdfALevel level, Report report) throws IOException {
        new EmbeddedFiles(doc, level, report).run();
    }

    private void run() throws IOException {
        COSDictionary cat = doc.getDocumentCatalog().getCOSObject();
        COSDictionary names = ContentGraph.dict(cat.getDictionaryObject(COSName.NAMES));
        if (names != null && names.getDictionaryObject(COSName.EMBEDDED_FILES) != null) {
            COSDictionary tree = ContentGraph.dict(names.getDictionaryObject(COSName.EMBEDDED_FILES));
            if (level.part() == 1) {
                names.removeItem(COSName.EMBEDDED_FILES);
                report.warn("Removed the embedded files, which PDF/A-1 does not allow");
            } else if (tree != null) {
                prune(tree, 0);
            }
        }
        for (PDPage page : doc.getPages()) {
            COSArray annots = ContentGraph.array(page.getCOSObject().getDictionaryObject(COSName.ANNOTS));
            if (annots == null) {
                continue;
            }
            COSArray kept = new COSArray();
            for (int i = 0; i < annots.size(); i++) {
                COSDictionary a = ContentGraph.dict(annots.getObject(i));
                if (a != null && FILE_ATTACHMENT.equals(a.getCOSName(COSName.SUBTYPE))) {
                    COSDictionary fs = ContentGraph.dict(a.getDictionaryObject(COSName.FS));
                    if (fs == null || !keep(fs)) {
                        report.warn("Removed a file attachment annotation, as " + level.label()
                                + " allows only " + (level.part() == 3 ? "attached files with a file specification"
                                : "PDF/A attachments"));
                        continue;
                    }
                }
                kept.add(annots.get(i));
            }
            if (kept.size() != annots.size()) {
                page.getCOSObject().setItem(COSName.ANNOTS, kept);
            }
        }
        if (level.part() == 3 && !associated.isEmpty()) {
            COSArray af = ContentGraph.array(cat.getDictionaryObject(AF));
            if (af == null) {
                af = new COSArray();
                cat.setItem(AF, af);
            }
            Set<COSDictionary> have = Collections.newSetFromMap(new IdentityHashMap<>());
            for (int i = 0; i < af.size(); i++) {
                COSDictionary d = ContentGraph.dict(af.getObject(i));
                if (d != null) {
                    have.add(d);
                }
            }
            for (COSDictionary fs : associated) {
                if (have.add(fs)) {
                    af.add(fs);
                }
            }
        } else if (level.part() < 3) {
            cat.removeItem(AF);
        }
    }

    private void prune(COSDictionary node, int depth) throws IOException {
        if (depth > 64) {
            return;
        }
        COSArray pairs = ContentGraph.array(node.getDictionaryObject(COSName.NAMES));
        if (pairs != null) {
            COSArray out = new COSArray();
            for (int i = 0; i + 1 < pairs.size(); i += 2) {
                COSDictionary fs = ContentGraph.dict(pairs.getObject(i + 1));
                if (fs != null && keep(fs)) {
                    out.add(pairs.get(i));
                    out.add(pairs.get(i + 1));
                } else {
                    report.warn("Removed an embedded file, as " + level.label() + " allows only "
                            + (level.part() == 3 ? "files with a file specification" : "PDF/A attachments"));
                }
            }
            node.setItem(COSName.NAMES, out);
        }
        COSArray kids = ContentGraph.array(node.getDictionaryObject(COSName.KIDS));
        if (kids != null) {
            for (int i = 0; i < kids.size(); i++) {
                COSDictionary k = ContentGraph.dict(kids.getObject(i));
                if (k != null) {
                    prune(k, depth + 1);
                }
            }
        }
    }

    private boolean keep(COSDictionary fs) throws IOException {
        COSDictionary ef = ContentGraph.dict(fs.getDictionaryObject(COSName.EF));
        COSStream file = null;
        if (ef != null) {
            for (COSName k : List.of(COSName.UF, COSName.F)) {
                if (ef.getDictionaryObject(k) instanceof COSStream s) {
                    file = s;
                    break;
                }
            }
        }
        if (file == null) {
            return level.part() == 1 ? false : ef == null;
        }
        if (level.part() == 2 && !pdfA(file)) {
            return false;
        }
        String name = fileName(fs);
        if (fs.getDictionaryObject(COSName.F) == null) {
            fs.setString(COSName.F, name);
        }
        if (fs.getDictionaryObject(COSName.UF) == null) {
            fs.setString(COSName.UF, name);
        }
        if (level.part() == 3) {
            if (!(fs.getDictionaryObject(AF_RELATIONSHIP) instanceof COSName)) {
                fs.setItem(AF_RELATIONSHIP, UNSPECIFIED);
            }
            if (!(file.getDictionaryObject(COSName.SUBTYPE) instanceof COSName)) {
                file.setItem(COSName.SUBTYPE, COSName.getPDFName(mime(name)));
            }
            COSDictionary params = ContentGraph.dict(file.getDictionaryObject(COSName.PARAMS));
            if (params == null) {
                params = new COSDictionary();
                file.setItem(COSName.PARAMS, params);
            }
            if (params.getDictionaryObject(COSName.MOD_DATE) == null) {
                params.setDate(COSName.MOD_DATE, java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")));
            }
            if (seen.add(fs)) {
                associated.add(fs);
            }
        }
        return true;
    }

    private static String fileName(COSDictionary fs) {
        for (COSName k : List.of(COSName.UF, COSName.F, COSName.UNIX, COSName.DOS, COSName.MAC)) {
            COSBase v = fs.getDictionaryObject(k);
            if (v instanceof COSString s && !s.getString().isBlank()) {
                return s.getString();
            }
        }
        return "attachment";
    }

    private static String mime(String name) {
        int dot = name.lastIndexOf('.');
        String ext = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
        return MIME.getOrDefault(ext, "application/octet-stream");
    }

    private static boolean pdfA(COSStream file) {
        try (InputStream in = file.createInputStream()) {
            byte[] head = in.readNBytes(8 << 20);
            String s = new String(head, StandardCharsets.ISO_8859_1);
            return s.startsWith("%PDF-") && s.matches("(?s).*pdfaid:part\\s*(=\\s*[\"']|>\\s*)[12].*");
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }
}
