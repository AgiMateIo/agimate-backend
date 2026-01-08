package ru.agimate.connectorsapi.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.common.security.SecurityUtils;
import ru.agimate.common.util.JsonUtils;
import ru.agimate.connectorsapi.client.ConnectorClient;
import ru.agimate.connectorsapi.connector.ConnectorMethod;
import ru.agimate.connectorsapi.controller.api.connectors.wildberries.dto.WildberriesGetCardsRequest;
import ru.agimate.connectorsapi.controller.api.connectors.wildberries.dto.WildberriesGetCardsResponse;
import ru.agimate.connectorsapi.controller.api.connectors.wildberries.dto.WildberriesGetOrdersRequest;
import ru.agimate.connectorsapi.controller.api.connectors.wildberries.dto.WildberriesGetOrdersResponse;
import ru.agimate.connectorsapi.database.entities.Credential;
import ru.agimate.connectorsapi.database.repositories.CredentialRepository;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class WildberriesCallService {

    private static final String CONNECTOR_CODE = "wildberries";

    private final CredentialRepository credentialRepository;
    private final CredentialService credentialService;
    private final List<ConnectorClient> connectorClients;
    private final ObjectMapper objectMapper = JsonUtils.MAPPER;

    @Transactional
    public WildberriesGetCardsResponse getCards(UUID credentialId, WildberriesGetCardsRequest request) {
        // Create method definition manually
        ConnectorMethod method = new ConnectorMethod(
                "getCards",
                "Получить карточки товаров",
                "Возвращает список карточек товаров продавца",
                "POST",
                "/content/v2/get/cards/list",
                null, // category not needed for execution
                List.of() // parameters not needed for execution
        );

        @SuppressWarnings("unchecked")
        Map<String, Object> parameters = objectMapper.convertValue(request, Map.class);

        return executeMethod("getCards", credentialId, method, parameters, WildberriesGetCardsResponse.class);
    }

    @Transactional
    public WildberriesGetOrdersResponse getOrders(UUID credentialId, WildberriesGetOrdersRequest request) {
        // Create method definition manually
        ConnectorMethod method = new ConnectorMethod(
                "getOrders",
                "Новые заказы",
                "Возвращает список новых заказов",
                "GET",
                "/api/v3/orders/new",
                null, // category not needed for execution
                List.of() // parameters not needed for execution
        );

        @SuppressWarnings("unchecked")
        Map<String, Object> parameters = objectMapper.convertValue(request, Map.class);

        return executeMethod("getOrders", credentialId, method, parameters, WildberriesGetOrdersResponse.class);
    }

    private <T> T executeMethod(
            String methodName,
            UUID credentialId,
            ConnectorMethod method,
            Map<String, Object> parameters,
            Class<T> responseType
    ) {
        // Get authenticated user
        var apiKeyUserPubId = SecurityUtils.getApiKeyUserPubId();

        // Get and validate credential
        Credential credential = credentialRepository.findByPubIdAndUserPubIdNotDeleted(credentialId, apiKeyUserPubId)
                .orElseThrow(() -> new NotFoundStatusException("Credential not found or access denied"));

        if (!credential.getConnector().getCode().equalsIgnoreCase(CONNECTOR_CODE)) {
            throw new BadRequestStatusException("Credential does not belong to connector: " + CONNECTOR_CODE);
        }

        if (!credential.isActive()) {
            throw new BadRequestStatusException("Credential is disabled");
        }

        // Get client
        ConnectorClient client = connectorClients.stream()
                .filter(c -> c.getConnectorCode().equalsIgnoreCase(CONNECTOR_CODE))
                .findFirst()
                .orElseThrow(() -> new NotFoundStatusException("No client implementation for connector: " + CONNECTOR_CODE));

        // Get decrypted credentials
        Map<String, String> credentials = credentialService.getDecryptedCredentialData(credentialId);

        // Execute call
        long startTime = System.currentTimeMillis();
        try {
            Object result = client.execute(method, credentials, parameters);
            long duration = System.currentTimeMillis() - startTime;

            // Update last used
            credentialService.updateLastUsedAt(credential.getId());

            log.info("Successfully called {}.{} in {}ms", CONNECTOR_CODE, methodName, duration);

            // Convert result to response type
            return objectMapper.convertValue(
                    Map.of("result", result, "durationMs", duration),
                    responseType
            );
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            log.error("Failed to call {}.{} in {}ms: {}", CONNECTOR_CODE, methodName, duration, e.getMessage(), e);
            throw new BadRequestStatusException("Connector call failed: " + e.getMessage());
        }
    }
}
