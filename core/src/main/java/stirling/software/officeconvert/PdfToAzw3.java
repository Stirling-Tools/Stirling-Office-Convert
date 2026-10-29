package stirling.software.officeconvert;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.Objects;

import org.apache.pdfbox.pdmodel.PDDocument;

import stirling.software.officeconvert.html.Azw3Writer;

public final class PdfToAzw3 {

    private PdfToAzw3() {}

    public static void convert(Path pdf, Path azw3, PdfToDocx.Options options) throws IOException {
        convert(pdf, azw3, options, EbookOptions.defaults());
    }

    public static void convert(Path pdf, Path azw3, PdfToDocx.Options options, EbookOptions book)
            throws IOException {
        Objects.requireNonNull(book, "book");
        PdfToDocx.convertWithDocument(pdf, azw3, options,
                doc -> out -> new Azw3Writer(out, options.pictures(), PdfBook.of(doc, options, book, false)));
    }

    public static void convert(PDDocument doc, OutputStream out, PdfToDocx.Options options) throws IOException {
        convert(doc, out, options, EbookOptions.defaults());
    }

    public static void convert(PDDocument doc, OutputStream out, PdfToDocx.Options options, EbookOptions book)
            throws IOException {
        Objects.requireNonNull(book, "book");
        PdfToDocx.convert(doc, out, options, o -> new Azw3Writer(o, options.pictures(), PdfBook.of(doc, options, book, false)));
    }
}
