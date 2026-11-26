package stirling.software.officeconvert.lineart;

import java.io.IOException;

import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDField;
import org.apache.pdfbox.pdmodel.interactive.form.PDSignatureField;

final class Signed {

    private static final COSName BYTE_RANGE = COSName.getPDFName("ByteRange");

    private Signed() {}

    static boolean is(PDDocument document) throws IOException {
        COSDictionary catalog = document.getDocumentCatalog().getCOSObject();
        if (catalog.getDictionaryObject(COSName.PERMS) instanceof COSDictionary perms) {
            for (COSBase value : perms.getValues()) {
                if (signature(value)) {
                    return true;
                }
            }
        }
        PDAcroForm form = document.getDocumentCatalog().getAcroForm(null);
        if (form == null) {
            return false;
        }
        for (PDField field : form.getFieldTree()) {
            LineArt.checkpoint();
            if (field instanceof PDSignatureField && signature(field.getCOSObject().getDictionaryObject(COSName.V))) {
                return true;
            }
        }
        return false;
    }

    private static boolean signature(COSBase value) {
        COSBase direct = value instanceof COSObject reference ? reference.getObject() : value;
        return direct instanceof COSDictionary d
                && (COSName.SIG.equals(d.getCOSName(COSName.TYPE)) || d.containsKey(BYTE_RANGE));
    }
}
