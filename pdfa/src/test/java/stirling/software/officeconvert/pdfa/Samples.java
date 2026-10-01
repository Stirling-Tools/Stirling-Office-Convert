package stirling.software.officeconvert.pdfa;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.GregorianCalendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSFloat;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDDocumentNameDictionary;
import org.apache.pdfbox.pdmodel.PDEmbeddedFilesNameTreeNode;
import org.apache.pdfbox.pdmodel.PDJavascriptNameTreeNode;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification;
import org.apache.pdfbox.pdmodel.common.filespecification.PDEmbeddedFile;
import org.apache.pdfbox.pdmodel.common.filespecification.PDSimpleFileSpecification;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDFontFactory;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.font.encoding.WinAnsiEncoding;
import org.apache.pdfbox.pdmodel.graphics.blend.BlendMode;
import org.apache.pdfbox.pdmodel.graphics.color.PDColor;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceCMYK;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceRGB;
import org.apache.pdfbox.pdmodel.graphics.color.PDLab;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.graphics.optionalcontent.PDOptionalContentGroup;
import org.apache.pdfbox.pdmodel.graphics.optionalcontent.PDOptionalContentProperties;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionJavaScript;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionLaunch;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.action.PDFormFieldAdditionalActions;
import org.apache.pdfbox.pdmodel.interactive.action.PDPageAdditionalActions;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationFileAttachment;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationFreeText;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationHighlight;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationSquare;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationText;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDCheckBox;
import org.apache.pdfbox.pdmodel.interactive.form.PDTextField;

final class Samples {

    interface Body {
        void make(PDDocument d) throws Exception;
    }

    static final Map<String, Body> ALL = new LinkedHashMap<>();

    private Samples() {}

    static Path write(Path dir, String name) throws Exception {
        Path p = dir.resolve(name + ".pdf");
        try (PDDocument d = new PDDocument()) {
            ALL.get(name).make(d);
            d.save(p.toFile());
        }
        return p;
    }

    private static void gen(String name, Body b) {
        ALL.put(name, b);
    }

    static PDPage page(PDDocument d) {
        PDPage p = new PDPage(PDRectangle.A4);
        d.addPage(p);
        return p;
    }

    static void text(PDPageContentStream cs, PDFont f, float size, float x, float y, String s) throws IOException {
        cs.beginText();
        cs.setFont(f, size);
        cs.newLineAtOffset(x, y);
        cs.showText(s);
        cs.endText();
    }

    static PDFont std(Standard14Fonts.FontName n) {
        return new PDType1Font(n);
    }

    static COSDictionary trueTypeUnembedded(String base, int[] widths) {
        COSDictionary f = new COSDictionary();
        f.setItem(COSName.TYPE, COSName.FONT);
        f.setItem(COSName.SUBTYPE, COSName.TRUE_TYPE);
        f.setName(COSName.BASE_FONT, base);
        f.setItem(COSName.ENCODING, COSName.WIN_ANSI_ENCODING);
        f.setInt(COSName.FIRST_CHAR, 32);
        f.setInt(COSName.LAST_CHAR, 32 + widths.length - 1);
        COSArray w = new COSArray();
        for (int x : widths) w.add(COSInteger.get(x));
        f.setItem(COSName.WIDTHS, w);
        COSDictionary fd = new COSDictionary();
        fd.setItem(COSName.TYPE, COSName.FONT_DESC);
        fd.setName(COSName.FONT_NAME, base);
        fd.setInt(COSName.FLAGS, 32);
        fd.setItem(COSName.FONT_BBOX, new PDRectangle(-665, -325, 2665, 1365).getCOSArray());
        fd.setInt(COSName.ITALIC_ANGLE, 0);
        fd.setInt(COSName.ASCENT, 905);
        fd.setInt(COSName.DESCENT, -212);
        fd.setInt(COSName.CAP_HEIGHT, 716);
        fd.setInt(COSName.STEM_V, 80);
        f.setItem(COSName.FONT_DESC, fd);
        return f;
    }

    static int[] arialWidths() throws IOException {
        PDType1Font h = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        int[] w = new int[224];
        for (int c = 32; c < 256; c++) {
            String g = WinAnsiEncoding.INSTANCE.getName(c);
            w[c - 32] = h.hasGlyph(g) ? Math.round(h.getWidthFromFont(c)) : 0;
        }
        return w;
    }

    static void raw(PDPage p, PDDocument d, String content) throws IOException {
        PDStream s = new PDStream(d);
        try (OutputStream o = s.createOutputStream(COSName.FLATE_DECODE)) {
            o.write(content.getBytes(StandardCharsets.ISO_8859_1));
        }
        p.setContents(s);
    }

    static BufferedImage picture(boolean alpha) {
        BufferedImage im = new BufferedImage(120, 80, alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 80; y++)
            for (int x = 0; x < 120; x++) {
                int a = alpha ? (x * 255 / 119) : 255;
                im.setRGB(x, y, (a << 24) | ((x * 2) << 16) | ((y * 3) << 8) | 160);
            }
        return im;
    }

    static final String PARA = "The quick brown fox jumps over the lazy dog. Archive 2026 (PDF/A) ";

    static {
        gen("s01_std14_unembedded", d -> {
            PDPage p = page(d);
            try (PDPageContentStream cs = new PDPageContentStream(d, p)) {
                float y = 780;
                for (Standard14Fonts.FontName n : Standard14Fonts.FontName.values()) {
                    PDFont f = std(n);
                    String s = n == Standard14Fonts.FontName.SYMBOL ? "\u03b1\u03b2\u03b3\u03b4\u03c0\u03b8 \u2211 \u221e" : n == Standard14Fonts.FontName.ZAPF_DINGBATS ? "\u2701\u2702\u2703\u2704\u260e\u2706" : n.getName() + ": " + PARA;
                    text(cs, f, 11, 50, y, s);
                    y -= 22;
                }
                text(cs, std(Standard14Fonts.FontName.TIMES_ROMAN), 12, 50, y - 10, "Accents: café naïve ångström € 100 – “quoted” • bullet");
            }
        });
        gen("s02_truetype_unembedded", d -> {
            PDPage p = page(d);
            int[] w = arialWidths();
            PDResources r = new PDResources();
            r.put(COSName.getPDFName("F1"), PDFontFactory.createFont(trueTypeUnembedded("Arial", w)));
            r.put(COSName.getPDFName("F2"), PDFontFactory.createFont(trueTypeUnembedded("Arial,Bold", w)));
            r.put(COSName.getPDFName("F3"), PDFontFactory.createFont(trueTypeUnembedded("TimesNewRomanPS-ItalicMT", w)));
            r.put(COSName.getPDFName("F4"), PDFontFactory.createFont(trueTypeUnembedded("CourierNewPSMT", w)));
            p.setResources(r);
            raw(p, d, "BT /F1 12 Tf 50 780 Td (Arial unembedded: " + PARA + ") Tj 0 -20 Td /F2 12 Tf (Arial Bold: " + PARA + ") Tj 0 -20 Td /F3 12 Tf (Times Italic widths borrowed) Tj 0 -20 Td /F4 12 Tf (Courier New) Tj ET");
        });
        gen("s03_type3", d -> {
            PDPage p = page(d);
            COSDictionary t3 = new COSDictionary();
            t3.setItem(COSName.TYPE, COSName.FONT);
            t3.setItem(COSName.SUBTYPE, COSName.TYPE3);
            t3.setItem(COSName.FONT_BBOX, new PDRectangle(0, 0, 1000, 1000).getCOSArray());
            COSArray m = new COSArray();
            for (float v : new float[] {0.001f, 0, 0, 0.001f, 0, 0}) m.add(new COSFloat(v));
            t3.setItem(COSName.FONT_MATRIX, m);
            COSDictionary procs = new COSDictionary();
            COSStream sq = d.getDocument().createCOSStream();
            try (OutputStream o = sq.createOutputStream()) { o.write("1000 0 0 0 1000 1000 d1 0 0 1000 1000 re f".getBytes()); }
            COSStream tri = d.getDocument().createCOSStream();
            try (OutputStream o = tri.createOutputStream()) { o.write("1000 0 0 0 1000 1000 d1 0 0 m 1000 0 l 500 1000 l f".getBytes()); }
            procs.setItem(COSName.getPDFName("square"), sq);
            procs.setItem(COSName.getPDFName("triangle"), tri);
            t3.setItem(COSName.CHAR_PROCS, procs);
            COSDictionary enc = new COSDictionary();
            enc.setItem(COSName.TYPE, COSName.ENCODING);
            COSArray diff = new COSArray();
            diff.add(COSInteger.get(65));
            diff.add(COSName.getPDFName("square"));
            diff.add(COSName.getPDFName("triangle"));
            enc.setItem(COSName.DIFFERENCES, diff);
            t3.setItem(COSName.ENCODING, enc);
            t3.setInt(COSName.FIRST_CHAR, 65);
            t3.setInt(COSName.LAST_CHAR, 66);
            COSArray w = new COSArray();
            w.add(COSInteger.get(1000));
            w.add(COSInteger.get(1000));
            t3.setItem(COSName.WIDTHS, w);
            t3.setItem(COSName.RESOURCES, new COSDictionary());
            PDResources r = new PDResources();
            r.put(COSName.getPDFName("T3"), PDFontFactory.createFont(t3));
            r.put(COSName.getPDFName("F1"), std(Standard14Fonts.FontName.HELVETICA));
            p.setResources(r);
            raw(p, d, "BT /T3 24 Tf 50 750 Td (ABABBA) Tj ET BT /F1 12 Tf 50 700 Td (Type 3 glyphs above) Tj ET");
        });
        gen("s04_transparency", d -> {
            PDPage p = page(d);
            PDFont f = std(Standard14Fonts.FontName.HELVETICA);
            PDImageXObject alpha = LosslessFactory.createFromImage(d, picture(true));
            try (PDPageContentStream cs = new PDPageContentStream(d, p)) {
                text(cs, f, 14, 50, 790, "Transparency: constant alpha, soft-masked image, blend mode");
                cs.setNonStrokingColor(Color.RED);
                cs.addRect(50, 600, 200, 150);
                cs.fill();
                PDExtendedGraphicsState gs = new PDExtendedGraphicsState();
                gs.setNonStrokingAlphaConstant(0.5f);
                cs.saveGraphicsState();
                cs.setGraphicsStateParameters(gs);
                cs.setNonStrokingColor(Color.BLUE);
                cs.addRect(150, 650, 200, 150);
                cs.fill();
                cs.restoreGraphicsState();
                cs.drawImage(alpha, 50, 400, 300, 200);
                PDExtendedGraphicsState bm = new PDExtendedGraphicsState();
                bm.setBlendMode(BlendMode.MULTIPLY);
                cs.saveGraphicsState();
                cs.setGraphicsStateParameters(bm);
                cs.setNonStrokingColor(Color.GREEN);
                cs.addRect(250, 350, 200, 150);
                cs.fill();
                cs.restoreGraphicsState();
                text(cs, f, 12, 50, 300, "Text after the transparent objects stays vector");
            }
            PDPage p2 = page(d);
            try (PDPageContentStream cs = new PDPageContentStream(d, p2)) {
                text(cs, f, 14, 50, 790, "A page without transparency");
            }
            COSDictionary g = new COSDictionary();
            g.setItem(COSName.S, COSName.TRANSPARENCY);
            g.setItem(COSName.CS, COSName.DEVICERGB);
            p.getCOSObject().setItem(COSName.GROUP, g);
        });
        gen("s05_javascript_actions", d -> {
            PDPage p = page(d);
            PDFont f = std(Standard14Fonts.FontName.HELVETICA);
            try (PDPageContentStream cs = new PDPageContentStream(d, p)) {
                text(cs, f, 12, 50, 780, "JavaScript, launch and page actions should be removed");
            }
            PDActionJavaScript js = new PDActionJavaScript("app.alert('hello');");
            d.getDocumentCatalog().setOpenAction(js);
            PDDocumentNameDictionary names = new PDDocumentNameDictionary(d.getDocumentCatalog());
            PDJavascriptNameTreeNode jsTree = new PDJavascriptNameTreeNode();
            jsTree.setNames(Map.of("init", new PDActionJavaScript("var x = 1;")));
            names.setJavascript(jsTree);
            d.getDocumentCatalog().setNames(names);
            PDPageAdditionalActions aa = new PDPageAdditionalActions();
            aa.setO(new PDActionJavaScript("app.beep(0);"));
            p.setActions(aa);
            PDAnnotationLink link = new PDAnnotationLink();
            link.setRectangle(new PDRectangle(50, 700, 200, 20));
            PDActionLaunch launch = new PDActionLaunch();
            launch.setFile(new PDSimpleFileSpecification(new COSString("calc.exe")));
            link.setAction(launch);
            PDAnnotationLink uri = new PDAnnotationLink();
            uri.setRectangle(new PDRectangle(50, 650, 200, 20));
            PDActionURI u = new PDActionURI();
            u.setURI("https://example.com/");
            uri.setAction(u);
            p.getAnnotations().add(link);
            p.getAnnotations().add(uri);
            d.getDocumentCatalog().getCOSObject().setItem(COSName.AA, new COSDictionary());
        });
        for (String kind : new String[] {"owner", "user"}) {
            gen("s06_encrypted_" + kind, d -> {
                PDPage p = page(d);
                try (PDPageContentStream cs = new PDPageContentStream(d, p)) {
                    text(cs, std(Standard14Fonts.FontName.TIMES_ROMAN), 12, 50, 780, "Encrypted document (" + kind + " password)");
                }
                AccessPermission ap = new AccessPermission();
                StandardProtectionPolicy spp = new StandardProtectionPolicy("owner-secret", kind.equals("user") ? "secret" : "", ap);
                spp.setEncryptionKeyLength(128);
                d.protect(spp);
            });
        }
        gen("s07_lzw", d -> {
            PDPage p = page(d);
            p.setResources(new PDResources());
            p.getResources().put(COSName.getPDFName("F1"), std(Standard14Fonts.FontName.COURIER));
            COSStream s = d.getDocument().createCOSStream();
            byte[] content = "BT /F1 12 Tf 50 780 Td (LZW compressed content stream) Tj ET 0 0 1 rg 50 600 200 100 re f".getBytes();
            try (OutputStream o = s.createOutputStream(COSName.LZW_DECODE)) { o.write(content); }
            p.getCOSObject().setItem(COSName.CONTENTS, s);
        });
        gen("s08_cmyk_lab_separation", d -> {
            PDPage p = page(d);
            PDFont f = std(Standard14Fonts.FontName.HELVETICA_BOLD);
            BufferedImage rgb = picture(false);
            PDImageXObject jpeg = JPEGFactory.createFromImage(d, rgb);
            PDImageXObject cmykImage = cmyk(d);
            PDLab lab = new PDLab();
            COSArray wp = new COSArray();
            wp.add(new COSFloat(0.9505f)); wp.add(new COSFloat(1f)); wp.add(new COSFloat(1.089f));
            lab.getCOSObject();
            COSDictionary labDict = new COSDictionary();
            labDict.setItem(COSName.WHITE_POINT, wp);
            COSArray labArr = new COSArray();
            labArr.add(COSName.LAB);
            labArr.add(labDict);
            COSArray sep = new COSArray();
            sep.add(COSName.SEPARATION);
            sep.add(COSName.getPDFName("Spot Orange"));
            sep.add(COSName.DEVICECMYK);
            COSDictionary fn = new COSDictionary();
            fn.setInt(COSName.FUNCTION_TYPE, 2);
            COSArray dom = new COSArray(); dom.add(COSInteger.get(0)); dom.add(COSInteger.get(1));
            fn.setItem(COSName.DOMAIN, dom);
            COSArray c0 = new COSArray(); for (int i = 0; i < 4; i++) c0.add(COSInteger.get(0));
            COSArray c1 = new COSArray(); c1.add(COSInteger.get(0)); c1.add(new COSFloat(0.6f)); c1.add(COSInteger.get(1)); c1.add(COSInteger.get(0));
            fn.setItem(COSName.C0, c0); fn.setItem(COSName.C1, c1); fn.setInt(COSName.N, 1);
            sep.add(fn);
            PDResources r = new PDResources();
            r.put(COSName.getPDFName("F1"), f);
            r.put(COSName.getPDFName("Im1"), jpeg);
            r.put(COSName.getPDFName("Im2"), cmykImage);
            r.getCOSObject().setItem(COSName.COLORSPACE, new COSDictionary());
            ((COSDictionary) r.getCOSObject().getDictionaryObject(COSName.COLORSPACE)).setItem("CSLab", labArr);
            ((COSDictionary) r.getCOSObject().getDictionaryObject(COSName.COLORSPACE)).setItem("CSSep", sep);
            p.setResources(r);
            raw(p, d, "0 0 0 1 k BT /F1 14 Tf 50 790 Td (Device CMYK text, Lab and a spot colour) Tj ET "
                    + "0.8 0.1 0 0 k 50 650 150 100 re f 0 0.9 0.9 0 K 4 w 220 650 150 100 re S "
                    + "/CSLab cs 60 50 -40 sc 50 500 150 100 re f /CSSep cs 0.8 sc 220 500 150 100 re f "
                    + "q 200 0 0 130 50 330 cm /Im1 Do Q q 200 0 0 130 300 330 cm /Im2 Do Q "
                    + "0.5 g 50 250 300 40 re f");
        });
        gen("s09_annotations_no_appearance", d -> {
            PDPage p = page(d);
            try (PDPageContentStream cs = new PDPageContentStream(d, p)) {
                text(cs, std(Standard14Fonts.FontName.HELVETICA), 12, 50, 780, "Annotations without appearance streams and with forbidden flags");
                text(cs, std(Standard14Fonts.FontName.HELVETICA), 12, 50, 600, "This line is highlighted.");
            }
            PDAnnotationText note = new PDAnnotationText();
            note.setRectangle(new PDRectangle(400, 760, 20, 20));
            note.setContents("A sticky note");
            note.setColor(new PDColor(new float[] {1, 1, 0}, PDDeviceRGB.INSTANCE));
            PDAnnotationSquare sq = new PDAnnotationSquare();
            sq.setRectangle(new PDRectangle(50, 650, 150, 80));
            sq.setColor(new PDColor(new float[] {1, 0, 0}, PDDeviceRGB.INSTANCE));
            sq.setHidden(true);
            PDAnnotationHighlight hl = new PDAnnotationHighlight();
            hl.setRectangle(new PDRectangle(48, 595, 150, 18));
            hl.setQuadPoints(new float[] {48, 613, 198, 613, 48, 595, 198, 595});
            hl.setColor(new PDColor(new float[] {1, 1, 0}, PDDeviceRGB.INSTANCE));
            hl.setConstantOpacity(0.5f);
            PDAnnotationFreeText ft = new PDAnnotationFreeText();
            ft.setRectangle(new PDRectangle(250, 650, 200, 40));
            ft.setContents("Free text annotation");
            ft.setDefaultAppearance("/Helv 12 Tf 0 0 1 rg");
            for (PDAnnotation a : List.of(note, sq, hl, ft)) p.getAnnotations().add(a);
            COSDictionary movie = new COSDictionary();
            movie.setItem(COSName.TYPE, COSName.ANNOT);
            movie.setItem(COSName.SUBTYPE, COSName.getPDFName("Movie"));
            movie.setItem(COSName.RECT, new PDRectangle(300, 300, 100, 100).getCOSArray());
            p.getAnnotations().add(PDAnnotation.createAnnotation(movie));
        });
        gen("s10_forms", d -> {
            PDPage p = page(d);
            PDFont helv = std(Standard14Fonts.FontName.HELVETICA);
            try (PDPageContentStream cs = new PDPageContentStream(d, p)) {
                text(cs, helv, 12, 50, 780, "A form with fields that have no appearances");
            }
            PDAcroForm form = new PDAcroForm(d);
            d.getDocumentCatalog().setAcroForm(form);
            PDResources dr = new PDResources();
            dr.put(COSName.getPDFName("Helv"), helv);
            form.setDefaultResources(dr);
            form.setDefaultAppearance("/Helv 0 Tf 0 g");
            form.setNeedAppearances(true);
            PDTextField t = new PDTextField(form);
            t.setPartialName("name");
            form.getFields().add(t);
            PDAnnotationWidget w = t.getWidgets().get(0);
            w.setRectangle(new PDRectangle(50, 700, 250, 24));
            w.setPage(p);
            p.getAnnotations().add(w);
            t.getCOSObject().setString(COSName.V, "Jane Doe");
            PDCheckBox cb = new PDCheckBox(form);
            cb.setPartialName("agree");
            form.getFields().add(cb);
            PDAnnotationWidget w2 = cb.getWidgets().get(0);
            w2.setRectangle(new PDRectangle(50, 650, 16, 16));
            w2.setPage(p);
            p.getAnnotations().add(w2);
            PDActionJavaScript calc = new PDActionJavaScript("event.value = 1;");
            PDFormFieldAdditionalActions faa = new PDFormFieldAdditionalActions();
            faa.setC(calc);
            t.getCOSObject().setItem(COSName.AA, faa.getCOSObject());
        });
        gen("s11_embedded_files", d -> {
            PDPage p = page(d);
            try (PDPageContentStream cs = new PDPageContentStream(d, p)) {
                text(cs, std(Standard14Fonts.FontName.HELVETICA), 12, 50, 780, "A document with embedded files");
            }
            PDEmbeddedFile ef = new PDEmbeddedFile(d, new ByteArrayInputStream("hello,world\n1,2\n".getBytes()));
            ef.setSubtype("text/csv");
            PDComplexFileSpecification fs = new PDComplexFileSpecification();
            fs.setFile("data.csv");
            fs.setEmbeddedFile(ef);
            PDEmbeddedFilesNameTreeNode tree = new PDEmbeddedFilesNameTreeNode();
            tree.setNames(Map.of("data.csv", fs));
            PDDocumentNameDictionary names = new PDDocumentNameDictionary(d.getDocumentCatalog());
            names.setEmbeddedFiles(tree);
            d.getDocumentCatalog().setNames(names);
            PDAnnotationFileAttachment att = new PDAnnotationFileAttachment();
            att.setRectangle(new PDRectangle(400, 760, 20, 20));
            PDEmbeddedFile ef2 = new PDEmbeddedFile(d, new ByteArrayInputStream("MZ fake exe".getBytes()));
            PDComplexFileSpecification fs2 = new PDComplexFileSpecification();
            fs2.setFile("tool.exe");
            fs2.setEmbeddedFile(ef2);
            att.setFile(fs2);
            p.getAnnotations().add(att);
        });
        gen("s12_optional_content", d -> {
            PDPage p = page(d);
            PDFont f = std(Standard14Fonts.FontName.HELVETICA);
            PDOptionalContentProperties ocp = new PDOptionalContentProperties();
            PDOptionalContentGroup on = new PDOptionalContentGroup("Visible layer");
            PDOptionalContentGroup off = new PDOptionalContentGroup("Hidden layer");
            ocp.addGroup(on);
            ocp.addGroup(off);
            ocp.setGroupEnabled(off, false);
            d.getDocumentCatalog().setOCProperties(ocp);
            COSDictionary dflt = (COSDictionary) ocp.getCOSObject().getDictionaryObject(COSName.D);
            dflt.setItem(COSName.getPDFName("AS"), new COSArray());
            try (PDPageContentStream cs = new PDPageContentStream(d, p)) {
                text(cs, f, 12, 50, 780, "Optional content: only the visible layer should show");
                cs.beginMarkedContent(COSName.OC, on);
                text(cs, f, 12, 50, 740, "Visible layer text");
                cs.endMarkedContent();
                cs.beginMarkedContent(COSName.OC, off);
                text(cs, f, 12, 50, 700, "HIDDEN LAYER TEXT");
                cs.setNonStrokingColor(Color.RED);
                cs.addRect(50, 600, 200, 80);
                cs.fill();
                cs.endMarkedContent();
            }
        });
        gen("s13_misc_forbidden", d -> {
            PDPage p = page(d);
            PDFont f = std(Standard14Fonts.FontName.HELVETICA);
            PDImageXObject im = LosslessFactory.createFromImage(d, picture(false));
            im.setInterpolate(true);
            COSArray alts = new COSArray();
            COSDictionary alt = new COSDictionary();
            alt.setItem(COSName.getPDFName("Image"), im.getCOSObject());
            alts.add(alt);
            im.getCOSObject().setItem(COSName.getPDFName("Alternates"), alts);
            PDExtendedGraphicsState tr = new PDExtendedGraphicsState();
            tr.getCOSObject().setItem(COSName.TR, COSName.IDENTITY);
            tr.getCOSObject().setItem(COSName.getPDFName("TR2"), COSName.IDENTITY);
            COSStream ps = d.getDocument().createCOSStream();
            ps.setItem(COSName.TYPE, COSName.XOBJECT);
            ps.setItem(COSName.SUBTYPE, COSName.PS);
            try (OutputStream o = ps.createOutputStream()) { o.write("showpage".getBytes()); }
            try (PDPageContentStream cs = new PDPageContentStream(d, p)) {
                cs.setGraphicsStateParameters(tr);
                text(cs, f, 12, 50, 780, "Interpolate, Alternates, transfer functions, a PostScript XObject, a bad intent");
                cs.drawImage(im, 50, 500, 240, 160);
            }
            p.getResources().getCOSObject().getCOSDictionary(COSName.XOBJECT).setItem("PS1", ps);
            PDStream extra = new PDStream(d);
            try (OutputStream o = extra.createOutputStream(COSName.FLATE_DECODE)) { o.write("/Bogus ri /PS1 Do".getBytes()); }
            COSArray contents = new COSArray();
            contents.add(p.getCOSObject().getDictionaryObject(COSName.CONTENTS));
            contents.add(extra.getCOSObject());
            p.getCOSObject().setItem(COSName.CONTENTS, contents);
        });
        gen("s14_widths_mismatch", d -> {
            PDPage p = page(d);
            PDType0Font f = PDType0Font.load(d, liberation(), false);
            try (PDPageContentStream cs = new PDPageContentStream(d, p)) {
                text(cs, f, 14, 50, 780, "Embedded TrueType whose W array disagrees with the program");
            }
            COSDictionary cid = (COSDictionary) ((COSArray) f.getCOSObject().getDictionaryObject(COSName.DESCENDANT_FONTS)).getObject(0);
            COSArray w = new COSArray();
            w.add(COSInteger.get(0));
            w.add(COSInteger.get(3000));
            COSArray ws = new COSArray();
            for (int i = 0; i < 3000; i++) ws.add(COSInteger.get(600));
            w.add(ws);
            COSArray ww = new COSArray(); ww.add(COSInteger.get(0)); ww.add(ws);
            cid.setItem(COSName.W, ww);
        });
        gen("s15_subset_notdef", d -> {
            PDPage p = page(d);
            PDType0Font f = PDType0Font.load(d, liberation(), true);
            try (PDPageContentStream cs = new PDPageContentStream(d, p)) {
                text(cs, f, 14, 50, 780, "Subset embedded font");
                cs.beginText();
                cs.setFont(f, 14);
                cs.newLineAtOffset(50, 740);
                cs.showTextWithPositioning(new Object[] {"Missing glyph next:"});
                cs.endText();
            }
        });
        gen("s16_cid_unembedded", d -> {
            PDPage p = page(d);
            COSDictionary type0 = new COSDictionary();
            type0.setItem(COSName.TYPE, COSName.FONT);
            type0.setItem(COSName.SUBTYPE, COSName.TYPE0);
            type0.setName(COSName.BASE_FONT, "ArialMT");
            type0.setItem(COSName.ENCODING, COSName.IDENTITY_H);
            COSDictionary cid = new COSDictionary();
            cid.setItem(COSName.TYPE, COSName.FONT);
            cid.setItem(COSName.SUBTYPE, COSName.CID_FONT_TYPE2);
            cid.setName(COSName.BASE_FONT, "ArialMT");
            COSDictionary info = new COSDictionary();
            info.setString(COSName.REGISTRY, "Adobe");
            info.setString(COSName.ORDERING, "Identity");
            info.setInt(COSName.SUPPLEMENT, 0);
            cid.setItem(COSName.CIDSYSTEMINFO, info);
            COSDictionary fd = new COSDictionary();
            fd.setItem(COSName.TYPE, COSName.FONT_DESC);
            fd.setName(COSName.FONT_NAME, "ArialMT");
            fd.setInt(COSName.FLAGS, 32);
            fd.setItem(COSName.FONT_BBOX, new PDRectangle(-665, -325, 2665, 1365).getCOSArray());
            fd.setInt(COSName.ITALIC_ANGLE, 0);
            fd.setInt(COSName.ASCENT, 905);
            fd.setInt(COSName.DESCENT, -212);
            fd.setInt(COSName.CAP_HEIGHT, 716);
            fd.setInt(COSName.STEM_V, 80);
            cid.setItem(COSName.FONT_DESC, fd);
            cid.setInt(COSName.DW, 556);
            COSArray desc = new COSArray();
            desc.add(cid);
            type0.setItem(COSName.DESCENDANT_FONTS, desc);
            String msg = "CID font, no program";
            StringBuilder hex = new StringBuilder();
            StringBuilder cmap = new StringBuilder("/CIDInit /ProcSet findresource begin 12 dict begin begincmap /CMapName /U def 1 begincodespacerange <0000> <FFFF> endcodespacerange " + msg.length() + " beginbfchar\n");
            for (int i = 0; i < msg.length(); i++) {
                int cidv = 100 + i;
                hex.append(String.format("%04X", cidv));
                cmap.append(String.format("<%04X> <%04X>\n", cidv, (int) msg.charAt(i)));
            }
            cmap.append("endbfchar endcmap CMapName currentdict /CMap defineresource pop end end");
            PDStream tu = new PDStream(d);
            try (OutputStream o = tu.createOutputStream()) { o.write(cmap.toString().getBytes()); }
            type0.setItem(COSName.TO_UNICODE, tu);
            PDResources r = new PDResources();
            r.put(COSName.getPDFName("F1"), PDFontFactory.createFont(type0));
            p.setResources(r);
            raw(p, d, "BT /F1 14 Tf 50 780 Td <" + hex + "> Tj ET");
        });
        gen("s17_all_in_one", d -> {
            PDPage p = page(d);
            PDFont helv = std(Standard14Fonts.FontName.HELVETICA);
            PDImageXObject alpha = LosslessFactory.createFromImage(d, picture(true));
            try (PDPageContentStream cs = new PDPageContentStream(d, p)) {
                cs.setNonStrokingColor(0.1f, 0.2f, 0.3f, 0.4f);
                text(cs, helv, 12, 50, 780, "Many problems at once: std14, CMYK, soft mask, JS, annotations");
                cs.drawImage(alpha, 50, 500, 300, 200);
            }
            d.getDocumentCatalog().setOpenAction(new PDActionJavaScript("app.alert(1)"));
            PDAnnotationText note = new PDAnnotationText();
            note.setRectangle(new PDRectangle(400, 760, 20, 20));
            note.setContents("note");
            p.getAnnotations().add(note);
            PDDocumentInformation info = d.getDocumentInformation();
            info.setTitle("All in one été");
            info.setAuthor("Stirling Bench");
            info.setSubject("Synthetic");
            info.setKeywords("pdfa, test");
            info.setCreator("SynthGen");
            info.setProducer("PDFBox");
            info.setCreationDate(new GregorianCalendar(2026, 0, 2, 3, 4, 5));
            info.setModificationDate(new GregorianCalendar(2026, 1, 3, 4, 5, 6));
            info.setTrapped("False");
            info.setCustomMetadataValue("Company", "Stirling");
        });
        gen("s18_form_xobject_shared", d -> {
            PDFont f = std(Standard14Fonts.FontName.TIMES_BOLD);
            PDFormXObject form = new PDFormXObject(d);
            form.setBBox(new PDRectangle(0, 0, 200, 50));
            PDResources fr = new PDResources();
            fr.put(COSName.getPDFName("F1"), f);
            form.setResources(fr);
            try (OutputStream o = form.getContentStream().createOutputStream(COSName.FLATE_DECODE)) {
                o.write("0 0 1 0 k 0 0 200 50 re f BT /F1 18 Tf 10 15 Td (Shared form) Tj ET".getBytes());
            }
            for (int i = 0; i < 3; i++) {
                PDPage p = page(d);
                try (PDPageContentStream cs = new PDPageContentStream(d, p)) {
                    cs.saveGraphicsState();
                    cs.transform(org.apache.pdfbox.util.Matrix.getTranslateInstance(50, 700 - i * 100));
                    cs.drawForm(form);
                    cs.restoreGraphicsState();
                }
            }
        });
    }

    static org.apache.fontbox.ttf.TrueTypeFont liberation() throws IOException {
        try (InputStream in = PDDocument.class.getResourceAsStream("/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf")) {
            return new org.apache.fontbox.ttf.TTFParser().parse(new org.apache.pdfbox.io.RandomAccessReadBuffer(in.readAllBytes()));
        }
    }

    static PDImageXObject cmyk(PDDocument d) throws IOException {
        int w = 60, h = 40;
        byte[] data = new byte[w * h * 4];
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                int i = (y * w + x) * 4;
                data[i] = (byte) (x * 4); data[i + 1] = (byte) (y * 6); data[i + 2] = (byte) 40; data[i + 3] = 0;
            }
        ByteArrayOutputStream z = new ByteArrayOutputStream();
        try (java.util.zip.DeflaterOutputStream o = new java.util.zip.DeflaterOutputStream(z)) { o.write(data); }
        return new PDImageXObject(d, new ByteArrayInputStream(z.toByteArray()), COSName.FLATE_DECODE, w, h, 8, PDDeviceCMYK.INSTANCE);
    }
}
