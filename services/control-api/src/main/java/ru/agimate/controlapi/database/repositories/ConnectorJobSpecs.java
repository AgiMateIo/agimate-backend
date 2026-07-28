package ru.agimate.controlapi.database.repositories;

import lombok.experimental.UtilityClass;
import org.springframework.data.jpa.domain.Specification;
import ru.agimate.controlapi.database.entities.ConnectorJob;
import ru.agimate.controlapi.database.enums.ConnectorJobKind;

import java.util.UUID;

@UtilityClass
public class ConnectorJobSpecs {

    public static Specification<ConnectorJob> ownedBy(UUID userId) {
        return (root, query, cb) -> cb.equal(root.get("userId"), userId);
    }

    public static Specification<ConnectorJob> hasConnector(String connectorCode) {
        return (root, query, cb) -> cb.equal(root.get("connectorCode"), connectorCode);
    }

    /** {@code connection_id} is stored as a string (see {@link ConnectorJob#getConnectionId()}). */
    public static Specification<ConnectorJob> hasConnection(String connectionId) {
        return (root, query, cb) -> cb.equal(root.get("connectionId"), connectionId);
    }

    public static Specification<ConnectorJob> hasKind(ConnectorJobKind kind) {
        return (root, query, cb) -> cb.equal(root.get("kind"), kind);
    }
}
