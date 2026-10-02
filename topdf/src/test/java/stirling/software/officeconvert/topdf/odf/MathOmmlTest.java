package stirling.software.officeconvert.topdf.odf;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;

class MathOmmlTest {

    private static String omml(String mathml) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        Element math = f.newDocumentBuilder().parse(new ByteArrayInputStream(
                ("<math xmlns=\"" + MathOmml.MATHML + "\">" + mathml + "</math>").getBytes(StandardCharsets.UTF_8)))
                .getDocumentElement();
        return MathOmml.omml(math);
    }

    @Test
    void fencedArgumentsAreSeparatedByCommasUnlessTheSeparatorsSayOtherwise() throws Exception {
        String comma = omml("<mi>f</mi><mfenced><mi>x</mi><mi>y</mi></mfenced>");
        assertTrue(comma.contains("<m:sepChr m:val=\",\"/>"), comma);
        String semi = omml("<mfenced separators=\" ; \"><mi>x</mi><mi>y</mi></mfenced>");
        assertTrue(semi.contains("<m:sepChr m:val=\";\"/>"), semi);
    }
}
