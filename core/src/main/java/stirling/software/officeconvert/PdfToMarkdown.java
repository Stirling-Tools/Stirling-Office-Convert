package stirling.software.officeconvert;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;

import org.apache.pdfbox.pdmodel.PDDocument;

import stirling.software.officeconvert.markdown.MarkdownWriter;

public final class PdfToMarkdown {

    private PdfToMarkdown() {}

    public static void convert(Path pdf, Path markdown, PdfToDocx.Options options) throws IOException {
        PdfToDocx.convert(pdf, markdown, options,
                out -> new MarkdownWriter(out, options.pictures(), MarkdownWriter.Layout.SINGLE_FILE));
    }

    public static void convert(PDDocument doc, OutputStream out, PdfToDocx.Options options) throws IOException {
        PdfToDocx.convert(doc, out, options,
                o -> new MarkdownWriter(o, options.pictures(), MarkdownWriter.Layout.SINGLE_FILE));
    }

    public static void convertPackage(PDDocument doc, OutputStream zip, PdfToDocx.Options options) throws IOException {
        PdfToDocx.convert(doc, zip, options,
                o -> new MarkdownWriter(o, options.pictures(), MarkdownWriter.Layout.PACKAGE));
    }
}
