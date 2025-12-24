package ru.agimate.connectorsapi.client;

import ru.agimate.connectorsapi.connector.ConnectorMethod;

import java.util.Map;

public interface ConnectorClient {

    String getConnectorCode();

    Object execute(
            ConnectorMethod method,
            Map<String, String> credentials,
            Map<String, Object> parameters
    );
}
