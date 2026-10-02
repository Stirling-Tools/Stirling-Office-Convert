package stirling.software.officeconvert.topdf.text;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.Writer;

final class TextBody {

    static final int LINES_PER_PAGE = 64;

    static final int COLUMNS = 80;

    static final int LINES_PER_PARAGRAPH = 64;

    static final int SOFT_PARAGRAPH = 16_000;

    static final int HARD_PARAGRAPH = 20_000;

    private final Writer out;

    private final long lineBudget;

    private final long charBudget;

    private final StringBuilder buf = new StringBuilder(1 << 12);

    private boolean open;

    private boolean inText;

    private boolean pendingBreak;

    private boolean any;

    private int chars;

    private int lineChars;

    private int paragraphLines;

    private long col;

    private long lines;

    private long written;

    private long paragraphs;

    private boolean cut;

    TextBody(Writer out, long lineBudget, long charBudget) {
        this.out = out;
        this.lineBudget = lineBudget;
        this.charBudget = charBudget;
    }

    boolean cut() {
        return cut;
    }

    long lines() {
        return lines;
    }

    void read(TextScanner in) throws IOException {
        for (int c = in.next(); c != TextScanner.END && !cut; c = in.next()) {
            switch (c) {
                case TextScanner.LINE -> lineEnd();
                case TextScanner.PAGE -> page();
                case TextScanner.TAB -> tab();
                default -> text(c);
            }
        }
        if (open) {
            pendingBreak = false;
            endParagraph();
        }
        if (!any) {
            out.write("<w:p/>");
        }
    }

    private boolean start() throws IOException {
        if (open) {
            return true;
        }
        if (lines >= lineBudget || written >= charBudget) {
            cut = true;
            return false;
        }
        if ((++paragraphs & 0x3FF) == 0 && Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("Interrupted while reading the text");
        }
        buf.append("<w:p><w:r>");
        open = true;
        any = true;
        chars = 0;
        lineChars = 0;
        paragraphLines = 0;
        col = 0;
        return true;
    }

    private boolean content() throws IOException {
        if (!start()) {
            return false;
        }
        if (pendingBreak) {
            closeText();
            buf.append("<w:br/>");
            pendingBreak = false;
        }
        return true;
    }

    private void text(int cp) throws IOException {
        if (!content()) {
            return;
        }
        if (!inText) {
            buf.append("<w:t xml:space=\"preserve\">");
            inText = true;
        }
        Parts.escape(buf, cp);
        chars++;
        lineChars++;
        col++;
        if (lineChars >= HARD_PARAGRAPH || lineChars >= SOFT_PARAGRAPH && cp == ' ') {
            endParagraph();
        } else if (buf.length() >= 1 << 12) {
            flush();
        }
    }

    private void tab() throws IOException {
        if (!content()) {
            return;
        }
        closeText();
        buf.append("<w:tab/>");
        col++;
    }

    private void lineEnd() throws IOException {
        if (!content()) {
            return;
        }
        countLine();
        paragraphLines++;
        if (lines >= lineBudget) {
            endParagraph();
            cut = true;
        } else if (paragraphLines >= LINES_PER_PARAGRAPH || chars >= SOFT_PARAGRAPH) {
            endParagraph();
        } else {
            pendingBreak = true;
        }
    }

    private void page() throws IOException {
        if (pendingBreak) {
            pendingBreak = false;
            endParagraph();
        }
        if (!start()) {
            return;
        }
        closeText();
        buf.append("<w:br w:type=\"page\"/>");
        lines += Math.ceilDiv(col, COLUMNS);
        lines = Math.ceilDiv(lines, LINES_PER_PAGE) * LINES_PER_PAGE;
        col = 0;
        if (lines >= lineBudget) {
            endParagraph();
            cut = true;
        }
    }

    private void countLine() {
        lines += Math.max(1, Math.ceilDiv(col, COLUMNS));
        col = 0;
        lineChars = 0;
    }

    private void endParagraph() throws IOException {
        if (!open) {
            return;
        }
        if (!pendingBreak && (col > 0 || paragraphLines == 0)) {
            countLine();
        }
        pendingBreak = false;
        closeText();
        buf.append("</w:r></w:p>");
        open = false;
        flush();
    }

    private void closeText() {
        if (inText) {
            buf.append("</w:t>");
            inText = false;
        }
    }

    private void flush() throws IOException {
        written += buf.length();
        out.append(buf);
        buf.setLength(0);
    }
}
