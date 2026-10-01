package stirling.software.officeconvert.topdf.xls;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.apache.poi.hssf.usermodel.HSSFFont;
import org.apache.poi.hssf.usermodel.HSSFPalette;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.hssf.util.HSSFColor;
import org.apache.poi.ss.usermodel.DataFormatter;

final class ChartFonts implements ChartRecords.Fonts {

    private static final int AUTO_FILL = 24;

    private static final int AUTO_LINE = 32;

    private final HSSFWorkbook wb;

    private final DataFormatter formatter = new DataFormatter(Locale.US);

    ChartFonts(HSSFWorkbook wb) {
        this.wb = wb;
    }

    @Override
    public BiffChart.Text font(int index, String text) {
        HSSFFont f = at(index);
        return f == null ? new BiffChart.Text(text, 10, false)
                : new BiffChart.Text(text, Math.max(1, Math.min(400, f.getFontHeight() / 20f)), f.getBold());
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
