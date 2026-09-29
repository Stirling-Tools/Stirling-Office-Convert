package stirling.software.officeconvert.topdf;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import org.apache.pdfbox.pdmodel.PDDocument;

import stirling.software.officeconvert.topdf.font.FontLibrary;
import stirling.software.officeconvert.topdf.io.OfficeZip;
import stirling.software.officeconvert.topdf.io.PoiXml;
import stirling.software.officeconvert.topdf.pdf.PageSize;
import stirling.software.officeconvert.topdf.pdf.PdfCanvas;
import stirling.software.officeconvert.topdf.pdf.PdfOutput;

public final class RenderJob {

    public static final class PageLimitReached extends RuntimeException {
        PageLimitReached(int limit) {
            super("The page limit of " + limit + " was reached", null, false, false);
        }
    }

    public static final int MAX_WARNINGS = 200;

    private static final int MAX_WARNING_CHARS = 500;

    static final String MORE_WARNINGS = "Further warnings were left out";

    private final OfficeZip zip;

    private final OfficeToPdf.Format format;

    private final OfficeToPdf.Options options;

    private final PdfOutput output;

    private final List<String> warnings = new ArrayList<>();

    private FontLibrary fonts;

    private boolean truncated;

    private boolean dropped;

    public RenderJob(OfficeZip zip, OfficeToPdf.Format format, OfficeToPdf.Options options, FontLibrary fonts,
            PdfOutput output) {
        this.zip = Objects.requireNonNull(zip, "zip");
        this.format = Objects.requireNonNull(format, "format");
        this.options = Objects.requireNonNull(options, "options");
        this.fonts = Objects.requireNonNull(fonts, "fonts");
        this.output = Objects.requireNonNull(output, "output");
        PoiXml.install();
    }

    public Path source() {
        return zip.path();
    }

    public OfficeZip zip() {
        return zip;
    }

    public OfficeToPdf.Format format() {
        return format;
    }

    public OfficeToPdf.Options options() {
        return options;
    }

    public FontLibrary fonts() {
        return fonts;
    }

    public void addDocumentFonts(List<byte[]> fontData) {
        fonts = fonts.withFonts(fontData);
    }

    public PdfOutput output() {
        return output;
    }

    public PDDocument document() {
        return output.document();
    }

    public PdfCanvas newPage(PageSize size) throws IOException {
        Objects.requireNonNull(size, "size");
        return newPage(size.width(), size.height());
    }

    public PdfCanvas newPage(float width, float height) throws IOException {
        checkpoint();
        if (pageLimitReached()) {
            truncated = true;
            throw new PageLimitReached(options.maxPages());
        }
        return output.newPage(width, height);
    }

    public int pageCount() {
        return output.pageCount();
    }

    public int maxPages() {
        return options.maxPages();
    }

    public boolean truncated() {
        return truncated;
    }

    public void truncate() {
        truncated = true;
    }

    public boolean pageLimitReached() {
        return options.maxPages() > 0 && output.pageCount() >= options.maxPages();
    }

    public void checkpoint() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("Conversion interrupted");
        }
    }

    public void warn(String message) {
        Objects.requireNonNull(message, "message");
        String m = clean(message);
        if (m == null || warnings.contains(m)) {
            return;
        }
        if (warnings.size() < MAX_WARNINGS) {
            warnings.add(m);
        } else {
            dropped = true;
        }
    }

    /** The warnings so far, at most {@link #MAX_WARNINGS} plus a closing note, each one line of plain text. */
    public List<String> warnings() {
        List<String> all = new ArrayList<>(warnings);
        boolean more = dropped;
        for (String s : output.fonts().substitutions()) {
            String m = clean(s);
            if (m == null || all.contains(m)) {
                continue;
            }
            if (all.size() >= MAX_WARNINGS) {
                more = true;
                break;
            }
            all.add(m);
        }
        if (more) {
            all.add(MORE_WARNINGS);
        }
        return Collections.unmodifiableList(all);
    }

    // Warnings quote names from the document: keep them to one line with no terminal or bidi control codes
    static String clean(String message) {
        StringBuilder b = new StringBuilder(Math.min(message.length(), MAX_WARNING_CHARS));
        for (int i = 0; i < message.length() && b.length() < MAX_WARNING_CHARS; ) {
            int cp = message.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isISOControl(cp) || Character.getType(cp) == Character.LINE_SEPARATOR
                    || Character.getType(cp) == Character.PARAGRAPH_SEPARATOR) {
                if (b.length() > 0 && b.charAt(b.length() - 1) != ' ') {
                    b.append(' ');
                }
            } else if (!(cp >= 0x202A && cp <= 0x202E || cp >= 0x2066 && cp <= 0x2069 || cp == 0x200E || cp == 0x200F
                    || cp == 0x061C || cp == 0xFEFF)) {
                b.appendCodePoint(cp);
            }
        }
        String s = b.toString().strip();
        return s.isEmpty() ? null : s;
    }
}
