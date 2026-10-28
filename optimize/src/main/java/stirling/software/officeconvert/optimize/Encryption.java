package stirling.software.officeconvert.optimize;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

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

    private static final byte[] AES_SALT = {0x73, 0x41, 0x6c, 0x54};

    private static final SecureRandom RANDOM = new SecureRandom();

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
        return encrypts(COSName.METADATA.equals(source.getCOSName(COSName.TYPE)));
    }

    boolean encrypts(boolean metadataStream) {
        return metadata || !metadataStream;
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

    long encryptedLength(long plain) {
        return handler.isAES() ? 16 + (plain / 16 + 1) * 16 : plain;
    }

    OutputStream encrypting(OutputStream out, int number) throws IOException {
        byte[] iv = new byte[16];
        if (handler.isAES()) {
            RANDOM.nextBytes(iv);
        }
        return encrypting(out, number, iv);
    }

    OutputStream encrypting(OutputStream out, int number, byte[] iv) throws IOException {
        byte[] file = handler.getEncryptionKey();
        Cipher cipher;
        try {
            if (handler.isAES()) {
                byte[] key = file.length == 32 ? file : objectKey(file, number, true);
                cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
                cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
                out.write(iv);
            } else {
                cipher = Cipher.getInstance("ARCFOUR");
                cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(objectKey(file, number, false), "ARCFOUR"));
            }
        } catch (GeneralSecurityException e) {
            throw new IOException("A stream could not be encrypted", e);
        }
        return new OutputStream() {
            private boolean closed;

            private byte[] buffer = new byte[0];

            @Override
            public void write(int b) throws IOException {
                write(new byte[] {(byte) b}, 0, 1);
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                int need = cipher.getOutputSize(len);
                if (buffer.length < need) {
                    buffer = new byte[need];
                }
                try {
                    out.write(buffer, 0, cipher.update(b, off, len, buffer));
                } catch (GeneralSecurityException e) {
                    throw new IOException("A stream could not be encrypted", e);
                }
            }

            @Override
            public void close() throws IOException {
                if (closed) {
                    return;
                }
                closed = true;
                try {
                    out.write(cipher.doFinal());
                } catch (GeneralSecurityException e) {
                    throw new IOException("A stream could not be encrypted", e);
                }
            }
        };
    }

    private static byte[] objectKey(byte[] file, int number, boolean aes) {
        byte[] key = new byte[file.length + 5];
        System.arraycopy(file, 0, key, 0, file.length);
        key[file.length] = (byte) number;
        key[file.length + 1] = (byte) (number >> 8);
        key[file.length + 2] = (byte) (number >> 16);
        MessageDigest md5;
        try {
            md5 = MessageDigest.getInstance("MD5");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
        md5.update(key);
        if (aes) {
            md5.update(AES_SALT);
        }
        return java.util.Arrays.copyOf(md5.digest(), Math.min(key.length, 16));
    }
}
