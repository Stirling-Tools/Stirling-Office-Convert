package stirling.software.officeconvert.topdf.xlsx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.apache.poi.xssf.model.StylesTable;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.junit.jupiter.api.Test;
import org.openxmlformats.schemas.spreadsheetml.x2006.main.CTCol;
import org.openxmlformats.schemas.spreadsheetml.x2006.main.CTCols;
import org.openxmlformats.schemas.spreadsheetml.x2006.main.CTWorksheet;

import stirling.software.officeconvert.topdf.font.FontLibrary;

class ColumnLookupTest {

    @Test
    void aColumnTakesTheSpanStartingNearestBeforeItOnlyWhileThatSpanReachesIt() {
        CTWorksheet ws = CTWorksheet.Factory.newInstance();
        CTCols cols = ws.addNewCols();
        col(cols, 2, 4, 3);
        col(cols, 3, 3, 5);
        col(cols, 10, 20, 7);
        col(cols, 12, 12, 9);
        col(cols, 16384, 20000, 11);
        Columns c = new Columns(ws, new PrintMetrics(FontMeasure.of(FontLibrary.system(), "Calibri", false, false), 11));
        Map<Integer, Integer> styled = Map.of(1, 3, 2, 5, 9, 7, 10, 7, 11, 9, 16383, 11);
        for (int col = -1; col < Columns.MAX; col++) {
            assertEquals(styled.getOrDefault(col, -1), c.style(col), "column " + col);
        }
    }

    @Test
    void stylesAreRememberedByPositionUpToABound() {
        StylesTable table = new StylesTable();
        for (int i = 0; i < 3; i++) {
            table.createCellStyle();
        }
        StyleCache cache = new StyleCache(table, new ExcelColors(null, null));
        XSSFCellStyle second = table.getStyleAt(2);
        assertSame(cache.of(second), cache.at(2));
        assertSame(cache.at(2), cache.at(2));
        assertSame(cache.at(-1), cache.at(table.getNumCellStyles()));
        assertTrue(cache.remembered() <= table.getNumCellStyles());
    }

    private static void col(CTCols cols, int min, int max, int style) {
        CTCol c = cols.addNewCol();
        c.setMin(min);
        c.setMax(max);
        c.setStyle(style);
    }
}
