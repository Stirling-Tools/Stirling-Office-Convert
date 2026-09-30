package stirling.software.officeconvert.topdf.pptx;

import java.io.IOException;
import java.util.List;

import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.openxmlformats.schemas.presentationml.x2006.main.CTPresentation;
import org.openxmlformats.schemas.presentationml.x2006.main.CTSlideSize;

import stirling.software.officeconvert.topdf.RenderJob;
import stirling.software.officeconvert.topdf.font.CloudFonts;
import stirling.software.officeconvert.topdf.font.FontLibrary;
import stirling.software.officeconvert.topdf.pdf.PdfCanvas;
import stirling.software.officeconvert.topdf.pdf.Units;

final class Deck {

    static final float DEFAULT_WIDTH = 720;

    static final float DEFAULT_HEIGHT = 540;

    private final RenderJob job;

    private final XMLSlideShow ppt;

    private final Pictures pictures;

    private final TextStyles styles;

    private final TableStyles tableStyles;

    private final Standins standins;

    private final float width;

    private final float height;

    private final int firstNumber;

    Deck(RenderJob job, XMLSlideShow ppt) {
        this.job = job;
        this.ppt = ppt;
        this.pictures = new Pictures(job);
        this.styles = new TextStyles(this);
        this.tableStyles = new TableStyles(job);
        this.standins = new Standins(new CloudFonts(job.fonts()));
        PlaceholderOrder.fix(ppt);
        CTPresentation pres = ppt.getCTPresentation();
        CTSlideSize size = pres == null ? null : pres.getSldSz();
        float w = size == null ? DEFAULT_WIDTH : Units.emu(size.getCx());
        float h = size == null ? DEFAULT_HEIGHT : Units.emu(size.getCy());
        this.width = clamp(w, DEFAULT_WIDTH);
        this.height = clamp(h, DEFAULT_HEIGHT);
        int first = 1;
        if (pres != null && pres.isSetFirstSlideNum()) {
            first = pres.getFirstSlideNum();
        }
        this.firstNumber = first;
    }

    private static void transparencyGroup(PDPage page) {
        COSDictionary group = new COSDictionary();
        group.setItem(COSName.TYPE, COSName.GROUP);
        group.setItem(COSName.S, COSName.TRANSPARENCY);
        group.setItem(COSName.CS, COSName.DEVICERGB);
        page.getCOSObject().setItem(COSName.GROUP, group);
    }

    private static float clamp(float v, float fallback) {
        if (!(v > 0) || !Float.isFinite(v)) {
            return fallback;
        }
        return Math.max(1, Math.min(14_400, v));
    }

    RenderJob job() {
        return job;
    }

    XMLSlideShow ppt() {
        return ppt;
    }

    FontLibrary fonts() {
        return job.fonts();
    }

    Pictures pictures() {
        return pictures;
    }

    TextStyles styles() {
        return styles;
    }

    Standins standins() {
        return standins;
    }

    TableStyles tableStyles() {
        return tableStyles;
    }

    float width() {
        return width;
    }

    float height() {
        return height;
    }

    void render() throws IOException {
        List<XSLFSlide> slides = ppt.getSlides();
        for (int i = 0; i < slides.size(); i++) {
            job.checkpoint();
            XSLFSlide slide = slides.get(i);
            if (slide.isHidden()) {
                continue;
            }
            try (PdfCanvas canvas = job.newPage(width, height)) {
                transparencyGroup(canvas.page());
                new SlidePainter(this, canvas, slide, firstNumber + i).paint();
            }
        }
    }
}
