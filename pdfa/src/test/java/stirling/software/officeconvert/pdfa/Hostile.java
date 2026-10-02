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

    static COSDictionary dict(COSName type) {
        COSDictionary d = new COSDictionary();
        d.setItem(COSName.TYPE, type);
        return d;
    }
}
