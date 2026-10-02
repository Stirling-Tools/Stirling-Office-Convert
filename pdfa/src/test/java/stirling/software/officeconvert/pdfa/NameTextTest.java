package stirling.software.officeconvert.pdfa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class NameTextTest {

    @Test
    void namesAreReadAsUtf8OrALegacyCodePageAndLatinLettersLoseTheirMarks() {
        byte[] euckr = {(byte) 0xC7, (byte) 0xD1, (byte) 0xC4, (byte) 0xC4};
        assertFalse(NameText.accepted(euckr));
        assertEquals("한컴", NameText.repair(euckr));
        byte[] cafe = "Café".getBytes(StandardCharsets.UTF_8);
        assertFalse(NameText.accepted(cafe));
        assertEquals("Cafe", NameText.repair(cafe));
        assertTrue(NameText.accepted("한컴 240".getBytes(StandardCharsets.UTF_8)));
        assertTrue(NameText.accepted("Helvetica".getBytes(StandardCharsets.US_ASCII)));
    }
}
