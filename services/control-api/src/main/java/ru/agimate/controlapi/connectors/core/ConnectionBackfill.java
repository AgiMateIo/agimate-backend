package ru.agimate.controlapi.connectors.core;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.controlapi.connectors.core.secret.SecretService;
import ru.agimate.controlapi.connectors.integrations.IntegrationEncryptionService;
import ru.agimate.controlapi.database.entities.App;
import ru.agimate.controlapi.database.entities.Connection;
import ru.agimate.controlapi.database.entities.ConnectionTool;
import ru.agimate.controlapi.database.entities.ConnectionTrigger;
import ru.agimate.controlapi.database.entities.Secret;
import ru.agimate.controlapi.database.repositories.AppRepository;
import ru.agimate.controlapi.database.repositories.ConnectionRepository;
import ru.agimate.controlapi.database.repositories.ConnectionToolRepository;
import ru.agimate.controlapi.database.repositories.ConnectionTriggerRepository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Одноразовый идемпотентный бэкфилл legacy-данных в единый реестр {@code connections} с последующим
 * сносом старых таблиц. Выполняется на старте (после Liquibase-схемы), в одной транзакции —
 * мигрировать → дропнуть, поэтому при сбое миграции таблицы не теряются (откат).
 *
 * <ul>
 *   <li>{@code integration_credentials} → {@code connections} (id сохраняется) + перешифровка
 *       {@code encrypted_data} (legacy single-key) в {@code secrets} (envelope);</li>
 *   <li>{@code mcp_tool} → {@code connection_tools};</li>
 *   <li>{@code apps} → {@code connections} (id = app.id, app_id) + {@code apps.tools/triggers}
 *       JSONB → {@code connection_tools/triggers};</li>
 *   <li>затем {@code DROP TABLE integration_credentials, mcp_tool}.</li>
 * </ul>
 *
 * Legacy-таблицы читаются через JDBC (их JPA-сущности удалены). Идемпотентно по существованию
 * таблиц и {@code connections.existsById}. Удалить компонент можно, когда все окружения мигрированы.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@Order(100)
public class ConnectionBackfill {

    private static final String SECRET_ENTITY = "connection";

    private final JdbcTemplate jdbc;
    private final AppRepository appRepository;
    private final ConnectionRepository connectionRepository;
    private final ConnectionToolRepository connectionToolRepository;
    private final ConnectionTriggerRepository connectionTriggerRepository;
    private final SecretService secretService;
    private final IntegrationEncryptionService legacyEncryption;

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void backfill() {
        boolean hadIntegrations = tableExists("integration_credentials");
        boolean hadMcpTools = tableExists("mcp_tool");
        if (!hadIntegrations && !hadMcpTools && appsAllMigrated()) {
            return; // уже мигрировано и снесено
        }

        int integrations = hadIntegrations ? backfillIntegrations() : 0;
        int tools = hadMcpTools ? backfillMcpTools() : 0;
        int apps = backfillApps();

        if (hadMcpTools) {
            jdbc.execute("DROP TABLE IF EXISTS mcp_tool");
        }
        if (hadIntegrations) {
            jdbc.execute("DROP TABLE IF EXISTS integration_credentials CASCADE");
        }

        if (integrations + tools + apps > 0 || hadIntegrations || hadMcpTools) {
            log.info("Connection backfill: {} integrations, {} mcp tools, {} apps migrated; legacy tables dropped",
                    integrations, tools, apps);
        }
    }

    private int backfillIntegrations() {
        int migrated = 0;
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, connector_code, user_id, name, platform_identifier, encrypted_data,
                       webhook_secret, enabled, last_used_at, deleted_at
                FROM integration_credentials""");
        for (Map<String, Object> row : rows) {
            UUID id = (UUID) row.get("id");
            if (connectionRepository.existsById(id)) {
                continue;
            }
            String connectorCode = (String) row.get("connector_code");
            String platformIdentifier = (String) row.get("platform_identifier");
            Map<String, String> decrypted = legacyEncryption.decryptCredentials((String) row.get("encrypted_data"));
            Secret secret = secretService.store(SECRET_ENTITY, id, decrypted);

            connectionRepository.save(Connection.builder()
                    .id(id)
                    .connectorCode(connectorCode)
                    .subCode(platformIdentifier)
                    .fullCode(FullCodes.fullCode(connectorCode, platformIdentifier))
                    .userId((UUID) row.get("user_id"))
                    .name((String) row.get("name"))
                    .secretId(secret.getId())
                    .webhookSecret((String) row.get("webhook_secret"))
                    .enabled((Boolean) row.get("enabled"))
                    .lastUsedAt(toLdt(row.get("last_used_at")))
                    .deletedAt(toLdt(row.get("deleted_at")))
                    .build());
            migrated++;
        }
        return migrated;
    }

    private int backfillMcpTools() {
        int migrated = 0;
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT integration_credentials_id, name, title, description,
                       input_schema, output_schema, annotations
                FROM mcp_tool""");
        for (Map<String, Object> row : rows) {
            UUID connectionId = (UUID) row.get("integration_credentials_id");
            String name = (String) row.get("name");
            if (!connectionRepository.existsById(connectionId)
                    || connectionToolRepository.findActiveByConnectionIdAndName(connectionId, name).isPresent()) {
                continue;
            }
            connectionToolRepository.save(ConnectionTool.builder()
                    .connectionId(connectionId)
                    .name(name)
                    .title((String) row.get("title"))
                    .description((String) row.get("description"))
                    .inputSchema((String) row.get("input_schema"))
                    .outputSchema((String) row.get("output_schema"))
                    .annotations((String) row.get("annotations"))
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

    private boolean appsAllMigrated() {
        return appRepository.findAll().stream().allMatch(a -> connectionRepository.existsById(a.getId()));
    }

    private boolean tableExists(String table) {
        Boolean exists = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM information_schema.tables
                               WHERE table_schema = 'public' AND table_name = ?)""",
                Boolean.class, table);
        return Boolean.TRUE.equals(exists);
    }

    private static LocalDateTime toLdt(Object value) {
        return value instanceof Timestamp ts ? ts.toLocalDateTime() : null;
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
