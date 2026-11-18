package ru.agimate.controlapi.connectors.integrations.ssh;

import org.apache.sshd.common.NamedResource;
import org.apache.sshd.common.config.keys.FilePasswordProvider;
import org.apache.sshd.common.util.security.SecurityUtils;
import ru.agimate.controlapi.connectors.core.ConnectorException;

import java.io.IOException;
import java.io.StringReader;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.util.Collection;

/**
 * Parses private-key text into exactly one key pair. It never touches the filesystem or the user's
 * {@code ~/.ssh}: the connection stores key content, not a path. Usage errors, an unsupported
 * format and a wrong passphrase all surface as one generic {@link ConnectorException} so no key
 * material or password reaches a message.
 */
public final class SshKeyLoader {

    static final String GENERIC_MESSAGE = "Unable to read the SSH private key";

    private SshKeyLoader() {
    }

    public static KeyPair loadKeyPair(String privateKey, String passphrase) {
        if (privateKey == null || privateKey.isBlank()) {
            throw new ConnectorException(GENERIC_MESSAGE);
        }
        FilePasswordProvider passwordProvider =
                passphrase == null || passphrase.isBlank() ? FilePasswordProvider.EMPTY : FilePasswordProvider.of(passphrase);
        Collection<KeyPair> keyPairs;
        try {
            keyPairs = SecurityUtils.getKeyPairResourceParser().loadKeyPairs(
                    null,
                    NamedResource.ofName("privateKey"),
                    passwordProvider,
                    new StringReader(privateKey));
        } catch (IOException | GeneralSecurityException | RuntimeException ignored) {
            // The library exception can carry format internals; the connector reports one safe text.
            throw new ConnectorException(GENERIC_MESSAGE);
        }
        if (keyPairs == null || keyPairs.size() != 1) {
            throw new ConnectorException(GENERIC_MESSAGE);
        }
        KeyPair keyPair = keyPairs.iterator().next();
        if (keyPair == null || keyPair.getPublic() == null || keyPair.getPrivate() == null) {
            throw new ConnectorException(GENERIC_MESSAGE);
        }
        return keyPair;
    }
}
