package stirling.software.officeconvert;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.Objects;

import org.apache.pdfbox.pdmodel.PDDocument;

import stirling.software.officeconvert.html.EpubWriter;

public final class PdfToEpub {

    private PdfToEpub() {}

    public static void convert(Path pdf, Path epub, PdfToDocx.Options options) throws IOException {
        convert(pdf, epub, options, EbookOptions.defaults());
    }

    public static void convert(Path pdf, Path epub, PdfToDocx.Options options, EbookOptions book)
            throws IOException {
        Objects.requireNonNull(book, "book");
        PdfToDocx.convertWithDocument(pdf, epub, options,
                doc -> out -> new EpubWriter(out, options.pictures(), PdfBook.of(doc, options, book)));
    }

    public static void convert(PDDocument doc, OutputStream out, PdfToDocx.Options options) throws IOException {
        convert(doc, out, options, EbookOptions.defaults());
    }

    public static void convert(PDDocument doc, OutputStream out, PdfToDocx.Options options, EbookOptions book)
            throws IOException {
        Objects.requireNonNull(book, "book");
        PdfToDocx.convert(doc, out, options, o -> new EpubWriter(o, options.pictures(), PdfBook.of(doc, options, book)));
    }
}
