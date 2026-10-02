package stirling.software.officeconvert.topdf.pdf;

import java.awt.Color;

public record Fill(Color color, Gradient gradient) {

    public Fill {
        if ((color == null) == (gradient == null)) {
            throw new IllegalArgumentException("A fill is either a colour or a gradient");
        }
    }

    public static Fill solid(Color color) {
        return new Fill(color, null);
    }

    public static Fill of(Gradient gradient) {
        return new Fill(null, gradient);
    }

    public Color flat() {
        return color != null ? color : gradient.average();
    }
}
