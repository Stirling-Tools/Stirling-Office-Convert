package stirling.software.officeconvert.topdf.wordml;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class NamesTest {

    @Test
    void binaryNumberFormatCodesMapToTheirOoxmlNames() {
        assertEquals("russianLower", Names.numberFormat("58"));
        assertEquals("hebrew2", Names.numberFormat("47"));
        assertEquals("hebrew1", Names.numberFormat("45"));
        assertEquals("thaiNumbers", Names.numberFormat("54"));
        assertEquals("none", Names.numberFormat("255"));
        assertEquals("decimal", Names.numberFormat("x"));
    }
}
