package ru.agimate.userapi.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.userapi.controller.dto.request.CreateWaitlistEntryRequest;
import ru.agimate.userapi.controller.dto.response.WaitlistEntryResponse;
import ru.agimate.userapi.service.WaitlistService;

@RestController
@RequestMapping("/waitlist")
@RequiredArgsConstructor
public class WaitlistController {

    private final WaitlistService waitlistService;

    @PostMapping
    public ResponseEntity<SuccessResponse<WaitlistEntryResponse>> createEntry(
            @Valid @RequestBody CreateWaitlistEntryRequest request) {

        var entry = waitlistService.create(request.email(), request.name(), request.message());
        return ResponseEntity.ok(SuccessResponse.ok(WaitlistEntryResponse.from(entry)));
    }
}
