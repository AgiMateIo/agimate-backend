package ru.agimate.common.net;

import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.security.GeneralSecurityException;
import java.security.KeyStore;

/**
 * The address guard for a client that hands out no DNS hook — Spring AI's OpenAI client, whose
 * builder exposes an SSL socket factory and nothing closer to the socket.
 *
 * <p>The check runs on the connected TCP socket, before the TLS handshake and therefore before a
 * single request byte: whatever the name resolved to, and wherever a redirect moved the request, a
 * connection to a private address ends here. That is the whole reason to work at this level rather
 * than vetting the URL up front — the url check happens once, this happens on every connection the
 * client opens.
 *
 * <p>It follows that a target reached over plain http is <b>not</b> covered, since no TLS socket is
 * ever created for it. Callers that rely on this factory must require https of their targets.
 */
public class PublicOnlySslSocketFactory extends SSLSocketFactory {

    private final SSLSocketFactory delegate;
    private final PublicTargets targets;

    public PublicOnlySslSocketFactory(SSLSocketFactory delegate, PublicTargets targets) {
        this.delegate = delegate;
        this.targets = targets;
    }

    /** The trust manager OkHttp insists on receiving alongside a custom factory. */
    public static X509TrustManager defaultTrustManager() {
        try {
            TrustManagerFactory factory = TrustManagerFactory.getInstance(
                    TrustManagerFactory.getDefaultAlgorithm());
            factory.init((KeyStore) null);
            for (TrustManager manager : factory.getTrustManagers()) {
                if (manager instanceof X509TrustManager x509) {
                    return x509;
                }
            }
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("No default X509 trust manager available", e);
        }
        throw new IllegalStateException("No default X509 trust manager available");
    }

    @Override
    public String[] getDefaultCipherSuites() {
        return delegate.getDefaultCipherSuites();
    }

    @Override
    public String[] getSupportedCipherSuites() {
        return delegate.getSupportedCipherSuites();
    }

    @Override
    public Socket createSocket(Socket socket, String host, int port, boolean autoClose) throws IOException {
        // The path OkHttp takes: the raw socket is already connected, so this is the true peer.
        targets.requirePublic(peerOf(socket));
        return delegate.createSocket(socket, host, port, autoClose);
    }

    @Override
    public Socket createSocket(String host, int port) throws IOException {
        targets.resolve(host);
        return delegate.createSocket(host, port);
    }

    @Override
    public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
        targets.resolve(host);
        return delegate.createSocket(host, port, localHost, localPort);
    }

    @Override
    public Socket createSocket(InetAddress host, int port) throws IOException {
        targets.requirePublic(host);
        return delegate.createSocket(host, port);
    }

    @Override
    public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort)
            throws IOException {
        targets.requirePublic(address);
        return delegate.createSocket(address, port, localAddress, localPort);
    }

    private static InetAddress peerOf(Socket socket) throws UnknownHostException {
        InetAddress peer = socket.getInetAddress();
        if (peer == null) {
            throw new UnknownHostException("Socket has no peer address to check");
        }
        return peer;
    }
}
