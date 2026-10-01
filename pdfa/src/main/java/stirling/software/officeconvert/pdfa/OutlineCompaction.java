package stirling.software.officeconvert.pdfa;

import java.io.IOException;
import java.io.OutputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.apache.fontbox.cff.CFFFont;
import org.apache.fontbox.cff.CFFType1Font;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.font.PDCIDFontType0;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1CFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.encoding.StandardEncoding;

final class OutlineCompaction {

    private OutlineCompaction() {}

    static COSStream type1(PDDocument doc, COSStream program, List<PDFont> fonts, Map<COSDictionary, TreeSet<Integer>> codes)
            throws IOException {
        Set<String> names = new HashSet<>();
        for (PDFont f : fonts) {
            if (!(f instanceof PDType1Font t1)) {
                return null;
            }
            for (int code : codes.get(f.getCOSObject())) {
                names.add(t1.codeToName(code));
            }
        }
        byte[] data = StreamFixer.read(program);
        if (data == null) {
            return null;
        }
        Type1Subset.Program p = Type1Subset.hollow(data, program.getInt(COSName.LENGTH1),
                program.getInt(COSName.LENGTH2), names);
        if (p == null || !smaller(program, p.data())) {
            return null;
        }
        COSStream s = stream(doc, p.data());
        s.setInt(COSName.LENGTH1, p.length1());
        s.setInt(COSName.LENGTH2, p.length2());
        s.setInt(COSName.LENGTH3, p.length3());
        return s;
    }

    static COSStream cff(PDDocument doc, COSStream program, List<PDFont> fonts, Map<COSDictionary, TreeSet<Integer>> codes)
            throws IOException {
        Set<Integer> gids = new TreeSet<>();
        boolean simple = false;
        for (PDFont f : fonts) {
            TreeSet<Integer> used = codes.get(f.getCOSObject());
            if (f instanceof PDType1CFont c && c.getCFFType1Font() != null) {
                CFFType1Font cff = c.getCFFType1Font();
                for (int code : used) {
                    gids.add(cff.nameToGID(c.codeToName(code)));
                }
                simple = true;
            } else if (f instanceof PDType0Font t0 && t0.getDescendantFont() instanceof PDCIDFontType0 cid
                    && cid.getCFFFont() != null) {
                for (int code : used) {
                    gids.add(cid.codeToGID(code));
                }
            } else {
                return null;
            }
        }
        byte[] data = StreamFixer.read(program);
        if (data == null) {
            return null;
        }
        if (simple) {
            List<Integer> accents = CffSubset.seacComponents(data, gids);
            if (accents == null) {
                return null;
            }
            CFFFont font = ((PDType1CFont) fonts.get(0)).getCFFType1Font();
            for (int code : accents) {
                String name = StandardEncoding.INSTANCE.getName(code);
                if (name != null && font instanceof CFFType1Font t) {
                    gids.add(t.nameToGID(name));
                }
            }
        }
        byte[] out = CffSubset.hollow(data, gids);
        if (out == null || !smaller(program, out)) {
            return null;
        }
        COSStream s = stream(doc, out);
        s.setItem(COSName.SUBTYPE, program.getDictionaryObject(COSName.SUBTYPE));
        return s;
    }

    private static boolean smaller(COSStream program, byte[] data) throws IOException {
        long before = program.getLength();
        long after = PdfWriter.deflate(data).length;
        return after <= before * FontCompaction.MAX_RATIO && before - after >= FontCompaction.MIN_SAVING;
    }

    private static COSStream stream(PDDocument doc, byte[] data) throws IOException {
        COSStream s = doc.getDocument().createCOSStream();
        try (OutputStream out = s.createOutputStream(COSName.FLATE_DECODE)) {
            out.write(data);
        }
        return s;
    }
}
