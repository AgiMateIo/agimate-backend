package ru.agimate.connectorsapi.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.connectorsapi.database.entities.Connector;
import ru.agimate.connectorsapi.database.repositories.ConnectorRepository;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class ConnectorService {

    private final ConnectorRepository connectorRepository;

    public List<Connector> getAllConnectors() {
        return connectorRepository.findAllEnabled();
    }

    public Connector getConnectorByCode(String code) {
        return connectorRepository.findByCode(code.toLowerCase())
                .orElseThrow(() -> new NotFoundStatusException("Connector not found: " + code));
    }

    public Connector getConnectorByPubId(UUID pubId) {
        return connectorRepository.findByPubId(pubId)
                .orElseThrow(() -> new NotFoundStatusException("Connector not found"));
    }

    public List<Connector> getAllConnectorsByUserPubId(UUID userPubId) {
        return connectorRepository.findByUserPubId(userPubId);
    }

    /**
     * Get connectors available for user:
     * - Connectors with active credentials (enabled=true, deletedAt=null)
     * - PLUS always include "mobile" connector
     */
    public List<Connector> getAvailableConnectorsForUser(UUID userPubId) {
        // Get connectors where user has active credentials
        List<Connector> connectorsWithCredentials = connectorRepository.findByUserPubId(userPubId);

        // Always include "mobile" connector if it exists and enabled
        java.util.Optional<Connector> mobileConnector = connectorRepository.findByCode("mobile");

        if (mobileConnector.isPresent() && mobileConnector.get().getEnabled()) {
            boolean mobileAlreadyIncluded = connectorsWithCredentials.stream()
                .anyMatch(c -> "mobile".equalsIgnoreCase(c.getCode()));

            if (!mobileAlreadyIncluded) {
                java.util.List<Connector> result = new java.util.ArrayList<>(connectorsWithCredentials);
                result.add(mobileConnector.get());
                return result;
            }
        }

        return connectorsWithCredentials;
    }
}
