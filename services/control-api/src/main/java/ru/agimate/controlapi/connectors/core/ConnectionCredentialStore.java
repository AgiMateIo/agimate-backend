package ru.agimate.controlapi.connectors.core;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ru.agimate.controlapi.connectors.core.dto.CredentialField;
import ru.agimate.controlapi.database.entities.Connection;
import ru.agimate.controlapi.database.entities.Secret;
import ru.agimate.controlapi.database.repositories.SecretRepository;
import ru.agimate.controlapi.service.secret.SecretService;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The only reader and writer of a connection's credentials. A connector sees one flat map; it is kept
 * in two parts by the connector's own declaration — the fields declared non-SECRET openly in
 * {@link Connection#getParams()}, where a skill's requirement is matched against them, everything else
 * (SECRET fields, what validation derived, OAuth state) in the encrypted secret. A key lives in
 * exactly one part, so gluing them back needs no precedence rule.
 */
@Component
@RequiredArgsConstructor
public class ConnectionCredentialStore {

    private static final String SECRET_ENTITY = "connection";

    private final ConnectorRegistry connectorRegistry;
    private final SecretRepository secretRepository;
    private final SecretService secretService;

    public Map<String, String> read(Connection connection) {
        Map<String, String> credentials = new LinkedHashMap<>(connection.getParams());
        if (connection.getSecretId() != null) {
            credentials.putAll(secretService.reveal(secret(connection), connection.getId()));
        }
        return credentials;
    }

    /**
     * Replaces both parts. The row's {@code params} is set on the entity and persists with the
     * caller's transaction; the secret is created on the first write, so an integration connection
     * always has one, if only an empty one.
     */
    public void write(Connection connection, Map<String, String> credentials) {
        Map<String, CredentialField> fields = connectorRegistry.findIntegrationHandler(connection.getConnectorCode())
                .map(IntegrationConnectorHandler::getCredentialFields)
                .orElse(Map.of());
        Map<String, String> open = new LinkedHashMap<>();
        Map<String, String> secret = new LinkedHashMap<>();
        CredentialField.normalize(fields, credentials).forEach((code, value) -> {
            CredentialField field = fields.get(code);
            if (field != null && !field.secret()) {
                if (value != null) {
                    open.put(code, value);
                }
            } else {
                secret.put(code, value);
            }
        });

        connection.setParams(open);
        if (connection.getSecretId() == null) {
            connection.setSecretId(secretService.store(SECRET_ENTITY, connection.getId(), secret).getId());
        } else {
            secretService.update(secret(connection), connection.getId(), secret);
        }
    }

    private Secret secret(Connection connection) {
        return secretRepository.findById(connection.getSecretId())
                .orElseThrow(() -> new ConnectorException("Secret not found for connection " + connection.getId()));
    }
}
