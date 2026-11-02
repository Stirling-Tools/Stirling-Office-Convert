package stirling.software.officeconvert.extract;

import java.io.IOException;

import org.apache.pdfbox.pdmodel.graphics.color.PDCalGray;
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
            if (space instanceof PDCalGray cal && colour.getComponents().length > 0) {
                int grey = channel(srgb((float) Math.pow(Math.clamp(colour.getComponents()[0], 0f, 1f), cal.getGamma())));
                return grey << 16 | grey << 8 | grey;
            }
            float[] rgb = space.toRGB(colour.getComponents());
            return channel(rgb[0]) << 16 | channel(rgb[1]) << 8 | channel(rgb[2]);
        } catch (IOException | RuntimeException e) {
            return fallback;
        }
    }

    private static float srgb(float linear) {
        return linear <= 0.0031308f ? 12.92f * linear : (float) (1.055 * Math.pow(linear, 1 / 2.4) - 0.055);
    }

    private static int channel(float value) {
        return Float.isNaN(value) ? 0 : Math.round(Math.clamp(value, 0f, 1f) * 255);
    }
}
