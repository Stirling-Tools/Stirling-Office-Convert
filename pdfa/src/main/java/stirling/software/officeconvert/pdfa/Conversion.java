package stirling.software.officeconvert.pdfa;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdmodel.PDDocument;

import stirling.software.officeconvert.extract.PdfFiles;

final class Conversion {

    private Conversion() {}

    static PdfToPdfA.Result run(PDDocument doc, OutputStream out, PdfToPdfA.Options options) throws IOException {
        try {
            return convert(doc, out, options);
        } catch (RuntimeException e) {
            PdfFiles.stopIfInterrupted();
            throw new IOException("The PDF could not be made PDF/A: " + e, e);
        }
    }

    private static PdfToPdfA.Result convert(PDDocument doc, OutputStream out, PdfToPdfA.Options options)
            throws IOException {
        PdfALevel level = options.level();
        int pages = doc.getNumberOfPages();
        if (options.maxPages() > 0 && pages > options.maxPages()) {
            throw new IOException("The PDF has " + pages + " pages, more than the limit of " + options.maxPages());
        }
        Report report = new Report();
        if (doc.isEncrypted()) {
            doc.setAllSecurityToBeRemoved(true);
        }
        doc.getDocument().setEncryptionDictionary(null);
        if (level.tagged()) {
            Tagging.run(doc, level, report);
        }
        Interactive.run(doc, level, report);
        EmbeddedFiles.run(doc, level, report);
        if (level.part() == 1) {
            OptionalContentRemoval.run(doc, report);
        } else {
            OptionalContent.configure(doc);
        }
        PdfFiles.stopIfInterrupted();
        PageSize.run(doc, level, report);
        Limits.names(doc, report);
        ContentGraph graph = ContentGraph.of(doc);
        FontUsage usage = new FontUsage();
        DeviceColours colours = new DeviceColours();
        ContentFixer.run(graph, level, report, usage, colours);
        FontFixer.run(doc, usage, level, options::fontLibrary, report);
        PdfFiles.stopIfInterrupted();
        FontCompaction compaction = new FontCompaction(doc, usage, level);
        CosWalk.Visitor jpx = JpxImages.visitor(doc, level, report);
        StreamFixer.run(doc, level, report, b -> {
            compaction.collect(b);
            jpx.visit(b);
        });
        compaction.run();
        if (level.part() == 1) {
            Transparency.run(doc, options.flattenDpi(), report);
        }
        if (level.tagged()) {
            StructureCheck.run(doc, level, report);
        }
        graph = ContentGraph.of(doc);
        ColourFixer.run(doc, graph, level, report, colours, Limits.prepare(doc, level, report));
        Metadata.run(doc, level);
        PdfFiles.stopIfInterrupted();
        save(doc, out, level);
        return new PdfToPdfA.Result(level, pages, report.warnings(), report.flattenedPages(), report.substitutedFonts());
    }

    private static void save(PDDocument doc, OutputStream out, PdfALevel level) throws IOException {
        doc.setVersion(level.pdfVersion());
        doc.getDocumentCatalog().getCOSObject().removeItem(COSName.VERSION);
        COSArray id = doc.getDocument().getDocumentID();
        if (id == null || id.size() != 2 || !(id.getObject(0) instanceof COSString)) {
            byte[] h = digest(doc);
            COSArray a = new COSArray();
            a.add(new COSString(h));
            a.add(new COSString(h));
            doc.getDocument().setDocumentID(a);
        }
        PdfWriter.write(doc, out, level);
    }

    private static byte[] digest(PDDocument doc) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            md.update(String.valueOf(doc.getNumberOfPages()).getBytes(StandardCharsets.US_ASCII));
            md.update(String.valueOf(System.nanoTime()).getBytes(StandardCharsets.US_ASCII));
            return md.digest();
        } catch (NoSuchAlgorithmException e) {
            return new byte[16];
        }
    }
}
