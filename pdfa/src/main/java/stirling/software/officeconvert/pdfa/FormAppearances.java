package stirling.software.officeconvert.pdfa;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDField;

import stirling.software.officeconvert.extract.PdfFiles;

final class FormAppearances {

    private FormAppearances() {}

    static void run(PDDocument doc, COSDictionary dictionary, Report report) throws IOException {
        boolean refresh = dictionary.getBoolean(COSName.NEED_APPEARANCES, false);
        dictionary.removeItem(COSName.NEED_APPEARANCES);
        if (!refresh && !missingText(dictionary)) {
            return;
        }
        AppearanceBudget.check(dictionary);
        checkFields(dictionary);
        PDAcroForm form = new PDAcroForm(doc, dictionary);
        List<PDField> missing = new ArrayList<>();
        for (PDField field : form.getFieldTree()) {
            PdfFiles.stopIfInterrupted();
            if (("Tx".equals(field.getFieldType()) || "Ch".equals(field.getFieldType()))
                    && (refresh || field.getWidgets().stream().anyMatch(widget -> widget.getNormalAppearanceStream() == null))) {
                missing.add(field);
            }
        }
        if (missing.isEmpty()) {
            return;
        }
        PDResources resources = form.getDefaultResources();
        if (resources == null) {
            resources = new PDResources();
            form.setDefaultResources(resources);
        }
        COSName helv = COSName.getPDFName("Helv");
        if (resources.getFont(helv) == null) {
            resources.put(helv, new PDType1Font(Standard14Fonts.FontName.HELVETICA));
        }
        if (form.getDefaultAppearance() == null || form.getDefaultAppearance().isBlank()) {
            form.setDefaultAppearance("/Helv 0 Tf 0 g");
        }
        form.refreshAppearances(missing);
        for (PDField field : missing) {
            if (!field.getValueAsString().isEmpty()
                    && field.getWidgets().stream().anyMatch(widget -> widget.getNormalAppearanceStream() == null)) {
                throw new IOException("A form field's value could not be given a visible appearance");
            }
        }
        report.warn("Generated form appearances from the stored text and choice values");
    }

    private static boolean missingText(COSDictionary form) throws IOException {
        List<COSDictionary> stack = new ArrayList<>();
        push(ContentGraph.array(form.getDictionaryObject(COSName.FIELDS)), stack);
        Set<COSDictionary> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        while (!stack.isEmpty()) {
            PdfFiles.stopIfInterrupted();
            COSDictionary field = stack.removeLast();
            if (!seen.add(field)) {
                continue;
            }
            if (seen.size() > 100_000) {
                throw new IOException("A form field tree is too large to inspect safely");
            }
            org.apache.pdfbox.cos.COSBase type = ButtonAppearances.inherited(field, COSName.FT);
            COSDictionary ap = ContentGraph.dict(field.getDictionaryObject(COSName.AP));
            if (field.containsKey(COSName.RECT) && (COSName.TX.equals(type) || COSName.CH.equals(type))
                    && (ap == null || ap.getDictionaryObject(COSName.N) == null)) {
                return true;
            }
            push(ContentGraph.array(field.getDictionaryObject(COSName.KIDS)), stack);
        }
        return false;
    }

    private static void checkFields(COSDictionary form) throws IOException {
        COSArray fields = ContentGraph.array(form.getDictionaryObject(COSName.FIELDS));
        List<COSDictionary> stack = new ArrayList<>();
        push(fields, stack);
        Set<COSDictionary> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        while (!stack.isEmpty()) {
            PdfFiles.stopIfInterrupted();
            COSDictionary field = stack.removeLast();
            if (!seen.add(field) || seen.size() > 10_000) {
                throw new IOException("A form field tree is recursive, shared, or too large to refresh safely");
            }
            push(ContentGraph.array(field.getDictionaryObject(COSName.KIDS)), stack);
        }
    }

    private static void push(COSArray fields, List<COSDictionary> stack) {
        for (int i = 0; fields != null && i < fields.size(); i++) {
            COSDictionary field = ContentGraph.dict(fields.getObject(i));
            if (field != null) {
                stack.add(field);
            }
        }
    }
}
