package stirling.software.officeconvert.topdf.text;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

public final class TextFormats {

    public enum Kind {
        PLAIN,
        CSV,
        TSV
    }

    public static final Set<String> PLAIN_EXTENSIONS = Set.of("txt", "text", "log", "asc");

    public static final Set<String> DELIMITED_EXTENSIONS = Set.of("csv", "tsv", "tab");

    private TextFormats() {}

    public static Kind kind(Path file) {
        Path name = file.getFileName();
        String n = name == null ? "" : name.toString().toLowerCase(Locale.ROOT);
        int dot = n.lastIndexOf('.');
        return kind(dot < 0 ? "" : n.substring(dot + 1));
    }

    public static Kind kind(String extension) {
        String e = extension.toLowerCase(Locale.ROOT);
        if (PLAIN_EXTENSIONS.contains(e)) {
            return Kind.PLAIN;
        }
        return switch (e) {
            case "csv" -> Kind.CSV;
            case "tsv", "tab" -> Kind.TSV;
            default -> null;
        };
    }
}
