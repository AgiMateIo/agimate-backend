package ru.agimate.deviceapi.service.trigger;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.deviceapi.controller.manage.dto.IssueProbeResponse;
import ru.agimate.deviceapi.controller.manage.dto.TriggerLogResponse;
import ru.agimate.deviceapi.database.repositories.TriggerLogRepository;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TriggerLogProbeService {

    public static final String PROBE_PREFIX = "agm-probe-";
    public static final String BLOCK_PREFIX = PROBE_PREFIX + "block-";
    public static final String PASS_PREFIX = PROBE_PREFIX + "pass-";

    private static final Pattern PROBE_CODE_PATTERN = Pattern.compile("^agm-probe-(block|pass)-[a-z0-9]{10}$");
    private static final String SUFFIX_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final int SUFFIX_LENGTH = 10;

    private final SecureRandom random = new SecureRandom();
    private final TriggerLogRepository triggerLogRepository;

    public IssueProbeResponse issue(Boolean blockDelivery) {
        boolean block = blockDelivery == null || blockDelivery;
        String prefix = block ? BLOCK_PREFIX : PASS_PREFIX;
        String code = prefix + randomSuffix();
        return new IssueProbeResponse(code, LocalDateTime.now());
    }

    public TriggerLogResponse match(UUID userId, String code, LocalDateTime since) {
        if (code == null || !PROBE_CODE_PATTERN.matcher(code).matches()) {
            throw new BadRequestStatusException("Invalid probe code format");
        }
        if (since == null) {
            throw new BadRequestStatusException("Parameter 'since' is required");
        }
        return triggerLogRepository.findFirstByUserAndPayloadContaining(userId, code, since)
                .map(TriggerLogResponse::from)
                .orElseThrow(() -> new NotFoundStatusException("No matching trigger log yet"));
    }

    private String randomSuffix() {
        StringBuilder sb = new StringBuilder(SUFFIX_LENGTH);
        for (int i = 0; i < SUFFIX_LENGTH; i++) {
            sb.append(SUFFIX_ALPHABET.charAt(random.nextInt(SUFFIX_ALPHABET.length())));
        }
        return sb.toString();
    }
}
