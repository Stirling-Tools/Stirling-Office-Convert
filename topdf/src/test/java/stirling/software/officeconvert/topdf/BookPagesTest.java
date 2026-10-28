package stirling.software.officeconvert.topdf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.BreakType;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BookPagesTest {

    @TempDir
    Path dir;

    private static final OfficeToPdf.Options OPTIONS = OfficeToPdf.Options.defaults().timeout(Duration.ofMinutes(2));

    private Path docx() throws IOException {
        Path file = dir.resolve("chapters.docx");
        try (XWPFDocument doc = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (String title : List.of("Opening Chapter", "Middle Chapter", "Closing Chapter")) {
                XWPFParagraph heading = doc.createParagraph();
                heading.getCTP().addNewPPr().addNewOutlineLvl().setVal(BigInteger.ZERO);
                heading.createRun().setText(title);
                XWPFParagraph body = doc.createParagraph();
                body.createRun().setText("Body text under " + title + ".");
                body.createRun().addBreak(BreakType.PAGE);
            }
            doc.write(out);
            Files.write(file, out.toByteArray());
        }
        return file;
    }

    private static String pageText(PDDocument doc, int page) throws IOException {
        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setStartPage(page);
        stripper.setEndPage(page);
        return stripper.getText(doc);
    }

    @Test
    void wordDocumentsGetContentsAndPageNumbers() throws IOException {
        Path out = dir.resolve("chapters.pdf");
        OfficeToPdf.Result r = OfficeToPdf.convert(docx(), out, OPTIONS.tableOfContents(true).pageNumbers(true));
        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            int pages = doc.getNumberOfPages();
            assertEquals(pages, r.pages());
            String contents = pageText(doc, pages);
            assertTrue(contents.contains("Contents") && contents.contains("Closing Chapter"), contents);
            List<Integer> targets = new ArrayList<>();
            for (PDAnnotation a : doc.getPage(pages - 1).getAnnotations()) {
                if (a instanceof PDAnnotationLink link && link.getDestination() instanceof PDPageDestination d) {
                    targets.add(doc.getPages().indexOf(d.getPage()));
                }
            }
            assertEquals(3, targets.size(), targets.toString());
            assertTrue(targets.stream().allMatch(t -> t >= 0 && t < pages - 1), targets.toString());
            for (int p = 1; p <= pages; p++) {
                assertTrue(List.of(pageText(doc, p).split("\\R")).contains(Integer.toString(p)), pageText(doc, p));
            }
            List<String> bookmarks = new ArrayList<>();
            doc.getDocumentCatalog().getDocumentOutline().children().forEach(i -> bookmarks.add(i.getTitle()));
            assertEquals("Contents", bookmarks.get(bookmarks.size() - 1));
        }
    }

    @Test
    void plainTextGetsPageNumbersAndNoContentsWithoutHeadings() throws IOException {
        Path txt = dir.resolve("story.txt");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 400; i++) {
            sb.append("Line ").append(i).append(" of a plain story told without any headings at all.\n");
        }
        Files.writeString(txt, sb, StandardCharsets.UTF_8);
        Path out = dir.resolve("story.pdf");
        OfficeToPdf.convert(txt, out, OPTIONS.tableOfContents(true).pageNumbers(true));
        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            assertTrue(doc.getNumberOfPages() > 1);
            for (int p = 1; p <= doc.getNumberOfPages(); p++) {
                String text = pageText(doc, p);
                assertTrue(List.of(text.split("\\R")).contains(Integer.toString(p)), text);
                assertTrue(!text.contains("Contents"), text);
            }
        }
    }
}
