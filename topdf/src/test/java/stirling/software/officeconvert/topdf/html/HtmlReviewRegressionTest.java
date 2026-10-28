package stirling.software.officeconvert.topdf.html;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipInputStream;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.xml.parsers.DocumentBuilderFactory;

import stirling.software.officeconvert.model.Inline;
import stirling.software.officeconvert.model.Paragraph;
import stirling.software.officeconvert.model.Section;
import stirling.software.officeconvert.model.RunStyle;
import stirling.software.officeconvert.model.StyleSheet;
import stirling.software.officeconvert.topdf.OfficeToPdf;

class HtmlReviewRegressionTest {

    @TempDir
    Path dir;

    private List<Paragraph> paragraphs(String html) throws IOException {
        Path source = Files.writeString(dir.resolve("flow.html"), html);
        HtmlTablesTest.Blocks sink = new HtmlTablesTest.Blocks();
        HtmlDocument.write(source, sink);
        return sink.blocks.stream().filter(Paragraph.class::isInstance).map(Paragraph.class::cast).toList();
    }

    private static String text(Paragraph paragraph) {
        StringBuilder text = new StringBuilder();
        for (Inline inline : paragraph.inlines) {
            if (inline instanceof Inline.Text run) {
                text.append(run.text());
            }
        }
        return text.toString();
    }

    @Test
    void hyphenatedHtmlWritesSettingsInSchemaOrder() throws Exception {
        Path source = Files.writeString(dir.resolve("hyphenation.html"),
                "<html lang=en><style>p{hyphens:auto}</style><p>International organisations</p></html>");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        HtmlDocument.write(source, bytes);
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (entry.getName().equals("word/settings.xml")) {
                    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                    factory.setNamespaceAware(true);
                    var settings = factory.newDocumentBuilder().parse(new ByteArrayInputStream(zip.readAllBytes()));
                    List<String> children = new java.util.ArrayList<>();
                    for (var node = settings.getDocumentElement().getFirstChild(); node != null;
                            node = node.getNextSibling()) {
                        if (node.getNodeType() == org.w3c.dom.Node.ELEMENT_NODE) {
                            children.add(node.getLocalName());
                        }
                    }
                    assertTrue(children.contains("autoHyphenation"), children.toString());
                    assertTrue(children.indexOf("defaultTabStop") >= 0
                            && children.indexOf("defaultTabStop") < children.indexOf("autoHyphenation"), children.toString());
                    return;
                }
            }
        }
        throw new AssertionError("settings part missing");
    }

    @Test
    void paddedBlockAnchorsDoNotCreateEmptyInlineParagraphs() throws IOException {
        List<Paragraph> paragraphs = paragraphs("<style>p,div{margin:0}</style>"
                + "<a href='#end' style='display:block;padding:16px'><div>First</div><div>Second</div></a>"
                + "<p id=end>After</p>");
        assertEquals(List.of("First", "Second", "After"), paragraphs.stream().map(HtmlReviewRegressionTest::text).toList());
    }

    @Test
    void paddedInlineWrappersAroundBlocksDoNotCreateEmptyParagraphs() throws IOException {
        List<Paragraph> paragraphs = paragraphs("<style>p,div{margin:0}</style>"
                + "<span style='padding:0 16px'><div>First</div><div>Second</div></span><p>After</p>");
        assertEquals(List.of("First", "Second", "After"), paragraphs.stream().map(HtmlReviewRegressionTest::text).toList());
    }

    @Test
    void inlinePaddingStaysWithActualInlineContent() throws IOException {
        List<Paragraph> paragraphs = paragraphs("<p>Before<span style='padding:0 16px'>Inside</span>After</p>");
        assertEquals(1, paragraphs.size());
        assertEquals("Before\u00a0Inside\u00a0After", text(paragraphs.getFirst()));
    }

    @Test
    void inlineContentOnEitherSideOfABlockKeepsOnlyItsOwnEdgePadding() throws IOException {
        List<Paragraph> paragraphs = paragraphs("<style>div,p{margin:0}</style>"
                + "<span style='padding:0 16px'>Lead<div>Block</div>Tail</span><p>After</p>");
        assertEquals(List.of("\u00a0Lead", "Block", "Tail\u00a0", "After"),
                paragraphs.stream().map(HtmlReviewRegressionTest::text).toList());
    }

    @Test
    void emptyInlinePaddingKeepsItsWidthWithoutBlockChildren() throws IOException {
        List<Paragraph> paragraphs = paragraphs("<span style='padding:0 16px'></span><p>After</p>");
        assertEquals(List.of("\u00a0\u00a0", "After"), paragraphs.stream().map(HtmlReviewRegressionTest::text).toList());
    }

    @Test
    void stringsUpdateCountersWithoutAnyPseudoElementRules() {
        Document doc = Jsoup.parse("<style>body{counter-reset:chapter}h2{counter-increment:chapter;"
                + "string-set:title counter(chapter) '. ' content()}</style><h2>First</h2><h2>Next</h2>");
        Generated.Result result = Generated.apply(doc, new Cascade(doc, 500), Map.of());
        assertEquals(List.of("1. First", "2. Next"), result.strings().values().stream().map(v -> v[2]).toList());
    }

    @Test
    void stringCounterNamesKeepTheirCase() {
        Document doc = Jsoup.parse("<style>body{counter-reset:chapNum 7 chapnum 2}"
                + "p::before{content:counter(chapnum)}h2{counter-increment:chapNum;"
                + "string-set:title counter(chapNum) '. ' content(FIRST-LETTER)}</style><h2>First</h2>");
        Generated.Result result = Generated.apply(doc, new Cascade(doc, 500), Map.of());
        assertEquals("8. F", result.strings().values().iterator().next()[2]);
    }

    @Test
    void attributeOnlyRunningStringsDoNotTraverseTheElementsText() {
        Document doc = Jsoup.parse("<style>h2{string-set:title attr(data-value)}</style>");
        int[] reads = {0};
        Element heading = new Element("h2") {
            @Override
            public String text() {
                reads[0]++;
                return super.text();
            }
        };
        heading.attr("data-value", "Running title");
        heading.appendText("Body text");
        doc.body().appendChild(heading);
        Generated.Result result = Generated.apply(doc, new Cascade(doc, 500), Map.of());
        assertEquals("Running title", result.strings().values().iterator().next()[2]);
        assertEquals(0, reads[0]);
    }

    private static HtmlContext context(Document doc) {
        return new HtmlContext(new Cascade(doc, 500), new StyleSheet(new RunStyle("Times New Roman", 12, false, false, false, false, 0, -1, 0, false)), new HtmlTablesTest.Blocks(),
                new java.util.ArrayList<>(), new Section(), new Linked(null));
    }

    @Test
    void targetIdsShareTheWrittenBookmarkOfTheirGeneratedMark() {
        Document doc = Jsoup.parse("<h2 id=chapter>Chapter</h2>");
        var element = doc.getElementById("chapter");
        HtmlContext context = context(doc);
        context.generated(new Generated.Result(Set.of("chapter"), Map.of(element, "mark"),
                Map.of("mark#chapter", new String[] {"mark", "chapter", "Chapter"})));
        assertEquals("mark", context.bookmarkFor(element));
        context.alias("mark", "pending");
        context.alias("pending", "written");
        assertEquals("written", context.targetBookmarks().get("chapter"));
        assertEquals(Set.of("written"), context.runningStrings().keySet());
    }

    @Test
    void runningStringsAndTargetPageCountersShareTheHeadingDestination() throws IOException {
        for (boolean naked : new boolean[] {false, true}) {
            Path source = Files.writeString(dir.resolve("targets" + naked + ".html"),
                    "<style>@page{size:300pt 400pt;margin:30pt;@top-center{content:string(chapter)}}"
                    + "h2{page-break-before:always;string-set:chapter content()}"
                    + "a::after{content:' on page ' target-counter(attr(href),page)}</style>"
                    + (naked ? "<a href='#c'>Contents</a>" : "<p><a href='#c'>Contents</a></p>")
                    + "<h2 id=c>Chapter title</h2><p>Body</p>");
            Path pdf = dir.resolve("targets" + naked + ".pdf");
            OfficeToPdf.convert(source, pdf, OfficeToPdf.Options.defaults());
            try (var document = Loader.loadPDF(pdf.toFile())) {
                assertEquals(2, document.getNumberOfPages());
                PDFTextStripper text = new PDFTextStripper();
                text.setStartPage(1);
                text.setEndPage(1);
                String contents = text.getText(document);
                assertTrue(contents.contains("Contents on page 2"), contents);
                text.setStartPage(2);
                text.setEndPage(2);
                assertEquals(2, text.getText(document).split("Chapter title", -1).length - 1);
            }
        }
    }

    @Test
    void emptyLeftOnlyPaddingIsKeptWithOrWithoutPrecedingText() throws IOException {
        for (String prefix : List.of("", "Before")) {
            List<Paragraph> paragraphs = paragraphs(prefix + "<span style='padding-left:16px'></span><p>After</p>");
            assertEquals(List.of(prefix + "\u00a0", "After"),
                    paragraphs.stream().map(HtmlReviewRegressionTest::text).toList());
        }
    }

    @Test
    void emptyPaddedFlexItemsRetainTheGapsOnBothSides() throws IOException {
        List<Paragraph> paragraphs = paragraphs("<div style='display:flex;gap:12px'><span>A</span>"
                + "<span style='padding-left:16px'></span><span>B</span></div>");
        assertEquals(List.of("A\u2002\u00a0\u2002B"),
                paragraphs.stream().map(HtmlReviewRegressionTest::text).toList());
    }

    @Test
    void emptyPriorFlexItemsDoNotInsertAGapBetweenTheNextItemsOwnEdges() throws IOException {
        List<Paragraph> paragraphs = paragraphs("<div style='display:flex;gap:12px'><span></span>"
                + "<span style='padding:0 16px'></span><span>B</span></div>");
        assertEquals(List.of("\u00a0\u00a0\u2002B"),
                paragraphs.stream().map(HtmlReviewRegressionTest::text).toList());
    }

    @Test
    void nestedEmptyInlineEdgesKeepTheirOuterToInnerOrderInAFlexItem() throws IOException {
        List<Paragraph> paragraphs = paragraphs("<div style='display:flex;gap:12px'><span>A</span>"
                + "<span style='padding-left:16px'><span style='padding-left:8px'></span></span>"
                + "<span>B</span></div>");
        Paragraph row = paragraphs.getFirst();
        assertEquals("A\u2002\u00a0\u00a0\u2002B", text(row));
        List<Float> spacing = row.inlines.stream().filter(Inline.Text.class::isInstance).map(Inline.Text.class::cast)
                .filter(run -> run.text().equals("\u00a0")).map(run -> run.style().spacing()).toList();
        assertEquals(List.of(9f, 3f), spacing);
    }

    @Test
    void markedBlockStyledBreakDoesNotAddAParagraphBeforeTheClosingParagraphTag() throws IOException {
        String css = "<style>.marked{string-set:marker attr(data-name)}</style>";
        List<String> plain = paragraphs(css + "<p>Before<br style='display:block'></p><p>After</p>")
                .stream().map(HtmlReviewRegressionTest::text).toList();
        List<String> marked = paragraphs(css + "<p>Before<br style='display:block;string-set:marker attr(data-name)'"
                + " class=marked id=marker data-name=Marker></p><p>After</p>")
                .stream().map(HtmlReviewRegressionTest::text).toList();
        assertEquals(List.of("Before", "After"), plain);
        assertEquals(plain, marked);
    }

    @Test
    void targetReferencedBlockStyledBreakKeepsTheUnmarkedBodyParagraphs() throws IOException {
        String prefix = "<style>a::after{content:target-counter(attr(href),page)}</style>"
                + "<p><a href='#marker'>Reference</a></p><p>Before";
        List<String> plain = paragraphs(prefix + "<br style='display:block'></p><p>After</p>")
                .stream().map(HtmlReviewRegressionTest::text).toList();
        List<String> marked = paragraphs(prefix + "<br id=marker style='display:block'></p><p>After</p>")
                .stream().map(HtmlReviewRegressionTest::text).toList();
        assertEquals(3, plain.size());
        assertEquals(plain, marked);
    }

    private record Destinations(HtmlContext context, List<Paragraph> paragraphs) {}

    private static Destinations destinations(String html, Set<String> targets) throws IOException {
        Document doc = Jsoup.parse(html);
        HtmlContext context = context(doc);
        context.generated(new Generated.Result(targets, Map.of(), Map.of()));
        List<Paragraph> paragraphs = new java.util.ArrayList<>();
        new Flow(context, b -> paragraphs.add((Paragraph) b), false).root(doc.body(), new Look(),
                new Flow.Frame(0, 0, 500, -1));
        return new Destinations(context, paragraphs);
    }

    private static String destination(Destinations result, String id) {
        String bookmark = result.context().targetBookmarks().get(id);
        return result.paragraphs().stream().filter(p -> bookmark.equals(p.bookmark))
                .map(HtmlReviewRegressionTest::text).findFirst().orElse(null);
    }

    @Test
    void blockTargetsFollowTheBoundaryAndPreserveThePrecedingInlineTarget() throws IOException {
        Destinations result = destinations("<span id=before>Before</span><h2 id=after>Heading</h2>",
                Set.of("before", "after"));
        assertEquals("Before", destination(result, "before"));
        assertEquals("Heading", destination(result, "after"));
    }

    @Test
    void hiddenAndSkippedTargetsCannotClaimVisibleContent() throws IOException {
        for (String hidden : List.of("<span id=hidden hidden>Hidden</span>",
                "<span id=hidden style='display:none'>Hidden</span>",
                "<span id=hidden style='visibility:hidden'>Hidden</span>",
                "<script id=hidden>Hidden</script>", "<input id=hidden type=hidden>")) {
            Destinations result = destinations(hidden + "<p id=shown>Shown</p>", Set.of("hidden", "shown"));
            assertEquals(null, destination(result, "hidden"));
            assertEquals("Shown", destination(result, "shown"));
        }
    }

    @Test
    void inlineAndParentTargetsAliasTheParagraphThatContainsTheirContent() throws IOException {
        Destinations inline = destinations("<p>Before <span id=inline>Inside</span> After</p>", Set.of("inline"));
        assertEquals("Before Inside After", destination(inline, "inline"));
        Destinations parent = destinations("<div id=parent><h2 id=child>Heading</h2><p>Body</p></div>",
                Set.of("parent", "child"));
        assertEquals("Heading", destination(parent, "parent"));
        assertEquals("Heading", destination(parent, "child"));
    }

    @Test
    void blockControlTargetsDoNotAttachToPrecedingText() throws IOException {
        Destinations result = destinations("Before<input id=control style='display:block' value=Value><p>After</p>",
                Set.of("control"));
        String bookmark = result.context().targetBookmarks().get("control");
        assertTrue(result.paragraphs().stream().filter(p -> bookmark.equals(p.bookmark))
                .anyMatch(p -> p.inlines.stream().anyMatch(Inline.FormControl.class::isInstance)));
    }

    @Test
    void prelaidPositionedTargetsDoNotStealTheNextFlowDestination() throws IOException {
        Destinations result = destinations("Before<div id=placed style='position:absolute;top:0;left:0'>Placed</div>"
                + "<h2 id=heading>Heading</h2>", Set.of("placed", "heading"));
        assertEquals("Heading", destination(result, "heading"));
    }

    @Test
    void cyclicAndExcessivelyDeepAliasesTerminateWithoutInventingATarget() {
        HtmlContext context = context(Jsoup.parse("<p>Body</p>"));
        context.alias("a", "b");
        context.alias("b", "a");
        assertTimeoutPreemptively(Duration.ofSeconds(1), () -> assertEquals("a", context.written("a")));
        for (int i = 0; i < 100; i++) {
            context.alias("long" + i, "long" + (i + 1));
        }
        assertTimeoutPreemptively(Duration.ofSeconds(1), () -> assertEquals("long0", context.written("long0")));
        assertEquals("unknown", context.written("unknown"));
    }
}
