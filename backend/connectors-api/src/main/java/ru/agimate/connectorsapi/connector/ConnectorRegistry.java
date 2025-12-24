package ru.agimate.connectorsapi.connector;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.agimate.common.rest.error.NotFoundStatusException;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@Slf4j
public class ConnectorRegistry {

    private final List<ConnectorDefinition> definitions;
    private Map<String, ConnectorDefinition> registry;

    @PostConstruct
    public void init() {
        registry = definitions.stream()
                .collect(Collectors.toMap(
                        ConnectorDefinition::getConnectorCode,
                        Function.identity()
                ));
        log.info("Registered {} connector definitions: {}",
                registry.size(),
                registry.keySet());
    }

    public Optional<ConnectorDefinition> findDefinition(String connectorCode) {
        return Optional.ofNullable(registry.get(connectorCode.toLowerCase()));
    }

    public ConnectorDefinition getDefinition(String connectorCode) {
        return findDefinition(connectorCode)
                .orElseThrow(() -> new NotFoundStatusException(
                        "Connector definition not found: " + connectorCode));
    }

    public List<ConnectorMethod> getMethods(String connectorCode) {
        return getDefinition(connectorCode).getMethods();
    }

    public ConnectorMethod getMethod(String connectorCode, String methodName) {
        return getMethods(connectorCode).stream()
                .filter(m -> m.name().equals(methodName))
                .findFirst()
                .orElseThrow(() -> new NotFoundStatusException(
                        "Method not found: " + methodName + " for connector: " + connectorCode));
    }

    public List<String> getRequiredCredentialFields(String connectorCode) {
        return getDefinition(connectorCode).getRequiredCredentialFields();
    }

    public boolean hasDefinition(String connectorCode) {
        return registry.containsKey(connectorCode.toLowerCase());
    }
}
