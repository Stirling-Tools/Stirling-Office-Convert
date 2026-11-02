package stirling.software.officeconvert.pdfa;

import java.awt.geom.GeneralPath;
import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.apache.fontbox.cff.CFFCIDFont;
import org.apache.fontbox.cff.CFFFont;
import org.apache.fontbox.cff.CFFParser;
import org.apache.fontbox.cff.CFFType1Font;
import org.apache.fontbox.cff.Type2CharString;
import org.apache.fontbox.cff.Type2CharStringParser;
import org.apache.fontbox.type1.Type1CharStringReader;
import org.apache.fontbox.encoding.StandardEncoding;
import org.apache.pdfbox.io.RandomAccessReadBuffer;

final class CffProgram extends FontProgram {

    private final CFFFont font;

    private final byte[][] globalSubrs;

    private final Type2CharStringParser parser;

    CffProgram(byte[] data) throws IOException {
        List<CFFFont> fonts;
        try {
            fonts = new CFFParser().parse(new RandomAccessReadBuffer(data));
        } catch (IOException | RuntimeException e) {
            byte[] shifted = CffRepair.header(data);
            byte[] base = shifted != null ? shifted : data;
            byte[] reals = CffRepair.realNumbers(base);
            byte[] fixed = reals != null ? reals : shifted;
            if (fixed == null) {
                throw e;
            }
            fonts = new CFFParser().parse(new RandomAccessReadBuffer(fixed));
        }
        if (fonts.isEmpty()) {
            throw new IOException("The CFF program has no font");
        }
        font = fonts.get(0);
        if (font.getNumCharStrings() < 1) {
            throw new IOException("The CFF program has no glyphs");
        }
        List<byte[]> global = font.getGlobalSubrIndex();
        globalSubrs = global == null ? new byte[0][] : global.toArray(new byte[0][]);
        parser = new Type2CharStringParser(font.getName());
    }

    @Override
    int count() {
        return font.getNumCharStrings();
    }

    @Override
    String name() {
        return font.getName();
    }

    @Override
    int gidForCid(int cid) {
        return font instanceof CFFCIDFont ? font.getCharset().getGIDForCID(cid) : cid;
    }

    @Override
    String name(int gid) {
        if (font instanceof CFFCIDFont) {
            return "cid" + font.getCharset().getCIDForGID(gid);
        }
        return font.getCharset().getNameForGID(gid);
    }

    @Override
    int gid(String name) {
        if (name == null || font instanceof CFFCIDFont) {
            return 0;
        }
        int sid = font.getCharset().getSID(name);
        return sid < 0 ? 0 : font.getCharset().getGIDForSID(sid);
    }

    private Type2CharString charString(int gid) throws IOException {
        Map<String, Object> priv = privateDict(gid);
        byte[][] local = priv != null && priv.get("Subrs") instanceof byte[][] l ? l : null;
        String name = name(gid);
        List<Object> sequence = parser.parse(font.getCharStringBytes().get(gid), globalSubrs, local, name);
        Type1CharStringReader reader = font instanceof CFFType1Font t1 ? t1::getType1CharString : n -> null;
        return new Type2CharString(reader, font.getName(), name, gid, sequence,
                (int) number(priv == null ? null : priv.get("defaultWidthX")),
                (int) number(priv == null ? null : priv.get("nominalWidthX")));
    }

    @Override
    GeneralPath rawPath(int gid) throws IOException {
        return Type2Outline.path(font.getCharStringBytes().get(gid), globalSubrs, localSubrs(gid), null, 0,
                code -> {
                    int accent = gid(StandardEncoding.INSTANCE.getName(code));
                    return accent <= 0 ? null : Type2Outline.path(font.getCharStringBytes().get(accent),
                            globalSubrs, localSubrs(accent), null, 0, null);
                });
    }

    private byte[][] localSubrs(int gid) {
        Map<String, Object> priv = privateDict(gid);
        return priv != null && priv.get("Subrs") instanceof byte[][] l ? l : null;
    }

    @Override
    double rawWidth(int gid) throws IOException {
        Map<String, Object> priv = privateDict(gid);
        Double operand = Type2Width.operand(font.getCharStringBytes().get(gid));
        if (operand == null || priv == null) {
            return charString(gid).getWidth();
        }
        return Double.isNaN(operand) ? number(priv.get("defaultWidthX"))
                : number(priv.get("nominalWidthX")) + operand;
    }

    private Map<String, Object> privateDict(int gid) {
        if (font instanceof CFFCIDFont cid) {
            List<Map<String, Object>> dicts = cid.getPrivDicts();
            int fd = cid.getFdSelect() == null ? 0 : cid.getFdSelect().getFDIndex(gid);
            return fd >= 0 && fd < dicts.size() ? dicts.get(fd) : null;
        }
        return font instanceof CFFType1Font t1 ? t1.getPrivateDict() : null;
    }

    @Override
    CharStringExpansion.Result expansion(int gid) throws IOException {
        Map<String, Object> priv = privateDict(gid);
        return CharStringExpansion.type2(font.getCharStringBytes().get(gid), globalSubrs,
                priv != null && priv.get("Subrs") instanceof byte[][] local ? local : null);
    }

    private static double number(Object o) {
        return o instanceof Number n ? n.doubleValue() : 0;
    }

    @Override
    List<Number> matrix() {
        return font.getFontMatrix();
    }

    @Override
    String weight() {
        Object w = font.getTopDict().get("Weight");
        return w instanceof String s ? s : null;
    }

    @Override
    float italicAngle() {
        Object a = font.getTopDict().get("ItalicAngle");
        return a instanceof Number n ? n.floatValue() : 0;
    }
}
