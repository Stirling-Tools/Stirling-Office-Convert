package stirling.software.officeconvert.topdf.docx;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

import stirling.software.officeconvert.topdf.OfficeToPdf;
import stirling.software.officeconvert.topdf.testing.Fixtures;

final class DocxDoc {

    static final String NS = "xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\""
            + " xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\""
            + " xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\""
            + " xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\""
            + " xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\""
            + " xmlns:wps=\"http://schemas.microsoft.com/office/word/2010/wordprocessingShape\""
            + " xmlns:mc=\"http://schemas.openxmlformats.org/markup-compatibility/2006\""
            + " xmlns:v=\"urn:schemas-microsoft-com:vml\" xmlns:o=\"urn:schemas-microsoft-com:office:office\"";

    static final String LETTER = "<w:sectPr><w:pgSz w:w=\"12240\" w:h=\"15840\"/><w:pgMar w:top=\"1440\""
            + " w:right=\"1440\" w:bottom=\"1440\" w:left=\"1440\" w:header=\"720\" w:footer=\"720\" w:gutter=\"0\"/>"
            + "</w:sectPr>";

    private final Fixtures.Zip zip = Fixtures.edit(Fixtures.docx("placeholder"));

    private String body = "";

    private String sectPr = LETTER;

    static String p(String text) {
        return "<w:p><w:r><w:t xml:space=\"preserve\">" + escape(text) + "</w:t></w:r></w:p>";
    }

    static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    DocxDoc body(String xml) {
        body = xml;
        return this;
    }

    DocxDoc section(String xml) {
        sectPr = xml;
        return this;
    }

    DocxDoc part(String name, String relType, String contentType, String xml) {
        zip.put("word/" + name, "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" + xml);
        zip.relationship("/word/document.xml", "rId" + name.replaceAll("[^A-Za-z0-9]", ""), Fixtures.REL + relType,
                name, false);
        zip.override("/word/" + name, contentType);
        return this;
    }

    DocxDoc styles(String inner) {
        return part("styles.xml", "styles", "application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml",
                "<w:styles " + NS + ">" + inner + "</w:styles>");
    }

    DocxDoc numbering(String inner) {
        return part("numbering.xml", "numbering",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.numbering+xml",
                "<w:numbering " + NS + ">" + inner + "</w:numbering>");
    }

    DocxDoc footnotes(String inner) {
        return part("footnotes.xml", "footnotes",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.footnotes+xml",
                "<w:footnotes " + NS + ">" + inner + "</w:footnotes>");
    }

    DocxDoc footer(String name, String inner) {
        return part(name, "footer", "application/vnd.openxmlformats-officedocument.wordprocessingml.footer+xml",
                "<w:ftr " + NS + ">" + inner + "</w:ftr>");
    }

    DocxDoc header(String name, String inner) {
        return part(name, "header", "application/vnd.openxmlformats-officedocument.wordprocessingml.header+xml",
                "<w:hdr " + NS + ">" + inner + "</w:hdr>");
    }

    DocxDoc media(String name, byte[] data, String id) {
        zip.put("word/media/" + name, data);
        zip.defaultType(name.substring(name.lastIndexOf('.') + 1), "image/png");
        zip.relationship("/word/document.xml", id, Fixtures.REL + "image", "media/" + name, false);
        return this;
    }

    static String chartXml(String title, String series, String... categories) {
        StringBuilder cats = new StringBuilder();
        StringBuilder vals = new StringBuilder();
        for (int i = 0; i < categories.length; i++) {
            cats.append("<c:pt idx=\"").append(i).append("\"><c:v>").append(categories[i]).append("</c:v></c:pt>");
            vals.append("<c:pt idx=\"").append(i).append("\"><c:v>").append(i + 2).append("</c:v></c:pt>");
        }
        return "<c:chartSpace xmlns:c=\"http://schemas.openxmlformats.org/drawingml/2006/chart\""
                + " xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\""
                + " xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><c:chart><c:title>"
                + "<c:tx><c:rich><a:bodyPr/><a:p><a:r><a:t>" + title + "</a:t></a:r></a:p></c:rich></c:tx></c:title>"
                + "<c:plotArea><c:barChart><c:barDir val=\"col\"/><c:grouping val=\"clustered\"/><c:ser><c:idx"
                + " val=\"0\"/><c:tx><c:strRef><c:f>Sheet1!$B$1</c:f><c:strCache><c:ptCount val=\"1\"/><c:pt"
                + " idx=\"0\"><c:v>" + series + "</c:v></c:pt></c:strCache></c:strRef></c:tx><c:cat><c:strRef><c:f>"
                + "Sheet1!$A$2</c:f><c:strCache><c:ptCount val=\"" + categories.length + "\"/>" + cats
                + "</c:strCache></c:strRef></c:cat><c:val><c:numRef><c:f>Sheet1!$B$2</c:f><c:numCache><c:formatCode>"
                + "General</c:formatCode><c:ptCount val=\"" + categories.length + "\"/>" + vals + "</c:numCache>"
                + "</c:numRef></c:val></c:ser><c:axId val=\"1\"/><c:axId val=\"2\"/></c:barChart><c:catAx><c:axId"
                + " val=\"1\"/><c:crossAx val=\"2\"/></c:catAx><c:valAx><c:axId val=\"2\"/><c:majorGridlines/>"
                + "<c:crossAx val=\"1\"/></c:valAx></c:plotArea><c:legend><c:legendPos val=\"b\"/></c:legend>"
                + "</c:chart><c:externalData r:id=\"rIdData\"><c:autoUpdate val=\"1\"/></c:externalData>"
                + "</c:chartSpace>";
    }

    static String chartRun(String id) {
        return "<w:r><w:drawing><wp:inline distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\"><wp:extent cx=\"5080000\""
                + " cy=\"3048000\"/><wp:docPr id=\"7\" name=\"Chart\"/><a:graphic><a:graphicData"
                + " uri=\"http://schemas.openxmlformats.org/drawingml/2006/chart\"><c:chart"
                + " xmlns:c=\"http://schemas.openxmlformats.org/drawingml/2006/chart\" r:id=\"" + id + "\"/>"
                + "</a:graphicData></a:graphic></wp:inline></w:drawing></w:r>";
    }

    DocxDoc chart(String name, String id, String xml) {
        zip.put("word/charts/" + name, "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" + xml);
        zip.relationship("/word/document.xml", id, Fixtures.REL + "chart", "charts/" + name, false);
        zip.override("/word/charts/" + name, "application/vnd.openxmlformats-officedocument.drawingml.chart+xml");
        return this;
    }

    DocxDoc relationship(String id, String type, String target, boolean external) {
        zip.relationship("/word/document.xml", id, type, target, external);
        return this;
    }

    Fixtures.Zip zip() {
        return zip;
    }

    byte[] bytes() {
        zip.put("word/document.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><w:document " + NS
                + "><w:body>" + body + sectPr + "</w:body></w:document>");
        return zip.bytes();
    }

    static Rendered render(Path dir, String name, byte[] docx) throws IOException {
        Path in = Fixtures.write(dir, name + ".docx", docx);
        Path out = dir.resolve(name + ".pdf");
        OfficeToPdf.Result result = OfficeToPdf.convert(in, out,
                OfficeToPdf.Options.defaults().timeout(Duration.ofMinutes(2)));
        return new Rendered(out, result);
    }

    record Word(String text, int page, float x, float y) {}

    record Rendered(Path pdf, OfficeToPdf.Result result) {

        List<Word> words() throws IOException {
            List<Word> out = new ArrayList<>();
            try (PDDocument d = open()) {
                PDFTextStripper s = new PDFTextStripper() {
                    @Override
                    protected void writeString(String text, List<TextPosition> positions) {
                        StringBuilder sb = new StringBuilder();
                        TextPosition first = null;
                        for (TextPosition p : positions) {
                            String u = p.getUnicode();
                            if (u.isBlank()) {
                                if (first != null) {
                                    out.add(new Word(sb.toString(), getCurrentPageNo(), first.getXDirAdj(),
                                            first.getYDirAdj()));
                                }
                                sb.setLength(0);
                                first = null;
                                continue;
                            }
                            if (first == null) {
                                first = p;
                            }
                            sb.append(u);
                        }
                        if (first != null) {
                            out.add(new Word(sb.toString(), getCurrentPageNo(), first.getXDirAdj(),
                                    first.getYDirAdj()));
                        }
                    }
                };
                s.setSortByPosition(true);
                s.getText(d);
            }
            return out;
        }

        Word word(String text) throws IOException {
            for (Word w : words()) {
                if (w.text().equals(text)) {
                    return w;
                }
            }
            throw new AssertionError("no word " + text);
        }

        float height(int page) throws IOException {
            try (PDDocument d = open()) {
                return d.getPage(page - 1).getMediaBox().getHeight();
            }
        }

        PDDocument open() throws IOException {
            return Loader.loadPDF(Files.readAllBytes(pdf));
        }

        String text() throws IOException {
            try (PDDocument d = open()) {
                return new PDFTextStripper().getText(d);
            }
        }

        String text(int page) throws IOException {
            try (PDDocument d = open()) {
                PDFTextStripper s = new PDFTextStripper();
                s.setStartPage(page);
                s.setEndPage(page);
                return s.getText(d);
            }
        }

        int pages() throws IOException {
            try (PDDocument d = open()) {
                return d.getNumberOfPages();
            }
        }
    }
}
