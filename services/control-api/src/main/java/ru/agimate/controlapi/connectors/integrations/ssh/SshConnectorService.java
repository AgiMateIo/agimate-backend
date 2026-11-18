package ru.agimate.controlapi.connectors.integrations.ssh;

import org.springframework.stereotype.Component;
import ru.agimate.controlapi.connectors.core.BaseConnectorHandler;
import ru.agimate.controlapi.connectors.core.ConnectorException;
import ru.agimate.controlapi.connectors.core.IntegrationConnectorHandler;
import ru.agimate.controlapi.connectors.core.ToolExecutionPolicy;
import ru.agimate.controlapi.connectors.core.dto.CredentialField;
import ru.agimate.controlapi.connectors.core.dto.CredentialField.Type;
import ru.agimate.controlapi.connectors.core.dto.IntegrationValidationResult;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Facade of the SSH connector: the credential form and the one tool of {@link SshToolService}. The
 * instance's identity is {@code username@host:port}, the canonical form of the open fields, so it is
 * returned as {@code derivedCredentials} and written back to {@code connections.params} — a skill's
 * requirement matches those values as they are.
 */
@Component
public class SshConnectorService extends BaseConnectorHandler implements IntegrationConnectorHandler {

    public static final String CONNECTOR_CODE = "ssh";

    /** The SSH setup, authentication and teardown around the command's own timeout. */
    private static final long POLICY_GRACE_SECONDS = 60L;

    private static final Map<String, CredentialField> CREDENTIAL_FIELDS = credentialFields();

    private final SshExecutor executor;

    public SshConnectorService(SshToolService toolService, SshExecutor executor) {
        super(toolService);
        this.executor = executor;
    }

    private static Map<String, CredentialField> credentialFields() {
        Map<String, CredentialField> fields = new LinkedHashMap<>();
        fields.put("host", CredentialField.required("Host name or IP address", Type.TEXT));
        fields.put("port", CredentialField.optional("Port (default 22)", Type.TEXT));
        fields.put("username", CredentialField.required("User name", Type.TEXT));
        fields.put("hostKeyFingerprint", CredentialField.required("Host key fingerprint (SHA256:…)", Type.TEXT));
        fields.put("privateKey",
                CredentialField.optional("Private key in OpenSSH format", Type.SECRET).withMultiline());
        fields.put("privateKeyPassphrase", CredentialField.optional("Private key passphrase", Type.SECRET));
        fields.put("password", CredentialField.optional("Password", Type.SECRET));
        return Collections.unmodifiableMap(fields);
    }

    @Override
    public String connectorCode() {
        return CONNECTOR_CODE;
    }

    @Override
    public String connectorName() {
        return "SSH";
    }

    @Override
    public String connectorDescription() {
        return "A server reached over SSH: the agent runs commands there with a key or a password, "
                + "and only when the host key matches the pinned fingerprint.";
    }

    /** Two connections to one host are two accounts on it. */
    @Override
    public boolean allowsMultipleInstances() {
        return true;
    }

    @Override
    public Map<String, CredentialField> getCredentialFields() {
        return CREDENTIAL_FIELDS;
    }

    /** Handshake and authentication only — a validation never runs a command. */
    @Override
    public IntegrationValidationResult validateCredentials(Map<String, String> credentials) {
        SshConnectionConfig config;
        try {
            config = SshConnectionConfig.fromCredentials(credentials);
        } catch (SshConnectionConfig.ValidationException e) {
            return IntegrationValidationResult.failure(e.field(), e.getMessage());
        }
        try {
            executor.validate(config);
        } catch (ConnectorException e) {
            return IntegrationValidationResult.failure(failureField(e, config), e.getMessage());
        }
        return new IntegrationValidationResult(IntegrationValidationResult.Outcome.VALID,
                config.identifier(), config.displayName(), null, null, config.derivedCredentials());
    }

    /**
     * The executor's messages are the contract here: nothing else tells which secret was rejected.
     * Anything unrecognised still points at the host — that is where reachability failed.
     */
    private static String failureField(ConnectorException failure, SshConnectionConfig config) {
        String message = failure.getMessage() == null ? "" : failure.getMessage();
        if (message.startsWith(SshExecutor.HOST_KEY_MISMATCH)) {
            return "hostKeyFingerprint";
        }
        if (message.startsWith(SshExecutor.AUTHENTICATION_FAILED)) {
            return config.privateKey() != null ? "privateKey" : "password";
        }
        if (message.startsWith(SshKeyLoader.GENERIC_MESSAGE) && config.privateKey() != null) {
            return "privateKey";
        }
        return "host";
    }

    /** A command on a remote host cannot be repeated: the call carries the whole attempt's budget. */
    @Override
    public ToolExecutionPolicy executionPolicy(String toolName, Map<String, Object> input) {
        if (!SshToolService.TOOL_RUN_COMMAND.equals(toolName)) {
            return super.executionPolicy(toolName, input);
        }
        Object timeout = input == null ? null : input.get("timeoutSeconds");
        return ToolExecutionPolicy.nonReplayable(
                Duration.ofSeconds(SshCommand.timeoutSeconds(timeout) + POLICY_GRACE_SECONDS));
    }
}
