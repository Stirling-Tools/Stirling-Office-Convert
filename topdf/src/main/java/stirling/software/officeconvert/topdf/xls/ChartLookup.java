package stirling.software.officeconvert.topdf.xls;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.apache.poi.hssf.usermodel.HSSFCell;
import org.apache.poi.hssf.usermodel.HSSFFont;
import org.apache.poi.hssf.usermodel.HSSFPalette;
import org.apache.poi.hssf.usermodel.HSSFRow;
import org.apache.poi.hssf.usermodel.HSSFSheet;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.hssf.util.HSSFColor;
import org.apache.poi.ss.SpreadsheetVersion;
import org.apache.poi.ss.formula.ptg.Area3DPtg;
import org.apache.poi.ss.formula.ptg.Ptg;
import org.apache.poi.ss.formula.ptg.Ref3DPtg;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.util.AreaReference;
import org.apache.poi.ss.util.CellReference;

final class ChartLookup implements ChartRecords.Lookup {

    private static final int AUTO_FILL = 24;

    private static final int AUTO_LINE = 32;

    private final HSSFWorkbook wb;

    private final DataFormatter formatter = new DataFormatter(Locale.US);

    ChartLookup(HSSFWorkbook wb) {
        this.wb = wb;
    }

    @Override
    public BiffChart.Text font(int index, String text) {
        HSSFFont f = at(index);
        float size = f == null ? 0 : f.getFontHeight() / 20f;
        return size < 4 || size > 400 ? new BiffChart.Text(text, 10, f != null && f.getBold())
                : new BiffChart.Text(text, size, f.getBold());
    }

    @Override
    public String face(int index) {
        HSSFFont f = at(index);
        return f == null ? null : f.getFontName();
    }

    private HSSFFont at(int index) {
        try {
            return index < 0 || index >= wb.getNumberOfFonts() + 1 ? null : wb.getFontAt(index);
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Override
    public String format(int index) {
        try {
            String f = wb.createDataFormat().getFormat((short) index);
            return f == null || f.isBlank() ? null : f;
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Override
    public String number(double value, int xf, int fallbackFormat) {
        try {
            int index = xf >= 0 && xf < wb.getNumCellStyles() ? wb.getCellStyleAt(xf).getDataFormat() : 0;
            if (index == 0) {
                index = fallbackFormat;
            }
            String code = index == 0 ? "General" : format(index);
            return formatter.formatRawCellContents(value, index, code == null ? "General" : code);
        } catch (RuntimeException e) {
            return SheetPart.number(value);
        }
    }

    @Override
    public List<Object> cells(Ptg[] reference) {
        if (reference == null || reference.length != 1) {
            return List.of();
        }
        int extern;
        AreaReference area;
        if (reference[0] instanceof Area3DPtg a) {
            extern = a.getExternSheetIndex();
            area = new AreaReference(new CellReference(a.getFirstRow(), a.getFirstColumn()),
                    new CellReference(a.getLastRow(), a.getLastColumn()), SpreadsheetVersion.EXCEL97);
        } else if (reference[0] instanceof Ref3DPtg r) {
            extern = r.getExternSheetIndex();
            area = new AreaReference(new CellReference(r.getRow(), r.getColumn()),
                    new CellReference(r.getRow(), r.getColumn()), SpreadsheetVersion.EXCEL97);
        } else {
            return List.of();
        }
        try {
            if (wb.getInternalWorkbook().getExternalSheet(extern) != null) {
                return List.of();
            }
            int index = wb.getInternalWorkbook().getFirstSheetIndexFromExternSheetIndex(extern);
            if (index < 0 || index >= wb.getNumberOfSheets()) {
                return List.of();
            }
            return values(wb.getSheetAt(index), area);
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    private List<Object> values(HSSFSheet sheet, AreaReference area) {
        CellReference first = area.getFirstCell();
        CellReference last = area.getLastCell();
        List<Object> out = new ArrayList<>();
        for (int r = first.getRow(); r <= last.getRow(); r++) {
            HSSFRow row = sheet.getRow(r);
            for (int c = first.getCol(); c <= last.getCol(); c++) {
                if (out.size() >= ChartRecords.MAX_POINTS) {
                    return out;
                }
                out.add(row == null ? null : value(row.getCell(c)));
            }
        }
        return out;
    }

    private Object value(HSSFCell cell) {
        if (cell == null) {
            return null;
        }
        CellType type = cell.getCellType() == CellType.FORMULA ? cell.getCachedFormulaResultType()
                : cell.getCellType();
        return switch (type) {
            case NUMERIC -> cell.getNumericCellValue();
            case STRING -> cell.getRichStringCellValue().getString();
            case BOOLEAN -> cell.getBooleanCellValue() ? "TRUE" : "FALSE";
            default -> null;
        };
    }

    List<Integer> autoColors(boolean lines) {
        List<Integer> out = new ArrayList<>(8);
        HSSFPalette palette = wb.getCustomPalette();
        for (int i = 0; i < 8; i++) {
            HSSFColor c = palette.getColor((lines ? AUTO_LINE : AUTO_FILL) + i);
            short[] t = c == null ? null : c.getTriplet();
            out.add(t == null ? 0x808080 : (t[0] & 0xFF) << 16 | (t[1] & 0xFF) << 8 | t[2] & 0xFF);
        }
        return out;
    }
}
