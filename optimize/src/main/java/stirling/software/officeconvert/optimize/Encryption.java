package stirling.software.officeconvert.optimize;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.PDEncryption;
import org.apache.pdfbox.pdmodel.encryption.ProtectionPolicy;
import org.apache.pdfbox.pdmodel.encryption.SecurityHandler;
import org.apache.pdfbox.pdmodel.encryption.StandardDecryptionMaterial;

final class Encryption {

    private final SecurityHandler<ProtectionPolicy> handler;

    private final boolean metadata;

    final COSDictionary dictionary;

    private Encryption(SecurityHandler<ProtectionPolicy> handler, boolean metadata, COSDictionary dictionary) {
        this.handler = handler;
        this.metadata = metadata;
        this.dictionary = dictionary;
    }

    static Encryption of(PDDocument doc, String password) throws IOException {
        if (!doc.isEncrypted()) {
            return null;
        }
        PDEncryption e = new PDEncryption(doc.getDocument().getEncryptionDictionary());
        int v = e.getVersion();
        if (v != 1 && v != 2 && v != 4 && v != 5 || !"Standard".equals(e.getFilter())) {
            throw new IOException("The PDF uses an encryption this optimizer does not write (V " + v + ")");
        }
        COSDictionary cf = e.getCOSObject().getCOSDictionary(COSName.CF);
        if (cf != null && (e.getCOSObject().containsKey(COSName.getPDFName("EFF"))
                && !e.getCOSObject().getNameAsString(COSName.getPDFName("EFF"), "")
                        .equals(e.getCOSObject().getNameAsString(COSName.STM_F, ""))
                || !e.getCOSObject().getNameAsString(COSName.STM_F, "StdCF")
                        .equals(e.getCOSObject().getNameAsString(COSName.STR_F, "StdCF")))) {
            throw new IOException("The PDF encrypts its strings, streams or attachments differently, which this "
                    + "optimizer does not write");
        }
        SecurityHandler<ProtectionPolicy> handler = e.getSecurityHandler();
        handler.prepareForDecryption(e, doc.getDocument().getDocumentID(),
                new StandardDecryptionMaterial(password == null ? "" : password));
        return new Encryption(handler, e.isEncryptMetaData(), e.getCOSObject());
    }

    static boolean usesCryptFilter(COSStream s) {
        java.util.List<COSName> filters = new java.util.ArrayList<>();
        java.util.List<org.apache.pdfbox.cos.COSBase> parms = new java.util.ArrayList<>();
        if (!Recoder.chain(s, filters, parms)) {
            return s.getItem(COSName.FILTER) != null;
        }
        return filters.contains(COSName.CRYPT);
    }

    byte[] string(byte[] plain, int number) {
        try {
            COSString s = new COSString(plain);
            handler.encryptString(s, number, 0);
            return s.getBytes();
        } catch (IOException e) {
            throw new IllegalStateException("A string could not be encrypted", e);
        }
    }

    boolean encrypts(COSStream source) {
        return metadata || !COSName.METADATA.equals(source.getCOSName(COSName.TYPE));
    }

    byte[] stream(byte[] plain, int number, COSStream source) throws IOException {
        if (!encrypts(source)) {
            return plain;
        }
        return encrypt(plain, number);
    }

    byte[] encrypt(byte[] plain, int number) throws IOException {
        COSStream t = new COSStream();
        try (OutputStream o = t.createRawOutputStream()) {
            o.write(plain);
        }
        handler.encryptStream(t, number, 0);
        try (InputStream in = t.createRawInputStream()) {
            return in.readAllBytes();
        } finally {
            t.close();
        }
    }
}
