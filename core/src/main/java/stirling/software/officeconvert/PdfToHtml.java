package stirling.software.officeconvert;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import org.apache.pdfbox.pdmodel.PDDocument;

import stirling.software.officeconvert.extract.PdfFiles;
import stirling.software.officeconvert.html.FixedHtml;
import stirling.software.officeconvert.html.HtmlWriter;
import stirling.software.officeconvert.html.PdfWebFonts;
import stirling.software.officeconvert.jpx.JpxImageIO;

public final class PdfToHtml {

    private PdfToHtml() {}

    public static void convert(Path pdf, Path html, PdfToDocx.Options options) throws IOException {
        PdfToDocx.convertWithDocument(pdf, html, options, doc -> out -> writer(doc, out, options));
    }

    public static void convert(PDDocument doc, OutputStream out, PdfToDocx.Options options) throws IOException {
        PdfToDocx.convert(doc, out, options, o -> writer(doc, o, options));
    }

    private static HtmlWriter writer(PDDocument doc, OutputStream out, PdfToDocx.Options options)
            throws IOException {
        return new HtmlWriter(out, options.pictures(), PdfWebFonts.of(doc, options.firstPage(), options.lastPage()));
    }

    public static void convertFixed(Path pdf, Path html, FixedHtml.Options options) throws IOException {
        Path part = Files.createTempFile(PdfFiles.outputFolder(html), ".office-convert-", ".part");
        try {
            try (PDDocument doc = PdfFiles.open(pdf, null); OutputStream out = Files.newOutputStream(part)) {
                convertFixed(doc, out, options);
            }
            Files.move(part, html, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(part);
        }
    }

    public static void convertFixed(PDDocument doc, OutputStream out, FixedHtml.Options options) throws IOException {
        PdfFiles.checkOpen(doc);
        JpxImageIO.install();
        FixedHtml.write(doc, out, options);
    }
}
