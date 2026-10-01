package stirling.software.officeconvert.pdfa;

import java.io.IOException;
import java.util.Arrays;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.common.function.PDFunction;
import org.apache.pdfbox.pdmodel.graphics.color.PDColorSpace;

record Tint(int inputs, COSBase alternate, PDFunction function, float[] ranges) {

    static Tint of(COSArray deviceN) {
        try {
            COSArray names = ContentGraph.array(deviceN.getObject(1));
            COSBase alt = deviceN.get(2);
            PDFunction f = PDFunction.create(deviceN.getObject(3));
            PDColorSpace cs = PDColorSpace.create(deviceN.getObject(2));
            int n = cs.getNumberOfComponents();
            if (names == null || n < 1 || n > 4) {
                return null;
            }
            return new Tint(names.size(), alt, f, ranges(deviceN.getObject(2), n));
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    static boolean deviceN(COSBase b) {
        return b instanceof COSArray a && a.size() >= 4 && COSName.DEVICEN.equals(a.getObject(0));
    }

    int outputs() {
        return ranges.length / 2;
    }

    float[] eval(float[] in) {
        float[] out;
        try {
            out = function.eval(in);
        } catch (IOException | RuntimeException e) {
            out = new float[0];
        }
        float[] r = new float[outputs()];
        for (int i = 0; i < r.length; i++) {
            float v = i < out.length && Float.isFinite(out[i]) ? out[i] : ranges[2 * i];
            r[i] = Math.max(ranges[2 * i], Math.min(ranges[2 * i + 1], v));
        }
        return r;
    }

    float[] initial() {
        float[] ones = new float[inputs];
        Arrays.fill(ones, 1f);
        return eval(ones);
    }

    private static float[] ranges(COSBase alt, int n) {
        float[] r = new float[2 * n];
        for (int i = 0; i < n; i++) {
            r[2 * i + 1] = 1;
        }
        COSArray a = ContentGraph.array(alt);
        if (a == null || a.size() < 2) {
            return r;
        }
        if (COSName.LAB.equals(a.getObject(0))) {
            r = new float[] {0, 100, -100, 100, -100, 100};
            COSArray range = ContentGraph.array(ContentGraph.dict(a.getObject(1)) == null ? null
                    : ContentGraph.dict(a.getObject(1)).getDictionaryObject(COSName.RANGE));
            if (range != null && range.size() == 4) {
                for (int i = 0; i < 4; i++) {
                    if (range.getObject(i) instanceof COSNumber v) {
                        r[2 + i] = v.floatValue();
                    }
                }
            }
        } else if (COSName.ICCBASED.equals(a.getObject(0)) && a.getObject(1) instanceof COSStream s
                && s.getDictionaryObject(COSName.RANGE) instanceof COSArray range && range.size() == 2 * n) {
            for (int i = 0; i < 2 * n; i++) {
                if (range.getObject(i) instanceof COSNumber v) {
                    r[i] = v.floatValue();
                }
            }
        }
        return r;
    }
}
