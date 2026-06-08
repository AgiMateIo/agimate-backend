package ru.agimate.controlapi.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.controlapi.database.entities.App;
import ru.agimate.controlapi.database.repositories.AppRepository;
import ru.agimate.controlapi.util.AppKeyUtils;
import ru.agimate.controlapi.util.ParsedAppKey;

import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class AppKeyAuthService {

    private final AppRepository appRepository;

    public Optional<App> validateKey(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return Optional.empty();
        }

        ParsedAppKey parsedKey;
        try {
            parsedKey = AppKeyUtils.parse(apiKey);
        } catch (IllegalArgumentException e) {
            log.debug("Invalid app key format: {}", e.getMessage());
            return Optional.empty();
        }

        if (!AppKeyUtils.verifyChecksum(parsedKey)) {
            log.debug("App key checksum verification failed");
            return Optional.empty();
        }

        Optional<App> keyOpt = appRepository.findActiveKeyByKeyId(parsedKey.keyId());

        if (keyOpt.isEmpty()) {
            return Optional.empty();
        }

        App key = keyOpt.get();
        if (!AppKeyUtils.verifySecret(parsedKey.secret(), key.getKeyHash())) {
            log.debug("App key secret verification failed");
            return Optional.empty();
        }

        return Optional.of(key);
    }
}
