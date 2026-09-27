package stirling.software.officeconvert.slides;

import stirling.software.officeconvert.model.Table;

public record TableShape(float x, float y, Table table) implements SlideShape {

    public float width() {
        float w = 0;
        for (float c : table.columnWidths) {
            w += c;
        }
        return w;
    }

    public float height() {
        float h = 0;
        for (Table.Row r : table.rows) {
            h += r.height;
        }
        return h;
    }
}
