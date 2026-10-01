package stirling.software.officeconvert.pdfa;

import static stirling.software.officeconvert.pdfa.RuleSamples.doc;
import static stirling.software.officeconvert.pdfa.RuleSamples.tagged;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

final class ObjectSamples {

    private ObjectSamples() {}

    static void register() {
        doc("o02_big_dictionaries", Set.of("1:6.1.12-6"), d -> {
            PDPage p = Samples.page(d);
            PDResources res = new PDResources();
            PDFont f = Samples.std(Standard14Fonts.FontName.HELVETICA);
            COSDictionary fonts = new COSDictionary();
            for (int i = 0; i < 5000; i++) {
                fonts.setItem("F" + i, f.getCOSObject());
            }
            res.getCOSObject().setItem(COSName.FONT, fonts);
            p.setResources(res);
            Samples.raw(p, d, "BT /F7 14 Tf 50 780 Td (Five thousand fonts, one used) Tj ET");
            COSDictionary dests = new COSDictionary();
            for (int i = 0; i < 5000; i++) {
                COSArray dest = new COSArray();
                dest.add(p.getCOSObject());
                dest.add(COSName.getPDFName("Fit"));
                dests.setItem("D" + i, dest);
            }
            d.getDocumentCatalog().getCOSObject().setItem(COSName.getPDFName("Dests"), dests);
            COSDictionary info = d.getDocumentInformation().getCOSObject();
            info.setString(COSName.TITLE, "Big dictionaries");
            for (int i = 0; i < 5000; i++) {
                info.setString("Custom" + i, "value " + i);
            }
            COSDictionary piece = new COSDictionary();
            COSDictionary app = new COSDictionary();
            COSDictionary priv = new COSDictionary();
            for (int i = 0; i < 5000; i++) {
                priv.setInt("AIPrivateData" + i, i);
            }
            app.setItem(COSName.getPDFName("Private"), priv);
            piece.setItem("Illustrator", app);
            p.getCOSObject().setItem(COSName.getPDFName("PieceInfo"), piece);
        });
        doc("o03_long_arrays", Set.of("1:6.1.12-5"), d -> {
            PDPage p = Samples.page(d);
            PDResources res = new PDResources();
            res.put(COSName.getPDFName("F1"), Samples.std(Standard14Fonts.FontName.HELVETICA));
            p.setResources(res);
            COSArray contents = new COSArray();
            for (int i = 0; i < 9000; i++) {
                COSStream s = d.getDocument().createCOSStream();
                String text = i == 0 ? "BT /F1 14 Tf 50 780 Td (Nine thousand content streams) Tj ET" : i % 2 == 1 ? "q" : "Q";
                try (OutputStream o = s.createOutputStream()) {
                    o.write(text.getBytes(StandardCharsets.US_ASCII));
                }
                contents.add(s);
            }
            p.getCOSObject().setItem(COSName.CONTENTS, contents);
            COSDictionary ink = new COSDictionary();
            ink.setItem(COSName.TYPE, COSName.ANNOT);
            ink.setItem(COSName.SUBTYPE, COSName.getPDFName("Ink"));
            ink.setItem(COSName.RECT, ColourSamples.floats(50, 300, 550, 500));
            ink.setInt(COSName.F, 4);
            COSArray path = new COSArray();
            for (int i = 0; i < 5000; i++) {
                path.add(COSInteger.get(50 + i / 10));
                path.add(COSInteger.get(300 + (i % 200)));
            }
            COSArray inks = new COSArray();
            inks.add(path);
            ink.setItem(COSName.getPDFName("InkList"), inks);
            COSArray annots = new COSArray();
            annots.add(ink);
            p.getCOSObject().setItem(COSName.ANNOTS, annots);
        });
        tagged("o04_wide_structure", Set.of("1:6.1.12-5"), d -> {
            PDPage[] pages = {Samples.page(d), Samples.page(d)};
            PDResources res = new PDResources();
            res.put(COSName.getPDFName("F1"), Samples.std(Standard14Fonts.FontName.HELVETICA));
            COSDictionary root = new COSDictionary();
            root.setItem(COSName.TYPE, COSName.STRUCT_TREE_ROOT);
            COSDictionary table = new COSDictionary();
            table.setItem(COSName.TYPE, COSName.getPDFName("StructElem"));
            table.setItem(COSName.S, COSName.getPDFName("Table"));
            table.setItem(COSName.P, root);
            COSArray rows = new COSArray();
            COSArray nums = new COSArray();
            for (int pg = 0; pg < 2; pg++) {
                COSArray parents = new COSArray();
                StringBuilder content = new StringBuilder();
                for (int i = 0; i < 4500; i++) {
                    COSDictionary row = new COSDictionary();
                    row.setItem(COSName.TYPE, COSName.getPDFName("StructElem"));
                    row.setItem(COSName.S, COSName.getPDFName("TR"));
                    row.setItem(COSName.P, table);
                    row.setItem(COSName.PG, pages[pg].getCOSObject());
                    row.setInt(COSName.K, i);
                    rows.add(row);
                    parents.add(row);
                    content.append("/TR <</MCID ").append(i).append(">> BDC BT /F1 4 Tf ").append(50 + (i / 150) * 16)
                            .append(' ').append(800 - (i % 150) * 5).append(" Td (").append(i % 10)
                            .append(") Tj ET EMC\n");
                }
                nums.add(COSInteger.get(pg));
                nums.add(parents);
                pages[pg].setResources(res);
                pages[pg].getCOSObject().setInt(COSName.STRUCT_PARENTS, pg);
                Samples.raw(pages[pg], d, content.toString());
            }
            table.setItem(COSName.K, rows);
            root.setItem(COSName.K, table);
            COSDictionary tree = new COSDictionary();
            tree.setItem(COSName.NUMS, nums);
            root.setItem(COSName.PARENT_TREE, tree);
            root.setInt(COSName.PARENT_TREE_NEXT_KEY, 2);
            d.getDocumentCatalog().getCOSObject().setItem(COSName.STRUCT_TREE_ROOT, root);
            d.getDocumentCatalog().getCOSObject().setString(COSName.LANG, "en");
            COSDictionary mark = new COSDictionary();
            mark.setBoolean(COSName.getPDFName("Marked"), true);
            d.getDocumentCatalog().getCOSObject().setItem(COSName.MARK_INFO, mark);
        });
    }

    static COSStream image(PDDocument d, int bpc, boolean mask) throws Exception {
        COSStream s = d.getDocument().createCOSStream();
        s.setItem(COSName.TYPE, COSName.XOBJECT);
        s.setItem(COSName.SUBTYPE, COSName.IMAGE);
        s.setInt(COSName.WIDTH, 8);
        s.setInt(COSName.HEIGHT, 8);
        s.setInt(COSName.BITS_PER_COMPONENT, bpc);
        int comps = mask ? 1 : 3;
        if (mask) {
            s.setBoolean(COSName.IMAGE_MASK, true);
        } else {
            s.setItem(COSName.COLORSPACE, COSName.DEVICERGB);
        }
        byte[] data = new byte[8 * 8 * comps * bpc / 8];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (i * 37 % 256 > 127 ? 255 : 0);
        }
        try (OutputStream o = s.createOutputStream(COSName.FLATE_DECODE)) {
            o.write(data);
        }
        return s;
    }

    static COSStream form(PDDocument d, String content) throws Exception {
        COSStream s = d.getDocument().createCOSStream();
        s.setItem(COSName.TYPE, COSName.XOBJECT);
        s.setItem(COSName.SUBTYPE, COSName.FORM);
        s.setItem(COSName.BBOX, ColourSamples.floats(0, 0, 500, 100));
        try (OutputStream o = s.createOutputStream()) {
            o.write(content.getBytes(StandardCharsets.US_ASCII));
        }
        return s;
    }
}
