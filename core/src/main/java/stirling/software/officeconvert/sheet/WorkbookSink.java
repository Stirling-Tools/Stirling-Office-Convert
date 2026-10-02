package stirling.software.officeconvert.sheet;

import java.io.Closeable;
import java.io.IOException;
import java.util.List;

public interface WorkbookSink extends Closeable {

    record SheetSetup(String name, boolean landscape, boolean a4, String header, String footer) {}

    record NamedRange(String name, int firstRow, int firstCol, int lastRow, int lastCol) {}

    record SheetEnd(List<Float> columnWidths, int frozenRows, List<NamedRange> names, boolean rightToLeft) {

        public SheetEnd {
            columnWidths = List.copyOf(columnWidths);
            names = List.copyOf(names);
        }

        public SheetEnd(List<Float> columnWidths, int frozenRows, List<NamedRange> names) {
            this(columnWidths, frozenRows, names, false);
        }
    }

    void startSheet(SheetSetup setup) throws IOException;

    void row(Row row) throws IOException;

    void endSheet(SheetEnd end) throws IOException;

    void finish(String title, String author) throws IOException;
}
