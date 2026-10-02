package stirling.software.officeconvert.pdfa;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;

final class Census {

    private static final COSName PIECE_INFO = COSName.getPDFName("PieceInfo");

    final List<COSDictionary> valued = new ArrayList<>();

    final List<COSDictionary> withMetadata = new ArrayList<>();

    final List<COSDictionary> withPieceInfo = new ArrayList<>();

    final List<COSArray> deviceNs = new ArrayList<>();

    final List<COSArray> separations = new ArrayList<>();

    private Census() {}

    static Census of(PDDocument doc) throws IOException {
        Census c = new Census();
        CosWalk.walk(doc, c::visit);
        return c;
    }

    private void visit(COSBase b) {
        if (b instanceof COSDictionary d) {
            if (d.containsKey(COSName.V) && !(d instanceof COSStream)) {
                valued.add(d);
            }
            if (d.getDictionaryObject(COSName.METADATA) instanceof COSStream) {
                withMetadata.add(d);
            }
            if (d.containsKey(PIECE_INFO)) {
                withPieceInfo.add(d);
            }
        } else if (b instanceof COSArray a && a.size() >= 4 && a.getObject(0) instanceof COSName kind) {
            if (COSName.DEVICEN.equals(kind)) {
                deviceNs.add(a);
            } else if (COSName.SEPARATION.equals(kind)) {
                separations.add(a);
            }
        }
    }
}
