package stirling.software.officeconvert.topdf.dml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.geom.Rectangle2D;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;

import stirling.software.officeconvert.topdf.io.SecureXml;

class DmlShapesTest {

    @Test
    void presetsAndCustomPathsKeepTheirOutline() throws Exception {
        Rectangle2D box = new Rectangle2D.Double(100, 100, 200, 100);
        List<DmlShapes.Outline> arrow = DmlShapes.preset("rightArrow", Map.of(), box);
        assertTrue(arrow.get(0).shape().contains(200, 150));
        assertFalse(arrow.get(0).shape().contains(295, 105));
        List<DmlShapes.Outline> star = DmlShapes.preset("star5", Map.of(), box);
        assertFalse(star.get(0).shape().contains(102, 102));
        assertNull(DmlShapes.preset("noSuchShape", Map.of(), box));
        String xml = "<a:custGeom xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\"><a:pathLst>"
                + "<a:path w=\"100\" h=\"100\"><a:moveTo><a:pt x=\"0\" y=\"100\"/></a:moveTo><a:lnTo><a:pt x=\"50\""
                + " y=\"0\"/></a:lnTo><a:lnTo><a:pt x=\"100\" y=\"100\"/></a:lnTo><a:close/></a:path></a:pathLst>"
                + "</a:custGeom>";
        Element geom = SecureXml.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)))
                .getDocumentElement();
        List<DmlShapes.Outline> tri = DmlShapes.custom(geom, box);
        assertTrue(tri.get(0).shape().contains(200, 190));
        assertFalse(tri.get(0).shape().contains(105, 105));
    }

    @Test
    void adjustValuesReshapeThePreset() {
        Rectangle2D box = new Rectangle2D.Double(100, 100, 200, 100);
        assertFalse(DmlShapes.preset("rightArrow", Map.of(), box).get(0).shape().contains(150, 105));
        List<DmlShapes.Outline> wide = DmlShapes.preset("rightArrow", Map.of("adj1", 100000L), box);
        assertTrue(wide.get(0).shape().contains(150, 105));
    }

    @Test
    void colourNamesAndTransformsMatchDrawingMl() {
        assertEquals(new Color(0x000080), DmlColors.preset("navy"));
        assertEquals(new Color(0xFF0000), DmlColors.hsl(0, 100000, 50000));
        assertEquals(128, DmlColors.modify(Color.RED, "alphaMod", 50000).getAlpha());
        assertEquals(new Color(0x7F7F7F).getRed(), DmlColors.modify(Color.WHITE, "lumMod", 50000).getRed(), 1);
    }
}
