package stirling.software.officeconvert.cli;

enum Mode {
    CONVERT(null, null, null),
    PDFA("--pdfa", "pdfa.pdf", "converts whole PDFs to PDF/A"),
    LINE_ART("--line-art", "lineart.pdf", "rewrites the pictures of whole PDFs"),
    OPTIMIZE("--optimize", "optimized.pdf", "rewrites whole PDFs");

    private final String flag;
    private final String suffix;
    private final String purpose;

    Mode(String flag, String suffix, String purpose) {
        this.flag = flag;
        this.suffix = suffix;
        this.purpose = purpose;
    }

    String flag() {
        return flag;
    }

    String suffix() {
        return suffix;
    }

    String purpose() {
        return purpose;
    }

    boolean rewritesPdf() {
        return this != CONVERT;
    }
}
