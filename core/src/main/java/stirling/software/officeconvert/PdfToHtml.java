package stirling.software.officeconvert;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

import org.apache.pdfbox.pdmodel.PDDocument;

import stirling.software.officeconvert.extract.FontGuard;
import stirling.software.officeconvert.extract.PdfFiles;
import stirling.software.officeconvert.extract.PdfFootprint;
import stirling.software.officeconvert.extract.StreamGuard;
import stirling.software.officeconvert.html.FixedHtml;
import stirling.software.officeconvert.html.HtmlWriter;
import stirling.software.officeconvert.html.PdfWebFonts;
import stirling.software.officeconvert.jpx.JpxImageIO;
import stirling.software.officeconvert.memory.Admission;

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

    /** Fixed-layout HTML with no password and the {@link OfficeConvert#DEFAULT_TIMEOUT}. */
    public static void convertFixed(Path pdf, Path html, FixedHtml.Options options) throws IOException {
        convertFixed(pdf, html, options, null, OfficeConvert.DEFAULT_TIMEOUT);
    }

    /** Fixed-layout HTML on the conversion worker: {@code timeout} as in {@link OfficeConvert.Settings}, ZERO for none. */
    public static void convertFixed(Path pdf, Path html, FixedHtml.Options options, String password, Duration timeout)
            throws IOException {
        Objects.requireNonNull(pdf, "pdf");
        Objects.requireNonNull(html, "html");
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(timeout, "timeout");
        OfficeConvert.run(timeout, () -> {
            PdfFiles.stopIfInterrupted();
            return PdfFiles.writeThenMove(html, ".office-convert-", part -> {
                try (PDDocument doc = PdfFiles.open(pdf, password); OutputStream out = Files.newOutputStream(part)) {
                    convertFixed(doc, out, options);
                }
                return null;
            });
        });
    }

    public static void convertFixed(PDDocument doc, OutputStream out, FixedHtml.Options options) throws IOException {
        Objects.requireNonNull(doc, "doc");
        Objects.requireNonNull(out, "out");
        Objects.requireNonNull(options, "options");
        PdfFiles.checkOpen(doc);
        JpxImageIO.install();
        PdfFiles.stopIfInterrupted();
        Admission.Ticket ticket = Admission.jvm().enter(
                PdfFootprint.estimate(doc, options.firstPage(), options.lastPage(), options.dpi()));
        try {
            StreamGuard.check(doc);
            FontGuard.check(doc);
            FixedHtml.write(doc, out, options);
        } finally {
            ticket.close();
        }
    }
}
