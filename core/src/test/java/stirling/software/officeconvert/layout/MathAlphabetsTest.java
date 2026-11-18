package stirling.software.officeconvert.layout;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import stirling.software.officeconvert.extract.FontInfo;

class MathAlphabetsTest {

    private static FontInfo font(String ps) {
        return new FontInfo("Cambria Math", false, false, true, false, true, ps, true);
    }

    @Test
    void boldEulerScriptCapitalsUseTheBoldScriptAlphabet() {
        assertEquals("𝓑", MathAlphabets.of("B", font("EUSB10")));
    }

    @Test
    void regularEulerScriptCapitalsKeepTheScriptAlphabet() {
        assertEquals("ℬ", MathAlphabets.of("B", font("EUSM10")));
    }
}
