package stirling.software.officeconvert.topdf.html;

import java.io.IOException;
import java.util.List;

import stirling.software.officeconvert.topdf.font.FontLibrary;
import stirling.software.officeconvert.topdf.pdf.PdfOutput;

public final class SvgPages {

    private SvgPages() {}

    public static void draw(byte[] svg, PdfOutput output, FontLibrary fonts, List<String> warnings) throws IOException {
        try {
            SvgDraw.render(SvgDoc.parse(svg), output, fonts, warnings);
        } catch (StackOverflowError e) {
            throw new IOException("The SVG drawing is nested too deeply to convert", e);
        }
    }
}
