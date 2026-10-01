package stirling.software.officeconvert.topdf.xls;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.poi.hssf.record.BOFRecord;
import org.apache.poi.hssf.record.CommonObjectDataSubRecord;
import org.apache.poi.hssf.record.ContinueRecord;
import org.apache.poi.hssf.record.DrawingRecord;
import org.apache.poi.hssf.record.EOFRecord;
import org.apache.poi.hssf.record.ObjRecord;
import org.apache.poi.hssf.record.Record;
import org.apache.poi.hssf.record.RecordFactoryInputStream;
import org.apache.poi.hssf.record.SubRecord;
import org.apache.poi.poifs.filesystem.DirectoryNode;
import org.apache.poi.poifs.filesystem.DocumentEntry;
import org.apache.poi.poifs.filesystem.Entry;

final class ChartStream {

    static final int MAX_CHARTS = 1000;

    private static final int BOF_CHART = 0x20;

    private static final int OBJECT_CHART = 5;

    record Embedded(int[] anchor, BiffChart chart) {}

    final Set<Integer> chartSheets = new HashSet<>();

    final Map<Integer, BiffChart> sheetCharts = new HashMap<>();

    final Map<Integer, List<Embedded>> embedded = new HashMap<>();

    private final ChartRecords.Fonts fonts;

    private final List<Integer> autoFills;

    private final List<Integer> autoLines;

    private ChartStream(ChartRecords.Fonts fonts, List<Integer> autoFills, List<Integer> autoLines) {
        this.fonts = fonts;
        this.autoFills = autoFills;
        this.autoLines = autoLines;
    }

    static ChartStream read(DirectoryNode root, ChartRecords.Fonts fonts, List<Integer> autoFills,
            List<Integer> autoLines) throws IOException {
        ChartStream out = new ChartStream(fonts, autoFills, autoLines);
        Entry e = root.hasEntryCaseInsensitive("Workbook") ? root.getEntryCaseInsensitive("Workbook") : null;
        if (!(e instanceof DocumentEntry doc)) {
            return out;
        }
        try (InputStream in = root.createDocumentInputStream(doc)) {
            out.scan(new RecordFactoryInputStream(in, true));
        } catch (RuntimeException ex) {
            return out;
        }
        return out;
    }

    private void scan(RecordFactoryInputStream rs) throws IOException {
        int depth = 0;
        int sheet = -2;
        int count = 0;
        int charts = 0;
        ChartRecords chart = null;
        int chartDepth = 0;
        byte[] drawing = null;
        int[] anchor = null;
        boolean continuesDrawing = false;
        Record r;
        while ((r = rs.nextRecord()) != null) {
            if ((++count & 4095) == 0) {
                SheetPart.stopIfInterrupted();
            }
            if (r instanceof BOFRecord b) {
                depth++;
                if (depth == 1) {
                    sheet++;
                    anchor = null;
                }
                if (b.getType() == BOF_CHART && chart == null && charts < MAX_CHARTS) {
                    chart = new ChartRecords(fonts);
                    chartDepth = depth;
                    if (depth == 1 && sheet >= 0) {
                        chartSheets.add(sheet);
                    }
                }
                continue;
            }
            if (r instanceof EOFRecord) {
                if (chart != null && depth == chartDepth) {
                    charts++;
                    finish(chart.build(autoFills, autoLines), depth, sheet, anchor);
                    chart = null;
                    anchor = null;
                }
                depth = Math.max(0, depth - 1);
                continue;
            }
            if (chart != null) {
                chart.add(r);
                continue;
            }
            if (depth != 1 || sheet < 0) {
                continue;
            }
            if (r instanceof DrawingRecord d) {
                drawing = d.getRecordData();
                continuesDrawing = true;
            } else if (r instanceof ContinueRecord c && continuesDrawing && drawing != null) {
                ByteArrayOutputStream joined = new ByteArrayOutputStream();
                joined.writeBytes(drawing);
                joined.writeBytes(c.getData());
                drawing = joined.toByteArray();
            } else {
                continuesDrawing = false;
                if (r instanceof ObjRecord o && isChart(o)) {
                    anchor = drawing == null ? null : lastAnchor(drawing);
                }
            }
        }
    }

    private void finish(BiffChart built, int depth, int sheet, int[] anchor) {
        if (built == null || sheet < 0) {
            return;
        }
        if (depth == 1) {
            sheetCharts.put(sheet, built);
        } else if (anchor != null) {
            embedded.computeIfAbsent(sheet, k -> new ArrayList<>()).add(new Embedded(anchor, built));
        }
    }

    private static boolean isChart(ObjRecord o) {
        for (SubRecord s : o.getSubRecords()) {
            if (s instanceof CommonObjectDataSubRecord c) {
                return c.getObjectType() == OBJECT_CHART;
            }
        }
        return false;
    }

    static int[] lastAnchor(byte[] d) {
        for (int i = d.length - 24; i >= 2; i--) {
            if ((d[i] & 0xFF) == 0x10 && (d[i + 1] & 0xFF) == 0xF0 && (d[i + 2] & 0xFF) == 18 && d[i + 3] == 0
                    && d[i + 4] == 0 && d[i + 5] == 0) {
                int at = i + 6 + 2;
                int[] a = new int[8];
                for (int k = 0; k < 8; k++) {
                    a[k] = ChartRecords.u16(d, at + 2 * k);
                }
                return a;
            }
        }
        return null;
    }
}
