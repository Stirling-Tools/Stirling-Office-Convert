package stirling.software.officeconvert.topdf.odf;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.spec.AlgorithmParameterSpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.w3c.dom.Element;

import stirling.software.officeconvert.topdf.crypt.Argon2id;
import stirling.software.officeconvert.topdf.crypt.OpenOfficeSha1;
import stirling.software.officeconvert.topdf.crypt.Passwords;
import stirling.software.officeconvert.topdf.io.OfficeZip;
import stirling.software.officeconvert.topdf.io.SecureXml;

final class OdfEncryption {

    static final String WHOLE_PACKAGE = "encrypted-package";

    static final int MAX_ITERATIONS = 10_000_000;

    static final long MAX_TOTAL_ITERATIONS = 100_000_000L;

    static final int MAX_ARGON2_KIB = 1 << 18;

    static final int MAX_ARGON2_PASSES = 64;

    static final int MAX_ARGON2_LANES = 64;

    static final long MAX_TOTAL_ARGON2 = (long) MAX_ARGON2_PASSES * MAX_ARGON2_KIB;

    private static final String TOO_MUCH_STRETCHING = "The document's encryption asks for more key stretching than"
            + " allowed, so it was not opened";

    private static final String UNSUPPORTED = "The document uses an encryption that is not supported";

    private static final Charset WINDOWS_1252 = Charset.forName("windows-1252");

    record Cost(long plainBytes, long argon2Bytes) {}

    private record Sealed(String cipher, byte[] iv, String startKey, String derivation, byte[] salt, int iterations,
            int keyBytes, int argonPasses, int argonKiB, int argonLanes, String checksumDigest, byte[] checksum,
            long size) {

        Derivation derivation(int candidate) {
            return new Derivation(startKey, candidate, this.derivation, ByteBuffer.wrap(salt), iterations, keyBytes,
                    argonPasses, argonKiB, argonLanes);
        }
    }

    private record Derivation(String startKey, int candidate, String kind, ByteBuffer salt, int iterations,
            int keyBytes, int argonPasses, int argonKiB, int argonLanes) {}

    private final Map<String, Sealed> sealed;

    private final String password;

    private long iterationsLeft = MAX_TOTAL_ITERATIONS;

    private int preferred;

    private final Map<String, List<byte[]>> startKeys = new HashMap<>();

    private final Map<Derivation, byte[]> keys = new HashMap<>();

    private OdfEncryption(Map<String, Sealed> sealed, String password) {
        this.sealed = sealed;
        this.password = password;
    }

    static OdfEncryption read(byte[] manifest, String password) throws IOException {
        if (manifest == null || !new String(manifest, StandardCharsets.UTF_8).contains("encryption-data")) {
            return null;
        }
        Element root = SecureXml.parse(new ByteArrayInputStream(manifest)).getDocumentElement();
        Map<String, Element> entries = new HashMap<>();
        for (Element f : Dom.kids(root, Ns.MANIFEST, "file-entry")) {
            String path = Dom.attr(f, Ns.MANIFEST, "full-path");
            if (Dom.kid(f, Ns.MANIFEST, "encryption-data") != null && path != null) {
                entries.putIfAbsent(path.replace('\\', '/').replaceFirst("^/+", ""), f);
            }
        }
        if (entries.isEmpty()) {
            return null;
        }
        if (password == null) {
            throw new Passwords.Refused(Passwords.PROTECTED, null);
        }
        Map<String, Sealed> sealed = new HashMap<>();
        for (Map.Entry<String, Element> e : entries.entrySet()) {
            sealed.put(e.getKey(), sealed(e.getValue(), Dom.kid(e.getValue(), Ns.MANIFEST, "encryption-data")));
        }
        OdfEncryption crypt = new OdfEncryption(sealed, password);
        crypt.checkArgon2();
        return crypt;
    }

    private void checkArgon2() throws IOException {
        Set<Derivation> distinct = new HashSet<>();
        long work = 0;
        for (Sealed s : sealed.values()) {
            if (!s.derivation().equals("argon2id")) {
                continue;
            }
            int candidates = startKeys(s.startKey()).size();
            for (int k = 0; k < candidates; k++) {
                if (distinct.add(s.derivation(k))) {
                    work += (long) s.argonPasses() * s.argonKiB();
                    if (work > MAX_TOTAL_ARGON2) {
                        throw new IOException(TOO_MUCH_STRETCHING);
                    }
                }
            }
        }
    }

    static Cost declared(byte[] manifest, List<String> paths) {
        long plain = 0;
        long argon = 0;
        if (manifest == null || !new String(manifest, StandardCharsets.UTF_8).contains("encryption-data")) {
            return new Cost(0, 0);
        }
        try {
            Element root = SecureXml.parse(new ByteArrayInputStream(manifest)).getDocumentElement();
            for (Element f : Dom.kids(root, Ns.MANIFEST, "file-entry")) {
                Element data = Dom.kid(f, Ns.MANIFEST, "encryption-data");
                if (data == null) {
                    continue;
                }
                if (paths.contains(Dom.attr(f, Ns.MANIFEST, "full-path"))) {
                    plain += Math.max(0, number(Dom.attr(f, Ns.MANIFEST, "size"), 0));
                }
                Element derivation = Dom.kid(data, Ns.MANIFEST, "key-derivation");
                long kib = derivation == null ? 0 : number(argon(derivation, "argon2-memory"), 0);
                argon = Math.max(argon, Math.min(Math.max(0, kib), MAX_ARGON2_KIB) << 10);
            }
        } catch (IOException | RuntimeException e) {
            return new Cost(0, 0);
        }
        return new Cost(plain, argon);
    }

    boolean sealed(String name) {
        return sealed.containsKey(name);
    }

    boolean wholePackage() {
        return sealed.containsKey(WHOLE_PACKAGE);
    }

    long size(String name) {
        Sealed s = sealed.get(name);
        return s == null ? -1 : s.size();
    }

    InputStream open(String name, byte[] encrypted) throws IOException {
        byte[] deflated = decrypt(sealed.get(name), encrypted);
        Inflater inflater = new Inflater(true);
        return new InflaterInputStream(new ByteArrayInputStream(deflated), inflater, 1 << 16) {
            @Override
            public void close() throws IOException {
                super.close();
                inflater.end();
            }
        };
    }

    void unwrap(byte[] encrypted, OutputStream out, long max) throws IOException {
        try (InputStream in = open(WHOLE_PACKAGE, encrypted)) {
            byte[] buffer = new byte[1 << 16];
            long total = 0;
            for (int n; (n = in.read(buffer)) > 0;) {
                OfficeZip.checkNotInterrupted();
                total += n;
                if (total > max) {
                    throw new OfficeZip.Oversized("The document is too large: over " + (max >> 20)
                            + " MB uncompressed");
                }
                out.write(buffer, 0, n);
            }
        }
    }

    private byte[] decrypt(Sealed s, byte[] encrypted) throws IOException {
        List<byte[]> starts = startKeys(s.startKey());
        for (int i = 0; i < starts.size(); i++) {
            int k = (i + preferred) % starts.size();
            byte[] plain = decrypt(s, encrypted, key(s, k, starts.get(k)));
            if (plain != null) {
                preferred = k;
                return plain;
            }
        }
        throw new Passwords.Refused(Passwords.WRONG, null);
    }

    private byte[] key(Sealed s, int candidate, byte[] start) throws IOException {
        Derivation id = s.derivation(candidate);
        byte[] key = keys.get(id);
        if (key == null) {
            key = s.derivation().equals("argon2id")
                    ? Argon2id.hash(start, s.salt(), s.argonPasses(), s.argonKiB(), s.argonLanes(), s.keyBytes())
                    : pbkdf2(start, s.salt(), s.iterations(), s.keyBytes());
            keys.put(id, key);
        }
        return key;
    }

    private List<byte[]> startKeys(String digest) throws IOException {
        List<byte[]> cached = startKeys.get(digest);
        if (cached == null) {
            cached = derivedStartKeys(digest);
            startKeys.put(digest, cached);
        }
        return cached;
    }

    private List<byte[]> derivedStartKeys(String digest) throws IOException {
        byte[] utf8 = password.getBytes(StandardCharsets.UTF_8);
        List<byte[]> candidates = new ArrayList<>();
        try {
            candidates.add(MessageDigest.getInstance(digest).digest(utf8));
            if (digest.equals("SHA-1")) {
                byte[] openOffice = OpenOfficeSha1.digest(utf8);
                if (openOffice != null) {
                    candidates.add(openOffice);
                }
                byte[] legacy = password.getBytes(WINDOWS_1252);
                if (!Arrays.equals(legacy, utf8)) {
                    candidates.add(MessageDigest.getInstance(digest).digest(legacy));
                }
            }
        } catch (GeneralSecurityException e) {
            throw new IOException(UNSUPPORTED, e);
        }
        return candidates;
    }

    private static byte[] decrypt(Sealed s, byte[] encrypted, byte[] key) throws IOException {
        byte[] plain;
        try {
            plain = switch (s.cipher()) {
                case "AES/CBC" -> unpad(cipher("AES/CBC/NoPadding", "AES", key, new IvParameterSpec(s.iv()), encrypted,
                        16));
                case "AES/GCM" -> cipher("AES/GCM/NoPadding", "AES", key, new GCMParameterSpec(128, s.iv()),
                        withoutIv(encrypted, s.iv()), 1);
                default -> cipher("Blowfish/CFB/NoPadding", "Blowfish", key, new IvParameterSpec(s.iv()), encrypted,
                        1);
            };
        } catch (AEADBadTagException e) {
            return null;
        } catch (GeneralSecurityException e) {
            throw new IOException("The document is damaged: an encrypted part could not be read", e);
        }
        if (plain == null) {
            return null;
        }
        return s.checksumDigest() == null || s.checksum() == null || checksumMatches(s, plain) ? plain : null;
    }

    private static boolean checksumMatches(Sealed s, byte[] plain) throws IOException {
        byte[] head = Arrays.copyOf(plain, Math.min(1024, plain.length));
        try {
            if (MessageDigest.isEqual(MessageDigest.getInstance(s.checksumDigest()).digest(head), s.checksum())) {
                return true;
            }
        } catch (GeneralSecurityException e) {
            throw new IOException(UNSUPPORTED, e);
        }
        byte[] openOffice = s.checksumDigest().equals("SHA-1") ? OpenOfficeSha1.digest(head) : null;
        return openOffice != null && MessageDigest.isEqual(openOffice, s.checksum());
    }

    private static byte[] cipher(String transformation, String algorithm, byte[] key,
            AlgorithmParameterSpec iv, byte[] encrypted, int block)
            throws GeneralSecurityException, IOException {
        if (encrypted.length % block != 0) {
            throw new IOException("The document is damaged: an encrypted part is cut short");
        }
        Cipher c = Cipher.getInstance(transformation);
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, algorithm), iv);
        return c.doFinal(encrypted);
    }

    private static byte[] withoutIv(byte[] encrypted, byte[] iv) {
        boolean prefixed = encrypted.length >= iv.length
                && Arrays.equals(encrypted, 0, iv.length, iv, 0, iv.length);
        return prefixed ? Arrays.copyOfRange(encrypted, iv.length, encrypted.length) : encrypted;
    }

    private static byte[] unpad(byte[] plain) {
        if (plain.length == 0) {
            return plain;
        }
        int pad = plain[plain.length - 1] & 0xff;
        return pad >= 1 && pad <= 16 && pad <= plain.length ? Arrays.copyOf(plain, plain.length - pad) : null;
    }

    private byte[] pbkdf2(byte[] secret, byte[] salt, int iterations, int length) throws IOException {
        if (iterations > iterationsLeft) {
            throw new IOException(TOO_MUCH_STRETCHING);
        }
        iterationsLeft -= iterations;
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret, "HmacSHA1"));
            int size = mac.getMacLength();
            byte[] out = new byte[length];
            for (int block = 1, pos = 0; pos < length; block++, pos += size) {
                mac.update(salt);
                byte[] u = mac.doFinal(new byte[] {(byte) (block >>> 24), (byte) (block >>> 16), (byte) (block >>> 8),
                    (byte) block});
                byte[] t = u.clone();
                for (int i = 1; i < iterations; i++) {
                    if ((i & 0xFFF) == 0 && Thread.currentThread().isInterrupted()) {
                        throw new InterruptedIOException("Conversion interrupted");
                    }
                    u = mac.doFinal(u);
                    for (int j = 0; j < size; j++) {
                        t[j] ^= u[j];
                    }
                }
                System.arraycopy(t, 0, out, pos, Math.min(size, length - pos));
            }
            return out;
        } catch (GeneralSecurityException e) {
            throw new IOException(UNSUPPORTED, e);
        }
    }

    private static Sealed sealed(Element entry, Element data) throws IOException {
        Element algorithm = Dom.kid(data, Ns.MANIFEST, "algorithm");
        Element start = Dom.kid(data, Ns.MANIFEST, "start-key-generation");
        Element derivation = Dom.kid(data, Ns.MANIFEST, "key-derivation");
        if (algorithm == null || derivation == null) {
            throw new IOException(UNSUPPORTED);
        }
        String cipher = cipher(Dom.attr(algorithm, Ns.MANIFEST, "algorithm-name"));
        String startKey = start == null ? "SHA-1" : digest(Dom.attr(start, Ns.MANIFEST, "start-key-generation-name"));
        String kdf = lower(Dom.attr(derivation, Ns.MANIFEST, "key-derivation-name"));
        String kind = kdf.endsWith("argon2id") ? "argon2id" : kdf.equals("pbkdf2") || kdf.endsWith("#pbkdf2")
                ? "pbkdf2" : null;
        if (cipher == null || startKey == null || kind == null) {
            throw new IOException(UNSUPPORTED);
        }
        String algorithmName = lower(Dom.attr(algorithm, Ns.MANIFEST, "algorithm-name"));
        int keyBytes = (int) number(Dom.attr(derivation, Ns.MANIFEST, "key-size"),
                algorithmName.contains("aes256") ? 32 : algorithmName.contains("aes192") ? 24 : 16);
        int iterations = (int) number(Dom.attr(derivation, Ns.MANIFEST, "iteration-count"), kind.equals("pbkdf2")
                ? -1 : 0);
        int passes = (int) number(argon(derivation, "argon2-iterations"), 0);
        int memory = (int) number(argon(derivation, "argon2-memory"), 0);
        int lanes = (int) number(argon(derivation, "argon2-lanes"), 0);
        boolean argon = kind.equals("argon2id");
        if (keyBytes != 16 && keyBytes != 24 && keyBytes != 32) {
            throw new IOException(UNSUPPORTED);
        }
        if (!argon && (iterations < 1 || iterations > MAX_ITERATIONS)
                || argon && (passes < 1 || passes > MAX_ARGON2_PASSES || lanes < 1 || lanes > MAX_ARGON2_LANES
                        || memory < 8 * lanes || memory > MAX_ARGON2_KIB)) {
            throw new IOException(TOO_MUCH_STRETCHING);
        }
        String checksumType = lower(Dom.attr(data, Ns.MANIFEST, "checksum-type"));
        String checksumDigest = checksumType.endsWith("sha256-1k") || checksumType.equals("sha256/1k") ? "SHA-256"
                : checksumType.endsWith("sha1-1k") || checksumType.equals("sha1/1k") ? "SHA-1" : null;
        byte[] iv = base64(Dom.attr(algorithm, Ns.MANIFEST, "initialisation-vector"));
        byte[] salt = base64(Dom.attr(derivation, Ns.MANIFEST, "salt"));
        if (iv == null || salt == null || iv.length == 0) {
            throw new IOException("The document is damaged: its encryption details are not readable");
        }
        return new Sealed(cipher, iv, startKey, kind, salt, iterations, keyBytes, passes, memory, lanes,
                checksumDigest, base64(Dom.attr(data, Ns.MANIFEST, "checksum")),
                number(Dom.attr(entry, Ns.MANIFEST, "size"), -1));
    }

    private static String argon(Element derivation, String name) {
        String v = Dom.attr(derivation, Ns.LOEXT, name);
        return v != null ? v : Dom.attr(derivation, Ns.MANIFEST, name);
    }

    private static String cipher(String name) {
        String n = lower(name);
        if (n.endsWith("#aes256-cbc") || n.endsWith("#aes192-cbc") || n.endsWith("#aes128-cbc")) {
            return "AES/CBC";
        }
        if (n.endsWith("#aes256-gcm") || n.endsWith("#aes192-gcm") || n.endsWith("#aes128-gcm")) {
            return "AES/GCM";
        }
        if (n.equals("blowfish cfb") || n.endsWith("#blowfish")) {
            return "Blowfish/CFB";
        }
        return null;
    }

    private static String digest(String name) {
        String n = lower(name);
        if (n.equals("sha1") || n.endsWith("#sha1")) {
            return "SHA-1";
        }
        if (n.equals("sha256") || n.endsWith("#sha256")) {
            return "SHA-256";
        }
        if (n.equals("sha512") || n.endsWith("#sha512")) {
            return "SHA-512";
        }
        return null;
    }

    private static String lower(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }

    private static long number(String s, long fallback) {
        if (s == null || s.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return Long.MIN_VALUE;
        }
    }

    private static byte[] base64(String s) throws IOException {
        if (s == null) {
            return null;
        }
        try {
            return Base64.getMimeDecoder().decode(s);
        } catch (IllegalArgumentException e) {
            throw new IOException("The document is damaged: its encryption details are not readable", e);
        }
    }
}
