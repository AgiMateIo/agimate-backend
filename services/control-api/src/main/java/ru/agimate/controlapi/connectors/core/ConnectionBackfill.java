package ru.agimate.controlapi.connectors.core;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.controlapi.connectors.core.secret.SecretService;
import ru.agimate.controlapi.connectors.integrations.IntegrationEncryptionService;
import ru.agimate.controlapi.database.entities.App;
import ru.agimate.controlapi.database.entities.Connection;
import ru.agimate.controlapi.database.entities.ConnectionTool;
import ru.agimate.controlapi.database.entities.ConnectionTrigger;
import ru.agimate.controlapi.database.entities.IntegrationCredentials;
import ru.agimate.controlapi.database.entities.McpTool;
import ru.agimate.controlapi.database.entities.Secret;
import ru.agimate.controlapi.database.repositories.AppRepository;
import ru.agimate.controlapi.database.repositories.ConnectionRepository;
import ru.agimate.controlapi.database.repositories.ConnectionToolRepository;
import ru.agimate.controlapi.database.repositories.ConnectionTriggerRepository;
import ru.agimate.controlapi.database.repositories.IntegrationCredentialsRepository;
import ru.agimate.controlapi.database.repositories.McpToolRepository;

import java.util.Map;
import java.util.UUID;

/**
 * Одноразовый идемпотентный бэкфилл существующих данных в единый реестр {@code connections}:
 * <ul>
 *   <li>{@code integration_credentials} → {@code connections} (id сохраняется) + перешифровка
 *       {@code encrypted_data} в {@code secrets} (envelope);</li>
 *   <li>{@code mcp_tool} → {@code connection_tools};</li>
 *   <li>{@code apps} → {@code connections} (id = app.id, app_id = app.id) + {@code apps.tools/triggers}
 *       JSONB → {@code connection_tools/triggers}.</li>
 * </ul>
 * Идемпотентно по {@code connections.existsById}. Удаляется вместе со старыми таблицами на этапе drop.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@Order(100)
public class ConnectionBackfill {

    private static final String SECRET_ENTITY = "connection";

    private final IntegrationCredentialsRepository integrationCredentialsRepository;
    private final AppRepository appRepository;
    private final McpToolRepository mcpToolRepository;
    private final ConnectionRepository connectionRepository;
    private final ConnectionToolRepository connectionToolRepository;
    private final ConnectionTriggerRepository connectionTriggerRepository;
    private final SecretService secretService;
    private final IntegrationEncryptionService legacyEncryption;

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void backfill() {
        int integrations = backfillIntegrations();
        int tools = backfillMcpTools();
        int apps = backfillApps();
        if (integrations + tools + apps > 0) {
            log.info("Connection backfill: {} integrations, {} mcp tools, {} apps migrated",
                    integrations, tools, apps);
        }
    }

    private int backfillIntegrations() {
        int migrated = 0;
        for (IntegrationCredentials ic : integrationCredentialsRepository.findAll()) {
            if (connectionRepository.existsById(ic.getId())) {
                continue;
            }
            Map<String, String> decrypted = legacyEncryption.decryptCredentials(ic.getEncryptedData());
            Secret secret = secretService.store(SECRET_ENTITY, ic.getId(), decrypted);

            Connection connection = Connection.builder()
                    .id(ic.getId())
                    .connectorCode(ic.getConnectorCode())
                    .subCode(ic.getPlatformIdentifier())
                    .fullCode(FullCodes.fullCode(ic.getConnectorCode(), ic.getPlatformIdentifier()))
                    .userId(ic.getUserId())
                    .name(ic.getName())
                    .secretId(secret.getId())
                    .webhookSecret(ic.getWebhookSecret())
                    .enabled(ic.getEnabled())
                    .lastUsedAt(ic.getLastUsedAt())
                    .deletedAt(ic.getDeletedAt())
                    .build();
            connectionRepository.save(connection);
            migrated++;
        }
        return migrated;
    }

    private int backfillMcpTools() {
        int migrated = 0;
        for (McpTool tool : mcpToolRepository.findAll()) {
            UUID connectionId = tool.getIntegrationCredentialsId();
            if (!connectionRepository.existsById(connectionId)
                    || connectionToolRepository.findActiveByConnectionIdAndName(connectionId, tool.getName()).isPresent()) {
                continue;
            }
            connectionToolRepository.save(ConnectionTool.builder()
                    .connectionId(connectionId)
                    .name(tool.getName())
                    .title(tool.getTitle())
                    .description(tool.getDescription())
                    .inputSchema(tool.getInputSchema())
                    .outputSchema(tool.getOutputSchema())
                    .annotations(tool.getAnnotations())
                    .build());
            migrated++;
        }
        return migrated;
    }

    private int backfillApps() {
        int migrated = 0;
        for (App app : appRepository.findAll()) {
            if (connectionRepository.existsById(app.getId())) {
                continue;
            }
            connectionRepository.save(Connection.builder()
                    .id(app.getId())
                    .connectorCode(app.getConnectorCode())
                    .subCode(FullCodes.slug(app.getConnectorCode(), app.getName()))
                    .fullCode(FullCodes.fullCode(app.getConnectorCode(), app.getName()))
                    .userId(app.getUserId())
                    .name(app.getName())
                    .appId(app.getId())
                    .enabled(app.getEnabled())
                    .deletedAt(app.getDeletedAt())
                    .build());

            mirrorCatalog(app);
            migrated++;
        }
        return migrated;
    }

    private void mirrorCatalog(App app) {
        if (app.getTools() != null) {
            app.getTools().forEach((name, value) -> connectionToolRepository.save(ConnectionTool.builder()
                    .connectionId(app.getId())
                    .name(name)
                    .description(description(value))
                    .build()));
        }
        if (app.getTriggers() != null) {
            app.getTriggers().forEach((name, value) -> connectionTriggerRepository.save(ConnectionTrigger.builder()
                    .connectionId(app.getId())
                    .name(name)
                    .description(description(value))
                    .build()));
        }
    }

    @SuppressWarnings("unchecked")
    private static String description(Object value) {
        if (value instanceof Map<?, ?> map) {
            Object d = ((Map<String, Object>) map).get("description");
            return d != null ? d.toString() : null;
        }
        return null;
    }
}
