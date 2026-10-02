package stirling.software.officeconvert.topdf.xlsx;

import java.util.Locale;

import org.openxmlformats.schemas.spreadsheetml.x2006.main.CTHeaderFooter;
import org.openxmlformats.schemas.spreadsheetml.x2006.main.CTPageMargins;
import org.openxmlformats.schemas.spreadsheetml.x2006.main.CTPageSetUpPr;
import org.openxmlformats.schemas.spreadsheetml.x2006.main.CTPageSetup;
import org.openxmlformats.schemas.spreadsheetml.x2006.main.CTPrintOptions;
import org.openxmlformats.schemas.spreadsheetml.x2006.main.CTWorksheet;
import org.openxmlformats.schemas.spreadsheetml.x2006.main.STOrientation;
import org.openxmlformats.schemas.spreadsheetml.x2006.main.STPageOrder;

import stirling.software.officeconvert.topdf.pdf.PageSize;

record PageSetup(PageSize paper, PageSize output, double left, double right, double top, double bottom, double header,
        double footer, int scale, boolean fitToPage, int fitWidth, int fitHeight, boolean centerHorizontally,
        boolean centerVertically, boolean overThenDown, int firstPageNumber, boolean gridlines, boolean headings,
        HeaderFooterSet headerFooter) {

    static final PageSize DEFAULT_PAPER = PageSize.A4;

    static final int AUTO_FIRST_PAGE = Integer.MIN_VALUE;

    static final double LETTER_ON_A4 = 0.95;

    static final double LETTER_ON_A4_LONG = 0.96;

    static PageSetup safe(CTWorksheet ws) {
        try {
            return of(ws);
        } catch (RuntimeException e) {
            return of(CTWorksheet.Factory.newInstance());
        }
    }

    static PageSetup of(CTWorksheet ws) {
        CTPageSetup ps = ws.isSetPageSetup() ? ws.getPageSetup() : null;
        CTPageMargins pm = ws.isSetPageMargins() ? ws.getPageMargins() : null;
        CTPrintOptions po = ws.isSetPrintOptions() ? ws.getPrintOptions() : null;
        CTPageSetUpPr pr = ws.isSetSheetPr() && ws.getSheetPr().isSetPageSetUpPr() ? ws.getSheetPr().getPageSetUpPr() : null;
        boolean landscape = ps != null && ps.isSetOrientation() && ps.getOrientation() == STOrientation.LANDSCAPE;
        PageSize paper = paper(ps);
        PageSize output = paper;
        boolean fit = pr != null && pr.isSetFitToPage() && pr.getFitToPage();
        if (letter(ps) && !fit) {
            paper = PageSize.LETTER;
            output = DEFAULT_PAPER;
        }
        paper = landscape ? paper.toLandscape() : paper.toPortrait();
        output = landscape ? output.toLandscape() : output.toPortrait();
        double left = inches(pm == null ? 0.7 : pm.getLeft());
        double right = inches(pm == null ? 0.7 : pm.getRight());
        double top = inches(pm == null ? 0.75 : pm.getTop());
        double bottom = inches(pm == null ? 0.75 : pm.getBottom());
        double header = inches(pm == null ? 0.3 : pm.getHeader());
        double footer = inches(pm == null ? 0.3 : pm.getFooter());
        double maxW = paper.width() * 0.45;
        double maxH = paper.height() * 0.45;
        left = clamp(left, maxW);
        right = clamp(right, maxW);
        top = clamp(top, maxH);
        bottom = clamp(bottom, maxH);
        header = clamp(header, maxH);
        footer = clamp(footer, maxH);
        int scale = ps != null && ps.isSetScale() ? (int) Math.max(10, Math.min(400, ps.getScale())) : 100;
        int fitW = ps != null && ps.isSetFitToWidth() ? (int) Math.min(Integer.MAX_VALUE, ps.getFitToWidth()) : 1;
        int fitH = ps != null && ps.isSetFitToHeight() ? (int) Math.min(Integer.MAX_VALUE, ps.getFitToHeight()) : 1;
        boolean centerH = po != null && po.isSetHorizontalCentered() && po.getHorizontalCentered();
        boolean centerV = po != null && po.isSetVerticalCentered() && po.getVerticalCentered();
        boolean overThenDown = ps != null && ps.isSetPageOrder() && ps.getPageOrder() == STPageOrder.OVER_THEN_DOWN;
        int first = ps != null && ps.isSetUseFirstPageNumber() && ps.getUseFirstPageNumber() && ps.isSetFirstPageNumber()
                ? (int) Math.max(Integer.MIN_VALUE / 2, Math.min(Integer.MAX_VALUE / 2, ps.getFirstPageNumber()))
                : AUTO_FIRST_PAGE;
        boolean grid = po != null && po.isSetGridLines() && po.getGridLines();
        boolean headings = po != null && po.isSetHeadings() && po.getHeadings();
        CTHeaderFooter hf = ws.isSetHeaderFooter() ? ws.getHeaderFooter() : null;
        return new PageSetup(paper, output, left, right, top, bottom, header, footer, scale, fit, Math.max(0, fitW),
                Math.max(0, fitH), centerH, centerV, overThenDown, first, grid, headings, HeaderFooterSet.of(hf));
    }

    boolean resized() {
        return !paper.equals(output);
    }

    java.awt.geom.AffineTransform resize() {
        double k = LETTER_ON_A4;
        double ox = left + PrintMetrics.ORIGIN;
        double oy = top + PrintMetrics.ORIGIN;
        boolean wide = wide();
        return new java.awt.geom.AffineTransform(k, 0, 0, k, (1 - k) * ox + (wide ? longOffset() : 0),
                (1 - k) * oy + (wide ? 0 : longOffset()));
    }

    double centeredLeft(double contentWidth) {
        if (!resized()) {
            return left + Math.max(0, (printableWidth() - contentWidth) / 2);
        }
        double offset = wide() ? longOffset() : 0;
        double avail = output.width() - left - right - offset - PrintMetrics.ORIGIN;
        double x = left + offset + Math.max(0, (avail - contentWidth * LETTER_ON_A4) / 2);
        return (x - resize().getTranslateX()) / LETTER_ON_A4;
    }

    double centeredTop(double contentHeight) {
        if (!resized()) {
            return top + Math.max(0, (printableHeight() - contentHeight) / 2);
        }
        double offset = wide() ? 0 : longOffset();
        double avail = output.height() - top - bottom - offset - PrintMetrics.ORIGIN;
        double y = top + offset + Math.max(0, (avail - contentHeight * LETTER_ON_A4) / 2);
        return (y - resize().getTranslateY()) / LETTER_ON_A4;
    }

    double bandOffsetX() {
        return resized() && wide() ? longOffset() : 0;
    }

    double bandOffsetY() {
        return resized() && !wide() ? longOffset() : 0;
    }

    private boolean wide() {
        return paper.width() > paper.height();
    }

    private double longOffset() {
        return wide() ? (output.width() - paper.width() * LETTER_ON_A4_LONG) / 2
                : (output.height() - paper.height() * LETTER_ON_A4_LONG) / 2;
    }

    private static boolean letter(CTPageSetup ps) {
        if (ps == null || ps.isSetPaperWidth() || ps.isSetPaperHeight()) {
            return false;
        }
        long code = ps.isSetPaperSize() ? ps.getPaperSize() : 1;
        return code == 1;
    }

    double printableWidth() {
        return Math.max(36, paper.width() - left - right - PrintMetrics.ORIGIN);
    }

    double printableHeight() {
        return Math.max(36, paper.height() - top - bottom - PrintMetrics.ORIGIN);
    }

    private static double inches(double v) {
        return Double.isFinite(v) && v >= 0 ? v * 72 : 0;
    }

    private static double clamp(double v, double max) {
        return Math.max(0, Math.min(v, max));
    }

    static PageSize paper(CTPageSetup ps) {
        if (ps == null) {
            return DEFAULT_PAPER;
        }
        if (ps.isSetPaperWidth() && ps.isSetPaperHeight()) {
            double w = length(ps.getPaperWidth());
            double h = length(ps.getPaperHeight());
            if (w >= 72 && h >= 72 && w <= PageSize.MAX_SIDE && h <= PageSize.MAX_SIDE) {
                return new PageSize((float) w, (float) h);
            }
        }
        int code = ps.isSetPaperSize() ? (int) ps.getPaperSize() : 1;
        return byCode(code);
    }

    static PageSize byCode(int code) {
        return switch (code) {
            case 3, 17 -> PageSize.TABLOID;
            case 4 -> new PageSize(1224, 792);
            case 5 -> PageSize.LEGAL;
            case 6 -> new PageSize(396, 612);
            case 7 -> PageSize.EXECUTIVE;
            case 8 -> PageSize.A3;
            case 9, 10 -> PageSize.A4;
            case 11 -> PageSize.A5;
            case 12 -> mm(257, 364);
            case 13 -> mm(182, 257);
            case 14 -> new PageSize(612, 936);
            case 15 -> mm(215, 275);
            case 16 -> new PageSize(720, 1008);
            case 18 -> new PageSize(612, 792);
            case 20 -> new PageSize(297, 684);
            case 24 -> new PageSize(1224, 1584);
            case 25 -> new PageSize(1584, 2448);
            case 26 -> new PageSize(2448, 3168);
            case 27 -> mm(110, 220);
            case 28 -> mm(162, 229);
            case 29 -> mm(324, 458);
            case 30 -> mm(229, 324);
            case 31 -> mm(114, 162);
            case 33 -> mm(250, 353);
            case 34 -> mm(176, 250);
            case 66 -> mm(420, 594);
            case 70 -> mm(105, 148);
            default -> DEFAULT_PAPER;
        };
    }

    private static PageSize mm(double w, double h) {
        return new PageSize((float) (w * 72 / 25.4), (float) (h * 72 / 25.4));
    }

    private static double length(String v) {
        if (v == null) {
            return 0;
        }
        String s = v.trim().toLowerCase(Locale.ROOT);
        double factor = 72;
        if (s.endsWith("mm")) {
            factor = 72 / 25.4;
            s = s.substring(0, s.length() - 2);
        } else if (s.endsWith("cm")) {
            factor = 72 / 2.54;
            s = s.substring(0, s.length() - 2);
        } else if (s.endsWith("in")) {
            s = s.substring(0, s.length() - 2);
        } else if (s.endsWith("pt")) {
            factor = 1;
            s = s.substring(0, s.length() - 2);
        }
        try {
            double d = Double.parseDouble(s.trim());
            return Double.isFinite(d) ? d * factor : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
