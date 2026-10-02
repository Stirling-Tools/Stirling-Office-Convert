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

final class Hostile {

    @FunctionalInterface
    interface Body {
        void make(PDDocument d) throws IOException;
    }

    private Hostile() {}

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

    static COSDictionary dict(COSName type) {
        COSDictionary d = new COSDictionary();
        d.setItem(COSName.TYPE, type);
        return d;
    }
}
