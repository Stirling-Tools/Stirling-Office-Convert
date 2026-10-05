package stirling.software.officeconvert.extract;

import java.awt.geom.Area;
import java.awt.geom.GeneralPath;
import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.util.Map;

import org.apache.fontbox.cmap.CMapParser;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.io.RandomAccessRead;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDSimpleFont;
import org.apache.pdfbox.pdmodel.font.PDType3Font;
import org.apache.pdfbox.pdmodel.font.PDVectorFont;
import org.apache.pdfbox.pdmodel.font.encoding.Encoding;
import org.apache.pdfbox.pdmodel.font.encoding.GlyphList;

final class GlyphShapes {

    private static final double SERIF_REACH = 0.06;

    private static final double STEM_WIDTH = 0.35;

    private GlyphShapes() {}

    static Boolean serifs(PDFont font) {
        boolean type3 = font instanceof PDType3Font;
        if (!type3 && (!(font instanceof PDVectorFont) || !font.isEmbedded())) {
            return null;
        }
        for (String letter : new String[] {"l", "I", "i", "L"}) {
            Integer code = codeFor(font, letter);
            if (code == null) {
                continue;
            }
            try {
                GeneralPath path = type3 ? drawnPath((PDType3Font) font, code)
                        : ((PDVectorFont) font).hasGlyph(code) ? ((PDVectorFont) font).getPath(code) : null;
                Rectangle2D b = path == null ? null : path.getBounds2D();
                if (b == null || b.getHeight() <= 1) {
                    continue;
                }
                Area middle = new Area(new Rectangle2D.Double(b.getMinX() - 1, b.getCenterY() - 1, b.getWidth() + 2, 2));
                middle.intersect(new Area(path));
                if (!middle.isEmpty() && middle.getBounds2D().getWidth() < STEM_WIDTH * b.getHeight()) {
                    return middle.getBounds2D().getMinX() - b.getMinX() > SERIF_REACH * b.getHeight();
                }
            } catch (IOException | RuntimeException e) {
                return null;
            }
        }
        return null;
    }

    private static GeneralPath drawnPath(PDType3Font font, int code) throws IOException {
        org.apache.pdfbox.pdmodel.font.PDType3CharProc proc = font.getCharProc(code);
        if (proc == null) {
            return null;
        }
        GeneralPath path = new GeneralPath();
        java.util.List<Float> nums = new java.util.ArrayList<>();
        org.apache.pdfbox.pdfparser.PDFStreamParser parser = new org.apache.pdfbox.pdfparser.PDFStreamParser(proc);
        for (Object token = parser.parseNextToken(); token != null; token = parser.parseNextToken()) {
            if (token instanceof org.apache.pdfbox.cos.COSNumber n) {
                nums.add(n.floatValue());
                continue;
            }
            if (token instanceof org.apache.pdfbox.contentstream.operator.Operator op) {
                float[] a = new float[nums.size()];
                for (int i = 0; i < a.length; i++) {
                    a[i] = nums.get(i);
                }
                String name = op.getName();
                if (name.equals("BI") || name.equals("Do")) {
                    return null;
                }
                trace(path, name, a);
            }
            nums.clear();
        }
        return path.getCurrentPoint() == null ? null : path;
    }

    private static void trace(GeneralPath path, String op, float[] a) {
        boolean started = path.getCurrentPoint() != null;
        switch (op) {
            case "m" -> {
                if (a.length >= 2) {
                    path.moveTo(a[0], a[1]);
                }
            }
            case "l" -> {
                if (a.length >= 2 && started) {
                    path.lineTo(a[0], a[1]);
                }
            }
            case "c" -> {
                if (a.length >= 6 && started) {
                    path.curveTo(a[0], a[1], a[2], a[3], a[4], a[5]);
                }
            }
            case "v", "y" -> {
                if (a.length >= 4 && started) {
                    path.lineTo(a[2], a[3]);
                }
            }
            case "h" -> {
                if (started) {
                    path.closePath();
                }
            }
            case "re" -> {
                if (a.length >= 4) {
                    path.append(new Rectangle2D.Float(a[0], a[1], a[2], a[3]), false);
                }
            }
            default -> {
            }
        }
    }

    static boolean fixedPitch(PDFont font) {
        float narrow = widthOf(font, "i", "l");
        float wide = widthOf(font, "m", "w");
        return narrow > 0 && wide > 0 && Math.abs(wide - narrow) <= 0.02f * wide;
    }

    private static float widthOf(PDFont font, String... letters) {
        if (font instanceof PDType3Font) {
            return 0;
        }
        for (String letter : letters) {
            Integer code = codeFor(font, letter);
            try {
                boolean drawn = code != null && font instanceof PDVectorFont v && font.isEmbedded() && v.hasGlyph(code);
                float w = drawn ? font.getWidth(code) : 0;
                if (w > 0) {
                    return w;
                }
            } catch (IOException | RuntimeException e) {
                return 0;
            }
        }
        return 0;
    }

    static float spaceEm(PDFont font) {
        Integer code = font instanceof PDType3Font ? null : codeFor(font, " ");
        if (code == null) {
            return Float.NaN;
        }
        try {
            float w = font.getWidth(code) / 1000f;
            return w > 0 ? w : Float.NaN;
        } catch (IOException | RuntimeException e) {
            return Float.NaN;
        }
    }

    private static Integer codeFor(PDFont font, String letter) {
        COSStream toUnicode = font.getCOSObject().getCOSStream(COSName.TO_UNICODE);
        Integer mapped = toUnicode == null ? null : mappedCode(toUnicode, letter);
        if (mapped != null) {
            return mapped;
        }
        if (!(font instanceof PDSimpleFont simple)) {
            return null;
        }
        Encoding encoding = simple.getEncoding();
        if (encoding != null) {
            for (Map.Entry<Integer, String> e : encoding.getCodeToNameMap().entrySet()) {
                if (letter.equals(GlyphList.getAdobeGlyphList().toUnicode(e.getValue()))) {
                    return e.getKey();
                }
            }
        }
        int own = letter.charAt(0);
        try {
            return toUnicode == null && font.toUnicode(own) == null ? own : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Integer mappedCode(COSStream toUnicode, String letter) {
        try (RandomAccessRead in = new RandomAccessReadBuffer(toUnicode.createInputStream())) {
            byte[] bytes = new CMapParser().parse(in).getCodesFromUnicode(letter);
            if (bytes == null) {
                return null;
            }
            int code = 0;
            for (byte b : bytes) {
                code = code << 8 | b & 0xff;
            }
            return code;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
