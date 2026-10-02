package stirling.software.officeconvert.pdfa;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentCatalog;
import org.apache.pdfbox.pdmodel.PDPage;

import stirling.software.officeconvert.extract.PdfFiles;

final class Interactive {

    private final PDDocument doc;

    private final PdfALevel level;

    private final Report report;

    private Interactive(PDDocument doc, PdfALevel level, Report report) {
        this.doc = doc;
        this.level = level;
        this.report = report;
    }

    static void run(PDDocument doc, PdfALevel level, Report report) throws IOException {
        Interactive i = new Interactive(doc, level, report);
        i.catalog();
        i.outlines();
        i.form();
        for (PDPage page : doc.getPages()) {
            PdfFiles.stopIfInterrupted();
            COSDictionary p = page.getCOSObject();
            if (p.containsKey(COSName.AA)) {
                p.removeItem(COSName.AA);
                report.warn("Removed page actions, which PDF/A does not allow");
            }
            p.removeItem(COSName.getPDFName("PresSteps"));
            Annotations.run(doc, page, level, report);
        }
    }

    private void catalog() throws IOException {
        PDDocumentCatalog cat = doc.getDocumentCatalog();
        COSDictionary c = cat.getCOSObject();
        if (c.containsKey(COSName.AA)) {
            c.removeItem(COSName.AA);
            report.warn("Removed document actions, which PDF/A does not allow");
        }
        Actions.filterKey(c, COSName.OPEN_ACTION, level, report);
        c.removeItem(COSName.getPDFName("Requirements"));
        c.removeItem(COSName.getPDFName("NeedsRendering"));
        COSDictionary perms = ContentGraph.dict(c.getDictionaryObject(COSName.PERMS));
        if (perms != null) {
            for (COSName k : new ArrayList<>(perms.keySet())) {
                if (level.part() == 1 || !(k.getName().equals("UR3") || k.getName().equals("DocMDP"))) {
                    perms.removeItem(k);
                }
            }
            if (perms.size() == 0) {
                c.removeItem(COSName.PERMS);
            }
        }
        COSDictionary names = ContentGraph.dict(c.getDictionaryObject(COSName.NAMES));
        if (names != null) {
            if (names.containsKey(COSName.JAVA_SCRIPT)) {
                names.removeItem(COSName.JAVA_SCRIPT);
                report.warn("Removed document JavaScript, which PDF/A does not allow");
            }
            names.removeItem(COSName.getPDFName("AlternatePresentations"));
            names.removeItem(COSName.getPDFName("Renditions"));
        }
    }

    private void outlines() throws IOException {
        COSDictionary root = ContentGraph.dict(doc.getDocumentCatalog().getCOSObject().getDictionaryObject(COSName.OUTLINES));
        if (root == null) {
            return;
        }
        Set<COSDictionary> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        List<COSDictionary> stack = new ArrayList<>();
        stack.add(root);
        while (!stack.isEmpty() && seen.size() < 1_000_000) {
            COSDictionary item = stack.remove(stack.size() - 1);
            if (!seen.add(item)) {
                continue;
            }
            Actions.filterKey(item, COSName.A, level, report);
            COSDictionary first = ContentGraph.dict(item.getDictionaryObject(COSName.FIRST));
            COSDictionary next = ContentGraph.dict(item.getDictionaryObject(COSName.NEXT));
            if (first != null) {
                stack.add(first);
            }
            if (next != null) {
                stack.add(next);
            }
        }
    }

    private void form() throws IOException {
        COSDictionary acro = ContentGraph.dict(doc.getDocumentCatalog().getCOSObject().getDictionaryObject(COSName.ACRO_FORM));
        if (acro == null) {
            return;
        }
        if (acro.containsKey(COSName.XFA)) {
            acro.removeItem(COSName.XFA);
            report.warn("Removed the XFA form, which PDF/A does not allow; the AcroForm fields remain");
        }
        FormAppearances.run(doc, acro, report);
        COSArray fields = ContentGraph.array(acro.getDictionaryObject(COSName.FIELDS));
        Set<COSDictionary> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        List<COSDictionary> stack = new ArrayList<>();
        if (fields != null) {
            for (int i = 0; i < fields.size(); i++) {
                COSDictionary f = ContentGraph.dict(fields.getObject(i));
                if (f != null) {
                    stack.add(f);
                }
            }
        }
        while (!stack.isEmpty() && seen.size() < 1_000_000) {
            COSDictionary f = stack.remove(stack.size() - 1);
            if (!seen.add(f)) {
                continue;
            }
            if (f.containsKey(COSName.AA) || f.containsKey(COSName.A)) {
                f.removeItem(COSName.AA);
                f.removeItem(COSName.A);
                report.warn("Removed form field actions, which PDF/A does not allow");
            }
            COSArray kids = ContentGraph.array(f.getDictionaryObject(COSName.KIDS));
            if (kids != null) {
                for (int i = 0; i < kids.size(); i++) {
                    COSBase k = kids.getObject(i);
                    if (ContentGraph.dict(k) != null) {
                        stack.add(ContentGraph.dict(k));
                    }
                }
            }
        }
    }
}
