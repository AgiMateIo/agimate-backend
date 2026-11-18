package ru.agimate.controlapi.connectors.integrations.ssh;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ru.agimate.controlapi.connectors.core.ConnectorEnv;
import ru.agimate.controlapi.connectors.core.ConnectorEnvHolder;
import ru.agimate.controlapi.connectors.core.annotation.Tool;
import ru.agimate.controlapi.connectors.core.annotation.ToolAnnotations;
import ru.agimate.controlapi.connectors.core.annotation.ToolParam;

/**
 * The SSH connector's one tool. The context (credentials, connectionId) arrives through
 * {@link ConnectorEnvHolder}, and the binding is done by {@code BaseConnectorHandler} in
 * {@link SshConnectorService}.
 */
@Component
@RequiredArgsConstructor
public class SshToolService {

    public static final String TOOL_RUN_COMMAND = "run_command";

    private final SshExecutor executor;

    @Tool(name = TOOL_RUN_COMMAND,
            description = "Run an arbitrary shell command on the SSH host and return its stdout, stderr and "
                    + "exit code. The command runs in a fresh non-interactive SSH session: there is no "
                    + "persistent shell, no stdin and no terminal, so an interactive program will not work. "
                    + "The command is stopped when its timeout expires.",
            timeoutSeconds = 1800,
            annotations = @ToolAnnotations(readOnlyHint = false, destructiveHint = true,
                    idempotentHint = false, openWorldHint = true))
    public SshCommandResult runCommand(
            @ToolParam("Shell command to run on the remote host") String command,
            @ToolParam(value = "Command timeout in seconds (1-1800); defaults to 60", required = false)
            Integer timeoutSeconds) {
        SshCommand validated = SshCommand.of(command, timeoutSeconds);
        ConnectorEnv env = ConnectorEnvHolder.current();
        return executor.execute(env.connectionId(),
                SshConnectionConfig.fromCredentials(env.credentials()), validated);
    }
}
