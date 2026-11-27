package stirling.software.officeconvert.topdf.ofd;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentNameDictionary;
import org.apache.pdfbox.pdmodel.PDEmbeddedFilesNameTreeNode;
import org.apache.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification;
import org.apache.pdfbox.pdmodel.common.filespecification.PDEmbeddedFile;
import org.w3c.dom.Element;

import stirling.software.officeconvert.topdf.io.Dom;
import stirling.software.officeconvert.topdf.io.OfficeZip;

final class OfdAttachments {

    static final int MAX_ATTACHMENTS = 1_000;

    static final int MAX_NAME = 200;

    private final OfdPackage pkg;

    private final Set<String> notes;

    private final Map<String, PDComplexFileSpecification> files = new LinkedHashMap<>();

    private long budget;

    OfdAttachments(OfdPackage pkg, Set<String> notes, long budget) {
        this.pkg = pkg;
        this.notes = notes;
        this.budget = budget;
    }

    void collect(PDDocument doc, String docPart, Element docRoot) throws IOException {
        Element ref = Dom.kid(docRoot, "Attachments");
        if (ref == null) {
            return;
        }
        String part = OfdPackage.resolve(docPart, Dom.text(ref));
        if (part == null || !pkg.exists(part)) {
            return;
        }
        Element list;
        try {
            list = pkg.xml(part);
        } catch (InterruptedIOException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            notes.add("The attachment list of the OFD document could not be read; attachments were left out");
            return;
        }
        for (Element a : Dom.kids(list, "Attachment")) {
            OfficeZip.checkNotInterrupted();
            if (files.size() >= MAX_ATTACHMENTS) {
                notes.add("The OFD document has more than " + MAX_ATTACHMENTS + " attachments; the rest were left out");
                return;
            }
            if (!OfdNumbers.bool(a.getAttribute("Visible"), true)) {
                continue;
            }
            String loc = OfdPackage.resolve(part, Dom.text(Dom.kid(a, "FileLoc")));
            if (loc == null || !pkg.exists(loc)) {
                continue;
            }
            byte[] data;
            try {
                data = pkg.read(loc, Math.min(OfdPackage.MAX_MEDIA_BYTES, Math.max(0, budget)));
            } catch (InterruptedIOException e) {
                throw e;
            } catch (IOException | RuntimeException e) {
                notes.add("An attachment of the OFD document is too large or unreadable and was left out");
                continue;
            }
            budget -= data.length;
            add(doc, name(a.getAttribute("Name"), loc), data, a.getAttribute("Usage"));
        }
    }

    private void add(PDDocument doc, String name, byte[] data, String usage) throws IOException {
        String unique = name;
        for (int n = 2; files.containsKey(unique); n++) {
            int dot = name.lastIndexOf('.');
            unique = dot > 0 ? name.substring(0, dot) + " (" + n + ")" + name.substring(dot) : name + " (" + n + ")";
        }
        PDEmbeddedFile file = new PDEmbeddedFile(doc, new ByteArrayInputStream(data), COSName.FLATE_DECODE);
        file.setSize(data.length);
        PDComplexFileSpecification spec = new PDComplexFileSpecification();
        spec.setFile(unique);
        spec.setFileUnicode(unique);
        spec.setEmbeddedFile(file);
        spec.setEmbeddedFileUnicode(file);
        String description = clean(usage);
        if (!description.isEmpty()) {
            spec.setFileDescription(description);
        }
        files.put(unique, spec);
    }

    void write(PDDocument doc) {
        if (files.isEmpty()) {
            return;
        }
        PDEmbeddedFilesNameTreeNode tree = new PDEmbeddedFilesNameTreeNode();
        tree.setNames(files);
        PDDocumentNameDictionary names = doc.getDocumentCatalog().getNames();
        if (names == null) {
            names = new PDDocumentNameDictionary(doc.getDocumentCatalog());
            doc.getDocumentCatalog().setNames(names);
        }
        names.setEmbeddedFiles(tree);
    }

    int count() {
        return files.size();
    }

    static String name(String given, String loc) {
        String base = loc.substring(loc.lastIndexOf('/') + 1);
        String name = clean(given);
        if (name.isEmpty()) {
            name = clean(base);
        } else if (!name.matches("(?s).*\\.[\\p{Alnum}]{1,10}")) {
            int dot = base.lastIndexOf('.');
            String ext = dot > 0 ? clean(base.substring(dot)) : "";
            if (ext.length() > 1 && ext.length() <= 10) {
                name += ext;
            }
        }
        return name.isEmpty() ? "attachment" : name;
    }

    private static String clean(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder b = new StringBuilder();
        s.strip().codePoints().forEach(cp -> {
            if (b.length() >= MAX_NAME) {
                return;
            }
            if (Character.isISOControl(cp) || cp == '/' || cp == '\\' || cp == ':' || Character.getType(cp) == Character.FORMAT) {
                b.append('_');
            } else {
                b.appendCodePoint(cp);
            }
        });
        String out = b.toString().strip();
        return List.of(".", "..").contains(out) ? "" : out;
    }
}
