package stirling.software.officeconvert.extract;

import java.io.IOException;

import org.apache.pdfbox.pdmodel.graphics.color.PDColor;

public final class PdfColours {

    private PdfColours() {}

    public static int toRgb(PDColor colour, int fallback) {
        if (colour == null) {
            return fallback;
        }
        try {
            return colour.toRGB() & 0xFFFFFF;
        } catch (IOException | RuntimeException e) {
            return fallback;
        }
    }
}
