package stirling.software.officeconvert.svg;

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
        String text;
        try (Admission.Ticket _ = Admission.jvm().enter(96L << 20)) {
            text = svg.write(page);
        } catch (StackOverflowError e) {
            throw new IOException("Page " + (index + 1) + " is nested too deeply to convert", e);
        } catch (OutOfMemoryError e) {
            throw Admission.NeedsMemory.causedBy(e);
        }
        PdfFiles.stopIfInterrupted();
        Writer w2 = new OutputStreamWriter(out, StandardCharsets.UTF_8);
        w2.write(text);
        w2.flush();
        PageSvg.Stats s = svg.stats();
        return new Page(s.textGlyphs(), s.pathGlyphs(), s.invisibleGlyphs());
    }

    public void close() {
        fonts.close();
    }
}
