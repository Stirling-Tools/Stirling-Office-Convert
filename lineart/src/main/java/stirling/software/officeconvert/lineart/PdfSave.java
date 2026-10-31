package stirling.software.officeconvert.lineart;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSDocument;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.pdfwriter.compress.CompressParameters;
import org.apache.pdfbox.pdmodel.PDDocument;

final class PdfSave {

    static final long MIN_VISITS = 1_000_000;

    static final long VISITS_PER_OBJECT = 64;

    private PdfSave() {}

    static void save(PDDocument document, OutputStream out) throws IOException {
        document.save(out, poolStaysBounded(document.getDocument())
                ? CompressParameters.DEFAULT_COMPRESSION : CompressParameters.NO_COMPRESSION);
    }

    static boolean poolStaysBounded(COSDocument document) {
        long limit = Math.max(MIN_VISITS, VISITS_PER_OBJECT * document.getXrefTable().size());
        Set<COSBase> pooled = identitySet();
        Set<COSBase> direct = identitySet();
        List<COSBase> level = new ArrayList<>();
        COSDictionary trailer = document.getTrailer();
        for (COSName key : new COSName[] {COSName.ROOT, COSName.INFO}) {
            COSDictionary top = trailer.getCOSDictionary(key);
            if (top != null) {
                level.add(top);
            }
        }
        long visits = 0;
        while (!level.isEmpty()) {
            List<COSBase> next = new ArrayList<>();
            for (COSBase element : level) {
                LineArt.checkpoint();
                COSBase target = element instanceof COSObject reference ? reference.getObject() : element;
                if (target == null) {
                    continue;
                }
                if (element instanceof COSObject || !target.isDirect()) {
                    pooled.add(target);
                }
                Iterable<COSBase> children = target instanceof COSDictionary dictionary ? dictionary.getValues()
                        : target instanceof COSArray array ? array : List.of();
                for (COSBase child : children) {
                    if (child instanceof COSObject reference) {
                        COSBase resolved = reference.getObject();
                        if (resolved == null || pooled.contains(resolved)) {
                            continue;
                        }
                    } else if (child instanceof COSDictionary) {
                        if (!direct.add(child)) {
                            continue;
                        }
                    } else if (!(child instanceof COSArray)) {
                        continue;
                    }
                    next.add(child);
                    if (++visits > limit) {
                        return false;
                    }
                }
            }
            level = next;
        }
        return true;
    }

    private static Set<COSBase> identitySet() {
        return Collections.newSetFromMap(new IdentityHashMap<>());
    }
}
