package stirling.software.officeconvert.topdf.xps;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import stirling.software.officeconvert.memory.Admission;
import stirling.software.officeconvert.topdf.font.FontLibrary;
import stirling.software.officeconvert.topdf.pdf.DocumentInfo;
import stirling.software.officeconvert.topdf.pdf.PdfOutput;

public final class XpsToPdf {

    public record Outcome(int pages, boolean lost, boolean limited, List<String> warnings) {}

    private XpsToPdf() {}

    public static final String XPS_REPRESENTATION = XpsPackage.XPS_REPRESENTATION;

    public static final String OXPS_REPRESENTATION = XpsPackage.OXPS_REPRESENTATION;

    public static final long MAX_ALL_FONT_BYTES = XpsFonts.MAX_ALL_FONT_BYTES;

    public static boolean isRepaired(Path source) {
        return XpsPackage.repairedIs(source);
    }

    static boolean fatal(Throwable e) {
        int depth = 0;
        for (Throwable t = e; t != null && depth++ < 16; t = t.getCause()) {
            if (t instanceof Admission.Stopped || t instanceof InterruptedIOException) {
                return true;
            }
            String m = t.getMessage();
            if (t instanceof IOException && m != null && m.startsWith("Maximum allowed scratch file memory exceeded")) {
                return true;
            }
        }
        return false;
    }

    public static Outcome convert(Path source, PdfOutput output, FontLibrary library, int maxPages) throws IOException {
        try (XpsPackage pkg = XpsPackage.open(source, maxPages > 0 ? maxPages + 1 : 0)) {
            int total = pkg.pages().size();
            int wanted = maxPages > 0 ? Math.min(maxPages, total) : total;
            XpsRenderer renderer = new XpsRenderer(pkg, output, library);
            try {
                return render(pkg, renderer, output, total, wanted, maxPages);
            } finally {
                renderer.fonts.close();
            }
        }
    }

    private static Outcome render(XpsPackage pkg, XpsRenderer renderer, PdfOutput output, int total, int wanted,
            int maxPages) throws IOException {
        int drawn = 0;
        int failed = 0;
        String firstFailure = null;
        for (int i = 0; i < wanted; i++) {
            try {
                renderer.page(i);
                drawn++;
            } catch (InterruptedIOException e) {
                throw e;
            } catch (IOException | RuntimeException e) {
                if (fatal(e)) {
                    throw e;
                }
                failed++;
                if (firstFailure == null) {
                    firstFailure = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                }
                if (output.pageCount() < i + 1) {
                    output.newPage(612, 792);
                }
            }
        }
        if (drawn == 0) {
            throw new IOException("No page of the XPS document could be converted"
                    + (firstFailure == null ? "" : ": " + firstFailure));
        }
        renderer.outline();
        renderer.finish();
        List<String> warnings = new ArrayList<>();
        for (String n : pkg.notes()) {
            if (n != null && !warnings.contains(n)) {
                warnings.add(n);
            }
        }
        if (failed > 0) {
            warnings.add(failed + (failed == 1 ? " page" : " pages") + " of the XPS document could not be drawn and"
                    + (failed == 1 ? " is" : " are") + " left blank (" + firstFailure + ")");
        }
        warnings.addAll(renderer.warnings());
        DocumentInfo info;
        try {
            info = DocumentInfo.read(pkg.zip());
        } catch (InterruptedIOException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            info = DocumentInfo.EMPTY;
        }
        if (info.language() == null && renderer.language() != null) {
            info = info.language(renderer.language());
        }
        output.info(info);
        boolean limited = maxPages > 0 && total > maxPages;
        if (limited) {
            warnings.add("Stopped at the page limit of " + maxPages + " pages");
        }
        boolean lost = failed > 0 || !renderer.warnings().isEmpty() || !pkg.notes().isEmpty();
        return new Outcome(output.pageCount(), lost, limited, warnings);
    }
}
