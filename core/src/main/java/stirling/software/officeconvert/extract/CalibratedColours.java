package stirling.software.officeconvert.extract;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSFloat;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;

final class CalibratedColours {

    private static final int MAX_DEPTH = 8;

    private static final float[] D50 = {0.9642f, 1f, 0.8249f};

    private static final double[][] BRADFORD = {
        {0.8951, 0.2664, -0.1614},
        {-0.7502, 1.7135, 0.0367},
        {0.0389, -0.0685, 1.0296}
    };

    private static final double[][] BRADFORD_INVERSE = {
        {0.9869929, -0.1470543, 0.1599627},
        {0.4323053, 0.5183603, 0.0492912},
        {-0.0085287, 0.0400428, 0.9684867}
    };

    private static final COSName CAL_RGB = COSName.getPDFName("CalRGB");

    private static final COSName WHITE_POINT = COSName.getPDFName("WhitePoint");

    private static final COSName MATRIX = COSName.MATRIX;

    private static final float WHITE_TOLERANCE = 1e-4f;

    private final Set<COSBase> seen = Collections.newSetFromMap(new IdentityHashMap<>());

    private CalibratedColours() {}

    static void seed(PDDocument document) {
        CalibratedColours walk = new CalibratedColours();
        try {
            for (PDPage page : document.getPages()) {
                PDResources resources = page.getResources();
                walk.resources(resources == null ? null : resources.getCOSObject(), 0);
                walk.annotations(page.getCOSObject().getCOSArray(COSName.ANNOTS));
            }
        } catch (RuntimeException e) {
            return;
        }
    }

    private void annotations(COSArray annots) {
        if (annots == null) {
            return;
        }
        for (int i = 0; i < annots.size(); i++) {
            if (annots.getObject(i) instanceof COSDictionary annot
                    && annot.getDictionaryObject(COSName.AP) instanceof COSDictionary ap) {
                for (COSName key : ap.keySet()) {
                    COSBase appearance = ap.getDictionaryObject(key);
                    if (appearance instanceof COSStream stream) {
                        resources(stream.getCOSDictionary(COSName.RESOURCES), 1);
                    } else if (appearance instanceof COSDictionary states) {
                        for (COSName state : states.keySet()) {
                            if (states.getDictionaryObject(state) instanceof COSStream stream) {
                                resources(stream.getCOSDictionary(COSName.RESOURCES), 1);
                            }
                        }
                    }
                }
            }
        }
    }

    private void resources(COSDictionary resources, int depth) {
        if (resources == null || depth > MAX_DEPTH || !seen.add(resources)) {
            return;
        }
        COSDictionary spaces = resources.getCOSDictionary(COSName.COLORSPACE);
        if (spaces != null) {
            for (COSName name : spaces.keySet()) {
                space(spaces.getDictionaryObject(name), 0);
            }
        }
        COSDictionary shadings = resources.getCOSDictionary(COSName.SHADING);
        if (shadings != null) {
            for (COSName name : shadings.keySet()) {
                shading(shadings.getDictionaryObject(name));
            }
        }
        COSDictionary patterns = resources.getCOSDictionary(COSName.PATTERN);
        if (patterns != null) {
            for (COSName name : patterns.keySet()) {
                if (patterns.getDictionaryObject(name) instanceof COSDictionary pattern) {
                    shading(pattern.getDictionaryObject(COSName.SHADING));
                    resources(pattern.getCOSDictionary(COSName.RESOURCES), depth + 1);
                }
            }
        }
        COSDictionary xobjects = resources.getCOSDictionary(COSName.XOBJECT);
        if (xobjects != null) {
            for (COSName name : xobjects.keySet()) {
                if (xobjects.getDictionaryObject(name) instanceof COSDictionary xobject) {
                    space(xobject.getDictionaryObject(COSName.COLORSPACE), 0);
                    resources(xobject.getCOSDictionary(COSName.RESOURCES), depth + 1);
                }
            }
        }
    }

    private void shading(COSBase shading) {
        if (shading instanceof COSDictionary dict) {
            space(dict.getDictionaryObject(COSName.COLORSPACE, COSName.CS), 0);
        }
    }

    private void space(COSBase space, int depth) {
        if (!(space instanceof COSArray array) || array.size() < 2 || depth > MAX_DEPTH || !seen.add(array)) {
            return;
        }
        COSBase family = array.getObject(0);
        if (CAL_RGB.equals(family) && array.getObject(1) instanceof COSDictionary dict) {
            calRgb(dict);
        } else if (COSName.DEVICEN.equals(family) && array.size() > 2) {
            space(array.getObject(2), depth + 1);
        } else if (COSName.SEPARATION.equals(family) && array.size() > 2) {
            space(array.getObject(2), depth + 1);
        } else if (COSName.INDEXED.equals(family) || COSName.PATTERN.equals(family)) {
            space(array.getObject(1), depth + 1);
        }
    }

    private void calRgb(COSDictionary dict) {
        if (!seen.add(dict)) {
            return;
        }
        float[] white = numbers(dict.getDictionaryObject(WHITE_POINT), 3);
        if (white == null || !(white[0] > 0) || !(white[1] > 0) || !(white[2] > 0)
                || Math.abs(white[0] - 1) < WHITE_TOLERANCE && Math.abs(white[1] - 1) < WHITE_TOLERANCE
                        && Math.abs(white[2] - 1) < WHITE_TOLERANCE) {
            return;
        }
        float[] matrix = numbers(dict.getDictionaryObject(MATRIX), 9);
        if (matrix == null) {
            matrix = new float[] {1, 0, 0, 0, 1, 0, 0, 0, 1};
        }
        double[][] adapt = adaptation(white);
        COSArray adapted = new COSArray();
        for (int column = 0; column < 3; column++) {
            for (int row = 0; row < 3; row++) {
                double v = 0;
                for (int k = 0; k < 3; k++) {
                    v += adapt[row][k] * matrix[column * 3 + k];
                }
                adapted.add(new COSFloat((float) v));
            }
        }
        COSArray unit = new COSArray();
        for (int i = 0; i < 3; i++) {
            unit.add(new COSFloat(1f));
        }
        dict.setItem(MATRIX, adapted);
        dict.setItem(WHITE_POINT, unit);
    }

    static double[][] adaptation(float[] white) {
        double[] source = multiply(BRADFORD, white);
        double[] target = multiply(BRADFORD, D50);
        double[][] scaled = new double[3][3];
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                scaled[row][col] = BRADFORD[row][col] * target[row] / source[row];
            }
        }
        double[][] out = new double[3][3];
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                for (int k = 0; k < 3; k++) {
                    out[row][col] += BRADFORD_INVERSE[row][k] * scaled[k][col];
                }
            }
        }
        return out;
    }

    private static double[] multiply(double[][] m, float[] v) {
        double[] out = new double[3];
        for (int row = 0; row < 3; row++) {
            out[row] = m[row][0] * v[0] + m[row][1] * v[1] + m[row][2] * v[2];
        }
        return out;
    }

    private static float[] numbers(COSBase base, int count) {
        if (!(base instanceof COSArray array) || array.size() < count) {
            return null;
        }
        float[] out = new float[count];
        for (int i = 0; i < count; i++) {
            if (!(array.getObject(i) instanceof COSNumber n) || !Float.isFinite(n.floatValue())) {
                return null;
            }
            out[i] = n.floatValue();
        }
        return out;
    }
}
