package ru.agimate.deviceapi.abac;

import lombok.experimental.UtilityClass;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.common.rest.error.ForbiddenStatusException;

import java.util.UUID;

@UtilityClass
public class PolicyValidationUtils {

    public void validateOwnership(UUID policyUserPubId, UUID requestUserPubId) {
        if (!policyUserPubId.equals(requestUserPubId)) {
            throw new ForbiddenStatusException("Access denied");
        }
    }

    public void validateConstraints(String connectorCode, String connectorIdentity, String fieldName, String fieldLabel) {
        if (connectorIdentity != null && connectorCode == null) {
            throw new BadRequestStatusException("connector_identity requires connector_code to be set");
        }
        if (fieldName != null && connectorCode == null) {
            throw new BadRequestStatusException(fieldLabel + " requires connector_code to be set");
        }
    }
}
