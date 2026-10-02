package stirling.software.officeconvert.pdfa;

import java.io.InterruptedIOException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSObject;

import stirling.software.officeconvert.extract.PdfFiles;

final class Visits {

    private final Set<COSBase> seen = Collections.newSetFromMap(new IdentityHashMap<>());

    boolean first(COSBase b) throws InterruptedIOException {
        if ((seen.size() & 1023) == 1023) {
            PdfFiles.stopIfInterrupted();
        }
        return seen.add(b instanceof COSObject o ? o.getObject() : b);
    }
}
