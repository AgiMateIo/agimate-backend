package ru.agimate.connectorsapi.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.common.util.JsonUtils;
import ru.agimate.connectorsapi.controller.manage.dto.request.CreateConnectorCredentialRequest;
import ru.agimate.connectorsapi.controller.manage.dto.request.UpdateConnectorCredentialRequest;
import ru.agimate.connectorsapi.controller.manage.dto.response.ConnectorSummaryResponse;
import ru.agimate.connectorsapi.controller.manage.dto.response.ConnectorCredentialResponse;
import ru.agimate.connectorsapi.database.entities.Connector;
import ru.agimate.connectorsapi.database.entities.ConnectorCredential;
import ru.agimate.connectorsapi.database.projections.ConnectorCredentialShortInfoProjection;
import ru.agimate.connectorsapi.database.repositories.ConnectorRepository;
import ru.agimate.connectorsapi.database.repositories.ConnectorCredentialRepository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class ConnectorCredentialService {

    private final ConnectorCredentialRepository connectorCredentialRepository;
    private final ConnectorRepository connectorRepository;
    private final ConnectorCredentialEncryptionService encryptionService;

    public List<ConnectorSummaryResponse> getCredentialsSummary(UUID userPubId) {
        return connectorCredentialRepository.findCredentialsSummaryByUser(userPubId).stream()
                .map(p -> new ConnectorSummaryResponse(
                        p.getConnectorCode(),
                        p.getConnectorName(),
                        p.getCredentialCount(),
                        p.getLastAddedAt(),
                        p.getLastUsedAt()
                ))
                .toList();
    }

    public List<ConnectorCredentialResponse> getCredentials(String connectorCode, UUID userPubId) {
        return connectorCredentialRepository.findByConnectorCodeAndUserPubIdNotDeleted(connectorCode.toLowerCase(), userPubId)
                .stream()
                .map(ConnectorCredentialResponse::from)
                .toList();
    }

    public List<ConnectorCredentialShortInfoProjection> getAllCredentialsByUserPubId(UUID userPubId) {
        return connectorCredentialRepository.findShortInfoByUserPubId(userPubId);
    }

    public ConnectorCredentialResponse getCredential(String connectorCode, UUID credentialId, UUID userPubId) {
        ConnectorCredential credential = findCredentialByPubIdAndUser(credentialId, userPubId);
        validateConnectorMatch(credential, connectorCode);
        return ConnectorCredentialResponse.from(credential);
    }

    @Transactional
    public ConnectorCredentialResponse createCredential(String connectorCode, CreateConnectorCredentialRequest request, UUID userPubId) {
        Connector connector = connectorRepository.findByCode(connectorCode.toLowerCase())
                .orElseThrow(() -> new NotFoundStatusException("Connector not found: " + connectorCode));

        // Encrypt credential data
        String jsonData = toJson(request.data());
        ConnectorCredentialEncryptionService.EncryptedData encrypted = encryptionService.encrypt(jsonData);

        ConnectorCredential credential = ConnectorCredential.builder()
                .connector(connector)
                .userPubId(userPubId)
                .name(request.name())
                .description(request.description())
                .encryptedData(encrypted.ciphertext())
                .encryptionIv(encrypted.iv())
                .build();

        ConnectorCredential saved = connectorCredentialRepository.save(credential);
        log.info("Created credential {} for connector {} (user: {})", saved.getPubId(), connectorCode, userPubId);

        return ConnectorCredentialResponse.from(saved);
    }

    @Transactional
    public ConnectorCredentialResponse updateCredential(String connectorCode, UUID credentialId, UpdateConnectorCredentialRequest request, UUID userPubId) {
        ConnectorCredential credential = findCredentialByPubIdAndUser(credentialId, userPubId);
        validateConnectorMatch(credential, connectorCode);

        if (request.name() != null) {
            credential.setName(request.name());
        }
        if (request.description() != null) {
            credential.setDescription(request.description());
        }
        if (request.enabled() != null) {
            credential.setEnabled(request.enabled());
        }
        if (request.data() != null && !request.data().isEmpty()) {
            String jsonData = toJson(request.data());
            ConnectorCredentialEncryptionService.EncryptedData encrypted = encryptionService.encrypt(jsonData);
            credential.setEncryptedData(encrypted.ciphertext());
            credential.setEncryptionIv(encrypted.iv());
        }

        ConnectorCredential saved = connectorCredentialRepository.save(credential);
        log.info("Updated credential {}", credentialId);

        return ConnectorCredentialResponse.from(saved);
    }

    @Transactional
    public void deleteCredential(String connectorCode, UUID credentialId, UUID userPubId) {
        ConnectorCredential credential = findCredentialByPubIdAndUser(credentialId, userPubId);
        validateConnectorMatch(credential, connectorCode);

        connectorCredentialRepository.softDelete(credential.getId(), LocalDateTime.now());
        log.info("Soft deleted credential {}", credentialId);
    }

    public Map<String, String> getDecryptedCredentialData(UUID credentialId) {
        ConnectorCredential credential = findCredentialByPubId(credentialId);
        String decrypted = encryptionService.decrypt(
                credential.getEncryptedData(),
                credential.getEncryptionIv()
        );
        return fromJson(decrypted);
    }

    @Transactional
    public void updateLastUsedAt(Long credentialId) {
        connectorCredentialRepository.updateLastUsedAt(credentialId, LocalDateTime.now());
    }

    private ConnectorCredential findCredentialByPubId(UUID pubId) {
        return connectorCredentialRepository.findByPubIdNotDeleted(pubId)
                .orElseThrow(() -> new NotFoundStatusException("Credential not found"));
    }

    private ConnectorCredential findCredentialByPubIdAndUser(UUID pubId, UUID userPubId) {
        return connectorCredentialRepository.findByPubIdAndUserPubIdNotDeleted(pubId, userPubId)
                .orElseThrow(() -> new NotFoundStatusException("Credential not found"));
    }

    private void validateConnectorMatch(ConnectorCredential credential, String connectorCode) {
        if (!credential.getConnector().getCode().equalsIgnoreCase(connectorCode)) {
            throw new NotFoundStatusException("Credential not found for connector: " + connectorCode);
        }
    }

    private String toJson(Map<String, String> data) {
        try {
            return JsonUtils.writeValueAsString(data);
        } catch (Exception e) {
            throw new BadRequestStatusException("Invalid credential data format");
        }
    }

    private Map<String, String> fromJson(String json) {
        try {
            return JsonUtils.readValue(json, JsonUtils.MAP_STRING_TYPE_REFERENCE);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse credential data", e);
        }
    }

    public Collection<ConnectorCredentialShortInfoProjection> getAllCredentialsByUserPubIdAndConnectorCode(UUID userPubId, String connectorCode) {
        return connectorCredentialRepository.findShortInfoByUserPubIdAndConnectorCode(userPubId, connectorCode);
    }
}
