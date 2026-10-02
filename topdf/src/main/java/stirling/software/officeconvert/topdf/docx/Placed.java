package stirling.software.officeconvert.topdf.docx;

final class Placed {

    final Strip strip;

    float x;

    float y;

    int column;

    final int section;

    float gap;

    boolean fixed;

    Placed(Strip strip, float x, float y, int column, int section, float gap) {
        this.strip = strip;
        this.x = x;
        this.y = y;
        this.column = column;
        this.section = section;
        this.gap = gap;
    }
}
