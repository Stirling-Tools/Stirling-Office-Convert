package stirling.software.officeconvert.topdf.markdown;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.junit.jupiter.api.Test;

class MarkdownPageTest {

    private static String body(String markdown) {
        String html = MarkdownPage.html(markdown);
        return html.substring(html.indexOf("<body"));
    }

    @Test
    void yamlFrontMatterIsSkippedAndNamesTheDocument() {
        String html = MarkdownPage.html("---\ntitle: \"Release notes\"\ntags: [a, b]\n---\n\n# Heading\n\nText\n");
        assertTrue(html.contains("<title>Release notes</title>"), html);
        assertFalse(html.contains("tags:"), html);
        assertTrue(html.contains("<h1 id=\"heading\">Heading</h1>"), html);
    }

    @Test
    void tomlFrontMatterIsSkipped() {
        String html = MarkdownPage.html("+++\ntitle = \"Hugo page\"\ndraft = false\n+++\nBody\n");
        assertTrue(html.contains("<title>Hugo page</title>"), html);
        assertFalse(html.contains("draft"), html);
    }

    @Test
    void ruleAndSetextHeadingAreNotFrontMatter() {
        String html = body("---\nNot front matter\n---\n");
        assertTrue(html.contains("<hr />"), html);
        assertTrue(html.contains("<h2 id=\"not-front-matter\">Not front matter</h2>"), html);
    }

    @Test
    void firstTopHeadingNamesADocumentWithoutFrontMatter() {
        assertTrue(MarkdownPage.html("Intro\n\n# The `real` title\n").contains("<title>The real title</title>"));
    }

    @Test
    void headingsGetGitHubAnchorsAndAuthorIds() {
        String html = body("# Hello, World!\n\n## Hello World\n\n## Hello World\n\n## Setup {#custom-id}\n\n## 安装 指南\n");
        assertTrue(html.contains("<h1 id=\"hello-world\">"), html);
        assertTrue(html.contains("<h2 id=\"hello-world-1\">"), html);
        assertTrue(html.contains("<h2 id=\"hello-world-2\">"), html);
        assertTrue(html.contains("<h2 id=\"custom-id\">Setup</h2>"), html);
        assertTrue(html.contains("<h2 id=\"安装-指南\">"), html);
    }

    @Test
    void extendedAutolinksFollowGfm() {
        String html = body("Visit www.commonmark.org/help, or (https://example.com/a_(b)). Mail foo@bar.baz. "
                + "See ftp://foo.bar.baz and 范围：https://x.example/#q. Not xhttp://no.example or www.a_b.c_d.\n");
        assertTrue(html.contains("href=\"http://www.commonmark.org/help\">www.commonmark.org/help</a>,"), html);
        assertTrue(html.contains("href=\"https://example.com/a_(b)\">https://example.com/a_(b)</a>)."), html);
        assertTrue(html.contains("href=\"mailto:foo@bar.baz\">foo@bar.baz</a>."), html);
        assertFalse(html.contains("href=\"ftp://"), html);
        assertTrue(html.contains("href=\"https://x.example/#q\""), html);
        assertFalse(html.contains("no.example\""), html);
        assertFalse(html.contains("href=\"http://www.a_b"), html);
    }

    @Test
    void autolinksSkipCodeAndExistingLinks() {
        String html = body("`www.example.com` [see www.example.com](https://a.example)\n");
        assertTrue(html.contains("<code>www.example.com</code>"), html);
        assertEquals(1, html.split("<a ", -1).length - 1, html);
    }

    @Test
    void gfmTablesStrikethroughTasksAndFootnotes() {
        String html = body("| a | b |\n|:-|-:|\n| 1 | 2 |\n\n~~gone~~\n\n- [ ] open\n- [x] done\n\nClaim[^1]\n\n[^1]: Note.\n");
        assertTrue(html.contains("<table>"), html);
        assertTrue(html.contains("<td align=\"right\">2</td>"), html);
        assertTrue(html.contains("<del>gone</del>"), html);
        assertTrue(html.contains("<li class=\"task-list-item\"><input type=\"checkbox\" disabled=\"\"> open</li>"), html);
        assertTrue(html.contains("checked=\"\"> done"), html);
        assertTrue(html.contains("class=\"footnotes\""), html);
        assertTrue(html.contains("href=\"#fn-1\""), html);
    }

    @Test
    void disallowedRawHtmlIsFiltered() {
        String html = body("<strong> <title> <style> <em>\n\n<script>alert(1)</script>\n");
        assertTrue(html.contains("<strong> &lt;title> &lt;style> <em>"), html);
        assertTrue(html.contains("&lt;script>alert(1)&lt;/script>"), html);
    }

    @Test
    void unsafeLinksAreDroppedAndDataPicturesKept() {
        String html = body("[x](javascript:alert(1)) ![p](data:image/png;base64,AAAA)\n");
        assertTrue(html.contains("href=\"\""), html);
        assertTrue(html.contains("src=\"data:image/png;base64,AAAA\""), html);
    }

    @Test
    void alertsBecomeTitledPanels() {
        String html = body("> [!WARNING]\n> Mind the **gap**.\n\n> [!NOTE] not alone\n");
        assertTrue(html.contains("<div class=\"alert alert-warning\"><p class=\"alert-title\">Warning</p>"), html);
        assertTrue(html.contains("<p>Mind the <strong>gap</strong>.</p>"), html);
        assertTrue(html.contains("<blockquote>\n<p>[!NOTE] not alone</p>"), html);
    }

    @Test
    void quotedHeadingsKeepAPlainQuote() {
        assertTrue(body("> # Title\n> text\n").contains("<blockquote class=\"headed\">"));
    }

    @Test
    void mathIsTypesetAndPricesAreNot() {
        String html = body("Inline $E = mc^2$ and $\\frac{1}{2}$.\n\n$$\n\\sum_{k=1}^{n} k\n$$\n\n```math\na^2\n```\n\n"
                + "Costs $5 and $10.\n");
        assertTrue(html.contains("<span class=\"math\"><i>E</i> = <i>m</i><i>c</i><sup>2</sup></span>"), html);
        assertTrue(html.contains("1/2"), html);
        assertTrue(html.contains("<div class=\"math display\">∑<sub><i>k</i> = 1</sub><sup><i>n</i></sup>"), html);
        assertTrue(html.contains("<div class=\"math display\"><i>a</i><sup>2</sup></div>"), html);
        assertTrue(html.contains("Costs $5 and $10."), html);
    }

    @Test
    void rightToLeftBlocksAreMarked() {
        String html = body("# כותרת\n\nשלום world\n\n- פריט\n\nEnglish first שלום\n");
        assertTrue(html.contains("<h1 id=\"כותרת\" dir=\"rtl\">"), html);
        assertTrue(html.contains("<p dir=\"rtl\">שלום world</p>"), html);
        assertTrue(html.contains("<ul dir=\"rtl\">"), html);
        assertTrue(html.contains("<p>English first"), html);
    }

    @Test
    void textIsDecodedFromItsByteOrderMarkOrAsWindows1252() {
        byte[] utf16 = "﻿# Ünïcode".getBytes(StandardCharsets.UTF_16LE);
        assertTrue(MarkdownPage.html(utf16).contains(">Ünïcode</h1>"));
        byte[] latin = "# café".getBytes(Charset.forName("windows-1252"));
        assertTrue(MarkdownPage.html(latin).contains(">café</h1>"));
        byte[] bom = "﻿# Title".getBytes(StandardCharsets.UTF_8);
        assertTrue(MarkdownPage.html(bom).contains("<h1 id=\"title\">Title</h1>"));
    }

    @Test
    void keyLookingTextAfterARuleIsNotFrontMatter() {
        String html = body("---\nAuthor: me\n\nSome text\n\nHeading\n---\n\nrest\n");
        assertTrue(html.contains("<hr />"), html);
        assertTrue(html.contains("<p>Some text</p>"), html);
        assertTrue(html.contains(">Heading</h2>"), html);
    }

    @Test
    void anUnclosedDisplayMathBlockEndsAtABlankLine() {
        String html = body("$$\nx + y\n\n# Heading\n\nParagraph\n");
        assertTrue(html.contains("<p>$$<br>x + y</p>"), html);
        assertTrue(html.contains(">Heading</h1>"), html);
        assertTrue(html.contains("<p>Paragraph</p>"), html);
    }

    @Test
    void mathCannotOpenMarkup() {
        String html = body("$\\mathbb{<style>}$ and $\\mathbb{</div></body>}$\n");
        assertFalse(html.contains("<style>"), html);
        assertFalse(html.contains("</div></body>"), html);
        assertTrue(html.contains("&lt;style&gt;"), html);
    }

    @Test
    void shellVariablesAreNotMath() {
        assertFalse(body("Set $HOME/bin:$PATH and $USER_NAME$x today\n").contains("class=\"math\""));
    }

    @Test
    void closingTagsAreFilteredToo() {
        assertTrue(body("<b>x</style></b>\n").contains("&lt;/style>"));
    }

    @Test
    void headingIdsStayUnique() {
        String html = body("# a\n# a\n# a-1\n# Intro {#intro}\n# Intro\n# Fn 1\n\nNote[^1]\n\n[^1]: x\n");
        for (String id : new String[] {"a", "a-1", "a-1-1", "intro", "intro-1", "fn-1-1"}) {
            assertEquals(1, html.split("id=\"" + id + "\"", -1).length - 1, id + " in " + html);
        }
    }

    @Test
    void utf16BigEndianIsRead() {
        byte[] b = "\uFEFF# Grüße".getBytes(StandardCharsets.UTF_16BE);
        assertTrue(MarkdownPage.html(b).contains(">Grüße</h1>"));
    }

    @Test
    void closedDetailsAreOpenedForPrint() {
        String html = body("<details>\n<summary>More</summary>\n\nInside\n</details>\n\n<DETAILS class=\"x\">y</DETAILS>\n");
        assertTrue(html.contains("<details open>"), html);
        assertTrue(html.contains("<details open class=\"x\">"), html);
    }

    @Test
    void namedElementsBecomeLinkTargets() {
        String html = body("<h3 name=\"faq\">Q</h3>\n\n<p name='p1' id=\"keep\">x</p>\n\n<input name=\"field\">\n");
        assertTrue(html.contains("<h3 name=\"faq\" id=\"faq\">"), html);
        assertTrue(html.contains("<p name='p1' id=\"keep\">"), html);
        assertFalse(html.contains("id=\"field\""), html);
    }

    @Test
    void rawPicturesFollowTheMarkdownPicturePolicy() {
        String html = body("<img src=\"https://x.example/a.png\" alt=\"A\"> <img src='pics/b.png'> <img src=/etc/c.png>"
                + " <img alt=\"d\" src=\"data:image/png;base64,AAAA\">\n");
        assertTrue(html.contains("<img src=\"\" alt=\"A\">"), html);
        assertTrue(html.contains("<img src='pics/b.png'>"), html);
        assertTrue(html.contains("<img src=\"\">"), html);
        assertTrue(html.contains("src=\"data:image/png;base64,AAAA\""), html);
    }

    @Test
    void picturesWithoutAltTextKeepNoEmptyAlt() {
        String html = body("![](https://x.example/a.png) ![b](https://x.example/b.png)\n");
        assertTrue(html.contains("<img src=\"\" />"), html);
        assertTrue(html.contains("<img src=\"\" alt=\"b\" />"), html);
    }

    @Test
    void displayEnvironmentsKeepTheirColumns() {
        String html = body("$$\nA = \\begin{pmatrix} a & b \\\\ c & d \\end{pmatrix}\n$$\n\n$$\n\\begin{aligned} x &= 1 \\\\[2pt]"
                + " y &= \\frac{1}{1+\\frac{1}{z}} \\end{aligned}\n$$\n");
        assertTrue(html.contains("<table class=\"tex\"><tr><td><i>A</i> =</td><td><table class=\"tex\"><tr>"
                + "<td class=\"fence\" rowspan=\"2\""), html);
        assertTrue(html.contains("<td style=\"text-align: center\"><i>a</i></td><td style=\"text-align: center\"><i>b</i></td>"),
                html);
        assertTrue(html.contains("<td style=\"text-align: right\"><i>y</i></td><td style=\"text-align: left\"> = 1/(1 + 1/<i>z</i>)"),
                html);
        assertFalse(html.contains("[2pt]"), html);
    }

    @Test
    void inlineEnvironmentsAndRootsStayOnTheLine() {
        String html = body("$\\begin{bmatrix}1 & 2\\\\3 & 4\\end{bmatrix}$ $\\sqrt[3]{x}$ $\\sqrt[n]{y}$ $a<b$\n");
        assertTrue(html.contains("[1 2; 3 4]"), html);
        assertTrue(html.contains("∛<i>x</i>"), html);
        assertTrue(html.contains("<sup><i>n</i></sup>√<i>y</i>") || html.contains("<sup>n</sup>√<i>y</i>"), html);
        assertTrue(html.contains("<i>a</i> &lt; <i>b</i>"), html);
        assertFalse(html.contains("<table"), html);
    }

    @Test
    void hostileInputsStayFast() {
        String[] cases = {">".repeat(50_000) + " x\n", "- ".repeat(20_000) + "y\n", "[".repeat(50_000) + "\n",
            "*a ".repeat(30_000) + "\n", "$a ".repeat(100_000) + "\n", "\\frac{".repeat(5_000) + "\n",
            "$" + "{".repeat(20_000) + "x$\n", "www.a" + ".b".repeat(20_000) + "\n",
            "http://a.b/" + ")".repeat(40_000) + "\n", " " + "www._".repeat(20_000) + "\n",
            "# a" + " ".repeat(80_000) + "b\n", "**".repeat(100_000) + "a" + "**".repeat(100_000) + "\n",
            "![".repeat(100_000) + "\n", "_".repeat(50_000) + "@" + "a-".repeat(50_000) + "\n",
            "$$\n" + "\\begin{pmatrix}".repeat(1_200) + "\n$$\n", "$$\n\\begin{matrix}" + "a&\\\\".repeat(6_000) + "\n$$\n",
            "<p name=x ".repeat(30_000) + "\n", "<img src=http://a ".repeat(30_000) + "\n",
            "<details " + "<details".repeat(30_000) + "\n", "text " + "$".repeat(100_000) + "x\n"};
        for (String input : cases) {
            assertTimeoutPreemptively(Duration.ofSeconds(4), () -> assertTrue(MarkdownPage.html(input).contains("</body>")),
                    input.substring(0, 12));
        }
    }

    @Test
    void aSparseMatrixIsNotPaddedOut() {
        String md = "$$\n\\begin{matrix}" + "&".repeat(5_999) + "\\\\".repeat(5_999) + "x\\end{matrix}\n$$\n";
        String html = assertTimeoutPreemptively(Duration.ofSeconds(4), () -> MarkdownPage.html(md));
        assertTrue(html.length() < 1_000_000, String.valueOf(html.length()));
        assertTrue(html.contains("\\begin{matrix}"), html.substring(0, 200));
    }

    @Test
    void aMultiLineHeadingKeepsTheSpaceInItsAnchor() {
        assertTrue(body("Foo\nbar\n===\n").contains("<h1 id=\"foo-bar\">"));
    }

    @Test
    void runsOfDollarsStayText() {
        assertTrue(body("Costs $$$ and $x$\n").contains("$$$"));
    }
}
