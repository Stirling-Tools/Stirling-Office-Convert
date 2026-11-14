package stirling.software.officeconvert.extract;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSDocument;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObjectKey;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.io.RandomAccessRead;
import org.apache.pdfbox.io.RandomAccessStreamCache.StreamCacheCreateFunction;
import org.apache.pdfbox.pdfparser.PDFObjectStreamParser;
import org.apache.pdfbox.pdfparser.PDFParser;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;

final class KeyedPdfParser extends PDFParser {

    private final Map<Long, Map<COSObjectKey, COSBase>> decompressed = new HashMap<>();

    private long[] keyHashes = new long[16];

    private COSObjectKey[] keyObjects = new COSObjectKey[16];

    private int keyCount;

    private int indexedEntries = -1;

    private final boolean allStreams;

    private final boolean tolerant;

    private boolean clear;

    KeyedPdfParser(RandomAccessRead source, String password, StreamCacheCreateFunction cache, boolean allStreams,
            boolean tolerant) throws IOException {
        super(source, password, null, null, cache);
        this.allStreams = allStreams;
        this.tolerant = tolerant;
    }

    @Override
    protected void prepareDecryption() throws IOException {
        try {
            super.prepareDecryption();
        } catch (InvalidPasswordException e) {
            if (!tolerant || !contentInTheClear(document.getEncryptionDictionary())) {
                throw e;
            }
            securityHandler = null;
            clear = true;
        }
    }

    @Override
    protected void checkPages(COSDictionary root) throws IOException {
        if (!tolerant) {
            super.checkPages(root);
        } else if (root.getCOSDictionary(COSName.PAGES) == null) {
            throw new IOException("Page tree root must be a dictionary");
        }
    }

    @Override
    public PDDocument parse() throws IOException {
        PDDocument doc = super.parse();
        if (clear) {
            document.setEncryptionDictionary(null);
            document.getTrailer().removeItem(COSName.ENCRYPT);
            doc.setAllSecurityToBeRemoved(true);
            if (doc.getDocumentCatalog().getCOSObject().getDictionaryObject(COSName.NAMES) instanceof COSDictionary names) {
                names.removeItem(COSName.EMBEDDED_FILES);
            }
        }
        return doc;
    }

    static boolean contentInTheClear(COSDictionary encryption) {
        if (encryption == null || !COSName.getPDFName("Standard").equals(encryption.getCOSName(COSName.FILTER))
                || encryption.getInt(COSName.V, 0) < 4) {
            return false;
        }
        for (COSName key : new COSName[] {COSName.STM_F, COSName.STR_F}) {
            COSName filter = encryption.getCOSName(key);
            if (filter != null && !COSName.IDENTITY.equals(filter)) {
                return false;
            }
        }
        return true;
    }

    @Override
    protected COSStream parseCOSStream(COSDictionary dic) throws IOException {
        COSStream stream = super.parseCOSStream(dic);
        if (COSName.XREF.equals(dic.getItem(COSName.TYPE)) && BrotliStreams.uses(stream)) {
            decodeBrotli(stream);
        }
        return stream;
    }

    @Override
    protected synchronized COSBase parseObjectDynamically(COSObjectKey objKey, boolean requireExistingNotCompressedObj)
            throws IOException {
        COSBase object = super.parseObjectDynamically(objKey, requireExistingNotCompressedObj);
        if (object instanceof COSStream stream && BrotliStreams.uses(stream)
                && (allStreams || BrotliStreams.structural(stream))) {
            decodeBrotli(stream);
        }
        return object;
    }

    private void decodeBrotli(COSStream stream) throws IOException {
        long at = source.getPosition();
        try {
            BrotliStreams.decode(stream, document);
        } finally {
            source.seek(at);
        }
    }

    @Override
    protected COSObjectKey getObjectKey(long num, int gen) {
        return key(document, num, gen);
    }

    private COSObjectKey key(COSDocument doc, long num, int gen) {
        Map<COSObjectKey, Long> xref = doc == null ? Map.of() : doc.getXrefTable();
        if (xref.size() != indexedEntries) {
            for (COSObjectKey k : xref.keySet()) {
                addKey(k);
            }
            indexedEntries = xref.size();
        }
        COSObjectKey found = findKey(COSObjectKey.computeInternalHash(num, gen));
        return found != null ? found : new COSObjectKey(num, gen);
    }

    private COSObjectKey findKey(long hash) {
        int mask = keyObjects.length - 1;
        for (int i = slot(hash, mask); keyObjects[i] != null; i = i + 1 & mask) {
            if (keyHashes[i] == hash) {
                return keyObjects[i];
            }
        }
        return null;
    }

    private void addKey(COSObjectKey key) {
        if ((keyCount + 1) * 3L > keyObjects.length * 2L) {
            long[] oldHashes = keyHashes;
            COSObjectKey[] oldObjects = keyObjects;
            keyHashes = new long[oldObjects.length * 2];
            keyObjects = new COSObjectKey[oldObjects.length * 2];
            keyCount = 0;
            for (int i = 0; i < oldObjects.length; i++) {
                if (oldObjects[i] != null) {
                    insert(oldHashes[i], oldObjects[i]);
                }
            }
        }
        insert(key.getInternalHash(), key);
    }

    private void insert(long hash, COSObjectKey key) {
        int mask = keyObjects.length - 1;
        int i = slot(hash, mask);
        while (keyObjects[i] != null) {
            if (keyHashes[i] == hash) {
                return;
            }
            i = i + 1 & mask;
        }
        keyHashes[i] = hash;
        keyObjects[i] = key;
        keyCount++;
    }

    private static int slot(long hash, int mask) {
        long h = hash * 0x9E3779B97F4A7C15L;
        return (int) (h ^ h >>> 32) & mask;
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
