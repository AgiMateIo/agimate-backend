package ru.agimate.deviceapi.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.deviceapi.database.entities.Agent;
import ru.agimate.deviceapi.database.repositories.AgentRepository;
import ru.agimate.deviceapi.util.AppKeyUtils;
import ru.agimate.deviceapi.util.ParsedAppKey;

import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class AgentKeyAuthService {

    private final AgentRepository agentRepository;

    public Optional<Agent> validateKey(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return Optional.empty();
        }

        ParsedAppKey parsedKey;
        try {
            parsedKey = AppKeyUtils.parse(apiKey);
        } catch (IllegalArgumentException e) {
            log.debug("Invalid agent key format: {}", e.getMessage());
            return Optional.empty();
        }

        if (!AppKeyUtils.verifyChecksum(parsedKey)) {
            log.debug("Agent key checksum verification failed");
            return Optional.empty();
        }

        Optional<Agent> agentOpt = agentRepository.findByKeyId(parsedKey.keyId());

        if (agentOpt.isEmpty()) {
            return Optional.empty();
        }

        Agent agent = agentOpt.get();
        if (!AppKeyUtils.verifySecret(parsedKey.secret(), agent.getKeyHash())) {
            log.debug("Agent key secret verification failed");
            return Optional.empty();
        }

        return Optional.of(agent);
    }
}
