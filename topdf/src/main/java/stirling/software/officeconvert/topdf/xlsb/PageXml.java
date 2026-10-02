package stirling.software.officeconvert.topdf.xlsb;

import stirling.software.officeconvert.topdf.xls.Xml;

/** The print settings a worksheet or chart sheet records (margins, page setup, options, header and footer). */
final class PageXml {

    private PageXml() {}

    static String margins(Data d) {
        double[] m = new double[6];
        for (int i = 0; i < 6; i++) {
            m[i] = d.f64();
            if (!Double.isFinite(m[i]) || m[i] < 0 || m[i] > 100) {
                return "";
            }
        }
        return "<pageMargins left=\"" + m[0] + "\" right=\"" + m[1] + "\" top=\"" + m[2] + "\" bottom=\"" + m[3]
                + "\" header=\"" + m[4] + "\" footer=\"" + m[5] + "\"/>";
    }

    static String printOptions(Data d) {
        int f = d.u16();
        if ((f & 0x0F) == 0) {
            return "";
        }
        return "<printOptions" + ((f & 0x01) != 0 ? " horizontalCentered=\"1\"" : "")
                + ((f & 0x02) != 0 ? " verticalCentered=\"1\"" : "") + ((f & 0x04) != 0 ? " headings=\"1\"" : "")
                + ((f & 0x08) != 0 ? " gridLines=\"1\"" : "") + "/>";
    }

    static String pageSetup(Data d) {
        int paper = d.i32();
        int scale = d.i32();
        int hRes = d.i32();
        int vRes = d.i32();
        d.i32();
        int first = d.i32();
        int fitWidth = d.i32();
        int fitHeight = d.i32();
        int f = d.u16();
        boolean valid = (f & 0x0004) == 0;
        StringBuilder b = new StringBuilder("<pageSetup");
        if (valid && paper > 0 && paper < 256) {
            b.append(" paperSize=\"").append(paper).append('"');
        }
        if (valid && scale >= 10 && scale <= 400) {
            b.append(" scale=\"").append(scale).append('"');
        }
        if ((f & 0x0080) != 0) {
            b.append(" firstPageNumber=\"").append(first).append("\" useFirstPageNumber=\"1\"");
        }
        if (fitWidth >= 0 && fitWidth < 32768) {
            b.append(" fitToWidth=\"").append(fitWidth).append('"');
        }
        if (fitHeight >= 0 && fitHeight < 32768) {
            b.append(" fitToHeight=\"").append(fitHeight).append('"');
        }
        if ((f & 0x0001) != 0) {
            b.append(" pageOrder=\"overThenDown\"");
        }
        if (valid && (f & 0x0040) == 0) {
            b.append(" orientation=\"").append((f & 0x0002) != 0 ? "landscape" : "portrait").append('"');
        }
        if ((f & 0x0008) != 0) {
            b.append(" blackAndWhite=\"1\"");
        }
        if ((f & 0x0010) != 0) {
            b.append(" draft=\"1\"");
        }
        if ((f & 0x0020) != 0) {
            b.append(" cellComments=\"").append((f & 0x0100) != 0 ? "atEnd" : "asDisplayed").append('"');
        }
        int errors = f >> 9 & 0x03;
        if (errors != 0) {
            b.append(" errors=\"").append(new String[] {"displayed", "blank", "dash", "NA"}[errors]).append('"');
        }
        if (hRes > 0 && hRes < 10_000) {
            b.append(" horizontalDpi=\"").append(hRes).append('"');
        }
        if (vRes > 0 && vRes < 10_000) {
            b.append(" verticalDpi=\"").append(vRes).append('"');
        }
        return b.append("/>").toString();
    }

    static String chartPageSetup(Data d) {
        int paper = d.i32();
        d.i32();
        d.i32();
        d.i32();
        int first = d.u16();
        int f = d.u16();
        if ((f & 0x0002) != 0) {
            return "";
        }
        StringBuilder b = new StringBuilder("<pageSetup");
        if (paper > 0 && paper < 256) {
            b.append(" paperSize=\"").append(paper).append('"');
        }
        if ((f & 0x0010) != 0) {
            b.append(" firstPageNumber=\"").append(first).append("\" useFirstPageNumber=\"1\"");
        }
        if ((f & 0x0008) == 0) {
            b.append(" orientation=\"").append((f & 0x0001) != 0 ? "landscape" : "portrait").append('"');
        }
        return b.append("/>").toString();
    }

    static String headerFooter(Data d) {
        int f = d.u16();
        String[] names = {"oddHeader", "oddFooter", "evenHeader", "evenFooter", "firstHeader", "firstFooter"};
        StringBuilder b = new StringBuilder("<headerFooter");
        if ((f & 0x0001) != 0) {
            b.append(" differentOddEven=\"1\"");
        }
        if ((f & 0x0002) != 0) {
            b.append(" differentFirst=\"1\"");
        }
        if ((f & 0x0004) == 0) {
            b.append(" scaleWithDoc=\"0\"");
        }
        if ((f & 0x0008) == 0) {
            b.append(" alignWithMargins=\"0\"");
        }
        b.append('>');
        for (String n : names) {
            String s = d.string();
            if (!s.isEmpty()) {
                b.append('<').append(n).append('>').append(Xml.attr(s)).append("</").append(n).append('>');
            }
        }
        return b.append("</headerFooter>").toString();
    }
}
