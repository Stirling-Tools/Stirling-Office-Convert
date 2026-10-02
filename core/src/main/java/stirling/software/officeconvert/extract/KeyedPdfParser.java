package stirling.software.officeconvert.extract;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDocument;
import org.apache.pdfbox.cos.COSObjectKey;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.io.RandomAccessRead;
import org.apache.pdfbox.io.RandomAccessStreamCache.StreamCacheCreateFunction;
import org.apache.pdfbox.pdfparser.PDFObjectStreamParser;
import org.apache.pdfbox.pdfparser.PDFParser;

final class KeyedPdfParser extends PDFParser {

    private final Map<Long, Map<COSObjectKey, COSBase>> decompressed = new HashMap<>();

    private final Map<Long, COSObjectKey> keys = new HashMap<>();

    private int indexedEntries = -1;

    KeyedPdfParser(RandomAccessRead source, String password, StreamCacheCreateFunction cache) throws IOException {
        super(source, password, null, null, cache);
    }

    @Override
    protected COSObjectKey getObjectKey(long num, int gen) {
        return key(document, num, gen);
    }

    private COSObjectKey key(COSDocument doc, long num, int gen) {
        Map<COSObjectKey, Long> xref = doc == null ? Map.of() : doc.getXrefTable();
        if (xref.size() != indexedEntries) {
            for (COSObjectKey k : xref.keySet()) {
                keys.putIfAbsent(k.getInternalHash(), k);
            }
            indexedEntries = xref.size();
        }
        COSObjectKey found = keys.get(COSObjectKey.computeInternalHash(num, gen));
        return found != null ? found : new COSObjectKey(num, gen);
    }

    @Override
    protected COSBase parseObjectStreamObject(long objstmObjNr, COSObjectKey key) throws IOException {
        Map<COSObjectKey, COSBase> parsed = decompressed.computeIfAbsent(objstmObjNr, n -> new HashMap<>());
        COSBase object = parsed.remove(key);
        if (object != null) {
            return object;
        }
        COSDocument doc = document;
        if (doc.getObjectFromPool(key(doc, objstmObjNr, 0)).getObject() instanceof COSStream stream) {
            try {
                Map<COSObjectKey, COSBase> all = new Stream(stream, doc).parseAllObjects();
                object = all.remove(key);
                all.forEach(parsed::putIfAbsent);
            } catch (IOException e) {
                if (!isLenient()) {
                    throw e;
                }
            }
        }
        return object;
    }

    private final class Stream extends PDFObjectStreamParser {

        private final COSDocument owner;

        Stream(COSStream stream, COSDocument owner) throws IOException {
            super(stream, owner);
            this.owner = owner;
        }

        @Override
        protected COSObjectKey getObjectKey(long num, int gen) {
            return key(owner, num, gen);
        }
    }
}
