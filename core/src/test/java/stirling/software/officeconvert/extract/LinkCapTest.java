package stirling.software.officeconvert.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.junit.jupiter.api.Test;

class LinkCapTest {

    @Test
    void pagesWithTooManyLinksKeepTheFirstOnesThatLieOnThePage() throws IOException {
        List<PDAnnotation> annotations = new ArrayList<>();
        for (int i = 0; i < 20_000; i++) {
            annotations.add(link(2_000 + i % 50, 100, 1, 1, "https://off/" + i));
        }
        annotations.add(link(100, 700, 50, 12, "https://real"));
        for (int i = 0; i < 10_000; i++) {
            annotations.add(link(i % 600, 0, 1, 1, "https://on/" + i));
        }
        List<PageData.Link> links = read(annotations);
        assertEquals(PageReader.MAX_LINKS, links.size());
        assertEquals("https://real", links.getFirst().uri());
        assertTrue(links.stream().noneMatch(l -> l.uri().startsWith("https://off/")));
        assertEquals("https://on/" + (PageReader.MAX_LINKS - 2), links.getLast().uri());
    }

    @Test
    void pagesWithinTheCapKeepEveryLink() throws IOException {
        List<PDAnnotation> annotations = List.of(link(2_000, 100, 1, 1, "https://off"),
                link(100, 700, 50, 12, "https://real"), link(-30, -30, 10, 10, "https://corner"));
        List<PageData.Link> links = read(annotations);
        assertEquals(List.of("https://off", "https://real", "https://corner"),
                links.stream().map(PageData.Link::uri).toList());
    }

    private static List<PageData.Link> read(List<PDAnnotation> annotations) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (PDDocument d = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            d.addPage(page);
            page.setAnnotations(annotations);
            d.save(bytes);
        }
        List<PageData.Link> out = new ArrayList<>();
        try (PDDocument d = Loader.loadPDF(bytes.toByteArray())) {
            new PageReader(d).read(0, 0, true, p -> out.addAll(p.links()));
        }
        return out;
    }

    private static PDAnnotationLink link(float x, float y, float w, float h, String uri) {
        PDAnnotationLink link = new PDAnnotationLink();
        link.setRectangle(new PDRectangle(x, y, w, h));
        PDActionURI action = new PDActionURI();
        action.setURI(uri);
        link.setAction(action);
        return link;
    }
}
