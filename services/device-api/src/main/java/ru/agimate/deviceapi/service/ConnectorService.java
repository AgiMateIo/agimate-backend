package ru.agimate.deviceapi.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.common.rest.error.ConflictStatusException;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.common.rest.error.UnauthorizedStatusException;
import ru.agimate.deviceapi.controller.app.dto.LinkDeviceRequest;
import ru.agimate.deviceapi.database.entities.Connector;
import ru.agimate.deviceapi.database.enums.ConnectorType;
import ru.agimate.deviceapi.database.repositories.ConnectorRepository;
import ru.agimate.deviceapi.security.ConnectorSecurityUtils;
import ru.agimate.deviceapi.service.dto.ConnectorCreateResult;
import ru.agimate.deviceapi.util.ConnectorKeyUtils;
import ru.agimate.deviceapi.util.GeneratedConnectorKey;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class ConnectorService {

    private static final int MAX_KEYS_PER_USER = 10;
    public static final String CONNECTOR_KEY_PREFIX = "dvck";

    private final ConnectorRepository connectorRepository;

    @Transactional
    public ConnectorCreateResult createConnector(UUID userPubId, String name, String description) {
        long existingCount = connectorRepository.countByUserPubIdNotDeleted(userPubId);
        if (existingCount >= MAX_KEYS_PER_USER) {
            throw new ConflictStatusException("Maximum number of connectors reached: " + MAX_KEYS_PER_USER);
        }

        if (connectorRepository.existsByUserPubIdAndName(userPubId, name)) {
            throw new ConflictStatusException("A connector with this name already exists");
        }

        GeneratedConnectorKey generatedKey = ConnectorKeyUtils.generate(CONNECTOR_KEY_PREFIX);

        Connector connector = Connector.builder()
                .userPubId(userPubId)
                .name(name)
                .description(description)
                .keyHash(generatedKey.secretHash())
                .keyId(generatedKey.keyId())
                .enabled(true)
                .build();

        Connector saved = connectorRepository.save(connector);
        log.info("Created new connector for user {}: {}", userPubId, saved.getPubId());

        return new ConnectorCreateResult(saved, generatedKey.fullKey());
    }

    @Transactional
    public Connector createOutboundConnector(
            UUID userPubId,
            String name,
            String description,
            Map<String, Object> triggers,
            Map<String, Object> tools
    ) {
        long existingCount = connectorRepository.countByUserPubIdNotDeleted(userPubId);
        if (existingCount >= MAX_KEYS_PER_USER) {
            throw new ConflictStatusException("Maximum number of connectors reached: " + MAX_KEYS_PER_USER);
        }

        GeneratedConnectorKey generatedKey = ConnectorKeyUtils.generate(CONNECTOR_KEY_PREFIX);

        Connector connector = Connector.builder()
                .userPubId(userPubId)
                .name(name)
                .description(description)
                .keyHash(generatedKey.secretHash())
                .keyId(generatedKey.keyId())
                .type(ConnectorType.OUTBOUND)
                .triggers(triggers)
                .tools(tools)
                .enabled(true)
                .build();

        Connector saved = connectorRepository.save(connector);
        log.info("Created outbound connector for user {}: {}", userPubId, saved.getPubId());

        return saved;
    }

    @Transactional
    public Connector createServerConnector(
            UUID userPubId,
            String name,
            String description,
            Map<String, Object> triggers,
            Map<String, Object> tools
    ) {
        GeneratedConnectorKey generatedKey = ConnectorKeyUtils.generate(CONNECTOR_KEY_PREFIX);

        Connector connector = Connector.builder()
                .userPubId(userPubId)
                .name(name)
                .description(description)
                .keyHash(generatedKey.secretHash())
                .keyId(generatedKey.keyId())
                .type(ConnectorType.SERVER)
                .triggers(triggers)
                .tools(tools)
                .enabled(true)
                .build();

        Connector saved = connectorRepository.save(connector);
        log.info("Created server connector for user {}: {}", userPubId, saved.getPubId());

        return saved;
    }

    public List<Connector> getConnectorsForUser(UUID userPubId) {
        return connectorRepository.findByUserPubIdNotDeleted(userPubId);
    }

    public Optional<Connector> getConnectorByPubIdForUser(UUID pubId, UUID userPubId) {
        return connectorRepository.findByPubIdNotDeleted(pubId)
                .filter(key -> key.getUserPubId().equals(userPubId));
    }

    @Transactional
    public Connector updateConnector(UUID pubId, UUID userPubId, String name, String description, Boolean enabled) {
        Connector connector = connectorRepository.findByPubIdNotDeleted(pubId)
                .filter(k -> k.getUserPubId().equals(userPubId))
                .orElseThrow(() -> new NotFoundStatusException("Connector not found"));

        if (name != null) connector.setName(name);
        if (description != null) connector.setDescription(description);
        if (enabled != null) connector.setEnabled(enabled);

        return connectorRepository.save(connector);
    }

    @Transactional
    public void deleteConnector(UUID pubId, UUID userPubId) {
        Connector connector = connectorRepository.findByPubIdNotDeleted(pubId)
                .filter(k -> k.getUserPubId().equals(userPubId))
                .orElseThrow(() -> new NotFoundStatusException("Connector not found"));

        connectorRepository.softDelete(connector.getId(), LocalDateTime.now());
        log.info("Soft deleted connector: {}", pubId);
    }

    @Transactional
    public ConnectorCreateResult regenerateConnectorKey(UUID pubId, UUID userPubId) {
        Connector oldConnector = connectorRepository.findByPubIdNotDeleted(pubId)
                .filter(k -> k.getUserPubId().equals(userPubId))
                .orElseThrow(() -> new NotFoundStatusException("Connector not found"));

        connectorRepository.softDelete(oldConnector.getId(), LocalDateTime.now());

        return createConnector(userPubId, oldConnector.getName(), oldConnector.getDescription());
    }

    public Connector getConnectorByPubId(UUID pubId, UUID userPubId) {
        return connectorRepository.findByPubIdNotDeleted(pubId)
                .filter(a -> a.getUserPubId().equals(userPubId))
                .orElseThrow(() -> new NotFoundStatusException("Connector not found"));
    }

    @Transactional
    public void disconnectConnector(UUID pubId, UUID userPubId) {
        Connector connector = connectorRepository.findByPubIdNotDeleted(pubId)
                .filter(a -> a.getUserPubId().equals(userPubId))
                .orElseThrow(() -> new NotFoundStatusException("Connector not found"));

        if (!connector.isLinked()) {
            throw new BadRequestStatusException("Connector is not linked to any device");
        }

        connector.disconnect();
        connectorRepository.save(connector);
        log.info("Disconnected connector {}", pubId);
    }

    public Connector getConnector() {
        UUID connectorPubId = ConnectorSecurityUtils.getConnectorPubId();
        return connectorRepository.findByPubId(connectorPubId)
                .orElseThrow(() -> new UnauthorizedStatusException("Connector not found"));
    }

    public Connector getConnector(Authentication authentication) {
        var principal = ConnectorSecurityUtils.getPrincipal(authentication);
        return connectorRepository.findByPubId(principal.connectorPubId())
                .orElseThrow(() -> new UnauthorizedStatusException("Connector not found"));
    }

    @Transactional
    public Connector linkDevice(Authentication authentication, LinkDeviceRequest linkDeviceRequest) {
        var connector = getConnector(authentication);

        // If already linked to the same device — update capabilities
        if (connector.isLinked() && connector.getDeviceId().equals(linkDeviceRequest.deviceId())) {
            connector.setDeviceFeatures(buildDeviceFeatures(linkDeviceRequest));
            connector.setTriggers(linkDeviceRequest.triggers());
            connector.setTools(linkDeviceRequest.tools());
            return connectorRepository.save(connector);
        }

        // If already linked to a different device — conflict
        if (connector.isLinked()) {
            log.warn("Connector {} is already linked to device {}", connector.getPubId(), connector.getDeviceId());
            return null;
        }

        // Link device
        connector.setDeviceId(linkDeviceRequest.deviceId());
        connector.setDeviceFeatures(buildDeviceFeatures(linkDeviceRequest));
        connector.setTriggers(linkDeviceRequest.triggers());
        connector.setTools(linkDeviceRequest.tools());
        connector = connectorRepository.save(connector);
        log.info("Linked device {} to connector {}", linkDeviceRequest.deviceId(), connector.getPubId());

        return connector;
    }

    private Map<String, Object> buildDeviceFeatures(LinkDeviceRequest request) {
        var features = request.deviceFeatures() != null
                ? new HashMap<>(request.deviceFeatures())
                : new HashMap<String, Object>();
        if (request.deviceName() != null) features.put("deviceName", request.deviceName());
        if (request.deviceOs() != null) features.put("deviceOs", request.deviceOs());
        return features;
    }
}
