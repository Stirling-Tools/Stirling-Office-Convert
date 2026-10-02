package stirling.software.officeconvert.extract;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;

public final class GreyGroupPages {

    public static final int GREY = 6;

    private GreyGroupPages() {}

    public static PDDocument darkGreyInAnIsolatedGroup(COSName colourSpace) throws IOException {
        PDDocument doc = new PDDocument();
        PDPage page = new PDPage(new PDRectangle(200, 200));
        doc.addPage(page);
        COSDictionary group = new COSDictionary();
        group.setItem(COSName.S, COSName.TRANSPARENCY);
        group.setItem(COSName.CS, colourSpace);
        group.setBoolean(COSName.I, true);
        PDFormXObject form = new PDFormXObject(doc);
        form.setBBox(new PDRectangle(200, 200));
        form.getCOSObject().setItem(COSName.GROUP, group);
        try (OutputStream o = form.getContentStream().createOutputStream()) {
            o.write("0.0235 g 50 50 100 100 re f".getBytes(StandardCharsets.US_ASCII));
        }
        PDResources resources = new PDResources();
        resources.put(COSName.getPDFName("F0"), form);
        page.setResources(resources);
        PDStream s = new PDStream(doc);
        try (OutputStream o = s.createOutputStream()) {
            o.write("1 0 0 rg 0 0 200 200 re f /F0 Do".getBytes(StandardCharsets.US_ASCII));
        }
        page.setContents(s);
        return doc;
    }
}
