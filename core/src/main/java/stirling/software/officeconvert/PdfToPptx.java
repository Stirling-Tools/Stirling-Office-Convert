package stirling.software.officeconvert;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.Objects;

import org.apache.pdfbox.pdmodel.PDDocument;

import stirling.software.officeconvert.pptx.PptxWriter;
import stirling.software.officeconvert.slides.SlideConversion;

public final class PdfToPptx {

    public record Options(int firstPage, int lastPage, boolean tables, float figureDpi, String password,
            boolean pictureFallback, Pictures pictures) {

        public Options(int firstPage, int lastPage, boolean tables, float figureDpi, String password) {
            this(firstPage, lastPage, tables, figureDpi, password, false);
        }

        public Options(int firstPage, int lastPage, boolean tables, float figureDpi, String password,
                boolean pictureFallback) {
            this(firstPage, lastPage, tables, figureDpi, password, pictureFallback, Pictures.COMPACT);
        }

        public static Options defaults() {
            return new Options(0, 0, true, 150f, null, false, Pictures.COMPACT);
        }

        public Options {
            Objects.requireNonNull(pictures, "pictures");
            if (firstPage < 0 || lastPage < 0) {
                throw new IllegalArgumentException("Page numbers are 1-based, or 0 for the first or last page");
            }
            if (firstPage > 0 && lastPage > 0 && lastPage < firstPage) {
                throw new IllegalArgumentException("The page range ends before it starts: " + firstPage + "-" + lastPage);
            }
            if (!(figureDpi >= 36 && figureDpi <= 600)) {
                throw new IllegalArgumentException("figureDpi must be between 36 and 600, was " + figureDpi);
            }
        }

        public Options withPictureFallback(boolean on) {
            return new Options(firstPage, lastPage, tables, figureDpi, password, on, pictures);
        }

        public Options withPictures(Pictures how) {
            return new Options(firstPage, lastPage, tables, figureDpi, password, pictureFallback, how);
        }

        @Override
        public String toString() {
            return "Options[pages=" + firstPage + "-" + lastPage + ", tables=" + tables + ", figureDpi=" + figureDpi
                    + ", password=" + (password == null ? "none" : "***") + ", pictureFallback=" + pictureFallback
                    + ", pictures=" + pictures + "]";
        }
    }

    private PdfToPptx() {}

    public static void convert(Path pdf, Path pptx, Options options) throws IOException {
        SlideConversion.convert(pdf, pptx, options, PptxWriter::new);
    }

    public static void convert(PDDocument doc, OutputStream out, Options options) throws IOException {
        SlideConversion.convert(doc, out, options, PptxWriter::new);
    }
}
