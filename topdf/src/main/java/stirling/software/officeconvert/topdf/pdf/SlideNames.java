package stirling.software.officeconvert.topdf.pdf;

import java.util.HashSet;
import java.util.Set;

public final class SlideNames {

    private final Set<String> taken = new HashSet<>();

    public String name(String title, int position) {
        String name = title == null ? "" : title.replaceAll("\\s+", " ").strip();
        if (name.isEmpty()) {
            name = "Slide " + position;
        }
        String unique = name;
        for (int k = 2; !taken.add(unique); k++) {
            unique = name + " (" + k + ")";
        }
        return unique;
    }
}
