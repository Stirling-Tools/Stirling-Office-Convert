package stirling.software.officeconvert.pdfa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSFloat;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TransferBakingTest {

    @TempDir
    Path dir;

    private static COSDictionary function(float c0, float c1) {
        COSDictionary f = new COSDictionary();
        f.setInt(COSName.FUNCTION_TYPE, 2);
        f.setItem(COSName.DOMAIN, numbers(0, 1));
        f.setItem(COSName.C0, numbers(c0));
        f.setItem(COSName.C1, numbers(c1));
        f.setInt(COSName.N, 1);
        return f;
    }

    private static COSArray numbers(float... v) {
        COSArray a = new COSArray();
        for (float f : v) {
            a.add(new COSFloat(f));
        }
        return a;
    }

    private Path page(String content, COSDictionary res) throws Exception {
        Path in = dir.resolve("in.pdf");
        try (PDDocument d = new PDDocument()) {
            PDPage p = new PDPage(new PDRectangle(200, 100));
            d.addPage(p);
            p.getCOSObject().setItem(COSName.RESOURCES, res);
            PDStream s = new PDStream(d);
            try (OutputStream o = s.createOutputStream()) {
                o.write(content.getBytes(StandardCharsets.US_ASCII));
            }
            p.setContents(s);
            d.save(in.toFile());
        }
        return in;
    }

    private static COSDictionary resources(String category, String name, COSDictionary value) {
        COSDictionary c = new COSDictionary();
        c.setItem(COSName.getPDFName(name), value);
        COSDictionary res = new COSDictionary();
        res.setItem(COSName.getPDFName(category), c);
        return res;
    }

    private static COSDictionary invert() {
        COSDictionary gs = new COSDictionary();
        gs.setItem(COSName.TYPE, COSName.EXT_G_STATE);
        gs.setItem(COSName.TR, function(1, 0));
        return gs;
    }

    private static int rgb(Path pdf, int x, int y) throws Exception {
        try (PDDocument d = Loader.loadPDF(pdf.toFile())) {
            BufferedImage img = new PDFRenderer(d).renderImage(0, 1);
            return img.getRGB(x, y) & 0xFFFFFF;
        }
    }

    private static void assertColour(int want, int got) {
        for (int s = 0; s <= 16; s += 8) {
            assertEquals(want >> s & 0xFF, got >> s & 0xFF, 3, Integer.toHexString(got));
        }
    }

    @Test
    void anInvertingTransferTurnsRedFillsCyanAtEveryLevel() throws Exception {
        COSDictionary res = resources("ExtGState", "GT", invert());
        Path in = page("1 0 0 rg /GT gs 0 0 100 100 re f q 0 0 1 rg 100 0 100 100 re f Q", res);
        for (PdfALevel level : new PdfALevel[] {PdfALevel.A1B, PdfALevel.A2B, PdfALevel.A4}) {
            Path out = dir.resolve("out-" + level + ".pdf");
            PdfToPdfA.convert(in, out, PdfToPdfA.Options.defaults().level(level));
            assertFalse(new String(Files.readAllBytes(out), StandardCharsets.ISO_8859_1).contains("/TR "));
            assertColour(0x00FFFF, rgb(out, 50, 50));
            assertColour(0xFFFF00, rgb(out, 150, 50));
            VeraPdf.assertCompliant(out, level);
        }
    }

    @Test
    void anIdentityTransferRestoresTheOriginalColour() throws Exception {
        COSDictionary identity = new COSDictionary();
        identity.setItem(COSName.TR, COSName.getPDFName("Identity"));
        COSDictionary states = new COSDictionary();
        states.setItem(COSName.getPDFName("GT"), invert());
        states.setItem(COSName.getPDFName("GI"), identity);
        COSDictionary res = new COSDictionary();
        res.setItem(COSName.EXT_G_STATE, states);
        Path in = page("0 0 1 rg /GT gs 0 0 100 100 re f /GI gs 100 0 100 100 re f", res);
        Path out = dir.resolve("identity.pdf");
        PdfToPdfA.convert(in, out, PdfToPdfA.Options.defaults().level(PdfALevel.A2B));
        assertColour(0xFFFF00, rgb(out, 50, 50));
        assertColour(0x0000FF, rgb(out, 150, 50));
    }

    @Test
    void imagesUnderATransferAreRedrawnWithIt() throws Exception {
        COSStream img = new COSStream();
        try (OutputStream o = img.createOutputStream()) {
            o.write(new byte[] {(byte) 255, 0, 0, (byte) 255, 0, 0, (byte) 255, 0, 0, (byte) 255, 0, 0});
        }
        img.setItem(COSName.TYPE, COSName.XOBJECT);
        img.setItem(COSName.SUBTYPE, COSName.IMAGE);
        img.setInt(COSName.WIDTH, 2);
        img.setInt(COSName.HEIGHT, 2);
        img.setInt(COSName.BITS_PER_COMPONENT, 8);
        img.setItem(COSName.COLORSPACE, COSName.DEVICERGB);
        COSDictionary res = resources("ExtGState", "GT", invert());
        COSDictionary xo = new COSDictionary();
        xo.setItem(COSName.getPDFName("Im"), img);
        res.setItem(COSName.XOBJECT, xo);
        Path in = page("q 100 0 0 100 100 0 cm /Im Do Q q /GT gs 100 0 0 100 0 0 cm /Im Do Q", res);
        Path out = dir.resolve("image.pdf");
        PdfToPdfA.convert(in, out, PdfToPdfA.Options.defaults().level(PdfALevel.A2B));
        assertColour(0x00FFFF, rgb(out, 50, 50));
        assertColour(0xFF0000, rgb(out, 150, 50));
        VeraPdf.assertCompliant(out, PdfALevel.A2B);
    }

    @Test
    void aFormDrawnUnderATransferIsCopiedAndKeptAsItIsElsewhere() throws Exception {
        COSStream form = new COSStream();
        try (OutputStream o = form.createOutputStream()) {
            o.write("1 0 0 rg 0 0 100 100 re f".getBytes(StandardCharsets.US_ASCII));
        }
        form.setItem(COSName.TYPE, COSName.XOBJECT);
        form.setItem(COSName.SUBTYPE, COSName.FORM);
        form.setItem(COSName.BBOX, new PDRectangle(0, 0, 100, 100).getCOSArray());
        form.setItem(COSName.RESOURCES, new COSDictionary());
        COSDictionary res = resources("ExtGState", "GT", invert());
        COSDictionary xo = new COSDictionary();
        xo.setItem(COSName.getPDFName("Fm"), form);
        res.setItem(COSName.XOBJECT, xo);
        Path in = page("q /GT gs /Fm Do Q q 1 0 0 1 100 0 cm /Fm Do Q", res);
        Path out = dir.resolve("form.pdf");
        PdfToPdfA.convert(in, out, PdfToPdfA.Options.defaults().level(PdfALevel.A2B));
        assertColour(0x00FFFF, rgb(out, 50, 50));
        assertColour(0xFF0000, rgb(out, 150, 50));
    }

    @Test
    void aSoftMaskKeepsItsTransferFunction() throws Exception {
        COSStream group = new COSStream();
        try (OutputStream o = group.createOutputStream()) {
            o.write("1 g 0 0 200 100 re f".getBytes(StandardCharsets.US_ASCII));
        }
        group.setItem(COSName.TYPE, COSName.XOBJECT);
        group.setItem(COSName.SUBTYPE, COSName.FORM);
        group.setItem(COSName.BBOX, new PDRectangle(0, 0, 200, 100).getCOSArray());
        COSDictionary g = new COSDictionary();
        g.setItem(COSName.S, COSName.TRANSPARENCY);
        g.setItem(COSName.CS, COSName.DEVICEGRAY);
        group.setItem(COSName.GROUP, g);
        COSDictionary mask = new COSDictionary();
        mask.setItem(COSName.TYPE, COSName.MASK);
        mask.setItem(COSName.S, COSName.ALPHA);
        mask.setItem(COSName.G, group);
        mask.setItem(COSName.TR, function(0, 0.5f));
        COSDictionary gs = new COSDictionary();
        gs.setItem(COSName.TYPE, COSName.EXT_G_STATE);
        gs.setItem(COSName.SMASK, mask);
        Path in = page("q /GM gs 0 0 1 rg 0 0 200 100 re f Q", resources("ExtGState", "GM", gs));
        for (PdfALevel level : new PdfALevel[] {PdfALevel.A1B, PdfALevel.A2B, PdfALevel.A4}) {
            Path out = dir.resolve("mask-" + level + ".pdf");
            PdfToPdfA.convert(in, out, PdfToPdfA.Options.defaults().level(level));
            assertColour(0x7F7FFF, rgb(out, 100, 50));
            VeraPdf.assertCompliant(out, level);
            if (level != PdfALevel.A1B) {
                try (PDDocument d = Loader.loadPDF(out.toFile())) {
                    COSDictionary states = (COSDictionary) d.getPage(0).getResources().getCOSObject()
                            .getDictionaryObject(COSName.EXT_G_STATE);
                    COSDictionary kept = (COSDictionary) ((COSDictionary) states.getDictionaryObject("GM"))
                            .getDictionaryObject(COSName.SMASK);
                    assertTrue(kept.getDictionaryObject(COSName.TR) instanceof COSDictionary, level.toString());
                }
            }
        }
    }
}
