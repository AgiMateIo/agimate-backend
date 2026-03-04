package ru.agimate.deviceapi.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.deviceapi.database.entities.App;
import ru.agimate.deviceapi.database.repositories.AppRepository;
import ru.agimate.deviceapi.util.ConnectorKeyUtils;
import ru.agimate.deviceapi.util.ParsedConnectorKey;

import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class ConnectorKeyAuthService {

    private final AppRepository appRepository;

    public Optional<App> validateKey(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return Optional.empty();
        }

        ParsedConnectorKey parsedKey;
        try {
            parsedKey = ConnectorKeyUtils.parse(apiKey);
        } catch (IllegalArgumentException e) {
            log.debug("Invalid connector key format: {}", e.getMessage());
            return Optional.empty();
        }

        if (!ConnectorKeyUtils.verifyChecksum(parsedKey)) {
            log.debug("Connector key checksum verification failed");
            return Optional.empty();
        }

        Optional<App> keyOpt = appRepository.findActiveKeyByKeyId(parsedKey.keyId());

        if (keyOpt.isEmpty()) {
            return Optional.empty();
        }

        App key = keyOpt.get();
        if (!ConnectorKeyUtils.verifySecret(parsedKey.secret(), key.getKeyHash())) {
            log.debug("Connector key secret verification failed");
            return Optional.empty();
        }

        return Optional.of(key);
    }
}
