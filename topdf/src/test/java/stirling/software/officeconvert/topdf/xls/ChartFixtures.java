package stirling.software.officeconvert.topdf.xls;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.apache.poi.hssf.record.BOFRecord;
import org.apache.poi.hssf.record.NumberRecord;
import org.apache.poi.hssf.record.RecordBase;
import org.apache.poi.hssf.record.UnknownRecord;
import org.apache.poi.hssf.record.WindowTwoRecord;
import org.apache.poi.hssf.record.chart.BeginRecord;
import org.apache.poi.hssf.record.chart.ChartRecord;
import org.apache.poi.hssf.record.chart.EndRecord;
import org.apache.poi.hssf.record.chart.LinkedDataRecord;
import org.apache.poi.hssf.record.chart.SeriesRecord;
import org.apache.poi.hssf.record.chart.SeriesTextRecord;
import org.apache.poi.hssf.usermodel.HSSFSheet;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.formula.ptg.Ptg;

final class ChartFixtures {

    private ChartFixtures() {}

    static void chartSheet(HSSFWorkbook wb, String name, List<RecordBase> chart) {
        HSSFSheet sheet = wb.createSheet(name);
        List<RecordBase> records = sheet.getSheet().getRecords();
        ((BOFRecord) records.get(0)).setType(0x20);
        int at = 0;
        while (!(records.get(at) instanceof WindowTwoRecord)) {
            at++;
        }
        records.addAll(at, chart);
    }

    static List<RecordBase> linkedChart(int type, String name, int points, Ptg values) {
        List<RecordBase> r = new ArrayList<>();
        r.add(new ChartRecord());
        r.add(new BeginRecord());
        SeriesRecord s = new SeriesRecord();
        s.setNumCategories((short) points);
        s.setNumValues((short) points);
        r.add(s);
        r.add(new BeginRecord());
        LinkedDataRecord link = new LinkedDataRecord();
        link.setLinkType((byte) 1);
        link.setReferenceType((byte) 2);
        link.setFormulaOfLink(new Ptg[] {values});
        r.add(link);
        r.add(text(name));
        r.add(new EndRecord());
        r.add(new UnknownRecord(0x1041, new byte[18]));
        r.add(new BeginRecord());
        r.add(new UnknownRecord(0x1014, new byte[20]));
        r.add(new BeginRecord());
        r.add(new UnknownRecord(type, new byte[6]));
        r.add(new EndRecord());
        r.add(new EndRecord());
        r.add(new EndRecord());
        return r;
    }

    static List<RecordBase> chart(String title, int type, byte[] flags, String[] series, String[] categories,
            double[][] values) {
        List<RecordBase> r = new ArrayList<>();
        r.add(new ChartRecord());
        r.add(new BeginRecord());
        for (String name : series) {
            SeriesRecord s = new SeriesRecord();
            s.setNumCategories((short) categories.length);
            s.setNumValues((short) categories.length);
            r.add(s);
            r.add(new BeginRecord());
            r.add(text(name));
            r.add(new EndRecord());
        }
        if (title != null) {
            r.add(new UnknownRecord(0x1025, new byte[32]));
            r.add(new BeginRecord());
            r.add(text(title));
            r.add(new UnknownRecord(0x1027, new byte[] {1, 0, 0, 0, 0, 0}));
            r.add(new EndRecord());
        }
        r.add(new UnknownRecord(0x1041, new byte[18]));
        r.add(new BeginRecord());
        r.add(new UnknownRecord(0x1014, new byte[20]));
        r.add(new BeginRecord());
        r.add(new UnknownRecord(type, flags));
        r.add(new EndRecord());
        r.add(new EndRecord());
        r.add(new EndRecord());
        r.add(new UnknownRecord(0x1065, new byte[] {2, 0}));
        for (int s = 0; s < series.length; s++) {
            for (int i = 0; i < categories.length; i++) {
                r.add(label(i, s, categories[i]));
            }
        }
        r.add(new UnknownRecord(0x1065, new byte[] {1, 0}));
        for (int s = 0; s < series.length; s++) {
            for (int i = 0; i < categories.length; i++) {
                NumberRecord n = new NumberRecord();
                n.setRow(i);
                n.setColumn((short) s);
                n.setValue(values[s][i]);
                r.add(n);
            }
        }
        return r;
    }

    private static SeriesTextRecord text(String s) {
        SeriesTextRecord t = new SeriesTextRecord();
        t.setText(s);
        return t;
    }

    private static UnknownRecord label(int row, int col, String text) {
        byte[] t = text.getBytes(StandardCharsets.ISO_8859_1);
        byte[] d = new byte[9 + t.length];
        d[0] = (byte) row;
        d[1] = (byte) (row >> 8);
        d[2] = (byte) col;
        d[6] = (byte) t.length;
        System.arraycopy(t, 0, d, 9, t.length);
        return new UnknownRecord(0x204, d);
    }
}
