package stirling.software.officeconvert.topdf.vsdx;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import stirling.software.officeconvert.topdf.testing.NoNetwork;

public final class VisioHostile {

    private VisioHostile() {}

    public static byte[] build(NoNetwork net) {
        String shape = "<Shape ID='1' Type='Shape' Master='7'><Cell N='PinX' V='4'/><Cell N='PinY' V='2'/>"
                + "<Section N='Hyperlink'><Row N='Row_1'><Cell N='Address' V='" + net.url("link") + "'/></Row>"
                + "</Section></Shape><Shape ID='2' Type='Foreign'><Cell N='PinX' V='2'/><Cell N='PinY' V='1'/>"
                + "<Cell N='Width' V='1'/><Cell N='Height' V='1'/><ForeignData ForeignType='Bitmap'"
                + " CompressionType='PNG'><Rel r:id='rIdImg'/></ForeignData></Shape>";
        Map<String, String> parts = VsdxPackageTest.drawing(shape, VsdxPackageTest.MASTER);
        parts.put("visio/pages/_rels/page1.xml.rels", parts.get("visio/pages/_rels/page1.xml.rels").replace(
                "</Relationships>", "<Relationship Id='rIdImg' Type='http://schemas.openxmlformats.org/officeDocument/"
                        + "2006/relationships/image' Target='" + net.url("linked.png") + "' TargetMode='External'/>"
                        + "<Relationship Id='rIdOle' Type='http://schemas.openxmlformats.org/officeDocument/2006/"
                        + "relationships/oleObject' Target='" + net.fileUrl("object.xlsx")
                        + "' TargetMode='External'/></Relationships>"));
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream(); ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, String> e : parts.entrySet()) {
                zip.putNextEntry(new ZipEntry(e.getKey()));
                zip.write(("<?xml version='1.0' encoding='utf-8'?>" + e.getValue()).getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            zip.finish();
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
