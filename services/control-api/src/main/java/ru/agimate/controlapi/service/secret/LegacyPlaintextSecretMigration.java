package ru.agimate.controlapi.service.secret;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.controlapi.database.entities.Secret;

import java.util.UUID;

/**
 * Одноразовый бэкфилл plaintext-секретов в envelope-шифрованный стор {@code secrets}
 * (миграция {@code 14-00-webhook-secrets-encryption}):
 * <ul>
 *   <li>{@code connections.webhook_secret} → entity {@code connection_webhook};</li>
 *   <li>{@code agents.webhook_auth_header} → entity {@code agent_webhook_auth}.</li>
 * </ul>
 * Старые колонки читаются сырым SQL (в JPA-сущностях их больше нет) и зануляются после переноса —
 * повторный запуск no-op. Колонки дропаются отдельной миграцией после подтверждения бэкфилла.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LegacyPlaintextSecretMigration implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;
    private final SecretService secretService;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        int connections = migrate(
                "SELECT id, webhook_secret FROM connections WHERE webhook_secret IS NOT NULL AND webhook_secret <> ''",
                "webhook_secret",
                "connection_webhook",
                "UPDATE connections SET webhook_secret_id = ?, webhook_secret = NULL WHERE id = ?");
        int agents = migrate(
                "SELECT id, webhook_auth_header FROM agents WHERE webhook_auth_header IS NOT NULL AND webhook_auth_header <> ''",
                "webhook_auth_header",
                "agent_webhook_auth",
                "UPDATE agents SET webhook_auth_secret_id = ?, webhook_auth_header = NULL WHERE id = ?");
        if (connections > 0 || agents > 0) {
            log.info("Migrated legacy plaintext secrets: {} connection webhook secret(s), {} agent auth header(s)",
                    connections, agents);
        }
    }

    private int migrate(String selectSql, String valueColumn, String secretEntity, String updateSql) {
        var rows = jdbcTemplate.queryForList(selectSql);
        for (var row : rows) {
            UUID ownerId = (UUID) row.get("id");
            String plaintext = (String) row.get(valueColumn);
            Secret secret = secretService.storeValue(secretEntity, ownerId, plaintext);
            jdbcTemplate.update(updateSql, secret.getId(), ownerId);
        }
        return rows.size();
    }
}
