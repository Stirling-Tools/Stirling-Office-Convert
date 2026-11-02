package stirling.software.officeconvert.cli;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;

import stirling.software.officeconvert.EbookOptions;
import stirling.software.officeconvert.PdfToAzw3;
import stirling.software.officeconvert.PdfToDocx;
import stirling.software.officeconvert.PdfToEpub;
import stirling.software.officeconvert.PdfToHtml;
import stirling.software.officeconvert.PdfToJson;
import stirling.software.officeconvert.PdfToMarkdown;
import stirling.software.officeconvert.PdfToOdp;
import stirling.software.officeconvert.PdfToOdt;
import stirling.software.officeconvert.PdfToPptx;
import stirling.software.officeconvert.PdfToRtf;
import stirling.software.officeconvert.PdfToText;
import stirling.software.officeconvert.PdfToXlsx;
import stirling.software.officeconvert.legacy.PdfToPpt;

enum OutputFormat {
    DOCX((pdf, target, s) -> PdfToDocx.convert(pdf, target, s.document())),
    ODT((pdf, target, s) -> PdfToOdt.convert(pdf, target, s.document())),
    FODT((pdf, target, s) -> PdfToOdt.convertFlat(pdf, target, s.document())),
    XML((pdf, target, s) -> PdfToOdt.convertFlat(pdf, target, s.document())),
    RTF((pdf, target, s) -> PdfToRtf.convert(pdf, target, s.document())),
    DOC((pdf, target, s) -> PdfToRtf.convert(pdf, target, s.document())),
    TXT((pdf, target, s) -> PdfToText.convert(pdf, target, s.document())),
    HTML((pdf, target, s) -> PdfToHtml.convert(pdf, target, s.document())),
    JSON((pdf, target, s) -> PdfToJson.convert(pdf, target, s.document())),
    MD((pdf, target, s) -> PdfToMarkdown.convert(pdf, target, s.document())),
    EPUB((pdf, target, s) -> PdfToEpub.convert(pdf, target, s.document(), s.ebook())),
    AZW3((pdf, target, s) -> PdfToAzw3.convert(pdf, target, s.document(), s.ebook())),
    PPTX((pdf, target, s) -> PdfToPptx.convert(pdf, target, s.slides())),
    ODP((pdf, target, s) -> PdfToOdp.convert(pdf, target, s.slides())),
    PPT((pdf, target, s) -> PdfToPpt.convert(pdf, target, s.slides())),
    XLSX((pdf, target, s) -> PdfToXlsx.convert(pdf, target, s.books())),
    ODS((pdf, target, s) -> PdfToXlsx.convert(pdf, target, s.books()));

    static final String PDF = "pdf";

    record Settings(PdfToDocx.Options document, PdfToPptx.Options slides, PdfToXlsx.Options books, EbookOptions ebook) {}

    @FunctionalInterface
    interface Converter {
        void convert(Path pdf, Path target, Settings settings) throws IOException;
    }

    private final Converter converter;

    OutputFormat(Converter converter) {
        this.converter = converter;
    }

    String extension() {
        return name().toLowerCase(Locale.ROOT);
    }

    void convert(Path pdf, Path target, Settings settings) throws IOException {
        converter.convert(pdf, target, settings);
    }

    static OutputFormat of(String extension) {
        for (OutputFormat f : values()) {
            if (f.extension().equals(extension)) {
                return f;
            }
        }
        return null;
    }

    static String known(String format) throws Usage {
        if (!PDF.equals(format) && of(format) == null) {
            throw new Usage("unknown output format '" + format + "': use " + choices() + " (pdf for Office input)");
        }
        return format;
    }

    static String choices() {
        OutputFormat[] all = values();
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < all.length; i++) {
            b.append(i == 0 ? "" : i == all.length - 1 ? " or " : ", ").append(all[i].extension());
        }
        return b.toString();
    }
}
