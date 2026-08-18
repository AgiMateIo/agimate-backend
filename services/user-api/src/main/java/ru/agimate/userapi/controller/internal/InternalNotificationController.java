package ru.agimate.userapi.controller.internal;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.userapi.controller.dto.request.push.NotificationRequest;
import ru.agimate.userapi.service.push.PushMessage;
import ru.agimate.userapi.service.push.PushSender;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * The relay: another service of ours reports something worth telling a person about, and this one
 * knows which devices that person has. The payload is passed through untouched — see
 * docs/decisions/push-notifications.md.
 *
 * <p>Answers as soon as the request is accepted, not when the transports have answered: delivery is
 * best-effort by nature, and the caller is in the middle of its own work.
 */
@RestController
@RequestMapping(InternalNotificationController.PATH)
@RequiredArgsConstructor
@Tag(name = "Internal notifications", description = "Service-to-service delivery of push notifications")
public class InternalNotificationController {

    public static final String PATH = "/internal/notifications";

    /** The transports cap a message at 4 KB; refusing here beats a delivery that silently fails. */
    private static final int MAX_PAYLOAD_BYTES = 4096;

    private final PushSender pushSender;

    @Operation(summary = "Notify every device of a user",
            description = "Accepted and delivered asynchronously; the response says nothing about delivery")
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public SuccessResponse<String> notify(@Valid @RequestBody NotificationRequest request) {
        requireWithinSizeLimit(request);

        Duration ttl = request.ttlSeconds() != null ? Duration.ofSeconds(request.ttlSeconds()) : null;
        pushSender.sendToUser(request.userId(), new PushMessage(request.data(), ttl));

        return SuccessResponse.ok("accepted");
    }

    /**
     * Measured on the payload alone: what the transport adds around it is small and fixed, while
     * what makes a notification too big is always the content — a preview nobody shortened.
     */
    private static void requireWithinSizeLimit(NotificationRequest request) {
        int size = request.data().entrySet().stream()
                .mapToInt(entry -> utf8Length(entry.getKey()) + utf8Length(entry.getValue()))
                .sum();
        if (size > MAX_PAYLOAD_BYTES) {
            throw new BadRequestStatusException(
                    "Notification payload is over " + MAX_PAYLOAD_BYTES + " bytes: " + size);
        }
    }

    /** Bytes, not characters: a preview in Cyrillic is twice its length on the wire. */
    private static int utf8Length(String value) {
        return value == null ? 0 : value.getBytes(StandardCharsets.UTF_8).length;
    }
}
