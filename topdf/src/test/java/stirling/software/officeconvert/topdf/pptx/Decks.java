package stirling.software.officeconvert.topdf.pptx;

import java.awt.Dimension;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.apache.poi.xslf.usermodel.XMLSlideShow;

import stirling.software.officeconvert.topdf.OfficeToPdf;
import stirling.software.officeconvert.topdf.testing.Fixtures;

final class Decks {

    static final String A = "http://schemas.openxmlformats.org/drawingml/2006/main";

    static final String P = "http://schemas.openxmlformats.org/presentationml/2006/main";

    static final String R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";

    static final String NS = "xmlns:a=\"" + A + "\" xmlns:p=\"" + P + "\" xmlns:r=\"" + R + "\"";

    record Converted(Path pdf, OfficeToPdf.Result result) {

        PDDocument open() throws IOException {
            return Loader.loadPDF(pdf.toFile());
        }

        String text() throws IOException {
            try (PDDocument d = open()) {
                return new PDFTextStripper().getText(d);
            }
        }

        BufferedImage render(int page, float dpi) throws IOException {
            try (PDDocument d = open()) {
                return new PDFRenderer(d).renderImageWithDPI(page, dpi);
            }
        }

        List<TextPosition> positions(int page) throws IOException {
            List<TextPosition> out = new ArrayList<>();
            try (PDDocument d = open()) {
                PDFTextStripper s = new PDFTextStripper() {
                    @Override
                    protected void processTextPosition(TextPosition text) {
                        out.add(text);
                    }
                };
                s.setStartPage(page + 1);
                s.setEndPage(page + 1);
                s.getText(d);
            }
            return out;
        }
    }

    private Decks() {}

    static byte[] deck(Consumer<XMLSlideShow> build) {
        return deck(new Dimension(720, 540), build);
    }

    static byte[] deck(Dimension size, Consumer<XMLSlideShow> build) {
        try (XMLSlideShow ppt = new XMLSlideShow(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ppt.setPageSize(size);
            build.accept(ppt);
            ppt.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static byte[] slideXml(String shapes) {
        byte[] base = deck(ppt -> ppt.createSlide());
        Fixtures.Zip z = Fixtures.edit(base);
        z.insertBefore("ppt/slides/slide1.xml", "</p:spTree>", shapes);
        return z.bytes();
    }

    static String textBox(int id, long x, long y, long cx, long cy, String bodyPr, String paragraphs) {
        return "<p:sp " + NS + "><p:nvSpPr><p:cNvPr id=\"" + id + "\" name=\"Box " + id + "\"/><p:cNvSpPr txBox=\"1\"/>"
                + "<p:nvPr/></p:nvSpPr><p:spPr><a:xfrm><a:off x=\"" + x + "\" y=\"" + y + "\"/><a:ext cx=\"" + cx
                + "\" cy=\"" + cy + "\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom><a:noFill/></p:spPr>"
                + "<p:txBody>" + bodyPr + "<a:lstStyle/>" + paragraphs + "</p:txBody></p:sp>";
    }

    static String run(String text, String rPr) {
        return "<a:r><a:rPr lang=\"en-US\" " + rPr + "/><a:t>" + text + "</a:t></a:r>";
    }

    static Converted convert(Path dir, String name, byte[] pptx) throws IOException {
        Path in = Fixtures.write(dir, name, pptx);
        Path out = dir.resolve(name + ".pdf");
        OfficeToPdf.Result r = OfficeToPdf.convert(in, out, OfficeToPdf.Options.defaults().timeout(Duration.ofMinutes(2)));
        return new Converted(out, r);
    }
}
