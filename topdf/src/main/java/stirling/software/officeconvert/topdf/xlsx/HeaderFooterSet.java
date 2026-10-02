package stirling.software.officeconvert.topdf.xlsx;

import org.openxmlformats.schemas.spreadsheetml.x2006.main.CTHeaderFooter;

record HeaderFooterSet(String oddHeader, String oddFooter, String evenHeader, String evenFooter, String firstHeader,
        String firstFooter, boolean differentFirst, boolean differentOddEven, boolean scaleWithDoc,
        boolean alignWithMargins) {

    static final HeaderFooterSet NONE = new HeaderFooterSet(null, null, null, null, null, null, false, false, true,
            true);

    static HeaderFooterSet of(CTHeaderFooter hf) {
        if (hf == null) {
            return NONE;
        }
        return new HeaderFooterSet(text(hf.isSetOddHeader(), hf.getOddHeader()),
                text(hf.isSetOddFooter(), hf.getOddFooter()), text(hf.isSetEvenHeader(), hf.getEvenHeader()),
                text(hf.isSetEvenFooter(), hf.getEvenFooter()), text(hf.isSetFirstHeader(), hf.getFirstHeader()),
                text(hf.isSetFirstFooter(), hf.getFirstFooter()), hf.isSetDifferentFirst() && hf.getDifferentFirst(),
                hf.isSetDifferentOddEven() && hf.getDifferentOddEven(), !hf.isSetScaleWithDoc() || hf.getScaleWithDoc(),
                !hf.isSetAlignWithMargins() || hf.getAlignWithMargins());
    }

    private static String text(boolean set, String code) {
        return set ? RichText.unescape(code) : null;
    }

    String header(int pageInSheet, int pageNumber) {
        if (differentFirst && pageInSheet == 0) {
            return firstHeader;
        }
        if (differentOddEven && pageNumber % 2 == 0) {
            return evenHeader;
        }
        return oddHeader;
    }

    String variant(int pageInSheet, int pageNumber) {
        if (differentFirst && pageInSheet == 0) {
            return "FIRST";
        }
        return differentOddEven && pageNumber % 2 == 0 ? "EVEN" : "";
    }

    String footer(int pageInSheet, int pageNumber) {
        if (differentFirst && pageInSheet == 0) {
            return firstFooter;
        }
        if (differentOddEven && pageNumber % 2 == 0) {
            return evenFooter;
        }
        return oddFooter;
    }
}
