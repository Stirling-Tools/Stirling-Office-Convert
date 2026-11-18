package stirling.software.officeconvert.pdfa;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class Type1LenientTest {

    @Test
    void refusesACharStringLengthThatOverflowsTheProgram() {
        byte[] clear = "%!PS-AdobeFont-1.0: Test\n/FontName /Test def\n".getBytes(StandardCharsets.ISO_8859_1);
        byte[] plain = "/CharStrings 1 dict dup begin\n/A 2147483640 RD xxxx ND\nend\n"
                .getBytes(StandardCharsets.ISO_8859_1);

        assertThrows(IOException.class, () -> Type1Lenient.parse(clear, FontPrograms.eexec(plain)));
    }
}
