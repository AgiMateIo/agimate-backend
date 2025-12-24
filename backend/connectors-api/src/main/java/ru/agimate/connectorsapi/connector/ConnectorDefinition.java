package ru.agimate.connectorsapi.connector;

import java.util.List;

public interface ConnectorDefinition {

    String getConnectorCode();

    List<String> getRequiredCredentialFields();

    List<ConnectorMethod> getMethods();
}
