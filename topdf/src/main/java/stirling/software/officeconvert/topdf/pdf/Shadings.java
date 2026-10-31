package stirling.software.officeconvert.topdf.pdf;

import java.awt.Color;
import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.MathContext;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBoolean;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSFloat;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.function.PDFunction;
import org.apache.pdfbox.pdmodel.common.function.PDFunctionType2;
import org.apache.pdfbox.pdmodel.common.function.PDFunctionType3;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceRGB;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.shading.PDShading;
import org.apache.pdfbox.pdmodel.graphics.shading.PDShadingType2;
import org.apache.pdfbox.pdmodel.graphics.shading.PDShadingType3;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;

final class Shadings {

    private Shadings() {}

    // The same gradient fully opaque, or its opacities as greys
    static Gradient recolour(Gradient g, boolean alpha) {
        List<Gradient.Stop> stops = new ArrayList<>();
        for (Gradient.Stop s : g.stops()) {
            Color c = s.color();
            stops.add(new Gradient.Stop(s.offset(), alpha ? new Color(c.getAlpha(), c.getAlpha(), c.getAlpha())
                    : new Color(c.getRed(), c.getGreen(), c.getBlue())));
        }
        return new Gradient(g.kind(), g.x1(), g.y1(), g.x2(), g.y2(), g.radius(), stops);
    }

    static PDShading shading(Gradient g) throws IOException {
        List<Gradient.Stop> stops = new ArrayList<>(g.stops());
        if (stops.size() < 2) {
            return null;
        }
        List<Float> offsets = new ArrayList<>();
        float last = -1;
        for (Gradient.Stop s : stops) {
            float o = Math.max(0, Math.min(1, s.offset()));
            if (o <= last) {
                o = Math.min(1, last + 1e-4f);
            }
            offsets.add(o);
            last = o;
        }
        PDFunction function;
        if (stops.size() == 2 && offsets.get(0) == 0 && offsets.get(1) == 1) {
            function = pair(stops.get(0).color(), stops.get(1).color());
        } else {
            COSArray functions = new COSArray();
            COSArray bounds = new COSArray();
            COSArray encode = new COSArray();
            Color previous = stops.get(0).color();
            float start = 0;
            for (int i = 0; i < stops.size(); i++) {
                float o = offsets.get(i);
                if (o <= start && i > 0) {
                    previous = stops.get(i).color();
                    continue;
                }
                if (o > start) {
                    functions.add(pair(previous, stops.get(i).color()));
                    if (o < 1) {
                        bounds.add(new COSFloat(o));
                    }
                    encode.add(new COSFloat(0));
                    encode.add(new COSFloat(1));
                    start = o;
                }
                previous = stops.get(i).color();
            }
            if (start < 1) {
                functions.add(pair(previous, previous));
                encode.add(new COSFloat(0));
                encode.add(new COSFloat(1));
            }
            if (functions.size() - 1 != bounds.size()) {
                return null;
            }
            if (functions.size() == 1) {
                function = PDFunction.create(functions.getObject(0));
            } else {
                COSDictionary dict = new COSDictionary();
                dict.setInt(COSName.FUNCTION_TYPE, 3);
                dict.setItem(COSName.DOMAIN, floats(0, 1));
                dict.setItem(COSName.FUNCTIONS, functions);
                dict.setItem(COSName.BOUNDS, bounds);
                dict.setItem(COSName.ENCODE, encode);
                function = new PDFunctionType3(dict);
            }
        }
        COSArray extend = new COSArray();
        extend.add(COSBoolean.TRUE);
        extend.add(COSBoolean.TRUE);
        if (g.kind() == Gradient.Kind.LINEAR) {
            PDShadingType2 axial = new PDShadingType2(new COSDictionary());
            axial.setShadingType(PDShading.SHADING_TYPE2);
            axial.setColorSpace(PDDeviceRGB.INSTANCE);
            axial.setCoords(floats(g.x1(), g.y1(), g.x2(), g.y2()));
            axial.setFunction(function);
            axial.setExtend(extend);
            return axial;
        }
        PDShadingType3 radial = new PDShadingType3(new COSDictionary());
        radial.setShadingType(PDShading.SHADING_TYPE3);
        radial.setColorSpace(PDDeviceRGB.INSTANCE);
        radial.setCoords(floats(g.x1(), g.y1(), 0, g.x2(), g.y2(), g.radius()));
        radial.setFunction(function);
        radial.setExtend(extend);
        return radial;
    }

    static PDFunctionType2 pair(Color a, Color b) {
        COSDictionary dict = new COSDictionary();
        dict.setInt(COSName.FUNCTION_TYPE, 2);
        dict.setItem(COSName.DOMAIN, floats(0, 1));
        dict.setItem(COSName.C0, floats(office(a)));
        dict.setItem(COSName.C1, floats(office(b)));
        dict.setInt(COSName.N, 1);
        return new PDFunctionType2(dict);
    }

    // Office writes colour components with three significant digits (128 as 0.502), so renderers round alike
    static float[] office(Color c) {
        return new float[] {unit(c.getRed()), unit(c.getGreen()), unit(c.getBlue())};
    }

    static PDExtendedGraphicsState softMask(PDDocument document, Rectangle2D b, PDShading mask) throws IOException {
        PDFormXObject form = new PDFormXObject(document);
        form.setBBox(new PDRectangle((float) b.getX(), (float) b.getY(), (float) b.getWidth(), (float) b.getHeight()));
        PDResources resources = new PDResources();
        COSName name = resources.add(mask);
        form.setResources(resources);
        COSDictionary group = new COSDictionary();
        group.setItem(COSName.TYPE, COSName.GROUP);
        group.setItem(COSName.S, COSName.TRANSPARENCY);
        group.setItem(COSName.CS, COSName.DEVICEGRAY);
        form.getCOSObject().setItem(COSName.GROUP, group);
        try (OutputStream os = form.getContentStream().createOutputStream()) {
            os.write(("/" + name.getName() + " sh\n").getBytes(StandardCharsets.US_ASCII));
        }
        COSDictionary smask = new COSDictionary();
        smask.setItem(COSName.TYPE, COSName.MASK);
        smask.setItem(COSName.S, COSName.LUMINOSITY);
        smask.setItem(COSName.G, form);
        PDExtendedGraphicsState state = new PDExtendedGraphicsState();
        state.getCOSObject().setItem(COSName.SMASK, smask);
        return state;
    }

    private static final float[] UNITS = new float[256];

    static {
        for (int i = 1; i < 255; i++) {
            UNITS[i] = new BigDecimal(i / 255.0).round(new MathContext(3)).floatValue();
        }
        UNITS[255] = 1;
    }

    static float unit(int component) {
        return UNITS[Math.max(0, Math.min(255, component))];
    }

    static COSArray floats(float... values) {
        COSArray a = new COSArray();
        a.setFloatArray(values);
        return a;
    }
}
