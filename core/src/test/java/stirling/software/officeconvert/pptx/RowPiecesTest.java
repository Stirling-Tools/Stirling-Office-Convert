package stirling.software.officeconvert.pptx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import stirling.software.officeconvert.model.Inline;
import stirling.software.officeconvert.model.Paragraph;
import stirling.software.officeconvert.model.RunStyle;
import stirling.software.officeconvert.model.Table;
import stirling.software.officeconvert.slides.TableShape;

class RowPiecesTest {

    private static Paragraph line(String text, float top) {
        Paragraph p = new Paragraph();
        p.inlines.add(new Inline.Text(text, new RunStyle("Arial", 8, false, false, false, false, 0, -1, 0, false), null, -1));
        p.lineHeight = 9.6f;
        p.sourceTop = top;
        p.sourceBottom = top + 9.6f;
        return p;
    }

    private static Table leaders(Paragraph... paras) {
        Table t = new Table();
        t.columnWidths.addAll(List.of(200f, 12f));
        for (int r = 0; r < 3; r++) {
            Table.Row row = new Table.Row();
            row.height = 12f;
            Table.Cell label = new Table.Cell();
            label.paragraphs.add(line("Line " + r, 100 + 12 * r + 1));
            Table.Cell dots = new Table.Cell();
            dots.vMerge = r == 0 ? 1 : 2;
            dots.left = new Table.Border(0.5f, 0);
            if (r == 0) {
                dots.paragraphs.addAll(List.of(paras));
            }
            row.cells.add(label);
            row.cells.add(dots);
            t.rows.add(row);
        }
        return t;
    }

    @Test
    void aMergedColumnOfOneLinerPerRowBecomesOneCellPerRow() {
        Table t = leaders(line(".", 101), line(".", 113), line(".", 125));
        Map<Table.Cell, Float> offsets = new IdentityHashMap<>();
        Table split = RowPieces.split(t, 100, offsets);
        for (int r = 0; r < 3; r++) {
            Table.Cell c = split.rows.get(r).cells.get(1);
            assertEquals(0, c.vMerge);
            assertEquals(1, c.paragraphs.size());
            assertEquals(1f, offsets.get(c), 0.01f);
            assertTrue(c.left.visible());
        }
        assertEquals(1, t.rows.get(0).cells.get(1).vMerge, "the source table is left as it was");
        assertEquals(3, t.rows.get(0).cells.get(1).paragraphs.size());
    }

    @Test
    void aParagraphThatCrossesRowsKeepsTheMerge() {
        Paragraph tall = line("A long note", 101);
        tall.sourceBottom = 130;
        Table t = leaders(tall);
        assertSame(t, RowPieces.split(t, 100, new IdentityHashMap<>()));
    }

    @Test
    void emptyCellsAreSizedToTheirRow() {
        Table t = leaders();
        StringBuilder sb = new StringBuilder();
        TextXml text = new TextXml(new SlideRels("../slideLayouts/slideLayout1.xml"), page -> 0, new HashMap<>(), null);
        new TableXml(text).table(sb, new TableShape(10, 100, t), 2);
        assertTrue(sb.toString().contains("<a:endParaRPr sz=\"923\""), sb.toString());
    }
}
