package stirling.software.officeconvert.pdfa;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;
import org.apache.pdfbox.pdmodel.interactive.form.PDField;
import org.apache.pdfbox.pdmodel.interactive.form.PDVariableText;

import stirling.software.officeconvert.extract.PdfFiles;

final class FormAppearances {

    private static final Pattern FONT = Pattern.compile("(/[^\\s/\\[\\]()<>{}%]*)\\s+([-+]?[0-9]*\\.?[0-9]+)\\s+Tf");

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
        Set<PDField> failed = Collections.newSetFromMap(new IdentityHashMap<>());
        for (PDField field : missing) {
            if (!refresh(form, field)) {
                failed.add(field);
            }
        }
        for (PDField field : missing) {
            if (!field.getValueAsString().isEmpty() && (failed.contains(field)
                    || field.getWidgets().stream().anyMatch(widget -> widget.getNormalAppearanceStream() == null))) {
                throw new IOException("A form field's value could not be given a visible appearance");
            }
        }
        report.warn("Generated form appearances from the stored text and choice values");
    }

    private static boolean refresh(PDAcroForm form, PDField field) throws IOException {
        try {
            form.refreshAppearances(List.of(field));
            return true;
        } catch (IOException | IllegalArgumentException | IllegalStateException e) {
            PdfFiles.stopIfInterrupted();
        }
        if (!(field instanceof PDVariableText text)) {
            return false;
        }
        text.setDefaultAppearance(withStandardFont(text.getDefaultAppearance()));
        for (PDAnnotationWidget widget : field.getWidgets()) {
            if (widget.getCOSObject() != field.getCOSObject()) {
                widget.getCOSObject().removeItem(COSName.DA);
            }
        }
        try {
            form.refreshAppearances(List.of(field));
            return true;
        } catch (IOException | IllegalArgumentException | IllegalStateException e) {
            PdfFiles.stopIfInterrupted();
            return false;
        }
    }

    static String withStandardFont(String appearance) {
        String da = appearance == null ? "" : appearance;
        Matcher m = FONT.matcher(da);
        if (m.find()) {
            return da.substring(0, m.start()) + "/Helv " + m.group(2) + " Tf" + da.substring(m.end());
        }
        return da.isBlank() ? "/Helv 0 Tf 0 g" : "/Helv 0 Tf " + da.strip();
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
