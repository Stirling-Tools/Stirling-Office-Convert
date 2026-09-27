package stirling.software.officeconvert.sink;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import javax.imageio.ImageIO;

import stirling.software.officeconvert.build.DocSink;
import stirling.software.officeconvert.model.Inline;
import stirling.software.officeconvert.model.Numbering;
import stirling.software.officeconvert.model.Paragraph;
import stirling.software.officeconvert.model.Picture;
import stirling.software.officeconvert.model.RunStyle;
import stirling.software.officeconvert.model.Section;
import stirling.software.officeconvert.model.StyleSheet;
import stirling.software.officeconvert.model.Table;

public final class SampleDocument {

    public static final RunStyle BODY = new RunStyle("Arial", 11, false, false, false, false, 0, -1, 0, false);
    public static final RunStyle BOLD = new RunStyle("Arial", 11, true, false, false, false, 0xC00000, -1, 0, false);

    private SampleDocument() {}

    public static void write(DocSink sink) throws IOException {
        StyleSheet styles = new StyleSheet(BODY);
        styles.use("Heading1", new RunStyle("Arial", 18, true, false, false, false, 0x1F3864, -1, 0, false));
        styles.use("ListParagraph", BODY);
        Numbering numbering = new Numbering();
        Numbering.Instance list = numbering.create();
        list.definition.levels[0] = new Numbering.Level("bullet", "\u2022", BODY, 36, 18);
        list.definition.levels[1] = new Numbering.Level("lowerLetter", "%2)", BODY, 72, 18);
        Numbering.Instance steps = numbering.create();
        steps.definition.levels[0] = new Numbering.Level("decimal", "%1.", BODY, 36, 18);
        steps.starts[0] = 3;

        Paragraph header = paragraph("Quarterly report ");
        header.inlines.add(new Inline.PageNumber(BODY, false));
        Paragraph footer = paragraph("Page ");
        footer.inlines.add(new Inline.PageNumber(BODY, false));
        footer.inlines.add(new Inline.Text(" of ", BODY, null, -1));
        footer.inlines.add(new Inline.PageNumber(BODY, true));
        sink.begin(styles, new DocSink.HeaderFooterSet(List.of(header), List.of(footer), List.of(), List.of(), false));

        byte[] png = png();
        Picture.MediaRef ref = sink.media(png, "png", 4, 2, "logo");

        Paragraph title = paragraph("Annual  Results");
        title.style = "Heading1";
        title.bookmark = "_Pg1";
        sink.block(title);

        Paragraph body = new Paragraph();
        body.inlines.add(new Inline.Text("Plain, ", BODY, null, -1));
        body.inlines.add(new Inline.Text("bold red", BOLD, null, -1));
        body.inlines.add(new Inline.Text(" and a link", BODY, "https://example.com/a?b=1&c=2", -1));
        body.inlines.add(new Inline.FootnoteRef(1, "1", false, BODY));
        body.inlines.add(new Inline.Tab(BODY));
        body.inlines.add(new Inline.Text("after tab", BODY, null, -1));
        body.tabs.add(new Paragraph.TabStop(200, Paragraph.TabStop.Kind.RIGHT, '.'));
        Picture inline = new Picture(ref, 20, 10);
        body.inlines.add(new Inline.Image(inline));
        sink.block(body);

        sink.block(item("First point", 1, 0));
        sink.block(item("Nested point", 1, 1));
        sink.block(item("Second point", 1, 0));
        sink.footnote(1, List.of(paragraph("The note text.")));
        sink.block(item("Step three", 2, 0));

        sink.block(table());

        Paragraph anchor = paragraph("Text beside a box.");
        Picture floating = new Picture(ref, 40, 20);
        floating.wrap = Picture.Wrap.SQUARE;
        floating.x = 400;
        floating.y = 300;
        floating.cropLeft = 0.25f;
        anchor.inlines.addFirst(new Inline.Image(floating));
        anchor.inlines.addFirst(new Inline.TextBox(380, 400, 120, 60, 0xDDEEFF, 4, 4, 6, List.of(paragraph("Boxed words")),
                0x000000, 0.5f, false, false));
        anchor.inlines.addFirst(new Inline.TextBox(40, 400, 20, 120, -1, 0, 0, 0, List.of(paragraph("Sideways")),
                -1, 0, false, true, 270));
        anchor.inlines.addFirst(new Inline.Shape(36, 700, 540, 20, 0xEEEEEE));
        anchor.inlines.addFirst(new Inline.Shape(36, 700, 540, 20, 0xCCCCCC).riding(12));
        Section single = section(1);
        anchor.endsSection = single;
        sink.block(anchor);

        Paragraph left = paragraph("Left column text.");
        sink.block(left);
        Paragraph brk = new Paragraph();
        brk.inlines.add(new Inline.ColumnBreak());
        sink.block(brk);
        Paragraph right = paragraph("Right column text.");
        Section two = section(2);
        two.continuous = true;
        right.endsSection = two;
        sink.block(right);

        Paragraph end = paragraph("The end, שלום and 中文.");
        sink.block(end);
        Section last = section(1);
        last.continuous = true;
        sink.finish(last, null, numbering, styles, "Sample & title", "Tester");
    }

    public static Paragraph paragraph(String text) {
        Paragraph p = new Paragraph();
        p.inlines.add(new Inline.Text(text, BODY, null, -1));
        p.lineRule = Paragraph.LineRule.EXACT;
        p.lineHeight = 13;
        return p;
    }

    private static Paragraph item(String text, int numId, int level) {
        Paragraph p = paragraph(text);
        p.style = "ListParagraph";
        p.list = new Paragraph.ListRef(numId, level);
        p.indentLeft = 36 * (level + 1);
        p.indentFirst = -18;
        return p;
    }

    private static Table table() {
        Table t = new Table();
        t.columnWidths.addAll(List.of(100f, 120f, 140f));
        Table.Border line = new Table.Border(0.5f, 0x000000);
        String[][] text = {{"Region", null, "Total"}, {"North", "12", "30"}, {"South", "18", null}};
        for (int r = 0; r < 3; r++) {
            Table.Row row = new Table.Row();
            row.height = 14;
            row.header = r == 0;
            for (int c = 0; c < 3; c++) {
                if (r == 0 && c == 1) {
                    continue;
                }
                Table.Cell cell = new Table.Cell();
                cell.top = cell.bottom = cell.left = cell.right = line;
                if (r == 0 && c == 0) {
                    cell.gridSpan = 2;
                    cell.shading = 0xD9D9D9;
                }
                if (c == 2 && r == 1) {
                    cell.vMerge = 1;
                } else if (c == 2 && r == 2) {
                    cell.vMerge = 2;
                }
                if (text[r][c] != null && cell.vMerge != 2) {
                    cell.paragraphs.add(paragraph(text[r][c]));
                }
                row.cells.add(cell);
            }
            t.rows.add(row);
        }
        return t;
    }

    public static Section section(int columns) {
        Section s = new Section();
        s.pageWidth = 612;
        s.pageHeight = 792;
        s.marginTop = s.marginBottom = s.marginLeft = s.marginRight = 72;
        s.headerDistance = s.footerDistance = 36;
        float width = (468 - 18 * (columns - 1)) / columns;
        for (int i = 0; i < columns; i++) {
            s.columns.add(new float[] {width, i + 1 < columns ? 18 : 0});
        }
        return s;
    }

    private static byte[] png() throws IOException {
        BufferedImage img = new BufferedImage(4, 2, BufferedImage.TYPE_INT_RGB);
        img.setRGB(0, 0, 0xFF0000);
        img.setRGB(3, 1, 0x0000FF);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }
}
