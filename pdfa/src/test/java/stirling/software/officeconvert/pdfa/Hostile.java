package stirling.software.officeconvert.pdfa;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;

final class Hostile {

    @FunctionalInterface
    interface Body {
        void make(PDDocument d) throws IOException;
    }

    private Hostile() {}

    static Path write(Path dir, String name, RawPdf raw) throws IOException {
        Path p = dir.resolve(name + ".pdf");
        java.nio.file.Files.write(p, raw.bytes());
        return p;
    }

    static RawPdf chain(String catalogExtra, int levels, String leaf, String link) {
        RawPdf r = RawPdf.page("", "0 0 1 rg 10 10 100 100 re f");
        int below = r.add(leaf);
        for (int i = 0; i < levels; i++) {
            below = r.add(link.replace("@", below + " 0 R"));
        }
        r.set(1, "<</Type/Catalog/Pages 2 0 R" + catalogExtra.replace("@", below + " 0 R") + ">>");
        return r;
    }

    static RawPdf optionalContentDag() {
        RawPdf r = chain("/OCProperties<</OCGs[5 0 R]/D<</Order @>>>>", 40, "<</Type/OCG/Name(Layer)>>",
                "[@ @]");
        r.set(5, "<</Type/OCG/Name(Layer)>>");
        r.set(6, "[5 0 R 5 0 R]");
        return r;
    }

    static RawPdf embeddedFileTreeDag() {
        return chain("/Names<</EmbeddedFiles @>>", 40,
                "<</Names[(a.txt)<</Type/Filespec/F(a.txt)>>]/Limits[(a.txt)(a.txt)]>>", "<</Kids[@ @]>>");
    }

    static RawPdf formChain(int forms) {
        RawPdf r = RawPdf.page("/XObject<</X 5 0 R>>", "0 0 1 rg 10 10 50 50 re f /X Do");
        for (int i = 0; i < forms; i++) {
            String res = i + 1 < forms ? "/Resources<</XObject<</X " + (6 + i) + " 0 R>>>>" : "";
            String body = i + 1 < forms ? "/X Do" : "0 1 0 rg 20 20 10 10 re f";
            r.add(RawPdf.stream("/Type/XObject/Subtype/Form/BBox[0 0 600 800]" + res, body));
        }
        return r;
    }

    static RawPdf pieceInfoDag() {
        RawPdf r = chain("", 8, "[/A]", "[" + "@ ".repeat(4000) + "]");
        r.set(3, "<</Type/Page/Parent 2 0 R/MediaBox[0 0 595 842]/Contents 4 0 R/PieceInfo<</App<</Private "
                + r.objects.size() + " 0 R>>>>>>");
        return r;
    }

    static Path write(Path dir, String name, Body body) throws IOException {
        Path p = dir.resolve(name + ".pdf");
        try (PDDocument d = new PDDocument()) {
            body.make(d);
            d.save(p.toFile());
        }
        return p;
    }

    static COSStream stream(PDDocument d, String content) throws IOException {
        COSStream s = d.getDocument().createCOSStream();
        try (OutputStream o = s.createOutputStream()) {
            o.write(content.getBytes(StandardCharsets.ISO_8859_1));
        }
        return s;
    }

    static PDPage page(PDDocument d) {
        PDPage p = new PDPage(PDRectangle.A4);
        d.addPage(p);
        return p;
    }

    static PDFont helvetica() {
        return new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    }

    static PDResources fonts(PDFont f) {
        PDResources r = new PDResources();
        r.put(COSName.getPDFName("F1"), f);
        return r;
    }

    static void sharedContents(PDDocument d) throws IOException {
        PDFont f = helvetica();
        COSStream shared = stream(d, "BT /F1 18 Tf 72 780 Td (SharedHeader) Tj ET\n");
        for (int i = 1; i <= 2; i++) {
            PDPage p = page(d);
            p.setResources(fonts(f));
            String own = "BT /F1 18 Tf 72 700 Td (" + (i == 1 ? "AlphaPage" : "BravoPage") + ") Tj ET\n" + (i == 1 ? "foo\n" : "");
            COSArray c = new COSArray();
            c.add(shared);
            c.add(stream(d, own));
            p.getCOSObject().setItem(COSName.CONTENTS, c);
        }
    }

    static COSStream repeated(PDDocument d, String line, long times) throws IOException {
        COSStream c = d.getDocument().createCOSStream();
        byte[] one = line.getBytes(StandardCharsets.ISO_8859_1);
        byte[] block = new byte[one.length * 4096];
        for (int i = 0; i < 4096; i++) {
            System.arraycopy(one, 0, block, i * one.length, one.length);
        }
        try (OutputStream o = c.createOutputStream(COSName.FLATE_DECODE)) {
            for (long i = 0; i < times / 4096; i++) {
                o.write(block);
            }
        }
        return c;
    }

    static void contentBomb(PDDocument d) throws IOException {
        PDPage p = page(d);
        p.getCOSObject().setItem(COSName.CONTENTS, repeated(d, "0.5 0.5 m\n", 20_000_000));
    }

    static void metadataBomb(PDDocument d) throws IOException {
        PDPage p = page(d);
        p.getCOSObject().setItem(COSName.CONTENTS, stream(d, "0 0 1 rg 10 10 100 100 re f\n"));
        COSStream c = repeated(d, " ".repeat(64), 700L << 20 >> 6);
        c.setItem(COSName.TYPE, COSName.METADATA);
        c.setItem(COSName.SUBTYPE, COSName.getPDFName("XML"));
        d.getDocumentCatalog().getCOSObject().setItem(COSName.METADATA, c);
    }

    static void slowFlatten(PDDocument d) throws IOException {
        PDPage p = page(d);
        PDExtendedGraphicsState gs = new PDExtendedGraphicsState();
        gs.setStrokingAlphaConstant(0.5f);
        PDResources r = new PDResources();
        r.put(COSName.getPDFName("GS0"), gs);
        p.setResources(r);
        COSStream c = repeated(d, "0 0 m 600 800 l 0 800 l 600 0 l 0 0 l S\n", 40_960);
        COSArray parts = new COSArray();
        parts.add(stream(d, "50 w 1 0 0 RG /GS0 gs\n"));
        parts.add(c);
        p.getCOSObject().setItem(COSName.CONTENTS, parts);
    }

    static void plainPage(PDDocument d) throws IOException {
        PDPage p = page(d);
        p.getCOSObject().setItem(COSName.CONTENTS, stream(d, "0 0 1 rg 10 10 100 100 re f\n"));
    }

    static void actionDag(PDDocument d) throws IOException {
        plainPage(d);
        COSDictionary next = null;
        for (int i = 0; i < 40; i++) {
            COSDictionary a = dict(COSName.getPDFName("Action"));
            a.setItem(COSName.S, COSName.JAVA_SCRIPT);
            a.setString(COSName.JS, "app.alert(" + i + ")");
            if (next != null) {
                COSArray pair = new COSArray();
                pair.add(next);
                pair.add(next);
                a.setItem(COSName.NEXT, pair);
            }
            a.setDirect(false);
            next = a;
        }
        d.getDocumentCatalog().getCOSObject().setItem(COSName.OPEN_ACTION, next);
    }

    static RawPdf inheritedFont() {
        RawPdf r = RawPdf.page(RawPdf.helvetica() + "/XObject<</Fm0 5 0 R>>", "BT /F1 24 Tf 72 700 Td (AB) Tj ET /Fm0 Do");
        r.set(3, "<</Type/Page/Parent 2 0 R/MediaBox[0 0 595 842]/Resources<<" + RawPdf.helvetica()
                + "/XObject<</Fm0 5 0 R>>>>/Contents 4 0 R>>");
        r.add(RawPdf.stream("/Type/XObject/Subtype/Form/BBox[0 0 600 800]/Resources<<>>",
                "BT 72 600 Td (XYZQWK) Tj ET"));
        return r;
    }

    static RawPdf deviceNInForm() {
        String names = "/S0/S1/S2/S3/S4/S5/S6/S7/S8";
        String cs = "/ColorSpace<</CS0 [/DeviceN[" + names + "]/DeviceRGB 6 0 R]>>";
        RawPdf r = RawPdf.page(cs + "/XObject<</Fm0 5 0 R>>",
                "/CS0 cs 0.2 0 0 0 0 0 0 0 0 scn 100 500 100 100 re f /Fm0 Do");
        r.add(RawPdf.stream("/Type/XObject/Subtype/Form/BBox[0 0 600 800]/Resources<<" + cs + ">>",
                "0.8 0 0 0 0 0 0 0 0 scn 300 500 100 100 re f"));
        r.add(RawPdf.stream("/FunctionType 4/Domain[0 1 0 1 0 1 0 1 0 1 0 1 0 1 0 1 0 1]/Range[0 1 0 1 0 1]",
                "{pop pop pop pop pop pop pop pop dup dup}"));
        return r;
    }

    static RawPdf hiddenLayer() {
        RawPdf r = RawPdf.page(RawPdf.helvetica() + "/Properties<</L1 5 0 R>>",
                "BT /F1 24 Tf 72 700 Td /OC /L1 BDC (HIDDEN WORDS ) Tj EMC (Visible) Tj ET\n"
                        + "/OC /L1 BDC 0 0 1 rg 1 0 0 1 200 0 cm 10 10 50 50 re f EMC 72 500 100 100 re f");
        r.add("<</Type/OCG/Name(Hidden layer)>>");
        r.set(1, "<</Type/Catalog/Pages 2 0 R/OCProperties<</OCGs[5 0 R]/D<</OFF[5 0 R]>>>>>>");
        return r;
    }

    static RawPdf transparencyGroup() {
        RawPdf r = RawPdf.page(RawPdf.helvetica() + "/XObject<</Fm0 5 0 R>>",
                "BT /F1 18 Tf 72 700 Td (Plain text) Tj ET /Fm0 Do");
        r.add(RawPdf.stream("/Type/XObject/Subtype/Form/BBox[0 0 600 800]/Group<</S/Transparency>>/Resources<<"
                + RawPdf.helvetica() + "/ExtGState<</GS0<</Type/ExtGState/ca 0.5>>>>>>",
                "/GS0 gs 1 0 0 rg 60 580 300 60 re f 0 g BT /F1 18 Tf 72 600 Td (Text inside a group) Tj ET"));
        return r;
    }

    static void taggedForm(PDDocument d, String variant) throws IOException {
        PDFont f = helvetica();
        PDPage p = page(d);
        p.setResources(fonts(f));
        org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject form =
                new org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject(d);
        form.setBBox(new PDRectangle(0, 0, 600, 800));
        PDResources fr = fonts(f);
        form.setResources(fr);
        boolean mcidInForm = variant.equals("mcid");
        String body = switch (variant) {
            case "mcid" -> "/P <</MCID 0>> BDC BT /F1 18 Tf 72 600 Td (Tagged form text) Tj ET EMC";
            case "self" -> "0 0 1 rg 10 10 50 50 re f /Fm0 Do";
            default -> "BT /F1 18 Tf 72 600 Td (Untagged form text) Tj ET";
        };
        try (OutputStream o = form.getCOSObject().createOutputStream()) {
            o.write(body.getBytes(StandardCharsets.ISO_8859_1));
        }
        if (variant.equals("structparents")) {
            form.getCOSObject().setInt(COSName.STRUCT_PARENTS, 1);
        }
        if (variant.equals("self")) {
            fr.put(COSName.getPDFName("Fm0"), form);
        }
        p.getResources().put(COSName.getPDFName("Fm0"), form);
        p.getCOSObject().setItem(COSName.CONTENTS,
                stream(d, "/P <</MCID 0>> BDC BT /F1 18 Tf 72 700 Td (Tagged) Tj ET EMC /Fm0 Do\n"));
        p.getCOSObject().setInt(COSName.STRUCT_PARENTS, 0);
        COSDictionary root = dict(COSName.getPDFName("StructTreeRoot"));
        COSDictionary doc = element(COSName.getPDFName("Document"), root, null);
        COSDictionary para = element(COSName.P, doc, p);
        para.setInt(COSName.K, 0);
        COSArray kids = new COSArray();
        kids.add(para);
        COSArray nums = new COSArray();
        COSArray onPage = new COSArray();
        onPage.add(para);
        nums.add(org.apache.pdfbox.cos.COSInteger.get(0));
        nums.add(onPage);
        if (mcidInForm) {
            COSDictionary inForm = element(COSName.P, doc, p);
            COSDictionary mcr = dict(COSName.getPDFName("MCR"));
            mcr.setInt(COSName.MCID, 0);
            mcr.setItem(COSName.getPDFName("Stm"), form.getCOSObject());
            inForm.setItem(COSName.K, mcr);
            kids.add(inForm);
        }
        doc.setItem(COSName.K, kids);
        root.setItem(COSName.K, doc);
        COSDictionary tree = new COSDictionary();
        tree.setItem(COSName.NUMS, nums);
        root.setItem(COSName.PARENT_TREE, tree);
        d.getDocumentCatalog().getCOSObject().setItem(COSName.STRUCT_TREE_ROOT, root);
        COSDictionary mark = new COSDictionary();
        mark.setBoolean(COSName.getPDFName("Marked"), true);
        d.getDocumentCatalog().getCOSObject().setItem(COSName.MARK_INFO, mark);
        d.getDocumentCatalog().setLanguage("en-US");
    }

    private static COSDictionary element(COSName type, COSDictionary parent, PDPage page) {
        COSDictionary e = dict(COSName.getPDFName("StructElem"));
        e.setItem(COSName.S, type);
        e.setItem(COSName.P, parent);
        if (page != null) {
            e.setItem(COSName.PG, page.getCOSObject());
        }
        return e;
    }

    static COSDictionary dict(COSName type) {
        COSDictionary d = new COSDictionary();
        d.setItem(COSName.TYPE, type);
        return d;
    }
}
