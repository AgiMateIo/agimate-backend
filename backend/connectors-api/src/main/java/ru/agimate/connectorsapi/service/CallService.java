package ru.agimate.connectorsapi.service;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.connectorsapi.client.ConnectorClient;
import ru.agimate.connectorsapi.connector.ConnectorMethod;
import ru.agimate.connectorsapi.connector.ConnectorRegistry;
import ru.agimate.connectorsapi.controller.dto.request.CallMethodRequest;
import ru.agimate.connectorsapi.controller.dto.response.CallResultResponse;
import ru.agimate.connectorsapi.database.entities.Credential;
import ru.agimate.connectorsapi.database.repositories.CredentialRepository;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class CallService {

    private final CredentialRepository credentialRepository;
    private final CredentialService credentialService;
    private final ConnectorRegistry connectorRegistry;
    private final List<ConnectorClient> connectorClients;

    private Map<String, ConnectorClient> clientRegistry;

    @PostConstruct
    public void init() {
        clientRegistry = connectorClients.stream()
                .collect(Collectors.toMap(
                        ConnectorClient::getConnectorCode,
                        Function.identity()
                ));
        log.info("Registered {} connector clients: {}",
                clientRegistry.size(),
                clientRegistry.keySet());
    }

    @Transactional
    public CallResultResponse executeMethod(
            String connectorCode,
            String methodName,
            CallMethodRequest request
    ) {
        // Get credential
        Credential credential = credentialRepository.findByPubIdNotDeleted(request.credentialId())
                .orElseThrow(() -> new NotFoundStatusException("Credential not found"));

        if (!credential.getConnector().getCode().equalsIgnoreCase(connectorCode)) {
            throw new BadRequestStatusException("Credential does not belong to connector: " + connectorCode);
        }

        if (!credential.isActive()) {
            throw new BadRequestStatusException("Credential is disabled");
        }

        // Get method definition
        ConnectorMethod method = connectorRegistry.getMethod(connectorCode, methodName);

        // Get client
        ConnectorClient client = clientRegistry.get(connectorCode.toLowerCase());
        if (client == null) {
            throw new NotFoundStatusException("No client implementation for connector: " + connectorCode);
        }

        // Get decrypted credentials
        Map<String, String> credentials = credentialService.getDecryptedCredentialData(request.credentialId());

        // Execute call
        long startTime = System.currentTimeMillis();
        try {
            Object result = client.execute(method, credentials, request.parameters());
            long duration = System.currentTimeMillis() - startTime;

            // Update last used
            credentialService.updateLastUsedAt(credential.getId());

            log.info("Successfully called {}.{} in {}ms", connectorCode, methodName, duration);
            return new CallResultResponse(true, result, null, duration);
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            log.warn("Failed to call {}.{} in {}ms: {}", connectorCode, methodName, duration, e.getMessage());
            return new CallResultResponse(false, null, e.getMessage(), duration);
        }
    }
}
