package ru.agimate.userapi.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.common.security.jwt.AgimateUserPrincipal;
import ru.agimate.userapi.controller.dto.request.push.RegisterPushSubscriptionRequest;
import ru.agimate.userapi.controller.dto.request.push.UnregisterPushSubscriptionRequest;
import ru.agimate.userapi.database.entities.PushProvider;
import ru.agimate.userapi.service.push.PushSubscriptionService;

import java.util.UUID;

/**
 * The caller's own push subscriptions. Next to {@code /sessions} on purpose: a subscription belongs
 * to a sign-in, and this is the service that owns sign-ins.
 */
@RestController
@RequestMapping("/push")
@RequiredArgsConstructor
@Tag(name = "Push", description = "Push subscriptions of the current user's devices")
public class PushSubscriptionController {

    private final PushSubscriptionService pushSubscriptionService;

    @Operation(summary = "Register or refresh this device's push subscription",
            description = "Idempotent: the application calls it on every sign-in and on every token "
                    + "rotation. A token already known to another account moves to the caller")
    @PutMapping("/subscriptions")
    public SuccessResponse<String> register(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @Valid @RequestBody RegisterPushSubscriptionRequest request
    ) {
        pushSubscriptionService.register(
                UUID.fromString(principal.id()),
                principal.authSessionId(),
                provider(request.provider()),
                request.token());
        return SuccessResponse.ok("ok");
    }

    @Operation(summary = "Remove this device's push subscription",
            description = "Called on sign-out, before the access tokens are cleared. Idempotent: "
                    + "removing what is not there is a success")
    @DeleteMapping("/subscriptions")
    public SuccessResponse<String> unregister(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @Valid @RequestBody UnregisterPushSubscriptionRequest request
    ) {
        pushSubscriptionService.unregister(UUID.fromString(principal.id()), request.token());
        return SuccessResponse.ok("ok");
    }

    private static PushProvider provider(String raw) {
        return PushProvider.fromCode(raw)
                .orElseThrow(() -> new BadRequestStatusException("Unknown push provider: " + raw));
    }
}
