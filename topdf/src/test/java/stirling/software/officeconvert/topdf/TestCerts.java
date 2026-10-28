package stirling.software.officeconvert.topdf;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

final class TestCerts {

    record Issued(KeyPair keys, X509Certificate certificate) {}

    private static final byte[] SHA256_RSA = {0x2A, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xF7, 0x0D, 0x01, 0x01,
            0x0B};
    private static final byte[] CN = {0x55, 0x04, 0x03};
    private static final byte[] SAN = {0x55, 0x1D, 0x11};
    private static final byte[] BASIC = {0x55, 0x1D, 0x13};
    private static final byte[] KEY_USAGE = {0x55, 0x1D, 0x0F};

    private TestCerts() {}

    static KeyPair keys() throws GeneralSecurityException {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        return g.generateKeyPair();
    }

    static Issued authority(String name) throws GeneralSecurityException {
        KeyPair k = keys();
        return new Issued(k, sign(name, name, k.getPublic().getEncoded(), k.getPrivate(), true, List.of(),
                Instant.now().minusSeconds(3600), Instant.now().plusSeconds(86_400), 1));
    }

    static Issued leaf(Issued issuer, String name, List<String> hosts, Instant from, Instant to)
            throws GeneralSecurityException {
        KeyPair k = keys();
        String issuerName = issuer == null ? name : subject(issuer.certificate());
        PrivateKey signer = issuer == null ? k.getPrivate() : issuer.keys().getPrivate();
        return new Issued(k, sign(name, issuerName, k.getPublic().getEncoded(), signer, false, hosts, from, to,
                System.nanoTime() & 0x7FFFFFFF));
    }

    private static String subject(X509Certificate c) {
        String dn = c.getSubjectX500Principal().getName();
        return dn.startsWith("CN=") ? dn.substring(3) : dn;
    }

    static SSLContext server(Issued leaf, Issued issuer) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, null);
        Certificate[] chain = issuer == null ? new Certificate[] {leaf.certificate()}
                : new Certificate[] {leaf.certificate(), issuer.certificate()};
        ks.setKeyEntry("leaf", leaf.keys().getPrivate(), "pw".toCharArray(), chain);
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, "pw".toCharArray());
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), null, null);
        return ctx;
    }

    static SSLContext trusting(Issued authority) throws Exception {
        KeyStore ts = KeyStore.getInstance("PKCS12");
        ts.load(null, null);
        ts.setCertificateEntry("ca", authority.certificate());
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ts);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, tmf.getTrustManagers(), null);
        return ctx;
    }

    private static X509Certificate sign(String subject, String issuer, byte[] publicKey, PrivateKey signer,
            boolean ca, List<String> hosts, Instant from, Instant to, long serial) throws GeneralSecurityException {
        byte[] algorithm = seq(oid(SHA256_RSA), new byte[] {0x05, 0x00});
        ByteArrayOutputStream ext = new ByteArrayOutputStream();
        if (ca) {
            ext.writeBytes(seq(oid(BASIC), bool(), octets(seq(bool()))));
            ext.writeBytes(seq(oid(KEY_USAGE), bool(), octets(tlv(0x03, new byte[] {0x01, 0x06}))));
        }
        if (!hosts.isEmpty()) {
            ByteArrayOutputStream names = new ByteArrayOutputStream();
            for (String h : hosts) {
                names.writeBytes(tlv(0x82, h.getBytes(StandardCharsets.US_ASCII)));
            }
            ext.writeBytes(seq(oid(SAN), octets(tlv(0x30, names.toByteArray()))));
        }
        byte[] tbs = seq(tlv(0xA0, tlv(0x02, new byte[] {0x02})), tlv(0x02, BigInteger.valueOf(serial).toByteArray()),
                algorithm, name(issuer), seq(time(from), time(to)), name(subject), publicKey,
                ext.size() == 0 ? new byte[0] : tlv(0xA3, tlv(0x30, ext.toByteArray())));
        Signature s = Signature.getInstance("SHA256withRSA");
        s.initSign(signer);
        s.update(tbs);
        byte[] sig = s.sign();
        byte[] bits = new byte[sig.length + 1];
        System.arraycopy(sig, 0, bits, 1, sig.length);
        byte[] der = seq(tbs, algorithm, tlv(0x03, bits));
        return (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(der));
    }

    private static byte[] name(String cn) {
        return seq(tlv(0x31, seq(oid(CN), tlv(0x0C, cn.getBytes(StandardCharsets.UTF_8)))));
    }

    private static byte[] time(Instant t) {
        String s = DateTimeFormatter.ofPattern("yyyyMMddHHmmss'Z'").withZone(ZoneOffset.UTC).format(t);
        return tlv(0x18, s.getBytes(StandardCharsets.US_ASCII));
    }

    private static byte[] bool() {
        return new byte[] {0x01, 0x01, (byte) 0xFF};
    }

    private static byte[] oid(byte[] body) {
        return tlv(0x06, body);
    }

    private static byte[] octets(byte[] body) {
        return tlv(0x04, body);
    }

    private static byte[] seq(byte[]... parts) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            b.writeBytes(p);
        }
        return tlv(0x30, b.toByteArray());
    }

    private static byte[] tlv(int tag, byte[] body) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(tag);
        int n = body.length;
        if (n < 128) {
            b.write(n);
        } else if (n < 256) {
            b.write(0x81);
            b.write(n);
        } else {
            b.write(0x82);
            b.write(n >> 8);
            b.write(n);
        }
        b.writeBytes(body);
        return b.toByteArray();
    }
}
