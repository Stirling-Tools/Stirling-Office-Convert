package stirling.software.officeconvert.pdfa;

import java.awt.geom.AffineTransform;
import java.awt.geom.GeneralPath;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.apache.fontbox.encoding.StandardEncoding;

import stirling.software.officeconvert.extract.PdfFiles;
import stirling.software.officeconvert.pdfa.WebFonts.FontKind;

abstract class FontProgram {

    int sourceBytes;

    int fsType;

    abstract int count();

    abstract String name();

    abstract String name(int gid);

    abstract int gid(String name);

    abstract GeneralPath rawPath(int gid) throws IOException;

    abstract double rawWidth(int gid) throws IOException;

    abstract CharStringExpansion.Result expansion(int gid) throws IOException;

    abstract List<Number> matrix();

    abstract String weight();

    abstract float italicAngle();

    Map<Integer, Integer> own() {
        return Map.of();
    }

    int gidForCid(int cid) {
        return cid;
    }

    int style() {
        String weight = weight() == null ? "" : weight().toLowerCase(Locale.ROOT);
        boolean bold = weight.contains("bold") || weight.contains("black") || weight.contains("heavy");
        return (bold ? 1 : 0) | (italicAngle() != 0 ? 2 : 0);
    }

    void checkExpansion() throws IOException {
        CharStringExpansion.Result[] glyphs = new CharStringExpansion.Result[count()];
        for (int gid = 0; gid < glyphs.length; gid++) {
            PdfFiles.stopIfInterrupted();
            glyphs[gid] = expansion(gid);
        }
        CharStringExpansion.check(glyphs, code -> gid(StandardEncoding.INSTANCE.getName(code)));
    }

    AffineTransform toEm() {
        List<Number> m = matrix();
        if (m == null || m.size() < 6) {
            return new AffineTransform();
        }
        AffineTransform t = AffineTransform.getScaleInstance(WebFonts.UNITS_PER_EM, WebFonts.UNITS_PER_EM);
        t.concatenate(new AffineTransform(m.get(0).doubleValue(), m.get(1).doubleValue(), m.get(2).doubleValue(),
                m.get(3).doubleValue(), m.get(4).doubleValue(), m.get(5).doubleValue()));
        return t;
    }

    GeneralPath path(int gid, AffineTransform toEm) throws IOException {
        GeneralPath p;
        try {
            p = rawPath(gid);
        } catch (IOException | RuntimeException e) {
            p = null;
        }
        if (p == null) {
            return new GeneralPath();
        }
        GeneralPath out = new GeneralPath(p);
        out.transform(toEm);
        return out;
    }

    int advance(int gid, AffineTransform toEm) {
        double w;
        try {
            w = rawWidth(gid);
        } catch (IOException | RuntimeException e) {
            w = 0;
        }
        return (int) Math.rint(w * toEm.getScaleX());
    }

    static FontProgram read(byte[] data, FontKind kind, int length1, int length2) throws IOException {
        if (data == null || data.length == 0) {
            throw new IOException("The font program is empty");
        }
        FontProgram program;
        try {
            program = kind == FontKind.CFF ? new CffProgram(data) : new Type1Program(data, length1, length2);
        } catch (IOException | RuntimeException e) {
            if (kind == FontKind.CFF) {
                throw e instanceof IOException io ? io
                        : new IOException("The font program cannot be read: " + e.getMessage(), e);
            }
            try {
                byte[][] segments = Type1Program.split(data, length1, length2);
                program = new RawType1Program(Type1Lenient.parse(segments[0], segments[1]));
            } catch (IOException | RuntimeException again) {
                throw new IOException("The font program cannot be read: " + e.getMessage(), e);
            }
        }
        program.sourceBytes = data.length;
        return program;
    }
}
