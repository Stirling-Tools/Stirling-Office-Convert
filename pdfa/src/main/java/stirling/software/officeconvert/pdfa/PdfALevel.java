package stirling.software.officeconvert.pdfa;

import java.util.Locale;

public enum PdfALevel {
    A1B(1, "B"),
    A2B(2, "B"),
    A2U(2, "U"),
    A3B(3, "B"),
    A3U(3, "U");

    private final int part;

    private final String conformance;

    PdfALevel(int part, String conformance) {
        this.part = part;
        this.conformance = conformance;
    }

    public int part() {
        return part;
    }

    public String conformance() {
        return conformance;
    }

    public boolean unicode() {
        return "U".equals(conformance);
    }

    public float pdfVersion() {
        return part == 1 ? 1.4f : 1.7f;
    }

    public String label() {
        return "PDF/A-" + part + conformance.toLowerCase(Locale.ROOT);
    }

    public static PdfALevel parse(String text) {
        String s = text == null ? "" : text.strip().toLowerCase(Locale.ROOT).replace("pdf/a-", "").replace("pdfa-", "")
                .replace("pdfa", "").replace("-", "");
        return switch (s) {
            case "1b", "1" -> A1B;
            case "2b", "2" -> A2B;
            case "2u" -> A2U;
            case "3b", "3" -> A3B;
            case "3u" -> A3U;
            case "1a", "2a", "3a" -> throw new IllegalArgumentException(
                    "PDF/A level a needs a tagged structure tree, which this converter does not build; use b or u");
            default -> throw new IllegalArgumentException(
                    "Unknown PDF/A level '" + text + "': use 1b, 2b, 2u, 3b or 3u");
        };
    }
}
