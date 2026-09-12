package ru.agimate.common.net;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * The trust set of every outbound TLS connection: the platform's anchors plus the ones this
 * installation added, for a server whose chain is ordinary but whose root the JDK does not ship.
 *
 * <p>Merging happens in one {@link KeyStore} rather than in a manager that tries the platform first
 * and our anchors second: what comes out is the JDK's own manager, so no code of ours decides what
 * is trusted. Only anchors are added — host name and validity are checked as before.
 *
 * <p>Both halves come from one instance: OkHttp takes the socket factory and the trust manager as a
 * pair and builds its chain cleaner from the latter, so halves over different sets break the
 * handshake.
 */
public final class OutboundTrust {

    private final X509TrustManager manager;
    private final SSLContext context;
    private final List<X509Certificate> addedAnchors;

    private OutboundTrust(X509TrustManager manager, SSLContext context, List<X509Certificate> addedAnchors) {
        this.manager = manager;
        this.context = context;
        this.addedAnchors = List.copyOf(addedAnchors);
    }

    /** The platform set untouched — what the JVM would do on its own. */
    public static OutboundTrust systemDefault() {
        X509TrustManager manager = PublicOnlySslSocketFactory.defaultTrustManager();
        try {
            return new OutboundTrust(manager, SSLContext.getDefault(), List.of());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("No default SSL context available", e);
        }
    }

    /**
     * The platform set plus every certificate in the given PEM streams; empty gives back
     * {@link #systemDefault()}. A stream holding no certificate is an error: a configured resource
     * that silently adds nothing is the failure this class exists to prevent. Streams are read, not
     * closed.
     */
    public static OutboundTrust with(Collection<InputStream> pemStreams) {
        List<X509Certificate> extra = new ArrayList<>();
        for (InputStream pem : pemStreams) {
            extra.addAll(readCertificates(pem));
        }
        if (extra.isEmpty()) {
            return systemDefault();
        }
        X509TrustManager merged = mergedManager(extra);
        try {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[]{merged}, null);
            return new OutboundTrust(merged, context, extra);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot build an SSL context over the merged trust set", e);
        }
    }

    public X509TrustManager manager() {
        return manager;
    }

    public SSLContext context() {
        return context;
    }

    public SSLSocketFactory socketFactory() {
        return context.getSocketFactory();
    }

    /** Only what this installation added; empty means the platform set is in force as-is. */
    public List<X509Certificate> addedAnchors() {
        return addedAnchors;
    }

    /** Total anchors in force; platform-dependent, so a laptop and the image differ legitimately. */
    public int anchorCount() {
        return manager.getAcceptedIssuers().length;
    }

    private static List<X509Certificate> readCertificates(InputStream pem) {
        Collection<? extends Certificate> parsed;
        try {
            parsed = CertificateFactory.getInstance("X.509").generateCertificates(pem);
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("Not a readable PEM certificate bundle: " + e.getMessage(), e);
        }
        if (parsed.isEmpty()) {
            throw new IllegalArgumentException("PEM bundle holds no certificate");
        }
        List<X509Certificate> certificates = new ArrayList<>(parsed.size());
        for (Certificate certificate : parsed) {
            if (certificate instanceof X509Certificate x509) {
                certificates.add(x509);
            }
        }
        return certificates;
    }

    private static X509TrustManager mergedManager(List<X509Certificate> extra) {
        try {
            KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
            store.load(null, null);
            int index = 0;
            for (X509Certificate anchor : PublicOnlySslSocketFactory.defaultTrustManager().getAcceptedIssuers()) {
                store.setCertificateEntry("platform-" + index++, anchor);
            }
            index = 0;
            for (X509Certificate anchor : extra) {
                store.setCertificateEntry("added-" + index++, anchor);
            }
            TrustManagerFactory factory = TrustManagerFactory.getInstance(
                    TrustManagerFactory.getDefaultAlgorithm());
            factory.init(store);
            for (TrustManager candidate : factory.getTrustManagers()) {
                if (candidate instanceof X509TrustManager x509) {
                    return x509;
                }
            }
            throw new IllegalStateException("No X509 trust manager over the merged trust set");
        } catch (GeneralSecurityException | IOException e) {
            throw new IllegalStateException("Cannot merge the trust set", e);
        }
    }
}
