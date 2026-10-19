package com.tencentx.pinyin.adb;

import android.content.Context;
import android.util.Base64;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.WorkerThread;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.x509.X509V3CertificateGenerator;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Security;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Date;

import javax.security.auth.x500.X500Principal;

/**
 * Stores the self-signed client certificate + RSA key pair used by the ADB
 * (wireless debugging) mode.
 * <p>
 * The files are kept in "{@code filesDir/adb}":
 * <ul>
 *     <li>{@code private.key}: the PKCS#8 encoded private key</li>
 *     <li>{@code cert.pem}: the PEM encoded X.509 certificate</li>
 * </ul>
 * The key pair is generated lazily on first use, on the calling (worker) thread.
 */
public final class AdbKeyStore {

    private static final String TAG = "AdbKeyStore";

    private static final String DIR_NAME = "adb";
    private static final String KEY_FILE_NAME = "private.key";
    private static final String CERT_FILE_NAME = "cert.pem";

    private static final String SUBJECT_DN = "CN=Sshd4a";
    private static final String SIGNATURE_ALGORITHM = "SHA256withRSA";
    private static final int KEY_SIZE = 2048;
    /** Validity of the generated certificate: (roughly) 10 years. */
    private static final long VALIDITY_MILLIS = 10L * 365L * 24L * 60L * 60L * 1000L;
    /** Backdate the certificate a little to allow for clock skew. */
    private static final long NOT_BEFORE_OFFSET_MILLIS = 60_000L;

    private static final String PEM_BEGIN = "-----BEGIN CERTIFICATE-----";
    private static final String PEM_END = "-----END CERTIFICATE-----";

    private static volatile AdbKeyStore instance;

    private final File keyFile;
    private final File certFile;

    private PrivateKey privateKey;
    private X509Certificate certificate;

    /**
     * Get the singleton instance.
     *
     * @param context Current context; the application context is used
     *
     * @return the instance
     */
    @NonNull
    public static AdbKeyStore getInstance(@NonNull final Context context) {
        AdbKeyStore local = instance;
        if (local == null) {
            synchronized (AdbKeyStore.class) {
                local = instance;
                if (local == null) {
                    local = new AdbKeyStore(context.getApplicationContext());
                    instance = local;
                }
            }
        }
        return local;
    }

    private AdbKeyStore(@NonNull final Context context) {
        final File dir = new File(context.getFilesDir(), DIR_NAME);
        keyFile = new File(dir, KEY_FILE_NAME);
        certFile = new File(dir, CERT_FILE_NAME);
    }

    /**
     * The RSA private key. Generates (and stores) a new key pair on first use.
     * <p>
     * This can generate a key and do file I/O, so it must be called from a worker thread.
     *
     * @return the private key; never {@code null}
     *
     * @throws RuntimeException when the key could not be loaded or generated
     */
    @WorkerThread
    @NonNull
    public synchronized PrivateKey getPrivateKey() {
        ensureLoaded();
        return privateKey;
    }

    /**
     * The self-signed X.509 certificate wrapping the public key.
     * <p>
     * This can generate a key and do file I/O, so it must be called from a worker thread.
     *
     * @return the certificate; never {@code null}
     *
     * @throws RuntimeException when the certificate could not be loaded or generated
     */
    @WorkerThread
    @NonNull
    public synchronized X509Certificate getCertificate() {
        ensureLoaded();
        return certificate;
    }

    /**
     * Delete the stored key + certificate. A new key pair is generated on next use.
     * Used to (re-)pair from scratch.
     */
    public synchronized void reset() {
        privateKey = null;
        certificate = null;

        if (keyFile.exists() && !keyFile.delete()) {
            Log.w(TAG, "Failed to delete " + keyFile);
        }
        if (certFile.exists() && !certFile.delete()) {
            Log.w(TAG, "Failed to delete " + certFile);
        }
    }

    @WorkerThread
    private void ensureLoaded() {
        if (privateKey != null && certificate != null) {
            return;
        }

        try {
            if (keyFile.exists() && certFile.exists()) {
                privateKey = readPrivateKey();
                certificate = readCertificate();
                return;
            }
        } catch (@NonNull final Exception e) {
            // Corrupt or partial files: regenerate from scratch below.
            Log.w(TAG, "Failed to load the stored ADB key/certificate, regenerating", e);
        }

        generateAndStore();
    }

    @WorkerThread
    private void generateAndStore() {
        try {
            ensureBouncyCastleProvider();

            final KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(KEY_SIZE, new SecureRandom());
            final KeyPair keyPair = generator.generateKeyPair();

            final long now = System.currentTimeMillis();
            final Date notBefore = new Date(now - NOT_BEFORE_OFFSET_MILLIS);
            final Date notAfter = new Date(now + VALIDITY_MILLIS);
            final X500Principal subject = new X500Principal(SUBJECT_DN);

            final X509V3CertificateGenerator certGenerator = new X509V3CertificateGenerator();
            certGenerator.setSerialNumber(BigInteger.valueOf(now));
            certGenerator.setIssuerDN(subject);
            certGenerator.setSubjectDN(subject);
            certGenerator.setNotBefore(notBefore);
            certGenerator.setNotAfter(notAfter);
            certGenerator.setPublicKey(keyPair.getPublic());
            certGenerator.setSignatureAlgorithm(SIGNATURE_ALGORITHM);
            final X509Certificate generated = certGenerator.generate(
                    keyPair.getPrivate(), BouncyCastleProvider.PROVIDER_NAME);

            writePrivateKey(keyPair.getPrivate());
            writeCertificate(generated);

            privateKey = keyPair.getPrivate();
            certificate = generated;

        } catch (@NonNull final Exception e) {
            throw new RuntimeException("Failed to generate the ADB key/certificate: " + e, e);
        }
    }

    private static void ensureBouncyCastleProvider() {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    @NonNull
    private PrivateKey readPrivateKey() throws IOException, GeneralSecurityException {
        final byte[] encoded = Files.readAllBytes(keyFile.toPath());
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(encoded));
    }

    @NonNull
    private X509Certificate readCertificate() throws IOException, CertificateException {
        try (InputStream is = new FileInputStream(certFile)) {
            return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(is);
        }
    }

    private void writePrivateKey(@NonNull final PrivateKey key) throws IOException {
        ensureDirectory();
        try (OutputStream os = new FileOutputStream(keyFile)) {
            os.write(key.getEncoded());
        }
    }

    private void writeCertificate(@NonNull final X509Certificate cert)
            throws IOException, CertificateEncodingException {
        ensureDirectory();
        final String pem = PEM_BEGIN + "\n"
                + Base64.encodeToString(cert.getEncoded(), Base64.NO_WRAP) + "\n"
                + PEM_END + "\n";
        try (OutputStream os = new FileOutputStream(certFile)) {
            os.write(pem.getBytes(StandardCharsets.US_ASCII));
        }
    }

    private void ensureDirectory() throws IOException {
        final File dir = keyFile.getParentFile();
        if (dir != null && !dir.exists() && !dir.mkdirs()) {
            throw new IOException("Failed to create directory " + dir);
        }
    }
}
