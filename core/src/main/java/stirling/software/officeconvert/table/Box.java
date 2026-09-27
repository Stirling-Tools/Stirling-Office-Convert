package stirling.software.officeconvert.table;

import stirling.software.officeconvert.table.PageContent.PdfWord;

record Box(float left, float top, float right, float bottom) {

    float width() {
        return right - left;
    }

    float height() {
        return bottom - top;
    }

    float area() {
        return width() * height();
    }

    float centreX() {
        return (left + right) / 2f;
    }

    float centreY() {
        return (top + bottom) / 2f;
    }

    boolean contains(PdfWord w) {
        float cx = w.centreX();
        float cy = w.centreY();
        return cx >= left - 1 && cx <= right + 1 && cy >= top - 1 && cy <= bottom + 1;
    }
}
