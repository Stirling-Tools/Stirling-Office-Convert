package stirling.software.officeconvert.pdfa;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CMapUseCMapTest {

    @ParameterizedTest
    @ValueSource(strings = {" ", "\n", "\t", "\r\n", " \t\n "})
    void predefinedCodespaceIsFoundWhateverWhitespaceSeparatesUsecmap(String separator) throws IOException {
        String text = "/UniGB-UTF16-H" + separator + "usecmap\n";
        assertTrue(CMapFixer.withPredefined(text).contains("begincodespacerange"));
    }
}
