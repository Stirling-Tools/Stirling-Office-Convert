package stirling.software.officeconvert.topdf.uof;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.imageio.ImageIO;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFFootnote;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.topdf.OfficeToPdf;

class UofPackageTest {

    private static final String HEAD = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><uof:UOF"
            + " xmlns:uof=\"http://schemas.uof.org/cn/2003/uof\" xmlns:字=\"http://schemas.uof.org/cn/2003/uof-wordproc\""
            + " xmlns:表=\"http://schemas.uof.org/cn/2003/uof-spreadsheet\""
            + " xmlns:演=\"http://schemas.uof.org/cn/2003/uof-slideshow\" xmlns:图=\"http://schemas.uof.org/cn/2003/graph\""
            + " uof:language=\"cn\" uof:version=\"1.0\" uof:mimetype=\"";

    @TempDir
    Path dir;

    private Path convert(String name, String xml) throws IOException {
        Path in = Files.writeString(dir.resolve(name), xml, StandardCharsets.UTF_8);
        Path out = dir.resolve(name + ".pdf");
        OfficeToPdf.convert(in, out);
        return out;
    }

    private static String text(Path pdf) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            return new PDFTextStripper().getText(doc);
        }
    }

    private static String run(String text, String props) {
        return "<字:句><字:句属性>" + props + "</字:句属性><字:文本串>" + text + "</字:文本串></字:句>";
    }

    private static String styles() {
        return "<uof:式样集><uof:字体集><uof:字体声明 uof:标识符=\"F1\" uof:名称=\"'Courier New'\"/></uof:字体集>"
                + "<uof:段落式样 字:标识符=\"Heading\" 字:名称=\"Heading\"><字:句属性><字:字体 字:字号=\"20\"/>"
                + "<字:粗体 字:值=\"true\"/></字:句属性></uof:段落式样></uof:式样集>";
    }

    @Test
    void textDocumentsKeepParagraphsTablesFootnotesAndPageSetup() throws IOException {
        String body = "<uof:文字处理><字:公用处理规则><字:文档设置><字:度量单位>mm</字:度量单位></字:文档设置></字:公用处理规则>"
                + "<字:主体><字:分节><字:节属性><字:纸张 uof:宽度=\"297\" uof:高度=\"210\"/>"
                + "<字:纸张方向>landscape</字:纸张方向><字:页边距 uof:左=\"20\" uof:上=\"20\" uof:右=\"20\" uof:下=\"20\"/>"
                + "</字:节属性></字:分节>"
                + "<字:段落><字:段落属性 字:式样引用=\"Heading\"/>" + run("Annual report", "") + "</字:段落>"
                + "<字:段落><字:段落属性><字:对齐 字:水平对齐=\"center\"/></字:段落属性>"
                + run("Body text", "<字:字体 字:西文字体引用=\"F1\" 字:字号=\"12\"/>")
                + "<字:句><字:文本串>with a note</字:文本串><字:脚注><字:段落>" + run("Footnote words", "")
                + "</字:段落></字:脚注></字:句></字:段落>"
                + "<字:文字表><字:行><字:单元格><字:单元格属性><字:跨列 字:值=\"2\"/></字:单元格属性><字:段落>"
                + run("Merged heading", "") + "</字:段落></字:单元格></字:行><字:行><字:单元格><字:段落>"
                + run("Cell one", "") + "</字:段落></字:单元格><字:单元格><字:段落>" + run("Cell two", "")
                + "</字:段落></字:单元格></字:行></字:文字表></字:主体></uof:文字处理>";
        Path pdf = convert("report.uot", HEAD + "vnd.uof.text\">" + styles() + body + "</uof:UOF>");
        String text = text(pdf);
        for (String s : new String[] {"Annual report", "Body text", "with a note", "Footnote words", "Merged heading",
                "Cell one", "Cell two"}) {
            assertTrue(text.contains(s), s + " in " + text);
        }
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            PDRectangle box = doc.getPage(0).getMediaBox();
            assertEquals(297 * 72 / 25.4, box.getWidth(), 1);
            assertEquals(210 * 72 / 25.4, box.getHeight(), 1);
        }
    }

    @Test
    void spreadsheetsKeepCellsMergesAndNumbers() throws IOException {
        String xml = HEAD + "vnd.uof.spreadsheet\"><uof:式样集><uof:单元格式样 表:标识符=\"ce1\"><表:字体格式><字:粗体"
                + " 字:值=\"true\"/></表:字体格式><表:数字格式 表:格式码=\"0.00\"/></uof:单元格式样></uof:式样集>"
                + "<uof:电子表格><表:主体><表:工作表 表:名称=\"Budget\"><表:工作表内容>"
                + "<表:行><表:单元格 表:合并列数=\"1\"><表:数据 表:数据类型=\"text\"><字:句><字:文本串>Quarterly budget"
                + "</字:文本串></字:句></表:数据></表:单元格></表:行>"
                + "<表:行 表:行号=\"3\"><表:单元格><表:数据 表:数据类型=\"text\"><字:句><字:文本串>Rent</字:文本串></字:句>"
                + "</表:数据></表:单元格><表:单元格 表:式样引用=\"ce1\"><表:数据 表:数据类型=\"number\" 表:数据数值=\"1234.5\">"
                + "<字:句><字:文本串>1234.50</字:文本串></字:句></表:数据></表:单元格></表:行>"
                + "</表:工作表内容></表:工作表></表:主体></uof:电子表格></uof:UOF>";
        String text = text(convert("budget.uos", xml));
        assertTrue(text.contains("Quarterly budget"), text);
        assertTrue(text.contains("Rent"), text);
        assertTrue(text.contains("1234.50"), text);
    }

    @Test
    void presentationsPlaceTextAndPicturesOnTheSlide() throws IOException {
        String picture = Base64.getEncoder().encodeToString(png());
        String xml = HEAD + "vnd.uof.presentation\"><uof:对象集><uof:其他对象 uof:标识符=\"img1\" uof:公共类型=\"png\""
                + " uof:内嵌=\"true\"><uof:数据>" + picture + "</uof:数据></uof:其他对象>"
                + "<图:图形 图:标识符=\"g1\" 图:层次=\"1\"><图:预定义图形><图:名称>文本框</图:名称><图:属性><图:填充>"
                + "<图:颜色>#ffff00</图:颜色></图:填充></图:属性></图:预定义图形><图:文本内容><字:段落>"
                + run("Slide title", "<字:字体 字:字号=\"32\"/>") + "</字:段落></图:文本内容></图:图形>"
                + "<图:图形 图:标识符=\"g2\" 图:层次=\"2\" 图:其他对象=\"img1\"/></uof:对象集><uof:演示文稿>"
                + "<演:公用处理规则><演:度量单位>cm</演:度量单位><演:页面设置集><演:页面设置 演:标识符=\"PM\"><演:纸张"
                + " uof:宽度=\"25.4\" uof:高度=\"19.05\"/></演:页面设置></演:页面设置集></演:公用处理规则><演:主体>"
                + "<演:母版集><演:母版 演:标识符=\"M\" 演:类型=\"slide\" 演:页面设置引用=\"PM\"/></演:母版集><演:幻灯片集>"
                + "<演:幻灯片 演:母版引用=\"M\"><uof:锚点 uof:x坐标=\"1\" uof:y坐标=\"1\" uof:宽度=\"20\" uof:高度=\"3\""
                + " uof:图形引用=\"g1\"/><uof:锚点 uof:x坐标=\"10\" uof:y坐标=\"10\" uof:宽度=\"5\" uof:高度=\"5\""
                + " uof:图形引用=\"g2\"/></演:幻灯片></演:幻灯片集></演:主体></uof:演示文稿></uof:UOF>";
        Path pdf = convert("deck.uop", xml);
        assertTrue(text(pdf).contains("Slide title"));
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            assertEquals(1, doc.getNumberOfPages());
            assertEquals(25.4 * 72 / 2.54, doc.getPage(0).getMediaBox().getWidth(), 1);
            BufferedImage page = new PDFRenderer(doc).renderImageWithDPI(0, 72);
            Color pic = new Color(page.getRGB(Math.round(12.5f * 72 / 2.54f), Math.round(12.5f * 72 / 2.54f)));
            assertTrue(pic.getRed() > 180 && pic.getGreen() < 80, "picture: " + pic);
            Color fill = new Color(page.getRGB(Math.round(19 * 72 / 2.54f), Math.round(1.5f * 72 / 2.54f)));
            assertTrue(fill.getRed() > 200 && fill.getGreen() > 200 && fill.getBlue() < 80, "fill: " + fill);
        }
    }

    @Test
    void groupsDrawTheirMembersAndNotThemselves() throws IOException {
        String member = "<图:图形 图:标识符=\"m2\"><图:文本内容><字:段落>" + run("Right member", "")
                + "</字:段落></图:文本内容></图:图形><图:组合位置 图:x坐标=\"NaN\" 图:y坐标=\"NaN\"/>";
        String sized = "<图:图形 图:标识符=\"m1\"><图:预定义图形><图:名称>rect</图:名称><图:属性><图:填充><图:颜色>#ff0000"
                + "</图:颜色></图:填充><图:宽度>2</图:宽度><图:高度>2</图:高度></图:属性></图:预定义图形></图:图形>"
                + "<图:组合位置 图:x坐标=\"1\" 图:y坐标=\"1\"/>";
        String nested = "<图:图形 图:标识符=\"inner\" 图:组合列表=\"m2\"/><图:图形 图:标识符=\"cycle\" 图:组合列表=\"cycle\"/>";
        String group = "<图:图形 图:标识符=\"grp\" 图:组合列表=\"m1,inner,cycle\"><图:预定义图形><图:名称>group</图:名称>"
                + "<图:属性><图:填充><图:颜色>#0000ff</图:颜色></图:填充></图:属性></图:预定义图形></图:图形>";
        String xml = HEAD + "vnd.uof.presentation\"><uof:对象集>" + sized + member + nested + group + "</uof:对象集>"
                + "<uof:演示文稿><演:主体><演:幻灯片集><演:幻灯片><uof:锚点 uof:x坐标=\"2\" uof:y坐标=\"2\""
                + " uof:宽度=\"20\" uof:高度=\"10\" uof:图形引用=\"grp\"/></演:幻灯片></演:幻灯片集></演:主体>"
                + "</uof:演示文稿></uof:UOF>";
        Path pdf = convert("group.uop", xml);
        assertTrue(text(pdf).contains("Right member"));
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            BufferedImage page = new PDFRenderer(doc).renderImageWithDPI(0, 72);
            Color member1 = new Color(page.getRGB(Math.round(4 * 72 / 2.54f), Math.round(4 * 72 / 2.54f)));
            assertTrue(member1.getRed() > 200 && member1.getBlue() < 80, "member: " + member1);
            Color inside = new Color(page.getRGB(Math.round(15 * 72 / 2.54f), Math.round(10 * 72 / 2.54f)));
            assertTrue(inside.getBlue() < 200 || inside.getRed() > 200, "group fill drawn: " + inside);
        }
    }

    private byte[] rewrite(String name, String xml, UofPackage.Kind kind) throws IOException {
        Path in = Files.writeString(dir.resolve(name), xml, StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        UofPackage.write(in, kind, out);
        return out.toByteArray();
    }

    @Test
    void aTextBoxThatAnchorsItselfIsExpandedOnce() throws IOException {
        String box = "<图:图形 图:标识符=\"loop\"><图:文本内容><字:段落>" + run("Inside the box", "")
                + "<字:句><字:锚点><字:图形 字:图形引用=\"loop\"/></字:锚点><字:锚点><字:图形 字:图形引用=\"loop\"/>"
                + "</字:锚点></字:句></字:段落></图:文本内容></图:图形>";
        String xml = HEAD + "vnd.uof.text\"><uof:对象集>" + box + "</uof:对象集><uof:文字处理><字:主体><字:段落>"
                + "<字:句><字:锚点><字:图形 字:图形引用=\"loop\"/></字:锚点></字:句></字:段落></字:主体></uof:文字处理>"
                + "</uof:UOF>";
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(rewrite("loop.uot", xml,
                UofPackage.Kind.TEXT)))) {
            String all = new XWPFWordExtractor(doc).getText();
            assertEquals(1, all.split("Inside the box", -1).length - 1, all);
        }
    }

    @Test
    void aFootnoteInsideAFootnoteKeepsTheNotesWellFormed() throws IOException {
        String inner = "<字:脚注><字:段落>" + run("Inner note", "") + "</字:段落></字:脚注>";
        String xml = HEAD + "vnd.uof.text\"><uof:文字处理><字:主体><字:段落><字:句><字:文本串>Body</字:文本串><字:脚注>"
                + "<字:段落><字:句><字:文本串>Outer note</字:文本串>" + inner + "</字:句></字:段落></字:脚注></字:句>"
                + "</字:段落></字:主体></uof:文字处理></uof:UOF>";
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(rewrite("notes.uot", xml,
                UofPackage.Kind.TEXT)))) {
            String notes = doc.getFootnotes().stream().map(XWPFFootnote::getParagraphs)
                    .flatMap(java.util.List::stream).map(XWPFParagraph::getText).reduce("", String::concat);
            assertTrue(notes.contains("Outer note") && notes.contains("Inner note"), notes);
        }
        String part = entry(rewrite("notes2.uot", xml, UofPackage.Kind.TEXT), "word/footnotes.xml");
        assertFalse(part.contains("footnoteReference") || part.contains("endnoteReference"), part);
        assertTrue(part.contains("Outer note") && part.contains("Inner note"), part);
    }

    @Test
    void picturesInNotesGetTheirOwnRelationships() throws IOException {
        String picture = Base64.getMimeEncoder().encodeToString(png());
        String xml = HEAD + "vnd.uof.text\"><uof:对象集><uof:其他对象 uof:标识符=\"img1\" uof:公共类型=\"png\">"
                + "<uof:数据>" + picture + "</uof:数据></uof:其他对象><图:图形 图:标识符=\"g1\" 图:其他对象=\"img1\"/>"
                + "</uof:对象集><uof:文字处理><字:主体><字:段落><字:句><字:文本串>Body</字:文本串><字:脚注><字:段落><字:句>"
                + "<字:锚点><字:图形 字:图形引用=\"g1\"/></字:锚点></字:句></字:段落></字:脚注></字:句></字:段落></字:主体>"
                + "</uof:文字处理></uof:UOF>";
        byte[] docx = rewrite("pic.uot", xml, UofPackage.Kind.TEXT);
        assertTrue(entry(docx, "word/footnotes.xml").contains("r:embed=\"rImg1\""));
        assertTrue(entry(docx, "word/_rels/footnotes.xml.rels").contains("Id=\"rImg1\""));
        assertFalse(entry(docx, "word/_rels/document.xml.rels").contains("rImg1"));
    }

    @Test
    void cellsBesideAVerticalMergeAreKept() throws IOException {
        String xml = HEAD + "vnd.uof.text\"><uof:文字处理><字:主体><字:文字表><字:行><字:单元格><字:单元格属性><字:跨行"
                + " 字:值=\"2\"/></字:单元格属性><字:段落>" + run("Tall", "") + "</字:段落></字:单元格></字:行><字:行>"
                + "<字:单元格><字:段落>" + run("Beside", "") + "</字:段落></字:单元格></字:行></字:文字表></字:主体>"
                + "</uof:文字处理></uof:UOF>";
        String doc = entry(rewrite("merge.uot", xml, UofPackage.Kind.TEXT), "word/document.xml");
        assertTrue(doc.contains("Tall") && doc.contains("Beside"), doc);
    }

    @Test
    void tablesWithoutWidthsShareTheTextWidthOfTheirSectionOrCell() throws IOException {
        String inner = "<字:文字表><字:行><字:单元格><字:段落>" + run("A", "") + "</字:段落></字:单元格><字:单元格><字:段落>"
                + run("B", "") + "</字:段落></字:单元格></字:行></字:文字表>";
        String xml = HEAD + "vnd.uof.text\"><uof:文字处理><字:主体><字:文字表><字:行><字:单元格>" + inner
                + "</字:单元格><字:单元格><字:段落>" + run("C", "") + "</字:段落></字:单元格></字:行></字:文字表>"
                + "<字:分节><字:节属性><字:分栏 字:栏数=\"2\"/></字:节属性></字:分节><字:文字表><字:行><字:单元格>"
                + "<字:段落>" + run("D", "") + "</字:段落></字:单元格></字:行></字:文字表></字:主体></uof:文字处理>"
                + "</uof:UOF>";
        String doc = entry(rewrite("widths.uot", xml, UofPackage.Kind.TEXT), "word/document.xml");
        assertTrue(doc.contains("<w:gridCol w:w=\"1970\"/><w:gridCol w:w=\"1970\"/></w:tblGrid>"), doc);
        assertTrue(doc.contains("<w:gridCol w:w=\"877\"/><w:gridCol w:w=\"877\"/></w:tblGrid>"), doc);
        assertTrue(doc.contains("<w:gridCol w:w=\"8306\"/></w:tblGrid>"), doc);
    }

    @Test
    void stylesWhoseNamesSanitiseAlikeKeepApartIds() throws IOException {
        String xml = HEAD + "vnd.uof.text\"><uof:式样集><uof:段落式样 字:标识符=\"Heading.A\"/><uof:段落式样"
                + " 字:标识符=\"Heading_A\" 字:基式样引用=\"Heading.A\"/></uof:式样集><uof:文字处理><字:主体><字:段落>"
                + "<字:段落属性 字:式样引用=\"Heading_A\"/>" + run("Styled", "") + "</字:段落></字:主体></uof:文字处理>"
                + "</uof:UOF>";
        byte[] docx = rewrite("styles.uot", xml, UofPackage.Kind.TEXT);
        String styles = entry(docx, "word/styles.xml");
        assertTrue(styles.contains("w:styleId=\"Heading_A\"") && styles.contains("w:styleId=\"Heading_A_2\""), styles);
        assertTrue(styles.contains("<w:basedOn w:val=\"Heading_A\"/>"), styles);
        assertTrue(entry(docx, "word/document.xml").contains("<w:pStyle w:val=\"Heading_A_2\"/>"));
    }

    @Test
    void manyStylesThatSanitiseAlikeStayQuick() throws IOException {
        StringBuilder set = new StringBuilder();
        for (int n = 0; n < 1 << 15; n++) {
            StringBuilder id = new StringBuilder("S");
            for (int bit = 14; bit >= 0; bit--) {
                id.append((n >> bit & 1) == 0 ? '.' : '_');
            }
            set.append("<uof:段落式样 字:标识符=\"").append(id).append("\"/>");
        }
        String xml = HEAD + "vnd.uof.text\"><uof:式样集>" + set + "</uof:式样集><uof:文字处理><字:主体><字:段落>"
                + run("Body", "") + "</字:段落></字:主体></uof:文字处理></uof:UOF>";
        String styles = assertTimeoutPreemptively(Duration.ofSeconds(3),
                () -> entry(rewrite("alike.uot", xml, UofPackage.Kind.TEXT), "word/styles.xml"));
        assertTrue(styles.contains("w:styleId=\"S_______________\"") && styles.contains("_32768\""), "distinct ids");
    }

    private static String entry(byte[] zip, String name) throws IOException {
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry e = in.getNextEntry(); e != null; e = in.getNextEntry()) {
                if (e.getName().equals(name)) {
                    return new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        throw new IOException("No " + name);
    }

    @Test
    void cellsAfterAMergeStartPastIt() throws IOException {
        String xml = HEAD + "vnd.uof.spreadsheet\"><uof:电子表格><表:主体><表:工作表 表:名称=\"S\"><表:工作表内容><表:行>"
                + "<表:单元格 表:合并列数=\"1\"><表:数据 表:数据类型=\"text\"><字:句><字:文本串>Wide</字:文本串></字:句>"
                + "</表:数据></表:单元格><表:单元格><表:数据 表:数据类型=\"text\"><字:句><字:文本串>Next</字:文本串>"
                + "</字:句></表:数据></表:单元格></表:行><表:行 表:跨度=\"1000\"/><表:行><表:单元格><表:数据"
                + " 表:数据类型=\"text\"><字:句><字:文本串>Late</字:文本串></字:句></表:数据></表:单元格></表:行>"
                + "</表:工作表内容></表:工作表></表:主体></uof:电子表格></uof:UOF>";
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(rewrite("merge.uos", xml,
                UofPackage.Kind.SPREADSHEET)))) {
            assertEquals("Next", wb.getSheetAt(0).getRow(0).getCell(2).getStringCellValue());
            assertEquals("Late", wb.getSheetAt(0).getRow(1001).getCell(0).getStringCellValue());
        }
    }

    @Test
    void emptyCellsInTheDefaultStyleDoNotWidenThePrintedArea() throws IOException {
        String blanks = "<表:单元格 表:式样引用=\"Default\"/>".repeat(120);
        StringBuilder rows = new StringBuilder();
        for (int r = 0; r < 3; r++) {
            rows.append("<表:行><表:单元格 表:式样引用=\"Default\"><表:数据 表:数据类型=\"text\"><字:句><字:文本串>Row ")
                    .append(r).append("</字:文本串></字:句></表:数据></表:单元格>").append(blanks).append("</表:行>");
        }
        String xml = HEAD + "vnd.uof.spreadsheet\"><uof:式样集><uof:单元格式样 表:标识符=\"Default\" 表:名称=\"Normal\">"
                + "<表:对齐格式><表:垂直对齐方式>center</表:垂直对齐方式></表:对齐格式></uof:单元格式样></uof:式样集>"
                + "<uof:电子表格><表:主体><表:工作表 表:名称=\"S\"><表:工作表内容>" + rows
                + "</表:工作表内容></表:工作表></表:主体></uof:电子表格></uof:UOF>";
        Path pdf = convert("blanks.uos", xml);
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            assertEquals(1, doc.getNumberOfPages());
        }
        assertTrue(text(pdf).contains("Row 2"));
    }

    @Test
    void numberingLevelsAreCountedFromZero() throws IOException {
        String xml = HEAD + "vnd.uof.text\"><uof:式样集><uof:自动编号集><字:自动编号 字:标识符=\"L1\"><字:级别 字:级别值=\"1\">"
                + "<字:项目符号>◆</字:项目符号></字:级别></字:自动编号></uof:自动编号集></uof:式样集><uof:文字处理><字:主体>"
                + "<字:段落><字:段落属性><字:自动编号信息 字:编号引用=\"L1\" 字:编号级别=\"1\"/></字:段落属性>"
                + run("Item", "") + "</字:段落></字:主体></uof:文字处理></uof:UOF>";
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(rewrite("list.uot", xml,
                UofPackage.Kind.TEXT)))) {
            XWPFParagraph p = doc.getParagraphs().get(0);
            assertEquals(1, p.getNumIlvl().intValue());
            assertEquals("◆", p.getNumLevelText());
        }
    }

    @Test
    void wordXmlThatDeclaresTheUofNamespaceIsLeftToWordXml() throws IOException {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><w:wordDocument"
                + " xmlns:w=\"http://schemas.microsoft.com/office/word/2003/wordml\""
                + " xmlns:uof=\"http://schemas.uof.org/cn/2003/uof\"><w:body><w:p><w:r><w:t>Plain Word words</w:t>"
                + "</w:r></w:p></w:body></w:wordDocument>";
        Path in = Files.writeString(dir.resolve("word.xml"), xml, StandardCharsets.UTF_8);
        assertNull(UofPackage.sniff(in));
        Path out = dir.resolve("word.pdf");
        OfficeToPdf.convert(in, out);
        assertTrue(text(out).contains("Plain Word words"));
    }

    @Test
    void uofRootsAreFoundWhateverTheQuotesOrEncoding() throws IOException {
        String single = "<?xml version='1.0' encoding='UTF-8'?><uof:UOF xmlns:uof='http://schemas.uof.org/cn/2003/uof'"
                + " uof:mimetype='vnd.uof.spreadsheet'><uof:电子表格/></uof:UOF>";
        assertEquals(UofPackage.Kind.SPREADSHEET, UofPackage.sniff(Files.writeString(dir.resolve("s.uos"), single)));
        String text = HEAD + "vnd.uof.text\"><uof:文字处理><字:主体/></uof:文字处理></uof:UOF>";
        assertEquals(UofPackage.Kind.TEXT, UofPackage.sniff(Files.writeString(dir.resolve("t.uot"), text,
                StandardCharsets.UTF_8)));
        String wide = text.replace("encoding=\"UTF-8\"", "encoding=\"UTF-16\"");
        assertEquals(UofPackage.Kind.TEXT, UofPackage.sniff(Files.writeString(dir.resolve("w.uot"), wide,
                StandardCharsets.UTF_16)));
        String deck = "<uof:UOF xmlns:uof=\"http://schemas.uof.org/cn/2003/uof\"><uof:演示文稿/></uof:UOF>";
        assertEquals(UofPackage.Kind.PRESENTATION, UofPackage.sniff(Files.writeString(dir.resolve("p.uop"), deck)));
    }

    @Test
    void onlyUofXmlIsTakenForUof() throws IOException {
        Path other = Files.writeString(dir.resolve("x.uot"), "<?xml version=\"1.0\"?><root/>");
        assertNull(UofPackage.sniff(other));
        Path doctype = Files.writeString(dir.resolve("d.uot"), "<?xml version=\"1.0\"?><!DOCTYPE x [<!ENTITY e \"x\">]>"
                + HEAD.substring(HEAD.indexOf("<uof:UOF")) + "vnd.uof.text\"><uof:文字处理><字:主体/></uof:文字处理>"
                + "</uof:UOF>");
        assertThrows(IOException.class, () -> OfficeToPdf.convert(doctype, dir.resolve("d.pdf")));
    }

    private static byte[] png() throws IOException {
        BufferedImage image = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 4; x++) {
                image.setRGB(x, y, Color.RED.getRGB());
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }
}
