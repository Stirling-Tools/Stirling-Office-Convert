package stirling.software.officeconvert.extract;

import java.io.IOException;

import org.apache.pdfbox.pdmodel.graphics.color.PDColor;
import org.apache.pdfbox.pdmodel.graphics.color.PDColorSpace;

public final class PdfColours {

    private PdfColours() {}

    public static int toRgb(PDColor colour, int fallback) {
        if (colour == null) {
            return fallback;
        }
        try {
            PDColorSpace space = colour.getColorSpace();
            if (space == null) {
                return colour.toRGB() & 0xFFFFFF;
            }
            float[] rgb = space.toRGB(colour.getComponents());
            return channel(rgb[0]) << 16 | channel(rgb[1]) << 8 | channel(rgb[2]);
        } catch (IOException | RuntimeException e) {
            return fallback;
        }
    }

    private static int channel(float value) {
        return Float.isNaN(value) ? 0 : Math.round(Math.clamp(value, 0f, 1f) * 255);
    }
}
