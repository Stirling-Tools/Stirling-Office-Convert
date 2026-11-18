package stirling.software.officeconvert.testing;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;

public final class RunawayFonts {

    public record Type1(byte[] data, int length1, int length2) {}

    private static final int CALL_SUBR = 10;

    private static final int RETURN = 11;

    private static final int ENDCHAR = 14;

    private RunawayFonts() {}

    public static byte[] cff(int levels, int fan) {
        byte[] header = {1, 0, 4, 4};
        byte[] names = index(ascii("Runaway"));
        byte[] strings = index(ascii("A"));
        byte[] globals = index();
        byte[] charset = {0, 1, (byte) 0x87};
        byte[] charStrings = index(notdef(), glyph());
        byte[] subrs = index(subrs(levels, fan));
        byte[] priv = concat(num5(6), new byte[] {19});
        int topLength = index(topDict(0, 0, 0, 0)).length;
        int charsetAt = header.length + names.length + topLength + strings.length + globals.length;
        int charStringsAt = charsetAt + charset.length;
        int privAt = charStringsAt + charStrings.length;
        byte[] top = index(topDict(charsetAt, charStringsAt, priv.length, privAt));
        return concat(header, names, top, strings, globals, charset, charStrings, priv, subrs);
    }

    public static byte[] cidCff(int levels, int fan) {
        byte[] header = {1, 0, 4, 4};
        byte[] names = index(ascii("RunawayCID"));
        byte[] strings = index(ascii("Adobe"), ascii("Identity"));
        byte[] globals = index();
        byte[] charset = {0, 0, 1};
        byte[] charStrings = index(notdef(), glyph());
        byte[] fdSelect = {0, 0, 0};
        byte[] subrs = index(subrs(levels, fan));
        byte[] priv = concat(num5(6), new byte[] {19});
        int fdLength = index(concat(num5(0), num5(0), new byte[] {18})).length;
        int topLength = index(cidTopDict(0, 0, 0, 0)).length;
        int charsetAt = header.length + names.length + topLength + strings.length + globals.length;
        int charStringsAt = charsetAt + charset.length;
        int fdSelectAt = charStringsAt + charStrings.length;
        int fdArrayAt = fdSelectAt + fdSelect.length;
        int privAt = fdArrayAt + fdLength;
        byte[] fdArray = index(concat(num5(priv.length), num5(privAt), new byte[] {18}));
        byte[] top = index(cidTopDict(charsetAt, charStringsAt, fdSelectAt, fdArrayAt));
        return concat(header, names, top, strings, globals, charset, charStrings, fdSelect, fdArray, priv, subrs);
    }

    public static Type1 type1(int levels) {
        String clear = "%!PS-AdobeFont-1.0: Runaway 001\n12 dict begin\n/FontName /Runaway def\n"
                + "/Encoding StandardEncoding def\n/PaintType 0 def\n/FontType 1 def\n"
                + "/FontMatrix [0.001 0 0 0.001 0 0] readonly def\n/FontBBox {0 0 500 800} readonly def\n"
                + "currentdict end\ncurrentfile eexec\n";
        ByteArrayOutputStream priv = new ByteArrayOutputStream();
        write(priv, "dup /Private 8 dict dup begin\n/RD{string currentfile exch readstring pop}executeonly def\n"
                + "/ND{noaccess def}executeonly def\n/NP{noaccess put}executeonly def\n/lenIV 4 def\n"
                + "/BlueValues [] def\n/password 5839 def\n");
        int count = 5 + levels + 1;
        write(priv, "/Subrs " + count + " array\n");
        for (int i = 0; i < count; i++) {
            byte[] cs = i >= 5 && i < 5 + levels
                    ? concat(type1Num(i + 1), new byte[] {CALL_SUBR}, type1Num(i + 1), new byte[] {CALL_SUBR, RETURN})
                    : new byte[] {RETURN};
            byte[] enc = charstring(cs);
            write(priv, "dup " + i + " " + enc.length + " RD ");
            priv.writeBytes(enc);
            write(priv, " NP\n");
        }
        write(priv, "ND\n2 index /CharStrings 2 dict dup begin\n");
        type1Glyph(priv, ".notdef", concat(type1Num(0), type1Num(250), new byte[] {13, ENDCHAR}));
        type1Glyph(priv, "A", concat(type1Num(0), type1Num(500), new byte[] {13}, type1Num(5),
                new byte[] {CALL_SUBR}, type1Num(10), type1Num(0), new byte[] {21, ENDCHAR}));
        write(priv, "end\nend\nreadonly put\nnoaccess put\ndup/FontName get exch definefont pop\n"
                + "mark currentfile closefile\n");
        byte[] head = ascii(clear);
        byte[] encrypted = encrypt(concat(new byte[4], priv.toByteArray()), 55665);
        byte[] tail = ascii(("0".repeat(64) + "\n").repeat(8) + "cleartomark\n");
        return new Type1(concat(head, encrypted, tail), head.length, encrypted.length);
    }

    public static PDDocument simpleFontPdf(COSName fontFileKey, byte[] program, COSName subtype, boolean widths)
            throws IOException {
        return simpleFontPdf(fontFileKey, program, subtype, widths, -1, -1);
    }

    public static PDDocument type1Pdf(Type1 font, boolean widths) throws IOException {
        return simpleFontPdf(COSName.FONT_FILE, font.data(), null, widths, font.length1(), font.length2());
    }

    private static PDDocument simpleFontPdf(COSName fontFileKey, byte[] program, COSName subtype, boolean widths,
            int length1, int length2) throws IOException {
        PDDocument doc = new PDDocument();
        COSStream file = fontFile(doc, program, subtype);
        if (length1 >= 0) {
            file.setInt(COSName.LENGTH1, length1);
            file.setInt(COSName.LENGTH2, length2);
            file.setInt(COSName.LENGTH3, 0);
        }
        COSDictionary font = new COSDictionary();
        font.setItem(COSName.TYPE, COSName.FONT);
        font.setItem(COSName.SUBTYPE, COSName.TYPE1);
        font.setName(COSName.BASE_FONT, "Runaway");
        font.setItem(COSName.ENCODING, COSName.WIN_ANSI_ENCODING);
        if (widths) {
            font.setInt(COSName.FIRST_CHAR, 65);
            font.setInt(COSName.LAST_CHAR, 65);
            font.setItem(COSName.WIDTHS, array(COSInteger.get(500)));
        }
        font.setItem(COSName.FONT_DESC, descriptor("Runaway", fontFileKey, file));
        addPage(doc, font, "BT /F1 24 Tf 72 700 Td (A) Tj ET");
        return doc;
    }

    public static PDDocument cidFontPdf(byte[] program, boolean widths) throws IOException {
        PDDocument doc = new PDDocument();
        COSStream file = fontFile(doc, program, COSName.getPDFName("CIDFontType0C"));
        COSDictionary cid = new COSDictionary();
        cid.setItem(COSName.TYPE, COSName.FONT);
        cid.setItem(COSName.SUBTYPE, COSName.CID_FONT_TYPE0);
        cid.setName(COSName.BASE_FONT, "RunawayCID");
        COSDictionary info = new COSDictionary();
        info.setString(COSName.REGISTRY, "Adobe");
        info.setString(COSName.ORDERING, "Identity");
        info.setInt(COSName.SUPPLEMENT, 0);
        cid.setItem(COSName.CIDSYSTEMINFO, info);
        if (widths) {
            cid.setItem(COSName.W, array(COSInteger.get(1), array(COSInteger.get(500))));
        }
        cid.setItem(COSName.FONT_DESC, descriptor("RunawayCID", COSName.FONT_FILE3, file));
        COSDictionary font = new COSDictionary();
        font.setItem(COSName.TYPE, COSName.FONT);
        font.setItem(COSName.SUBTYPE, COSName.TYPE0);
        font.setName(COSName.BASE_FONT, "RunawayCID");
        font.setItem(COSName.ENCODING, COSName.IDENTITY_H);
        font.setItem(COSName.DESCENDANT_FONTS, array(cid));
        addPage(doc, font, "BT /F1 24 Tf 72 700 Td <0001> Tj ET");
        return doc;
    }

    private static COSStream fontFile(PDDocument doc, byte[] program, COSName subtype) throws IOException {
        COSStream file = doc.getDocument().createCOSStream();
        if (subtype != null) {
            file.setItem(COSName.SUBTYPE, subtype);
        }
        try (OutputStream out = file.createOutputStream()) {
            out.write(program);
        }
        return file;
    }

    private static COSDictionary descriptor(String name, COSName key, COSStream file) {
        COSDictionary d = new COSDictionary();
        d.setItem(COSName.TYPE, COSName.FONT_DESC);
        d.setName(COSName.FONT_NAME, name);
        d.setInt(COSName.FLAGS, 32);
        d.setItem(COSName.FONT_BBOX, array(COSInteger.ZERO, COSInteger.ZERO, COSInteger.get(500),
                COSInteger.get(800)));
        d.setInt(COSName.ITALIC_ANGLE, 0);
        d.setInt(COSName.ASCENT, 800);
        d.setInt(COSName.DESCENT, -200);
        d.setInt(COSName.CAP_HEIGHT, 700);
        d.setInt(COSName.STEM_V, 80);
        d.setItem(key, file);
        return d;
    }

    private static void addPage(PDDocument doc, COSDictionary font, String content) throws IOException {
        PDPage page = new PDPage(PDRectangle.LETTER);
        PDResources resources = new PDResources();
        COSDictionary fonts = new COSDictionary();
        fonts.setItem(COSName.getPDFName("F1"), font);
        resources.getCOSObject().setItem(COSName.FONT, fonts);
        page.setResources(resources);
        PDStream stream = new PDStream(doc);
        try (OutputStream out = stream.createOutputStream()) {
            out.write(ascii(content));
        }
        page.setContents(stream);
        doc.addPage(page);
    }

    private static COSArray array(COSBase... items) {
        COSArray a = new COSArray();
        for (COSBase item : items) {
            a.add(item);
        }
        return a;
    }

    private static byte[] notdef() {
        return concat(num(0), num(0), new byte[] {21, ENDCHAR});
    }

    private static byte[] glyph() {
        return concat(num(-107), new byte[] {CALL_SUBR}, num(100), num(0), new byte[] {21}, num(0), num(100),
                new byte[] {5, ENDCHAR});
    }

    private static byte[][] subrs(int levels, int fan) {
        byte[][] subrs = new byte[levels + 1][];
        for (int i = 0; i < levels; i++) {
            ByteArrayOutputStream calls = new ByteArrayOutputStream();
            for (int k = 0; k < fan; k++) {
                calls.writeBytes(num(i + 1 - 107));
                calls.write(CALL_SUBR);
            }
            calls.write(RETURN);
            subrs[i] = calls.toByteArray();
        }
        subrs[levels] = new byte[] {RETURN};
        return subrs;
    }

    private static byte[] topDict(int charsetAt, int charStringsAt, int privSize, int privAt) {
        return concat(num5(charsetAt), new byte[] {15}, num5(charStringsAt), new byte[] {17}, num5(privSize),
                num5(privAt), new byte[] {18});
    }

    private static byte[] cidTopDict(int charsetAt, int charStringsAt, int fdSelectAt, int fdArrayAt) {
        return concat(num5(391), num5(392), num5(0), new byte[] {12, 30}, num5(2), new byte[] {12, 34},
                num5(charsetAt), new byte[] {15}, num5(charStringsAt), new byte[] {17}, num5(fdArrayAt),
                new byte[] {12, 36}, num5(fdSelectAt), new byte[] {12, 37});
    }

    private static byte[] index(byte[]... items) {
        if (items.length == 0) {
            return new byte[] {0, 0};
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(items.length >> 8);
        out.write(items.length);
        out.write(4);
        int offset = 1;
        out.writeBytes(int32(offset));
        for (byte[] item : items) {
            offset += item.length;
            out.writeBytes(int32(offset));
        }
        for (byte[] item : items) {
            out.writeBytes(item);
        }
        return out.toByteArray();
    }

    private static byte[] num(int v) {
        if (v < -107 || v > 107) {
            throw new IllegalArgumentException("one-byte operands only: " + v);
        }
        return new byte[] {(byte) (v + 139)};
    }

    private static byte[] num5(int v) {
        return concat(new byte[] {29}, int32(v));
    }

    private static byte[] int32(int v) {
        return new byte[] {(byte) (v >> 24), (byte) (v >> 16), (byte) (v >> 8), (byte) v};
    }

    private static byte[] type1Num(int v) {
        if (v >= -107 && v <= 107) {
            return new byte[] {(byte) (v + 139)};
        }
        int w = v - 108;
        return new byte[] {(byte) (w / 256 + 247), (byte) (w % 256)};
    }

    private static void type1Glyph(ByteArrayOutputStream out, String name, byte[] body) {
        byte[] enc = charstring(body);
        write(out, "/" + name + " " + enc.length + " RD ");
        out.writeBytes(enc);
        write(out, " ND\n");
    }

    private static byte[] charstring(byte[] plain) {
        return encrypt(concat(new byte[4], plain), 4330);
    }

    private static byte[] encrypt(byte[] plain, int key) {
        int r = key;
        byte[] out = new byte[plain.length];
        for (int i = 0; i < plain.length; i++) {
            int c = (plain[i] & 0xFF) ^ (r >> 8);
            out[i] = (byte) c;
            r = ((c + r) * 52845 + 22719) & 0xFFFF;
        }
        return out;
    }

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static void write(ByteArrayOutputStream out, String s) {
        out.writeBytes(ascii(s));
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            out.writeBytes(p);
        }
        return out.toByteArray();
    }
}
