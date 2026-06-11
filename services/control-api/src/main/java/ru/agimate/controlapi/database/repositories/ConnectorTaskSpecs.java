package ru.agimate.controlapi.database.repositories;

import lombok.experimental.UtilityClass;
import org.springframework.data.jpa.domain.Specification;
import ru.agimate.controlapi.database.entities.ConnectorTask;
import ru.agimate.controlapi.database.enums.ConnectorTaskKind;

import java.util.UUID;

@UtilityClass
public class ConnectorTaskSpecs {

    public static Specification<ConnectorTask> ownedBy(UUID userId) {
        return (root, query, cb) -> cb.equal(root.get("userId"), userId);
    }

    public static Specification<ConnectorTask> hasConnector(String connectorCode) {
        return (root, query, cb) -> cb.equal(root.get("connectorCode"), connectorCode);
    }

    public static Specification<ConnectorTask> hasKind(ConnectorTaskKind kind) {
        return (root, query, cb) -> cb.equal(root.get("kind"), kind);
    }
}
