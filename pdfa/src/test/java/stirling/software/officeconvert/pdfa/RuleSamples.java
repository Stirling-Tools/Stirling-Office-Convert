package stirling.software.officeconvert.pdfa;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.apache.pdfbox.pdmodel.PDDocument;

final class RuleSamples {

    interface Source {
        byte[] make() throws Exception;
    }

    record Sample(String name, Set<String> rules, boolean tagged, Source source) {}

    static final Map<String, Sample> ALL = new LinkedHashMap<>();

    static {
        SyntaxSamples.register();
        ColourSamples.register();
        ObjectSamples.register();
    }

    private RuleSamples() {}

    static void raw(String name, Set<String> rules, Source source) {
        ALL.put(name, new Sample(name, rules, false, source));
    }

    static void doc(String name, Set<String> rules, Samples.Body body) {
        ALL.put(name, new Sample(name, rules, false, () -> save(body)));
    }

    static void tagged(String name, Set<String> rules, Samples.Body body) {
        ALL.put(name, new Sample(name, rules, true, () -> save(body)));
    }

    static byte[] save(Samples.Body body) throws Exception {
        try (PDDocument d = new PDDocument()) {
            body.make(d);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            d.save(out);
            return out.toByteArray();
        }
    }

    static Path write(Path dir, String name) throws Exception {
        Path p = dir.resolve(name + ".pdf");
        Files.write(p, ALL.get(name).source().make());
        return p;
    }
}
