package stirling.software.officeconvert.pdfa;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDField;
import org.apache.pdfbox.pdmodel.interactive.form.PDSignatureField;

import stirling.software.officeconvert.extract.PdfFiles;

final class Signatures {

    private static final COSName SIG_FLAGS = COSName.getPDFName("SigFlags");

    private Signatures() {}

    static void run(PDDocument doc, Census census, Report report) throws IOException {
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
        flatten(doc, owners);
        for (COSDictionary d : owners) {
            d.removeItem(COSName.V);
        }
        cat.removeItem(COSName.PERMS);
        COSDictionary acro = ContentGraph.dict(cat.getDictionaryObject(COSName.ACRO_FORM));
        if (acro != null) {
            acro.removeItem(SIG_FLAGS);
        }
        report.warn("Removed digital signatures, which no longer match the file once it is rewritten as PDF/A, and "
                + "drew their appearances into the pages");
    }

    private static void flatten(PDDocument doc, List<COSDictionary> owners) throws IOException {
        PDAcroForm acro = doc.getDocumentCatalog().getAcroForm(null);
        if (acro == null) {
            return;
        }
        Set<COSDictionary> signed = Collections.newSetFromMap(new IdentityHashMap<>());
        signed.addAll(owners);
        List<PDField> fields = new ArrayList<>();
        try {
            for (PDField f : acro.getFieldTree()) {
                if (f instanceof PDSignatureField && signed.contains(f.getCOSObject())) {
                    fields.add(f);
                }
            }
            if (!fields.isEmpty()) {
                acro.flatten(fields, false);
            }
        } catch (IOException | RuntimeException e) {
            PdfFiles.stopIfInterrupted();
        }
    }

    private static boolean signature(COSBase v) {
        COSDictionary d = ContentGraph.dict(v);
        return d != null && (COSName.SIG.equals(d.getCOSName(COSName.TYPE))
                || COSName.DOC_TIME_STAMP.equals(d.getCOSName(COSName.TYPE)) || d.containsKey(COSName.BYTERANGE));
    }
}
