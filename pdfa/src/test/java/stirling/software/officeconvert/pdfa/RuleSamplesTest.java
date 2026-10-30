package stirling.software.officeconvert.pdfa;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class RuleSamplesTest {

    @TempDir
    Path dir;

    static Stream<Arguments> cases() {
        return RuleSamples.ALL.values().stream().flatMap(s -> Stream.of(PdfALevel.values())
                .filter(l -> !l.tagged() || s.tagged()).map(l -> Arguments.of(s.name(), l)));
    }

    @ParameterizedTest(name = "{0} to {1}")
    @MethodSource("cases")
    void eachSampleBreaksItsRulesAndConvertsToACompliantFile(String name, PdfALevel level) throws Exception {
        Path in = RuleSamples.write(dir, name);
        Set<String> expected = new TreeSet<>();
        Set<String> profile = VeraPdf.rules(level);
        for (String r : RuleSamples.ALL.get(name).rules()) {
            String[] parts = r.split(":");
            boolean applies = switch (parts[0]) {
                case "1" -> level.part() == 1;
                case "2" -> level.part() == 2 || level.part() == 3;
                default -> parts[0].equals(String.valueOf(level.part()));
            };
            if (applies && profile.contains(parts[1])) {
                expected.add(parts[1]);
            }
        }
        Map<String, Integer> before = expected.isEmpty() ? Map.of() : VeraPdf.failures(in, level);
        Set<String> missing = new TreeSet<>(expected);
        missing.removeAll(before.keySet());
        assertTrue(missing.isEmpty(), "the input does not break " + missing + " for " + level + ": " + before);
        Path out = dir.resolve(name + "-" + level + ".pdf");
        PdfToPdfA.convert(in, out, PdfToPdfA.Options.defaults().level(level));
        VeraPdf.assertCompliant(out, level);
    }
}
