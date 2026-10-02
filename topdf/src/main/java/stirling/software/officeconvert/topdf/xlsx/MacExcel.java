package stirling.software.officeconvert.topdf.xlsx;

import java.io.IOException;
import java.io.InterruptedIOException;

import org.w3c.dom.Element;

import stirling.software.officeconvert.topdf.io.OfficeZip;
import stirling.software.officeconvert.topdf.io.Relationship;

final class MacExcel {

    private MacExcel() {}

    static boolean saved(OfficeZip zip) throws InterruptedIOException {
        try {
            Relationship r = zip.packageRelationships().first("extended-properties");
            if (r == null || r.part() == null || !zip.exists(r.part())) {
                return false;
            }
            Element app = Dml.child(zip.xml(r).getDocumentElement(), "Application");
            return app != null && app.getTextContent().trim().startsWith("Microsoft Macintosh Excel");
        } catch (InterruptedIOException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }
}
