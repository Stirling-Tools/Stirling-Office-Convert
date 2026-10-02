package stirling.software.officeconvert.extract;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;

import stirling.software.officeconvert.memory.Admission;

/** The heap a conversion of a loaded PDF is likely to need, from its page tree and picture dictionaries alone: the
 * pages sampled for document statistics, what PDFBox keeps of every page read, the largest picture that is decoded and
 * figures rendered finer than usual. Nothing is decoded or drawn. */
public final class PdfFootprint {

    static final int SAMPLED_PAGES = 60;

    static final long SAMPLED_PAGE_BYTES = 256L << 10;

    static final long PAGE_BYTES = 112L << 10;

    static final long MAX_RENDER_PIXELS = 16_000_000L;

    // The page costs were measured with figures drawn at this resolution; only a finer one costs more
    static final float MEASURED_DPI = 150f;

    static final int MAX_SCANNED_PAGES = 2_000;

    static final int MAX_SCANNED_XOBJECTS = 4_096;

    private static final int MAX_FORM_DEPTH = 3;

    private PdfFootprint() {}

    public static long estimate(PDDocument doc, int firstPage, int lastPage, float dpi) {
        try {
            return scan(doc, firstPage, lastPage, dpi);
        } catch (RuntimeException e) {
            return Admission.BASE_BYTES + SAMPLED_PAGE_BYTES + PAGE_BYTES;
        }
    }

    private static long scan(PDDocument doc, int firstPage, int lastPage, float dpi) {
        int total = doc.getNumberOfPages();
        int first = firstPage > 0 ? Math.min(firstPage, total) - 1 : 0;
        int last = lastPage > 0 ? Math.min(lastPage, total) - 1 : total - 1;
        int pages = Math.max(1, last - first + 1);
        long model = Math.min(pages, SAMPLED_PAGES) * SAMPLED_PAGE_BYTES + pages * PAGE_BYTES;
        Scan scan = new Scan();
        int end = Math.min(last, first + MAX_SCANNED_PAGES - 1);
        int i = 0;
        for (PDPage page : doc.getPages()) {
            if (i > end || scan.budget <= 0) {
                break;
            }
            if (i++ < first) {
                continue;
            }
            try {
                PDRectangle box = page.getCropBox();
                scan.area = Math.max(scan.area, (double) box.getWidth() * box.getHeight());
                PDResources resources = page.getResources();
                scan.resources(resources == null ? null : resources.getCOSObject(), 0);
            } catch (RuntimeException e) {
                // a broken page costs what the model says; the converter reports it
            }
        }
        long render = renderBytes(scan.area, dpi) - renderBytes(scan.area, MEASURED_DPI);
        long sum = model + scan.picture + Math.max(0, render);
        return sum < 0 ? Long.MAX_VALUE : sum;
    }

    private static long renderBytes(double area, float dpi) {
        double scale = dpi / 72.0;
        return 4 * (long) Math.min(MAX_RENDER_PIXELS, area * scale * scale);
    }

    private static final class Scan {

        final Set<COSBase> seen = Collections.newSetFromMap(new IdentityHashMap<>());

        int budget = MAX_SCANNED_XOBJECTS;

        long picture;

        double area;

        void resources(COSDictionary resources, int depth) {
            if (resources == null) {
                return;
            }
            COSDictionary xobjects = resources.getCOSDictionary(COSName.XOBJECT);
            if (xobjects == null) {
                return;
            }
            for (COSName name : xobjects.keySet()) {
                if (budget-- <= 0) {
                    return;
                }
                if (!(xobjects.getDictionaryObject(name) instanceof COSStream s) || !seen.add(s)) {
                    continue;
                }
                COSName subtype = s.getCOSName(COSName.SUBTYPE);
                if (COSName.IMAGE.equals(subtype)) {
                    picture = Math.max(picture, decoded(s));
                } else if (COSName.FORM.equals(subtype) && depth < MAX_FORM_DEPTH) {
                    resources(s.getCOSDictionary(COSName.RESOURCES), depth + 1);
                }
            }
        }

        // The raw samples plus the 4-byte pixels PDFBox decodes them to; a picture over the budget is never decoded
        static long decoded(COSStream image) {
            long pixels = (long) Math.max(0, image.getInt(COSName.WIDTH, 0)) * Math.max(0, image.getInt(COSName.HEIGHT, 0));
            boolean mask = image.getBoolean(COSName.IMAGE_MASK, false);
            int bits = mask ? 1 : Math.max(1, Math.min(16, image.getInt(COSName.BITS_PER_COMPONENT, 8)));
            COSBase space = image.getDictionaryObject(COSName.COLORSPACE);
            int components = mask || COSName.DEVICEGRAY.equals(space) ? 1 : COSName.DEVICECMYK.equals(space) ? 4 : 3;
            long raw = pixels * components * bits / 8;
            if (raw > ImageBudget.MAX_DECODED_BYTES || raw < 0) {
                return 0;
            }
            return raw + 4 * pixels;
        }
    }
}
