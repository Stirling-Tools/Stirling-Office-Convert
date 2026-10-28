package stirling.software.officeconvert.topdf.html;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageXYZDestination;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import stirling.software.officeconvert.topdf.OfficeToPdf;

class HtmlAnchorTest {

    private static PDDocument convert(String target, String content) throws Exception {
        String html = "<style>@page{size:300pt 400pt;margin:30pt}p,h2{margin:0}</style>"
                + "<a href='#" + target + "'>Contents</a>" + content;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        OfficeToPdf.convert(new ByteArrayInputStream(html.getBytes(StandardCharsets.UTF_8)), OfficeToPdf.Format.HTML,
                out, OfficeToPdf.Options.defaults());
        return Loader.loadPDF(out.toByteArray());
    }

    private static List<PDAnnotationLink> links(PDDocument document) throws Exception {
        return document.getPage(0).getAnnotations().stream().filter(PDAnnotationLink.class::isInstance)
                .map(PDAnnotationLink.class::cast).toList();
    }

    private static void targetsSecondPage(String target, String html) throws Exception {
        try (PDDocument document = convert(target, html)) {
            assertEquals(2, document.getNumberOfPages());
            assertEquals(1, links(document).size());
            PDPageXYZDestination destination = (PDPageXYZDestination) links(document).getFirst().getDestination();
            assertEquals(1, document.getPages().indexOf(destination.getPage()));
            PDFTextStripper text = new PDFTextStripper();
            text.setStartPage(2);
            assertTrue(text.getText(document).contains("Target"));
        }
    }

    @Test
    void aBlockIdBelongsToItsHeadingAfterPrecedingInlineContent() throws Exception {
        targetsSecondPage("chapter", "<h2 id=chapter style='page-break-before:always'>Target chapter</h2>");
    }

    @Test
    void hiddenTargetsCannotCreateLinksToUnrelatedVisibleContent() throws Exception {
        for (String hidden : List.of("<span id=hidden hidden>Hidden</span>",
                "<span id=hidden style='display:none'>Hidden</span>",
                "<span id=hidden style='visibility:hidden'>Hidden</span>",
                "<input id=hidden type=hidden>")) {
            try (PDDocument document = convert("hidden", hidden + "<p>Visible</p>")) {
                assertEquals(0, links(document).size(), hidden);
            }
        }
    }

    @Test
    void inlineIdsAndNamedAnchorsRemainWithTheirParagraph() throws Exception {
        targetsSecondPage("inline", "<p style='page-break-before:always'>Before <span id=inline>Target</span></p>");
        targetsSecondPage("named", "<p style='page-break-before:always'><a name=named></a>Target named</p>");
    }

    @Test
    void emptyBlockAndParentAnchorsRemainWithTheNextContent() throws Exception {
        targetsSecondPage("empty", "<div id=empty></div><h2 style='page-break-before:always'>Target after empty</h2>");
        targetsSecondPage("parent", "<div id=parent><h2 style='page-break-before:always'>Target child</h2></div>");
    }

    @Test
    void visibleBlockControlsCanBeInternalTargets() throws Exception {
        try (PDDocument document = convert("control", "<input id=control style='display:block' value=Target>")) {
            assertEquals(1, links(document).size());
        }
    }

    @Test
    void anIdentifiedBlockStyledBreakDoesNotIntroduceAnExtraBlankRun() throws Exception {
        try (PDDocument plain = convert("split", "<p>Before<br style='display:block'></p><p>After</p>");
                PDDocument identified = convert("split", "<p>Before<br id=split style='display:block'></p><p>After</p>")) {
            PDFTextStripper text = new PDFTextStripper();
            assertEquals(text.getText(plain), text.getText(identified));
        }
    }

}
