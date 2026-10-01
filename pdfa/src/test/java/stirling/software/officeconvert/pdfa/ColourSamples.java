package stirling.software.officeconvert.pdfa;

import static stirling.software.officeconvert.pdfa.RuleSamples.doc;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSFloat;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

final class ColourSamples {

    private ColourSamples() {}

    static void register() {
        doc("c01_devicen_colourants", Set.of("1:6.1.12-9", "2:6.1.13-9"), d -> {
            PDPage p = Samples.page(d);
            PDResources res = new PDResources();
            res.put(COSName.getPDFName("F1"), Samples.std(Standard14Fonts.FontName.HELVETICA));
            COSArray nine = deviceN(d, 9);
            COSArray many = deviceN(d, 33);
            COSDictionary cs = new COSDictionary();
            cs.setItem("N9", nine);
            cs.setItem("N33", many);
            COSArray indexed = new COSArray();
            indexed.add(COSName.INDEXED);
            indexed.add(nine);
            indexed.add(COSInteger.get(1));
            byte[] lookup = new byte[18];
            lookup[0] = (byte) 255;
            lookup[9 + 3] = (byte) 200;
            indexed.add(new COSString(lookup));
            cs.setItem("I9", indexed);
            res.getCOSObject().setItem(COSName.COLORSPACE, cs);
            COSStream image = d.getDocument().createCOSStream();
            image.setItem(COSName.TYPE, COSName.XOBJECT);
            image.setItem(COSName.SUBTYPE, COSName.IMAGE);
            image.setInt(COSName.WIDTH, 4);
            image.setInt(COSName.HEIGHT, 4);
            image.setInt(COSName.BITS_PER_COMPONENT, 8);
            image.setItem(COSName.COLORSPACE, many);
            byte[] px = new byte[4 * 4 * 33];
            for (int i = 0; i < 16; i++) {
                px[i * 33 + i % 4] = (byte) (60 * (i / 4) + 40);
            }
            try (OutputStream o = image.createOutputStream(COSName.FLATE_DECODE)) {
                o.write(px);
            }
            COSDictionary xo = new COSDictionary();
            xo.setItem("Im1", image);
            res.getCOSObject().setItem(COSName.XOBJECT, xo);
            COSDictionary shading = new COSDictionary();
            shading.setInt(COSName.SHADING_TYPE, 2);
            shading.setItem(COSName.COLORSPACE, nine);
            shading.setItem(COSName.COORDS, floats(50, 0, 300, 0));
            COSDictionary f = new COSDictionary();
            f.setInt(COSName.FUNCTION_TYPE, 2);
            f.setItem(COSName.DOMAIN, floats(0, 1));
            f.setItem(COSName.C0, floats(0, 0, 0, 0, 0, 0, 0, 0, 0));
            f.setItem(COSName.C1, floats(1, 0, 0, 0.2f, 0, 0, 0, 0, 0));
            f.setInt(COSName.N, 1);
            shading.setItem(COSName.FUNCTION, f);
            COSDictionary sh = new COSDictionary();
            sh.setItem("Sh1", shading);
            res.getCOSObject().setItem(COSName.SHADING, sh);
            p.setResources(res);
            Samples.raw(p, d, "BT /F1 14 Tf 50 780 Td (DeviceN with 9 and 33 colourants) Tj ET "
                    + "/N9 cs 0 1 0 0 0 0 0 0 0 scn 50 650 200 80 re f "
                    + "/N33 cs " + "0 ".repeat(32) + "1 scn 300 650 200 80 re f "
                    + "/I9 cs 1 sc 50 540 100 80 re f "
                    + "q 50 400 300 100 re W n /Sh1 sh Q "
                    + "q 200 0 0 120 300 400 cm /Im1 Do Q");
        });
    }

    static COSArray deviceN(PDDocument d, int n) throws Exception {
        COSArray names = new COSArray();
        String[] process = {"Cyan", "Magenta", "Yellow", "Black"};
        COSDictionary colorants = new COSDictionary();
        for (int i = 0; i < n; i++) {
            String name = i < 4 ? process[i] : "Spot" + i;
            names.add(COSName.getPDFName(name));
            if (i >= 4) {
                colorants.setItem(name, separation(d, name));
            }
        }
        COSArray a = new COSArray();
        a.add(COSName.DEVICEN);
        a.add(names);
        a.add(COSName.DEVICECMYK);
        a.add(postScript(d, n, 4, "{ " + "pop ".repeat(n - 4) + "}"));
        COSDictionary attrs = new COSDictionary();
        attrs.setItem(COSName.getPDFName("Colorants"), colorants);
        a.add(attrs);
        return a;
    }

    static COSArray separation(PDDocument d, String name) throws Exception {
        COSArray a = new COSArray();
        a.add(COSName.SEPARATION);
        a.add(COSName.getPDFName(name));
        a.add(COSName.DEVICECMYK);
        a.add(postScript(d, 1, 4, "{ 0 0 0 4 -1 roll }"));
        return a;
    }

    static COSStream postScript(PDDocument d, int in, int out, String code) throws Exception {
        COSStream f = d.getDocument().createCOSStream();
        f.setInt(COSName.FUNCTION_TYPE, 4);
        float[] dom = new float[2 * in];
        float[] range = new float[2 * out];
        for (int i = 0; i < in; i++) dom[2 * i + 1] = 1;
        for (int i = 0; i < out; i++) range[2 * i + 1] = 1;
        f.setItem(COSName.DOMAIN, floats(dom));
        f.setItem(COSName.RANGE, floats(range));
        try (OutputStream o = f.createOutputStream()) {
            o.write(code.getBytes(StandardCharsets.US_ASCII));
        }
        return f;
    }

    static COSArray floats(float... v) {
        COSArray a = new COSArray();
        for (float x : v) a.add(new COSFloat(x));
        return a;
    }
}
