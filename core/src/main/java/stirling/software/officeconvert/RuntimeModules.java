package stirling.software.officeconvert;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

public final class RuntimeModules {

    public static final List<String> REQUIRED = List.of("java.desktop", "java.xml", "java.management");

    public static final List<String> RECOMMENDED = List.of("jdk.charsets", "jdk.localedata");

    private static final Map<String, String> LOST_WITHOUT = Map.of(
            "jdk.charsets", "documents in Big5, Shift_JIS, EUC and other legacy encodings cannot be read",
            "jdk.localedata", "month and day names and Thai line breaking fall back to English rules");

    private static final String ADD = "jlink --add-modules " + String.join(",", REQUIRED) + ","
            + String.join(",", RECOMMENDED);

    private static final AtomicBoolean LOGGED = new AtomicBoolean();

    private static final class Boot {
        static final Report REPORT = inspect(name -> ModuleLayer.boot().findModule(name).isPresent());
    }

    public record Report(List<String> missingRequired, List<String> missingRecommended) {

        public Report {
            missingRequired = List.copyOf(missingRequired);
            missingRecommended = List.copyOf(missingRecommended);
        }

        public void check() {
            if (!missingRequired.isEmpty()) {
                throw new IllegalStateException("This Java runtime lacks the module"
                        + (missingRequired.size() == 1 ? " " : "s ") + String.join(", ", missingRequired)
                        + ", which Stirling Office Convert needs; build the runtime with " + ADD);
            }
        }

        public List<String> warnings() {
            List<String> out = new ArrayList<>();
            for (String m : missingRecommended) {
                out.add("This Java runtime lacks the module " + m + ", so " + LOST_WITHOUT.get(m)
                        + "; add it with jlink --add-modules " + m);
            }
            return out;
        }
    }

    private RuntimeModules() {}

    public static Report inspect(Predicate<String> present) {
        Objects.requireNonNull(present, "present");
        List<String> required = new ArrayList<>();
        for (String m : REQUIRED) {
            if (!present.test(m)) {
                required.add(m);
            }
        }
        List<String> recommended = new ArrayList<>();
        for (String m : RECOMMENDED) {
            if (!present.test(m)) {
                recommended.add(m);
            }
        }
        return new Report(required, recommended);
    }

    public static void check() {
        Report r = Boot.REPORT;
        r.check();
        if (!r.missingRecommended().isEmpty() && LOGGED.compareAndSet(false, true)) {
            System.Logger log = System.getLogger(RuntimeModules.class.getName());
            for (String w : r.warnings()) {
                log.log(System.Logger.Level.WARNING, w);
            }
        }
    }

    public static List<String> warnings() {
        return Boot.REPORT.warnings();
    }
}
