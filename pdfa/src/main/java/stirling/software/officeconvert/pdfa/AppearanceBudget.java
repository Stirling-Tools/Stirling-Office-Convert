package stirling.software.officeconvert.pdfa;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Set;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSString;

import stirling.software.officeconvert.extract.PdfFiles;

final class AppearanceBudget {

    private static final int MAX_FIELDS = 10_000;

    private static final int MAX_VALUE_BYTES = 32_768;

    private static final int MAX_TOTAL_BYTES = 1 << 20;

    private AppearanceBudget() {}

    static void check(COSDictionary acro) throws IOException {
        Set<COSBase> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<COSBase> todo = new ArrayDeque<>();
        COSArray fields = ContentGraph.array(acro.getDictionaryObject(COSName.FIELDS));
        if (fields == null) {
            return;
        }
        todo.push(fields);
        int total = 0;
        int count = 0;
        while (!todo.isEmpty()) {
            PdfFiles.stopIfInterrupted();
            COSBase value = todo.pop();
            if (!seen.add(value)) {
                continue;
            }
            if (++count > MAX_FIELDS) {
                throw new IOException("The form has too many fields to refresh appearances safely");
            }
            if (value instanceof COSArray a) {
                for (int i = 0; i < a.size(); i++) {
                    COSBase item = a.getObject(i);
                    if (item != null) {
                        todo.push(item);
                    }
                }
            } else if (value instanceof COSDictionary d) {
                for (COSName key : new COSName[] {COSName.KIDS, COSName.V, COSName.DV}) {
                    COSBase item = d.getDictionaryObject(key);
                    if (item != null) {
                        todo.push(item);
                    }
                }
            } else if (value instanceof COSString s) {
                int bytes = s.getBytes().length;
                if (bytes > MAX_VALUE_BYTES || (total += bytes) > MAX_TOTAL_BYTES) {
                    throw new IOException("The form values are too large to refresh appearances safely");
                }
            }
        }
    }
}
