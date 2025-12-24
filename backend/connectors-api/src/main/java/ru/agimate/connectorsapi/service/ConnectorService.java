package ru.agimate.connectorsapi.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.connectorsapi.connector.ConnectorRegistry;
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
    private final ConnectorRegistry connectorRegistry;

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

    public boolean hasMethodDefinitions(String connectorCode) {
        return connectorRegistry.hasDefinition(connectorCode);
    }

    public List<String> getRequiredCredentialFields(String connectorCode) {
        return connectorRegistry.getRequiredCredentialFields(connectorCode);
    }
}
