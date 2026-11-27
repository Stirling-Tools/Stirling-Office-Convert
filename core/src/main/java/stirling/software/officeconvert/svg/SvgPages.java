package stirling.software.officeconvert.svg;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;

import stirling.software.officeconvert.extract.HiddenContent;
import stirling.software.officeconvert.extract.PdfFiles;
import stirling.software.officeconvert.html.FixedHtml;
import stirling.software.officeconvert.memory.Admission;

public final class SvgPages {

    public record Page(int textGlyphs, int pathGlyphs, int invisibleGlyphs) {}

    private final PDDocument doc;
    private final SvgFonts fonts;
    private final HiddenContent hidden;

    public SvgPages(PDDocument doc, FixedHtml.FontSource source) {
        this.doc = doc;
        this.fonts = new SvgFonts(source);
        this.hidden = HiddenContent.of(doc);
    }

    public Page write(int index, OutputStream out) throws IOException {
        PdfFiles.stopIfInterrupted();
        PDPage page = doc.getPage(index);
        PDRectangle box = page.getCropBox();
        int rotation = ((page.getRotation() % 360) + 360) % 360;
        boolean turned = rotation == 90 || rotation == 270;
        float w = turned ? box.getHeight() : box.getWidth();
        float h = turned ? box.getWidth() : box.getHeight();
        hidden.reset();
        fonts.clearUsed();
        PageSvg svg = new PageSvg(doc, page, fonts, hidden, w, h, PageSvg.toSvg(box, rotation));
        Writer w2 = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8), 1 << 16);
        try (Admission.Ticket _ = Admission.jvm().enter(96L << 20)) {
            svg.write(page, w2);
        } catch (StackOverflowError e) {
            throw new IOException("Page " + (index + 1) + " is nested too deeply to convert", e);
        } catch (OutOfMemoryError e) {
            throw Admission.NeedsMemory.causedBy(e);
        }
        w2.flush();
        PdfFiles.stopIfInterrupted();
        PageSvg.Stats s = svg.stats();
        return new Page(s.textGlyphs(), s.pathGlyphs(), s.invisibleGlyphs());
    }

    public void close() {
        fonts.close();
    }
}
