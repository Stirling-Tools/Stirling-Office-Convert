package stirling.software.officeconvert.pdfa;

import java.io.ByteArrayOutputStream;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;

/** Inputs for the rule classes the other sample sets leave out, kept as raw syntax so odd forms survive. */
final class SweepSamples {

    static final Map<String, RuleSamples.Source> ALL = new LinkedHashMap<>();

    private static final String TEXT = "/Artifact BMC BT /F1 14 Tf 72 780 Td (Sweep sample) Tj ET EMC";

    private static final String RECT = "/Rect[72 600 172 640]";

    private SweepSamples() {}

    static {
        annotations();
        actionsAndForms();
        colourAndTransparency();
        fontsAndText();
        documentLevel();
        resourcesAndTags();
    }

    /** One page whose extra objects are numbered from 5 up. */
    static byte[] page(String catalog, String page, String resources, String content, String... objects) {
        RawPdf r = RawPdf.page(RawPdf.helvetica() + resources, TEXT + " " + content);
        r.set(1, "<</Type/Catalog/Pages 2 0 R" + catalog + ">>");
        r.set(3, "<</Type/Page/Parent 2 0 R/MediaBox[0 0 595 842]/Resources<<" + RawPdf.helvetica() + resources
                + ">>/Contents 4 0 R" + page + ">>");
        for (String o : objects) {
            r.add(o);
        }
        return r.bytes();
    }

    private static String refs(int from, int count) {
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < count; i++) {
            b.append(from + i).append(" 0 R ");
        }
        return b.append(']').toString();
    }

    private static String form(String content) {
        return RawPdf.stream("/Type/XObject/Subtype/Form/BBox[0 0 100 40]", content);
    }

    private static void annotations() {
        String[] subtypes = {"Text", "Link", "FreeText/DA(/Helv 10 Tf 0 g)", "Line/L[0 0 10 10]", "Square",
            "Circle", "Polygon/Vertices[0 0 10 10 20 0]", "PolyLine/Vertices[0 0 10 10]",
            "Highlight/QuadPoints[72 640 172 640 72 600 172 600]", "Underline/QuadPoints[72 640 172 640 72 600 172 600]",
            "Squiggly/QuadPoints[72 640 172 640 72 600 172 600]", "StrikeOut/QuadPoints[72 640 172 640 72 600 172 600]",
            "Stamp/Name/Approved", "Caret", "Ink/InkList[[0 0 10 10]]", "Sound", "Movie", "Screen", "PrinterMark",
            "TrapNet", "Watermark", "3D", "Redact", "RichMedia", "Projection", "FileAttachment"};
        String[] flags = {"", "/F 2", "/F 32", "/F 1", "/F 4/CA 0.5", "/F 4/C[1 0 0 0]/IC[0 1]", "/F 260",
            "/F 4/BM/Multiply"};
        StringBuilder noAp = new StringBuilder();
        StringBuilder withAp = new StringBuilder();
        String[] objs = new String[subtypes.length * 2 + 1];
        for (int i = 0; i < subtypes.length; i++) {
            objs[i] = "<</Type/Annot/Subtype/" + subtypes[i] + RECT + flags[i % flags.length] + "/Contents(note "
                    + i + ")>>";
            objs[subtypes.length + i] = "<</Type/Annot/Subtype/" + subtypes[i] + RECT + "/F 4"
                    + flags[(i + 3) % flags.length].replace("/F 4", "").replaceAll("/F \\d+", "")
                    + "/AP<</N " + (5 + 2 * subtypes.length) + " 0 R/D " + (5 + 2 * subtypes.length) + " 0 R>>>>";
            noAp.append(5 + i).append(" 0 R ");
            withAp.append(5 + subtypes.length + i).append(" 0 R ");
        }
        objs[2 * subtypes.length] = form("/GS1 gs 1 0 0 rg 0 0 100 40 re f");
        ALL.put("w01_annotation_subtypes", () -> page("", "/Annots[" + noAp + "]", "", "", objs));
        String[] ap = objs.clone();
        ap[2 * subtypes.length] = RawPdf.stream("/Type/XObject/Subtype/Form/BBox[0 0 100 40]/Resources<</ExtGState"
                + "<</GS1<</ca 0.4/BM/Screen>>>>>>/Group<</S/Transparency/CS/DeviceRGB>>", "/GS1 gs 1 0 0 rg 0 0 100 40 re f");
        ALL.put("w02_annotation_appearances", () -> page("", "/Annots[" + withAp + "]", "", "", ap));
        ALL.put("w03_appearance_states", () -> page("/AcroForm<</Fields[5 0 R 6 0 R]>>", "/Annots[5 0 R 6 0 R 7 0 R]",
                "", "",
                "<</Type/Annot/Subtype/Widget/FT/Btn/T(cb)/F 4" + RECT + "/AP<</N<</On 8 0 R/Off 8 0 R>>>>>>",
                "<</Type/Annot/Subtype/Widget/FT/Btn/T(cb2)/F 4/AS/Yes/Rect[72 500 172 540]/AP<</N<</On 8 0 R>>/D"
                        + "<</On 8 0 R>>/R 8 0 R>>>>",
                "<</Type/Annot/Subtype/Text/F 4/Rect[300 300 300 300]/AP<</N<</A 8 0 R/B 8 0 R>>>>>>",
                form("0 0 1 rg 0 0 100 40 re f")));
    }

    private static void actionsAndForms() {
        String js = "<</S/JavaScript/JS(app.alert\\(1\\))>>";
        String[] kinds = {"/S/Launch/F(calc.exe)", "/S/GoToR/F(other.pdf)/D[0/Fit]", "/S/Thread/D 0",
            "/S/URI/URI(https://example.com)/Next " + js, "/S/SubmitForm/F<</FS/URL/F(https://example.com)>>",
            "/S/ResetForm", "/S/ImportData/F(data.fdf)", "/S/Hide/T(f)", "/S/SetOCGState/State[/OFF]",
            "/S/Rendition/OP 0", "/S/Trans", "/S/GoTo3DView/TA 0/V 0", "/S/GoToE/D[0/Fit]", "/S/Sound",
            "/S/Movie", "/S/Named/N/Foo", "/S/Named/N/FirstPage", "/S/GoTo/D[3 0 R/XYZ 0 0 0]/Next[" + js + "]"};
        String[] objs = new String[kinds.length + 3];
        for (int i = 0; i < kinds.length; i++) {
            objs[i] = "<</Type/Annot/Subtype/Link/F 4/Border[0 0 0]/Rect[72 " + (100 + 30 * i) + " 172 " + (120 + 30 * i)
                    + "]/A<<" + kinds[i] + ">>>>";
        }
        int o = 5 + kinds.length;
        objs[kinds.length] = "<</Type/Outlines/First " + (o + 1) + " 0 R/Last " + (o + 1) + " 0 R/Count 1>>";
        objs[kinds.length + 1] = "<</Title(Go)/Parent " + o + " 0 R/A<</S/GoToR/F(x.pdf)/D[0/Fit]/Next " + js
                + ">>>>";
        objs[kinds.length + 2] = "<</Names[(a)" + js + "]>>";
        ALL.put("w04_actions_everywhere", () -> page("/OpenAction<</S/Launch/F(run.exe)>>/AA<</WC " + js
                + ">>/Outlines " + o + " 0 R/Names<</JavaScript " + (o + 2) + " 0 R>>",
                "/AA<</O " + js + "/C<</S/Named/N/Print>>>>/Annots" + refs(5, kinds.length), "", "", objs));
        String dr = "/DR<</Font<</Helv<</Type/Font/Subtype/Type1/BaseFont/Helvetica/Encoding/WinAnsiEncoding>>>>>>";
        ALL.put("w05_form_fields", () -> page("/AcroForm<</NeedAppearances true/DA(/Helv 0 Tf 0 g)" + dr
                + "/Fields[5 0 R 6 0 R 7 0 R 8 0 R 9 0 R 10 0 R 11 0 R]/CO[5 0 R]>>",
                "/Annots[5 0 R 6 0 R 7 0 R 8 0 R 9 0 R 12 0 R 13 0 R 10 0 R 11 0 R]", "", "",
                "<</Type/Annot/Subtype/Widget/FT/Tx/T(name)/V(Jane \\(test\\))/F 4/Rect[72 700 300 720]/MK<<>>"
                        + "/AA<</K " + js + "/C " + js + ">>/RV(<p>rich</p>)>>",
                "<</Type/Annot/Subtype/Widget/FT/Ch/Ff 131072/T(combo)/Opt[(One)(Two)]/V(Two)/F 4/Rect[72 650 300 670]>>",
                "<</Type/Annot/Subtype/Widget/FT/Ch/T(list)/Opt[[(a)(Apple)][(b)(Banana)]]/V(b)/F 4"
                        + "/Rect[72 560 300 640]>>",
                "<</Type/Annot/Subtype/Widget/FT/Btn/Ff 65536/T(push)/F 4/Rect[72 520 172 540]/A " + js
                        + "/MK<</CA(Press)>>>>",
                "<</FT/Btn/Ff 49152/T(radio)/V/b/Kids[12 0 R 13 0 R]>>",
                "<</Type/Annot/Subtype/Widget/FT/Sig/T(sign)/F 4/Rect[72 420 272 460]>>",
                "<</Type/Annot/Subtype/Widget/FT/Tx/T(multi)/Ff 4096/V(line one\\nline two)/F 4/Q 2"
                        + "/DA(/Helv 9 Tf 1 0 0 rg)/Rect[300 300 500 380]>>",
                "<</Type/Annot/Subtype/Widget/Parent 9 0 R/AS/a/F 4/Rect[72 480 92 500]/MK<</CA(l)>>>>",
                "<</Type/Annot/Subtype/Widget/Parent 9 0 R/AS/b/F 4/Rect[100 480 120 500]/AP<</N<</b 14 0 R>>>>>>",
                form("0 0 0 rg 0 0 20 20 re f")));
    }

    private static void colourAndTransparency() {
        ALL.put("w06_colour_spaces", () -> page("", "", "/ColorSpace<</Lab[/Lab<</WhitePoint[0.9505 1 1.089]"
                + "/Range[-100 100 -100 100]>>]/CalR[/CalRGB<</WhitePoint[0.9505 1 1.089]/Gamma[2.2 2.2 2.2]>>]"
                + "/CalG[/CalGray<</WhitePoint[0.9505 1 1.089]/Gamma 2.2>>]/IdxLab[/Indexed[/Lab<</WhitePoint"
                + "[0.9505 1 1.089]>>]1<0000008080FF8080>]/IdxRgb[/Indexed/DeviceRGB 1<FF000000FF00>]/All[/Separation"
                + "/All/DeviceCMYK 5 0 R]/None[/Separation/None/DeviceGray 6 0 R]/Spot[/Separation/Gold/Lab<<"
                + "/WhitePoint[0.9505 1 1.089]>> 7 0 R]>>/Shading<</Sh1<</ShadingType 2/ColorSpace/DeviceCMYK"
                + "/Coords[0 0 100 0]/Function<</FunctionType 2/Domain[0 1]/C0[0 0 0 0]/C1[1 0 0 0]/N 1>>>>>>"
                + "/XObject<</Im1 8 0 R>>/ExtGState<</OP<</OP true/op true/OPM 1>>>>",
                "/Lab cs 50 10 10 sc 72 700 50 50 re f /CalR cs 1 0 0 sc 130 700 50 50 re f /CalG cs 0.5 sc "
                        + "190 700 50 50 re f /IdxLab cs 1 sc 250 700 50 50 re f /IdxRgb cs 1 sc 310 700 50 50 re f "
                        + "/All cs 1 sc 370 700 50 50 re f /None cs 1 sc 430 700 50 50 re f /Spot cs 1 sc "
                        + "490 700 50 50 re f /Bogus ri /OP gs 0 0 0 1 k 72 600 100 50 re f q 72 500 100 50 re W n "
                        + "/Sh1 sh Q q 100 0 0 50 72 420 cm /Im1 Do Q",
                "<</FunctionType 2/Domain[0 1]/C0[0 0 0 0]/C1[1 1 1 1]/N 1>>",
                "<</FunctionType 2/Domain[0 1]/C0[0]/C1[1]/N 1>>",
                "<</FunctionType 2/Domain[0 1]/C0[100 0 0]/C1[60 20 60]/N 1>>",
                RawPdf.stream("/Type/XObject/Subtype/Image/Width 2/Height 1/BitsPerComponent 8/Intent/Bogus"
                        + "/ColorSpace[/Lab<</WhitePoint[0.9505 1 1.089]>>]/Decode[0 100 -128 127 -128 127]",
                        "\u0080\u0080\u0080\u0040\u00c0\u0040")));
        ALL.put("w07_overprint_icc_cmyk", () -> RuleSamples.save(d -> {
            var p = Samples.page(d);
            p.setResources(new org.apache.pdfbox.pdmodel.PDResources());
            COSDictionary cs = new COSDictionary();
            cs.setItem("CMYK", ColourSamples.iccBased(d, IccProfiles.cmyk(), 4));
            COSDictionary gs = new COSDictionary();
            COSDictionary op = new COSDictionary();
            op.setBoolean(COSName.OP, true);
            op.setBoolean(COSName.OP_NS, true);
            op.setInt(COSName.OPM, 1);
            gs.setItem("OP", op);
            p.getResources().getCOSObject().setItem(COSName.COLORSPACE, cs);
            p.getResources().getCOSObject().setItem(COSName.EXT_G_STATE, gs);
            Samples.raw(p, d, "/OP gs /CMYK cs 0 1 0 0 sc 72 700 200 50 re f /CMYK CS 1 0 0 0 SC 72 600 200 50 re S");
        }));
        ALL.put("w08_blend_and_masks", () -> page("", "/Group<</S/Transparency/CS/DeviceRGB/I true>>",
                "/ExtGState<</M<</BM/Multiply/CA 0.5/ca 0.5>>/A<</BM[/Bogus/Screen]>>/C<</BM/Compatible>>/S<<"
                        + "/SMask<</S/Luminosity/G 5 0 R/BC[0]/TR/Identity>>>>/K<</SMask/None/AIS true>>>>"
                        + "/XObject<</Fm 6 0 R/Im 7 0 R>>",
                "/M gs 1 0 0 rg 72 700 100 50 re f /A gs 0 1 0 rg 100 690 100 50 re f /C gs /S gs 0 0 1 rg "
                        + "72 600 100 50 re f /K gs /Fm Do q 50 0 0 50 300 600 cm /Im Do Q",
                RawPdf.stream("/Type/XObject/Subtype/Form/BBox[0 0 595 842]/Group<</S/Transparency/CS/DeviceGray>>",
                        "0.5 g 0 0 595 842 re f"),
                RawPdf.stream("/Type/XObject/Subtype/Form/BBox[0 0 595 842]/Group<</S/Transparency/CS/DeviceCMYK"
                        + "/K true/I true>>", "0 1 0 0 k 72 500 100 50 re f"),
                RawPdf.stream("/Type/XObject/Subtype/Image/Width 1/Height 1/BitsPerComponent 8/ColorSpace/DeviceRGB"
                        + "/SMask 8 0 R", "\u00ff\u0000\u0000"),
                RawPdf.stream("/Type/XObject/Subtype/Image/Width 1/Height 1/BitsPerComponent 8/ColorSpace/DeviceGray"
                        + "/Matte[0 0 0]", "\u0080")));
    }

    private static void fontsAndText() {
        ALL.put("w09_type3_notdef", () -> page("", "", "/Font<</T3 5 0 R>>/XObject<</Im 8 0 R>>",
                "BT /T3 24 Tf 72 700 Td <00414243> Tj ET",
                "<</Type/Font/Subtype/Type3/FontBBox[0 0 750 750]/FontMatrix[0.001 0 0 0.001 0 0]/CharProcs 6 0 R"
                        + "/Encoding<</Type/Encoding/Differences[0/.notdef 65/a/b/missing]>>/FirstChar 0/LastChar 67"
                        + "/Widths[500" + " 0".repeat(64) + " 600 600 600]/Resources<</XObject<</Im 8 0 R>>>>>>",
                "<</.notdef 7 0 R/a 7 0 R/b 9 0 R>>",
                RawPdf.stream("", "600 0 d0 0 0 500 500 re f"),
                RawPdf.stream("/Type/XObject/Subtype/Image/Width 1/Height 1/BitsPerComponent 8/ColorSpace/DeviceRGB",
                        "\u0000\u0080\u00ff"),
                RawPdf.stream("", "600 0 0 0 600 600 d1 q 500 0 0 500 0 0 cm /Im Do Q")));
        ALL.put("w10_cid_cmaps", () -> page("", "", "/Font<</J 5 0 R/U 7 0 R/V 10 0 R>>",
                "BT /J 18 Tf 72 700 Td <30423044> Tj /U 18 Tf 72 650 Td <0041004200FF> Tj /V 18 Tf 300 650 Td "
                        + "<00410042> Tj ET",
                "<</Type/Font/Subtype/Type0/BaseFont/MS-Mincho/Encoding/UniJIS-UCS2-H/DescendantFonts[6 0 R]>>",
                "<</Type/Font/Subtype/CIDFontType0/BaseFont/MS-Mincho/CIDSystemInfo<</Registry(Adobe)/Ordering"
                        + "(Japan1)/Supplement 6>>/FontDescriptor<</Type/FontDescriptor/FontName/MS-Mincho/Flags 4"
                        + "/FontBBox[0 -141 1000 859]/ItalicAngle 0/Ascent 859/Descent -141/CapHeight 700/StemV 80>>"
                        + "/DW 1000>>",
                "<</Type/Font/Subtype/Type0/BaseFont/ArialMT/Encoding 8 0 R/DescendantFonts[9 0 R]>>",
                RawPdf.stream("/Type/CMap/CMapName/Custom/CIDSystemInfo<</Registry(Adobe)/Ordering(Identity)"
                        + "/Supplement 0>>/UseCMap/Identity-H/WMode 1", "/CIDInit /ProcSet findresource begin 12 dict "
                        + "begin begincmap /CMapName /Custom def 1 begincodespacerange <0000> <FFFF> endcodespacerange "
                        + "1 begincidrange <0041> <0042> 36 endcidrange endcmap CMapName currentdict /CMap "
                        + "defineresource pop end end"),
                "<</Type/Font/Subtype/CIDFontType2/BaseFont/ArialMT/CIDSystemInfo<</Registry(Adobe)/Ordering"
                        + "(Identity)/Supplement 0>>/FontDescriptor<</Type/FontDescriptor/FontName/ArialMT/Flags 32"
                        + "/FontBBox[-665 -325 2000 1006]/ItalicAngle 0/Ascent 905/Descent -212/CapHeight 716/StemV 80>>"
                        + "/W[36[667 667]]>>",
                "<</Type/Font/Subtype/Type0/BaseFont/Vert/Encoding/Identity-V/DescendantFonts[11 0 R]>>",
                "<</Type/Font/Subtype/CIDFontType2/BaseFont/ArialMT/CIDSystemInfo<</Registry(Adobe)/Ordering"
                        + "(Identity)/Supplement 0>>/CIDToGIDMap/Identity/FontDescriptor<</Type/FontDescriptor"
                        + "/FontName/ArialMT/Flags 32/FontBBox[0 0 1000 1000]/ItalicAngle 0/Ascent 905/Descent -212"
                        + "/CapHeight 716/StemV 80>>>>"));
        ALL.put("w11_symbolic_invisible_text", () -> page("", "", "/Font<</S 5 0 R/W 6 0 R/D 7 0 R>>",
                "BT /S 20 Tf 72 700 Td (abc) Tj /W 20 Tf 72 650 Td (\\001\\002\\377) Tj 3 Tr /D 20 Tf 72 600 Td "
                        + "(hidden text) Tj 7 Tr (clip) Tj ET",
                "<</Type/Font/Subtype/Type1/BaseFont/Symbol>>",
                "<</Type/Font/Subtype/TrueType/BaseFont/Wingdings-Regular/FirstChar 1/LastChar 255/FontDescriptor"
                        + "<</Type/FontDescriptor/FontName/Wingdings-Regular/Flags 4/FontBBox[0 -210 1359 899]"
                        + "/ItalicAngle 0/Ascent 899/Descent -210/CapHeight 722/StemV 80>>>>",
                "<</Type/Font/Subtype/Type1/BaseFont/Courier/Encoding<</Differences[97/uni0041/g123/ff/zzz]>>>>"));
        ALL.put("w12_limits_outside_content", () -> page("/PieceInfo<</App<</LastModified(D:2026)/Private<</Long("
                + "x".repeat(70_000) + ")/N/" + "n".repeat(200) + "/R 1" + "0".repeat(40) + ".5/I 3000000000/Tiny "
                + "0.000000000000000000000000000000000000001/H<4142 4G>/E<>/Arr[" + "1 ".repeat(9000) + "]>>>>>>",
                "/Annots[5 0 R]", "", "",
                "<</Type/Annot/Subtype/Text/F 4/Rect[72 600 99999999999 640]/Contents<FEFF004100420043004>"
                        + "/NM(" + "y".repeat(40_000) + ")>>"));
    }

    private static void documentLevel() {
        ALL.put("w13_page_boxes", () -> {
            RawPdf r = RawPdf.page(RawPdf.helvetica(), TEXT);
            r.set(2, "<</Type/Pages/Kids[3 0 R 5 0 R 6 0 R 7 0 R]/Count 4>>");
            r.set(3, "<</Type/Page/Parent 2 0 R/MediaBox[0 0 20000 20000]/Resources<<" + RawPdf.helvetica()
                    + ">>/Contents 4 0 R>>");
            r.add("<</Type/Page/Parent 2 0 R/MediaBox[0 0 1 1]/Resources<<" + RawPdf.helvetica() + ">>/Contents 4 0 R>>");
            r.add("<</Type/Page/Parent 2 0 R/MediaBox[0 0 2000 2000]/UserUnit 10/Rotate 45/Resources<<"
                    + RawPdf.helvetica() + ">>/Contents 4 0 R>>");
            r.add("<</Type/Page/Parent 2 0 R/MediaBox[-500 -500 100 100]/CropBox[0 0 900 900]/BleedBox[1 1 0 0]"
                    + "/Resources<<" + RawPdf.helvetica() + ">>/Contents 4 0 R>>");
            return r.bytes();
        });
        String xmp = "<?xpacket begin='\u00ef\u00bb\u00bf' id='W5M0MpCehiHzreSzNTczkc9d' encoding='UTF-8'?>"
                + "<x:xmpmeta xmlns:x='adobe:ns:meta/'><rdf:RDF xmlns:rdf='http://www.w3.org/1999/02/22-rdf-syntax"
                + "-ns#'><rdf:Description rdf:about='uuid:one' xmlns:pdfaid='http://www.aiim.org/pdfa/ns/id/' "
                + "pdfaid:part='3' pdfaid:conformance='A'/><rdf:Description rdf:about='uuid:two' "
                + "xmlns:dc='http://purl.org/dc/elements/1.1/' xmlns:xmp='http://ns.adobe.com/xap/1.0/'><dc:title>"
                + "Plain title</dc:title><xmp:CreateDate>not a date</xmp:CreateDate><xmp:Rating>x</xmp:Rating>"
                + "</rdf:Description></rdf:RDF></x:xmpmeta><?xpacket end='r'?>";
        ALL.put("w14_xmp_and_info", () -> {
            RawPdf r = RawPdf.page(RawPdf.helvetica(), TEXT);
            r.set(1, "<</Type/Catalog/Pages 2 0 R/Metadata 5 0 R/Lang(xx-!!)/ViewerPreferences<</Bogus true>>"
                    + "/PageLabels<</Nums[0<</S/r>>]>>>>");
            r.add(RawPdf.stream("/Type/Metadata/Subtype/XML/Filter/ASCIIHexDecode", hex(xmp) + ">"));
            r.add("<</Title<FEFF00540069007400" + "6C0065>/Author(Ann \\351)/CreationDate(D:2026)/ModDate(yesterday)"
                    + "/Trapped/Bogus/Custom(value)/Producer<>>>");
            r.trailerExtra += "/Info 6 0 R";
            return r.bytes();
        });
        ALL.put("w15_damaged_streams", () -> {
            RawPdf r = RawPdf.page(RawPdf.helvetica() + "/XObject<</Im 7 0 R>>", TEXT);
            r.set(3, "<</Type/Page/Parent 2 0 R/MediaBox[0 0 595 842]/Resources<<" + RawPdf.helvetica()
                    + "/XObject<</Im 7 0 R>>>>/Contents[4 0 R 5 0 R 6 0 R 99 0 R]>>");
            r.set(4, "<</Length 9999>>stream\n" + TEXT + "\nendstream");
            r.add("<</Length 20/Filter/FlateDecode>>stream\nx\u009c\u00d3\u00d7\u00d3\u0001\u0000garbage!!\nendstream");
            r.add("<</Length 10/Filter/ASCII85Decode>>stream\nq 0 0 5 5 re f Q\nendstream");
            r.add("<</Type/XObject/Subtype/Image/Width 8/Height 8/BitsPerComponent 8/ColorSpace/DeviceRGB"
                    + "/Filter/DCTDecode/Length 12>>stream\n\u00ff\u00d8\u00ff\u00e0junkjunk\nendstream");
            return r.bytes();
        });
        ALL.put("w16_embedded_files", () -> page("/Names<</EmbeddedFiles<</Names[(a.txt)5 0 R(b.pdf)7 0 R"
                + "(c.bin)9 0 R]>>>>/Collection<</View/T>>/AF[5 0 R]", "/Annots[11 0 R]", "", "",
                "<</Type/Filespec/F(a.txt)/EF<</F 6 0 R>>>>",
                RawPdf.stream("/Type/EmbeddedFile/Filter/ASCIIHexDecode", "48656c6c6f>"),
                "<</Type/Filespec/F(b.pdf)/UF(b.pdf)/AFRelationship/Bogus/EF<</F 8 0 R/UF 8 0 R>>>>",
                RawPdf.stream("/Type/EmbeddedFile/Subtype/application#2Fpdf", new String(page("", "", "", ""),
                        java.nio.charset.StandardCharsets.ISO_8859_1)),
                "<</Type/Filespec/F(c.bin)/EF<</F 10 0 R>>/RF<<>>>>",
                RawPdf.stream("/Type/EmbeddedFile/Subtype/application#2Foctet-stream/Params<<>>", "\u0000\u0001"),
                "<</Type/Annot/Subtype/FileAttachment/F 4" + RECT + "/FS 9 0 R/Name/Paperclip>>"));
        ALL.put("w17_optional_content", () -> page("/OCProperties<</OCGs[5 0 R 6 0 R]/D<</OFF[6 0 R]/AS[<</Event"
                + "/View/OCGs[5 0 R]/Category[/View]>>]/ListMode/VisiblePages>>/Configs[<</Name(A)/ON[5 0 R]>><</Name"
                + "(A)>><<>>]>>", "", "/Properties<</O1 5 0 R/O2 6 0 R/M 7 0 R/Gone 8 0 R>>",
                "/OC /O1 BDC 72 700 50 50 re f EMC /OC /O2 BDC 72 600 50 50 re f EMC /OC /M BDC 72 500 50 50 re f "
                        + "EMC /OC /Gone BDC 72 400 50 50 re f EMC",
                "<</Type/OCG/Name(Shown)/Intent/Design/Usage<</Print<</PrintState/OFF>>>>>>",
                "<</Type/OCG/Name(Hidden)>>",
                "<</Type/OCMD/VE[/And 5 0 R[/Not 6 0 R]]>>",
                "<</Type/OCG/Name(Not listed)>>"));
        for (String algo : new String[] {"rc4", "aes"}) {
            ALL.put("w18_encrypted_" + algo, () -> {
                try (PDDocument d = new PDDocument()) {
                    Samples.raw(Samples.page(d), d, "");
                    StandardProtectionPolicy p = new StandardProtectionPolicy("owner", "", new AccessPermission());
                    p.setEncryptionKeyLength(algo.equals("rc4") ? 40 : 256);
                    d.protect(p);
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    d.save(out);
                    return out.toByteArray();
                }
            });
        }
        ALL.put("w19_structure_partial", () -> page("/MarkInfo<</Marked true/Suspects true>>/StructTreeRoot 5 0 R"
                + "/Lang(en)", "/StructParents 0/Tabs/S", "", "/P <</MCID 0>> BDC 72 700 50 50 re f EMC",
                "<</Type/StructTreeRoot/K 6 0 R/ParentTree<</Nums[0[6 0 R]]>>/RoleMap<</Para/P/Loop/Loop2/Loop2/Loop>>>>",
                "<</Type/StructElem/S/Loop/P 5 0 R/K[<</Type/MCR/MCID 0/Pg 3 0 R>> 7 0 R]>>",
                "<</Type/StructElem/S/Figure/P 6 0 R/K 99>>"));
    }

    private static void resourcesAndTags() {
        String img = "/Type/XObject/Subtype/Image/Width 1/Height 1/BitsPerComponent ";
        ALL.put("w20_appearance_contents", () -> page("", "/Annots[5 0 R 6 0 R]", "", "",
                "<</Type/Annot/Subtype/Square/F 4" + RECT + "/AP<</N 7 0 R>>>>",
                "<</Type/Annot/Subtype/Widget/FT/Tx/T(t)/F 4/Rect[72 500 172 540]/AP<</N 8 0 R>>>>",
                RawPdf.stream("/Type/XObject/Subtype/Form/BBox[0 0 100 40]/Resources<<" + RawPdf.helvetica()
                        + "/XObject<</I 9 0 R/F 8 0 R>>/ExtGState<</G<</ca 0.5/SMask<</S/Alpha/G 8 0 R>>>>>>>>",
                        "/G gs 1 0 0 rg 0 0 100 40 re f BT /F1 9 Tf 2 2 Td (in the box) Tj ET 40 0 0 40 0 0 cm /I Do /F Do"
                                + " /Bad ri"),
                RawPdf.stream("/Type/XObject/Subtype/Form/BBox[0 0 100 40]/Group<</S/Transparency>>/Resources<<"
                        + RawPdf.helvetica() + ">>", "/Tx BMC BT /F1 0 Tf 0 0 1 rg 2 10 Td (field) Tj ET EMC"),
                RawPdf.stream(img + "8/ColorSpace/DeviceRGB/SMask 10 0 R/Interpolate true", "\u00ff\u0000\u0000"),
                RawPdf.stream(img + "8/ColorSpace/DeviceGray", "\u0080")));
        ALL.put("w21_xobject_kinds", () -> page("", "", "/XObject<</PS 5 0 R/Ref 6 0 R/Alt 7 0 R/Deep 9 0 R/Mask 10 0 R"
                + "/Big 11 0 R>>/ExtGState<</E<</TR/Identity/TR2/Default/HT<</HalftoneType 1/Frequency 60/Angle 45"
                + "/SpotFunction/Round/TransferFunction/Identity>>/HTP[1 2]/BG2/Default/UCR2/Default/SA true/RI/Bogus"
                + "/UseBlackPtComp/ON/Font[12 0 R 12]/D[[3 2]0]>>>>",
                "/E gs /PS Do /Ref Do /Alt Do /Deep Do q 30 0 0 30 72 400 cm /Mask Do Q q 30 0 0 30 72 300 cm /Big Do Q "
                        + "q ".repeat(30) + "0 0 5 5 re f " + "Q ".repeat(30)
                        + "BX 1 2 bogus EX q 72 200 50 50 re W n /Sh sh Q",
                RawPdf.stream("/Type/XObject/Subtype/PS", "0 0 moveto"),
                RawPdf.stream("/Type/XObject/Subtype/Form/BBox[0 0 10 10]/Ref<</F(other.pdf)/Page 0>>/PS 8 0 R", "0 g"),
                RawPdf.stream(img + "8/ColorSpace/DeviceGray/Alternates[<</Image 10 0 R>>]/OPI<</1.3<<>>>>", "\u0040"),
                RawPdf.stream("", "showpage"),
                RawPdf.stream("/Type/XObject/Subtype/Form/BBox[0 0 0 0]/Group<</S/Transparency/CS/DeviceCMYK>>"
                        + "/OPI<</2.0<<>>>>/Resources<</XObject<</D 6 0 R>>>>", "/D Do 0.5 0 0 0 k 0 0 9 9 re f"),
                RawPdf.stream(img + "1/ImageMask true/Decode[1 0]", "\u0080"),
                RawPdf.stream(img + "16/ColorSpace/DeviceRGB/Intent/Saturation", "\u00ff\u00ff\u0000\u0000\u0080\u0080"),
                "<</Type/Font/Subtype/Type1/BaseFont/Times-Roman>>"));
        ALL.put("w22_tagged_gaps", () -> page("/MarkInfo<</Marked true>>/StructTreeRoot 5 0 R/Lang(en-GB)",
                "/StructParents 0/Annots[8 0 R 9 0 R]",
                "/XObject<</Im 10 0 R>>", "/P <</MCID 0>> BDC BT /F1 12 Tf 72 700 Td (Para) Tj ET EMC /Figure <</MCID 1>>"
                        + " BDC q 50 0 0 50 72 600 cm /Im Do Q EMC BT /F1 12 Tf 72 500 Td (untagged) Tj ET",
                "<</Type/StructTreeRoot/K 11 0 R/ParentTree<</Nums[0[6 0 R 7 0 R] 1 6 0 R]>>/ParentTreeNextKey 2>>",
                "<</Type/StructElem/S/P/P 11 0 R/Pg 3 0 R/K 0>>",
                "<</Type/StructElem/S/Figure/P 11 0 R/Pg 3 0 R/K 1/Alt(A grey square)>>",
                "<</Type/Annot/Subtype/Link/F 4" + RECT + "/A<</S/URI/URI(https://example.com)>>/StructParent 1>>",
                "<</Type/Annot/Subtype/Text/F 4/Rect[300 600 320 620]>>",
                RawPdf.stream(img + "8/ColorSpace/DeviceGray", "\u0080"),
                "<</Type/StructElem/S/Document/P 5 0 R/K[6 0 R 7 0 R]>>"));
        ALL.put("w23_tagged_annotations", () -> page("/MarkInfo<</Marked true>>/StructTreeRoot 5 0 R/Lang(en-GB)",
                "/StructParents 0/Tabs/S/Annots[8 0 R 9 0 R]", "/XObject<</Im 10 0 R>>",
                "/P <</MCID 0>> BDC BT /F1 12 Tf 72 700 Td (Para) Tj ET EMC /Figure <</MCID 1>> BDC q 50 0 0 50 72 600"
                        + " cm /Im Do Q EMC",
                "<</Type/StructTreeRoot/K 11 0 R/ParentTree<</Nums[0[6 0 R 7 0 R] 1 12 0 R]>>/ParentTreeNextKey 2"
                        + "/RoleMap<</Para/P>>>>",
                "<</Type/StructElem/S/Para/P 11 0 R/Pg 3 0 R/K 0>>",
                "<</Type/StructElem/S/Figure/P 11 0 R/Pg 3 0 R/K 1/Alt(A grey square)>>",
                "<</Type/Annot/Subtype/Link/F 4" + RECT + "/A<</S/URI/URI(https://example.com)>>/StructParent 1>>",
                "<</Type/Annot/Subtype/Text/F 4/Rect[300 600 320 620]>>",
                RawPdf.stream(img + "8/ColorSpace/DeviceGray", ""),
                "<</Type/StructElem/S/Document/P 5 0 R/K[6 0 R 7 0 R 12 0 R]>>",
                "<</Type/StructElem/S/Link/P 11 0 R/Pg 3 0 R/K<</Type/OBJR/Obj 8 0 R>>>>"));
    }

    static {
        ALL.put("w24_inherited_overprint", () -> RuleSamples.save(d -> {
            var p = Samples.page(d);
            p.setResources(new org.apache.pdfbox.pdmodel.PDResources());
            COSDictionary res = p.getResources().getCOSObject();
            COSDictionary cs = new COSDictionary();
            cs.setItem("CMYK", ColourSamples.iccBased(d, IccProfiles.cmyk(), 4));
            cs.setItem(COSName.getPDFName("DefaultCMYK"), ColourSamples.iccBased(d, IccProfiles.cmyk(), 4));
            COSDictionary op = new COSDictionary();
            op.setBoolean(COSName.OP, true);
            op.setInt(COSName.OPM, 1);
            COSDictionary gs = new COSDictionary();
            gs.setItem("OP", op);
            COSDictionary xo = new COSDictionary();
            var form = ObjectSamples.form(d, "/CMYK cs 0 0 1 0 sc 0 0 50 50 re f 1 0 0 0 K 0 0 50 50 re S");
            COSDictionary formRes = new COSDictionary();
            formRes.setItem(COSName.COLORSPACE, cs);
            form.setItem(COSName.RESOURCES, formRes);
            xo.setItem("Fm", form);
            res.setItem(COSName.COLORSPACE, cs);
            res.setItem(COSName.EXT_G_STATE, gs);
            res.setItem(COSName.XOBJECT, xo);
            Samples.raw(p, d, "/OP gs q 1 0 0 1 72 600 cm /Fm Do Q 0 1 0 0 k 72 500 50 50 re f");
        }));
        String big = "x".repeat(40_000);
        ALL.put("w25_content_syntax", () -> page("", "", "/Properties<</" + "p".repeat(130) + "<</A 1>>>>",
                "BT /F1 9 Tf 72 700 Td <41 4> Tj <4G42> Tj <> Tj (" + big + ") Tj [(a) -99999999999 (b)] TJ ET /"
                        + "t".repeat(130) + " /" + "p".repeat(130) + " BDC 99999.5 0 0 1 0 0 cm EMC"));
        ALL.put("w26_cmap_details", () -> page("", "", "/Font<</A 5 0 R/B 8 0 R>>",
                "BT /A 18 Tf 72 700 Td <0041420043> Tj /B 18 Tf 72 650 Td <00410042> Tj ET",
                "<</Type/Font/Subtype/Type0/BaseFont/ArialMT/Encoding 6 0 R/DescendantFonts[7 0 R]>>",
                RawPdf.stream("/Type/CMap/CMapName/Declared/CIDSystemInfo<</Registry(Adobe)/Ordering(Identity)"
                        + "/Supplement 0>>/WMode 0", "/CIDInit /ProcSet findresource begin 12 dict begin begincmap "
                        + "/CMapName /Other def /WMode 1 def 2 begincodespacerange <00> <3F> <4000> <FFFF> "
                        + "endcodespacerange 2 begincidrange <00> <3F> 1 <4000> <FFFF> 100 endcidrange endcmap CMapName "
                        + "currentdict /CMap defineresource pop end end"),
                "<</Type/Font/Subtype/CIDFontType2/BaseFont/ArialMT/CIDSystemInfo<</Registry(Adobe)/Ordering"
                        + "(Identity)/Supplement 0>>/FontDescriptor 9 0 R/W[1[500]]>>",
                "<</Type/Font/Subtype/Type0/BaseFont/ArialMT/Encoding/Identity-H/DescendantFonts[10 0 R]>>",
                "<</Type/FontDescriptor/FontName/ArialMT/Flags 32/FontBBox[0 0 1000 1000]/ItalicAngle 0/Ascent 905"
                        + "/Descent -212/CapHeight 716/StemV 80>>",
                "<</Type/Font/Subtype/CIDFontType0/BaseFont/ArialMT/CIDSystemInfo<</Registry(Adobe)/Ordering"
                        + "(Identity)/Supplement 0>>/FontDescriptor 9 0 R/CIDToGIDMap/Identity>>"));
        ALL.put("w27_four_byte_codes", () -> page("", "", "/Font<</G 5 0 R/E 7 0 R>>",
                "BT /G 18 Tf 72 700 Td <4E00D840DC0032A3> Tj /E 18 Tf 72 650 Td <4E8CD840DC014E00> Tj ET",
                "<</Type/Font/Subtype/Type0/BaseFont/STSong-Light-UniGB-UTF16-H/Encoding/UniGB-UTF16-H"
                        + "/DescendantFonts[6 0 R]>>",
                "<</Type/Font/Subtype/CIDFontType0/BaseFont/STSong-Light/CIDSystemInfo<</Registry(Adobe)/Ordering(GB1)"
                        + "/Supplement 4>>/FontDescriptor<</Type/FontDescriptor/FontName/STSong-Light/Flags 6/FontBBox"
                        + "[-195 -272 1110 1075]/ItalicAngle 0/Ascent 880/Descent -120/CapHeight 763/StemV 80>>/DW 1000>>",
                "<</Type/Font/Subtype/Type0/BaseFont/STSong-Light/Encoding 8 0 R/DescendantFonts[9 0 R]>>",
                RawPdf.stream("/Type/CMap/CMapName/Over/CIDSystemInfo<</Registry(Adobe)/Ordering(GB1)/Supplement 4>>",
                        "/CIDInit /ProcSet findresource begin 12 dict begin begincmap /UniGB-UTF16-H usecmap /CMapName "
                                + "/Over def 1 begincidchar <4E00> 500 endcidchar endcmap CMapName currentdict /CMap "
                                + "defineresource pop end end"),
                "<</Type/Font/Subtype/CIDFontType0/BaseFont/STSong-Light/CIDSystemInfo<</Registry(Adobe)/Ordering(GB1)"
                        + "/Supplement 4>>/FontDescriptor<</Type/FontDescriptor/FontName/STSong-Light/Flags 6/FontBBox"
                        + "[-195 -272 1110 1075]/ItalicAngle 0/Ascent 880/Descent -120/CapHeight 763/StemV 80>>>>"));
        ALL.put("w28_shared_cid_font", () -> page("", "", "/Font<</H 5 0 R/V 6 0 R>>",
                "BT /H 18 Tf 72 700 Td <4E004E09D840DC01> Tj /V 18 Tf 300 700 Td <4E8C9F98> Tj ET",
                "<</Type/Font/Subtype/Type0/BaseFont/STSong-Light-UniGB-UTF16-H/Encoding/UniGB-UTF16-H"
                        + "/DescendantFonts[7 0 R]>>",
                "<</Type/Font/Subtype/Type0/BaseFont/STSong-Light-GBK-EUC-H/Encoding/UniGB-UCS2-H/DescendantFonts[7 0 R]>>",
                "<</Type/Font/Subtype/CIDFontType0/BaseFont/STSong-Light/CIDSystemInfo<</Registry(Adobe)/Ordering(GB1)"
                        + "/Supplement 4>>/FontDescriptor<</Type/FontDescriptor/FontName/STSong-Light/Flags 6/FontBBox"
                        + "[-195 -272 1110 1075]/ItalicAngle 0/Ascent 880/Descent -120/CapHeight 763/StemV 80>>>>"));
    }

    private static String hex(String s) {
        StringBuilder b = new StringBuilder();
        for (byte x : s.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
            b.append(String.format("%02X", x));
        }
        return b.toString();
    }
}
