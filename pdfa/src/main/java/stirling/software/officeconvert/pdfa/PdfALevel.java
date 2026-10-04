package stirling.software.officeconvert.pdfa;

import java.util.Locale;

public enum PdfALevel {
    A1A(1, "A"),
    A1B(1, "B"),
    A2A(2, "A"),
    A2B(2, "B"),
    A2U(2, "U"),
    A3A(3, "A"),
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
        return !"B".equals(conformance);
    }

    public boolean tagged() {
        return "A".equals(conformance);
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
            case "1a" -> A1A;
            case "2a" -> A2A;
            case "3a" -> A3A;
            default -> throw new IllegalArgumentException(
                    "Unknown PDF/A level '" + text + "': use 1a, 1b, 2a, 2b, 2u, 3a, 3b or 3u");
        };
    }
}
