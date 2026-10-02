package stirling.software.officeconvert.topdf.text;

import java.util.List;

public record Converted(List<String> warnings, boolean lost) {
    public Converted {
        warnings = List.copyOf(warnings);
    }
}
