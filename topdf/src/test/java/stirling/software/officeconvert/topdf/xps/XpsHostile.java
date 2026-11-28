package stirling.software.officeconvert.topdf.xps;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import stirling.software.officeconvert.topdf.testing.NoNetwork;

public final class XpsHostile {

    private XpsHostile() {}

    public static byte[] build(NoNetwork net) {
        return zip(parts(net, List.of()));
    }

    static Map<String, byte[]> parts(NoNetwork net, List<String> local) {
        StringBuilder body = new StringBuilder("<Canvas><Canvas.Resources><ResourceDictionary Source='")
                .append(net.url("dict.xaml")).append("'/></Canvas.Resources>");
        List<String> targets = new java.util.ArrayList<>(net.hostileTargets("x"));
        targets.addAll(local);
        targets.add("javascript:app.alert(1)");
        targets.add("file:///etc/passwd");
        targets.add("../../../../etc/passwd");
        int y = 40;
        for (String t : targets) {
            body.append("<Glyphs Fill='#FF000000' FontUri='").append(t).append("' FontRenderingEmSize='12' OriginX='10'")
                    .append(" OriginY='").append(y).append("' UnicodeString='Remote font' FixedPage.NavigateUri='")
                    .append(t).append("'/>");
            body.append("<Path Data='M 300,").append(y - 10).append(" l 20,0 0,10 -20,0 Z'><Path.Fill><ImageBrush ImageSource='")
                    .append(t).append("' Viewbox='0,0,10,10' ViewboxUnits='Absolute' Viewport='0,0,10,10'")
                    .append(" ViewportUnits='Absolute'/></Path.Fill></Path>");
            body.append("<Path Data='M 400,").append(y - 10).append(" l 20,0 0,10 -20,0 Z' Fill='ContextColor ")
                    .append(t.replace(' ', '_')).append(" 1,0.5,0.5,0.5'/>");
            y += 20;
        }
        body.append(XpsToPdfTest.glyphs("Hostile page", 10, y + 20, "")).append("</Canvas>");
        Map<String, byte[]> parts = XpsToPdfTest.xps(List.of(XpsToPdfTest.page(body.toString())));
        parts.put("_rels/.rels", XpsToPdfTest.utf8("<Relationships xmlns='http://schemas.openxmlformats.org/package/2006/"
                + "relationships'><Relationship Id='R1' Type='" + XpsPackage.XPS_REPRESENTATION + "' Target="
                + "'/FixedDocumentSequence.fdseq'/><Relationship Id='R2' Type='http://schemas.openxmlformats.org/package/"
                + "2006/relationships/metadata/thumbnail' Target='" + net.url("thumb.png") + "' TargetMode='External'/>"
                + "</Relationships>"));
        parts.put("FixedDocumentSequence.fdseq", XpsToPdfTest.utf8("<FixedDocumentSequence xmlns='" + XpsToPdfTest.NS
                + "'><DocumentReference Source='/Documents/1/FixedDocument.fdoc'/><DocumentReference Source='"
                + net.url("remote.fdoc") + "'/><DocumentReference Source='../../../outside.fdoc'/>"
                + "</FixedDocumentSequence>"));
        return parts;
    }

    static byte[] zip(Map<String, byte[]> parts) {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream(); ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, byte[]> e : parts.entrySet()) {
                zip.putNextEntry(new ZipEntry(e.getKey()));
                zip.write(e.getValue());
                zip.closeEntry();
            }
            zip.finish();
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static byte[] text(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
