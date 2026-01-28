package ru.agimate.connectorsapi.controller.manage;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.connectorsapi.controller.manage.dto.response.EventDescriptionResponse;
import ru.agimate.connectorsapi.service.EventDescriptionService;

import java.util.List;

@RestController
@RequestMapping(EventDescriptionManageController.PATH)
@RequiredArgsConstructor
@Tag(name = "Events", description = "Manage event descriptions")
public class EventDescriptionManageController {

    public static final String PATH = "/manage/events";

    private final EventDescriptionService eventDescriptionService;

    @Operation(summary = "Get all event descriptions",
            description = "Retrieve all event descriptions, optionally filtered by event type pattern")
    @GetMapping("/")
    public SuccessResponse<List<EventDescriptionResponse>> getAllEvents(
            @RequestParam(required = false) String event_type_like
    ) {
        if (event_type_like != null && !event_type_like.isBlank()) {
            return SuccessResponse.ok(eventDescriptionService.findByEventTypeLike(event_type_like));
        }
        return SuccessResponse.ok(eventDescriptionService.findAll());
    }
}
