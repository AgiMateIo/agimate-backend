package ru.agimate.connectorsapi.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.common.util.JsonUtils;
import ru.agimate.connectorsapi.connector.ConnectorRegistry;
import ru.agimate.connectorsapi.controller.dto.request.CreateCredentialRequest;
import ru.agimate.connectorsapi.controller.dto.request.UpdateCredentialRequest;
import ru.agimate.connectorsapi.controller.dto.response.ConnectorSummaryResponse;
import ru.agimate.connectorsapi.controller.dto.response.CredentialResponse;
import ru.agimate.connectorsapi.database.entities.Connector;
import ru.agimate.connectorsapi.database.entities.Credential;
import ru.agimate.connectorsapi.database.projections.CredentialShortInfoProjection;
import ru.agimate.connectorsapi.database.repositories.ConnectorRepository;
import ru.agimate.connectorsapi.database.repositories.CredentialRepository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class CredentialService {

    private final CredentialRepository credentialRepository;
    private final ConnectorRepository connectorRepository;
    private final ConnectorRegistry connectorRegistry;
    private final CredentialEncryptionService encryptionService;

    public List<ConnectorSummaryResponse> getCredentialsSummary(UUID userPubId) {
        return credentialRepository.findCredentialsSummaryByUser(userPubId).stream()
                .map(p -> new ConnectorSummaryResponse(
                        p.getConnectorCode(),
                        p.getConnectorName(),
                        p.getCredentialCount(),
                        p.getLastAddedAt(),
                        p.getLastUsedAt()
                ))
                .toList();
    }

    public List<CredentialResponse> getCredentials(String connectorCode, UUID userPubId) {
        return credentialRepository.findByConnectorCodeAndUserPubIdNotDeleted(connectorCode.toLowerCase(), userPubId)
                .stream()
                .map(CredentialResponse::from)
                .toList();
    }

    public List<CredentialShortInfoProjection> getAllCredentialsByUserPubId(UUID userPubId) {
        return credentialRepository.findShortInfoByUserPubId(userPubId);
    }

    public CredentialResponse getCredential(String connectorCode, UUID credentialId, UUID userPubId) {
        Credential credential = findCredentialByPubIdAndUser(credentialId, userPubId);
        validateConnectorMatch(credential, connectorCode);
        return CredentialResponse.from(credential);
    }

    @Transactional
    public CredentialResponse createCredential(String connectorCode, CreateCredentialRequest request, UUID userPubId) {
        Connector connector = connectorRepository.findByCode(connectorCode.toLowerCase())
                .orElseThrow(() -> new NotFoundStatusException("Connector not found: " + connectorCode));

        // Validate required fields
        if (connectorRegistry.hasDefinition(connectorCode)) {
            List<String> requiredFields = connectorRegistry.getRequiredCredentialFields(connectorCode);
            for (String field : requiredFields) {
                if (!request.data().containsKey(field) || request.data().get(field).isBlank()) {
                    throw new BadRequestStatusException("Missing required field: " + field);
                }
            }
        }

        // Encrypt credential data
        String jsonData = toJson(request.data());
        CredentialEncryptionService.EncryptedData encrypted = encryptionService.encrypt(jsonData);

        Credential credential = Credential.builder()
                .connector(connector)
                .userPubId(userPubId)
                .name(request.name())
                .description(request.description())
                .encryptedData(encrypted.ciphertext())
                .encryptionIv(encrypted.iv())
                .build();

        Credential saved = credentialRepository.save(credential);
        log.info("Created credential {} for connector {} (user: {})", saved.getPubId(), connectorCode, userPubId);

        return CredentialResponse.from(saved);
    }

    @Transactional
    public CredentialResponse updateCredential(String connectorCode, UUID credentialId, UpdateCredentialRequest request, UUID userPubId) {
        Credential credential = findCredentialByPubIdAndUser(credentialId, userPubId);
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
            CredentialEncryptionService.EncryptedData encrypted = encryptionService.encrypt(jsonData);
            credential.setEncryptedData(encrypted.ciphertext());
            credential.setEncryptionIv(encrypted.iv());
        }

        Credential saved = credentialRepository.save(credential);
        log.info("Updated credential {}", credentialId);

        return CredentialResponse.from(saved);
    }

    @Transactional
    public void deleteCredential(String connectorCode, UUID credentialId, UUID userPubId) {
        Credential credential = findCredentialByPubIdAndUser(credentialId, userPubId);
        validateConnectorMatch(credential, connectorCode);

        credentialRepository.softDelete(credential.getId(), LocalDateTime.now());
        log.info("Soft deleted credential {}", credentialId);
    }

    public Map<String, String> getDecryptedCredentialData(UUID credentialId) {
        Credential credential = findCredentialByPubId(credentialId);
        String decrypted = encryptionService.decrypt(
                credential.getEncryptedData(),
                credential.getEncryptionIv()
        );
        return fromJson(decrypted);
    }

    @Transactional
    public void updateLastUsedAt(Long credentialId) {
        credentialRepository.updateLastUsedAt(credentialId, LocalDateTime.now());
    }

    private Credential findCredentialByPubId(UUID pubId) {
        return credentialRepository.findByPubIdNotDeleted(pubId)
                .orElseThrow(() -> new NotFoundStatusException("Credential not found"));
    }

    private Credential findCredentialByPubIdAndUser(UUID pubId, UUID userPubId) {
        return credentialRepository.findByPubIdAndUserPubIdNotDeleted(pubId, userPubId)
                .orElseThrow(() -> new NotFoundStatusException("Credential not found"));
    }

    private void validateConnectorMatch(Credential credential, String connectorCode) {
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

    public Collection<CredentialShortInfoProjection> getAllCredentialsByUserPubIdAndConnectorCode(UUID userPubId, String connectorCode) {
        return credentialRepository.findShortInfoByUserPubIdAndConnectorCode(userPubId, connectorCode);
    }
}
