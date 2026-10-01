package stirling.software.officeconvert.pdfa;

import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;

final class Signatures {

    private static final COSName SIG_FLAGS = COSName.getPDFName("SigFlags");

    private Signatures() {}

    static void run(PDDocument doc, Census census, Report report) {
        List<COSDictionary> owners = new ArrayList<>();
        for (COSDictionary d : census.valued) {
            if (signature(d.getDictionaryObject(COSName.V))) {
                owners.add(d);
            }
        }
        COSDictionary cat = doc.getDocumentCatalog().getCOSObject();
        COSDictionary perms = ContentGraph.dict(cat.getDictionaryObject(COSName.PERMS));
        boolean signedPerms = false;
        if (perms != null) {
            for (COSBase v : perms.getValues()) {
                signedPerms |= signature(v);
            }
        }
        if (owners.isEmpty() && !signedPerms) {
            return;
        }
        for (COSDictionary d : owners) {
            d.removeItem(COSName.V);
        }
        cat.removeItem(COSName.PERMS);
        COSDictionary acro = ContentGraph.dict(cat.getDictionaryObject(COSName.ACRO_FORM));
        if (acro != null) {
            acro.removeItem(SIG_FLAGS);
        }
        report.warn("Removed digital signatures, which no longer match the file once it is rewritten as PDF/A");
    }

    private static boolean signature(COSBase v) {
        COSDictionary d = ContentGraph.dict(v);
        return d != null && (COSName.SIG.equals(d.getCOSName(COSName.TYPE))
                || COSName.DOC_TIME_STAMP.equals(d.getCOSName(COSName.TYPE)) || d.containsKey(COSName.BYTERANGE));
    }
}
