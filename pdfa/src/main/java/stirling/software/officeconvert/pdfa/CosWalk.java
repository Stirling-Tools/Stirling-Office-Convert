package stirling.software.officeconvert.pdfa;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.pdmodel.PDDocument;

import stirling.software.officeconvert.extract.PdfFiles;

final class CosWalk {

    static final int MAX_OBJECTS = 5_000_000;

    interface Visitor {
        void visit(COSBase object) throws IOException;
    }

    private CosWalk() {}

    static void walk(PDDocument doc, Visitor visitor) throws IOException {
        Set<COSBase> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<COSBase> stack = new ArrayDeque<>();
        stack.push(doc.getDocument().getTrailer());
        int n = 0;
        while (!stack.isEmpty()) {
            COSBase b = stack.pop();
            if (b instanceof COSObject o) {
                b = o.getObject();
            }
            if (b == null || !(b instanceof COSDictionary || b instanceof COSArray) || !seen.add(b)) {
                continue;
            }
            if (++n > MAX_OBJECTS) {
                throw new IOException("The PDF has more than " + MAX_OBJECTS + " objects");
            }
            if ((n & 1023) == 0) {
                PdfFiles.stopIfInterrupted();
            }
            visitor.visit(b);
            if (b instanceof COSDictionary d) {
                for (Map.Entry<COSName, COSBase> e : d.entrySet()) {
                    stack.push(e.getValue());
                }
            } else {
                COSArray a = (COSArray) b;
                for (int i = 0; i < a.size(); i++) {
                    stack.push(a.get(i));
                }
            }
        }
    }
}
