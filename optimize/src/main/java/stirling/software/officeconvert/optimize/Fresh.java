package stirling.software.officeconvert.optimize;

import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.cos.COSObjectKey;

final class Fresh {

    private long next;

    private final Map<COSObjectKey, COSBase> created = new LinkedHashMap<>();

    Fresh(long next) {
        this.next = next;
    }

    COSObject add(COSBase object) {
        COSObjectKey key = new COSObjectKey(next++, 0);
        created.put(key, object);
        return new COSObject(object, key);
    }

    Map<COSObjectKey, COSBase> created() {
        return created;
    }
}
