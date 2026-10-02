package stirling.software.officeconvert.pdfa;

import java.io.IOException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;

final class MovedPageTags {

    private static final COSName MCR = COSName.getPDFName("MCR");

    private static final COSName STM = COSName.getPDFName("Stm");

    private MovedPageTags() {}

    static void run(PDDocument doc, COSDictionary page, COSStream form) throws IOException {
        COSBase parents = page.getDictionaryObject(COSName.STRUCT_PARENTS);
        if (parents == null) {
            return;
        }
        form.setItem(COSName.STRUCT_PARENTS, parents);
        page.removeItem(COSName.STRUCT_PARENTS);
        CosWalk.walk(doc, object -> {
            if (!(object instanceof COSDictionary element)) {
                return;
            }
            if (MCR.equals(element.getCOSName(COSName.TYPE)) && here(element) == page
                    && element.getDictionaryObject(STM) == null) {
                element.setItem(STM, form);
            }
            if (COSName.STRUCT_ELEM.equals(element.getCOSName(COSName.TYPE)) && here(element) == page) {
                COSBase children = element.getDictionaryObject(COSName.K);
                if (children != null) {
                    element.setItem(COSName.K, moved(children, page, form,
                            Collections.newSetFromMap(new IdentityHashMap<>()), 0));
                }
            }
        });
    }

    private static COSDictionary here(COSDictionary element) {
        for (int depth = 0; element != null && depth < 512; depth++) {
            COSDictionary page = ContentGraph.dict(element.getDictionaryObject(COSName.PG));
            if (page != null) {
                return page;
            }
            element = ContentGraph.dict(element.getDictionaryObject(COSName.P));
        }
        return null;
    }

    private static COSBase moved(COSBase object, COSDictionary page, COSStream form, Set<COSArray> path, int depth)
            throws IOException {
        if (object instanceof COSNumber mcid) {
            COSDictionary reference = new COSDictionary();
            reference.setItem(COSName.TYPE, MCR);
            reference.setItem(COSName.PG, page);
            reference.setItem(STM, form);
            reference.setItem(COSName.MCID, mcid);
            return reference;
        }
        if (object instanceof COSArray array) {
            if (depth > 64 || !path.add(array)) {
                throw new IOException("A marked content reference array is recursive or too deeply nested");
            }
            try {
                COSArray copy = new COSArray();
                for (int i = 0; i < array.size(); i++) {
                    copy.add(moved(array.getObject(i), page, form, path, depth + 1));
                }
                return copy;
            } finally {
                path.remove(array);
            }
        }
        if (object instanceof COSDictionary reference && MCR.equals(reference.getCOSName(COSName.TYPE))
                && reference.getDictionaryObject(STM) == null) {
            COSDictionary target = ContentGraph.dict(reference.getDictionaryObject(COSName.PG));
            if (target == null || target == page) {
                reference.setItem(STM, form);
                reference.setItem(COSName.PG, page);
            }
        }
        return object;
    }
}
