package stirling.software.officeconvert.pdfa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.font.PDCIDFontType2;
import org.apache.pdfbox.pdmodel.font.PDFontFactory;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SharedDescendantTest {

    @TempDir
    Path dir;

    @Test
    void twoFontsSharingOneCidFontBothKeepTheirGlyphsAndText() throws Exception {
        String type0 = "<</Type/Font/Subtype/Type0/BaseFont/Arial,Bold/Encoding/Identity-H/DescendantFonts[7 0 R]"
                + "/ToUnicode 9 0 R>>";
        RawPdf r = RawPdf.page("/Font<</F1 5 0 R/F2 6 0 R>>",
                "BT /F1 24 Tf 72 700 Td <00410042> Tj ET BT /F2 24 Tf 72 600 Td <00430044> Tj ET");
        r.add(type0);
        r.add(type0);
        r.add("<</Type/Font/Subtype/CIDFontType2/BaseFont/Arial,Bold/CIDSystemInfo<</Registry(Adobe)"
                + "/Ordering(Identity)/Supplement 0>>/FontDescriptor 8 0 R/DW 600>>");
        r.add("<</Type/FontDescriptor/FontName/Arial,Bold/Flags 32/FontBBox[-628 -376 2000 1018]/ItalicAngle 0"
                + "/Ascent 905/Descent -212/CapHeight 716/StemV 120>>");
        r.add(RawPdf.stream("", "/CIDInit /ProcSet findresource begin 12 dict begin begincmap /CIDSystemInfo"
                + " << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def /CMapName /Adobe-Identity-UCS def"
                + " /CMapType 2 def 1 begincodespacerange <0000> <FFFF> endcodespacerange 4 beginbfchar"
                + " <0041> <0041> <0042> <0042> <0043> <0043> <0044> <0044> endbfchar endcmap"
                + " CMapName currentdict /CMap defineresource pop end end"));
        Path in = Hostile.write(dir, "shared", r);
        for (PdfALevel level : List.of(PdfALevel.A1B, PdfALevel.A2B, PdfALevel.A2U)) {
            Path out = dir.resolve("shared-" + level + ".pdf");
            PdfToPdfA.convert(in, out, PdfToPdfA.Options.defaults().level(level));
            try (PDDocument d = Loader.loadPDF(out.toFile())) {
                COSDictionary fonts = (COSDictionary) d.getPage(0).getResources().getCOSObject()
                        .getDictionaryObject(COSName.FONT);
                for (String key : List.of("F1", "F2")) {
                    PDType0Font f = (PDType0Font) PDFontFactory.createFont(
                            (COSDictionary) fonts.getDictionaryObject(COSName.getPDFName(key)));
                    PDCIDFontType2 cid = (PDCIDFontType2) f.getDescendantFont();
                    for (int code = 0x41; code <= 0x44; code++) {
                        assertTrue(cid.codeToGID(code) > 0, level + " " + key + " code " + code);
                    }
                }
            }
            assertEquals("AB\nCD", Converted.text(out).strip(), level.toString());
        }
    }
}
