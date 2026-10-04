package stirling.software.officeconvert.pdfa;

import java.nio.file.Path;
import java.util.stream.Stream;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class SamplesComplianceTest {

    @TempDir
    Path dir;

    static Stream<Arguments> cases() {
        return Samples.ALL.keySet().stream().filter(n -> !n.equals("s06_encrypted_user"))
                .flatMap(n -> Stream.of(PdfALevel.values()).filter(l -> !l.tagged() || n.equals("s21_tagged"))
                        .map(l -> Arguments.of(n, l)));
    }

    @ParameterizedTest(name = "{0} to {1}")
    @MethodSource("cases")
    void everySampleConvertsToACompliantFile(String name, PdfALevel level) throws Exception {
        Path in = Samples.write(dir, name);
        Path out = dir.resolve(name + "-" + level + ".pdf");
        PdfToPdfA.convert(in, out, PdfToPdfA.Options.defaults().level(level));
        VeraPdf.assertCompliant(out, level);
    }
}
