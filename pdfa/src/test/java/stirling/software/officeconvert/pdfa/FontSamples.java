package stirling.software.officeconvert.pdfa;

import static stirling.software.officeconvert.pdfa.RuleSamples.doc;
import static stirling.software.officeconvert.pdfa.RuleSamples.tagged;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDFontFactory;
import org.apache.pdfbox.pdmodel.font.PDTrueTypeFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.encoding.WinAnsiEncoding;

final class FontSamples {

    private FontSamples() {}

    static void register() {
        doc("f01_font_dictionaries", Set.of("1:6.3.2-1", "1:6.3.2-3", "1:6.3.2-4", "1:6.3.2-5", "1:6.3.2-6",
                "2:6.2.11.2-1", "2:6.2.11.2-3", "2:6.2.11.2-4", "2:6.2.11.2-5", "2:6.2.11.2-6"), d -> {
                    PDFont a = trueType(d);
                    PDFont b = trueType(d);
                    PDFont c = trueType(d);
                    a.getCOSObject().removeItem(COSName.TYPE);
                    b.getCOSObject().removeItem(COSName.BASE_FONT);
                    for (COSName k : new COSName[] {COSName.FIRST_CHAR, COSName.LAST_CHAR, COSName.WIDTHS}) {
                        c.getCOSObject().removeItem(k);
                    }
                    page(d, "BT /A 14 Tf 50 780 Td (No Type entry) Tj /B 14 Tf 0 -30 Td (No BaseFont entry) Tj "
                            + "/C 14 Tf 0 -30 Td (No widths) Tj ET", a, b, c);
                });
        doc("f02_cmaps", Set.of("1:6.3.3.1-1", "1:6.3.3.3-1", "1:6.3.3.3-2", "1:6.1.12-10", "2:6.2.11.3.1-1",
                "2:6.2.11.3.3-2", "2:6.2.11.3.3-3", "2:6.1.13-10"), d -> {
                    PDType0Font predefined = PDType0Font.load(d, Samples.liberation(), false);
                    predefined.getCOSObject().setItem(COSName.ENCODING, COSName.getPDFName("UniJIS-UCS2-H"));
                    PDType0Font vertical = PDType0Font.load(d, Samples.liberation(), false);
                    vertical.getCOSObject().setItem(COSName.ENCODING, cmap(d, "Custom-V", 0, 1, "", 65_536));
                    PDType0Font chained = PDType0Font.load(d, Samples.liberation(), false);
                    COSStream chain = cmap(d, "Chained", 0, 0, "/Base usecmap ", 40);
                    chain.setItem(COSName.getPDFName("UseCMap"), cmap(d, "Base", 0, 0, "", 30));
                    chained.getCOSObject().setItem(COSName.ENCODING, chain);
                    page(d, "BT /A 14 Tf 50 780 Td <00410042> Tj /B 14 Tf 0 -30 Td <0041> Tj "
                            + "/C 14 Tf 0 -30 Td <0041> Tj ET", predefined, vertical, chained);
                });
        doc("f03_glyphs_and_unicode", Set.of("1:6.3.5-3", "2:6.2.11.4.2-2", "2:6.2.11.7.2-2"), d -> {
            PDType0Font noSet = PDType0Font.load(d, Samples.liberation(), true);
            PDType0Font badSet = PDType0Font.load(d, Samples.liberation(), true);
            PDType0Font full = PDType0Font.load(d, Samples.liberation(), false);
            PDPage p = page(d, "", noSet, badSet, full);
            try (var cs = new PDPageContentStream(d, p, PDPageContentStream.AppendMode.APPEND, false)) {
                Samples.text(cs, noSet, 14, 50, 780, "A subset without a CIDSet");
                Samples.text(cs, badSet, 14, 50, 740, "A CIDSet that lacks glyphs the subset has");
                Samples.text(cs, full, 14, 50, 700, "A ToUnicode map to U+FEFF");
            }
            d.save(new ByteArrayOutputStream());
            descriptor(noSet).removeItem(COSName.CID_SET);
            COSStream set = d.getDocument().createCOSStream();
            try (OutputStream o = set.createOutputStream()) {
                o.write(new byte[] {(byte) 0x80});
            }
            descriptor(badSet).setItem(COSName.CID_SET, set);
            byte[] code = full.encode("A");
            COSStream tu = d.getDocument().createCOSStream();
            try (OutputStream o = tu.createOutputStream()) {
                o.write(("/CIDInit /ProcSet findresource begin 12 dict begin begincmap /CMapName /Bad def "
                        + "1 begincodespacerange <0000> <FFFF> endcodespacerange 1 beginbfchar "
                        + String.format("<%02X%02X> <FEFF>", code[0], code[1])
                        + " endbfchar endcmap CMapName currentdict /CMap defineresource pop end end")
                        .getBytes(StandardCharsets.US_ASCII));
            }
            full.getCOSObject().setItem(COSName.TO_UNICODE, tu);
        });
        doc("f04_truetype_encodings", Set.of("1:6.3.5-1", "1:6.3.7-1", "1:6.3.7-2", "2:6.2.11.4.1-2",
                "2:6.2.11.6-2", "2:6.2.11.6-3", "2:6.2.11.8-1"), d -> {
                    PDFont notdef = trueType(d);
                    PDFont standard = trueType(d);
                    COSDictionary enc = new COSDictionary();
                    enc.setItem(COSName.BASE_ENCODING, COSName.STANDARD_ENCODING);
                    standard.getCOSObject().setItem(COSName.ENCODING, enc);
                    PDFont symbolic = trueType(d);
                    symbolic.getFontDescriptor().setSymbolic(true);
                    symbolic.getFontDescriptor().setNonSymbolic(false);
                    PDType0Font cid = PDType0Font.load(d, Samples.liberation(), false);
                    page(d, "BT /A 14 Tf 50 780 Td (Undefined code: ) Tj <81> Tj /B 14 Tf 0 -30 Td "
                            + "(StandardEncoding) Tj /C 14 Tf 0 -30 Td (Symbolic with an encoding) Tj "
                            + "/D 14 Tf 0 -30 Td <002C270F0000> Tj ET", notdef, standard, symbolic, cid);
                });
        tagged("f05_private_use_text", Set.of("2:6.2.11.7.3-1"), d -> {
            Samples.ALL.get("s21_tagged").make(d);
            PDPage p = d.getPage(0);
            PDType0Font f = PDType0Font.load(d, Samples.liberation(), false);
            p.getResources().put(COSName.getPDFName("PU"), f);
            byte[] code = f.encode("A");
            COSStream tu = d.getDocument().createCOSStream();
            try (OutputStream o = tu.createOutputStream()) {
                o.write(("/CIDInit /ProcSet findresource begin 12 dict begin begincmap /CMapName /P def "
                        + "1 begincodespacerange <0000> <FFFF> endcodespacerange 1 beginbfchar "
                        + String.format("<%02X%02X> <E000>", code[0], code[1])
                        + " endbfchar endcmap CMapName currentdict /CMap defineresource pop end end")
                        .getBytes(StandardCharsets.US_ASCII));
            }
            f.getCOSObject().setItem(COSName.TO_UNICODE, tu);
            COSStream extra = d.getDocument().createCOSStream();
            try (OutputStream o = extra.createOutputStream()) {
                o.write(String.format("/Artifact BMC BT /PU 12 Tf 50 700 Td <%02X%02X> Tj ET EMC", code[0], code[1])
                        .getBytes(StandardCharsets.US_ASCII));
            }
            ((COSArray) p.getCOSObject().getDictionaryObject(COSName.CONTENTS)).add(extra);
        });
        doc("f06_font_programs", Set.of("1:6.3.5-2", "1:6.3.2-7", "2:6.2.11.4.2-1", "2:6.2.11.2-7"), d -> {
            COSDictionary subset = OutlineCompactionTest.font(d, false);
            subset.setName(COSName.BASE_FONT, "ABCDEF+TestType");
            ((COSDictionary) subset.getDictionaryObject(COSName.FONT_DESC)).setName(COSName.FONT_NAME, "ABCDEF+TestType");
            COSDictionary charset = OutlineCompactionTest.font(d, false);
            charset.setName(COSName.BASE_FONT, "GHIJKL+TestType");
            ((COSDictionary) charset.getDictionaryObject(COSName.FONT_DESC)).setName(COSName.FONT_NAME, "GHIJKL+TestType");
            ((COSDictionary) charset.getDictionaryObject(COSName.FONT_DESC)).setString(COSName.CHAR_SET, "/A/B");
            COSDictionary bogus = OutlineCompactionTest.font(d, true);
            COSDictionary fd = (COSDictionary) bogus.getDictionaryObject(COSName.FONT_DESC);
            ((COSStream) fd.getDictionaryObject(COSName.FONT_FILE3)).setItem(COSName.SUBTYPE, COSName.getPDFName("Bogus"));
            page(d, "BT /A 20 Tf 50 760 Td (ABC) Tj /B 20 Tf 0 -40 Td (ABC) Tj /C 20 Tf 0 -40 Td (ABC) Tj ET",
                    PDFontFactory.createFont(subset), PDFontFactory.createFont(charset), PDFontFactory.createFont(bogus));
        });
        doc("f07_codes_in_the_cmap", Set.of(), d -> {
            org.apache.fontbox.ttf.TrueTypeFont lib = Samples.liberation();
            java.util.TreeMap<Integer, Integer> map = new java.util.TreeMap<>(java.util.Map.of(0x41, lib.nameToGID("A"),
                    0x92, lib.nameToGID("quoteright")));
            byte[] program = withCmap(lib, Cmaps.table(java.util.List.of(new Cmaps.Subtable(1, 0, map),
                    new Cmaps.Subtable(3, 1, map))));
            COSDictionary f = Samples.trueTypeUnembedded("Broken", new int[0]);
            f.setInt(COSName.FIRST_CHAR, 65);
            f.setInt(COSName.LAST_CHAR, 146);
            COSArray widths = new COSArray();
            for (int c = 65; c <= 146; c++) {
                int gid = c == 65 ? lib.nameToGID("A") : c == 146 ? lib.nameToGID("quoteright") : 0;
                widths.add(org.apache.pdfbox.cos.COSInteger.get(gid == 0 ? 0
                        : Math.round(lib.getAdvanceWidth(gid) * 1000f / lib.getUnitsPerEm())));
            }
            f.setItem(COSName.WIDTHS, widths);
            COSStream file = d.getDocument().createCOSStream();
            try (OutputStream o = file.createOutputStream(COSName.FLATE_DECODE)) {
                o.write(program);
            }
            file.setInt(COSName.LENGTH1, program.length);
            ((COSDictionary) f.getDictionaryObject(COSName.FONT_DESC)).setItem(COSName.FONT_FILE2, file);
            page(d, "BT /A 30 Tf 50 760 Td (A\\222A) Tj ET", PDFontFactory.createFont(f));
        });
        doc("f08_tounicode_ranges", Set.of("2:6.2.11.7.2-1"), d -> {
            PDType0Font f = PDType0Font.load(d, Samples.liberation(), false);
            String lo = "0000";
            String hi = "03FF";
            COSStream tu = d.getDocument().createCOSStream();
            try (OutputStream o = tu.createOutputStream()) {
                o.write(("/CIDInit /ProcSet findresource begin 12 dict begin begincmap /CMapName /Wide def "
                        + "1 begincodespacerange <0000> <FFFF> endcodespacerange 1 beginbfrange <" + lo + "> <" + hi
                        + "> <0020> endbfrange endcmap CMapName currentdict /CMap defineresource pop end end")
                        .getBytes(StandardCharsets.US_ASCII));
            }
            f.getCOSObject().setItem(COSName.TO_UNICODE, tu);
            PDPage p = page(d, "", f);
            try (var cs = new PDPageContentStream(d, p, PDPageContentStream.AppendMode.APPEND, false)) {
                Samples.text(cs, f, 14, 50, 780, "A range across a byte boundary: \u0141\u00f3d\u017a");
            }
        });
        doc("f09_font_types_and_maps", Set.of("2:6.2.11.3.2-1", "2:6.2.11.6-1"), d -> {
            PDFont noSubtype = trueType(d);
            noSubtype.getCOSObject().setItem(COSName.SUBTYPE, COSName.getPDFName("Bogus"));
            PDType0Font noMap = PDType0Font.load(d, Samples.liberation(), false);
            org.apache.fontbox.ttf.TrueTypeFont lib = Samples.liberation();
            java.util.TreeMap<Integer, Integer> map = new java.util.TreeMap<>();
            for (char c = 'A'; c <= 'Z'; c++) {
                map.put(0xF000 + c, lib.nameToGID(String.valueOf(c)));
            }
            byte[] program = withCmap(lib, Cmaps.table(java.util.List.of(new Cmaps.Subtable(3, 0, map))));
            COSDictionary symbolOnly = Samples.trueTypeUnembedded("SymbolOnly", Samples.arialWidths());
            COSStream file = d.getDocument().createCOSStream();
            try (OutputStream o = file.createOutputStream(COSName.FLATE_DECODE)) {
                o.write(program);
            }
            file.setInt(COSName.LENGTH1, program.length);
            ((COSDictionary) symbolOnly.getDictionaryObject(COSName.FONT_DESC)).setItem(COSName.FONT_FILE2, file);
            StringBuilder hex = new StringBuilder();
            for (byte b : noMap.encode("No CIDToGIDMap")) {
                hex.append(String.format("%02X", b));
            }
            PDPage p = page(d, "BT /A 14 Tf 50 780 Td (No Subtype) Tj ET BT /B 14 Tf 50 740 Td <" + hex + "> Tj ET "
                    + "BT /C 14 Tf 50 700 Td (ABC) Tj ET", noSubtype, noMap, PDFontFactory.createFont(symbolOnly));
            d.save(new ByteArrayOutputStream());
            ((COSDictionary) ((COSArray) noMap.getCOSObject().getDictionaryObject(COSName.DESCENDANT_FONTS))
                    .getObject(0)).removeItem(COSName.CID_TO_GID_MAP);
        });
    }

    static byte[] withCmap(org.apache.fontbox.ttf.TrueTypeFont font, byte[] cmap) throws Exception {
        java.util.TreeMap<String, byte[]> tables = new java.util.TreeMap<>();
        for (var e : font.getTableMap().entrySet()) {
            tables.put(e.getKey(), "cmap".equals(e.getKey()) ? cmap : font.getTableBytes(e.getValue()));
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        java.io.DataOutputStream o = new java.io.DataOutputStream(out);
        o.writeInt(0x00010000);
        o.writeShort(tables.size());
        o.writeShort(0);
        o.writeShort(0);
        o.writeShort(0);
        int offset = 12 + 16 * tables.size();
        for (var e : tables.entrySet()) {
            o.writeBytes(e.getKey());
            o.writeInt(0);
            o.writeInt(offset);
            o.writeInt(e.getValue().length);
            offset += (e.getValue().length + 3) & ~3;
        }
        for (byte[] t : tables.values()) {
            o.write(t);
            o.write(new byte[((t.length + 3) & ~3) - t.length]);
        }
        return out.toByteArray();
    }

    static COSDictionary descriptor(PDType0Font f) {
        return (COSDictionary) ((COSDictionary) ((COSArray) f.getCOSObject().getDictionaryObject(COSName.DESCENDANT_FONTS))
                .getObject(0)).getDictionaryObject(COSName.FONT_DESC);
    }

    static PDFont trueType(PDDocument d) throws Exception {
        return PDTrueTypeFont.load(d, Samples.liberation(), WinAnsiEncoding.INSTANCE);
    }

    static PDPage page(PDDocument d, String content, PDFont... fonts) throws Exception {
        PDPage p = Samples.page(d);
        PDResources res = new PDResources();
        for (int i = 0; i < fonts.length; i++) {
            res.getCOSObject().setItem(COSName.FONT, res.getCOSObject().getDictionaryObject(COSName.FONT) == null
                    ? new COSDictionary() : res.getCOSObject().getDictionaryObject(COSName.FONT));
            ((COSDictionary) res.getCOSObject().getDictionaryObject(COSName.FONT))
                    .setItem(String.valueOf((char) ('A' + i)), fonts[i].getCOSObject());
        }
        p.setResources(res);
        if (!content.isEmpty()) {
            Samples.raw(p, d, content);
        }
        return p;
    }

    static COSStream cmap(PDDocument d, String name, int dictMode, int streamMode, String use, int cid)
            throws Exception {
        COSStream s = d.getDocument().createCOSStream();
        s.setItem(COSName.TYPE, COSName.getPDFName("CMap"));
        s.setName(COSName.getPDFName("CMapName"), name);
        COSDictionary info = new COSDictionary();
        info.setString(COSName.REGISTRY, "Adobe");
        info.setString(COSName.ORDERING, "Identity");
        info.setInt(COSName.SUPPLEMENT, 0);
        s.setItem(COSName.CIDSYSTEMINFO, info);
        s.setInt(COSName.getPDFName("WMode"), dictMode);
        String body = "/CIDInit /ProcSet findresource begin 12 dict begin begincmap " + use
                + "/CIDSystemInfo << /Registry (Adobe) /Ordering (Identity) /Supplement 0 >> def /CMapName /" + name
                + " def /CMapType 1 def /WMode " + streamMode + " def 1 begincodespacerange <0000> <FFFF> "
                + "endcodespacerange 2 begincidrange <0000> <0040> 0 <0041> <0041> " + cid + " endcidrange "
                + "endcmap CMapName currentdict /CMap defineresource pop end end";
        try (OutputStream o = s.createOutputStream()) {
            o.write(body.getBytes(StandardCharsets.US_ASCII));
        }
        return s;
    }
}
