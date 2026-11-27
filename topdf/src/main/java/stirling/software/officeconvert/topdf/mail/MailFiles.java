package stirling.software.officeconvert.topdf.mail;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.io.RandomAccessReadBufferedFile;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentCatalog;
import org.apache.pdfbox.pdmodel.PDDocumentNameDictionary;
import org.apache.pdfbox.pdmodel.PDEmbeddedFilesNameTreeNode;
import org.apache.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification;
import org.apache.pdfbox.pdmodel.common.filespecification.PDEmbeddedFile;

public final class MailFiles {

    public static final int MAX_FILES = 1_000;

    static final int MAX_NAME_CHARS = 200;

    private static final Pattern MEDIA_TYPE = Pattern.compile("[a-z0-9][a-z0-9!#$&^_.+-]{0,63}/[a-z0-9][a-z0-9!#$&^_.+-]{0,126}");

    private static final Pattern DEVICES = Pattern.compile("CON|PRN|AUX|NUL|COM[0-9]|LPT[0-9]");

    public record File(String name, String type, byte[] data) {}

    private MailFiles() {}

    public static List<File> plan(Mail mail, long maxEach, long maxTotal) {
        List<File> files = new ArrayList<>();
        Set<String> used = new HashSet<>();
        long[] total = {0};
        plan(mail, maxEach, maxTotal, files, used, total, 0);
        return files;
    }

    private static void plan(Mail mail, long maxEach, long maxTotal, List<File> files, Set<String> used, long[] total,
            int depth) {
        for (Mail.Attachment a : mail.attachments) {
            byte[] data = a.data() == null ? new byte[0] : a.data();
            if (files.size() >= MAX_FILES) {
                mail.notes.put(a, "not attached: more than " + MAX_FILES + " files");
            } else if (data.length > maxEach) {
                mail.notes.put(a, "not attached: larger than " + MailPage.size(maxEach));
            } else if (total[0] + data.length > maxTotal) {
                mail.notes.put(a, "not attached: over the " + MailPage.size(maxTotal) + " total");
            } else {
                String name = safeName(a.name(), files.size() + 1, used);
                files.add(new File(name, safeType(a.type()), data));
                total[0] += data.length;
                mail.notes.put(a, name.equals(a.name()) ? "attached to this PDF" : "attached to this PDF as " + name);
            }
        }
        if (depth < 8) {
            for (Mail nested : mail.nested) {
                plan(nested, maxEach, maxTotal, files, used, total, depth + 1);
            }
        }
    }

    static String safeName(String name, int index, Set<String> used) {
        String n = name == null ? "" : name;
        int cut = Math.max(n.lastIndexOf('/'), n.lastIndexOf('\\'));
        n = n.substring(cut + 1);
        StringBuilder sb = new StringBuilder(n.length());
        for (int i = 0; i < n.length() && sb.length() < MAX_NAME_CHARS; i++) {
            char c = n.charAt(i);
            boolean lone = Character.isHighSurrogate(c) && (i + 1 >= n.length()
                    || !Character.isLowSurrogate(n.charAt(i + 1)))
                    || Character.isLowSurrogate(c) && (i == 0 || !Character.isHighSurrogate(n.charAt(i - 1)));
            if (c < 0x20 || c == 0x7F || "<>:\"|?*".indexOf(c) >= 0 || Character.getType(c) == Character.FORMAT || lone) {
                sb.append('_');
            } else {
                sb.append(c);
            }
        }
        String s = sb.toString().strip();
        while (s.startsWith(".") || s.endsWith(".")) {
            s = s.startsWith(".") ? s.substring(1) : s.substring(0, s.length() - 1);
        }
        s = s.strip();
        String stem = (s.indexOf('.') < 0 ? s : s.substring(0, s.indexOf('.'))).toUpperCase(Locale.ROOT);
        if (DEVICES.matcher(stem).matches()) {
            s = "_" + s;
        }
        if (s.isEmpty() || s.chars().allMatch(c -> c == '_' || c == '.')) {
            s = "attachment-" + index;
        }
        String base = s;
        int k = 2;
        while (!used.add(s.toLowerCase(Locale.ROOT))) {
            int dot = base.lastIndexOf('.');
            s = dot > 0 ? base.substring(0, dot) + " (" + k + ")" + base.substring(dot) : base + " (" + k + ")";
            k++;
        }
        return s;
    }

    static String safeType(String type) {
        String t = type == null ? "" : type.strip().toLowerCase(Locale.ROOT);
        return MEDIA_TYPE.matcher(t).matches() ? t : "application/octet-stream";
    }

    public static void embed(Path pdf, List<File> files, OutputStream sink) throws IOException {
        try (PDDocument doc = Loader.loadPDF(new RandomAccessReadBufferedFile(pdf.toFile()), null, null, null,
                IOUtils.createTempFileOnlyStreamCache())) {
            Map<String, PDComplexFileSpecification> specs = new TreeMap<>();
            for (File f : files) {
                PDEmbeddedFile ef = new PDEmbeddedFile(doc, new ByteArrayInputStream(f.data()));
                ef.setSubtype(f.type());
                ef.setSize(f.data().length);
                PDComplexFileSpecification spec = new PDComplexFileSpecification();
                spec.setFile(f.name());
                spec.setFileUnicode(f.name());
                spec.setEmbeddedFile(ef);
                spec.setEmbeddedFileUnicode(ef);
                specs.put(f.name(), spec);
            }
            PDEmbeddedFilesNameTreeNode tree = new PDEmbeddedFilesNameTreeNode();
            tree.setNames(specs);
            PDDocumentCatalog catalog = doc.getDocumentCatalog();
            PDDocumentNameDictionary names = catalog.getNames();
            if (names == null) {
                names = new PDDocumentNameDictionary(catalog);
            }
            names.setEmbeddedFiles(tree);
            catalog.setNames(names);
            doc.save(sink);
        }
    }
}
